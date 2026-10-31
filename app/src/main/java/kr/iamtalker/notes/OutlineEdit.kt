package kr.iamtalker.notes

/** 아웃라이너 편집(컴퓨터 앱 outliner/model.py 와 같은 생각)의 순수 계산: 본문 줄 목록에서 항목 한 줄을 고치고, 새로 만들고,
 *  들여쓰기·내어쓰기(하위 항목 함께)·완료·삭제를 한다. 화면을 모르므로 시험할 수 있다. 들여쓰기는 컴퓨터 앱처럼 4칸. */
object OutlineEdit {
    private val item = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(?:\\[([ xX])\\]\\s+)?(.*)$")
    const val UNIT = "    "

    data class Item(val lead: String, val marker: String, val check: Char?, val text: String) {
        val depth get() = indentOf(lead)
        fun render() = lead + marker + " " + (check?.let { "[$it] " } ?: "") + text
    }

    fun parse(line: String): Item? = item.find(line)?.let { Item(it.groupValues[1], it.groupValues[2], it.groupValues[3].firstOrNull(), it.groupValues[4]) }

    fun isItem(lines: List<String>, i: Int) = i in lines.indices && parse(lines[i]) != null

    /** i 번 항목의 하위까지의 끝(제외). 같거나 얕은 항목이나 목록이 아닌 줄이 나오면 거기까지 */
    fun subtreeEnd(lines: List<String>, i: Int): Int {
        val d = parse(lines[i])?.depth ?: return i + 1
        var j = i + 1
        while (j < lines.size) {
            val p = parse(lines[j]) ?: break
            if (p.depth <= d) break
            j++
        }
        return j
    }

    fun hasChildren(lines: List<String>, i: Int) = subtreeEnd(lines, i) > i + 1

    fun setText(lines: List<String>, i: Int, text: String): List<String> {
        val p = parse(lines.getOrNull(i) ?: return lines) ?: return lines
        return lines.toMutableList().also { it[i] = p.copy(text = text.replace("\n", " ")).render() }
    }

    /** Enter: 하위가 있으면 첫 자식, 없으면 다음 형제로 빈 항목을 만든다 → (새 줄 목록, 새 항목 번호) */
    fun enter(lines: List<String>, i: Int): Pair<List<String>, Int> {
        val p = parse(lines.getOrNull(i) ?: return lines to i) ?: return lines to i
        val kids = hasChildren(lines, i)
        val childLead = if (kids) parse(lines[i + 1])!!.lead else p.lead
        val marker = if (p.marker.first().isDigit()) "-" else p.marker           // 번호 목록에서 늘리면 점 항목으로(번호를 새로 매기지 않는다)
        val at = if (kids) i + 1 else subtreeEnd(lines, i)
        val fresh = Item(childLead, marker, if (p.check != null) ' ' else null, "").render()
        return lines.toMutableList().also { it.add(at, fresh) } to at
    }

    /** Tab: 바로 앞 항목(더 얕지 않은)의 아래로 들어간다. 하위 항목도 함께. 못 하면 null */
    fun indent(lines: List<String>, i: Int): List<String>? {
        val p = parse(lines.getOrNull(i) ?: return null) ?: return null
        var j = i - 1
        while (j >= 0 && parse(lines[j]).let { it != null && it.depth > p.depth }) j--       // 앞의 형제를 찾는다(더 깊은 하위는 건너뜀)
        val prev = lines.getOrNull(j)?.let { parse(it) } ?: return null
        if (prev.depth < p.depth) return null                                              // 형제가 없다(첫 항목)
        val out = lines.toMutableList()
        for (k in i until subtreeEnd(lines, i)) out[k] = UNIT + lines[k]
        return out
    }

    /** Shift+Tab: 한 단계 밖으로(하위도 함께). 이미 맨 바깥이면 null */
    fun outdent(lines: List<String>, i: Int): List<String>? {
        val p = parse(lines.getOrNull(i) ?: return null) ?: return null
        if (p.lead.isEmpty()) return null
        val out = lines.toMutableList()
        for (k in i until subtreeEnd(lines, i)) {
            val l = lines[k]
            out[k] = if (l.startsWith("\t")) l.drop(1) else l.drop(minOf(4, l.length - l.trimStart(' ').length))
        }
        return out
    }

    /** 완료 토글: [ ] ↔ [x], 체크 칸이 없으면 [x] 로 만든다 */
    fun toggleDone(lines: List<String>, i: Int): List<String> {
        val p = parse(lines.getOrNull(i) ?: return lines) ?: return lines
        val next = when (p.check) { null -> 'x'; ' ' -> 'x'; else -> ' ' }
        return lines.toMutableList().also { it[i] = p.copy(check = next).render() }
    }

    /** 항목과 그 하위를 지운다 → (새 줄 목록, 포커스를 줄 앞 항목 번호 또는 -1) */
    fun delete(lines: List<String>, i: Int): Pair<List<String>, Int> {
        if (!isItem(lines, i)) return lines to -1
        val out = lines.toMutableList()
        repeat(subtreeEnd(lines, i) - i) { out.removeAt(i) }
        var f = i - 1
        while (f >= 0 && !isItem(out, f)) f--
        return out to f
    }
}
