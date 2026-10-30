package kr.iamtalker.notes

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import org.json.JSONObject
import java.security.MessageDigest

/** 데스크톱 앱과 같은 형식의 보관 폴더를 다룬다: 노트 버전(.history/<해시>/<밀리초>.md), 변경 이력(.history/journal.jsonl), 휴지통(.trash/<번호>/item + meta.json).
 *  모든 함수는 파일 시스템을 만지므로 백그라운드에서 부른다. 실패는 예외로 알린다(부르는 쪽이 노트를 건드리지 않게 처리). */
object History {
    const val HISTORY = ".history"
    const val TRASH = ".trash"

    data class Entry(val id: String, val name: String, val isDir: Boolean, val size: Long, val modified: Long)

    private fun rootId(tree: Uri) = DocumentsContract.getTreeDocumentId(tree)
    fun docUri(tree: Uri, id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    /** 캐시 없이 폴더의 내용을 읽는다 */
    fun list(ctx: Context, tree: Uri, docId: String): List<Entry> {
        val out = ArrayList<Entry>()
        ctx.contentResolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId),
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED),
            null, null, null
        )?.use { c ->
            while (c.moveToNext()) out.add(Entry(c.getString(0), c.getString(1) ?: continue, c.getString(2) == Document.MIME_TYPE_DIR, c.getLong(3), c.getLong(4)))
        }
        return out
    }

    /** parent 안에서 name 폴더를 찾고, 없으면(create=true) 만든다 → 문서 id (없으면 null) */
    fun dir(ctx: Context, tree: Uri, parentId: String, name: String, create: Boolean = true): String? {
        list(ctx, tree, parentId).firstOrNull { it.isDir && it.name == name }?.let { return it.id }
        if (!create) return null
        val u = DocumentsContract.createDocument(ctx.contentResolver, docUri(tree, parentId), Document.MIME_TYPE_DIR, name) ?: return null
        return DocumentsContract.getDocumentId(u)
    }

    /** 볼트의 기본 폴더 구조(데스크톱과 같다). 동기화 도구에서 폴더 경로를 고를 수 있도록 빠진 것만 미리 만든다(있는 것은 건드리지 않는다) → 새로 만든 폴더 이름들 */
    val LAYOUT = listOf(HISTORY, TRASH, ".resources", ".imported", ".sync")

    fun ensureLayout(ctx: Context, tree: Uri): List<String> {
        val root = rootId(tree)
        val have = list(ctx, tree, root).filter { it.isDir }.map { it.name }.toSet()
        val made = ArrayList<String>()
        for (name in LAYOUT) if (name !in have && dir(ctx, tree, root, name) != null) made.add(name)
        return made
    }

    fun delete(ctx: Context, tree: Uri, id: String) {
        DocumentsContract.deleteDocument(ctx.contentResolver, docUri(tree, id))
    }

    fun relOf(n: Note) = if (n.folder.isEmpty()) n.name else n.folder + "/" + n.name

    internal fun hash(rel: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(rel.lowercase().toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }.take(20)
    }

    // ------------------------------------------------------------ 노트 버전
    data class Version(val id: String, val ms: Long, val size: Long)

    private fun histDir(ctx: Context, tree: Uri, rel: String, create: Boolean): String? {
        val h = dir(ctx, tree, rootId(tree), HISTORY, create) ?: return null
        return dir(ctx, tree, h, hash(rel), create)
    }

    /** 이 노트의 이전 버전들, 최신 순 */
    fun versions(ctx: Context, tree: Uri, rel: String): List<Version> {
        val d = histDir(ctx, tree, rel, false) ?: return emptyList()
        return list(ctx, tree, d).filter { !it.isDir && it.name.endsWith(".md") && it.name.removeSuffix(".md").all(Char::isDigit) }
            .map { Version(it.id, it.name.removeSuffix(".md").toLong(), it.size) }.sortedByDescending { it.ms }
    }

    fun readVersion(ctx: Context, tree: Uri, v: Version): String = NoteRepo.read(ctx, docUri(tree, v.id))

    /** old(덮어쓰기 전 내용)를 버전으로 남긴다. 비었거나 새 내용과 같으면 건너뛰고, force 가 아니면 마지막 버전이 minutes 분 안일 때도 건너뛴다. 개수는 keep 으로 자른다 */
    fun snapshot(ctx: Context, tree: Uri, rel: String, old: String, new: String, force: Boolean, keep: Int = 10, minutes: Int = 1): Boolean {
        if (old == new || old.isBlank() || keep <= 0) return false
        val d = histDir(ctx, tree, rel, true) ?: return false
        val have = list(ctx, tree, d).filter { it.name.endsWith(".md") }.mapNotNull { e -> e.name.removeSuffix(".md").toLongOrNull()?.let { it to e.id } }.sortedByDescending { it.first }
        val now = System.currentTimeMillis()
        if (!force && minutes > 0 && have.isNotEmpty() && now - have[0].first < minutes * 60_000L) return false
        val u = DocumentsContract.createDocument(ctx.contentResolver, docUri(tree, d), "application/octet-stream", "$now.md") ?: return false
        NoteRepo.write(ctx, u, old)
        for ((_, id) in have.drop(keep - 1)) runCatching { delete(ctx, tree, id) }
        return true
    }

    fun deleteVersion(ctx: Context, tree: Uri, v: Version) = delete(ctx, tree, v.id)

    /** 노트 이름이 바뀌거나 옮겨지면 버전 기록도 따라간다(해시 폴더 이름만 바꾼다) */
    fun moveHistory(ctx: Context, tree: Uri, oldRel: String, newRel: String) {
        val a = hash(oldRel); val b = hash(newRel)
        if (a == b) return
        val h = dir(ctx, tree, rootId(tree), HISTORY, false) ?: return
        val entries = list(ctx, tree, h)
        val src = entries.firstOrNull { it.isDir && it.name == a } ?: return
        if (entries.any { it.name == b }) return
        DocumentsContract.renameDocument(ctx.contentResolver, docUri(tree, src.id), b)
    }

    // ------------------------------------------------------------ 변경 이력(journal.jsonl)
    data class Rec(val t: Double, val ev: String, val path: String, val to: String?, val d: String)

    private val lastEdit = HashMap<String, Long>()

    private fun journalFile(ctx: Context, tree: Uri, create: Boolean): Uri? {
        val h = dir(ctx, tree, rootId(tree), HISTORY, create) ?: return null
        list(ctx, tree, h).firstOrNull { !it.isDir && it.name == "journal.jsonl" }?.let { return docUri(tree, it.id) }
        if (!create) return null
        return DocumentsContract.createDocument(ctx.contentResolver, docUri(tree, h), "application/octet-stream", "journal.jsonl")
    }

    /** 변경 한 줄을 적는다. edit 는 같은 노트를 1분에 한 번만(데스크톱과 같게). 실패해도 본 작업을 막지 않는다 */
    fun journal(ctx: Context, tree: Uri, ev: String, rel: String, to: String? = null, detail: String = "") {
        runCatching {
            if (ev == "edit") {
                val now = System.currentTimeMillis()
                synchronized(lastEdit) {
                    if (now - (lastEdit[rel] ?: 0) < 60_000) return
                    lastEdit[rel] = now
                }
            }
            val rec = JSONObject().put("t", Math.round(System.currentTimeMillis() / 100.0) / 10.0).put("ev", ev).put("path", rel)
            if (to != null) rec.put("to", to)
            if (detail.isNotEmpty()) rec.put("d", detail)
            val f = journalFile(ctx, tree, true) ?: return
            val line = rec.toString() + "\n"
            try {
                ctx.contentResolver.openOutputStream(f, "wa")?.use { it.write(line.toByteArray(Charsets.UTF_8)) }
            } catch (e: Exception) {   // "wa" 를 지원하지 않는 제공자: 읽어서 다시 쓴다
                val old = NoteRepo.read(ctx, f)
                NoteRepo.write(ctx, f, old + line)
            }
        }
    }

    /** 최근 것부터 */
    fun readJournal(ctx: Context, tree: Uri, limit: Int = 2000): List<Rec> {
        val f = journalFile(ctx, tree, false) ?: return emptyList()
        val out = ArrayList<Rec>()
        for (line in NoteRepo.read(ctx, f).lineSequence().toList().takeLast(limit)) {
            runCatching {
                val j = JSONObject(line)
                out.add(Rec(j.getDouble("t"), j.getString("ev"), j.getString("path"), if (j.has("to")) j.getString("to") else null, j.optString("d", "")))
            }
        }
        out.reverse()
        return out
    }

    // ------------------------------------------------------------ 휴지통
    data class TrashItem(val id: String, val rel: String, val deleted: Double, val isNote: Boolean)

    /** .trash 안의 항목들(최근 지운 것부터). 노트 하나인 것만 폰에서 복원할 수 있다 */
    fun trashItems(ctx: Context, tree: Uri): List<TrashItem> {
        val t = dir(ctx, tree, rootId(tree), TRASH, false) ?: return emptyList()
        val out = ArrayList<TrashItem>()
        for (e in list(ctx, tree, t).filter { it.isDir }.sortedByDescending { it.name }.take(400)) {
            runCatching {
                val kids = list(ctx, tree, e.id)
                val meta = kids.firstOrNull { it.name == "meta.json" } ?: return@runCatching
                val j = JSONObject(NoteRepo.read(ctx, docUri(tree, meta.id)))
                val item = kids.firstOrNull { it.name == "item" }
                val isNote = item != null && !item.isDir && !j.optBoolean("dir", false) && !j.has("into") && j.getString("rel").endsWith(".md", ignoreCase = true)
                out.add(TrashItem(e.id, j.getString("rel"), j.optDouble("deleted", 0.0), isNote))
            }
        }
        return out
    }

    /** 휴지통의 노트를 원래 자리로 되돌린다(같은 이름이 있으면 ' (복원)' 을 붙인다) → 되돌린 경로 */
    fun restore(ctx: Context, tree: Uri, item: TrashItem): String {
        val r = ctx.contentResolver
        val kids = list(ctx, tree, item.id)
        val file = kids.firstOrNull { it.name == "item" && !it.isDir } ?: throw IllegalStateException("휴지통 항목에 노트가 없습니다")
        val parts = item.rel.split('/')
        var parent = rootId(tree)
        for (seg in parts.dropLast(1)) parent = dir(ctx, tree, parent, seg, true) ?: throw IllegalStateException("노트북을 만들 수 없습니다: $seg")
        val have = list(ctx, tree, parent).map { it.name }.toSet()
        var name = parts.last()
        var i = 0
        while (name in have) {
            i++
            name = name.removeSuffix(".md").removeSuffix(".MD") + " (복원" + (if (i == 1) "" else "$i") + ").md"
        }
        val renamed = DocumentsContract.renameDocument(r, docUri(tree, file.id), name) ?: throw IllegalStateException("이름을 정하지 못했습니다")
        val moved = DocumentsContract.moveDocument(r, renamed, docUri(tree, item.id), docUri(tree, parent))
        if (moved == null) {
            runCatching { DocumentsContract.renameDocument(r, renamed, "item") }   // 되돌려 놓는다
            throw IllegalStateException("이 폴더에서는 옮길 수 없습니다")
        }
        runCatching { delete(ctx, tree, item.id) }   // 남은 meta.json 이 든 빈 항목을 치운다
        return (parts.dropLast(1) + name).joinToString("/")
    }

    fun purge(ctx: Context, tree: Uri, item: TrashItem) = delete(ctx, tree, item.id)
}
