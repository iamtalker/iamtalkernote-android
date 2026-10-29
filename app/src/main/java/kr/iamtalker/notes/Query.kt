package kr.iamtalker.notes

/** 검색식: 공백/AND = 둘 다, OR, NOT(또는 -낱말), 괄호, "정확한 구절", #태그, & | ! 기호. 데스크톱 앱(folder-search)과 같은 문법.
 *  AND·OR·NOT 은 대문자만 연산자로 읽는다. */
sealed class Q {
    data class Term(val s: String) : Q()
    data class Tag(val s: String) : Q()
    data class Not(val q: Q) : Q()
    data class And(val a: List<Q>) : Q()
    data class Or(val a: List<Q>) : Q()
}

object QueryParser {
    private enum class K { WORD, PHRASE, OPEN, CLOSE }
    private data class T(val k: K, val s: String)

    private fun tokenize(input: String): List<T> {
        val out = ArrayList<T>()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            when {
                c.isWhitespace() -> i++
                c == '(' -> { out.add(T(K.OPEN, "(")); i++ }
                c == ')' -> { out.add(T(K.CLOSE, ")")); i++ }
                c == '"' -> {
                    val end = input.indexOf('"', i + 1).let { if (it < 0) input.length else it }
                    val s = input.substring(i + 1, end)
                    if (s.isNotBlank()) out.add(T(K.PHRASE, s))
                    i = end + 1
                }
                else -> {
                    var j = i
                    while (j < input.length && !input[j].isWhitespace() && input[j] != '(' && input[j] != ')' && input[j] != '"') j++
                    out.add(T(K.WORD, input.substring(i, j)))
                    i = j
                }
            }
        }
        return out
    }

    fun parse(input: String): Q? {
        val toks = tokenize(input)
        var p = 0
        fun peek() = toks.getOrNull(p)
        fun isOr(t: T?) = t != null && t.k == K.WORD && (t.s == "OR" || t.s == "|")
        fun isAnd(t: T?) = t != null && t.k == K.WORD && (t.s == "AND" || t.s == "&")
        fun isNot(t: T?) = t != null && t.k == K.WORD && (t.s == "NOT" || t.s == "!")
        lateinit var parseOr: () -> Q?

        fun atom(): Q? {
            val t = peek() ?: return null
            when (t.k) {
                K.OPEN -> {
                    p++
                    val q = parseOr()
                    if (peek()?.k == K.CLOSE) p++
                    return q
                }
                K.CLOSE -> return null
                K.PHRASE -> { p++; return Q.Term(t.s.lowercase()) }
                K.WORD -> {
                    p++
                    val w = t.s
                    return when {
                        w.length > 1 && (w.startsWith("-") || w.startsWith("!")) -> Q.Not(Q.Term(w.substring(1).lowercase()))
                        w.length > 1 && w.startsWith("#") -> Q.Tag(w.substring(1).lowercase())
                        else -> Q.Term(w.lowercase())
                    }
                }
            }
        }
        fun unary(): Q? {
            if (isNot(peek())) {
                p++
                val q = unary() ?: return null
                return Q.Not(q)
            }
            return atom()
        }
        fun and(): Q? {
            val parts = ArrayList<Q>()
            while (true) {
                val t = peek() ?: break
                if (t.k == K.CLOSE || isOr(t)) break
                if (isAnd(t)) { p++; continue }
                val u = unary()
                if (u == null) { if (peek()?.k == K.CLOSE) break else { p++; continue } }
                parts.add(u)
            }
            return when (parts.size) { 0 -> null; 1 -> parts[0]; else -> Q.And(parts) }
        }
        parseOr = {
            val parts = ArrayList<Q>()
            and()?.let { parts.add(it) }
            while (isOr(peek())) {
                p++
                and()?.let { parts.add(it) }
            }
            when (parts.size) { 0 -> null; 1 -> parts[0]; else -> Q.Or(parts) }
        }
        val q = parseOr()
        // 짝 없는 ')' 가 남아 있어도 그 뒤는 무시한다
        return q
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** 색인 DB(hay=제목+본문 소문자, tags)에서 찾는 WHERE 조건과 인자 */
    fun toSql(q: Q, args: MutableList<String>): String = when (q) {
        is Q.Term -> { args.add("%" + esc(q.s) + "%"); "hay LIKE ? ESCAPE '\\'" }
        is Q.Tag -> { args.add("%\n" + esc(q.s) + "\n%"); "(char(10)||lower(tags)||char(10)) LIKE ? ESCAPE '\\'" }
        is Q.Not -> "NOT (" + toSql(q.q, args) + ")"
        is Q.And -> q.a.joinToString(" AND ", "(", ")") { toSql(it, args) }
        is Q.Or -> q.a.joinToString(" OR ", "(", ")") { toSql(it, args) }
    }

    /** 제목(과 태그)만으로 비교할 때 */
    fun matches(q: Q, title: String, tags: List<String>): Boolean = when (q) {
        is Q.Term -> title.lowercase().contains(q.s)
        is Q.Tag -> tags.any { it.equals(q.s, ignoreCase = true) }
        is Q.Not -> !matches(q.q, title, tags)
        is Q.And -> q.a.all { matches(it, title, tags) }
        is Q.Or -> q.a.any { matches(it, title, tags) }
    }

    /** 부정(NOT) 밖에 있는 일반 낱말들 — 관련도 계산용 */
    fun terms(q: Q): List<String> = when (q) {
        is Q.Term -> listOf(q.s)
        is Q.Tag, is Q.Not -> emptyList()
        is Q.And -> q.a.flatMap(::terms)
        is Q.Or -> q.a.flatMap(::terms)
    }
}
