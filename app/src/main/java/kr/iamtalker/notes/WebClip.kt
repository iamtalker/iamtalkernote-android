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
        val js = asset(ctx, "Readability.js") + "\n;" + asset(ctx, "turndown.js") + "\n;" + asset(ctx, "turndown-plugin-gfm.js") + "\n;" + asset(ctx, "webclip.js")
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
