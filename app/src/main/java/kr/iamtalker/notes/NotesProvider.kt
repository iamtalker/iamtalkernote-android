package kr.iamtalker.notes

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

/** 앱 안(앱 전용 저장 공간)의 노트 폴더를 ‘폴더 고르기’로 고른 폴더와 똑같은 모양(SAF 트리)으로 보여 주는 문서 저장소.
 *  공유 저장소(Documents 등)는 파일을 만들 때마다 안드로이드 미디어 저장소를 거쳐 작은 파일 수만 개를 받는 동기화가 건당 몇 초씩 걸린다.
 *  앱 전용 공간은 그 단계를 거치지 않아 100배 넘게 빠르다. 앱의 읽기·쓰기 코드는 그대로 쓰고, 동기화 엔진은 이 폴더(실제 경로)에 직접 쓴다.
 *  문서 id: 맨 위는 "r", 그 아래는 "r/상대/경로" (공유 저장소 트리의 "트리id/상대경로" 규칙과 같다) */
class NotesProvider : DocumentsProvider() {
    companion object {
        const val AUTHORITY = "kr.iamtalker.notes.docs"
        const val ROOT_DOC = "r"
        private const val ROOT_ID = "iatnotes"
        @Volatile var root: File? = null

        fun dir(ctx: Context): File = File(ctx.filesDir, "iamtalkernote").apply { mkdirs() }
        fun treeUri(): Uri = android.provider.DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_DOC)
        fun isOurs(tree: Uri?): Boolean = tree?.authority == AUTHORITY

        private val ROOT_COLS = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_ICON, Root.COLUMN_AVAILABLE_BYTES)
        private val DOC_COLS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_SIZE, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)
    }

    override fun onCreate(): Boolean {
        root = dir(context!!)
        return true
    }

    private fun base(): File = root ?: dir(context!!).also { root = it }

    private fun file(id: String): File {
        val r = base()
        val f = if (id == ROOT_DOC) r else File(r, id.removePrefix("$ROOT_DOC/"))
        val rc = r.canonicalPath
        val fc = f.canonicalPath
        if (fc != rc && !fc.startsWith("$rc/")) throw FileNotFoundException("범위 밖: $id")
        return f
    }

    private fun idOf(f: File): String {
        val rel = f.canonicalFile.relativeTo(base().canonicalFile).path.replace('\\', '/')
        return if (rel.isEmpty() || rel == ".") ROOT_DOC else "$ROOT_DOC/$rel"
    }

    private fun mimeOf(f: File): String {
        if (f.isDirectory) return Document.MIME_TYPE_DIR
        return when (val ext = f.extension.lowercase()) {
            "md" -> "text/markdown"
            "json" -> "application/json"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    private fun flagsOf(f: File, isRoot: Boolean): Int {
        var fl = 0
        if (f.isDirectory) fl = fl or Document.FLAG_DIR_SUPPORTS_CREATE
        else fl = fl or Document.FLAG_SUPPORTS_WRITE
        if (!isRoot) fl = fl or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_MOVE
        return fl
    }

    private fun addRow(c: MatrixCursor, f: File) {
        val isRoot = f.canonicalPath == base().canonicalPath
        val row = c.newRow()
        for (col in c.columnNames) when (col) {
            Document.COLUMN_DOCUMENT_ID -> row.add(idOf(f))
            Document.COLUMN_DISPLAY_NAME -> row.add(if (isRoot) "iamtalkernote" else f.name)
            Document.COLUMN_SIZE -> row.add(if (f.isDirectory) 0L else f.length())
            Document.COLUMN_MIME_TYPE -> row.add(mimeOf(f))
            Document.COLUMN_LAST_MODIFIED -> row.add(f.lastModified())
            Document.COLUMN_FLAGS -> row.add(flagsOf(f, isRoot))
            else -> row.add(null)
        }
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: ROOT_COLS)
        val r = base()
        val row = c.newRow()
        for (col in c.columnNames) when (col) {
            Root.COLUMN_ROOT_ID -> row.add(ROOT_ID)
            Root.COLUMN_FLAGS -> row.add(Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
            Root.COLUMN_TITLE -> row.add("iamtalkernote")
            Root.COLUMN_DOCUMENT_ID -> row.add(ROOT_DOC)
            Root.COLUMN_ICON -> row.add(R.mipmap.ic_launcher)
            Root.COLUMN_AVAILABLE_BYTES -> row.add(r.usableSpace)
            else -> row.add(null)
        }
        return c
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val f = file(documentId)
        if (!f.exists()) throw FileNotFoundException(documentId)
        return MatrixCursor(projection ?: DOC_COLS).also { addRow(it, f) }
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val dir = file(parentDocumentId)
        val c = MatrixCursor(projection ?: DOC_COLS)
        dir.listFiles()?.forEach { addRow(c, it) }
        return c
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val f = file(documentId)
        val m = ParcelFileDescriptor.parseMode(mode)
        if (m and ParcelFileDescriptor.MODE_CREATE != 0) f.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(f, m)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = file(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException(parentDocumentId)
        val clean = displayName.replace('/', '_').replace('\\', '_').ifEmpty { "_" }
        var target = File(parent, clean)
        if (target.exists()) {   // 같은 이름이 있으면 저장소 선택기(SAF)처럼 “이름 (1).확장자” 로 피한다
            val stem = target.nameWithoutExtension
            val ext = target.extension
            var n = 1
            do { target = File(parent, if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext"); n++ } while (target.exists())
        }
        if (mimeType == Document.MIME_TYPE_DIR) { if (!target.mkdir()) throw FileNotFoundException("폴더를 만들 수 없음: $displayName") }
        else if (!target.createNewFile()) throw FileNotFoundException("파일을 만들 수 없음: $displayName")
        return idOf(target)
    }

    override fun deleteDocument(documentId: String) {
        val f = file(documentId)
        if (f.canonicalPath == base().canonicalPath) throw SecurityException("맨 위 폴더는 지울 수 없음")
        if (!f.deleteRecursively()) throw FileNotFoundException("지울 수 없음: $documentId")
    }

    override fun renameDocument(documentId: String, displayName: String): String? {
        val f = file(documentId)
        val target = File(f.parentFile, displayName.replace('/', '_').replace('\\', '_'))
        if (target.exists()) throw IllegalStateException("이미 있음: $displayName")
        if (!f.renameTo(target)) throw FileNotFoundException("이름을 바꿀 수 없음: $documentId")
        return idOf(target)
    }

    override fun moveDocument(sourceDocumentId: String, sourceParentDocumentId: String, targetParentDocumentId: String): String {
        val src = file(sourceDocumentId)
        val dst = File(file(targetParentDocumentId), src.name)
        if (dst.exists()) throw IllegalStateException("이미 있음: ${dst.name}")
        if (!src.renameTo(dst)) throw FileNotFoundException("옮길 수 없음: $sourceDocumentId")
        return idOf(dst)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = runCatching {
        val p = file(parentDocumentId).canonicalPath
        val d = file(documentId).canonicalPath
        d == p || d.startsWith("$p/")
    }.getOrDefault(false)

    override fun getDocumentType(documentId: String): String = mimeOf(file(documentId))
}
