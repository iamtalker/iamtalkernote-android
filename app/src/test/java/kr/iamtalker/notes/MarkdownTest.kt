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

    @Test fun imageLineWithTrailingTextStillShowsImage() {
        // 웹 스크랩 노트 형식: 그림 문법 뒤에 '이미지 크게 보기' 글자가 붙어 있다
        val blocks = parseBlocks("본문\n\n![LG 제공 사진 (LG 제공)](../.resources/41dd.jpg)이미지 크게 보기\n\n끝")
        val img = blocks.filterIsInstance<Block.Img>().single()
        assertEquals("../.resources/41dd.jpg", img.src)
        assertEquals("LG 제공 사진 (LG 제공)", img.alt)
        assertTrue(blocks.any { it is Block.P && it.text == "이미지 크게 보기" })
    }

    @Test fun linkedImageShowsImageAndDropsOuterLink() {
        // 웹 스크랩 형식: 그림을 원본 주소 링크로 감싸고, 그림 이름의 \_ 이스케이프가 들어 있다
        val blocks = parseBlocks("[![퇴사 글.jpg\\_1.webp](../.resources/69a4326e755eaefa.jpg)](https://i2.ruliweb.com/img/a.webp)\n\n  \n\n[![퇴사 글.jpg\\_2.webp](../.resources/d7476043018c3fba.jpg)](https://i3.ruliweb.com/img/b.webp)")
        val imgs = blocks.filterIsInstance<Block.Img>()
        assertEquals(listOf("../.resources/69a4326e755eaefa.jpg", "../.resources/d7476043018c3fba.jpg"), imgs.map { it.src })
        assertEquals(2, blocks.size)
    }

    @Test fun lineWithSeveralLocalImagesAndTextShowsEachImage() {
        // 카페 댓글 형식: 프로필 그림 + 이름, 다음 링크, 그리고 본문 중간의 그림
        val blocks = parseBlocks("[![p.png](../.resources/a1.png)**붉은 갈까마귀**](https://m.cafe.naver.com/x)[채팅](https://m.cafe.naver.com/y)\n가운데 ![그림](../.resources/b2.jpg) 끝")
        val imgs = blocks.filterIsInstance<Block.Img>()
        assertEquals(listOf("../.resources/a1.png", "../.resources/b2.jpg"), imgs.map { it.src })
        assertTrue(blocks.any { it is Block.P && it.text.contains("붉은 갈까마귀") })
        assertTrue(blocks.any { it is Block.P && it.text == "가운데" })
        assertTrue(blocks.any { it is Block.P && it.text == "끝" })
    }

    @Test fun remoteImagesAreNotSplitByLocalRule() {
        val blocks = parseBlocks("[![](https://static.example.com/a.jpg)<br>쿠우](https://www.twitch.tv/x)")
        assertEquals(0, blocks.filterIsInstance<Block.Img>().size)
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
