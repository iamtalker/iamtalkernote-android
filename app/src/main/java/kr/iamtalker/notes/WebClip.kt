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
import kotlinx.coroutines.withContext
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
    internal const val UPSTREAM_CONTENT_URL = "https://raw.githubusercontent.com/iamtalker/joplin-clipper-plus/main/content.js"
    private val rulesLock = Any()

    /** 앱이 새로 설치되면 이름이 바뀌어 묵은 사본이 앱에 든 새 규칙을 가리지 않는다 */
    private fun rulesCache(ctx: Context): java.io.File {
        val code = runCatching { androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0)) }.getOrDefault(0L)
        return java.io.File(ctx.filesDir, "site-rules-$code.json")
    }

    private fun manualRulesCache(ctx: Context) = java.io.File(ctx.filesDir, "site-rules-manual.json")

    /** 캐시(GitHub 에서 받은 사본)와 앱에 든 규칙 중 날짜표("updated")가 더 새 것을 쓴다. 옛 캐시가 새 규칙(예: 모바일 주소 연결)을 가리지 않도록 */
    private fun rules(ctx: Context): String {
        val bundled = runCatching { asset(ctx, "site-rules.json") }.getOrDefault("{}")
        val cached = runCatching { rulesCache(ctx).takeIf { it.isFile }?.readText(Charsets.UTF_8)?.also { JSONObject(it) } }.getOrNull()
        val manual = runCatching { manualRulesCache(ctx).takeIf { it.isFile }?.readText(Charsets.UTF_8)?.also { validateRules(it) } }.getOrNull()
        return pickManualRules(manual, pickRules(cached, bundled))
    }

    /** Fetch only the allowlisted rule data from upstream content.js. */
    suspend fun checkUpstreamRules(ctx: Context): String = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val c = java.net.URL(UPSTREAM_CONTENT_URL).openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            val source = try { c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } } finally { c.disconnect() }
            val candidate = parseUpstreamRules(source)
            val changed = synchronized(rulesLock) {
                val file = manualRulesCache(ctx)
                val active = rules(ctx)
                if (sameRuleTables(active, candidate)) return@synchronized false
                val stamp = maxOf(utcToday(), rulesStamp(active))
                JSONObject(candidate).put("updated", stamp).let { writeAtomically(file, it.toString()) }
                true
            }
            if (changed) tr("웹 클리퍼 규칙을 업데이트했습니다") else tr("웹 클리퍼 규칙은 최신입니다")
        } catch (_: Exception) { tr("웹 클리퍼 규칙 확인 실패") }
    }

    internal fun validateRules(json: String): Boolean {
        val o = JSONObject(json)
        require(o.length() == 6 && o.has("updated"))
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(o.getString("updated")))
        val tables = mapOf("content" to false, "cleanup" to true, "label" to true, "iframe" to false, "alias" to false)
        for ((name, arrays) in tables) {
            val table = o.getJSONObject(name)
            for (key in table.keys()) {
                val value = table.get(key)
                if (arrays) { require(value is org.json.JSONArray); for (i in 0 until value.length()) require(value.get(i) is String) }
                else require(value is String)
            }
        }
        return true
    }

    private fun utcToday(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())

    private fun writeAtomically(file: java.io.File, text: String) {
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try { stream.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(stream) }
        catch (e: Exception) { atomic.failWrite(stream); throw e }
    }

    private val tableNames = listOf("content", "cleanup", "label", "iframe", "alias")
    private fun tableOnly(o: JSONObject) = JSONObject().also { out -> tableNames.forEach { out.put(it, o.get(it)) } }
    internal fun sameRuleTables(left: String, right: String): Boolean = runCatching { sameJson(tableOnly(JSONObject(left)), tableOnly(JSONObject(right))) }.getOrDefault(false)
    private fun sameJson(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> a.length() == b.length() && a.keys().asSequence().all { a.has(it) && b.has(it) && sameJson(a.get(it), b.get(it)) }
        a is org.json.JSONArray && b is org.json.JSONArray -> a.length() == b.length() && (0 until a.length()).all { sameJson(a.get(it), b.get(it)) }
        else -> a == b
    }

    /** Small data-only parser for quoted-key object/array/string literals; comments and trailing commas are accepted. */
    internal fun parseUpstreamRules(source: String): String {
        val result = parseUpstreamTables(source).put("updated", utcToday())
        val json = result.toString()
        validateRules(json)
        return json
    }

    private fun parseUpstreamTables(source: String): JSONObject {
        val names = linkedMapOf("content" to "SITE_CONTENT_SELECTORS", "cleanup" to "SITE_CLEANUP_SELECTORS", "label" to "SITE_LABEL_FROM_TITLE_SELECTORS", "iframe" to "SITE_IFRAME_SELECTORS", "alias" to "MOBILE_HOST_ALIAS")
        val result = JSONObject()
        for ((key, name) in names) {
            val start = Regex("\\bconst\\s+" + name + "\\s*=\\s*").find(source)?.range?.last?.plus(1) ?: error("Missing table $name")
            val parser = RuleLiteralParser(source, start)
            result.put(key, parser.parse())
        }
        return result
    }

    private class RuleLiteralParser(private val s: String, start: Int) {
        private var i = start
        private fun ws() { while (i < s.length) { if (s[i].isWhitespace()) i++ else if (s.startsWith("//", i)) { i = s.indexOf('\n', i).let { if (it < 0) s.length else it + 1 } } else if (s.startsWith("/*", i)) { val end = s.indexOf("*/", i + 2); require(end >= 0); i = end + 2 } else break } }
        fun parse(): Any { ws(); val parsed = value(); ws(); require(i < s.length && s[i] == ';'); return parsed }
        private fun value(): Any { ws(); require(i < s.length); return when (s[i]) {
            '{' -> { i++; val o = JSONObject(); ws(); while (i < s.length && s[i] != '}') { val k = string(); ws(); require(s[i++] == ':'); o.put(k, value()); ws(); if (s[i] == ',') { i++; ws() } else require(s[i] == '}') }; require(s[i++] == '}'); o }
            '[' -> { i++; val a = org.json.JSONArray(); ws(); while (i < s.length && s[i] != ']') { a.put(value()); ws(); if (s[i] == ',') { i++; ws() } else require(s[i] == ']') }; require(s[i++] == ']'); a }
            '\'', '"' -> string()
            else -> error("Unsupported rule syntax")
        } }
        private fun string(): String { ws(); require(i < s.length); val q = s[i++]; require(q == '\'' || q == '"'); val out = StringBuilder(); while (i < s.length) { val c = s[i++]; if (c == q) return out.toString(); if (c == '\\') { require(i < s.length); val e = s[i++]; out.append(when(e) { 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> e }) } else out.append(c) }; error("Unclosed string") }
    }

    /** 캐시가 앱에 든 규칙과 같거나 더 새면 캐시, 아니면 앱에 든 규칙. 시험할 수 있게 따로 뺐다 */
    internal fun pickRules(cached: String?, bundled: String): String =
        if (cached != null && rulesStamp(cached) >= rulesStamp(bundled)) cached else bundled

    internal fun pickManualRules(manual: String?, regular: String): String =
        if (manual != null && rulesStamp(manual) >= rulesStamp(regular)) manual else regular

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
            synchronized(rulesLock) { writeAtomically(f, text) }
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
