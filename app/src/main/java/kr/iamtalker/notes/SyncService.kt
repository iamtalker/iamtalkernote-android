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
 *  전송 중 5초·대기 중 30초마다 컴퓨터가 보낸 폴더를 받아 정해진 위치에 두는 일(ForkSync.tick)도 여기서 한다 — 앱 화면이 꺼져 있어도 돈다. */
class SyncService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var cpu: android.os.PowerManager.WakeLock? = null
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null

    /** 받을 것이 남은 동안만 CPU 와 와이파이를 깨워 둔다. 화면이 꺼진 채 몇 시간 두면 CPU 가 잠들어 작은 파일 수만 개가 거의 안 받아지던 문제(read timeout)를 막는다.
     *  CPU 잠금은 1분 제한으로 갱신하고 오류·정체·완료 시 해제한다 */
    private fun hold(on: Boolean) {
        runCatching {
            if (on) {
                if (cpu == null) cpu = getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "iamtalkernote:sync").also { it.setReferenceCounted(false) }
                if (wifi == null) wifi = getApplicationContext().getSystemService(android.net.wifi.WifiManager::class.java)
                    .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "iamtalkernote:sync").also { it.setReferenceCounted(false) }
                cpu?.acquire(60_000L)
                if (wifi?.isHeld != true) wifi?.acquire()
            } else {
                if (cpu?.isHeld == true) cpu?.release()
                if (wifi?.isHeld == true) wifi?.release()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private val busyExt = HashSet<String>()   // 받을 것이 남아 있던 외부 폴더(끝났을 때 알리려고 기억)
    @Volatile private var foreground = false   // 지금 상시 알림(포그라운드)으로 떠 있는가
    private var doneTicks = 0                  // 연달아 '다 받음'으로 확인된 횟수

    /** 외부 폴더(음악·PDF 등)가 다 받아지면 한 번 알린다. 파일 관리 앱에서는 받는 중인 파일도 크기가 다 차 보여 끝난 줄 착각하기 쉬워서, 앱이 끝났다고 직접 알려 준다 */
    private fun notifyExtDone(r: ForkSync.Report?) {
        r ?: return
        for (f in r.folders) {
            if (!f.label.startsWith("외부 폴더")) continue
            if (f.needFiles > 0L) { busyExt.add(f.label); continue }
            if (f.state == "idle" && f.totalFiles > 0L && f.failed == 0 && busyExt.remove(f.label)) {
                val nm = getSystemService(NotificationManager::class.java)
                if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, tr("받기 끝남"), NotificationManager.IMPORTANCE_DEFAULT))
                val name = f.label.removePrefix("외부 폴더 · ")
                val text = trf("%s: 파일 %s개를 모두 받았습니다", name, f.totalFiles) + (if (f.path.isNotEmpty()) "\n" + trf("받은 위치: %s", f.path) else "")
                val b = Notification.Builder(this, CHANNEL_DONE).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(tr("받기 끝남"))
                    .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
                runCatching { nm.notify(2000 + (f.label.hashCode() and 0xFFF), b.build()) }
                EmbeddedSync.note(this, "외부 폴더 받기 끝남: " + text.replace("\n", " · "))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, tr("동기화"), NotificationManager.IMPORTANCE_LOW))
        val n = notice(tr("컴퓨터와 노트를 맞추는 중입니다"), 0, 0)
        ServiceCompat.startForeground(this, 1, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        foreground = true
        doneTicks = 0
        if (job?.isActive != true) job = scope.launch { loop() }
        return START_STICKY
    }

    /** 작업표시줄 알림(누르면 설정 → 동기화). all>0 이면 진행 막대도 보인다 */
    private fun notice(text: String, have: Int, all: Int): Notification {
        val b = Notification.Builder(this, CHANNEL).setSmallIcon(R.mipmap.ic_launcher).setContentTitle(tr("iamtalkernote 동기화"))
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
        val power = SyncPower()
        var activeTransfer = false
        var failureSince: Long? = null
        var lastProgress = 0L
        var overall: Overall? = null
        val fileEta = Eta(300_000, 60_000)   // 합친 파일 개수가 줄어드는 속도: 최근 5분(폰이 센 값은 2분마다 갱신)
        val syncEta = Eta()   // 폰이 센 값이 없을 때 Syncthing 이 알려 주는 남은 파일 수로 속도를 잰다
        var etaSec = -1L
        var lastText = ""
        var cleanedImported = false   // 예전 '가져온 문서 요약' 폴더를 한 번만 치운다
        var downSince = 0L      // 컴퓨터와 연결이 끊긴 채로 지내기 시작한 때
        var lastRecover = 0L    // 마지막으로 기기를 다시 연결시킨 때
        var lastRestart = 0L    // 마지막으로 엔진을 다시 시작한 때
        while (scope.isActive) {
            try {
                if (!EmbeddedSync.alive()) EmbeddedSync.start(ctx)
                if (prefs.getBoolean("resyncFolders", false)) {
                    // 노트 폴더 위치를 바꿨다: 엔진에 등록된 폴더(파일은 지워지지 않음)를 지워, 컴퓨터가 다시 보내는 공유를 새 경로로 받게 한다
                    val ok = runCatching {
                        api.folders().map { it.getString("id") }.filter { it.startsWith(ForkSync.ID_PREFIX) }.forEach { api.removeFolder(it) }
                        val me = api.myId()
                        api.deviceIds().filter { it != me }.forEach { api.reconnect(it) }   // 컴퓨터가 공유 목록을 다시 보내 새 요청이 오게 한다
                    }.isSuccess
                    if (ok) { prefs.edit().remove("resyncFolders").apply(); EmbeddedSync.note(ctx, "노트 폴더 위치가 바뀌어 동기화 폴더 등록을 지우고 새 경로로 다시 받습니다") }
                }
                val tree = prefs.getString("tree", null)?.let(Uri::parse)
                val pc = prefs.getString("pcId", "").orEmpty()
                if (!cleanedImported && tree != null) {
                    cleanedImported = true
                    runCatching { ForkSync.treePath(tree)?.let { ForkSync.removeOldImported(java.io.File(it)) } }
                }
                runCatching { notifyExtDone(EmbeddedSync.report) }
                runCatching {
                    ForkSync.cleanOldExt(ctx, api)?.let { msg ->
                        EmbeddedSync.note(ctx, msg)
                        android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show() }
                    }
                }
                val tickT = System.nanoTime()
                EmbeddedSync.report = if (tree == null) ForkSync.Report(error = tr("노트 폴더를 먼저 고르세요"))
                else ForkSync.tick(ctx, api, pc, tree)   // 단계별 가림은 이제 컴퓨터가 맡는다(폰의 긴 무시 목록이 느렸다)
                activeTransfer = power.active(EmbeddedSync.report, android.os.SystemClock.elapsedRealtime())
                hold(activeTransfer)
                if (activeTransfer) SyncDiag.sample(ctx, api, pc, (System.nanoTime() - tickT) / 1_000_000)
                failureSince = null
                // 전송 중 2분·대기 중 15분에 한 번: 폰에 실제로 있는 글·그림 수를 세어 컴퓨터에 알린다(컴퓨터 화면의 진행 표시용)
                if (tree != null && System.currentTimeMillis() - lastProgress > (if (activeTransfer) 120_000L else 900_000L)) {
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
                                EmbeddedSync.fileRate = rate
                            }
                        }
                    }
                }
                // 알림: 동기화 상태 N/M (P%) · 남은 시간(글자가 바뀔 때만 갱신)
                val r = EmbeddedSync.report
                // 연결이 계속 안 되면 스스로 되살린다(전엔 폰에서 동기화를 껐다 켜야 다시 시작됐다): 1분 반 넘게 → 기기를 다시 연결, 5분 넘게 → 엔진을 다시 시작
                val nowMs = System.currentTimeMillis()
                if (r == null || r.connected || tree == null || r.error != null) downSince = 0L
                else {
                    if (downSince == 0L) downSince = nowMs
                    val down = nowMs - downSince
                    val hasPeer = runCatching { api.deviceIds().size > 1 }.getOrDefault(false)
                    if (hasPeer && down > 90_000 && nowMs - lastRecover > 120_000) {
                        lastRecover = nowMs
                        EmbeddedSync.note(ctx, "컴퓨터와 ${down / 1000}초째 연결이 안 돼 기기를 다시 연결합니다")
                        EmbeddedSync.refreshNow(ctx)
                    }
                    if (hasPeer && down > 300_000 && nowMs - lastRestart > 600_000) {
                        lastRestart = nowMs
                        EmbeddedSync.note(ctx, "컴퓨터와 ${down / 1000}초째 연결이 안 돼 엔진을 다시 시작합니다")
                        EmbeddedSync.stop(); downSince = nowMs
                    }
                }
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
                // Power follows live engine progress, not the cached whole-vault count.
                val txt = SyncNotice.text(r?.connected == true, r?.error ?: r?.blocked?.takeIf { it.isNotEmpty() }?.let { tr("컴퓨터와 규칙이 달라 멈춘 폴더가 있습니다 — 설정 → 동기화에서 확인하세요") }, have, all, eta)
                // 다 받았으면(몇 번 연달아 확인한 뒤) 알림을 내리고, 받을 것이 다시 생기면 올린다
                val complete = SyncNotice.complete(r, have, all)
                doneTicks = if (complete) doneTicks + 1 else 0
                if (foreground && doneTicks >= DONE_TICKS) {
                    foreground = false
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    EmbeddedSync.note(ctx, "동기화가 끝나 알림을 내렸습니다")
                } else if (!foreground && !complete && doneTicks == 0) {
                    // 백그라운드에서 다시 앞으로 올리는 것은 시스템이 막을 수 있다: 막히면 알림 없이 계속 받는다(앱을 열면 다시 올라온다)
                    foreground = runCatching {
                        ServiceCompat.startForeground(this, 1, notice(txt, have, all), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
                    }.isSuccess
                    lastText = txt
                }
                if (foreground && txt != lastText) {
                    lastText = txt
                    runCatching { getSystemService(NotificationManager::class.java).notify(1, notice(txt, have, all)) }
                }
            } catch (e: Exception) {
                // 확인 주기와 무관하게 1분 넘게 응답이 없으면 엔진을 다시 시작한다
                hold(false)
                activeTransfer = false
                power.active(null, android.os.SystemClock.elapsedRealtime())
                val failureNow = android.os.SystemClock.elapsedRealtime()
                if (failureSince == null) failureSince = failureNow
                val why = ForkSync.friendly(e)
                if (EmbeddedSync.alive() && failureNow - (failureSince ?: failureNow) >= 60_000L) {
                    EmbeddedSync.note(ctx, "엔진이 1분 넘게 응답하지 않아 다시 시작합니다 (마지막 오류: $why)")
                    EmbeddedSync.stop(); failureSince = null
                }
                EmbeddedSync.report = ForkSync.Report(error = if (EmbeddedSync.alive()) trf("동기화 엔진을 시작하는 중… (%s)", why) else why)
            }
            val idleMs = prefs.getLong(SyncPower.PREF_IDLE_SEC, SyncPower.DEFAULT_IDLE_MS / 1000L) * 1000L
            delay(power.pollMs(activeTransfer, foreground && doneTicks in 1 until DONE_TICKS, idleMs))
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
        private const val CHANNEL_DONE = "done"
        private const val DONE_TICKS = 3   // 5초 간격으로 3번(약 15초) 연달아 다 받음이면 알림을 내린다
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
    /** 알림을 내려도 되는 '다 받음': 컴퓨터와 연결돼 있고 오류가 없고, 노트 전체를 다 받았고, 모든 폴더(외부 폴더 포함)에 받을 파일이 없다 */
    fun complete(r: ForkSync.Report?, have: Int, all: Int): Boolean {
        r ?: return false
        if (r.blocked.isNotEmpty()) return false   // 컴퓨터와 규칙이 달라 멈춘 폴더가 있으면 끝난 것이 아니다(알림으로 알린다)
        val gone = r.gone.map { it.first }.toSet()   // 컴퓨터가 더는 공유하지 않는 폴더는 앞으로도 받을 수 없으니 판정에서 뺀다
        return r.connected && r.error == null && all > 0 && have >= all &&
            // 받을 파일·용량이 0이면 상태가 syncing·scanning 이어도 끝난 것으로 본다(큰 폴더는 받은 뒤에도 한참 상태가 안 바뀐다). 오류 상태만 제외
            r.folders.filter { it.id !in gone }.all { it.needFiles == 0L && it.needBytes == 0L && it.upNeed == 0L && it.state != "error" }
    }

    fun text(connected: Boolean, error: String?, have: Int, all: Int, etaSec: Long): String {
        if (error != null) return "⚠ $error"
        val head = when {
            all <= 0 -> if (connected) tr("컴퓨터와 노트를 맞추는 중입니다") else tr("컴퓨터와 연결 대기 중")
            have >= all -> tr("동기화 완료 · 전체 %,d개").format(all)
            else -> tr("동기화 %,d/%,d (%d%%)").format(have, all, (have.toLong() * 100 / all).toInt()) +
                (if (etaSec > 0) " · ${Eta.text(etaSec)} 남음" else "") + (if (!connected) tr(" · 연결 대기 중") else "")
        }
        return head
    }
}
