package kr.iamtalker.notes

import org.junit.Assert.*
import org.junit.Test

class TrashTransferTest {
    @Test fun renameFailureAndRollbackFailureKeepTheOnlyCopy() {
        for (throwRollback in listOf(false, true)) {
            var location = "source"
            var cleaned = false
            val error = runCatching {
                TrashTransfer.run({}, { location = "trash"; "moved" }, { error("rename failed") },
                    { if (throwRollback) error("rollback failed") else false }, { cleaned = true; location = "deleted" })
            }.exceptionOrNull()
            assertEquals("rename failed", error?.message)
            assertEquals("trash", location)
            assertFalse(cleaned)
        }
    }

    @Test fun successfulRollbackAllowsEmptyContainerCleanup() {
        var location = "source"
        var cleaned = false
        runCatching {
            TrashTransfer.run({}, { location = "trash"; "moved" }, { error("rename failed") },
                { location = "source"; true }, { cleaned = true })
        }
        assertEquals("source", location)
        assertTrue(cleaned)
    }

    @Test fun successKeepsTrashAndMoveFailureOnlyCleansEmptyContainer() {
        var rollback = false
        var cleaned = false
        TrashTransfer.run({}, { "moved" }, {}, { rollback = true; true }, { cleaned = true })
        assertFalse(rollback); assertFalse(cleaned)
        runCatching {
            TrashTransfer.run<String>({}, { error("move failed") }, {}, { rollback = true; true }, { cleaned = true })
        }
        assertFalse(rollback); assertTrue(cleaned)
    }
}
