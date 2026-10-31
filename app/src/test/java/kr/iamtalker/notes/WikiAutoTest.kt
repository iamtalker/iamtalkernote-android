package kr.iamtalker.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class WikiAutoTest {
    @After fun off() { WikiAuto.enabled = false }

    private fun on(vararg t: String) { WikiAuto.setTitles(t.toList()); WikiAuto.enabled = true }

    @Test fun koreanWithParticle() {
        on("아이유", "만화")
        assertEquals(listOf("오늘 " to null, "아이유" to "아이유", "가 " to null, "만화" to "만화", "를 봄" to null), WikiAuto.split("오늘 아이유가 만화를 봄"))
    }

    @Test fun longestFirst() {
        on("서울", "서울 맛집")
        assertEquals(listOf("서울 맛집" to "서울 맛집", " 좋다" to null), WikiAuto.split("서울 맛집 좋다"))
    }

    @Test fun englishNeedsWordBoundary() {
        on("git")
        assertEquals(listOf("digital git" to null).let { listOf("digital " to null, "git" to "git") }, WikiAuto.split("digital git"))
    }

    @Test fun oneCharTitleIgnoredAndOffByDefault() {
        on("가")
        assertEquals(listOf("가나다" to null), WikiAuto.split("가나다"))
        WikiAuto.enabled = false
        on("아이유"); WikiAuto.enabled = false
        assertEquals(listOf("아이유" to null), WikiAuto.split("아이유"))
    }
}
