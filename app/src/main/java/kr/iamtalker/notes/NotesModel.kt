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

enum class Sort(val label: String) {
    RELEVANCE("관련도순"), DATE_DESC("수정 최근 순"), DATE_ASC("수정 오래된 순"), CREATED_DESC("생성 최근 순"), TITLE("제목 순")
}

enum class Period(val label: String, val days: Int) { ALL("전체 기간", 0), TODAY("오늘", 1), WEEK("7일", 7), MONTH("30일", 30), YEAR("1년", 365) }

/** 저장된 검색(스마트 폴더): 이름과 검색식. 이 폰에만 저장한다 */
data class Saved(val name: String, val query: String)

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
    var period by mutableStateOf(Period.ALL)
        private set
    var tag by mutableStateOf<String?>(null)
        private set
    var sort by mutableStateOf(Sort.values().getOrElse(prefs.getInt("sort2", 1)) { Sort.DATE_DESC })
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

    fun index(): Index = idx ?: Index(getApplication(), tree!!).also { idx = it }

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
    fun changeSort(s: Sort) { sort = s; prefs.edit().putInt("sort2", s.ordinal).apply(); reapply() }
    fun changePeriod(p: Period) { period = p; reapply() }

    // ---- 최근 검색(30개)·저장된 검색
    private fun loadStrings(key: String): List<String> = runCatching {
        val a = org.json.JSONArray(prefs.getString(key, "[]")); (0 until a.length()).map { a.getString(it) }
    }.getOrDefault(emptyList())

    var recent by mutableStateOf(loadStrings("recent"))
        private set
    var saved by mutableStateOf(runCatching {
        val a = org.json.JSONArray(prefs.getString("saved", "[]")); (0 until a.length()).map { Saved(a.getJSONObject(it).getString("name"), a.getJSONObject(it).getString("q")) }
    }.getOrDefault(emptyList()))
        private set

    fun addRecent(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        recent = (listOf(t) + recent.filter { it != t }).take(30)
        prefs.edit().putString("recent", org.json.JSONArray(recent).toString()).apply()
    }

    private fun persistSaved() {
        val a = org.json.JSONArray()
        for (x in saved) a.put(org.json.JSONObject().put("name", x.name).put("q", x.query))
        prefs.edit().putString("saved", a.toString()).apply()
    }

    fun saveSearch(name: String) {
        if (query.isBlank()) return
        saved = saved.filter { it.name != name } + Saved(name.ifBlank { query.trim().take(20) }, query.trim())
        persistSaved()
    }

    fun deleteSaved(x: Saved) { saved = saved - x; persistSaved() }
    fun applySaved(x: Saved) { folder = null; tag = null; period = Period.ALL; query = x.query; reapply() }

    // ---- 여러 노트 선택
    var selected by mutableStateOf<Set<String>>(emptySet())
        private set
    var message by mutableStateOf<String?>(null)

    fun toggleSelect(n: Note) { val u = n.uri.toString(); selected = if (u in selected) selected - u else selected + u }
    fun clearSelect() { selected = emptySet() }
    fun selectAll() { selected = shown.map { it.uri.toString() }.toSet() }

    /** 고른 노트들에 같은 일을 한다(백그라운드). 하나가 실패해도 나머지는 계속하고, 끝나면 결과를 알리고 목록을 새로 읽는다 */
    fun batch(label: String, work: (Note) -> Unit) {
        val targets = notes.filter { it.uri.toString() in selected }
        selected = emptySet()
        viewModelScope.launch {
            var ok = 0; var fail = 0; var lastErr = ""
            withContext(Dispatchers.IO) {
                for ((i, n) in targets.withIndex()) {
                    try { work(n); ok++ } catch (e: Exception) { fail++; lastErr = e.message ?: e.javaClass.simpleName }
                    say("$label ${i + 1}/${targets.size}")
                }
            }
            message = "$label: ${ok}개 완료" + if (fail > 0) ", ${fail}개 실패 ($lastErr)" else ""
            refresh()
        }
    }

    /** 노트 글 아래 '태그:' 줄에 태그를 더한다(이전 내용은 노트 버전으로 남김) */
    fun addTagsTo(n: Note, tags: List<String>) {
        val ctx = getApplication<Application>()
        val text = NoteRepo.read(ctx, n.uri)
        val out = NoteText.setTagLine(text, NoteText.tagLineTags(text) + tags)
        if (out == text) return
        NoteRepo.write(ctx, n.uri, out)
        runCatching {
            History.snapshot(ctx, tree!!, History.relOf(n), text, out, true)
            History.journal(ctx, tree!!, "tags", History.relOf(n), detail = tags.joinToString(" ") { "#$it" })
        }
    }

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
        if (r != null && r.size == n.size && r.mtime == n.modified) n.copy(updated = r.updated, tags = r.tags, snip = r.snip, created = r.created) else n
    }

    /** 동기화 충돌 복사본(이름.sync-conflict-….md)과 그 원래 노트 */
    var conflicts by mutableStateOf<List<Conflicts.Pair>>(emptyList())
        private set

    fun resolveConflict(p: Conflicts.Pair, choice: Conflicts.Choice) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { Conflicts.resolve(getApplication(), tree!!, this@NotesModel, p, choice) }
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
            }
            refresh()
        }
    }

    private fun publish(all: List<Note>) {
        val (conf, list) = all.partition { Conflicts.isConflict(it.name) }
        conflicts = conf.map { c ->
            val on = Conflicts.originalName(c.name)
            Conflicts.Pair(c, list.firstOrNull { it.parentId == c.parentId && it.name == on })
        }.sortedByDescending { it.conflict.modified }
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
        // 앱 밖에서(동기화 도구·다른 편집기) 고친 노트: 색인에 있던 노트가 바뀌었고, 이 앱이 저장한 기록(변경 이력)보다 늦은 것
        val external: Set<String> = withContext(Dispatchers.IO) {
            val changed = todo.filter { have.containsKey(it.uri.toString()) }
            if (changed.isEmpty()) emptySet() else runCatching {
                val last = HashMap<String, Double>()
                for (r in History.readJournal(getApplication(), tree!!, 20000)) if (r.t > (last[r.path] ?: 0.0)) last[r.path] = r.t
                changed.filter { it.modified / 1000.0 > (last[History.relOf(it)] ?: 0.0) + 5 }.map { it.uri.toString() }.toSet()
            }.getOrDefault(emptySet())
        }
        if (external.size > 200) runCatching { History.journal(getApplication(), tree!!, "external", ".", detail = "앱 밖에서 노트 ${external.size}개 수정") }
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
                        if (external.size in 1..200 && n.uri.toString() in external) runCatching {
                            // 바뀌기 전 내용은 알 수 없지만, 고친 뒤의 내용을 노트 버전으로 남겨 이후에 돌아올 수 있게 한다
                            History.journal(getApplication(), tree!!, "external", History.relOf(n), detail = "앱 밖에서 수정")
                            History.snapshot(getApplication(), tree!!, History.relOf(n), text, "", true)
                        }
                        val hay = (n.title + "\n" + text).lowercase().take(300_000)
                        val snip = Meta.snippet(text, n.title)
                        ix.put(db, n.uri.toString(), n.size, n.modified, info.updated, info.tags, hay, snip, info.created)
                        rows[n.uri.toString()] = Index.Row(n.size, n.modified, info.updated, info.tags, snip, info.created)
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
            val q = QueryParser.parse(query)
            val f = folder
            val t = tag
            val body = inBody
            val sorter = sort
            val seen = indexed
            val pinned = pins
            val per = period
            val result = withContext(Dispatchers.IO) {
                var list = base
                if (f != null) list = list.filter { it.folder == f || it.folder.startsWith("$f/") }
                if (t != null) list = list.filter { n -> n.tags.any { it.equals(t, ignoreCase = true) } }
                if (per != Period.ALL) {
                    val since = System.currentTimeMillis() - per.days * 86_400_000L
                    list = list.filter { it.date >= since }
                }
                if (q != null) {
                    if (body) {
                        val args = ArrayList<String>()
                        val where = QueryParser.toSql(q, args)
                        val hit = runCatching { index().searchWhere(where, args.toTypedArray()) }.getOrDefault(emptySet())
                        // 아직 색인되지 않은 노트는 제목·태그로만 비교한다
                        list = list.filter { val u = it.uri.toString(); u in hit || (u !in seen && QueryParser.matches(q, it.title, it.tags)) }
                    } else list = list.filter { QueryParser.matches(q, it.title, it.tags) }
                }
                val terms = if (q != null) QueryParser.terms(q) else emptyList()
                val ordered = when (sorter) {
                    Sort.RELEVANCE ->
                        if (terms.isEmpty()) list.sortedByDescending { it.date }
                        else list.sortedWith(compareByDescending<Note> { n ->
                            val ti = n.title.lowercase(); val sn = n.snip.lowercase()
                            terms.sumOf { w -> (if (ti.contains(w)) 10 else 0) + (if (sn.contains(w)) 3 else 0) }
                        }.thenByDescending { it.date })
                    Sort.DATE_DESC -> list.sortedByDescending { it.date }
                    Sort.DATE_ASC -> list.sortedBy { it.date }
                    Sort.CREATED_DESC -> list.sortedByDescending { if (it.created > 0) it.created else it.modified }
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
        bg { History.journal(getApplication(), t, "create", History.relOf(n)) }
        return n
    }

    // ---- 노트 하나에 하는 일(편집 화면의 ⋮ 메뉴). 백그라운드에서 부르고, 실패하면 예외(노트는 그대로)
    /** 백그라운드에서 한 번 실행(화면이 닫혀도 끝까지 간다). 실패는 무시 */
    fun bg(block: () -> Unit) { viewModelScope.launch(Dispatchers.IO) { runCatching(block) } }

    fun renameNote(n: Note, title: String): Note {
        val r = NoteRepo.rename(getApplication(), n, title)
        runCatching {
            History.moveHistory(getApplication(), tree!!, History.relOf(n), History.relOf(r))
            History.journal(getApplication(), tree!!, "rename", History.relOf(n), History.relOf(r))
        }
        return r
    }

    fun moveNote(n: Note, dstFolder: String): Note {
        val id = folderIds[dstFolder] ?: if (dstFolder.isEmpty()) android.provider.DocumentsContract.getTreeDocumentId(tree!!)
            else throw IllegalStateException("이 노트북으로는 옮길 수 없습니다")
        val r = NoteRepo.move(getApplication(), tree!!, n, dstFolder, id)
        runCatching {
            History.moveHistory(getApplication(), tree!!, History.relOf(n), History.relOf(r))
            History.journal(getApplication(), tree!!, "move", History.relOf(n), History.relOf(r))
        }
        return r
    }

    fun trashNote(n: Note) {
        NoteRepo.trash(getApplication(), tree!!, n)
        History.journal(getApplication(), tree!!, "delete", History.relOf(n), detail = "휴지통")
    }

    // ---- 다른 앱에서 공유로 받은 글: (제목 후보, 본문). 앱에서 노트북을 고르면 새 노트로 저장한다
    var shared by mutableStateOf<Pair<String, String>?>(null)

    fun handleSend(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        shared = (intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "") to text
    }

    /** 공유받은 글을 새 노트로 저장한다. 주소가 있으면 데스크톱 클리퍼처럼 머리말 source 에 적는다 */
    fun saveShared(title: String, text: String, folder: String) {
        val t = tree ?: return
        shared = null
        viewModelScope.launch {
            try {
                val note = withContext(Dispatchers.IO) {
                    val parent = if (folder.isEmpty()) null else folderIds[folder]
                    val n = NoteRepo.create(getApplication(), t, parent, if (parent == null) "" else folder, title) ?: throw IllegalStateException("노트를 만들 수 없습니다")
                    val url = Regex("https?://\\S+").find(text)?.value
                    NoteRepo.write(getApplication(), n.uri, (if (url != null) "---\nsource: $url\n---\n\n" else "") + text.trim() + "\n")
                    History.journal(getApplication(), t, "create", History.relOf(n), detail = "공유로 받음")
                    n
                }
                message = "저장했습니다: ${note.title}"
                refresh()
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
            }
        }
    }

    // ---- 사진·그림 첨부: 볼트 안 .resources 에 복사하고 노트에서 가리킬 상대 경로를 돌려준다
    fun attachImage(uri: Uri, note: Note): String {
        val ctx = getApplication<Application>()
        val t = tree!!
        val mime = ctx.contentResolver.getType(uri) ?: "image/jpeg"
        val ext = when {
            mime.contains("png") -> "png"
            mime.contains("webp") -> "webp"
            mime.contains("gif") -> "gif"
            else -> "jpg"
        }
        val root = android.provider.DocumentsContract.getTreeDocumentId(t)
        val res = History.dir(ctx, t, root, ".resources", true) ?: throw IllegalStateException(".resources 폴더를 만들 수 없습니다")
        val name = java.util.UUID.randomUUID().toString().replace("-", "") + "." + ext
        val dst = android.provider.DocumentsContract.createDocument(ctx.contentResolver, History.docUri(t, res), mime, name)
            ?: throw IllegalStateException("그림 파일을 만들 수 없습니다")
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            ctx.contentResolver.openOutputStream(dst, "wt")?.use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException("그림을 읽을 수 없습니다")
        NoteRepo.clearCache()
        val depth = note.folder.split('/').count { it.isNotEmpty() }
        return "../".repeat(depth) + ".resources/" + name
    }

    // ---- 태그 관리: 그 태그가 든 모든 노트에서 이름 바꾸기(이미 있는 이름이면 합치기)·삭제
    fun retagAll(old: String, new: String?) {
        selected = notes.filter { n -> n.tags.any { it.equals(old, ignoreCase = true) } }.map { it.uri.toString() }.toSet()
        batch(if (new == null) "태그 삭제" else "태그 이름 변경") { n ->
            val ctx = getApplication<Application>()
            val text = NoteRepo.read(ctx, n.uri)
            val out = NoteText.retag(text, old, new)
            if (out != text) {
                runCatching { History.snapshot(ctx, tree!!, History.relOf(n), text, out, true) }
                NoteRepo.write(ctx, n.uri, out)   // 수정 시각은 바꾸지 않는다(데스크톱과 같게)
                History.journal(ctx, tree!!, "tags", History.relOf(n), detail = if (new == null) "#$old 삭제" else "#$old → #$new")
            }
        }
    }

    // ---- 정리 도구
    fun findDuplicates(): List<List<Note>> = Cleanup.duplicates(index(), notes)

    fun findUnusedMedia(progress: (String) -> Unit): List<Cleanup.Media> = Cleanup.unusedMedia(getApplication(), tree!!, index(), progress)

    /** 안 쓰는 미디어 파일을 완전히 지운다(휴지통 없음) → 지운 개수 */
    fun deleteMedia(list: List<Cleanup.Media>): Int {
        var n = 0
        for (m in list) runCatching { History.delete(getApplication(), tree!!, m.id); n++ }
        NoteRepo.clearCache()
        History.journal(getApplication(), tree!!, "cleanup", ".", detail = "안 쓰는 미디어 ${n}개 삭제")
        return n
    }

    /** 노트들을 휴지통으로 보낸다(batch 와 같은 방식으로 결과를 알리고 목록을 새로 읽는다) */
    fun trashMany(list: List<Note>) {
        selected = list.map { it.uri.toString() }.toSet()
        batch("휴지통") { n -> trashNote(n) }
    }
}
