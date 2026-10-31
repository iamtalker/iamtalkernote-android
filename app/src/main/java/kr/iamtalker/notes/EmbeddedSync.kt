package kr.iamtalker.notes

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.security.SecureRandom

/** 앱에 함께 넣은 Syncthing(libsyncthingnative.so)을 직접 실행한다. Fork 앱도, API 키 복사도 필요 없다(키는 앱이 스스로 정한다).
 *  실행 파일은 저장소에 넣지 않는다 — app/src/main/jniLibs/<abi>/ 에 두면 APK 에 들어간다(prototypes/syncthing-embed/README.md). */
object EmbeddedSync {
    const val PORT = 18384
    const val BIN = "libsyncthingnative.so"

    @Volatile private var proc: Process? = null

    /** 서비스가 5초마다 갱신하는 상태(설정 화면이 보여 준다) */
    var report by mutableStateOf<ForkSync.Report?>(null)
    var running by mutableStateOf(false)

    fun binary(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, BIN)
    fun available(ctx: Context) = binary(ctx).isFile

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", 0)
    fun wanted(ctx: Context) = prefs(ctx).getBoolean("embOn", false)
    fun setWanted(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("embOn", on).apply()

    /** 앱이 정하는 API 키(영문·숫자 32자). 처음 한 번 만들어 저장한다 */
    fun newKey(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun key(ctx: Context): String {
        val p = prefs(ctx)
        return p.getString("embKey", null)?.takeIf { it.length == 32 } ?: newKey().also { p.edit().putString("embKey", it).apply() }
    }

    fun api(ctx: Context) = ForkSync.Api(key(ctx), PORT, plain = true)

    fun command(bin: String, home: String, key: String): List<String> =
        listOf(bin, "serve", "--home=$home", "--no-browser", "--no-restart", "--gui-address=http://127.0.0.1:$PORT", "--gui-apikey=$key")

    fun alive(): Boolean = proc?.isAlive == true

    /** 엔진 로그의 마지막 줄들(문제 확인용) */
    fun logTail(ctx: Context, lines: Int = 40): String = runCatching {
        val f = File(ctx.filesDir, "syncthing.log")
        if (!f.exists()) "" else f.readLines().takeLast(lines).joinToString("\n")
    }.getOrDefault("")

    /** 엔진 로그 파일에 앱이 남기는 한 줄(엔진이 아무것도 안 쓸 때 무슨 일이 있었는지 보려고) */
    fun note(ctx: Context, text: String) {
        runCatching { File(ctx.filesDir, "syncthing.log").appendText(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " [앱] $text\n") }
    }

    @Synchronized
    fun start(ctx: Context) {
        if (alive()) return
        note(ctx, "엔진 시작 (${binary(ctx).absolutePath}, 실행 파일 ${if (binary(ctx).isFile) "있음" else "없음"})")
        val home = File(ctx.filesDir, "syncthing").apply { mkdirs() }
        val pb = ProcessBuilder(command(binary(ctx).absolutePath, home.absolutePath, key(ctx)))
        pb.environment().apply {
            put("HOME", home.absolutePath); put("STNODEFAULTFOLDER", "1"); put("STNORESTART", "1"); put("STNOUPGRADE", "1")
            put("TMPDIR", ctx.cacheDir.absolutePath)
        }
        pb.redirectErrorStream(true)
        val p = runCatching { pb.start() }.getOrElse { note(ctx, "엔진을 실행하지 못했습니다: ${it.message}"); throw it }
        proc = p
        Thread { runCatching { val c = p.waitFor(); note(ctx, "엔진이 종료됐습니다 (코드 $c)") } }.start()
        val log = File(ctx.filesDir, "syncthing.log")
        if (log.length() > 512 * 1024) log.delete()
        Thread { runCatching { p.inputStream.bufferedReader().forEachLine { log.appendText(it + "\n") } } }.start()
        running = true
    }

    @Synchronized
    fun stop() {
        proc?.destroy()
        proc = null
        running = false
        report = null
    }

    /** 큐알(또는 붙여넣은 글)에서 기기 ID(7글자씩 8묶음, 하이픈)를 뽑는다. Syncthing 이 보여 주는 큐알은 ID 글자 그대로다 */
    fun parseDeviceId(text: String): String? =
        Regex("(?:[A-Za-z0-9]{7}-){7}[A-Za-z0-9]{7}").find(text.trim())?.value?.uppercase()
}
