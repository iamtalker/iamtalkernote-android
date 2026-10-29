package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTextTest {
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
}
