package kr.iamtalker.notes

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate

/** 달력·할일·공수: 컴퓨터 앱(app/planner/model.py)과 같은 파일 형식이다. 노트 폴더의 `달력` 폴더에 달마다 마크다운 파일
 *  (`2026년 11월.md` = 공수 목록 + 날짜 있는 할일, `할일.md` = 날짜 없는 할일). 끝의 `✓날짜` = 완료한 날. 앱 상태(알린 기록)는 `.planner/data.json`.
 *  손으로 고쳐도 읽히는 보통 마크다운 목록이라, 못 읽는 줄은 건너뛴다. */
data class Todo(val id: String, val text: String, val date: String?, val done: Boolean, val doneOn: String? = null)

object PlannerMd {
    const val DIR = "달력"
    const val UNDATED = "할일.md"
    val monthFile = Regex("""^(\d{4})년 (\d{1,2})월\.md$""")
    private val todoRe = Regex("""^\s*[-*]\s*\[( |x|X)\]\s*(?:(\d{4}-\d{2}-\d{2})\s+)?(.*?)(?:\s*✓(\d{4}-\d{2}-\d{2}))?\s*$""")
    private val gongsuRe = Regex("""^\s*[-*]\s*(\d{4}-\d{2}-\d{2})\s*[:：]\s*(\d+(?:\.\d+)?)""")

    data class Row(val date: String?, val text: String, val done: Boolean, val doneOn: String?)

    fun monthName(year: Int, month: Int) = "%d년 %02d월.md".format(year, month)

    fun fmt(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString().trimEnd('0')

    fun parse(text: String): Pair<List<Row>, Map<String, Double>> {
        val rows = ArrayList<Row>()
        val gongsu = LinkedHashMap<String, Double>()
        for (line in text.split("\n")) {
            val m = todoRe.matchEntire(line.trimEnd('\r'))
            if (m != null) {
                if (m.groupValues[3].isNotEmpty()) rows.add(Row(m.groupValues[2].ifEmpty { null }, m.groupValues[3], m.groupValues[1] in listOf("x", "X"), m.groupValues[4].ifEmpty { null }))
                continue
            }
            val g = gongsuRe.find(line)
            if (g != null && validDate(g.groupValues[1])) {
                val v = g.groupValues[2].toDouble()
                if (v > 0) gongsu[g.groupValues[1]] = v
            }
        }
        return rows to gongsu
    }

    fun renderMonth(year: Int, month: Int, todos: List<Todo>, gongsu: Map<String, Double>): String {
        val out = ArrayList<String>()
        out.add("# ${year}년 ${month}월"); out.add("")
        if (gongsu.isNotEmpty()) { out.add("## 공수"); gongsu.toSortedMap().forEach { (d, v) -> out.add("- $d: ${fmt(v)}") }; out.add("") }
        if (todos.isNotEmpty()) {
            out.add("## 할일")
            todos.sortedWith(compareBy({ it.date }, { it.text })).forEach { out.add(line(it, withDate = true)) }
            out.add("")
        }
        return out.joinToString("\n").trimEnd('\n') + "\n"
    }

    fun renderUndated(todos: List<Todo>): String = (listOf("# 할일", "") + todos.map { line(it, withDate = false) }).joinToString("\n") + "\n"

    private fun line(t: Todo, withDate: Boolean) =
        "- [${if (t.done) "x" else " "}] " + (if (withDate) "${t.date} " else "") + t.text + (if (t.done && t.doneOn != null) " ✓${t.doneOn}" else "")

    fun validDate(s: String?): Boolean = s != null && runCatching { LocalDate.parse(s) }.isSuccess
}

object PlannerCalc {
    data class Summary(val gongsu: Double, val days: Int, val pay: Long, val meal: Long) { val sum get() = pay + meal }

    /** 그 달의 공수 합계·출근일수·일당 합계·일비 합계·총액 */
    fun summary(gongsu: Map<String, Double>, year: Int, month: Int, wage: Long, meal: Long): Summary {
        val pre = "%04d-%02d-".format(year, month)
        val vals = gongsu.filterKeys { it.startsWith(pre) }.values
        return Summary(vals.sum(), vals.size, Math.round(vals.sum() * wage), vals.size * meal)
    }

    /** 맨 위가 가장 과거, 맨 아래가 가장 미래(날짜순). 끝낸 것도 자기 날짜 자리. 날짜 없는 예전 할일은 맨 위 */
    fun ordered(todos: List<Todo>): List<Todo> = todos.sortedWith(compareBy({ it.date ?: "" }, { it.text }))

    /** 오늘(이전 날짜 포함) 알릴 할일: 안 했고 날짜가 오늘 이하이며 오늘 아직 안 알린 것 */
    fun due(todos: List<Todo>, today: String, notified: Map<String, String>): List<Todo> =
        todos.filter { !it.done && it.date != null && it.date <= today && notified[it.id] != today }

    /** 목록의 날짜 옆 표시: 미래만 D-n, 오늘은 D-day, 지난 날짜·끝낸 것은 없음 */
    fun dLabel(t: Todo, today: LocalDate): String {
        if (t.done || t.date == null || !PlannerMd.validDate(t.date)) return ""
        val diff = java.time.temporal.ChronoUnit.DAYS.between(today, LocalDate.parse(t.date))
        return if (diff > 0) "D-$diff" else if (diff == 0L) "D-day" else ""
    }

