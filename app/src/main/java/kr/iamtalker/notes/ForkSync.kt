package kr.iamtalker.notes

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.json.JSONArray
import org.json.JSONObject

/** 폰의 Syncthing-Fork 를 이 앱이 대신 설정한다: 컴퓨터를 기기로 넣고, 컴퓨터가 보낸 폴더 공유 요청을 알맞은 경로로 받아 준다.
 *  Fork 의 로컬 REST API(127.0.0.1:8384, HTTPS, API 키 필요)를 쓴다. 컴퓨터 쪽 규칙은 컴퓨터 앱의 syncpolicy.py 와 같다. */
object ForkSync {
    /** Syncthing-Fork 는 배포처·버전마다 패키지 이름이 다르다: 플레이·F-Droid 의 이름, GitHub 최신판의 이름, 공식 안드로이드 앱 */
    val PKGS = listOf("com.github.catfriend1.syncthingandroid", "com.github.catfriend1.syncthingfork", "com.nutomic.syncthingandroid")
    const val STORE_PKG = "com.github.catfriend1.syncthingandroid"   // 스토어 목록에 있는 이름
    const val PORT = 8384
    const val ID_PREFIX = "inn-"
    /** 사용 설명서(GitHub) */
    const val MANUAL_URL = "https://github.com/iamtalker/iamtalkernote/blob/main/docs/manual.md"

    fun installedPkg(ctx: Context): String? = PKGS.firstOrNull { p -> runCatching { ctx.packageManager.getPackageInfo(p, 0); true }.getOrDefault(false) }
    fun installed(ctx: Context): Boolean = installedPkg(ctx) != null

    // ------------------------------------------------------------ 순수 계산(시험하기 쉽게 안드로이드와 분리)
    /** 저장소 트리 문서 id("primary:Documents/demo", "1234-ABCD:data/iamtalkernote") → 실제 경로. Syncthing 은 파일 경로가 필요하다 */
    fun treeIdToPath(docId: String): String? {
        val i = docId.indexOf(':')
        if (i < 0) return null
        val vol = docId.substring(0, i)
        val rel = docId.substring(i + 1).trim('/')
        val base = if (vol == "primary") "/storage/emulated/0" else "/storage/$vol"
        return if (rel.isEmpty()) base else "$base/$rel"
    }

    fun treePath(tree: Uri): String? = runCatching { treeIdToPath(DocumentsContract.getTreeDocumentId(tree)) }.getOrNull()

    /** 폴더 ID 의 종류 부분(inn-<볼트>-<종류>-<해시>): mar 노트 전체 · med 미디어 · imp 가져온 요약 · his 이력 · tra 휴지통 · not 노트북 · ext 외부 폴더 */
    fun kindOf(folderId: String): String = folderId.split('-').getOrElse(2) { "" }

    /** 컴퓨터가 알려 준 폴더 이름표와 ID 로 폰의 저장 경로를 정한다. 외부 폴더는 노트 폴더 옆 '<노트폴더>-files/<이름>' */
    fun localPath(vaultPath: String, folderId: String, label: String): String {
        fun sub(rel: String) = if (rel.isEmpty()) vaultPath else "$vaultPath/$rel"
        return when (kindOf(folderId)) {
            "mar" -> sub("")
            "med" -> sub(".resources")
            "imp" -> sub(".imported")
            "his" -> sub(".history")
            "tra" -> sub(".trash")
            "not" -> sub(label.substringAfter("노트북 · ", "").trim().ifEmpty { "notebook" })
            "ext" -> "$vaultPath-files/" + label.substringAfter("외부 폴더 · ", "").trim().ifEmpty { "external" }
            else -> "$vaultPath-files/" + label.ifEmpty { folderId }
        }
    }

    /** 컴퓨터의 방향 정책 → 폰 쪽 Syncthing 폴더 종류 */
    fun typeFor(direction: String): String? = when (direction) {
        "both" -> "sendreceive"
        "pc2mobile" -> "receiveonly"
        "mobile2pc" -> "sendonly"
        else -> null
    }

