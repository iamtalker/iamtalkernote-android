package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DateQueryTest {
    private fun day(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() }

    @Test fun rangeAndWords() {
        val p = DateQuery.split("date:20101011-20240104 만화&아이유")
        assertEquals("만화&아이유", p.rest)
        assertEquals("2010-10-11", day(p.from))
        assertEquals("2024-01-04", day(p.to))
        assertTrue(p.bad.isEmpty())
    }

    @Test fun singleMonthYearAndOpenEnds() {
        assertEquals("2024-02-29", day(DateQuery.split("date:202402").to))
        assertEquals("2024-01-01", day(DateQuery.split("날짜:2024").from))
        assertNull(DateQuery.split("date:20240101-").to)
        assertNull(DateQuery.split("date:-20240104").from)
    }

    @Test fun impossibleDateIsReportedNotIgnored() {
        assertEquals(listOf("date:20200202-20222222"), DateQuery.split("date:20200202-20222222 시노지키").bad)
        assertEquals(listOf("date:abc"), DateQuery.split("date:abc").bad)
    }

    @Test fun noDateLeavesQueryAlone() {
        val p = DateQuery.split("회의록 AND 예산")
        assertEquals("회의록 AND 예산", p.rest)
        assertNull(p.from); assertNull(p.to)
    }
}
