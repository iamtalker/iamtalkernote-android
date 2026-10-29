package kr.iamtalker.notes

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

/** 색인 DB: 노트별 수정 시각·태그와 검색용 본문 사본. 파일이 원본이고 이 DB 는 언제든 지우고 다시 만들 수 있는 캐시다. 폴더(트리)마다 따로 둔다. */
class Index(ctx: Context, tree: Uri) :
    SQLiteOpenHelper(ctx, "index-" + Integer.toHexString(tree.toString().hashCode()) + ".db", null, 4) {

    init {
        setWriteAheadLoggingEnabled(true)   // 색인하는 동안에도 검색이 막히지 않게
    }

    data class Row(val size: Long, val mtime: Long, val updated: Long, val tags: List<String>, val snip: String = "", val created: Long = 0)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE notes (uri TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, updated INTEGER, tags TEXT, hay TEXT, snip TEXT, created INTEGER)")
        createDocs(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 3) {
            db.execSQL("DROP TABLE IF EXISTS notes")
            db.execSQL("CREATE TABLE notes (uri TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, updated INTEGER, tags TEXT, hay TEXT, snip TEXT, created INTEGER)")
        }
        createDocs(db)   // 4: 데스크톱이 내보낸 가져온 파일 요약
    }

    private fun createDocs(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS docs (id TEXT PRIMARY KEY, folder TEXT, rel TEXT, ext TEXT, size INTEGER, mtime INTEGER, title TEXT, " +
            "tags TEXT, memo TEXT, snip TEXT, hay TEXT, nhay TEXT, text TEXT)")
    }

    /** 가져온 파일 한 줄(Docs.kt 가 만든다). hay=이름·경로·글·태그·메모, nhay=글만 뺀 것 */
    data class DocRow(
        val id: String, val folder: String, val rel: String, val ext: String, val size: Long, val mtime: Long, val title: String,
        val tags: List<String>, val memo: String, val snip: String, val text: String, val hay: String, val nhay: String
    )

    fun docCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM docs", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun replaceDocs(rows: List<DocRow>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("docs", null, null)
            for (r in rows) {
                val v = ContentValues()
                v.put("id", r.id); v.put("folder", r.folder); v.put("rel", r.rel); v.put("ext", r.ext); v.put("size", r.size); v.put("mtime", r.mtime)
                v.put("title", r.title); v.put("tags", r.tags.joinToString("\n")); v.put("memo", r.memo); v.put("snip", r.snip)
                v.put("hay", r.hay); v.put("nhay", r.nhay); v.put("text", r.text)
                db.insertWithOnConflict("docs", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 가져온 파일 목록(글 본문은 읽지 않는다) */
    fun listDocs(): List<Doc> {
        val out = ArrayList<Doc>()
        readableDatabase.rawQuery("SELECT id, folder, rel, ext, size, mtime, title, tags, memo, snip FROM docs", null).use { c ->
            while (c.moveToNext()) out.add(Doc(c.getString(0), c.getString(1).orEmpty(), c.getString(2).orEmpty(), c.getString(3).orEmpty(), c.getLong(4), c.getLong(5),
                c.getString(6).orEmpty(), c.getString(7).orEmpty().split('\n').filter { it.isNotEmpty() }, c.getString(8).orEmpty(), c.getString(9).orEmpty()))
        }
        return out
    }

    fun docText(id: String): String =
        readableDatabase.rawQuery("SELECT text FROM docs WHERE id=?", arrayOf(id)).use { c -> if (c.moveToFirst()) c.getString(0).orEmpty() else "" }

    /** 검색식 조건(QueryParser.toSql)에 맞는 가져온 파일의 id. withText=false 면 이름·경로·태그·메모로만 찾는다 */
    fun searchDocs(where: String, args: Array<String>, withText: Boolean): Set<String> {
        val cond = if (withText) where else where.replace("hay LIKE", "nhay LIKE")
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT id FROM docs WHERE $cond", args).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    /** 색인에 있는 모든 노트의 요약(본문은 읽지 않는다) */
    fun load(): Map<String, Row> {
        val out = HashMap<String, Row>()
        readableDatabase.rawQuery("SELECT uri, size, mtime, updated, tags, snip, created FROM notes", null).use { c ->
            while (c.moveToNext()) {
                val tags = c.getString(4).orEmpty().split('\n').filter { it.isNotEmpty() }
                out[c.getString(0)] = Row(c.getLong(1), c.getLong(2), c.getLong(3), tags, c.getString(5).orEmpty(), c.getLong(6))
            }
        }
        return out
    }

    /** 노트 하나를 색인한다(여러 개는 begin/commit 으로 묶어 부른다) */
    fun put(db: SQLiteDatabase, uri: String, size: Long, mtime: Long, updated: Long, tags: List<String>, hay: String, snip: String, created: Long) {
        val v = ContentValues()
        v.put("uri", uri); v.put("size", size); v.put("mtime", mtime); v.put("updated", updated)
        v.put("tags", tags.joinToString("\n") { it }); v.put("hay", hay); v.put("snip", snip); v.put("created", created)
        db.insertWithOnConflict("notes", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeMissing(keep: Set<String>) {
        val db = writableDatabase
        val stale = ArrayList<String>()
        db.rawQuery("SELECT uri FROM notes", null).use { c -> while (c.moveToNext()) if (c.getString(0) !in keep) stale.add(c.getString(0)) }
        if (stale.isEmpty()) return
        db.beginTransaction()
        try {
            for (u in stale) db.delete("notes", "uri=?", arrayOf(u))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** uri → 색인된 글(제목\n본문, 소문자) */
    fun hayOf(uris: List<String>): Map<String, String> {
        val out = HashMap<String, String>()
        for (chunk in uris.chunked(400)) {
            readableDatabase.rawQuery("SELECT uri, hay FROM notes WHERE uri IN (" + chunk.joinToString(",") { "?" } + ")", chunk.toTypedArray())
                .use { c -> while (c.moveToNext()) out[c.getString(0)] = c.getString(1).orEmpty() }
        }
        return out
    }

    /** hay 가 패턴(LIKE)에 맞는 노트의 글들 */
    fun hayLike(pattern: String): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery("SELECT hay FROM notes WHERE hay LIKE ?", arrayOf(pattern)).use { c -> while (c.moveToNext()) out.add(c.getString(0).orEmpty()) }
        return out
    }

    /** WHERE 조건(QueryParser.toSql)에 맞는 노트의 uri */
    fun searchWhere(where: String, args: Array<String>): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT uri FROM notes WHERE $where", args).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }
}