    // ---- 새 방식(노트 폴더 하나·방향 하나): 컴퓨터 앱 syncpolicy.py 의 SIMPLE_OPTIONS·plan 과 같은 규칙
    private val SIMPLE_OPTIONS = listOf("media" to ".resources", "imported" to ".imported", "history" to ".history", "trash" to ".trash")
    private val COMMON_IGNORE = listOf("(?d)Thumbs.db", "(?d)desktop.ini", "(?d).DS_Store", "~*.tmp")

    /** 새 방식에서 노트 폴더 Syncthing 폴더가 가져야 할 제외 목록: 보내지 않기로 한 것 + 복원 지점(컴퓨터 전용) + 공통 */
    fun ignoreLines(simple: JSONObject): List<String> {
        val inc = simple.optJSONObject("include")
        val out = ArrayList<String>()
        for ((key, dir) in SIMPLE_OPTIONS) if (inc?.optBoolean(key, false) != true) out.add("/$dir")
        out.add("/.snapshots")
        out.addAll(COMMON_IGNORE)
        return out
    }

    /** 새 방식으로 바뀌면 따로 둘 이유가 없어진 옛 폴더 종류(미디어·가져온 요약·이력·휴지통·노트북) */
    fun staleKindsForSimple(): Set<String> = setOf("med", "imp", "his", "tra", "not")

    /** Fork 에 등록된 폴더 ID 들 중 이 볼트의 옛 폴더(새 방식에서는 지울 것). 외부 폴더(ext)와 다른 볼트·다른 앱의 폴더는 건드리지 않는다 */
    fun staleFolders(ids: List<String>, vault: String): List<String> =
        ids.filter { it.startsWith("$ID_PREFIX$vault-") && kindOf(it) in staleKindsForSimple() }

    private fun sha1Hex(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** 컴퓨터 앱의 syncpolicy.folder_id 와 같은 값 */
    fun folderId(vault: String, kind: String, mid: String) = "$ID_PREFIX$vault-${kind.take(3)}-" + sha1Hex(mid).take(8)

    /** 붙여넣은 글에서 API 키를 뽑는다: Syncthing 의 키는 영문·숫자 32자. 앞뒤에 다른 글이 딸려 와도 32자 덩어리를 찾는다 */
    fun keyFrom(text: String): String = Regex("[A-Za-z0-9]{32}").find(text)?.value ?: text.trim()

    /** 연결 오류를 사용자가 알아볼 말로 */
    fun friendly(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            e is java.net.ConnectException || m.contains("failed to connect", true) || m.contains("ECONNREFUSED") || m.contains("Connection refused", true) ->
                "Syncthing-Fork 가 실행 중이 아닙니다. Fork 를 열고 ‘FORCE START’(실행 조건 무시하고 시작)를 누른 뒤 다시 시도하세요."
            e is java.net.SocketTimeoutException -> "Syncthing-Fork 가 응답하지 않습니다. Fork 가 켜져 있는지 확인하세요."
            m.isNotEmpty() -> m
            else -> e.javaClass.simpleName
        }
    }

