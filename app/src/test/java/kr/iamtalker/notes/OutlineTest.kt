package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineTest {
    private val src = "# 계획\n\n메모 문단\n\n- A\n  - A1\n  - A2\n    - A2a\n- B\n\n끝 메모\n\n1. 번호 하나\n2. 번호 둘\n"
    private val blocks = parseBlocks(src)

    @Test fun tabIndentCountsAsDepth() {
        val n = Outline.nodes(parseBlocks("- A\n\t- A1\n\t\t- A1a\n- B\n"))
        assertEquals(listOf(0, 1, 2, 0), n.map { it.depth })
        val m = Outline.nodes(parseBlocks("- A\n    - A1\n        - A1a\n"))        // PC 앱은 4칸
        assertEquals(listOf(0, 1, 2), m.map { it.depth })
    }

    @Test fun nodeKeepsSourceLine() {
        val n = Outline.nodes(parseBlocks("- A\n  - [ ] B\n"))
        assertEquals(listOf(0, 1), n.map { it.line })
    }

    @Test fun nodesAndDepth() {
        val n = Outline.nodes(blocks)
        assertEquals(listOf("A", "A1", "A2", "A2a", "B", "번호 하나", "번호 둘"), n.map { it.text })
        assertEquals(listOf(0, 1, 1, 2, 0, 0, 0), n.map { it.depth })
        assertEquals(listOf("A1", "A2"), n[0].children.map { it.text })
        assertEquals("A2", n[3].parent?.text)
        assertEquals(listOf("A", "A2"), Outline.ancestors(n[3]).map { it.text })
        assertNull(Outline.parentId(n, 0)); assertEquals(0, Outline.parentId(n, 1))
    }

    @Test fun rootRowsKeepMemoBlocksSeparate() {
        val rows = Outline.rows(blocks, null)
        val kinds = rows.map { if (it is Outline.Row.Memo) "M" else "B" }.joinToString("")
        assertEquals("MMBBBBBMBB", kinds)                                          // 제목·문단(메모) → 목록 5 → 끝 메모 → 번호 목록 2
        assertEquals(listOf(0, 1, 1, 2, 0), rows.filterIsInstance<Outline.Row.Bullet>().take(5).map { it.depth })
    }

    @Test fun zoomShowsOnlySubtree() {
        val n = Outline.nodes(blocks)
        val rows = Outline.rows(blocks, n[2].id)                                   // A2 로 줌인
        assertTrue(rows[0] is Outline.Row.Crumbs && (rows[0] as Outline.Row.Crumbs).path.map { it.text } == listOf("A"))
        assertEquals("A2", (rows[1] as Outline.Row.Title).node.text)
        assertEquals(listOf("A2a"), rows.filterIsInstance<Outline.Row.Bullet>().map { it.node.text })
        assertEquals(listOf(0), rows.filterIsInstance<Outline.Row.Bullet>().map { it.depth })
        assertTrue(Outline.rows(blocks, n[4].id).last() is Outline.Row.Empty)      // 하위가 없는 항목
        assertTrue(Outline.rows(blocks, 99).none { it is Outline.Row.Title })      // 없는 번호는 노트 전체
    }

    @Test fun listSplitByOtherBlock() {
        val b = parseBlocks("- a\n- b\n\n문단\n\n- c\n")
        val n = Outline.nodes(b)
        assertEquals(listOf("a", "b", "c"), n.map { it.text })
        assertEquals(listOf(0, 0, 0), n.map { it.depth })
        assertFalse(Outline.hasList(parseBlocks("그냥 글\n")))
        assertTrue(Outline.hasList(b))
    }
}
