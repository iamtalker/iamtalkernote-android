package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class PlannerTest {
    private fun vault(): String = File.createTempFile("vault", "").also { it.delete(); it.mkdirs() }.absolutePath

    @Test fun parsesAndRendersLikeDesktop() {
        val text = "# 2026년 11월\n\n## 공수\n- 2026-11-02: 1\n- 2026-11-03: 1.5\n\n## 할일\n- [ ] 2026-11-05 회의 준비\n- [x] 2026-11-03 보고서 쓰기 ✓2026-11-04\n"
        val (rows, g) = PlannerMd.parse(text)
        assertEquals(2, rows.size)
        assertEquals(PlannerMd.Row("2026-11-05", "회의 준비", false, null), rows[0])
        assertEquals(PlannerMd.Row("2026-11-03", "보고서 쓰기", true, "2026-11-04"), rows[1])
        assertEquals(mapOf("2026-11-02" to 1.0, "2026-11-03" to 1.5), g)
        val out = PlannerMd.renderMonth(2026, 11, rows.map { Todo("x", it.text, it.date, it.done, it.doneOn) }, g)
        assertTrue("- 2026-11-03: 1.5" in out && "- [ ] 2026-11-05 회의 준비" in out && "- [x] 2026-11-03 보고서 쓰기 ✓2026-11-04" in out)
        assertEquals("1", PlannerMd.fmt(1.0)); assertEquals("1.5", PlannerMd.fmt(1.5))
    }

    @Test fun summaryMatchesDesktop() {
        val g = mapOf("2026-11-02" to 1.0, "2026-11-03" to 1.5, "2026-11-04" to 2.0, "2026-12-01" to 1.0)
        val s = PlannerCalc.summary(g, 2026, 11, 150000, 20000)
        assertEquals(4.5, s.gongsu, 0.0); assertEquals(3, s.days); assertEquals(675000L, s.pay); assertEquals(60000L, s.meal); assertEquals(735000L, s.sum)
    }

    @Test fun orderedOldestFirstAndDLabels() {
        val ts = listOf(Todo("a", "미래", "2026-11-12", false), Todo("b", "과거", "2026-09-01", false), Todo("c", "날짜없음", null, false))
        assertEquals(listOf("날짜없음", "과거", "미래"), PlannerCalc.ordered(ts).map { it.text })
        val today = LocalDate.of(2026, 10, 31)
        assertEquals("D-12", PlannerCalc.dLabel(ts[0], today)); assertEquals("", PlannerCalc.dLabel(ts[1], today))
        assertEquals("D-day", PlannerCalc.dLabel(Todo("d", "오늘", "2026-10-31", false), today))
        assertEquals("", PlannerCalc.dLabel(Todo("e", "끝냄", "2026-11-12", true, "2026-10-31"), today))
    }

    @Test fun looseDatesAndDue() {
        assertEquals("2026-09-14", PlannerCalc.parseLoose("20260914")); assertEquals("2026-09-04", PlannerCalc.parseLoose("2026.9.4"))
        assertNull(PlannerCalc.parseLoose("2026-02-30")); assertNull(PlannerCalc.parseLoose("abc"))
        val ts = listOf(Todo("a", "오늘", "2026-11-03", false), Todo("b", "미래", "2027-01-01", false), Todo("c", "끝", "2026-11-01", true))
        assertEquals(listOf("a"), PlannerCalc.due(ts, "2026-11-03", emptyMap()).map { it.id })
        assertTrue(PlannerCalc.due(ts, "2026-11-03", mapOf("a" to "2026-11-03")).isEmpty())
    }

    @Test fun storeWritesMonthFilesAndReadsDesktopFiles() {
        val v = vault()
        val s = PlannerStore(v)
        s.add("날짜 없는 할일")
        val t = s.add("회의 준비", "2026-11-05")!!
        s.setGongsu("2026-11-02", 1.0)
        val month = File(v, "달력/2026년 11월.md").readText()
        assertTrue("- 2026-11-02: 1\n" in month && "- [ ] 2026-11-05 회의 준비" in month)
        assertTrue("- [ ] 날짜 없는 할일" in File(v, "달력/할일.md").readText())
        File(v, "달력/2026년 11월.md").appendText("- [ ] 2026-11-20 컴퓨터에서 쓴 할일\n")
        s.load()
        assertTrue(s.todos.any { it.text == "컴퓨터에서 쓴 할일" })
        s.setDone(t.id, true, "2026-11-12")
        assertTrue("✓2026-11-12" in File(v, "달력/2026년 11월.md").readText())
        s.setDate(t.id, "2026-12-01")
        assertTrue(File(v, "달력/2026년 12월.md").exists())
        s.remove(t.id)
        assertFalse(File(v, "달력/2026년 12월.md").exists())
        assertEquals(setOf("날짜 없는 할일", "컴퓨터에서 쓴 할일"), PlannerStore(v).todos.map { it.text }.toSet())
    }
}
