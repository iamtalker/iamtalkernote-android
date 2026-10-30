package kr.iamtalker.sttest

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/** 시험 앱: 앱에 함께 넣은 Syncthing(libsyncthingnative.so)을 직접 실행하고, 앱이 정한 API 키로 REST 를 호출한다(사용자가 키를 복사할 일이 없는지). */
class MainActivity : Activity() {
    private val tv by lazy { TextView(this) }
    private fun say(s: String) { Log.i("STTEST", s); runOnUiThread { tv.append(s + "\n") } ; File(filesDir, "result.txt").appendText(s + "\n") }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(tv)
        File(filesDir, "result.txt").delete()
        Thread { run() }.start()
    }

    private fun run() {
        try {
            val bin = File(applicationInfo.nativeLibraryDir, "libsyncthingnative.so")
            say("실행 파일: ${bin.name} 존재=${bin.exists()} 크기=${bin.length()} 실행가능=${bin.canExecute()}")
            val home = File(filesDir, "st").apply { mkdirs() }
            val key = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }   // 앱이 스스로 만든 API 키
            val pb = ProcessBuilder(bin.absolutePath, "serve", "--home=${home.absolutePath}", "--no-browser", "--no-restart",
                "--gui-address=http://127.0.0.1:18384", "--gui-apikey=$key")
            pb.environment().apply { put("HOME", home.absolutePath); put("STNODEFAULTFOLDER", "1"); put("STNORESTART", "1"); put("STNOUPGRADE", "1"); put("TMPDIR", cacheDir.absolutePath) }
            pb.redirectErrorStream(true)
            val p = pb.start()
            Thread { p.inputStream.bufferedReader().forEachLine { File(filesDir, "st.log").appendText(it + "\n") } }.start()
            say("프로세스 시작")
            var ok = false
            for (i in 1..60) {
                Thread.sleep(1000)
                try {
                    val c = URL("http://127.0.0.1:18384/rest/system/status").openConnection() as HttpURLConnection
                    c.setRequestProperty("X-API-Key", key)
                    val code = c.responseCode
                    val body = c.inputStream.bufferedReader().readText()
                    val id = Regex("\"myID\":\"([A-Z0-9-]+)\"").find(body)?.groupValues?.get(1)
                    say("REST $code ${i}초 만에 응답, 내 기기 ID 앞 7자=${id?.take(7)}")
                    // 잘못된 키는 거부되는지
                    val bad = URL("http://127.0.0.1:18384/rest/system/status").openConnection() as HttpURLConnection
                    bad.setRequestProperty("X-API-Key", "wrong")
                    say("틀린 키 응답 코드=${bad.responseCode}")
                    say("모든 파일 접근 권한=" + android.os.Environment.isExternalStorageManager())
                    fun api(path: String, method: String = "GET", body: String? = null): String {
                        val c2 = URL("http://127.0.0.1:18384" + path).openConnection() as HttpURLConnection
                        c2.requestMethod = method; c2.setRequestProperty("X-API-Key", key)
                        if (body != null) { c2.doOutput = true; c2.setRequestProperty("Content-Type", "application/json"); c2.outputStream.use { it.write(body.toByteArray()) } }
                        return (if (c2.responseCode in 200..299) c2.inputStream else c2.errorStream).bufferedReader().readText()
                    }
                    val dir = "/storage/emulated/0/Documents/sttest"
                    File(dir).mkdirs()
                    say("앱 코드가 공용 저장소에 폴더 만들기=" + File(dir).isDirectory)
                    val tmpl = org.json.JSONObject(api("/rest/config/defaults/folder"))
                    tmpl.put("id", "inn-test-mar-00000000").put("label", "iamtalkernote").put("path", dir).put("type", "sendreceive")
                    api("/rest/config/folders/inn-test-mar-00000000", "PUT", tmpl.toString())
                    Thread.sleep(4000)
                    say("Syncthing 이 만든 표식(.stfolder)=" + File(dir, ".stfolder").exists())
                    File(dir, "hello.md").writeText("# 안녕\n")
                    api("/rest/db/scan?folder=inn-test-mar-00000000", "POST")
                    Thread.sleep(3000)
                    say("폴더 상태=" + org.json.JSONObject(api("/rest/db/status?folder=inn-test-mar-00000000")).let { "${it.optString("state")} 파일 ${it.optInt("localFiles")}개" })
                    ok = true; break
                } catch (e: Exception) { if (i % 10 == 0) say("아직 응답 없음(${e.javaClass.simpleName}) ${i}초") }
            }
            say(if (ok) "결과: 성공" else "결과: 실패(응답 없음)")
            if (!p.isAlive) say("프로세스 종료 코드=${p.exitValue()}")
        } catch (e: Throwable) { say("오류: ${e.javaClass.simpleName} ${e.message}") }
    }
}
