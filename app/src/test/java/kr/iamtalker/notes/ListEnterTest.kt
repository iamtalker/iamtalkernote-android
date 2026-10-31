package kr.iamtalker.notes

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Test

class ListEnterTest {
    private fun enter(before: String, at: Int): TextFieldValue {
        val old = TextFieldValue(before, TextRange(at))
        val nt = before.substring(0, at) + "\n" + before.substring(at)
        return ListEnter.apply(old, TextFieldValue(nt, TextRange(at + 1)))
    }

    @Test fun bulletContinues() {
        val r = enter("- 하나", 4)
        assertEquals("- 하나\n- ", r.text); assertEquals(r.text.length, r.selection.start)
    }

    @Test fun indentAndNumberAndCheckboxContinue() {
        assertEquals("    - 둘\n    - ", enter("    - 둘", 7).text)
        assertEquals("1. a\n2. ", enter("1. a", 4).text)
        assertEquals("- [x] 끝\n- [ ] ", enter("- [x] 끝", 7).text)
    }

    @Test fun emptyItemEndsList() {
        val r = enter("- 하나\n- ", 7)
        assertEquals("- 하나\n", r.text); assertEquals(r.text.length, r.selection.start)
    }

    @Test fun plainLineUnchanged() {
        assertEquals("그냥 글\n", enter("그냥 글", 4).text)
    }

    @Test fun shiftIndentsAndOutdents() {
        val a = ListEnter.shift("- A\n- B", 0, 0, false)
        assertEquals("    - A\n- B", a.text)
        val b = ListEnter.shift("    - A\n- B", 6, 6, true)
        assertEquals("- A\n- B", b.text)
        val c = ListEnter.shift("- A\n- B", 0, 7, false)             // 두 줄 모두
        assertEquals("    - A\n    - B", c.text)
    }
}
