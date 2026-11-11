package kr.iamtalker.notes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 공유받은 주소의 본문을 보이지 않는 WebView 로 불러 마크다운으로 바꾼다(컴퓨터 클리퍼와 같은 Readability·Turndown, 폰에서 직접 가져옴).
 *  주소는 외부 서버로 보내지 않는다. 공개 페이지만 된다(앱 안 로그인 없음). 반드시 메인 스레드에서 부른다. */
object WebClip {
    class Result(val title: String, val markdown: String)

    private const val TIMEOUT_MS = 30_000L
    private const val SETTLE_MS = 1500L   // 불러온 뒤 자바스크립트가 본문을 그릴 시간

    /** 사이트별 규칙(컴퓨터 클리퍼 표에서 뽑은 JSON). 앱에 든 것을 기본으로 쓰고, 일주일에 한 번 GitHub 의 최신 파일을 받아 두었으면 그것을 쓴다(앱 새 버전 없이 규칙만 갱신) */
    private const val RULES_URL = "https://raw.githubusercontent.com/iamtalker/iamtalkernote-android/main/app/src/main/assets/clip/site-rules.json"
    private const val RULES_EVERY_MS = 7L * 24 * 3600 * 1000

    /** 앱이 새로 설치되면 이름이 바뀌어 묵은 사본이 앱에 든 새 규칙을 가리지 않는다 */
    private fun rulesCache(ctx: Context): java.io.File {
        val code = runCatching { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0)) }.getOrDefault(0L)
        return java.io.File(ctx.filesDir, "site-rules-$code.json")
    }

    /** 캐시(GitHub 에서 받은 사본)와 앱에 든 규칙 중 날짜표("updated")가 더 새 것을 쓴다. 옛 캐시가 새 규칙(예: 모바일 주소 연결)을 가리지 않도록 */
    private fun rules(ctx: Context): String {
        val bundled = runCatching { asset(ctx, "site-rules.json") }.getOrDefault("{}")
        val cached = runCatching { rulesCache(ctx).takeIf { it.isFile }?.readText(Charsets.UTF_8)?.also { JSONObject(it) } }.getOrNull()
        return pickRules(cached, bundled)
    }

    /** 캐시가 앱에 든 규칙과 같거나 더 새면 캐시, 아니면 앱에 든 규칙. 시험할 수 있게 따로 뺐다 */
    internal fun pickRules(cached: String?, bundled: String): String =
        if (cached != null && rulesStamp(cached) >= rulesStamp(bundled)) cached else bundled

    /** 날짜표("updated": "YYYY-MM-DD")를 읽는다. YYYY-MM-DD 는 글자 순서가 곧 날짜 순서. 없으면 빈 글(가장 오래된 것) */
    internal fun rulesStamp(json: String): String =
        Regex("\"updated\"\\s*:\\s*\"([0-9-]*)\"").find(json)?.groupValues?.get(1) ?: ""

    /** 최신 규칙을 받아 둔다(일주일에 한 번, 실패하면 조용히 넘어감). 내려받은 글이 JSON 이 아니면 버린다. 보내는 정보는 없다(공개 파일 하나 읽기) */
    fun refreshRules(ctx: Context) {
        val f = rulesCache(ctx)
        if (f.isFile && System.currentTimeMillis() - f.lastModified() < RULES_EVERY_MS) return
        runCatching {
            val c = java.net.URL(RULES_URL).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            val text = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            c.disconnect()
            val o = JSONObject(text)
            if (!o.has("content")) return
            f.writeText(text, Charsets.UTF_8)
        }
    }

    private fun asset(ctx: Context, name: String) = ctx.assets.open("clip/$name").use { String(it.readBytes(), Charsets.UTF_8) }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(ctx: Context, url: String): Result {
        if (!Regex("(?i)^https?://").containsMatchIn(url)) throw IllegalArgumentException(tr("http 주소만 가져올 수 있습니다"))
        return withTimeout(TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val h = Handler(Looper.getMainLooper())
                val wv = WebView(ctx.applicationContext)
                var finished = false
                fun done(block: () -> Unit) {
                    if (finished) return
                    finished = true
                    h.removeCallbacksAndMessages(null)
                    wv.stopLoading(); wv.destroy()
                    block()
                }
                wv.settings.javaScriptEnabled = true
                wv.settings.domStorageEnabled = true
                wv.settings.allowFileAccess = false
                wv.settings.allowContentAccess = false
                wv.webViewClient = object : WebViewClient() {
                    private var started = false
                    override fun onPageStarted(v: WebView, u: String?, favicon: Bitmap?) { started = false }
                    override fun onPageFinished(v: WebView, u: String?) {
                        if (started || finished) return
                        started = true
                        h.postDelayed({ extract(ctx, wv) { r -> done { if (r.isSuccess) cont.resume(r.getOrThrow()) else cont.resumeWithException(r.exceptionOrNull()!!) } } }, SETTLE_MS)
                    }
                    override fun onReceivedError(v: WebView, req: WebResourceRequest, err: WebResourceError) {
                        if (req.isForMainFrame) done { cont.resumeWithException(IllegalStateException(tr("페이지를 불러오지 못했습니다") + ": " + err.description)) }
                    }
                }
                cont.invokeOnCancellation { h.post { done { } } }
                wv.loadUrl(url)
            }
        }
    }

    private fun extract(ctx: Context, wv: WebView, onResult: (kotlin.Result<Result>) -> Unit) {
        val js = "var __siteRules = " + rules(ctx) + ";\n;" + asset(ctx, "Readability.js") + "\n;" + asset(ctx, "turndown.js") + "\n;" + asset(ctx, "turndown-plugin-gfm.js") + "\n;" + asset(ctx, "webclip.js")
        wv.evaluateJavascript(js) { raw ->
            onResult(runCatching {
                val s = JSONTokener(raw ?: "null").nextValue() as? String ?: throw IllegalStateException(tr("본문을 찾지 못했습니다"))
                val o = JSONObject(s)
                if (o.has("error")) throw IllegalStateException(if (o.getString("error") == "nocontent") tr("본문을 찾지 못했습니다") else o.getString("error"))
                Result(o.optString("title"), o.getString("markdown"))
            })
        }
    }
}
