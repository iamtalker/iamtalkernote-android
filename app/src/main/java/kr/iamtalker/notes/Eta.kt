package kr.iamtalker.notes

/** 받는 속도와 남은 시간 추정: 최근 90초 동안 '남은 용량'이 줄어든 만큼으로 계산한다 */
class Eta(private val windowMs: Long = 90_000, private val minSpanMs: Long = 15_000) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** 지금 남은 용량을 알리면 초당 바이트(아직 모르면 0)를 돌려준다. 남은 용량이 늘면(새 단계가 열림) 다시 센다 */
    fun update(now: Long, needBytes: Long): Double {
        if (samples.isNotEmpty() && needBytes > samples.last().second) samples.clear()
        samples.addLast(now to needBytes)
        while (samples.size > 2 && now - samples.first().first > windowMs) samples.removeFirst()
        val first = samples.first()
        val span = now - first.first
        if (span < minSpanMs || first.second <= needBytes) return 0.0
        return (first.second - needBytes) * 1000.0 / span
    }

    companion object {
        /** 남은 시간(초). 모르면 -1 */
        fun seconds(needBytes: Long, rate: Double): Long = if (needBytes <= 0L) 0L else if (rate <= 0.0) -1L else (needBytes / rate).toLong()

        fun text(sec: Long): String = when {
            sec < 0 -> "계산 중"
            sec < 60 -> "1분 미만"
            sec < 3600 -> "약 ${sec / 60}분"
            sec < 86400 -> "약 ${sec / 3600}시간 ${sec % 3600 / 60}분"
            else -> "약 ${sec / 86400}일 ${sec % 86400 / 3600}시간"
        }

        fun size(b: Long): String = when {
            b >= 1L shl 30 -> "%.1fGB".format(b / 1073741824.0)
            b >= 1L shl 20 -> "%.0fMB".format(b / 1048576.0)
            else -> "%.0fKB".format(b / 1024.0)
        }
    }
}
