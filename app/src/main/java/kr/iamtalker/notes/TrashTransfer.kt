package kr.iamtalker.notes

/** 이동 뒤 이름 변경에 실패하면 되돌리고, 되돌리기도 실패하면 보관본을 지우지 않는다. */
internal object TrashTransfer {
    fun <T : Any> run(prepare: () -> Unit, move: () -> T, rename: (T) -> Unit,
                      rollback: (T) -> Boolean, cleanup: () -> Unit) {
        var moved: T? = null
        try {
            prepare()
            moved = move()
            rename(moved)
        } catch (error: Exception) {
            val safeToRemove = moved?.let { runCatching { rollback(it) }.getOrDefault(false) } ?: true
            if (safeToRemove) runCatching { cleanup() }
            throw error
        }
    }
}
