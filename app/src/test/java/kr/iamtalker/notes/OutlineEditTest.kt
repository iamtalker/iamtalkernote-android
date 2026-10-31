package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutlineEditTest {
    private val src = listOf("- A", "    - A1", "    - A2", "        - A2a", "- B", "메모")

    @Test fun setTextKeepsMarkerAndCheck() {
        assertEquals("    - A1 고침", OutlineEdit.setText(src, 1, "A1 고침")[1])
        assertEquals("- [x] 끝", OutlineEdit.setText(listOf("- [x] 처음"), 0, "끝")[0])
    }

    @Test fun enterMakesSiblingOrFirstChild() {
        val (a, ia) = OutlineEdit.enter(src, 4)                                  // B 는 하위가 없다 → 다음 형제
        assertEquals(5, ia); assertEquals("- ", a[5]); assertEquals("메모", a[6])
        val (b, ib) = OutlineEdit.enter(src, 0)                                  // A 는 하위가 있다 → 첫 자식
        assertEquals(1, ib); assertEquals("    - ", b[1])
        val (c, ic) = OutlineEdit.enter(src, 2)                                  // A2 는 하위(A2a)가 있다 → 그 자식 자리
        assertEquals(3, ic); assertEquals("        - ", c[3])
    }

    @Test fun indentMovesSubtree() {
        val out = OutlineEdit.indent(src, 2)!!                                   // A2 를 A1 아래로: A2 와 A2a 모두 한 단계 더 안으로
        assertEquals("        - A2", out[2]); assertEquals("            - A2a", out[3])
        assertNull(OutlineEdit.indent(src, 0))                                   // 첫 항목은 못 들여쓴다
        assertEquals("    - B", OutlineEdit.indent(src, 4)!![4])
    }

    @Test fun outdentMovesSubtree() {
        val out = OutlineEdit.outdent(src, 2)!!
        assertEquals("- A2", out[2]); assertEquals("    - A2a", out[3])
        assertNull(OutlineEdit.outdent(src, 0))
    }

    @Test fun toggleDoneAddsAndFlips() {
        val a = OutlineEdit.toggleDone(src, 4); assertEquals("- [x] B", a[4])
        val b = OutlineEdit.toggleDone(a, 4); assertEquals("- [ ] B", b[4])
        assertEquals("- [x] B", OutlineEdit.toggleDone(b, 4)[4])
    }

    @Test fun deleteRemovesSubtreeAndPicksPrevious() {
        val (out, f) = OutlineEdit.delete(src, 2)
        assertEquals(listOf("- A", "    - A1", "- B", "메모"), out); assertEquals(1, f)
        val (o2, f2) = OutlineEdit.delete(listOf("- 하나"), 0)
        assertEquals(listOf<String>(), o2); assertEquals(-1, f2)
    }
}
