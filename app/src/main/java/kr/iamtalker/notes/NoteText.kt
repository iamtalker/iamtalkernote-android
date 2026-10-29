package kr.iamtalker.notes

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 노트 글을 고치는 순수 함수 모음(화면 코드 없음). 데스크톱 앱(store.py·notes.py)의 규칙과 같은 모양으로 파일에 적는다. */
object NoteText {
    private val front = Regex("^﻿?---[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n---[ \\t]*(?:\\r?\\n|$)")
    private val tagLine = Regex("(?m)^[ \\t]*(?:태그|tags?)[ \\t]*[:：][ \\t]*(.+)$", RegexOption.IGNORE_CASE)

    /** 머리말 뒤 본문이 시작하는 글자 위치 */
    fun bodyStart(text: String): Int = front.find(text)?.range?.last?.plus(1) ?: 0

    /** 머리말에 값이 이미 있던 모양(UTC 'Z' / 'T' 구분 / 현지 시각) 그대로 시각을 적는다 */
    private fun formatLike(ms: Long, sample: String): String {
        val s = sample.trim().trim('"', '\'')
        val sep = if (Regex("^\\d{4}-\\d{2}-\\d{2}T").containsMatchIn(s)) "T" else " "
        val utc = s.endsWith("Z")
        val f = DateTimeFormatter.ofPattern("yyyy-MM-dd'$sep'HH:mm:ss").withZone(if (utc) ZoneId.of("UTC") else ZoneId.systemDefault())
        return f.format(Instant.ofEpochMilli(ms)) + if (utc) "Z" else ""
    }

    /** 머리말에 updated 줄이 있으면 지금 시각으로 바꾼다(조플린·데스크톱과 같은 동작). 없으면 그대로 */
    fun touchUpdated(text: String, nowMs: Long): String {
        val m = front.find(text) ?: return text
        val fm = m.groups[1]!!
        val upd = Regex("(?m)^updated:[ \\t]*(.*)$").find(fm.value) ?: return text
        val v = upd.groups[1]!!
        val at = fm.range.first + v.range.first
        return text.substring(0, at) + formatLike(nowMs, v.value) + text.substring(fm.range.first + v.range.last + 1)
    }

    data class Edit(val text: String, val start: Int, val end: Int)

    /** 선택한 글을 pre/suf 로 감싼다. 이미 감싸져 있으면 푼다. 선택이 없으면 pre+suf 를 넣고 커서를 가운데에 둔다 */
    fun wrap(text: String, s0: Int, e0: Int, pre: String, suf: String = pre): Edit {
        val s = minOf(s0, e0); val e = maxOf(s0, e0)
        if (s >= pre.length && e + suf.length <= text.length && text.startsWith(pre, s - pre.length) && text.startsWith(suf, e))
            return Edit(text.substring(0, s - pre.length) + text.substring(s, e) + text.substring(e + suf.length), s - pre.length, e - pre.length)
        return Edit(text.substring(0, s) + pre + text.substring(s, e) + suf + text.substring(e), s + pre.length, e + pre.length)
    }

    /** 선택에 걸친 모든 줄 앞에 prefix 를 붙이거나(하나라도 없으면) 뗀다(모두 있으면). "# " 같은 제목은 기존 # 를 바꾼다 */
    fun linePrefix(text: String, s0: Int, e0: Int, prefix: String, heading: Boolean = false): Edit {
        val s = minOf(s0, e0); val e = maxOf(s0, e0)
        val ls = if (s == 0) 0 else text.lastIndexOf('\n', s - 1) + 1
        var le = text.indexOf('\n', e)
        if (le < 0) le = text.length
        val lines = text.substring(ls, le).split("\n")
        val allHave = lines.all { it.startsWith(prefix) }
        val out = lines.map { l ->
            when {
                allHave -> l.removePrefix(prefix)
                heading -> prefix + l.trimStart('#').trimStart()
                else -> prefix + l
            }
        }.joinToString("\n")
        val delta = out.length - (le - ls)
        return Edit(text.substring(0, ls) + out + text.substring(le), maxOf(ls, s + if (allHave) -prefix.length else prefix.length), e + delta)
    }

    /** 대소문자를 가리지 않고 q 가 나오는 모든 시작 위치 */
    fun findAll(text: String, q: String): List<Int> {
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        var i = text.indexOf(q, 0, ignoreCase = true)
        while (i >= 0) { out.add(i); i = text.indexOf(q, i + q.length.coerceAtLeast(1), ignoreCase = true) }
        return out
    }

    // ---- 태그: 글 맨 아래의 '태그: #a #b' 줄(데스크톱과 같은 위치·모양)
    fun tagLineTags(text: String): List<String> {
        val off = bodyStart(text)
        val m = tagLine.findAll(text.substring(off)).lastOrNull { !it.value.contains("](") } ?: return emptyList()
        return m.groupValues[1].split(',', ' ', '\t').map { it.trim().trimStart('#') }.filter { it.isNotEmpty() }.distinct()
    }