    /** 사람이 친 날짜(20260914 · 2026-9-14 · 2026.09.14 · 2026/9/14) → 'YYYY-MM-DD'. 안 읽히면 null */
    fun parseLoose(s: String?): String? {
        val m = Regex("""^\s*(\d{4})[.\-/]?(\d{1,2})[.\-/]?(\d{1,2})\s*$""").matchEntire(s ?: return null) ?: return null
        return runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()).toString() }.getOrNull()
    }
}

/** 노트 폴더(vaultPath)의 달력 파일을 읽고 쓰는 저장소. 컴퓨터 앱 Planner 와 같은 파일을 쓴다. */
class PlannerStore(vaultPath: String) {
    private val dir = File(vaultPath, PlannerMd.DIR)
    private val state = File(File(vaultPath, ".planner"), "data.json")
    var todos: List<Todo> = emptyList(); private set
    var gongsu: Map<String, Double> = emptyMap(); private set
    var notified: Map<String, String> = emptyMap(); private set

    init { load() }

    private fun tid(date: String?, text: String, seen: MutableSet<String>): String {
        val base = MessageDigest.getInstance("SHA-1").digest("$date|$text".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(8)
        var id = base
        var n = 1
        while (!seen.add(id)) { n++; id = "$base$n" }
        return id
    }

    fun load() {
        notified = runCatching { JSONObject(state.readText()).optJSONObject("notified")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } } }.getOrNull() ?: emptyMap()
        val rows = ArrayList<PlannerMd.Row>()
        val g = LinkedHashMap<String, Double>()
        for (f in (dir.listFiles() ?: emptyArray()).sortedBy { it.name }) {
            if (!(PlannerMd.monthFile.matches(f.name) || f.name == PlannerMd.UNDATED)) continue
            val (r, gs) = PlannerMd.parse(runCatching { f.readText() }.getOrDefault(""))
            rows += r; g.putAll(gs)
        }
        val seen = HashSet<String>()
        todos = rows.map { Todo(tid(it.date, it.text, seen), it.text, it.date, it.done, it.doneOn) }
        gongsu = g
    }

    fun save() {
        dir.mkdirs()
        val months = HashMap<Pair<Int, Int>, Pair<MutableList<Todo>, MutableMap<String, Double>>>()
        fun slot(d: String) = LocalDate.parse(d).let { months.getOrPut(it.year to it.monthValue) { mutableListOf<Todo>() to mutableMapOf() } }
        todos.filter { PlannerMd.validDate(it.date) }.forEach { slot(it.date!!).first.add(it) }
        gongsu.forEach { (d, v) -> if (PlannerMd.validDate(d)) slot(d).second[d] = v }
        val want = HashMap<String, String>()
        months.forEach { (ym, v) -> want[PlannerMd.monthName(ym.first, ym.second)] = PlannerMd.renderMonth(ym.first, ym.second, v.first, v.second) }
        val undated = todos.filter { !PlannerMd.validDate(it.date) }
        if (undated.isNotEmpty()) want[PlannerMd.UNDATED] = PlannerMd.renderUndated(undated)
        want.forEach { (name, text) -> write(File(dir, name), text) }
        dir.listFiles()?.forEach { if ((PlannerMd.monthFile.matches(it.name) || it.name == PlannerMd.UNDATED) && it.name !in want) it.delete() }
        state.parentFile?.mkdirs()
        write(state, JSONObject().put("notified", JSONObject(notified)).toString())
    }

    private fun write(f: File, text: String) {
        if (runCatching { f.readText() == text }.getOrDefault(false)) return
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    // ---- 할일·날짜: 생성·변경·삭제 (바꿀 때마다 바로 저장)
    private fun commit(newTodos: List<Todo>? = null, newGongsu: Map<String, Double>? = null) {
        if (newTodos != null) todos = newTodos
        if (newGongsu != null) gongsu = newGongsu
        save()
    }

    fun add(text: String, date: String? = null): Todo? {
        val t = text.trim().split(Regex("\\s+")).joinToString(" ")
        if (t.isEmpty()) return null
        val todo = Todo(tid(date, t, todos.map { it.id }.toMutableSet()), t, date, false)
        commit(todos + todo)
        return todo
    }

    fun update(id: String, f: (Todo) -> Todo) = commit(todos.map { if (it.id == id) f(it) else it })

    fun rename(id: String, text: String) = update(id) { it.copy(text = text.trim()) }
    fun setDate(id: String, date: String?) = update(id) { it.copy(date = date) }
    fun setDone(id: String, done: Boolean, on: String = LocalDate.now().toString()) = update(id) { it.copy(done = done, doneOn = if (done) on else null) }
    fun remove(id: String) = commit(todos.filter { it.id != id })
    fun setGongsu(date: String, v: Double) = commit(newGongsu = if (v > 0) gongsu + (date to v) else gongsu - date)
    fun markNotified(ids: Collection<String>, today: String) { notified = notified + ids.associateWith { today }; save() }
}
