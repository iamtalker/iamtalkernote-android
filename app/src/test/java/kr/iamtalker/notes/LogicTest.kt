package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private val joplin = "---\ntitle: x\nupdated: 2023-05-01T12:30:00.000Z\ncreated: 2023-01-01 00:00:00\ntags:\n  - 경제\n  - Work\n---\n\n# 제목\n본문 #메모 와 C#언어 # 아님\n태그: 하나, 둘\n```\n#코드안\n```\n"

    @Test fun metaDateAndTags() {
        val i = Meta.parse(joplin)
        assertEquals(1682944200000L, i.updated)
        assertEquals(listOf("경제", "Work", "메모", "하나", "둘"), i.tags)
    }

    @Test fun metaInlineTagsAndNoFrontMatter() {
        assertEquals(listOf("a", "b"), Meta.parse("---\ntags: [a, b]\n---\nx").tags)
        val i = Meta.parse("그냥 #태그1 글")
        assertEquals(0L, i.updated)
        assertEquals(listOf("태그1"), i.tags)
    }

    @Test fun splitFrontMatter() {
        val (f, b) = Meta.split(joplin)
        assertTrue(f.contains("updated"))
        assertTrue(b.trimStart().startsWith("# 제목"))
    }

    @Test fun queryParsing() {
        val q = parseQuery("회의 \"정확한 구절\" -취소 NOT 예산 #업무 AND 일정")
        assertEquals(listOf("회의", "정확한 구절", "일정"), q.must)
        assertEquals(listOf("취소", "예산"), q.not)
        assertEquals(listOf("업무"), q.tags)
    }
}
