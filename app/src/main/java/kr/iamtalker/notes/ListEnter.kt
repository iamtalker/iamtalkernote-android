package kr.iamtalker.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** 원문 편집기의 목록 도우미: 목록 줄에서 Enter 를 치면 아래에 같은 목록 항목이 이어지고(빈 항목에서 치면 목록이 끝남),
 *  ⇥ ⇤ 로 줄을 들여쓰기·내어쓰기 한다(4칸, 컴퓨터 앱과 같음). */
object ListEnter {
    private val item = Regex("^(\\s*)(?:([-*+])|(\\d+)([.)]))\\s+(\\[[ xX]\\]\\s+)?(.*)$")

    /** old → new 로 글자가 바뀐 직후에 부른다. Enter 한 번이면 목록을 이어 준 값, 아니면 그대로 */
    fun apply(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
        val t = new.text
        val c = new.selection.start
        if (new.selection.length != 0 || t.length != old.text.length + 1 || c < 1 || t[c - 1].code != 10) return new
        val ls = t.lastIndexOf('\n', c - 2) + 1
        val m = item.find(t.substring(ls, c - 1)) ?: return new
        val lead = m.groupValues[1]
        if (m.groupValues[6].isEmpty()) {                                             // 빈 항목에서 Enter → 목록 끝(표시를 지운다)
            val out = t.substring(0, ls) + t.substring(c)
            return TextFieldValue(out, TextRange(ls))
        }
        val marker = if (m.groupValues[3].isNotEmpty()) (m.groupValues[3].toInt() + 1).toString() + m.groupValues[4] else m.groupValues[2]
        val box = if (m.groupValues[5].isNotEmpty()) "[ ] " else ""
        val ins = "$lead$marker $box"
        return TextFieldValue(t.substring(0, c) + ins + t.substring(c), TextRange(c + ins.length))
    }

    /** 커서(선택)가 걸친 줄들을 한 단계 들여쓰거나(내어쓰거나) 한다 */
    fun shift(text: String, s: Int, e: Int, out: Boolean): NoteText.Edit {
        val a = text.lastIndexOf('\n', maxOf(0, s) - 1) + 1
        val bEnd = text.indexOf('\n', maxOf(s, e)).let { if (it < 0) text.length else it }
        val lines = text.substring(a, bEnd).split("\n")
        var delta = 0
        val moved = lines.map { l ->
            if (!out) { delta += 4; "    $l" }
            else {
                val n = if (l.startsWith("\t")) 1 else minOf(4, l.length - l.trimStart(' ').length)
                delta -= n; l.drop(n)
            }
        }
        val res = text.substring(0, a) + moved.joinToString("\n") + text.substring(bEnd)
        val first = if (out) -(lines.first().length - moved.first().length) else 4
        return NoteText.Edit(res, maxOf(a, s + first), maxOf(a, e + delta))
    }
}
