package kr.iamtalker.notes

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/** 정리 도구(데스크톱 cleanup.py 의 일부): 중복 노트 찾기, 안 쓰는 미디어 찾기. 화면 코드는 Screens.kt */
object Cleanup {
    private val suffix = Regex("(\\s*\\(\\d+\\)|-\\d+)$")

    /** 같은 이름에 앱·클리퍼가 붙이는 번호("제목-1", "제목 (2)")를 뗀 제목 */
    fun titleKey(title: String): String = title.replace(suffix, "").trim().lowercase()

    /** 색인에 저장된 글(제목\n머리말+본문, 소문자)에서 머리말과 제목 줄을 뺀 본문 */
    fun bodyOf(hay: String): String = Meta.split(hay.substringAfter('\n', "")).second.trim()

    /** 제목이 같고(번호는 무시) 본문이 완전히 같은 노트끼리 묶는다 → 묶음마다 최근 수정 순 */
    fun duplicates(index: Index, notes: List<Note>, minBody: Int = 30): List<List<Note>> {
        val buckets = notes.groupBy { titleKey(it.title) }.filter { it.value.size in 2..60 }
        val out = ArrayList<List<Note>>()
        val hays = index.hayOf(buckets.values.flatten().map { it.uri.toString() })
        for ((_, group) in buckets) {
            val byBody = group.groupBy { bodyOf(hays[it.uri.toString()] ?: "") }
            for ((body, same) in byBody) if (same.size >= 2 && body.length >= minBody) out.add(same.sortedByDescending { it.date })
        }
        return out.sortedByDescending { it.first().date }
    }

    // ------------------------------------------------------------ 안 쓰는 미디어
    private val ref = Regex("[._]resources/([^)\\s\"'<>]+)")

    /** 글 속 .resources / _resources 링크의 파일 이름(소문자, %xx 풀어서) */
    fun mediaRefs(text: String): Set<String> {
        val out = HashSet<String>()
        for (m in ref.findAll(text)) {
            val raw = m.groupValues[1].substringBefore('?').substringBefore('#')
            out.add(java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8").substringAfterLast('/').lowercase())
        }
        return out
    }

    data class Media(val id: String, val name: String, val size: Long, val dirId: String)

    /** 어떤 노트(휴지통 속 노트 포함)도 가리키지 않는 .resources · _resources 의 파일, 큰 것부터 */
    fun unusedMedia(ctx: Context, tree: Uri, index: Index, progress: (String) -> Unit = {}): List<Media> {
        val root = DocumentsContract.getTreeDocumentId(tree)
        val used = HashSet<String>()
        progress("노트의 링크를 읽는 중…")
        for (h in index.hayLike("%resources/%")) used.addAll(mediaRefs(h))
        progress("휴지통의 노트를 읽는 중…")
        History.dir(ctx, tree, root, History.TRASH, false)?.let { t ->
            for (e in History.list(ctx, tree, t).filter { it.isDir }.take(500)) runCatching {
                History.list(ctx, tree, e.id).firstOrNull { it.name == "item" && !it.isDir && it.size in 1..2_000_000 }
                    ?.let { used.addAll(mediaRefs(NoteRepo.read(ctx, History.docUri(tree, it.id)).lowercase())) }
            }
        }
        progress("미디어 폴더를 읽는 중…")
        val out = ArrayList<Media>()
        for (dirName in listOf(".resources", "_resources")) {
            val d = History.dir(ctx, tree, root, dirName, false) ?: continue
            for (f in History.list(ctx, tree, d)) if (!f.isDir && f.name.lowercase() !in used) out.add(Media(f.id, f.name, f.size, d))
        }
        return out.sortedByDescending { it.size }
    }
}
