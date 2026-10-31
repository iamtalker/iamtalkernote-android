package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class EtaTest {
    @Test fun rateAndRemaining() {
        val e = Eta()
        assertEquals(0.0, e.update(0, 1000), 0.0)
        assertEquals(0.0, e.update(10_000, 900), 0.0)              // 15초 전에는 모름
        val r = e.update(20_000, 800)                              // 20초에 200바이트 → 초당 10
        assertEquals(10.0, r, 0.01)
        assertEquals(80L, Eta.seconds(800, r))
        assertEquals(-1L, Eta.seconds(800, 0.0))
        assertEquals(0L, Eta.seconds(0, 0.0))
    }
    @Test fun newTierResets() {
        val e = Eta()
        e.update(0, 1000); e.update(20_000, 800)
        assertEquals(0.0, e.update(25_000, 5000), 0.0)             // 남은 용량이 늘면 다시 센다
    }
    @Test fun texts() {
        assertEquals("계산 중", Eta.text(-1)); assertEquals("1분 미만", Eta.text(30)); assertEquals("약 5분", Eta.text(300))
        assertEquals("약 2시간 3분", Eta.text(7380)); assertEquals("약 1일 2시간", Eta.text(93600))
        assertEquals("12MB", Eta.size(12L shl 20))
    }
}

class StuckKickTest {
    private val line = ForkSync.FolderLine("x", "idle", 10, 100, needFiles = 5, totalFiles = 50)
    @Test fun kicksOnlyAfterIdleTwoMinutes() {
        val api = ForkSync.Api("k", 1, plain = true)
        assertEquals(false, ForkSync.kickIfStuck(api, "t1", line, true, 0))             // 처음 본 때
        assertEquals(false, ForkSync.kickIfStuck(api, "t1", line, true, 60_000))        // 아직 1분
        assertEquals(false, ForkSync.kickIfStuck(api, "t1", line.copy(state = "syncing"), true, 200_000))   // 받는 중이면 아무것도 안 함
        assertEquals(false, ForkSync.kickIfStuck(api, "t2", line, false, 0))            // 연결 안 됨
    }
}
