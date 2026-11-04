package kr.iamtalker.notes

import org.junit.Assert.*
import org.junit.Test

class SyncPowerTest {
    private fun report(bytes: Long = 100, failed: Int = 0, up: Long = 0) = ForkSync.Report(
        connected = true, folders = listOf(ForkSync.FolderLine("notes", "syncing", bytes, 100,
            failed = failed, id = "vault", upNeed = up)))

    @Test fun releasesOnStallAndResumesOnProgress() {
        val p = SyncPower()
        assertTrue(p.active(report(), 0))
        assertFalse(p.active(report(), 180_000))
        assertTrue(p.active(report(90), 185_000))
        assertFalse(p.active(report(0), 190_000))
    }

    @Test fun releasesOnErrorsDisconnectAndBlockedFolders() {
        val p = SyncPower()
        assertTrue(p.active(report(), 0))
        assertFalse(p.active(report().copy(error = "timeout"), 5_000))
        assertFalse(p.active(report().copy(connected = false), 10_000))
        assertFalse(p.active(report(failed = 1), 15_000))
        assertFalse(p.active(report().copy(blocked = setOf("vault")), 20_000))
        assertFalse(p.active(report().copy(gone = listOf("vault" to "notes")), 25_000))
    }

    @Test fun uploadsAndPolling() {
        val p = SyncPower()
        assertTrue(p.active(report(0, up = 1), 0))
        assertEquals(5_000L, p.pollMs(true, false))
        assertEquals(5_000L, p.pollMs(false, true))
        assertEquals(30_000L, p.pollMs(false, false))
    }
}
