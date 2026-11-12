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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class Sort(val label: String) {
    RELEVANCE(tr("관련도순")), DATE_DESC(tr("수정 최근 순")), DATE_ASC(tr("수정 오래된 순")), CREATED_DESC(tr("생성 최근 순")), TITLE(tr("제목 순"))
}

enum class Period(val label: String, val days: Int) { ALL(tr("전체 기간"), 0), TODAY(tr("오늘"), 1), WEEK(tr("7일"), 7), MONTH(tr("30일"), 30), YEAR(tr("1년"), 365) }

/** 저장된 검색(스마트 폴더): 이름과 검색식. 이 폰에만 저장한다 */
data class Saved(val name: String, val query: String)

class NotesModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", 0)
    /** 빠르게 연속 공유해도 같은 경로의 노트북 생성이 겹치지 않게 한다. */
    private val sharedSaveMutex = Mutex()

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
    /** 즐겨찾기(컴퓨터와 맞춤): 노트 경로(폴더/이름.md) 집합. 설정 폴더의 favorites-*.json 에서 읽는다(Favorites.kt) */
    var favs by mutableStateOf<Set<String>>(emptySet())
        private set
    var favOnly by mutableStateOf(false)
        private set

    var folderCounts by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    /** 저장 폴더(루트) 아래 전체 노트 수(바로 있는 노트 + 하위 노트북 노트). 루트 줄에 표시한다 */
    var rootCount by mutableStateOf(0)
        private set

    /** 노트북 이름 → 문서 id (노트가 있는 폴더만). 맨 위 폴더는 "" */
    var folderIds by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** 설정 폴더에서 즐겨찾기를 다시 읽는다(컴퓨터에서 바꾼 것이 동기화로 왔을 수 있다) */
    fun reloadFavs() {
        val t = tree ?: return
        viewModelScope.launch {
            val s = withContext(Dispatchers.IO) { ForkSync.treePath(t)?.let { vp -> Favorites.load(vp).filterValues { it }.keys } ?: emptySet() }
            if (s != favs) { favs = s; reapply() }
        }
    }

    fun toggleFavorite(n: Note) {
        val t = tree ?: return
        val rel = Favorites.relOf(n)
        val on = rel !in favs
        favs = if (on) favs + rel else favs - rel
        reapply()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ForkSync.treePath(t)?.let { vp ->
                    Favorites.set(vp, rel, on)
                    EmbeddedSync.api(getApplication()).scanSettingsFolder()      // 컴퓨터에 바로 가게 설정 폴더를 즉시 검사
                }
            }
        }
    }

    /** 서랍의 '즐겨찾기': 켜면 폴더·태그 거르기는 해제한다 */
    fun changeFav(on: Boolean) { favOnly = on; if (on) { folder = null; tag = null }; reapply() }

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

    /** 앱 안(앱 전용 공간)에 노트 폴더를 둔다: 작은 파일이 수만 개인 동기화가 공유 저장소보다 훨씬 빠르다. 앱을 지우면 폰의 사본도 지워진다(원본은 컴퓨터) */
    fun useAppFolder() = pick(NotesProvider.treeUri())

    /** 앱 안 폴더로 옮기기 전 폴더(공유 저장소)가 남아 있으면 그 경로. 없으면 null */
    fun oldFolderPath(): String? {
        if (!NotesProvider.isOurs(tree)) return null
        val old = prefs.getString("oldTree", null)?.let(Uri::parse) ?: return null
        return ForkSync.treePath(old)?.takeIf { java.io.File(it).isDirectory }
    }

    /** 예전 폴더 가져오기 진행: 처음엔 복사할 파일을 찾는 중(total<0), 그다음 done/total, 끝나면 finished 에 결과 글 */
    class ImportState(val total: Int = -1, val done: Int = 0, val finished: String? = null)
    var importState by mutableStateOf<ImportState?>(null)
        private set
    val importing: Boolean get() = importState?.let { it.finished == null } == true
    fun closeImport() { if (!importing) importState = null }

    /** 예전 폴더에서 새 위치(앱 안)에 **없는** 파일만 복사한다(이미 받은 것은 건너뜀). 폰에서만 만든 노트가 컴퓨터로 올라가지 않았을 수 있어서 옮긴 뒤에도 잃지 않게 한다.
     *  예전 폴더는 지우지 않는다 */
    fun importFromOld() {
        val oldPath = oldFolderPath() ?: return
        val dstRoot = NotesProvider.root ?: return
        if (importing) return
        importState = ImportState()
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val src = java.io.File(oldPath)
                    val todo = ArrayList<java.io.File>()
                    var skipped = 0
                    src.walkTopDown().onEnter { d -> d == src || (!d.name.startsWith(".st") && d.name != ".stversions") }.forEach { f ->
                        if (!f.isFile) return@forEach
                        val n = f.name
                        if (n.startsWith(".syncthing.") || n.startsWith("~syncthing~") || n == ".stfolder" || n == ".stignore") return@forEach
                        if (java.io.File(dstRoot, f.relativeTo(src).path).exists()) skipped++ else todo.add(f)
                    }
                    main.post { importState = ImportState(todo.size, 0) }
                    var copied = 0
                    for (f in todo) {
                        val dst = java.io.File(dstRoot, f.relativeTo(src).path)
                        dst.parentFile?.mkdirs()
                        if (!dst.createNewFile()) { skipped++; continue }   // 그 사이 동기화가 먼저 만들었다
                        f.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
                        dst.setLastModified(f.lastModified())
                        copied++
                        if (copied % 50 == 0) { val d = copied; main.post { importState = ImportState(todo.size, d) } }
                    }
                    copied to skipped
                }
                importState = ImportState(result.first, result.first, trf("예전 폴더에서 %s개를 가져왔습니다(이미 있는 %s개는 건너뜀). 예전 폴더는 지우지 않았습니다.", result.first, result.second))
                refresh()
            } catch (e: Exception) {
                importState = ImportState(0, 0, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun pick(uri: Uri) {
        val before = tree
        // 기억하지 못해도(일부 기기) 이번 실행에서는 쓸 수 있으니 실패해도 앱을 죽이지 않는다. 앱 안 폴더는 권한이 필요 없다
        if (!NotesProvider.isOurs(uri)) runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        // 폴더를 바꾸면 동기화 엔진에 등록된 폴더 경로도 새로 잡아야 한다: 등록을 지우면(파일은 그대로) 컴퓨터가 다시 보낸 공유를 새 경로로 받는다
        if (before != null && before != uri) {
            prefs.edit().putBoolean("resyncFolders", true).apply()
            if (!NotesProvider.isOurs(before)) prefs.edit().putString("oldTree", before.toString()).apply()   // 예전 폴더의 노트를 나중에 가져올 수 있게 기억
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
    fun changeFolder(f: String?) { folder = f; favOnly = false; reapply() }
    fun changeTag(t: String?) { tag = t; favOnly = false; reapply() }

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
    /** 메시지 창에 [복사] 를 보일지: 전부 성공한 작업 결과에는 복사가 필요 없다 */
    var messageCopy by mutableStateOf(true)

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
            messageCopy = fail > 0   // 실패가 있을 때만 오류 글을 복사할 수 있게 한다
            message = trf("%s: %s개 완료", label, ok) + if (fail > 0) trf(", %s개 실패 (%s)", fail, lastErr) else ""
            refresh()
        }
    }

    /** 노트 글 아래 '태그:' 줄에 태그를 더한다(이전 내용은 노트 버전으로 남김) */
    fun addTagsTo(n: Note, tags: List<String>) {
        val ctx = getApplication<Application>()
        val text = NoteRepo.read(ctx, n.uri)
        val out = NoteText.setTagLine(text, NoteText.tagLineTags(text) + tags)
        if (out == text) return
        NoteRepo.write(ctx, n.uri, NoteText.ensureDates(out, n.creationDate, n.date))
        runCatching {
            History.snapshot(ctx, tree!!, History.relOf(n), text, out, true)
            History.journal(ctx, tree!!, "tags", History.relOf(n), detail = tags.joinToString(" ") { "#$it" })
        }
    }

    /** 목록을 다시 읽고(폴더를 훑음), 바뀐 노트만 읽어 색인을 맞춘다 */
    private var lastScan = 0L

    // ---- 설정 화면의 값(이 폰에만 저장)
    /** 테마 이름(AppThemes): 컴퓨터 앱과 같은 이름. 예전 설정(0 시스템 · 1 밝게 · 2 어둡게)은 시스템/라이트 파랑/다크 파랑으로 옮긴다 */
    var themeName by mutableStateOf(prefs.getString("themeName", null) ?: when (prefs.getInt("theme", 0)) { 1 -> "라이트 파랑"; 2 -> "다크 파랑"; else -> AppThemes.SYSTEM })
        private set
    var openEditing by mutableStateOf(prefs.getBoolean("openEditing", false))   // 노트를 열 때 바로 편집
    var clipWifiOnly by mutableStateOf(prefs.getBoolean("clipWifiOnly", false)) // 웹 클리퍼: 그림은 와이파이(데이터 요금이 없는 연결)일 때만 내려받는다
        private set
    var editorFont by mutableStateOf(prefs.getInt("editorFont", 15))
        private set
    var autoRefresh by mutableStateOf(prefs.getBoolean("autoRefresh", true))
        private set
    /** 내 테마의 배경색·강조색. 예전의 강조색 직접 고르기(themeAccent)는 그 테마의 바탕색 + 같은 강조색인 내 테마로 옮긴다 */
    var customBg by mutableStateOf(prefs.getLong("customBg", AppThemes.CUSTOM_BG))
        private set
    var customAccent by mutableStateOf(prefs.getLong("customAccent", AppThemes.CUSTOM_ACCENT))
        private set
    init {
        AppThemes.legacy[themeName]?.let { (bg, acc) ->
            customBg = bg; customAccent = acc; themeName = AppThemes.CUSTOM
            prefs.edit().putString("themeName", themeName).putLong("customBg", bg).putLong("customAccent", acc).apply()
        }
        if (prefs.contains("themeAccent")) {
            customBg = AppThemes.all[themeName]?.bg ?: AppThemes.CUSTOM_BG
            customAccent = prefs.getLong("themeAccent", AppThemes.CUSTOM_ACCENT)
            themeName = AppThemes.CUSTOM
            prefs.edit().remove("themeAccent").putString("themeName", themeName).putLong("customBg", customBg).putLong("customAccent", customAccent).apply()
        }
    }
    fun changeTheme(name: String) { themeName = name; prefs.edit().putString("themeName", name).apply() }
    /** 내 테마의 색을 바꾸고 바로 그 테마를 쓴다 */
    fun changeCustom(bg: Long = customBg, accent: Long = customAccent) {
        customBg = bg; customAccent = accent; themeName = AppThemes.CUSTOM
        prefs.edit().putLong("customBg", bg).putLong("customAccent", accent).putString("themeName", themeName).apply()
    }
    fun changeOpenEditing(b: Boolean) { openEditing = b; prefs.edit().putBoolean("openEditing", b).apply() }
    /** 웹 클리퍼: 공유로 저장할 노트북(경로). null 이면 공유 창을 열 때 보고 있던 노트북, "" 이면 맨 위. 없는 노트북이면 저장할 때 만든다 */
    var clipFolder by mutableStateOf<String?>(prefs.getString("clipFolder", null))
    fun changeClipFolder(f: String?) { clipFolder = f; prefs.edit().apply { if (f == null) remove("clipFolder") else putString("clipFolder", f) }.apply() }

    /** 외부 폴더(컴퓨터에서 따로 공유한 폴더: 음악·PDF 등)를 받을 곳. null 이면 자동(공유 저장소의 iamtalkernote-files). SD카드 폴더도 된다 */
    var extRoot by mutableStateOf<String?>(prefs.getString("extRoot", null))
    fun changeExtRoot(p: String?) { extRoot = p; prefs.edit().apply { if (p == null) remove("extRoot") else putString("extRoot", p) }.apply() }

    fun changeClipWifiOnly(b: Boolean) { clipWifiOnly = b; prefs.edit().putBoolean("clipWifiOnly", b).apply() }
    fun changeEditorFont(sp: Int) { editorFont = sp; prefs.edit().putInt("editorFont", sp).apply() }
    var wikiAuto by mutableStateOf(prefs.getBoolean("wikiAuto", false).also { WikiAuto.enabled = it })
        private set
    fun changeWikiAuto(b: Boolean) { wikiAuto = b; WikiAuto.enabled = b; WikiAuto.setTitles(notes.map { it.title }); prefs.edit().putBoolean("wikiAuto", b).apply() }
    fun changeAutoRefresh(b: Boolean) { autoRefresh = b; prefs.edit().putBoolean("autoRefresh", b).apply() }

    /** Syncthing-Fork 연결 상태(동기화 마법사가 보여 준다) */
    var forkReport by mutableStateOf<ForkSync.Report?>(null)
        private set
    private var forkJob: Job? = null

    /** Fork API 키가 저장돼 있으면, 앱이 켜져 있는 동안 어느 화면에 있든 5초마다 컴퓨터가 보낸 공유 폴더를 받아 정해진 위치에 둔다
     *  (마법사 화면을 벗어나면 Fork 의 알림을 직접 눌러 경로를 써야 했던 문제) */
    fun watchFork() {
        if (forkJob?.isActive == true) return
        forkJob = viewModelScope.launch {
            while (true) {
                val key = prefs.getString("forkKey", "").orEmpty().trim()
                val t = tree
                if (key.length >= 20 && t != null) {
                    val pc = prefs.getString("pcId", "").orEmpty().trim()
                    forkReport = withContext(Dispatchers.IO) {
                        runCatching { ForkSync.tick(getApplication(), ForkSync.Api(key), pc, t) }.getOrElse { ForkSync.Report(error = ForkSync.friendly(it)) }
                    }
                }
                delay(5000)
            }
        }
    }

    /** 앱으로 돌아왔을 때: 동기화로 파일이 바뀌었을 수 있으니 마지막으로 읽은 지 minMs 가 지났으면 다시 읽는다. 읽는 중이거나 색인 중이면 건드리지 않는다 */
    fun refreshIfStale(minMs: Long = 60_000) {
        if (!autoRefresh || tree == null || loading || job?.isActive == true) return
        if (System.currentTimeMillis() - lastScan < minMs) return
        refresh()
    }

    fun refresh() {
        val t = tree ?: return
        lastScan = System.currentTimeMillis()
        reloadFavs()
        job?.cancel()
        job = viewModelScope.launch {
            loading = true
            status = tr("노트를 찾는 중…")
            try {
                withContext(Dispatchers.IO) { runCatching { History.ensureLayout(getApplication(), t) } }   // 실패해도(읽기 전용 폴더 등) 노트 읽기는 계속한다
                val t0 = System.nanoTime()
                val scanned = withContext(Dispatchers.IO) { NoteRepo.scan(getApplication(), t) { n -> say(trf("노트를 찾는 중… %s", n)) } }
                android.util.Log.i("IATPERF", "scan ${scanned.size} notes ${(System.nanoTime() - t0) / 1_000_000} ms fast=${NoteRepo.fastBase(t) != null}")
                val have = withContext(Dispatchers.IO) { index().load() }
                indexed = have.keys
                publish(merge(scanned, have))
                loading = false
                syncIndex(scanned, have)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                status = trf("폴더를 읽지 못했습니다: %s %s", e.javaClass.simpleName, e.message ?: "")
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
                val raw = e.message ?: e.javaClass.simpleName
                message = if (raw.contains("Missing file") || raw.contains("FileNotFound")) trf("이 충돌 복사본은 이미 없어졌습니다(동기화가 정리했을 수 있습니다). 목록을 새로 고칩니다.\n\n%s", raw) else raw
            }
            refresh()
        }
    }

    /** 파일이 없어진 노트를 목록에서 바로 뺀다(새로 읽기 전에 눌러도 안전하게) */
    fun forget(n: Note) { publish(notes.filter { it.uri != n.uri } + conflicts.map { it.conflict }) }

    private fun publish(all: List<Note>) {
        val (conf, list) = all.partition { Conflicts.isConflict(it.name) }
        conflicts = conf.map { c ->
            val on = Conflicts.originalName(c.name)
            Conflicts.Pair(c, list.firstOrNull { it.parentId == c.parentId && it.name == on })
        }.sortedByDescending { it.conflict.modified }
        notes = list
        WikiAuto.setTitles(list.map { it.title })
        val fset = sortedSetOf<String>()
        fset.addAll(NoteRepo.folders(list))
        for (k in NoteRepo.dirs.keys) { var f = k; while (f.isNotEmpty()) { fset.add(f); f = f.substringBeforeLast('/', "") } }   // 빈 노트북도
        folders = fset.toList()
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
        for ((k, v) in NoteRepo.dirs) ids.putIfAbsent(k, v)
        folderCounts = fc
        rootCount = list.size
        folderIds = ids
        val counts = LinkedHashMap<String, Pair<String, Int>>()
        for (n in list) for (t in n.tags) {
            val k = t.lowercase()
            counts[k] = (counts[k]?.first ?: t) to ((counts[k]?.second ?: 0) + 1)
        }
        tagCounts = counts.values.sortedByDescending { it.second }
        status = trf("노트 %s개", list.size)
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
        val t1 = System.nanoTime()
        val rows = HashMap<String, Index.Row>(have)
        withContext(Dispatchers.IO) {
            val ix = index()
            val db = ix.writableDatabase
            var done = 0
            val base = NoteRepo.fastBase(tree!!)
            for (chunk in todo.chunked(200)) {
                ensureActive()
                // 본문 읽기는 기다리는 시간이 대부분이라 여러 개를 동시에 읽는다(파일 직접 읽기가 되면 그것으로, 아니면 저장소 API 로)
                val texts: List<String> = coroutineScope {
                    chunk.chunked(maxOf(1, (chunk.size + 3) / 4)).map { part ->
                        async {
                            part.map { n ->
                                if (n.size !in 0..2_000_000) ""
                                else runCatching {
                                    if (base != null) java.io.File(base, History.relOf(n)).readText(Charsets.UTF_8) else NoteRepo.read(getApplication(), n.uri)
                                }.getOrDefault("")
                            }
                        }
                    }.awaitAll().flatten()
                }
                db.beginTransaction()
                try {
                    for ((ni, n) in chunk.withIndex()) {
                        val text = texts[ni]
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
                say(trf("색인 중… %s/%s", done, todo.size))
            }
            ix.removeMissing(scanned.map { it.uri.toString() }.toSet())
        }
        android.util.Log.i("IATPERF", "index todo=${todo.size} ${(System.nanoTime() - t1) / 1_000_000} ms")
        indexed = rows.keys
        if (todo.isNotEmpty()) publish(merge(scanned, rows))
        else status = trf("노트 %s개", scanned.size)
    }

    private fun reapply() {
        searchJob?.cancel()
        val base = notes
        searchJob = viewModelScope.launch {
            delay(120)
            val dq = DateQuery.split(query)
            val q = QueryParser.parse(dq.rest)
            val f = folder
            val t = tag
            val body = inBody
            val sorter = sort
            val seen = indexed
            val pinned = pins
            val favSet = if (favOnly) favs else null
            val per = period
            val result = withContext(Dispatchers.IO) {
                var list = if (dq.bad.isNotEmpty()) emptyList() else base   // 읽을 수 없는 날짜는 기간 없이 찾지 않고 비워서 알린다
                if (dq.from != null || dq.to != null) {
                    val byCreated = sorter == Sort.CREATED_DESC
                    list = list.filter { n ->
                        val t = if (byCreated) n.creationDate else n.date
                        (dq.from == null || t >= dq.from) && (dq.to == null || t <= dq.to)
                    }
                }
                if (f != null) list = list.filter { it.folder == f || it.folder.startsWith("$f/") }
                if (t != null) list = list.filter { n -> n.tags.any { it.equals(t, ignoreCase = true) } }
                if (favSet != null) list = list.filter { Favorites.relOf(it) in favSet }
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
                    Sort.CREATED_DESC -> list.sortedWith(compareByDescending<Note> { it.creationDate }.thenBy { it.title.lowercase() }.thenBy { History.relOf(it).lowercase() })
                    Sort.TITLE -> list.sortedBy { it.title.lowercase() }
                }
                if (pinned.isEmpty()) ordered else ordered.filter { it.uri.toString() in pinned } + ordered.filter { it.uri.toString() !in pinned }
            }
            shown = result
            if (dq.bad.isNotEmpty()) status = tr("날짜를 읽을 수 없습니다: ") + dq.bad.joinToString(", ") + tr("  (예: date:20200202-20221231)")
        }
    }

    /** 지금 보고 있는 노트북(폴더) 안에 새 노트를 만든다 */
    fun create(title: String): Note? {
        val t = tree ?: return null
        val f = folder
        val parent = if (f == null) null else folderIds[f] ?: notes.firstOrNull { it.folder == f }?.parentId
        val n = NoteRepo.create(getApplication(), t, parent, if (parent == null) "" else f ?: "", title) ?: return null
        publish(listOf(n.copy(updated = System.currentTimeMillis())) + notes)
        bg { History.journal(getApplication(), t, "create", History.relOf(n)) }
        return n
    }

    // ---- 노트 하나에 하는 일(편집 화면의 ⋮ 메뉴). 백그라운드에서 부르고, 실패하면 예외(노트는 그대로)
    /** 백그라운드에서 한 번 실행(화면이 닫혀도 끝까지 간다). 실패는 무시 */
    fun bg(block: () -> Unit) { viewModelScope.launch(Dispatchers.IO) { runCatching(block) } }

    /** 이름을 바꾸거나 옮긴 노트의 즐겨찾기를 새 경로로 옮긴다(안 옮기면 풀려 버린다) */
    private fun favMove(old: Note, new: Note) {
        val o = Favorites.relOf(old)
        val nw = Favorites.relOf(new)
        if (o == nw || o !in favs) return
        favs = favs - o + nw
        runCatching { ForkSync.treePath(tree!!)?.let { Favorites.move(it, o, nw) } }
    }

    fun renameNote(n: Note, title: String): Note {
        val r = NoteRepo.rename(getApplication(), n, title)
        favMove(n, r)
        runCatching {
            History.moveHistory(getApplication(), tree!!, History.relOf(n), History.relOf(r))
            History.journal(getApplication(), tree!!, "rename", History.relOf(n), History.relOf(r))
        }
        return r
    }

    fun moveNote(n: Note, dstFolder: String): Note {
        val id = folderIds[dstFolder] ?: if (dstFolder.isEmpty()) android.provider.DocumentsContract.getTreeDocumentId(tree!!)
            else throw IllegalStateException(tr("이 노트북으로는 옮길 수 없습니다"))
        val r = NoteRepo.move(getApplication(), tree!!, n, dstFolder, id)
        favMove(n, r)
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

    /** 알림을 눌러 열었을 때 설정 → 동기화 화면으로 가라는 신호 */
    var openSync by mutableStateOf(false)

    fun handleSend(intent: Intent?) {
        if (intent?.getBooleanExtra(OPEN_SYNC, false) == true) openSync = true
        if (intent == null || intent.action != Intent.ACTION_SEND || intent.type?.startsWith("text/") != true) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        shared = (intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "") to text
        startClip(text)
    }

    /** 공유받은 주소의 본문 가져오기 상태: 가져오는 중 / 끝(제목·마크다운) / 실패(안내 글). 주소 없는 글(선택한 글 공유)은 null */
    class ClipState(val loading: Boolean, val title: String = "", val markdown: String = "", val error: String = "")
    var clip by mutableStateOf<ClipState?>(null)
    private var clipJob: kotlinx.coroutines.Job? = null

    /** 글이 사실상 주소 하나(앞뒤 설명 200자 이하)일 때만 페이지를 가져온다 */
    fun clipUrl(text: String): String? {
        val m = Regex("https?://\\S+").find(text) ?: return null
        return if (text.replace(m.value, "").trim().length <= 200) m.value else null
    }

    fun startClip(text: String) {
        clipJob?.cancel()
        val url = clipUrl(text)
        if (url == null) { clip = null; return }
        clip = ClipState(true)
        viewModelScope.launch(Dispatchers.IO) { WebClip.refreshRules(getApplication()) }
        clipJob = viewModelScope.launch {
            clip = try {
                val r = WebClip.fetch(getApplication(), url)
                ClipState(false, r.title, r.markdown)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ClipState(false, error = if (e is kotlinx.coroutines.TimeoutCancellationException) tr("시간이 너무 오래 걸립니다") else (e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** 같은 주소를 전에 저장했으면 그 노트 제목(이 폰에서 저장한 것만 기억) */
    fun clippedTitle(url: String): String? = getApplication<Application>().getSharedPreferences("settings", 0).getString("clip:$url", null)

    fun cancelShared() { clipJob?.cancel(); clip = null; shared = null }

    /** 노트북 경로(a/b/c)를 없는 것만 만들어 가며 맞추고 마지막 폴더의 문서 id 를 돌려준다 */
    private fun ensureFolder(t: Uri, path: String): String {
        val ctx = getApplication<Application>()
        return NoteRepo.ensureFolderPath(
            rootDocId(), path,
            find = { parentId, name -> NoteRepo.findFolder(ctx, t, parentId, name) },
            create = { parentId, name -> NoteRepo.createFolder(ctx, t, parentId, name) }
        )
    }

    /** 공유받은 글을 새 노트로 저장한다. 주소가 있으면 데스크톱 클리퍼처럼 머리말 source 에 적는다 */
    fun saveShared(title: String, text: String, folder: String) {
        val t = tree ?: return
        val body = clip?.takeIf { !it.loading && it.markdown.isNotEmpty() }?.markdown
        clipJob?.cancel(); clip = null
        shared = null
        viewModelScope.launch {
            try {
                val note = withContext(Dispatchers.IO) { sharedSaveMutex.withLock {
                    val parent = if (folder.isEmpty()) null else ensureFolder(t, folder)
                    val n = NoteRepo.create(getApplication(), t, parent, if (parent == null) "" else folder, title) ?: throw IllegalStateException(tr("노트를 만들 수 없습니다"))
                    val url = Regex("https?://\\S+").find(text)?.value
                    val content = if (body != null) ClipImages.localize(getApplication(), t, body, if (parent == null) "" else folder, wifiOnly = clipWifiOnly) else text.trim()
                    NoteRepo.write(getApplication(), n.uri, NoteText.ensureDates((if (url != null) "---\nsource: $url\n---\n\n" else "") + content + "\n", n.creationDate, n.date))
                    if (url != null && body != null) getApplication<Application>().getSharedPreferences("settings", 0).edit().putString("clip:$url", n.title).apply()
                    History.journal(getApplication(), t, "create", History.relOf(n), detail = "공유로 받음")
                    n
                } }
                android.widget.Toast.makeText(getApplication(), trf("저장했습니다: %s", note.title), android.widget.Toast.LENGTH_SHORT).show()
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
        val res = History.dir(ctx, t, root, ".resources", true) ?: throw IllegalStateException(tr(".resources 폴더를 만들 수 없습니다"))
        val name = java.util.UUID.randomUUID().toString().replace("-", "") + "." + ext
        val dst = android.provider.DocumentsContract.createDocument(ctx.contentResolver, History.docUri(t, res), mime, name)
            ?: throw IllegalStateException(tr("그림 파일을 만들 수 없습니다"))
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            ctx.contentResolver.openOutputStream(dst, "wt")?.use { out -> input.copyTo(out) }
        } ?: throw IllegalStateException(tr("그림을 읽을 수 없습니다"))
        NoteRepo.clearCache()
        val depth = note.folder.split('/').count { it.isNotEmpty() }
        return "../".repeat(depth) + ".resources/" + name
    }

    // ---- 태그 관리: 그 태그가 든 모든 노트에서 이름 바꾸기(이미 있는 이름이면 합치기)·삭제
    fun retagAll(old: String, new: String?) {
        selected = notes.filter { n -> n.tags.any { it.equals(old, ignoreCase = true) } }.map { it.uri.toString() }.toSet()
        batch(if (new == null) tr("태그 삭제") else tr("태그 이름 변경")) { n ->
            val ctx = getApplication<Application>()
            val text = NoteRepo.read(ctx, n.uri)
            val out = NoteText.retag(text, old, new)
            if (out != text) {
                runCatching { History.snapshot(ctx, tree!!, History.relOf(n), text, out, true) }
                NoteRepo.write(ctx, n.uri, NoteText.ensureDates(out, n.creationDate, n.date))   // 수정 시각은 바꾸지 않는다(데스크톱과 같게)
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
        batch(tr("휴지통")) { n -> trashNote(n) }
    }

    // ---- 노트북(폴더) 만들기·휴지통으로
    private fun rootDocId() = android.provider.DocumentsContract.getTreeDocumentId(tree!!)

    /** parent 노트북("" 이면 맨 위) 안에 새 노트북을 만들고 그 노트북을 연다 */
    fun createNotebook(parent: String, name: String) {
        val t = tree ?: return
        viewModelScope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    val pid = if (parent.isEmpty()) rootDocId() else folderIds[parent] ?: throw IllegalStateException(tr("상위 노트북을 찾을 수 없습니다"))
                    NoteRepo.createFolder(getApplication(), t, pid, name)
                    val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    val p = if (parent.isEmpty()) clean else "$parent/$clean"
                    History.journal(getApplication(), t, "create", p, detail = "노트북")
                    p
                }
                refresh()
                folder = path; tag = null
                reapply()
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
            }
        }
    }

    fun deleteNotebook(path: String) {
        val t = tree ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val id = folderIds[path] ?: throw IllegalStateException(tr("노트북을 찾을 수 없습니다"))
                    val parent = path.substringBeforeLast('/', "")
                    val pid = if (parent.isEmpty()) rootDocId() else folderIds[parent] ?: throw IllegalStateException(tr("상위 노트북을 찾을 수 없습니다"))
                    NoteRepo.trashFolder(getApplication(), t, path, id, pid)
                    History.journal(getApplication(), t, "delete", path, detail = "노트북 휴지통")
                }
                // 지운 항목은 큰 볼트의 재검색이 끝날 때까지 목록에 남겨 두지 않는다.
                job?.cancel()
                NoteRepo.dirs = NoteRepo.dirs.filterKeys { it != path && !it.startsWith("$path/") }
                val remaining = (notes + conflicts.map { it.conflict }).filter { it.folder != path && !it.folder.startsWith("$path/") }
                if (folder == path || folder?.startsWith("$path/") == true) folder = null
                publish(remaining)
                message = trf("‘%s’ 노트북을 휴지통으로 보냈습니다", path.substringAfterLast('/'))
                refresh()
            } catch (e: Exception) {
                message = e.message ?: e.javaClass.simpleName
                refresh() // 동기화·외부 삭제로 없어진 폴더의 낡은 목록도 다시 확인한다.
            }
        }
    }
}
