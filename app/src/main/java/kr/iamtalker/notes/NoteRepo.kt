package kr.iamtalker.notes

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** 노트 폴더 안의 .md 파일 하나. 파일이 원본이고 앱은 목록을 메모리에만 둔다(데스크톱 앱과 같은 원칙).
 *  updated: 머리말의 수정 시각(없으면 0), tags: 머리말·본문에서 읽은 태그 */
data class Note(
    val uri: Uri, val name: String, val folder: String, val parentId: String, val modified: Long, val size: Long,
    val updated: Long = 0, val tags: List<String> = emptyList()
) {
    val title: String get() = name.removeSuffix(".md").removeSuffix(".MD")

    /** 정렬·표시에 쓰는 시각: 머리말 updated, 없으면 파일 수정 시각 */
    val date: Long get() = if (updated > 0) updated else modified
}

object NoteRepo {
    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE)

    /** 점으로 시작하는 폴더(.history .trash .resources …)와 _resources 는 데스크톱 앱처럼 건너뛴다 */
    private fun skipDir(name: String) = name.startsWith(".") || name == "_resources"

    fun scan(ctx: Context, tree: Uri, onProgress: (Int) -> Unit = {}): List<Note> {
        val out = ArrayList<Note>()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        fun walk(docId: String, folder: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            val dirs = ArrayList<Pair<String, String>>()
            ctx.contentResolver.query(children, columns, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    if (c.getString(2) == Document.MIME_TYPE_DIR) {
                        if (!skipDir(name)) dirs.add(id to (if (folder.isEmpty()) name else "$folder/$name"))
                    } else if (name.endsWith(".md", ignoreCase = true)) {
                        out.add(Note(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, folder, docId, c.getLong(3), c.getLong(4)))
                    }
                }
            }
            onProgress(out.size)
            for ((id, f) in dirs) walk(id, f)
        }
        walk(rootId, "")
        return out
    }

    /** 노트가 있는 폴더 이름(경로)과 그 상위 폴더 목록 */
    fun folders(notes: List<Note>): List<String> {
        val set = sortedSetOf<String>()
        for (n in notes) {
            var f = n.folder
            while (f.isNotEmpty()) {
                set.add(f)
                f = f.substringBeforeLast('/', "")
            }
        }
        return set.toList()
    }

    fun read(ctx: Context, uri: Uri): String =
        ctx.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""

    fun write(ctx: Context, uri: Uri, text: String) {
        // "wt": 기존 내용을 비우고 쓴다
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
    }

    /** 새 노트 파일을 만든다(parentId: 넣을 폴더의 문서 id, null 이면 맨 위) → 만든 노트 */
    fun create(ctx: Context, tree: Uri, parentId: String?, folder: String, title: String): Note? {
        val pid = parentId ?: DocumentsContract.getTreeDocumentId(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, pid)
        val name = title.trim().ifEmpty { "제목 없음" }.replace(Regex("[\\\\/:*?\"<>|]"), "_") + ".md"
        val uri = DocumentsContract.createDocument(ctx.contentResolver, parent, "text/markdown", name) ?: return null
        return Note(uri, name, folder, pid, System.currentTimeMillis(), 0)
    }
}
