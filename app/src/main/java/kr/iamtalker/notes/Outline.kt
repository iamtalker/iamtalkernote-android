package kr.iamtalker.notes

/** 아웃라이너 보기(컴퓨터 앱 outline.py 와 같은 생각): 목록(-, *, 1.)을 점(●) 계층으로 보이게 하고, 점을 누르면 그 항목으로 줌인한다.
 *  목록이 아닌 글(문단·제목·인용·표·코드·그림)은 '메모' 블록으로 따로 보인다. 화면을 모르는 순수 계산이라 시험할 수 있다. */
object Outline {
    class Node(val id: Int, val depth: Int, val marker: String, val text: String, val checked: Boolean?, val parent: Node?, val line: Int = 0) {
        val children = ArrayList<Node>()
        val indent get() = depth
    }

    sealed class Row {
        data class Memo(val block: Block) : Row()
        data class Bullet(val node: Node, val depth: Int) : Row()
        data class Crumbs(val path: List<Node>) : Row()      // 경로: 맨 위(노트 전체) 다음부터 부모까지
        data class Title(val node: Node) : Row()
        object Empty : Row()
    }

    fun hasList(blocks: List<Block>) = blocks.any { it is Block.Li }

    /** 블록들에서 항목(Node)들을 위에서 아래 순서로 만든다. 들여쓰기(Li.indent)가 더 깊으면 하위 항목 */
    fun nodes(blocks: List<Block>): List<Node> {
        val out = ArrayList<Node>()
        val stack = ArrayList<Pair<Int, Node>>()
        for (b in blocks) {
            if (b !is Block.Li) { stack.clear(); continue }               // 목록 사이에 낀 다른 블록은 목록을 끊는다
            while (stack.isNotEmpty() && stack.last().first >= b.indent) stack.removeAt(stack.size - 1)
            val parent = stack.lastOrNull()?.second
            val n = Node(out.size, stack.size, b.marker, b.text, b.checked, parent, b.line)
            parent?.children?.add(n)
            out.add(n)
            stack.add(b.indent to n)
        }
        return out
    }

    /** 부모 항목의 id (없으면 null = 노트 전체) */
    fun parentId(nodes: List<Node>, id: Int): Int? = nodes.getOrNull(id)?.parent?.id

    fun ancestors(n: Node): List<Node> {
        val out = ArrayList<Node>()
        var p = n.parent
        while (p != null) { out.add(p); p = p.parent }
        return out.reversed()
    }

    private fun walk(ns: List<Node>, d: Int, out: MutableList<Row>) {
        for (n in ns) { out.add(Row.Bullet(n, d)); walk(n.children, d + 1, out) }
    }

    /** 화면에 그릴 줄들. zoom 이 null 이면 노트 전체(메모 블록 + 아웃라이너), 아니면 그 항목으로 줌인한 모습 */
    fun rows(blocks: List<Block>, zoom: Int?): List<Row> {
        val all = nodes(blocks)
        val z = zoom?.let { all.getOrNull(it) }
        val out = ArrayList<Row>()
        if (z != null) {
            out.add(Row.Crumbs(ancestors(z)))
            out.add(Row.Title(z))
            if (z.children.isEmpty()) out.add(Row.Empty) else walk(z.children, 0, out)
            return out
        }
        var i = 0
        var seq = 0
        while (i < blocks.size) {
            val b = blocks[i]
            if (b is Block.Li) {                                           // 이어진 목록 한 덩어리는 최상위 항목부터 걷는다
                var j = i
                while (j < blocks.size && blocks[j] is Block.Li) j++
                val groupNodes = all.subList(seq, seq + (j - i))
                walk(groupNodes.filter { it.parent == null }, 0, out)
                seq += j - i
                i = j
            } else { out.add(Row.Memo(b)); i++ }
        }
        return out
    }
}
