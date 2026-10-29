package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryTest {
    /** 데스크톱(store.py hist_dir)과 같은 폴더 이름이 나와야 두 앱이 같은 노트 버전을 본다: sha1(소문자 상대 경로)[:20] */
    @Test fun hashMatchesDesktop() {
        assertEquals("1b522e2ef9877c0f5e26", History.hash("첫째/노트.md"))
        assertEquals("67bf7a7b42c52f7d460d", History.hash("Abc.MD"))
        assertEquals(History.hash("abc.md"), History.hash("ABC.MD"))
    }
}
