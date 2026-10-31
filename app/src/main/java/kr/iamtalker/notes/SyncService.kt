package kr.iamtalker.notes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 내장 Syncthing 을 살려 두는 포그라운드 서비스(알림이 떠야 시스템이 앱을 정리해도 같이 죽지 않는다).
 *  5초마다 컴퓨터가 보낸 폴더를 받아 정해진 위치에 두는 일(ForkSync.tick)도 여기서 한다 — 앱 화면이 꺼져 있어도 돈다. */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var cpu: android.os.PowerManager.WakeLock? = null
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null

    /** 받을 것이 남은 동안만 CPU 와 와이파이를 깨워 둔다. 화면이 꺼진 채 몇 시간 두면 CPU 가 잠들어 작은 파일 수만 개가 거의 안 받아지던 문제(read timeout)를 막는다.
     *  15분짜리로 잡고 매번 새로 잡으므로, 어떤 일이 있어도 15분 넘게 새지 않는다 */
    private fun hold(on: Boolean) {
        runCatching {
            if (on) {
                if (cpu == null) cpu = getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "iamtalkernote:sync").also { it.setReferenceCounted(false) }
                if (wifi == null) wifi = getApplicationContext().getSystemService(android.net.wifi.WifiManager::class.java)
                    .createWifiLock(if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "iamtalkernote:sync").also { it.setReferenceCounted(false) }
                cpu?.acquire(15 * 60_000L)
                if (wifi?.isHeld != true) wifi?.acquire()
            } else {
                if (cpu?.isHeld == true) cpu?.release()
                if (wifi?.isHeld == true) wifi?.release()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "동기화", NotificationManager.IMPORTANCE_LOW))
        val n = notice("컴퓨터와 노트를 맞추는 중입니다", 0, 0)
        ServiceCompat.startForeground(this, 1, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (job?.isActive != true) job = scope.launch { loop() }
        return START_STICKY
    }

    /** 작업표시줄 알림(누르면 설정 → 동기화). all>0 이면 진행 막대도 보인다 */
    private fun notice(text: String, have: Int, all: Int): Notification {
        val b = Notification.Builder(this, CHANNEL).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("iamtalkernote 동기화")
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(android.app.PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra(OPEN_SYNC, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
        if (all > 0 && have < all) b.setProgress(all, have, false)
        return b.build()
    }

    private suspend fun loop() {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("settings", 0)
        val api = EmbeddedSync.api(ctx)
        var fails = 0
        var lastProgress = 0L
        var overall: Overall? = null
        val fileEta = Eta()
        val syncEta = Eta()   // 폰이 센 값이 없을 때 Syncthing 이 알려 주는 남은 파일 수로 속도를 잰다
        var etaSec = -1L
        var lastText = ""
        while (scope.isActive) {
            try {
                if (!EmbeddedSync.alive()) EmbeddedSync.start(ctx)
                val tree = prefs.getString("tree", null)?.let(Uri::parse)
                val pc = prefs.getString("pcId", "").orEmpty()
                EmbeddedSync.report = if (tree == null) ForkSync.Report(error = "노트 폴더를 먼저 고르세요")
                else ForkSync.tick(ctx, api, pc, tree)   // 단계별 가림은 이제 컴퓨터가 맡는다(폰의 긴 무시 목록이 느렸다)
                fails = 0
                // 2분에 한 번: 폰에 실제로 있는 글·그림 수를 세어 컴퓨터에 알린다(컴퓨터 화면의 진행 표시용)
                if (tree != null && System.currentTimeMillis() - lastProgress > 120_000) {
                    lastProgress = System.currentTimeMillis()
                    runCatching {
                        ForkSync.treePath(tree)?.let { vp ->
                            val vf = java.io.File(vp)
                            val counted = Overall.count(vf)
                            if (counted == null) EmbeddedSync.note(ctx, "전체 진행을 셀 수 없음: " + Overall.why(vf))
                            if (counted != null) {
                                if (PhoneProgress.write(vp, counted)) api.scanSettingsFolder()      // 컴퓨터에 바로 가게 설정 폴더를 즉시 검사
                                else EmbeddedSync.note(ctx, "센 진행을 컴퓨터에 알리지 못함: 설정 폴더 없음(${SyncSettings.dir(vp).path})")
                            }
                            counted?.let { o ->
                                overall = o
                                val rate = fileEta.update(System.currentTimeMillis(), (o.all - o.have).toLong())   // 남은 파일 수가 줄어드는 속도
                                etaSec = Eta.seconds((o.all - o.have).toLong(), rate)
                            }
                        }
                    }
                }
                // 알림: 동기화 상태 N/M (P%) · 남은 시간(글자가 바뀔 때만 갱신)
                val r = EmbeddedSync.report
                val o = overall
                // 폰이 센 전체 개수가 있으면 그것을, 없으면 Syncthing 이 알려 주는 값(공개된 파일 기준)으로라도 진행률을 보인다
                val f = r?.folders?.firstOrNull()
                val have: Int; val all: Int; val eta: Long
                if (o != null) { have = o.have; all = o.all; eta = etaSec }
                else if (f != null && f.totalFiles > 0) {
                    have = (f.totalFiles - f.needFiles).toInt(); all = f.totalFiles.toInt()
                    val rate = syncEta.update(System.currentTimeMillis(), f.needFiles)
                    eta = Eta.seconds(f.needFiles, rate)
                } else { have = 0; all = 0; eta = -1L }
                hold(r?.connected == true && all > 0 && have < all)
                val txt = SyncNotice.text(r?.connected == true, r?.error, have, all, eta)
                if (txt != lastText) {
                    lastText = txt
                    runCatching { getSystemService(NotificationManager::class.java).notify(1, notice(txt, have, all)) }
                }
            } catch (e: Exception) {
                // 시작 직후에는 아직 응답하지 않는 게 정상이다. 1분(12번) 넘게 응답이 없으면 엔진이 멈춘 것이니 새로 시작한다
                fails++
                val why = ForkSync.friendly(e)
                if (EmbeddedSync.alive() && fails >= 12) {
                    EmbeddedSync.note(ctx, "엔진이 1분 넘게 응답하지 않아 다시 시작합니다 (마지막 오류: $why)")
                    EmbeddedSync.stop(); fails = 0
                }
                EmbeddedSync.report = ForkSync.Report(error = if (EmbeddedSync.alive()) "동기화 엔진을 시작하는 중… ($why)" else why)
            }
            delay(5000)
        }
    }

    override fun onDestroy() {
        hold(false)
        scope.cancel()
        EmbeddedSync.stop()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "sync"
        fun start(ctx: Context) {
            EmbeddedSync.setWanted(ctx, true)
            val i = Intent(ctx, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) {
            EmbeddedSync.setWanted(ctx, false)
            ctx.stopService(Intent(ctx, SyncService::class.java))
        }
    }
}

/** 동기화 알림을 눌러 앱을 열 때 붙이는 표시(설정 → 동기화 화면으로 간다) */
const val OPEN_SYNC = "openSync"

/** 작업표시줄 알림에 보일 글: 동기화 상태 N/M (P%) · 남은 시간 */
object SyncNotice {
    fun text(connected: Boolean, error: String?, have: Int, all: Int, etaSec: Long): String {
        if (error != null) return "⚠ $error"
        val head = when {
            all <= 0 -> if (connected) "컴퓨터와 노트를 맞추는 중입니다" else "컴퓨터와 연결 대기 중"
            have >= all -> "동기화 완료 · 전체 %,d개".format(all)
            else -> "동기화 %,d/%,d (%d%%)".format(have, all, (have.toLong() * 100 / all).toInt()) +
                (if (etaSec > 0) " · ${Eta.text(etaSec)} 남음" else "") + (if (!connected) " · 연결 대기 중" else "")
        }
        return head
    }
}
