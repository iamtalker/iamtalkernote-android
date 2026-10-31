package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test fun snippetSkipsFrontMatterTitleAndMarks() {
        val t = "---\nupdated: 2023-05-01\n---\n\n# 회의록\n**오늘** 안건은 [링크](http://x) 와 ![](a.png) 입니다.\n- [ ] 할 일\n"
        assertEquals("오늘 안건은 링크 와 입니다. 할 일", Meta.snippet(t, "회의록"))
    }

    @Test fun blocksCheckboxImageAndLineNumbers() {
        val blocks = parseBlocks("제목줄\n\n- [ ] 하나\n- [x] 둘\n- 셋\n\n![그림](../.resources/a%20b.png)\n")
        val li = blocks.filterIsInstance<Block.Li>()
        assertEquals(listOf<Boolean?>(false, true, null), li.map { it.checked })
        assertEquals(listOf(2, 3, 4), li.map { it.line })
        val img = blocks.filterIsInstance<Block.Img>().single()
        assertEquals("../.resources/a%20b.png", img.src)
    }

    @Test fun toggleCheckboxFlips() {
        val t = "---\nx: 1\n---\n- [ ] a\n- [x] b\n"
        assertEquals("---\nx: 1\n---\n- [x] a\n- [x] b\n", toggleCheckbox(t, 3))
        assertEquals("---\nx: 1\n---\n- [ ] a\n- [ ] b\n", toggleCheckbox(t, 4))
        assertTrue(toggleCheckbox(t, 99) == t)
    }

    @Test fun tableParsesAlignmentAndSplitsCells() {
        val t = parseTable("| 이름 | 나이 | 설명 |\n|:--|--:|:-:|\n| 홍길동 | 30 | a \\| b |\n| 김 | 4 | |")!!
        assertEquals(listOf("left", "right", "center"), t.aligns)
        assertEquals(3, t.cols)
        assertEquals(listOf("이름", "나이", "설명"), t.rows[0])
        assertEquals(listOf("홍길동", "30", "a | b"), t.rows[1])
        assertEquals(listOf("김", "4", ""), t.rows[2])
    }

    @Test fun tableNeedsSeparatorLine() {
        assertEquals(null, parseTable("| a | b |\n| 1 | 2 |"))
        assertEquals(null, parseTable("| a |"))
        assertEquals(null, parseTable("a | b\n-\n1 | 2"))
    }

    @Test fun tableSeparatorMayUseOneDash() {
        assertEquals(listOf("", "right"), parseTable("a | b\n-|-:\n1 | 2")!!.aligns)
        assertEquals(listOf("center"), parseTable("| a |\n|:-:|\n| 1 |")!!.aligns)
    }

    @Test fun parseBlocksKeepsTableRowsTogether() {
        val blocks = parseBlocks("앞\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n뒤")
        val tb = blocks.filterIsInstance<Block.Table>().single()
        assertEquals(2, parseTable(tb.text)!!.rows.size)
        assertEquals(3, blocks.size)
    }
}
