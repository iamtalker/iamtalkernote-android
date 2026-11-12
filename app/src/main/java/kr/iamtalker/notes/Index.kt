package kr.iamtalker.notes

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri

/** 색인 DB: 노트별 수정 시각·태그와 검색용 본문 사본. 파일이 원본이고 이 DB 는 언제든 지우고 다시 만들 수 있는 캐시다. 폴더(트리)마다 따로 둔다. */
class Index(ctx: Context, tree: Uri) :
    SQLiteOpenHelper(ctx, "index-" + Integer.toHexString(tree.toString().hashCode()) + ".db", null, 8) {

    init {
        setWriteAheadLoggingEnabled(true)   // 색인하는 동안에도 검색이 막히지 않게
    }

    data class Row(val size: Long, val mtime: Long, val updated: Long, val tags: List<String>, val snip: String = "", val created: Long = 0)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(createSql("notes"))
    }

    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 6) {   // 5: 미리보기에서 ‘태그:’ 줄 제외 · 6: 본문 #태그 규칙을 데스크톱과 같게 — 노트 색인을 다시 만든다
            db.execSQL("DROP TABLE IF EXISTS notes")
            db.execSQL(createSql("notes"))
        } else if (old < 8) {
            // 8: 본문 사본(hay)을 맨 끝 열로 옮긴다. hay 가 snip·created 앞에 있으면 목록을 읽을 때(load) 줄마다 본문 사본을 건너가며 읽어 느리다.
            // 노트 파일을 다시 읽지 않고 DB 안에서 옮긴다. 옮기지 못하면(저장 공간 부족 등) 색인을 비우고 다음 새로 고침 때 다시 만든다
            try {
                db.execSQL("DROP TABLE IF EXISTS notes_new")
                db.execSQL(createSql("notes_new"))
                db.execSQL("INSERT INTO notes_new (uri, size, mtime, updated, tags, snip, created, hay) SELECT uri, size, mtime, updated, tags, snip, created, hay FROM notes")
                db.execSQL("DROP TABLE notes")
                db.execSQL("ALTER TABLE notes_new RENAME TO notes")
            } catch (e: android.database.SQLException) {
                db.execSQL("DROP TABLE IF EXISTS notes_new")
                db.execSQL("DROP TABLE IF EXISTS notes")
                db.execSQL(createSql("notes"))
            }
        }
        if (old < 7) db.execSQL("DROP TABLE IF EXISTS docs")   // 7: 가져온 파일 요약(데스크톱이 내보내던 것)은 없어졌다
    }

    /** 색인 표. 크기가 큰 hay(본문 사본)는 맨 끝에 둔다: 요약 열만 읽을 때 본문 사본을 건드리지 않게 */
    private fun createSql(table: String) =
        "CREATE TABLE $table (uri TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, updated INTEGER, tags TEXT, snip TEXT, created INTEGER, hay TEXT)"

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
