package kr.iamtalker.notes

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Sort(val label: String) { DATE_DESC("최근 수정 순"), DATE_ASC("오래된 순"), TITLE("제목 순") }

/** 검색식: 공백 = AND, "정확한 구절", -낱말 또는 NOT 낱말 = 제외, #태그 = 태그. (OR 은 아직 없다) */
class ParsedQuery(val must: List<String>, val not: List<String>, val tags: List<String>)

fun parseQuery(q: String): ParsedQuery {
    val must = ArrayList<String>(); val not = ArrayList<String>(); val tags = ArrayList<String>()
    var negate = false
    for (m in Regex("\"([^\"]+)\"|(\\S+)").findAll(q)) {
        val phrase = m.groups[1]?.value
        val word = (phrase ?: m.groups[2]!!.value)
        if (phrase == null) {
            if (word == "AND" || word == "&") continue
            if (word == "NOT" || word == "!") { negate = true; continue }
        }
        var w = word
        var neg = negate
        negate = false
        if (phrase == null && w.length > 1 && w.startsWith("-")) { neg = true; w = w.substring(1) }
        if (phrase == null && w.length > 1 && w.startsWith("#") && !neg) { tags.add(w.substring(1)); continue }
        w = w.lowercase()
        if (neg) not.add(w) else must.add(w)
    }
    return ParsedQuery(must, not, tags)
}

class NotesModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", 0)

    var tree by mutableStateOf<Uri?>(prefs.getString("tree", null)?.let(Uri::parse))
        private set
    var notes by mutableStateOf<List<Note>>(emptyList())
        private set
    var shown by mutableStateOf<List<Note>>(emptyList())
        private set
    var folders by mutableStateOf<List<String>>(emptyList())
        private set
    var tagCounts by mutableStateOf<List<Pair<String, Int>>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set

    var query by mutableStateOf("")
        private set
    var inBody by mutableStateOf(prefs.getBoolean("inBody", true))
        private set
    var folder by mutableStateOf<String?>(null)
        private set
    var tag by mutableStateOf<String?>(null)
        private set
    var sort by mutableStateOf(Sort.values().getOrElse(prefs.getInt("sort", 0)) { Sort.DATE_DESC })
        private set

    /** 고정(즐겨찾기)한 노트의 uri — 이 폰에만 저장한다(파일에는 쓰지 않는다) */
    var pins by mutableStateOf<Set<String>>(prefs.getStringSet("pins", emptySet())!!.toSet())
        private set
    var folderCounts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    /** 노트북 이름 → 문서 id (노트가 있는 폴더만). 맨 위 폴더는 "" */
    var folderIds by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    fun togglePin(n: Note) {
        val u = n.uri.toString()
        pins = if (u in pins) pins - u else pins + u
        prefs.edit().putStringSet("pins", pins).apply()
        reapply()
    }

    private val main = Handler(Looper.getMainLooper())

    /** 백그라운드 스레드에서 상태 글을 바꿀 때: Compose 상태는 메인 스레드에서만 쓸 수 있다(아니면 IllegalStateException) */
    private fun say(s: String) { main.post { status = s } }

    private var idx: Index? = null
    private var indexed: Set<String> = emptySet()
    private var job: Job? = null
    private var searchJob: Job? = null

    init {
        if (tree != null) refresh()
    }

    private fun index(): Index = idx ?: Index(getApplication(), tree!!).also { idx = it }

    fun pick(uri: Uri) {
        // 기억하지 못해도(일부 기기) 이번 실행에서는 쓸 수 있으니 실패해도 앱을 죽이지 않는다
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        prefs.edit().putString("tree", uri.toString()).apply()
        tree = uri
        idx = null
        NoteRepo.clearCache()
        notes = emptyList(); shown = emptyList()
        folder = null; tag = null
        refresh()
    }

    fun changeQuery(q: String) { query = q; reapply() }
    fun changeInBody(b: Boolean) { inBody = b; prefs.edit().putBoolean("inBody", b).apply(); reapply() }
    fun changeFolder(f: String?) { folder = f; reapply() }
    fun changeTag(t: String?) { tag = t; reapply() }
    fun changeSort(s: Sort) { sort = s; prefs.edit().putInt("sort", s.ordinal).apply(); reapply() }

    /** 목록을 다시 읽고(폴더를 훑음), 바뀐 노트만 읽어 색인을 맞춘다 */
    fun refresh() {
        val t = tree ?: return
        job?.cancel()
        job = viewModelScope.launch {
            loading = true
            status = "노트를 찾는 중…"
            try {
                val scanned = withContext(Dispatchers.IO) { NoteRepo.scan(getApplication(), t) { n -> say("노트를 찾는 중… $n") } }
                val have = withContext(Dispatchers.IO) { index().load() }
                indexed = have.keys
                publish(merge(scanned, have))
                loading = false
                syncIndex(scanned, have)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                status = "폴더를 읽지 못했습니다: ${e.javaClass.simpleName} ${e.message ?: ""}"
                CrashLog.save(getApplication(), e)
                loading = false
            }
        }
    }

    private fun merge(scanned: List<Note>, have: Map<String, Index.Row>): List<Note> = scanned.map { n ->
        val r = have[n.uri.toString()]
        if (r != null && r.size == n.size && r.mtime == n.modified) n.copy(updated = r.updated, tags = r.tags, snip = r.snip) else n
    }

    private fun publish(list: List<Note>) {
        notes = list
        folders = NoteRepo.folders(list)
        val fc = HashMap<String, Int>()
        val ids = HashMap<String, String>()
        for (n in list) {
            var f = n.folder
            ids.putIfAbsent(n.folder, n.parentId)
            while (true) {
                fc[f] = (fc[f] ?: 0) + 1
                if (f.isEmpty()) break
                f = f.substringBeforeLast('/', "")
            }
        }
        folderCounts = fc
        folderIds = ids
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for (n in list) for (t in n.tags) {
            val k = t.lowercase()
            counts[k] = (counts[k]?.first ?: t) to ((counts[k]?.second ?: 0) + 1)
        }
        tagCounts = counts.values.sortedByDescending { it.second }
        status = "노트 ${list.size}개"
        reapply()
    }

    /** 새로 생겼거나 바뀐 노트를 읽어 날짜·태그·본문 사본을 색인 DB 에 적는다(처음 한 번은 오래 걸린다) */
    private suspend fun syncIndex(scanned: List<Note>, have: Map<String, Index.Row>) {
        val todo = scanned.filter { n -> have[n.uri.toString()].let { it == null || it.size != n.size || it.mtime != n.modified } }
        val rows = HashMap<String, Index.Row>(have)
        withContext(Dispatchers.IO) {
            val ix = index()
            val db = ix.writableDatabase
            var done = 0
            for (chunk in todo.chunked(40)) {
                ensureActive()
                db.beginTransaction()
                try {
                    for (n in chunk) {
                        val text = if (n.size in 0..2_000_000) runCatching { NoteRepo.read(getApplication(), n.uri) }.getOrDefault("") else ""
                        val info = Meta.parse(text)
                        val hay = (n.title + "\n" + text).lowercase().take(300_000)
                        val snip = Meta.snippet(text, n.title)
                        ix.put(db, n.uri.toString(), n.size, n.modified, info.updated, info.tags, hay, snip)
                        rows[n.uri.toString()] = Index.Row(n.size, n.modified, info.updated, info.tags, snip)
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                done += chunk.size
                say("색인 중… $done/${todo.size}")
            }
            ix.removeMissing(scanned.map { it.uri.toString() }.toSet())
        }
        indexed = rows.keys
        if (todo.isNotEmpty()) publish(merge(scanned, rows))
        else status = "노트 ${scanned.size}개"
    }

    private fun reapply() {
        searchJob?.cancel()
        val base = notes
        searchJob = viewModelScope.launch {
            delay(120)
            val q = parseQuery(query)
            val f = folder
            val tagFilters = q.tags + listOfNotNull(tag)
            val body = inBody
            val sorter = sort
            val seen = indexed
            val pinned = pins
            val result = withContext(Dispatchers.IO) {
                var list = base
                if (f != null) list = list.filter { it.folder == f || it.folder.startsWith("$f/") }
                for (t in tagFilters) list = list.filter { n -> n.tags.any { it.equals(t, ignoreCase = true) } }
                if (q.must.isNotEmpty() || q.not.isNotEmpty()) {
                    fun titleMatch(n: Note): Boolean {
                        val h = n.title.lowercase()
                        return q.must.all { h.contains(it) } && q.not.none { h.contains(it) }
                    }
                    if (body) {
                        val hit = runCatching { index().search(q.must, q.not) }.getOrDefault(emptySet())
                        // 아직 색인되지 않은 노트는 제목으로만 비교한다
                        list = list.filter { val u = it.uri.toString(); u in hit || (u !in seen && titleMatch(it)) }
                    } else list = list.filter(::titleMatch)
                }
                val ordered = when (sorter) {
                    Sort.DATE_DESC -> list.sortedByDescending { it.date }
                    Sort.DATE_ASC -> list.sortedBy { it.date }
                    Sort.TITLE -> list.sortedBy { it.title.lowercase() }
                }
                if (pinned.isEmpty()) ordered else ordered.filter { it.uri.toString() in pinned } + ordered.filter { it.uri.toString() !in pinned }
            }
            shown = result
        }
    }

    /** 지금 보고 있는 노트북(폴더) 안에 새 노트를 만든다 */
    fun create(title: String): Note? {
        val t = tree ?: return null
        val f = folder
        val parent = if (f == null) null else notes.firstOrNull { it.folder == f }?.parentId
        val n = NoteRepo.create(getApplication(), t, parent, if (parent == null) "" else f ?: "", title) ?: return null
        publish(listOf(n.copy(updated = System.currentTimeMillis())) + notes)
        return n
    }

    // ---- 노트 하나에 하는 일(편집 화면의 ⋮ 메뉴). 백그라운드에서 부르고, 실패하면 예외(노트는 그대로)
    fun renameNote(n: Note, title: String): Note = NoteRepo.rename(getApplication(), n, title)

    fun moveNote(n: Note, dstFolder: String): Note {
        val id = folderIds[dstFolder] ?: if (dstFolder.isEmpty()) android.provider.DocumentsContract.getTreeDocumentId(tree!!)
            else throw IllegalStateException("이 노트북으로는 옮길 수 없습니다")
        return NoteRepo.move(getApplication(), tree!!, n, dstFolder, id)
    }

    fun trashNote(n: Note) = NoteRepo.trash(getApplication(), tree!!, n)
}
