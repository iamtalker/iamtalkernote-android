package kr.iamtalker.notes

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 앱 자동 업데이트: GitHub 최신 릴리스를 확인하고, 누르면 받아서 설치 화면을 연다(안드로이드 규칙상 마지막 '설치' 확인은 한 번 눌러야 한다) */
object Updater {
    const val REPO = "iamtalker/iamtalkernote-android"
    const val ASSET = "iamtalkernote-android.apk"
    const val RESULT_ACTION = "kr.iamtalker.notes.INSTALL_RESULT"
    private const val CHECK_EVERY_MS = 6 * 3600_000L

    data class Info(val version: String, val url: String, val notes: String)

    var available by mutableStateOf<Info?>(null)      // 새 버전이 있으면
    var status by mutableStateOf("")                   // 받는 중 45% / 설치 확인 중 / 실패 이유
    var busy by mutableStateOf(false)
    var progress by mutableStateOf(-1f)                // 0..1 받은 비율, 음수면 얼마나 걸릴지 모르는 단계(설치 준비)
    var checkedMsg by mutableStateOf("")               // 마지막 확인 결과(설정 화면용)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", 0)
    fun autoCheck(ctx: Context) = prefs(ctx).getBoolean("autoUpdate", true)
    fun setAutoCheck(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("autoUpdate", on).apply()

    fun current(ctx: Context): String = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    /** "v1.4.6" · "1.4.6" → [1,4,6] */
    fun parts(v: String): List<Int> = v.trim().removePrefix("v").removePrefix("V").split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    fun newer(latest: String, current: String): Boolean {
        val a = parts(latest); val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** GitHub /releases/latest 의 JSON → 받을 앱 파일이 있으면 정보 */
    fun parseRelease(json: String): Info? = runCatching {
        val o = JSONObject(json)
        val tag = o.getString("tag_name")
        val assets = o.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name") == ASSET) return Info(tag.removePrefix("v"), a.getString("browser_download_url"), o.optString("body"))
        }
        null
    }.getOrNull()

    /** 새 버전을 찾는다. force 가 아니면 마지막 확인 뒤 6시간이 안 지났을 때는 건너뛴다 */
    suspend fun check(ctx: Context, force: Boolean = false) {
        val p = prefs(ctx)
        if (!force && (!autoCheck(ctx) || System.currentTimeMillis() - p.getLong("updateChecked", 0L) < CHECK_EVERY_MS)) return
        checkedMsg = "확인 중…"
        val info = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 15_000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.inputStream.bufferedReader().use { parseRelease(it.readText()) }
            }.getOrNull()
        }
        if (info == null) { checkedMsg = "확인하지 못했습니다(인터넷 연결을 확인하세요)"; return }
        p.edit().putLong("updateChecked", System.currentTimeMillis()).apply()
        if (newer(info.version, current(ctx))) { available = info; checkedMsg = "새 버전 ${info.version} 이 있습니다" }
        else { available = null; checkedMsg = "최신 버전입니다" }
    }

    /** 누르면: 설치 권한이 없으면 그 설정 화면으로 보내고, 있으면 받아서 설치 화면을 연다 */
    suspend fun updateNow(ctx: Context) {
        val info = available ?: return
        if (busy) return
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            status = "먼저 ‘이 출처 허용’을 켠 뒤 돌아와서 다시 누르세요"
            runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        busy = true
        progress = 0f
        status = "새 버전 ${info.version} 받는 중 0%"
        try {
            val file = withContext(Dispatchers.IO) { download(ctx, info) }
            progress = -1f
            status = "내려받기 완료 · 설치를 준비하는 중…"
            notify(ctx, "설치를 준비하는 중…", -1)
            withContext(Dispatchers.IO) { install(ctx, file) }
        } catch (e: Exception) {
            status = "업데이트하지 못했습니다: ${e.message}"
        } finally {
            busy = false
            progress = -1f
            cancelNotice(ctx)
        }
    }

    private fun download(ctx: Context, info: Info): File {
        val out = File(ctx.cacheDir, "update.apk")
        val tmp = File(ctx.cacheDir, "update.apk.tmp")
        val c = URL(info.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 30_000
        val total = c.contentLengthLong
        var got = 0L; var lastPct = -1
        c.inputStream.use { i -> tmp.outputStream().use { o ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = i.read(buf); if (n < 0) break
                o.write(buf, 0, n); got += n
                val pct = if (total > 0) (got * 100 / total).toInt() else 0
                if (pct != lastPct) {
                    lastPct = pct
                    progress = if (total > 0) got.toFloat() / total else -1f
                    status = "새 버전 ${info.version} 받는 중 $pct%" + (if (total > 0) " (%.1f / %.1fMB)".format(got / 1048576.0, total / 1048576.0) else "")
                    notify(ctx, "새 버전 ${info.version} 받는 중 $pct%", pct)
                }
            }
        } }
        if (total > 0 && got != total) { tmp.delete(); throw IllegalStateException("받은 크기가 다릅니다") }
        out.delete(); tmp.renameTo(out)
        return out
    }

    private const val NOTICE_ID = 2

    /** 받는 동안 작업표시줄에도 진행 막대를 보인다(알림 권한이 없으면 조용히 건너뜀) */
    private fun notify(ctx: Context, text: String, pct: Int) {
        runCatching {
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(android.app.NotificationChannel("update", "앱 업데이트", android.app.NotificationManager.IMPORTANCE_LOW))
            val b = android.app.Notification.Builder(ctx, "update").setSmallIcon(R.mipmap.ic_launcher).setContentTitle("iamtalkernote 업데이트")
                .setContentText(text).setOngoing(true).setOnlyAlertOnce(true)
            if (pct in 0..100) b.setProgress(100, pct, false) else b.setProgress(0, 0, true)
            nm.notify(NOTICE_ID, b.build())
        }
    }

    private fun cancelNotice(ctx: Context) {
        runCatching { ctx.getSystemService(android.app.NotificationManager::class.java).cancel(NOTICE_ID) }
    }

    private fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            apk.inputStream().use { i -> s.openWrite("app.apk", 0, apk.length()).use { o -> i.copyTo(o); s.fsync(o) } }
            val intent = Intent(ctx, UpdateReceiver::class.java).setAction(RESULT_ACTION).setPackage(ctx.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            s.commit(PendingIntent.getBroadcast(ctx, id, intent, flags).intentSender)
        }
    }
}

/** 설치 결과를 받는다: 시스템의 '설치할까요?' 확인 화면을 띄우고, 실패하면 이유를 알린다 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { ctx.startActivity(confirm) }
                Updater.status = "설치 확인 화면이 떴습니다 — ‘설치’(또는 ‘업데이트’)를 눌러 주세요"
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.status = "설치했습니다 — 앱이 새 버전으로 다시 시작됩니다"
            else -> Updater.status = "설치하지 못했습니다: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "알 수 없는 오류")
        }
    }
}

/** 앱이 새 버전으로 바뀐 직후: 동기화를 켜 두었다면 다시 시작한다(업데이트가 엔진을 꺼 버리므로) */
class ReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED && EmbeddedSync.wanted(ctx)) runCatching { SyncService.start(ctx) }
    }
}
