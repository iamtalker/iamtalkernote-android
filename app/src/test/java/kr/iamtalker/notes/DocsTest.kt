package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DocsTest {
    /** 데스크톱 docs.py 의 export 가 쓰는 한 줄 모양 */
    private val line = """{"id": "ab12", "folder": "문서함", "rel": "하위/계획서.txt", "ext": "txt", "size": 120, "mtime": 1700000000.5,
        "title": "계획서", "tags": ["업무", "중요"], "memo": "확인 필요", "text": "올해 계획서 본문입니다."}""".replace("\n", " ")

    @Test fun parsesDesktopExportLine() {
        val r = DocsRepo.parseLine(line)!!
        assertEquals("ab12", r.id)
        assertEquals("문서함", r.folder)
        assertEquals(listOf("업무", "중요"), r.tags)
        assertEquals(1700000000500L, r.mtime)
        assertEquals("올해 계획서 본문입니다.", r.snip)
        // hay 에는 이름·경로·글·태그·메모가 소문자로, nhay 에는 글이 빠져 있다
        assertEquals(true, r.hay.contains("본문입니다") && r.hay.contains("#업무") && r.hay.contains("확인 필요") && r.hay.contains("하위/계획서.txt"))
        assertEquals(false, r.nhay.contains("본문입니다"))
        assertEquals(true, r.nhay.contains("#중요"))
    }

    @Test fun badLinesAreSkipped() {
        assertNull(DocsRepo.parseLine("not json"))
        assertNull(DocsRepo.parseLine("{\"title\": \"id 없음\"}"))
        assertNotNull(DocsRepo.parseLine("{\"id\": \"x\", \"title\": \"t\"}"))
    }
}
