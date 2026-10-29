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
        val q = QueryParser.parse("회의 \"정확한 구절\" -취소 NOT 예산 #업무 AND 일정")!!
        assertEquals(Q.And(listOf(Q.Term("회의"), Q.Term("정확한 구절"), Q.Not(Q.Term("취소")), Q.Not(Q.Term("예산")), Q.Tag("업무"), Q.Term("일정"))), q)
    }

    @Test fun orAndParentheses() {
        val q = QueryParser.parse("회의록 AND (예산 OR 일정) NOT 취소")!!
        assertTrue(QueryParser.matches(q, "회의록 예산", emptyList()))
        assertTrue(QueryParser.matches(q, "일정 회의록", emptyList()))
        assertTrue(!QueryParser.matches(q, "회의록", emptyList()))
        assertTrue(!QueryParser.matches(q, "회의록 예산 취소", emptyList()))
        val args = ArrayList<String>()
        val sql = QueryParser.toSql(q, args)
        assertEquals("(hay LIKE ? ESCAPE '\\' AND (hay LIKE ? ESCAPE '\\' OR hay LIKE ? ESCAPE '\\') AND NOT (hay LIKE ? ESCAPE '\\'))", sql)
        assertEquals(listOf("%회의록%", "%예산%", "%일정%", "%취소%"), args)
        assertEquals(listOf("회의록", "예산", "일정"), QueryParser.terms(q))
    }

    @Test fun symbolsAndTags() {
        val q = QueryParser.parse("a & (b | c) !d #Tag")!!
        assertTrue(QueryParser.matches(q, "a b", listOf("tag")))
        assertTrue(!QueryParser.matches(q, "a b d", listOf("tag")))
        assertTrue(!QueryParser.matches(q, "a b", emptyList()))
        assertEquals(null, QueryParser.parse("   "))
        assertTrue(QueryParser.parse("(x") != null)
    }
}
