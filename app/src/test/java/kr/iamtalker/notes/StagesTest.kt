package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StagesTest {
    private val json = """{"version":1,"made":1,"tiers":[{"name":"a","files":["x/new.md",".resources/p.png"]},{"name":"b","files":["x/old[1].md"]},{"name":"c","files":["y/a{b}.md","z/c #1.md"]}]}"""

    @Test fun parsesTiers() {
        val t = Stages.parse(json)!!
        assertEquals(3, t.size)
        assertEquals(listOf("x/new.md", ".resources/p.png"), t[0])
        assertNull(Stages.parse("not json"))
    }

    @Test fun hiddenIsEverythingAfterTheReleasedTiers() {
        val t = Stages.parse(json)!!
        // 패턴에서 뜻이 있는 글자는 ? 로 바꾼다(# 와 ! 는 줄 맨 앞이 아니면 괜찮다)
        assertEquals(listOf("/x/old?1?.md", "/y/a?b?.md", "/z/c #1.md"), Stages.hidden(t, 1))
        assertEquals(listOf("/y/a?b?.md", "/z/c #1.md"), Stages.hidden(t, 2))
        assertEquals(emptyList<String>(), Stages.hidden(t, 3))
    }

    @Test fun nextTierOpensOnlyWhenCurrentIsDone() {
        assertEquals(1, Stages.nextReleased(1, 5, "syncing", 0))
        assertEquals(1, Stages.nextReleased(1, 5, "idle", 100))
        assertEquals(2, Stages.nextReleased(1, 5, "idle", 0))
        assertEquals(5, Stages.nextReleased(5, 5, "idle", 0))    // 마지막까지 열렸으면 그대로
    }
}
