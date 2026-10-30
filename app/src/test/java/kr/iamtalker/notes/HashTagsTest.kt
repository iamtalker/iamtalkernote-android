package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 기대값은 데스크톱 앱(notes.py)의 hashtags(mask_protected(글)) · line_tags(글)가 같은 입력에서 낸 값이다 */
class HashTagsTest {
    private fun tags(t: String) = HashTags.find(t).map { it.second }

    @Test fun wikiFootnoteAnchorIsNotATag() {
        val t = "가졌다.<sup>[\\[1\\]](#cite_note-Territorial_Kansas_Online-1)</sup> 1856년의 [피의 #진짜태그 입니다"
        assertEquals(listOf("진짜태그"), tags(t))
    }

    @Test fun sameAsDesktopOnPlainText() {
        assertEquals(listOf("태그", "두번째_태그", "tag-b"), tags("본문 #태그 입니다 #두번째_태그 와 #tag-b"))
        assertEquals(emptyList<String>(), tags("주소 http://a.com/page#anchor 와 (#괄호) 와 [x](#frag) 와 a/#슬래시"))
        assertEquals(listOf("밖의태그"), tags("`#인라인코드` 와 #밖의태그"))
        assertEquals(listOf("펜스밖"), tags("```\n#펜스안\n```\n#펜스밖"))
        assertEquals(listOf("_밑줄시작", "한글ABC123"), tags("#123 숫자시작 / word#붙임 / #_밑줄시작 / #한글ABC123"))
        assertEquals(listOf("제목없는공백"), tags("## 제목\n#제목없는공백 다음줄"))
        assertEquals(emptyList<String>(), tags("&#38; 엔티티 와 =#등호 와 -#하이픈 와 :#콜론 와 .#점 와 ?#물음"))
    }

    @Test fun tagLinesSkipLinksAndBrackets() {
        val a = "태그: a, b, [블로그](http://x.y/#z), #c"
        assertEquals(listOf("a", "b", "c"), HashTags.lineTags(a))
        assertEquals(listOf("c"), tags(a))
        val b = "Tags: x y, 한글태그 ,#h"
        assertEquals(listOf("x", "y", "한글태그", "h"), HashTags.lineTags(b))
        assertEquals(listOf("h"), tags(b))
    }

    @Test fun parseKeepsFootnoteOutOfNoteTags() {
        val note = "---\nupdated: 2023-05-01\ntags: [위키]\n---\n# 글\n내용[\\[1\\]](#cite_note-Foo-1) 그리고 #진짜\n\n태그: 참고\n"
        val info = Meta.parse(note)
        assertEquals(setOf("위키", "참고", "진짜"), info.tags.toSet())
        assertFalse(info.tags.any { it.startsWith("cite_note") })
    }

    @Test fun renamingATagLeavesAnchorsAndCodeAlone() {
        val note = "본문 #옛 그리고 [각주](#옛) 와 `#옛` 와 http://a.b/#옛 끝 #옛\n"
        val out = NoteText.retag(note, "옛", "새")
        assertEquals("본문 #새 그리고 [각주](#옛) 와 `#옛` 와 http://a.b/#옛 끝 #새\n", out)
        val gone = NoteText.retag(note, "옛", null)
        assertTrue(gone.contains("[각주](#옛)") && gone.contains("`#옛`") && gone.contains("http://a.b/#옛"))
        assertFalse(gone.contains(" #옛 ") || gone.endsWith("#옛\n"))
    }
}
