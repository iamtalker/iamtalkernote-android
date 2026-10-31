package kr.iamtalker.notes

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.net.HttpURLConnection
import java.net.URL

/** 웹 클리퍼로 가져온 마크다운 안의 그림(http 주소)을 내려받아 노트 폴더의 .resources 에 저장하고, 노트에서 가리킬 상대 경로로 바꾼다.
 *  내려받지 못한 그림(용량 초과·오류·그림이 아닌 것)은 주소 그대로 둔다. 반드시 백그라운드 스레드에서 부른다. */
object ClipImages {
    private const val MAX_IMAGES = 40
    private const val MAX_EACH = 8L * 1024 * 1024
    private const val MAX_TOTAL = 40L * 1024 * 1024
    private val IMG = Regex("""!\[([^\]]*)\]\((https?://[^)\s]+)((?:\s+"[^"]*")?)\)""")

    private fun extOf(mime: String): String? = when {
        mime.contains("png") -> "png"
        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
        mime.contains("webp") -> "webp"
        mime.contains("gif") -> "gif"
        else -> null
    }

    /** folder = 노트가 들어갈 노트북 경로(맨 위면 빈 글). 상대 경로의 `../` 깊이를 정하는 데 쓴다 */
    fun localize(ctx: Context, tree: Uri, md: String, folder: String): String {
        val urls = IMG.findAll(md).map { it.groupValues[2] }.distinct().take(MAX_IMAGES).toList()
        if (urls.isEmpty()) return md
        val depth = folder.split('/').count { it.isNotEmpty() }
        val prefix = "../".repeat(depth) + ".resources/"
        val root = DocumentsContract.getTreeDocumentId(tree)
        val res = History.dir(ctx, tree, root, ".resources", true) ?: return md
        val saved = HashMap<String, String>()
        var total = 0L
        for (u in urls) {
            if (total >= MAX_TOTAL) break
            runCatching {
                val c = URL(u).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 15_000; c.instanceFollowRedirects = true
                try {
                    val mime = (c.contentType ?: "").lowercase()
                    val ext = extOf(mime) ?: return@runCatching
                    if (c.responseCode != 200 || c.contentLengthLong > MAX_EACH) return@runCatching
                    val bytes = c.inputStream.use { input ->
                        val buf = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(16 * 1024)
                        while (buf.size() <= MAX_EACH) { val n = input.read(chunk); if (n < 0) break; buf.write(chunk, 0, n) }
                        buf.toByteArray()
                    }
                    if (bytes.size > MAX_EACH || bytes.isEmpty()) return@runCatching
                    val name = java.util.UUID.randomUUID().toString().replace("-", "") + "." + ext
                    val dst = DocumentsContract.createDocument(ctx.contentResolver, History.docUri(tree, res), mime.substringBefore(';'), name) ?: return@runCatching
                    ctx.contentResolver.openOutputStream(dst, "wt")?.use { it.write(bytes) } ?: return@runCatching
                    total += bytes.size
                    saved[u] = prefix + name
                } finally { c.disconnect() }
            }
        }
        if (saved.isEmpty()) return md
        NoteRepo.clearCache()
        return IMG.replace(md) { m -> saved[m.groupValues[2]]?.let { "![${m.groupValues[1]}]($it${m.groupValues[3]})" } ?: m.value }
    }
}
