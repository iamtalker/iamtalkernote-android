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

    @Test fun looksDoneNeedsTheComputersListAndConnection() {
        assertEquals(true, Stages.looksDone("idle", 0, 100, true))
        assertEquals(false, Stages.looksDone("idle", 0, 0, true))        // 컴퓨터 목록이 아직 안 옴
        assertEquals(false, Stages.looksDone("idle", 0, 100, false))     // 연결 끊김
        assertEquals(false, Stages.looksDone("syncing", 0, 100, true))
        assertEquals(false, Stages.looksDone("idle", 5, 100, true))
    }

    @Test fun nextTierOpensOnlyAfterHoldingForAWhile() {
        assertEquals(1 to 1000L, Stages.advance(1, 5, true, 0L, 1000L))              // 처음 done 으로 보임: 시각만 적는다
        assertEquals(1 to 1000L, Stages.advance(1, 5, true, 1000L, 10_000L))         // 아직 20초 안 됨
        assertEquals(2 to 0L, Stages.advance(1, 5, true, 1000L, 21_000L))            // 20초 이어짐: 다음 단계
        assertEquals(1 to 0L, Stages.advance(1, 5, false, 1000L, 21_000L))           // 중간에 받을 게 생기면 처음부터
        assertEquals(5 to 0L, Stages.advance(5, 5, true, 0L, 1000L))                 // 마지막까지 열렸으면 그대로
    }
}
