package kr.iamtalker.notes

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject

/** 데스크톱이 가져온 폴더(pdf·txt·mp3 …)의 요약. 컴퓨터가 노트 폴더 안 숨김 폴더 .imported/docs.jsonl 로 내보낸 것을 읽기 전용으로 보여 준다.
 *  원본 파일은 폰에 없으니 이름·위치·태그·메모와 글 앞부분(최대 2만 자)만 있다. */
data class Doc(
    val id: String, val folder: String, val rel: String, val ext: String, val size: Long, val mtime: Long,
    val title: String, val tags: List<String>, val memo: String, val snip: String
) {
    val name: String get() = "$title.$ext"
}

object DocsRepo {
    const val DIR = ".imported"

    /** 내보낸 한 줄 → 색인에 넣을 행 (파싱할 수 없으면 null) */
    fun parseLine(line: String): Index.DocRow? = runCatching {
        val j = JSONObject(line)
        val tags = j.optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        val text = j.optString("text", "")
        val title = j.optString("title", "")
        val rel = j.optString("rel", "")
        val memo = j.optString("memo", "")
        val extra = tags.joinToString(" ") { "#$it" } + "\n" + memo
        Index.DocRow(
            id = j.getString("id"), folder = j.optString("folder", ""), rel = rel, ext = j.optString("ext", ""), size = j.optLong("size", 0),
            mtime = (j.optDouble("mtime", 0.0) * 1000).toLong(), title = title, tags = tags, memo = memo,
            snip = Meta.snippet(text, title), text = text,
            hay = (title + "\n" + rel + "\n" + text + "\n" + extra).lowercase(), nhay = (title + "\n" + rel + "\n" + extra).lowercase()
        )
    }.getOrNull()

    /** .imported/docs.jsonl 이 바뀌었으면 읽어서 색인의 docs 표를 통째로 바꾼다 → 바뀌었는가 */
    fun sync(ctx: Context, tree: Uri, index: Index, prefs: SharedPreferences): Boolean {
        val root = DocumentsContract.getTreeDocumentId(tree)
        val dir = History.dir(ctx, tree, root, DIR, false)
        if (dir == null) {
            if (index.docCount() > 0) { index.replaceDocs(emptyList()); prefs.edit().remove("docsSig").apply(); return true }
            return false
        }
        val f = History.list(ctx, tree, dir).firstOrNull { !it.isDir && it.name == "docs.jsonl" } ?: return false
        val sig = "${f.size}:${f.modified}"
        if (prefs.getString("docsSig", "") == sig && index.docCount() > 0) return false
        val rows = NoteRepo.read(ctx, History.docUri(tree, f.id)).lineSequence().filter { it.isNotBlank() }.mapNotNull(::parseLine).toList()
        index.replaceDocs(rows)
        prefs.edit().putString("docsSig", sig).apply()
        return true
    }
}