    /** 태그 줄을 tags 로 바꾼다(비어 있으면 줄을 지운다, 줄이 없으면 글 맨 아래에 만든다). 공백은 _ 로 바뀐다 */
    fun setTagLine(text: String, tags: List<String>): String {
        val clean = tags.map { it.trim().trimStart('#').replace(Regex("\\s+"), "_") }.filter { it.isNotEmpty() }.distinct()
        val off = bodyStart(text)
        val rest = text.substring(off)
        val m = tagLine.findAll(rest).lastOrNull { !it.value.contains("](") }
        val line = if (clean.isEmpty()) "" else "태그: " + clean.joinToString(" ") { "#$it" }
        if (m != null) {
            var end = m.range.last + 1
            if (line.isEmpty() && end < rest.length && rest[end] == '\n') end++
            return text.substring(0, off) + rest.substring(0, m.range.first) + line + rest.substring(end)
        }
        if (line.isEmpty()) return text
        val base = text.trimEnd('\n', ' ')
        return base + "\n\n" + line + "\n"
    }

    /** 노트 링크 [[제목]] 의 제목 → 노트 (제목이 같은 것 중 첫째, 대소문자 무시) */
    fun <T> findByTitle(items: List<T>, title: String, titleOf: (T) -> String): T? =
        items.firstOrNull { titleOf(it).equals(title.trim(), ignoreCase = true) }

    // ---- 태그 이름 바꾸기·삭제: 머리말 tags / 글 아래 '태그:' 줄 / 본문 #태그 세 군데를 모두 고친다(코드 블록은 건드리지 않음)
    private val bodyTagRe = Regex("(?<![\\p{L}\\p{N}_&/#])#([\\p{L}\\p{N}_\\-]{1,40})")

    private fun sameTag(a: String, b: String) = a.trim().trimStart('#').equals(b.trim().trimStart('#'), ignoreCase = true)

    /** 태그 old 를 new 로 바꾼다(new 가 null 이면 지운다). 바뀐 곳이 없으면 같은 글을 돌려준다. 이름의 공백은 _ 로 바뀐다 */
    fun retag(text: String, old: String, new: String?): String {
        val nw = new?.trim()?.trimStart('#')?.replace(Regex("\\s+"), "_")?.ifEmpty { null }
        val off = bodyStart(text)
        val head = text.substring(0, off)
        val rest = text.substring(off)

        // 머리말: tags: a, b / tags: [a, b] / 목록 형식
        val headOut = StringBuilder()
        var inList = false
        for (line in head.split("\n")) {
            val m = Regex("^(tags?)([ \\t]*:[ \\t]*)(.*)$", RegexOption.IGNORE_CASE).find(line)
            if (m != null) {
                val v = m.groupValues[3].trim()
                inList = v.isEmpty()
                if (v.isNotEmpty()) {
                    val br = v.startsWith("[")
                    val parts = v.trim('[', ']').split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    val out = LinkedHashMap<String, String>()
                    for (p in parts) {
                        val rep = if (sameTag(p, old)) nw else p
                        if (rep != null) out.putIfAbsent(rep.lowercase(), rep)
                    }
                    if (parts.any { sameTag(it, old) }) {
                        headOut.append(if (out.isEmpty()) "" else m.groupValues[1] + m.groupValues[2] + (if (br) "[" else "") + out.values.joinToString(", ") + (if (br) "]" else "")).append("\n")
                        continue
                    }
                }
            } else if (inList) {
                val t = line.trim()
                if (t.startsWith("-")) {
                    if (sameTag(t.removePrefix("-"), old)) { if (nw != null) headOut.append(line.substringBefore("-")).append("- ").append(nw).append("\n"); continue }
                } else if (t.isNotEmpty()) inList = false
            }
            headOut.append(line).append("\n")
        }
        val newHead = if (head.isEmpty()) "" else headOut.toString().removeSuffix("\n")

        // 본문
        var fence = false
        val out = ArrayList<String>()
        for (line in rest.split("\n")) {
            if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) { fence = !fence; out.add(line); continue }
            if (fence) { out.add(line); continue }
            val tl = tagLine.find(line)
            if (tl != null && !line.contains("](")) {
                val toks = tl.groupValues[1].split(',', ' ', '\t').map { it.trim().trimStart('#') }.filter { it.isNotEmpty() }
                if (toks.any { sameTag(it, old) }) {
                    val seen = LinkedHashMap<String, String>()
                    for (t in toks) { val r = if (sameTag(t, old)) nw else t; if (r != null) seen.putIfAbsent(r.lowercase(), r) }
                    if (seen.isNotEmpty()) out.add("태그: " + seen.values.joinToString(" ") { "#$it" })
                    continue
                }
            }
            if (line.startsWith("#") && !line.startsWith("##") && line.length > 1 && line[1] == ' ') { out.add(line); continue }   // 제목 줄
            out.add(if (line.contains('#')) bodyTagRe.replace(line) { m ->
                if (!sameTag(m.groupValues[1], old)) m.value else if (nw != null) "#$nw" else ""
            } else line)
        }
        return newHead + out.joinToString("\n")
    }
}