    // ------------------------------------------------------------ Fork API
    /** plain=true 는 앱에 내장한 Syncthing(http://127.0.0.1, 앱이 정한 키)용. 기본은 Fork(https, 자체 서명 인증서) */
    class Api(private val key: String, private val port: Int = PORT, private val plain: Boolean = false) {
        // 127.0.0.1 의 Fork 가 자체 서명 인증서를 쓰므로, 이 연결에만 인증서 검사를 끈다(주소가 루프백일 때만)
        private val ssl: SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), SecureRandom())
        }

        fun call(path: String, method: String = "GET", body: JSONObject? = null): String {
            val c = URL((if (plain) "http" else "https") + "://127.0.0.1:$port$path").openConnection() as java.net.HttpURLConnection
            if (c is HttpsURLConnection) {
                c.sslSocketFactory = ssl.socketFactory
                c.hostnameVerifier = javax.net.ssl.HostnameVerifier { host, _ -> host == "127.0.0.1" }
            }
            c.requestMethod = method
            c.connectTimeout = 4000
            c.readTimeout = 15000
            c.setRequestProperty("X-API-Key", key)
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException(
                if (code == 403 && plain) "내장 동기화 엔진이 이 앱의 키를 받아 주지 않습니다. 동기화를 껐다 다시 켜 보세요."
                else if (code == 403) "Fork 가 이 API 키를 받아 주지 않습니다. Fork 를 지웠다 다시 설치했다면 키가 새로 바뀌니, 새 Fork 에서 키를 다시 복사하세요."
                else "Fork 응답 $code ${text.take(120)}")
            return text
        }

        fun myId(): String = JSONObject(call("/rest/system/status")).getString("myID")
        fun config(): JSONObject = JSONObject(call("/rest/config"))

        fun putDevice(id: String, name: String) {
            call("/rest/config/devices/$id", "PUT", JSONObject().put("deviceID", id).put("name", name)
                .put("addresses", JSONArray().put("dynamic")).put("autoAcceptFolders", false).put("paused", false))
        }

        fun deviceIds(): List<String> {
            val a = config().getJSONArray("devices")
            return (0 until a.length()).map { a.getJSONObject(it).getString("deviceID") }
        }

        fun hasDevice(id: String): Boolean {
            val a = config().getJSONArray("devices")
            return (0 until a.length()).any { a.getJSONObject(it).getString("deviceID") == id }
        }

        /** 컴퓨터가 우리에게 공유하려는 폴더들: 폴더 ID → (이름표, 보낸 기기 ID 들) */
        fun pendingFolders(): Map<String, Pair<String, Set<String>>> {
            val o = JSONObject(call("/rest/cluster/pending/folders"))   // 못 읽으면 예외 — 호출한 쪽이 화면에 알린다
            val out = LinkedHashMap<String, Pair<String, Set<String>>>()
            for (fid in o.keys()) {
                val by = o.getJSONObject(fid).optJSONObject("offeredBy") ?: continue
                val devs = by.keys().asSequence().toSet()
                val label = devs.firstNotNullOfOrNull { by.getJSONObject(it).optString("label").ifEmpty { null } } ?: fid
                out[fid] = label to devs
            }
            return out
        }

        fun folders(): List<JSONObject> {
            val a = config().getJSONArray("folders")
            return (0 until a.length()).map { a.getJSONObject(it) }
        }

        fun acceptFolder(id: String, label: String, path: String, type: String, deviceIds: List<String>, paused: Boolean = false) {
            val f = JSONObject(call("/rest/config/defaults/folder"))
            f.put("id", id).put("label", label).put("path", path).put("type", type).put("paused", paused)
            f.put("devices", JSONArray().also { arr -> deviceIds.forEach { arr.put(JSONObject().put("deviceID", it)) } })
            call("/rest/config/folders/$id", "PUT", f)
        }

        fun ignores(id: String): List<String> {
            val a = JSONObject(call("/rest/db/ignores?folder=$id")).optJSONArray("ignore") ?: return emptyList()
            return (0 until a.length()).map { a.getString(it) }
        }

        fun setIgnores(id: String, lines: List<String>) {
            call("/rest/db/ignores?folder=$id", "POST", JSONObject().put("ignore", JSONArray().also { arr -> lines.forEach { arr.put(it) } }))
        }

        /** Fork 의 폴더 설정만 지운다(폰의 파일은 그대로) */
        fun removeFolder(id: String) {
            call("/rest/config/folders/$id", "DELETE")
        }

        /** 멈춰 둔 폴더를 다시 시작한다 */
        fun resume(id: String) {
            val f = folders().firstOrNull { it.getString("id") == id } ?: return
            f.put("paused", false)
            call("/rest/config/folders/$id", "PUT", f)
        }

        fun setFolderType(folder: JSONObject, type: String) {
            folder.put("type", type)
            call("/rest/config/folders/" + folder.getString("id"), "PUT", folder)
        }

        fun connected(deviceId: String): Boolean =
            JSONObject(call("/rest/system/connections")).optJSONObject("connections")?.optJSONObject(deviceId)?.optBoolean("connected") == true

        /** 폴더 상태: (idle/syncing/scanning …, 아직 받을 바이트, 전체 바이트) */
        fun folderStatus(id: String): Triple<String, Long, Long> {
            val s = JSONObject(call("/rest/db/status?folder=$id"))
            return Triple(s.optString("state", "?"), s.optLong("needBytes"), s.optLong("globalBytes"))
        }
    }

    // ------------------------------------------------------------ 정책 파일(.sync/policy.json) 읽기
    fun readPolicy(ctx: Context, tree: Uri): JSONObject? {
        val root = DocumentsContract.getTreeDocumentId(tree)
        val dir = History.list(ctx, tree, root).firstOrNull { it.isDir && it.name == ".sync" } ?: return null
        val file = History.list(ctx, tree, dir.id).firstOrNull { !it.isDir && it.name == "policy.json" } ?: return null
        return runCatching { JSONObject(NoteRepo.read(ctx, History.docUri(tree, file.id))) }.getOrNull()
    }

    /** 새 방식 정책일 때의 뒷정리. 돌려주는 값: 화면에 알릴 말(없으면 빈 글) */
    private fun simpleNote(ctx: Context, api: Api, vault: String, sm: JSONObject, vaultPath: String, staged: Boolean): String {
        val have = api.folders()
        val mar = have.firstOrNull { it.getString("id") == folderId(vault, "markdown", "markdown") } ?: return ""   // 아직 못 받았으면 다음 확인 때
        val id = mar.getString("id")
        var want = ignoreLines(sm)
        if (staged) {   // 최근부터 단계별로: 컴퓨터가 만든 단계 목록으로 아직 받을 차례가 아닌 파일을 가린다. 목록이 오기 전에는 전부 가린다
            val tiers = runCatching { Stages.parse(java.io.File(vaultPath, Stages.FILE).readText()) }.getOrNull()
            if (tiers == null) want = Stages.GATE
            else {
                val prefs = ctx.getSharedPreferences("settings", 0)
                var rel = prefs.getInt("stagesReleased", 1)
                val st = runCatching { api.folderStatus(id) }.getOrNull()
                if (st != null) {
                    val up = runCatching { api.deviceIds().any { it != api.myId() && api.connected(it) } }.getOrDefault(false)
                    val (r2, since) = Stages.advance(rel, tiers.size, Stages.looksDone(st.first, st.second, st.third, up), prefs.getLong("stagesSince", 0L), System.currentTimeMillis())
                    rel = r2
                    prefs.edit().putLong("stagesSince", since).apply()
                }
                prefs.edit().putInt("stagesReleased", rel).apply()
                want = want + Stages.hidden(tiers, rel)
            }
        }
        if (api.ignores(id) != want) api.setIgnores(id, want)
        staleFolders(have.map { it.getString("id") }, vault).forEach { api.removeFolder(it) }
        val at = mar.optString("path").trimEnd('/', '\\')
        // 경로만 바꾸면 Syncthing 이 파일이 사라진 것으로 보고 삭제를 전할 수 있어 자동으로 바꾸지 않는다
        return if (at != vaultPath.trimEnd('/')) "Fork 에 등록된 노트 폴더 경로($at)가 앱의 노트 폴더($vaultPath)와 다릅니다. Fork 에서 이 앱이 만든 폴더(ID 가 inn- 로 시작)를 지우고 다시 받으세요(폰의 파일은 그대로입니다)." else ""
    }

    data class FolderLine(val label: String, val state: String, val needBytes: Long, val totalBytes: Long)
    data class Report(val myId: String = "", val connected: Boolean = false, val folders: List<FolderLine> = emptyList(), val accepted: Int = 0,
                      val note: String = "", val error: String? = null)

    /** 5초마다 한 번: 컴퓨터가 보낸 공유 요청을 받고, 정책 파일이 생겼으면 방향(폴더 종류)을 맞추고, 상태를 모은다 */
    fun tick(ctx: Context, api: Api, pcId: String, tree: Uri, staged: Boolean = false): Report {
        val vaultPath = treePath(tree) ?: return Report(error = "이 저장소의 실제 경로를 알 수 없습니다(노트 폴더를 내장 메모리나 SD 카드에서 고르세요)")
        val me = api.myId()
        var accepted = 0
        var note = ""
        val policy = runCatching { readPolicy(ctx, tree) }.getOrNull()
        // 새 방식 정책이면 옛 종류의 폴더(미디어·이력 등)는 받지 않는다(컴퓨터가 아직 공유 중이어도 노트 폴더 안에서 함께 보낸다)
        val simpleVault = if (policy?.optJSONObject("simple") != null) policy.optString("vault") else ""
        // Fork 에 이미 등록된 기기(컴퓨터 추가로 넣었거나 전에 연결한 기기)가 보낸 우리 폴더(inn-…)만 받는다. 컴퓨터 ID 를 정확히 안 넣었어도 된다
        val known = api.deviceIds().filter { it != me }.toSet()
        if (known.isEmpty()) note = "Fork 에 컴퓨터가 아직 등록되지 않았습니다. 3단계에서 ‘컴퓨터 추가’를 누르세요."
        else try {
            for ((fid, v) in api.pendingFolders()) {
                if (!fid.startsWith(ID_PREFIX)) continue
                if (simpleVault.isNotEmpty() && fid in staleFolders(listOf(fid), simpleVault)) continue
                val from = v.second.filter { it in known }
                if (from.isEmpty()) continue
                val gate = staged && kindOf(fid) == "mar"   // 노트 폴더는 멈춘 채로 받아 무시 목록(전부 가림)부터 걸고 시작한다
                api.acceptFolder(fid, v.first, localPath(vaultPath, fid, v.first), "sendreceive", listOf(me) + from, paused = gate)
                if (gate) { api.setIgnores(fid, Stages.GATE); api.resume(fid) }
                accepted++
            }
        } catch (e: Exception) {
            note = "컴퓨터가 보낸 폴더 목록을 읽지 못했습니다(Fork 버전이 오래됐을 수 있습니다): ${friendly(e)}"
        }
        // 정책 파일이 도착하면 폴더마다 방향을 맞춘다(처음엔 모두 양방향으로 받는다)
        policy?.let { pol ->
            val vault = pol.optString("vault")
            val mods = pol.optJSONObject("modules")
            if (vault.isNotEmpty() && mods != null) {
                val have = api.folders().associateBy { it.getString("id") }
                for (mid in mods.keys()) {
                    val m = mods.getJSONObject(mid)
                    val want = typeFor(m.optString("direction")) ?: continue
                    val f = have[folderId(vault, m.optString("kind"), mid)] ?: continue
                    if (f.optString("type") != want) api.setFolderType(f, want)
                }
                // 새 방식(노트 폴더 하나·방향 하나): 제외 목록을 컴퓨터와 같게 하고, 쓸 일 없어진 옛 폴더를 정리한다
                pol.optJSONObject("simple")?.let { sm -> simpleNote(ctx, api, vault, sm, vaultPath, staged).takeIf { it.isNotEmpty() }?.let { note = it } }
                // 옛 방식 정책이면 단계별로 받을 일이 없으니, 받기 전에 걸어 둔 '전부 가림'을 푼다
                if (staged && pol.optJSONObject("simple") == null) {
                    val mid = folderId(vault, "markdown", "markdown")
                    if (have.containsKey(mid) && api.ignores(mid) == Stages.GATE) api.setIgnores(mid, emptyList())
                }
            }
        }
        val lines = api.folders().filter { it.getString("id").startsWith(ID_PREFIX) }.map {
            val st = runCatching { api.folderStatus(it.getString("id")) }.getOrDefault(Triple("?", 0L, 0L))
            FolderLine(it.optString("label"), st.first, st.second, st.third)
        }
        val connected = (if (pcId.isNotEmpty()) listOf(pcId) else known.toList()).any { runCatching { api.connected(it) }.getOrDefault(false) }
        return Report(me, connected, lines, accepted, note)
    }
}
