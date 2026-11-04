package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTextTest {
    @Test fun datesArePortableAndDoNotOverwriteExistingDates() {
        val created = 1609502400000L
        val updated = 1609588800000L
        val text = "---\nsource: https://example.com\ntags: [보존]\n---\n본문"
        val result = NoteText.ensureDates(text, created, updated)
        assertEquals(created, Meta.parse(result).created)
        assertEquals(updated, Meta.parse(result).updated)
        assertEquals(listOf("보존"), Meta.parse(result).tags)
        assertEquals(result, NoteText.ensureDates(result, 1, 2))
        assertTrue(result.endsWith("본문"))
        val blank = NoteText.ensureDates("", created, updated)
        assertTrue(Meta.split(blank).second.isBlank()) // 날짜만 든 새 노트는 여전히 빈 노트다.
        assertEquals(created, Meta.parse(NoteText.touchUpdated(blank, updated + 100000)).created)
    }

    private val ms = 1682944200000L   // 2023-05-01T12:30:00Z

    @Test fun touchUpdatedKeepsFormat() {
        assertEquals("---\nupdated: 2023-05-01T12:30:00Z\n---\nx",
            NoteText.touchUpdated("---\nupdated: 2001-01-01T00:00:00.000Z\n---\nx", ms))
        assertEquals("no front", NoteText.touchUpdated("no front", ms))
        assertEquals("---\ncreated: 2001-01-01\n---\nx", NoteText.touchUpdated("---\ncreated: 2001-01-01\n---\nx", ms))
    }

    @Test fun wrapAndUnwrap() {
        val e = NoteText.wrap("가나다라", 1, 3, "**")
        assertEquals("가**나다**라", e.text)
        assertEquals(3, e.start); assertEquals(5, e.end)
        assertEquals("가나다라", NoteText.wrap(e.text, e.start, e.end, "**").text)
        val c = NoteText.wrap("ab", 1, 1, "`")
        assertEquals("a``b", c.text); assertEquals(2, c.start)
    }

    @Test fun linePrefixToggles() {
        assertEquals("- a\n- b", NoteText.linePrefix("a\nb", 0, 3, "- ").text)
        assertEquals("a\nb", NoteText.linePrefix("- a\n- b", 0, 7, "- ").text)
        assertEquals("## 제목", NoteText.linePrefix("# 제목", 0, 0, "## ", heading = true).text)
        assertEquals("x\n- [ ] y", NoteText.linePrefix("x\ny", 2, 2, "- [ ] ").text)
    }

    @Test fun findAllIgnoresCase() {
        assertEquals(listOf(0, 4), NoteText.findAll("Abc abc", "ABC"))
        assertTrue(NoteText.findAll("abc", "").isEmpty())
    }

    @Test fun tagLineRoundTrip() {
        val t = "---\ntitle: x\n---\n본문\n\n태그: #a #b\n"
        assertEquals(listOf("a", "b"), NoteText.tagLineTags(t))
        assertEquals("---\ntitle: x\n---\n본문\n\n태그: #b #c_d\n", NoteText.setTagLine(t, listOf("b", "c d")))
        assertEquals("---\ntitle: x\n---\n본문\n\n", NoteText.setTagLine(t, emptyList()))
        assertEquals("본문\n\n태그: #새\n", NoteText.setTagLine("본문\n", listOf("#새")))
        assertEquals("본문", NoteText.setTagLine("본문", emptyList()))
    }

    @Test fun linkByTitle() {
        assertEquals("회의록", NoteText.findByTitle(listOf("a", "회의록"), " 회의록 ") { it })
    }

    @Test fun linkByTitleLatest() {
        val items = listOf("회의록" to 1L, "회의록" to 9L, "다른" to 99L)
        assertEquals(9L, NoteText.findLatestByTitle(items, "회의록", { it.first }, { it.second })?.second)
    }

    @Test fun retagRenamesEverywhere() {
        val t = "---\ntitle: x\ntags: [Work, 경제]\n---\n본문 #work 와 #workout 은 다르고\n```\n#work\n```\n\n태그: #경제 #work\n"
        val r = NoteText.retag(t, "work", "일")
        assertEquals("---\ntitle: x\ntags: [일, 경제]\n---\n본문 #일 와 #workout 은 다르고\n```\n#work\n```\n\n태그: #경제 #일\n", r)
    }

    @Test fun retagDeleteAndMerge() {
        val t = "---\ntags:\n  - a\n  - b\n---\n글 #b 끝\n\n태그: #a #b\n"
        assertEquals("---\ntags:\n  - a\n---\n글  끝\n\n태그: #a\n", NoteText.retag(t, "b", null))
        // 이미 있는 이름으로 바꾸면 합쳐진다(중복 없음)
        assertEquals("태그: #a\n", NoteText.retag("태그: #a #b\n", "b", "a"))
        assertEquals("그대로 #z", NoteText.retag("그대로 #z", "q", "r"))
    }

    @Test fun dropTitleLineRemovesOnlyTheLineUsedAsTitle() {
        // 첫 줄이 제목이 되면 본문에서는 빠진다(제목 test / 본문 test 처럼 두 번 나오지 않게)
        assertEquals("", NoteText.dropTitleLine("test"))
        assertEquals("둘째 줄" + "\n" + "셋째", NoteText.dropTitleLine("test" + "\n" + "\n" + "둘째 줄" + "\n" + "셋째"))
        // 맨 앞의 빈 줄·마크다운 기호가 있어도 제목으로 고른 줄을 뺀다
        assertEquals("본문", NoteText.dropTitleLine("\n" + "# 제목" + "\n" + "본문"))
        // 머리말은 그대로 둔다
        assertEquals("---" + "\n" + "tags: [a]" + "\n" + "---" + "\n" + "본문", NoteText.dropTitleLine("---" + "\n" + "tags: [a]" + "\n" + "---" + "\n" + "제목" + "\n" + "본문"))
        // 제목으로 삼을 줄이 없으면 그대로
        assertEquals("---", NoteText.dropTitleLine("---"))
        // titleFromBody 가 고른 줄과 같은 줄을 뺀다
        val text = "첫 글" + "\n" + "나머지"
        assertEquals("첫 글", NoteText.titleFromBody(text))
        assertEquals("나머지", NoteText.dropTitleLine(text))
    }
}
