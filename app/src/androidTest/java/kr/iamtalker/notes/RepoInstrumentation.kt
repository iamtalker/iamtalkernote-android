package kr.iamtalker.notes

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.provider.DocumentsContract
import java.io.File

/** 실제 DocumentsProvider를 쓰되 임시 볼트만 건드리는 저장소 회귀 시험. */
class RepoInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val result = Bundle()
        val original = NotesProvider.root
        val temp = File(targetContext.cacheDir, "repo-test-${System.nanoTime()}").apply { mkdirs() }
        try {
            NotesProvider.root = temp
            val ctx = targetContext
            val tree = NotesProvider.treeUri()
            val root = NotesProvider.ROOT_DOC
            fun ensure(path: String) = NoteRepo.ensureFolderPath(root, path,
                { parent, name -> NoteRepo.findFolder(ctx, tree, parent, name) },
                { parent, name -> NoteRepo.createFolder(ctx, tree, parent, name) })
            val parent = ensure("노트북")
            val child = ensure("노트북/하위")
            check(ensure("노트북/하위") == child) { "폴더 재사용 실패" }
            val note = NoteRepo.create(ctx, tree, child, "노트북/하위", "시험")!!
            NoteRepo.write(ctx, note.uri, "삭제 전 본문")
            NoteRepo.scan(ctx, tree)
            check("노트북/하위" in NoteRepo.dirs)
            NoteRepo.trashFolder(ctx, tree, "노트북", parent, root)
            check(!File(temp, "노트북").exists()) { "원래 폴더가 남음" }
            check(NoteRepo.scan(ctx, tree).isEmpty()) { "삭제한 노트가 스캔에 남음" }
            check(NoteRepo.dirs.isEmpty()) { "삭제한 폴더가 스캔에 남음" }
            val trash = File(temp, ".trash").listFiles()!!.single()
            check(File(trash, "item/하위/시험.md").readText() == "삭제 전 본문")
            check(File(trash, "meta.json").readText().contains("노트북"))
            val fresh = ensure("노트북/하위")
            val single = NoteRepo.create(ctx, tree, fresh, "노트북/하위", "하나")!!
            NoteRepo.write(ctx, single.uri, "휴지통과 복원")
            NoteRepo.trash(ctx, tree, single)
            val item = History.trashItems(ctx, tree).single { it.isNote }
            History.restore(ctx, tree, item)
            check(File(temp, "노트북/하위/하나.md").readText() == "휴지통과 복원")
            check(NoteRepo.findFolder(ctx, tree, root, "노트북") != null)
            result.putString("stream", "PASS: existing folder reuse; nested notebook trash; scan cleanup; note trash and restore\n")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${android.util.Log.getStackTraceString(error)}")
            finish(Activity.RESULT_CANCELED, result)
        } finally {
            NotesProvider.root = original
            NoteRepo.clearCache()
            NoteRepo.dirs = emptyMap()
            temp.deleteRecursively()
        }
    }
}
