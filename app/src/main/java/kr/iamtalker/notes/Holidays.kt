package kr.iamtalker.notes

import java.time.DayOfWeek
import java.time.LocalDate

/** 공휴일(PC app/planner/model.py 와 같은 규칙): 고정 + 음력 표(2024~2030) + 대체공휴일. 표에 없는 해는 음력 공휴일이 빠진다 */
object Holidays {
    private val fixed = mapOf((1 to 1) to "신정", (3 to 1) to "삼일절", (5 to 5) to "어린이날", (6 to 6) to "현충일", (8 to 15) to "광복절", (10 to 3) to "개천절", (10 to 9) to "한글날", (12 to 25) to "성탄절")
    private val lunar = mapOf(
        2024 to mapOf("설날" to (2 to 10), "부처님오신날" to (5 to 15), "추석" to (9 to 17)),
        2025 to mapOf("설날" to (1 to 29), "부처님오신날" to (5 to 5), "추석" to (10 to 6)),
        2026 to mapOf("설날" to (2 to 17), "부처님오신날" to (5 to 24), "추석" to (9 to 25)),
        2027 to mapOf("설날" to (2 to 7), "부처님오신날" to (5 to 13), "추석" to (9 to 15)),
        2028 to mapOf("설날" to (1 to 27), "부처님오신날" to (5 to 2), "추석" to (10 to 3)),
        2029 to mapOf("설날" to (2 to 13), "부처님오신날" to (5 to 20), "추석" to (9 to 22)),
        2030 to mapOf("설날" to (2 to 3), "부처님오신날" to (5 to 9), "추석" to (9 to 12)))
    private val noSubstitute = setOf("신정", "현충일")

    fun of(year: Int): Map<String, String> {
        val groups = ArrayList<Pair<String, List<LocalDate>>>()
        for ((md, name) in fixed) { if (name == "성탄절" && year < 2023) continue; groups += name to listOf(LocalDate.of(year, md.first, md.second)) }
        for ((name, md) in lunar[year] ?: emptyMap()) {
            val c = LocalDate.of(year, md.first, md.second)
            groups += name to (if (name != "부처님오신날") listOf(c.minusDays(1), c, c.plusDays(1)) else listOf(c))
        }
        val out = LinkedHashMap<String, String>()
        for ((name, days) in groups) days.forEachIndexed { i, d -> out[d.toString()] = if (days.size == 1 || i == 1) name else "$name 연휴" }
        for ((name, days) in groups.sortedBy { it.second[0] }) {
            if (name in noSubstitute) continue
            val clash = days.any { it.dayOfWeek == DayOfWeek.SUNDAY || (days.size == 1 && it.dayOfWeek == DayOfWeek.SATURDAY) || groups.count { g -> it in g.second } > 1 }
            if (clash) {
                var d = days.last().plusDays(1)
                while (d.dayOfWeek.value >= 6 || d.toString() in out) d = d.plusDays(1)
                out[d.toString()] = "대체공휴일"
            }
        }
        return out
    }
}
