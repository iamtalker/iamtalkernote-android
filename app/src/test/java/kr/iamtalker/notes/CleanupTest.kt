package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class CleanupTest {
    @Test fun titleKeyDropsNumbers() {
        assertEquals("회의록", Cleanup.titleKey("회의록-1"))
        assertEquals("회의록", Cleanup.titleKey("회의록 (2)"))
        assertEquals("a-b", Cleanup.titleKey("A-b"))
    }

    @Test fun bodyIgnoresFrontMatterAndTitle() {
        val a = Cleanup.bodyOf("제목\n---\nupdated: 2020-01-01\n---\n본문 글")
        val b = Cleanup.bodyOf("제목\n---\nupdated: 2024-05-05\n---\n본문 글")
        assertEquals(a, b)
        assertEquals("본문 글", a)
    }

    @Test fun mediaRefsFindFileNames() {
        val refs = Cleanup.mediaRefs("![](../.resources/ABC%20d.png) [x](_resources/q.PDF?x=1) ![](.resources/sub/z.jpg)")
        assertEquals(setOf("abc d.png", "q.pdf", "z.jpg"), refs)
    }
}
