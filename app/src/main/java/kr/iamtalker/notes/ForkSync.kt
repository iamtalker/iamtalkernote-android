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
    private val tuned = HashSet<String>()
    /** Syncthing-Fork 는 배포처·버전마다 패키지 이름이 다르다: 플레이·F-Droid 의 이름, GitHub 최신판의 이름, 공식 안드로이드 앱 */
    val PKGS = listOf("com.github.catfriend1.syncthingandroid", "com.github.catfriend1.syncthingfork", "com.nutomic.syncthingandroid")
    const val STORE_PKG = "com.github.catfriend1.syncthingandroid"   // 스토어 목록에 있는 이름
    const val PORT = 8384
    const val ID_PREFIX = "inn-"
    /** 사용 설명서(GitHub) */
    const val MANUAL_URL = "https://github.com/iamtalker/iamtalkernote/blob/main/docs/manual.md"
    /** 컴퓨터 앱(exe) 최신 릴리스 */
    const val PC_URL = "https://github.com/iamtalker/iamtalkernote/releases/latest"

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

    fun treePath(tree: Uri): String? =
        if (NotesProvider.isOurs(tree)) NotesProvider.root?.path   // 앱 안 노트 폴더
        else runCatching { treeIdToPath(DocumentsContract.getTreeDocumentId(tree)) }.getOrNull()

    /** 폴더 ID 의 종류 부분(inn-<볼트>-<종류>-<해시>): mar 노트 전체 · med 미디어 · imp 가져온 요약 · his 이력 · tra 휴지통 · not 노트북 · ext 외부 폴더 */
    fun kindOf(folderId: String): String = folderId.split('-').getOrElse(2) { "" }

    /** 컴퓨터가 알려 준 폴더 이름표와 ID 로 폰의 저장 경로를 정한다. 외부 폴더는 노트 폴더 옆 '<노트폴더>-files/<이름>' */
    fun localPath(vaultPath: String, folderId: String, label: String, extRoot: String? = null): String {
        fun sub(rel: String) = if (rel.isEmpty()) vaultPath else "$vaultPath/$rel"
        // 노트 폴더가 앱 안(/data/…)이면 그 옆(-files)도 앱 안이라 다른 앱(음악 앱·파일 관리 앱)이 못 본다 → 외부 폴더는 공유 저장소에 둔다
        // 사용자가 설정에서 외부 폴더를 받을 곳(SD카드 포함)을 정했으면 그 안에 이름별로 만든다
        val files = extRoot?.trim()?.trimEnd('/', '\\')?.takeIf { it.isNotEmpty() }
            ?: if (vaultPath.startsWith("/data/")) "/storage/emulated/0/iamtalkernote-files" else "$vaultPath-files"
        return when (kindOf(folderId)) {
            "mar" -> sub("")
            "med" -> sub(".resources")
            "imp" -> sub(".imported")
            "his" -> sub(".history")
            "tra" -> sub(".trash")
            "not" -> sub(label.substringAfter("노트북 · ", "").trim().ifEmpty { "notebook" })
            "cfg" -> vaultPath.trimEnd('/') + "-settings"
            "ext" -> "$files/" + label.substringAfter("외부 폴더 · ", "").trim().ifEmpty { "external" }
            else -> "$files/" + label.ifEmpty { folderId }
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

    /** 이 볼트의 외부 폴더(ext) 중 정책(keep: 지금 정책에 있는 외부 폴더 ID)에서 빠진 것 — 컴퓨터가 더는 공유하지 않는 폴더 */
    fun goneExt(ids: List<String>, vault: String, keep: Set<String>): List<String> =
        ids.filter { it.startsWith("$ID_PREFIX$vault-") && kindOf(it) == "ext" && it !in keep }

    private const val GONE_AFTER_MS = 10 * 60_000L   // 정책 파일이 늦게 도착하는 때를 피하려고 이만큼 계속 빠져 있어야 지운다
    private val goneSince = HashMap<String, Long>()

    /** 컴퓨터가 정책에서 뺀 외부 폴더의 Syncthing 등록을 지운다(받은 파일은 그대로). 지운 폴더 ID 목록 */
    fun dropGoneExt(api: Api, vault: String, keep: Set<String>, connected: Boolean, now: Long): List<String> {
        val gone = goneExt(api.folders().map { it.getString("id") }, vault, keep)
        goneSince.keys.retainAll(gone.toSet())
        val out = ArrayList<String>()
        for (id in gone) {
            val since = goneSince.getOrPut(id) { now }
            if (connected && now - since >= GONE_AFTER_MS) { api.removeFolder(id); goneSince.remove(id); out.add(id) }
        }
        return out
    }

    /** 컴퓨터 앱의 syncpolicy.folder_id 와 같은 값 */
    fun folderId(vault: String, kind: String, mid: String) = "$ID_PREFIX$vault-${kind.take(3)}-" + sha1Hex(mid).take(8)

    /** 붙여넣은 글에서 API 키를 뽑는다: Syncthing 의 키는 영문·숫자 32자. 앞뒤에 다른 글이 딸려 와도 32자 덩어리를 찾는다 */
    fun keyFrom(text: String): String = Regex("[A-Za-z0-9]{32}").find(text)?.value ?: text.trim()

    /** 연결 오류를 사용자가 알아볼 말로 */
    fun friendly(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            e is java.net.ConnectException || m.contains("failed to connect", true) || m.contains("ECONNREFUSED") || m.contains("Connection refused", true) ->
                tr("Syncthing-Fork 가 실행 중이 아닙니다. Fork 를 열고 ‘FORCE START’(실행 조건 무시하고 시작)를 누른 뒤 다시 시도하세요.")
            e is java.net.SocketTimeoutException -> tr("Syncthing-Fork 가 응답하지 않습니다. Fork 가 켜져 있는지 확인하세요.")
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
                if (code == 403 && plain) tr("내장 동기화 엔진이 이 앱의 키를 받아 주지 않습니다. 동기화를 껐다 다시 켜 보세요.")
                else if (code == 403) tr("Fork 가 이 API 키를 받아 주지 않습니다. Fork 를 지웠다 다시 설치했다면 키가 새로 바뀌니, 새 Fork 에서 키를 다시 복사하세요.")
                else trf("Fork 응답 %s %s", code, text.take(120)))
            return text
        }

        fun myId(): String = JSONObject(call("/rest/system/status")).getString("myID")
        fun config(): JSONObject = JSONObject(call("/rest/config"))

        fun putDevice(id: String, name: String) {
            call("/rest/config/devices/$id", "PUT", JSONObject().put("deviceID", id).put("name", name)
                .put("addresses", JSONArray().put("dynamic")).put("autoAcceptFolders", false).put("paused", false))
        }

        /** 상대 기기와의 연결을 한 번 끊었다 다시 잇는다(잠깐 멈췄다 푼다). 상대가 폴더 공유 목록을 다시 보내, 지운 폴더가 새 공유 요청으로 다시 온다 */
        fun reconnect(id: String) {
            val d = config().getJSONArray("devices").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.getString("deviceID") == id } } ?: return
            call("/rest/config/devices/$id", "PUT", d.put("paused", true))
            call("/rest/config/devices/$id", "PUT", d.put("paused", false))
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

        /** 폴더를 바로 다시 검사시킨다. 폰이 파일을 폴더에 직접 쓰면 안드로이드 외부 저장소에서는 Syncthing 이 새 파일을 바로 알아채지 못해 최대 한 시간 뒤 재검사까지 전송이 밀린다 */
        fun scan(id: String) {
            call("/rest/db/scan?folder=" + java.net.URLEncoder.encode(id, "UTF-8"), "POST")
        }

        /** 설정 폴더(종류 cfg)를 바로 검사시킨다. 폰이 settings.json·progress.json 을 쓴 직후에 부른다 */
        fun scanSettingsFolder() = runCatching {
            folders().map { it.getString("id") }.filter { it.startsWith(ID_PREFIX) && kindOf(it) == "cfg" }.forEach { scan(it) }
        }

        /** 폴더를 잠깐 멈췄다 다시 시작한다. 받기에 실패해 '몇 시간 뒤 재시도'로 밀린 대기가 풀린다 */
        fun kick(id: String) {
            val f = folders().firstOrNull { it.getString("id") == id } ?: return
            f.put("paused", true)
            call("/rest/config/folders/$id", "PUT", f)
            f.put("paused", false)
            call("/rest/config/folders/$id", "PUT", f)
        }

        /** 받지 못한 파일들: (경로, 오류 글) */
        fun folderErrors(id: String): List<Pair<String, String>> = runCatching {
            val a = JSONObject(call("/rest/folder/errors?folder=$id")).optJSONArray("errors") ?: return emptyList()
            (0 until a.length()).map { a.getJSONObject(it).let { e -> e.optString("path") to e.optString("error") } }
        }.getOrDefault(emptyList())

        /** 폴더 설정을 그대로 다시 올린다(필드를 고친 뒤) */
        fun putFolder(folder: JSONObject) {
            call("/rest/config/folders/" + folder.getString("id"), "PUT", folder)
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

        /** 폴더 진행 상황 전체: 상태, 받을/전체 바이트, 받을/전체 파일 수 */
        fun folderDetail(id: String): FolderLine {
            val s = JSONObject(call("/rest/db/status?folder=$id"))
            return FolderLine("", s.optString("state", "?"), s.optLong("needBytes"), s.optLong("globalBytes"),
                s.optLong("needFiles"), s.optLong("globalFiles"))
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
        return if (at != vaultPath.trimEnd('/')) trf("Fork 에 등록된 노트 폴더 경로(%s)가 앱의 노트 폴더(%s)와 다릅니다. Fork 에서 이 앱이 만든 폴더(ID 가 inn- 로 시작)를 지우고 다시 받으세요(폰의 파일은 그대로입니다).", at, vaultPath) else ""
    }

    private val etas = HashMap<String, Eta>()
    private val stuckSince = HashMap<String, Long>()   // 받을 것이 남았는데 놀고 있기 시작한 때
    private val lastKick = HashMap<String, Long>()

    /** 남은 것이 있는데 2분 넘게 놀면(받기 실패로 몇 시간 뒤 재시도에 밀린 경우) 5분에 한 번 폴더를 다시 시작해 바로 재시도시킨다. 돌려주는 값: 시작했는가 */
    fun kickIfStuck(api: Api, id: String, d: FolderLine, connected: Boolean, now: Long): Boolean {
        if (!(connected && d.state == "idle" && d.needFiles > 0)) { stuckSince.remove(id); return false }
        val since = stuckSince.getOrPut(id) { now }
        if (now - since < 120_000 || now - (lastKick[id] ?: 0L) < 300_000) return false
        lastKick[id] = now
        return runCatching { api.kick(id); true }.getOrDefault(false)
    }

    private var lastClean = 0L

    /** 1.4.32 이하에서 앱 내부(노트 폴더 옆 iamtalkernote-files)에 받힌 외부 폴더 사본을 지운다(거기는 다른 앱이 못 보는 곳이다).
     *  지우는 조건: ① 그 안을 쓰는 동기화 폴더 등록이 하나도 없고 ② 외부 폴더가 새 위치로 **다 받아졌을 때**(대기 상태·남은 파일 0). 원본은 컴퓨터에 있다.
     *  지웠으면 알릴 글, 아니면 null. 1분에 한 번만 확인한다 */
    fun cleanOldExt(ctx: Context, api: Api): String? {
        val old = java.io.File(ctx.filesDir, "iamtalkernote-files")
        if (!old.isDirectory) return null
        val now = System.currentTimeMillis()
        if (now - lastClean < 60_000) return null
        lastClean = now
        val ext = api.folders().filter { it.getString("id").startsWith(ID_PREFIX) && kindOf(it.getString("id")) == "ext" }
        if (ext.isEmpty()) return null
        val oldPath = old.canonicalPath
        if (ext.any { java.io.File(it.optString("path")).canonicalPath.startsWith(oldPath) }) return null
        val done = ext.all { f ->
            val d = api.folderDetail(f.getString("id"))
            d.state == "idle" && d.needFiles == 0L && d.totalFiles > 0L
        }
        if (!done) return null
        val n = old.walkTopDown().count { it.isFile }
        if (!old.deleteRecursively()) return null
        return trf("예전에 앱 내부에 받았던 외부 폴더 사본 %s개를 지웠습니다(새 위치로 다 받아졌고 원본은 컴퓨터에 있습니다)", n)
    }

    data class FolderLine(val label: String, val state: String, val needBytes: Long, val totalBytes: Long,
                          val needFiles: Long = 0, val totalFiles: Long = 0, val rate: Double = 0.0,
                          val failed: Int = 0, val firstError: String = "", val path: String = "")
    data class Report(val myId: String = "", val connected: Boolean = false, val folders: List<FolderLine> = emptyList(), val accepted: Int = 0,
                      val note: String = "", val error: String? = null)

    /** 5초마다 한 번: 컴퓨터가 보낸 공유 요청을 받고, 정책 파일이 생겼으면 방향(폴더 종류)을 맞추고, 상태를 모은다 */
    fun tick(ctx: Context, api: Api, pcId: String, tree: Uri, staged: Boolean = false): Report {
        val vaultPath = treePath(tree) ?: return Report(error = tr("이 저장소의 실제 경로를 알 수 없습니다(노트 폴더를 내장 메모리나 SD 카드에서 고르세요)"))
        val me = api.myId()
        var accepted = 0
        var note = ""
        // 컴퓨터가 준 정책에, 폰이나 컴퓨터가 더 최근에 바꾼 설정(설정 폴더의 settings.json)을 덮어 쓴다
        val policy = runCatching { readPolicy(ctx, tree) }.getOrNull()?.let { pol ->
            val base = SyncSettings.fromPolicy(pol)
            if (base == null) pol else SyncSettings.overlay(pol, SyncSettings.effective(base, SyncSettings.read(vaultPath)))
        }
        // 새 방식 정책이면 옛 종류의 폴더(미디어·이력 등)는 받지 않는다(컴퓨터가 아직 공유 중이어도 노트 폴더 안에서 함께 보낸다)
        val simpleVault = if (policy?.optJSONObject("simple") != null) policy.optString("vault") else ""
        // Fork 에 이미 등록된 기기(컴퓨터 추가로 넣었거나 전에 연결한 기기)가 보낸 우리 폴더(inn-…)만 받는다. 컴퓨터 ID 를 정확히 안 넣었어도 된다
        val known = api.deviceIds().filter { it != me }.toSet()
        if (known.isEmpty()) note = tr("Fork 에 컴퓨터가 아직 등록되지 않았습니다. 3단계에서 ‘컴퓨터 추가’를 누르세요.")
        else try {
            // 외부 폴더(음악·PDF 등)의 등록 경로가 지금 설정(외부 폴더 저장 위치)과 다르면 등록을 지워 새 경로로 다시 받게 한다.
            // 받기 전용(컴퓨터 → 폰)일 때만 한다: 폰에서 파일이 사라져도 컴퓨터로 삭제가 가지 않는다. 예전 위치의 파일은 지우지 않는다
            val extRoot = ctx.getSharedPreferences("settings", 0).getString("extRoot", null)
            var moved = 0
            for (f in api.folders()) {
                val id = f.getString("id")
                if (!id.startsWith(ID_PREFIX) || kindOf(id) != "ext") continue
                val want = localPath(vaultPath, id, f.optString("label"), extRoot).trimEnd('/', '\\')
                if (f.optString("path").trimEnd('/', '\\') != want && f.optString("type") == "receiveonly") { api.removeFolder(id); moved++ }
            }
            if (moved > 0) {
                known.forEach { runCatching { api.reconnect(it) } }   // 컴퓨터가 공유 목록을 다시 보내 새 요청이 오게 한다
                note = trf("외부 폴더 %s개의 저장 위치가 바뀌어 새 위치로 다시 받습니다", moved)
            }
            for ((fid, v) in api.pendingFolders()) {
                if (!fid.startsWith(ID_PREFIX)) continue
                if (simpleVault.isNotEmpty() && fid in staleFolders(listOf(fid), simpleVault)) continue
                val from = v.second.filter { it in known }
                if (from.isEmpty()) continue
                val gate = staged && kindOf(fid) == "mar"   // 노트 폴더는 멈춘 채로 받아 무시 목록(전부 가림)부터 걸고 시작한다
                api.acceptFolder(fid, v.first, localPath(vaultPath, fid, v.first, ctx.getSharedPreferences("settings", 0).getString("extRoot", null)), "sendreceive", listOf(me) + from, paused = gate)
                if (gate) { api.setIgnores(fid, Stages.GATE); api.resume(fid) }
                accepted++
            }
        } catch (e: Exception) {
            note = trf("컴퓨터가 보낸 폴더 목록을 읽지 못했습니다(Fork 버전이 오래됐을 수 있습니다): %s", friendly(e))
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
                // 컴퓨터가 정책에서 뺀 외부 폴더는 등록만 지운다(받은 파일은 그대로): 안 지우면 '대기 중'으로 계속 남는다
                if (mods.has("markdown")) {
                    val conn = known.any { runCatching { api.connected(it) }.getOrDefault(false) }
                    val dropped = dropGoneExt(api, vault, SyncSettings.extModules(pol).keys, conn, System.currentTimeMillis())
                    if (dropped.isNotEmpty()) note = trf("컴퓨터가 뺀 외부 폴더 %s개의 등록을 지웠습니다(받은 파일은 그대로 있습니다)", dropped.size)
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
        // 작은 파일이 수만 개일 때 파일마다 디스크에 바로 쓰는(fsync) 대기가 가장 큰 병목이다: 받는 폰의 노트 폴더는 끄고 쓰기를 동시에 여러 개 한다.
        // 컴퓨터가 원본이라 전원이 갑자기 꺼져도 다시 받으면 되므로 안전하다. 프로세스마다 한 번만 올린다(폴더가 다시 시작되므로 자주 올리지 않는다).
        runCatching {
            for (f in api.folders()) {
                val id = f.getString("id")
                if (!id.startsWith(ID_PREFIX) || kindOf(id) !in setOf("mar", "ext") || id in tuned) continue
                tuned.add(id)
                // 파일 하나를 처리하는 데 몇 초씩 걸리는데 동시에 2개(기본)만 처리해 분당 10개 안팎이었다 → 동시 처리(copiers)와 요청 중인 양을 키운다
                // 받는 순서: 최근 수정 순. 노트(.md)는 전부 가장 최근 시각이고 그림은 대부분 오래돼서, 노트가 그림보다 먼저 온다(임의 순서에서는 노트가 분당 33개로 그림의 1/20 이었음)
                if (!f.optBoolean("disableFsync") || f.optInt("maxConcurrentWrites") < 16 || f.optInt("copiers") < 8 || f.optInt("pullerMaxPendingKiB") < 65536 || f.optString("order") != "newestFirst") {
                    f.put("disableFsync", true).put("maxConcurrentWrites", 16).put("copiers", 8).put("pullerMaxPendingKiB", 65536).put("order", "newestFirst"); api.putFolder(f)
                }
            }
        }
        val lines = api.folders().filter { it.getString("id").startsWith(ID_PREFIX) && kindOf(it.getString("id")) != "cfg" }.map {
            val id = it.getString("id")
            val d = runCatching { api.folderDetail(id) }.getOrDefault(FolderLine("", "?", 0L, 0L))
            val rate = etas.getOrPut(id) { Eta() }.update(System.currentTimeMillis(), d.needBytes)
            val conn = (if (pcId.isNotEmpty()) listOf(pcId) else known.toList()).any { c -> runCatching { api.connected(c) }.getOrDefault(false) }
            if (kickIfStuck(api, id, d, conn, System.currentTimeMillis())) note = note.ifEmpty { tr("받기에 실패한 파일이 있어 다시 시도했습니다") }
            val errs = if (d.needFiles > 0) api.folderErrors(id) else emptyList()
            d.copy(label = it.optString("label"), path = it.optString("path"), rate = rate, failed = errs.size, firstError = errs.firstOrNull()?.let { e -> e.first.substringAfterLast('/') + " — " + e.second } ?: "")
        }
        val connected = (if (pcId.isNotEmpty()) listOf(pcId) else known.toList()).any { runCatching { api.connected(it) }.getOrDefault(false) }
        return Report(me, connected, lines, accepted, note)
    }
}
