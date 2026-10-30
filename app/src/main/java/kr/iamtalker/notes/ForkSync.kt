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
    const val PKG = "com.github.catfriend1.syncthingfork"
    const val PORT = 8384
    const val ID_PREFIX = "inn-"

    fun installed(ctx: Context): Boolean = runCatching { ctx.packageManager.getPackageInfo(PKG, 0); true }.getOrDefault(false)

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

    private fun sha1Hex(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** 컴퓨터 앱의 syncpolicy.folder_id 와 같은 값 */
    fun folderId(vault: String, kind: String, mid: String) = "$ID_PREFIX$vault-${kind.take(3)}-" + sha1Hex(mid).take(8)

    // ------------------------------------------------------------ Fork API
    class Api(private val key: String, private val port: Int = PORT) {
        // 127.0.0.1 의 Fork 가 자체 서명 인증서를 쓰므로, 이 연결에만 인증서 검사를 끈다(주소가 루프백일 때만)
        private val ssl: SSLContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }), SecureRandom())
        }

        fun call(path: String, method: String = "GET", body: JSONObject? = null): String {
            val c = URL("https://127.0.0.1:$port$path").openConnection() as HttpsURLConnection
            c.sslSocketFactory = ssl.socketFactory
            c.hostnameVerifier = javax.net.ssl.HostnameVerifier { host, _ -> host == "127.0.0.1" }
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
            if (code !in 200..299) throw IllegalStateException(if (code == 403) "API 키가 맞지 않습니다" else "Fork 응답 $code ${text.take(120)}")
            return text
        }

        fun myId(): String = JSONObject(call("/rest/system/status")).getString("myID")
        fun config(): JSONObject = JSONObject(call("/rest/config"))

        fun putDevice(id: String, name: String) {
            call("/rest/config/devices/$id", "PUT", JSONObject().put("deviceID", id).put("name", name)
                .put("addresses", JSONArray().put("dynamic")).put("autoAcceptFolders", false).put("paused", false))
        }

        fun hasDevice(id: String): Boolean {
            val a = config().getJSONArray("devices")
            return (0 until a.length()).any { a.getJSONObject(it).getString("deviceID") == id }
        }

        /** 컴퓨터가 우리에게 공유하려는 폴더들: 폴더 ID → (이름표, 보낸 기기 ID 들) */
        fun pendingFolders(): Map<String, Pair<String, Set<String>>> {
            val o = runCatching { JSONObject(call("/rest/cluster/pending/folders")) }.getOrNull() ?: return emptyMap()
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

        fun acceptFolder(id: String, label: String, path: String, type: String, deviceIds: List<String>) {
            val f = JSONObject(call("/rest/config/defaults/folder"))
            f.put("id", id).put("label", label).put("path", path).put("type", type).put("paused", false)
            f.put("devices", JSONArray().also { arr -> deviceIds.forEach { arr.put(JSONObject().put("deviceID", it)) } })
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

    data class FolderLine(val label: String, val state: String, val needBytes: Long, val totalBytes: Long)
    data class Report(val myId: String = "", val connected: Boolean = false, val folders: List<FolderLine> = emptyList(), val accepted: Int = 0, val error: String? = null)

    /** 5초마다 한 번: 컴퓨터가 보낸 공유 요청을 받고, 정책 파일이 생겼으면 방향(폴더 종류)을 맞추고, 상태를 모은다 */
    fun tick(ctx: Context, api: Api, pcId: String, tree: Uri): Report {
        val vaultPath = treePath(tree) ?: return Report(error = "이 저장소의 실제 경로를 알 수 없습니다(노트 폴더를 내장 메모리나 SD 카드에서 고르세요)")
        val me = api.myId()
        var accepted = 0
        if (pcId.isNotEmpty() && api.hasDevice(pcId)) {
            for ((fid, v) in api.pendingFolders()) {
                if (!fid.startsWith(ID_PREFIX) || pcId !in v.second) continue
                api.acceptFolder(fid, v.first, localPath(vaultPath, fid, v.first), "sendreceive", listOf(me, pcId))
                accepted++
            }
        }
        // 정책 파일이 도착하면 폴더마다 방향을 맞춘다(처음엔 모두 양방향으로 받는다)
        readPolicy(ctx, tree)?.let { pol ->
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
            }
        }
        val lines = api.folders().filter { it.getString("id").startsWith(ID_PREFIX) }.map {
            val st = runCatching { api.folderStatus(it.getString("id")) }.getOrDefault(Triple("?", 0L, 0L))
            FolderLine(it.optString("label"), st.first, st.second, st.third)
        }
        return Report(me, if (pcId.isEmpty()) false else runCatching { api.connected(pcId) }.getOrDefault(false), lines, accepted)
    }
}
