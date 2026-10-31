package kr.iamtalker.notes

/** 위키 낱말 자동 연결(PC wikilinks.auto_links 와 같은 규칙): 괄호 없이 다른 노트 제목과 같은 낱말을 그 노트로 잇는다.
 *  2글자 이상 제목만, 영문·숫자 제목은 낱말 경계에서만, 한글은 조사가 붙으므로 경계를 보지 않는다. 가장 긴 제목 먼저.
 *  첫 글자별로 후보 길이를 모아 두어 글자마다 모든 길이를 뒤지지 않는다(노트가 1만 개여도 빠르게). */
object WikiAuto {
    @Volatile var enabled = false
    @Volatile var version = 0
        private set
    @Volatile private var table: Map<String, String> = emptyMap()
    @Volatile private var lengths: Map<Char, IntArray> = emptyMap()

    private fun norm(t: String) = t.trim().replace(Regex("""\s+"""), " ").lowercase()
    private fun word(c: Char) = c.code < 128 && c.isLetterOrDigit()

    /** 노트 제목들로 표를 새로 만든다(같은 제목이면 첫째만) */
    fun setTitles(titles: Collection<String>) {
        val t = HashMap<String, String>()
        for (x in titles) { val k = norm(x); if (k.length >= 2 && k !in t) t[k] = x.trim() }
        val by = HashMap<Char, MutableSet<Int>>()
        for (k in t.keys) by.getOrPut(k[0]) { sortedSetOf() }.add(k.length)
        lengths = by.mapValues { e -> e.value.sortedDescending().toIntArray() }
        table = t
        version++
    }

    /** text → [(조각, 연결할 제목 또는 null)] */
    fun split(text: String): List<Pair<String, String?>> {
        if (!enabled || table.isEmpty() || text.length < 2) return listOf(text to null)
        val low = text.lowercase()
        if (low.length != text.length) return listOf(text to null)      // 소문자로 바꾸면 길이가 달라지는 드문 글자는 건너뜀
        val out = ArrayList<Pair<String, String?>>()
        var i = 0; var last = 0; val n = text.length
        val tb = table; val lg = lengths
        while (i < n) {
            var hitLen = 0; var hitTitle: String? = null
            val ls = lg[low[i]]
            if (ls != null) for (ln in ls) {
                if (i + ln > n) continue
                val key = low.substring(i, i + ln)
                val title = tb[key] ?: continue
                if ((word(key[0]) && i > 0 && word(low[i - 1])) || (word(key[ln - 1]) && i + ln < n && word(low[i + ln]))) continue
                hitLen = ln; hitTitle = title; break
            }
            if (hitTitle != null) {
                if (i > last) out.add(text.substring(last, i) to null)
                out.add(text.substring(i, i + hitLen) to hitTitle)
                i += hitLen; last = i
            } else i++
        }
        if (last < n) out.add(text.substring(last) to null)
        return if (out.isEmpty()) listOf(text to null) else out
    }
}
