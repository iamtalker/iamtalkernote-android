package kr.iamtalker.notes

/** 본문 속 #태그와 '태그: a, b' 줄을 찾는 규칙. 데스크톱 앱(notes.py 의 hashtags · mask_protected · line_tags)과 똑같이 맞춘다 —
 *  코드·주소·그림 속의 #(위키 각주 `](#cite_note-…)` 같은 앵커 포함)와 `( [ / = - . : ? & #` 바로 뒤의 #, 숫자로 시작하는 이름은 태그가 아니다. */
object HashTags {
    private val hashFast = Regex("#([A-Za-z_가-힣][\\p{L}\\p{N}_\\-]*)")
    private const val BAD_PREV = "#&/(=-.:[?"
    private val codeRe = Regex("```.*?```|`[^`\\n]*`", RegexOption.DOT_MATCHES_ALL)
    private val urlRe = Regex("(?:https?|ftp)://[^\\s<>\\]\\)]+|file:///[^\\s<>\\]\\)]+", RegexOption.IGNORE_CASE)
    private val dataImg = Regex("data:image/[a-z+.\\-]+;base64,[A-Za-z0-9+/=]+", RegexOption.IGNORE_CASE)
    private val tagLineRe = Regex("^[ \\t]*(?:태그|tags?)[ \\t]*[:：][ \\t]*(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val badChars = Regex("[\\[\\]()/<>]")
    private val splitter = Regex("[,\\s]+")

    private fun blank(m: MatchResult): String = m.value.map { if (it == '\n') '\n' else ' ' }.joinToString("")

    /** 코드·그림·주소 덩어리를 같은 길이의 공백으로 가린다(줄바꿈은 그대로) — 글자 위치가 그대로라 원문에서 바로 바꿀 수 있다 */
    fun mask(text: String): String = urlRe.replace(codeRe.replace(dataImg.replace(text, ::blank), ::blank), ::blank)

    /** 본문 속 #태그: (원문 속 시작 위치, 이름) 목록 */
    fun find(text: String): List<Pair<Int, String>> {
        if (!text.contains('#')) return emptyList()
        val masked = mask(text)
        val out = ArrayList<Pair<Int, String>>()
        for (m in hashFast.findAll(masked)) {
            val i = m.range.first
            if (i > 0) {
                val p = masked[i - 1]
                if (p.isLetterOrDigit() || p == '_' || p in BAD_PREV) continue
            }
            out.add(i to m.groupValues[1])
        }
        return out
    }

    /** '태그: a, b' 줄에 적힌 태그들. 링크·괄호·긴 조각은 태그가 아니다(블로그의 'Tags: [블로그](주소)' 같은 줄) */
    fun lineTags(body: String): List<String> = lineTagsAt(body).map { it.second }.distinct()

    /** (그 줄의 시작 위치, 이름) */
    private fun lineTagsAt(body: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        for (m in tagLineRe.findAll(body)) {
            val seen = HashSet<String>()
            for (raw in m.groupValues[1].split(splitter)) {
                val t = raw.trim().trimStart('#')
                if (t.isNotEmpty() && t.length <= 30 && !badChars.containsMatchIn(t) && seen.add(t)) out.add(m.range.first to t)
            }
        }
        return out
    }

    /** 본문의 모든 태그(‘태그:’ 줄 + #태그)를 글에 나온 순서대로, 겹치는 이름은 한 번만 */
    fun ordered(body: String): List<String> = (find(body) + lineTagsAt(body)).sortedBy { it.first }.map { it.second }.distinct()
}
