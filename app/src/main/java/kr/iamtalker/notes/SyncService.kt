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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "동기화", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(R.mipmap.ic_launcher).setContentTitle("iamtalkernote 동기화")
            .setContentText("컴퓨터와 노트를 맞추는 중입니다").setOngoing(true).build()
        ServiceCompat.startForeground(this, 1, n, if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (job?.isActive != true) job = scope.launch { loop() }
        return START_STICKY
    }

    private suspend fun loop() {
        val ctx = applicationContext
        val prefs = ctx.getSharedPreferences("settings", 0)
        val api = EmbeddedSync.api(ctx)
        while (scope.isActive) {
            try {
                if (!EmbeddedSync.alive()) EmbeddedSync.start(ctx)
                val tree = prefs.getString("tree", null)?.let(Uri::parse)
                val pc = prefs.getString("pcId", "").orEmpty()
                EmbeddedSync.report = if (tree == null) ForkSync.Report(error = "노트 폴더를 먼저 고르세요")
                else ForkSync.tick(ctx, api, pc, tree)
            } catch (e: Exception) {
                // 시작 직후에는 아직 응답하지 않는 게 정상이다
                EmbeddedSync.report = ForkSync.Report(error = if (EmbeddedSync.alive()) "동기화 엔진을 시작하는 중…" else ForkSync.friendly(e))
            }
            delay(5000)
        }
    }

    override fun onDestroy() {
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
