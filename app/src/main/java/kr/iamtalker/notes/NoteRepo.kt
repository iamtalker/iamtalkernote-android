package kr.iamtalker.notes

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** 노트 폴더 안의 .md 파일 하나. 파일이 원본이고 앱은 목록을 메모리에만 둔다(데스크톱 앱과 같은 원칙).
 *  updated: 머리말의 수정 시각(없으면 0), tags: 머리말·본문에서 읽은 태그 */
data class Note(
    val uri: Uri, val name: String, val folder: String, val parentId: String, val modified: Long, val size: Long,
    val updated: Long = 0, val tags: List<String> = emptyList(), val snip: String = "", val created: Long = 0
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

    // ---- 그림: 노트의 상대 경로(../.resources/x.png)를 폴더 트리에서 찾는다
    private val dirCache = HashMap<String, Map<String, String>>()

    fun clearCache() = synchronized(dirCache) { dirCache.clear() }

    private fun children(ctx: Context, tree: Uri, docId: String): Map<String, String> {
        synchronized(dirCache) { dirCache[docId]?.let { return it } }
        val m = HashMap<String, String>()
        ctx.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId),
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) m[c.getString(1) ?: continue] = c.getString(0)
        }
        synchronized(dirCache) { dirCache[docId] = m }
        return m
    }

    /** 노트가 있는 폴더(folder) 기준의 상대 경로 → 파일 uri (없으면 null) */
    fun resolve(ctx: Context, tree: Uri, folder: String, rel: String): Uri? {
        val segs = ArrayList(folder.split('/').filter { it.isNotEmpty() })
        for (p in Uri.decode(rel.substringBefore('?').substringBefore('#')).split('/')) when (p) {
            "", "." -> {}
            ".." -> if (segs.isNotEmpty()) segs.removeAt(segs.size - 1) else return null
            else -> segs.add(p)
        }
        var id = DocumentsContract.getTreeDocumentId(tree)
        for (s in segs) id = children(ctx, tree, id)[s] ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }

    /** 너비가 maxW 의 두 배를 넘으면 줄여서 읽는다(메모리 절약) */
    fun loadBitmap(ctx: Context, uri: Uri, maxW: Int = 1080): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > maxW * 2) sample *= 2
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
    }

    // ---- 노트 하나에 하는 일: 이름 바꾸기·이동·휴지통(데스크톱과 같은 .trash 형식)
    fun rename(ctx: Context, note: Note, title: String): Note {
        val name = safeName(title) + ".md"
        val uri = DocumentsContract.renameDocument(ctx.contentResolver, note.uri, name) ?: throw IllegalStateException("이름을 바꾸지 못했습니다")
        return note.copy(uri = uri, name = name)
    }

    fun move(ctx: Context, tree: Uri, note: Note, dstFolder: String, dstParentId: String): Note {
        val r = ctx.contentResolver
        val uri = DocumentsContract.moveDocument(r, note.uri,
            DocumentsContract.buildDocumentUriUsingTree(tree, note.parentId), DocumentsContract.buildDocumentUriUsingTree(tree, dstParentId))
            ?: throw IllegalStateException("이 폴더에서는 노트를 옮길 수 없습니다")
        // 깊이가 달라지면 그림 링크의 ../ 개수를 맞춘다(데스크톱과 같은 규칙)
        runCatching {
            val text = read(ctx, uri)
            val depth = dstFolder.split('/').count { it.isNotEmpty() }
            val fixed = text.replace(Regex("(?<![\\w/.])(?:\\.\\./)*(\\.resources|_resources)/")) { "../".repeat(depth) + it.groupValues[1] + "/" }
            if (fixed != text) write(ctx, uri, fixed)
        }
        return note.copy(uri = uri, folder = dstFolder, parentId = dstParentId)
    }

    /** 휴지통(.trash/<번호>/item + meta.json)으로 옮긴다 — 데스크톱 앱에서 복원할 수 있다. 어느 단계든 실패하면 지우지 않고 예외를 던진다. */
    fun trash(ctx: Context, tree: Uri, note: Note) {
        val r = ctx.contentResolver
        clearCache()
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        fun docUri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)
        val trashId = children(ctx, tree, rootId)[".trash"]
            ?: DocumentsContract.createDocument(r, docUri(rootId), Document.MIME_TYPE_DIR, ".trash")?.let { DocumentsContract.getDocumentId(it) }
            ?: throw IllegalStateException("휴지통 폴더를 만들 수 없습니다")
        val tid = "${System.currentTimeMillis()}-" + Integer.toHexString((Math.random() * 0xFFFFFF).toInt()).padStart(6, '0')
        val tidId = DocumentsContract.createDocument(r, docUri(trashId), Document.MIME_TYPE_DIR, tid)?.let { DocumentsContract.getDocumentId(it) }
            ?: throw IllegalStateException("휴지통 항목을 만들 수 없습니다")
        try {
            val rel = if (note.folder.isEmpty()) note.name else note.folder + "/" + note.name
            val meta = DocumentsContract.createDocument(r, docUri(tidId), "application/json", "meta.json")
                ?: throw IllegalStateException("휴지통 정보를 만들 수 없습니다")
            val json = "{\"rel\": " + org.json.JSONObject.quote(rel) + ", \"deleted\": " + (System.currentTimeMillis() / 1000.0) + ", \"dir\": false}"
            write(ctx, meta, json)
            val moved = DocumentsContract.moveDocument(r, note.uri, docUri(note.parentId), docUri(tidId))
                ?: throw IllegalStateException("이 폴더에서는 휴지통으로 옮길 수 없습니다")
            if (DocumentsContract.renameDocument(r, moved, "item") == null) {
                runCatching { DocumentsContract.moveDocument(r, moved, docUri(tidId), docUri(note.parentId)) }   // 되돌리기
                throw IllegalStateException("휴지통 항목 이름을 정하지 못해 되돌렸습니다")
            }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(r, docUri(tidId)) }   // 빈 휴지통 항목만 치운다(노트는 그대로)
            throw e
        } finally {
            clearCache()
        }
    }

    private fun safeName(title: String) = title.trim().ifEmpty { "제목 없음" }.replace(Regex("[\\/:*?\"<>|]"), "_")

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
