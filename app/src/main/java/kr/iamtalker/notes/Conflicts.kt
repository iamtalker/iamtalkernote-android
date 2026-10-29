package kr.iamtalker.notes

import android.content.Context
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 동기화 충돌: Syncthing 은 양쪽에서 같은 노트를 고치면 '이름.sync-conflict-20240101-123456-ABCDEFG.md' 복사본을 남긴다.
 *  원래 노트와 충돌 복사본을 비교해 하나를 고르거나 둘 다 둔다. 어느 쪽을 고르든 지는 쪽 내용은 노트 버전이나 휴지통에 남는다. */
object Conflicts {
    private val pattern = Regex("^(.*)\\.sync-conflict-\\d{8}-\\d{6}(?:-[A-Za-z0-9]+)?(\\.[^.]+)$")

    fun isConflict(name: String) = pattern.matches(name)

    /** 충돌 복사본의 원래 파일 이름 */
    fun originalName(name: String): String? = pattern.matchEntire(name)?.let { it.groupValues[1] + it.groupValues[2] }

    /** 충돌 복사본 이름에 들어 있는 날짜(없으면 null): 20240101-123456 */
    fun stamp(name: String): String? = Regex("\\.sync-conflict-(\\d{8}-\\d{6})").find(name)?.groupValues?.get(1)

    data class Pair(val conflict: Note, val original: Note?)

    enum class Choice { KEEP_MINE, USE_THEIRS, KEEP_BOTH, RESTORE_NAME }

    /** 충돌 하나를 푼다. 원래 노트(original)가 있으면 KEEP_MINE / USE_THEIRS / KEEP_BOTH, 없으면 RESTORE_NAME 이나 KEEP_BOTH */
    fun resolve(ctx: Context, tree: Uri, model: NotesModel, p: Pair, choice: Choice) {
        val rel = History.relOf(p.conflict)
        val theirs = NoteRepo.read(ctx, p.conflict.uri)
        when (choice) {
            Choice.KEEP_MINE -> {
                val o = p.original ?: throw IllegalStateException("원래 노트가 없습니다")
                // 상대 내용을 원래 노트의 버전으로 남긴 뒤 충돌 복사본은 휴지통으로
                History.snapshot(ctx, tree, History.relOf(o), theirs, "", true)
                model.trashNote(p.conflict)
                History.journal(ctx, tree, "conflict", History.relOf(o), detail = "내 것 유지")
            }
            Choice.USE_THEIRS -> {
                val o = p.original ?: throw IllegalStateException("원래 노트가 없습니다")
                val mine = NoteRepo.read(ctx, o.uri)
                History.snapshot(ctx, tree, History.relOf(o), mine, "", true)   // 내 내용은 이전 버전으로
                NoteRepo.write(ctx, o.uri, theirs)
                model.trashNote(p.conflict)
                History.journal(ctx, tree, "conflict", History.relOf(o), detail = "상대 것으로 교체")
            }
            Choice.KEEP_BOTH -> {
                val fmt = SimpleDateFormat("yyyy.MM.dd HH.mm", Locale.KOREA)
                val base = (originalName(p.conflict.name) ?: p.conflict.name).removeSuffix(".md")
                val renamed = NoteRepo.rename(ctx, p.conflict, base + " (충돌 " + fmt.format(Date(p.conflict.modified)) + ")")
                History.moveHistory(ctx, tree, rel, History.relOf(renamed))
                History.journal(ctx, tree, "conflict", History.relOf(renamed), detail = "둘 다 둠")
            }
            Choice.RESTORE_NAME -> {
                val name = originalName(p.conflict.name) ?: throw IllegalStateException("원래 이름을 알 수 없습니다")
                val renamed = NoteRepo.rename(ctx, p.conflict, name.removeSuffix(".md"))
                History.journal(ctx, tree, "conflict", History.relOf(renamed), detail = "원래 이름으로")
            }
        }
    }
}
