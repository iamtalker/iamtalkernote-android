package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflictsTest {
    @Test fun detectsSyncthingConflicts() {
        assertTrue(Conflicts.isConflict("회의록.sync-conflict-20240101-123456-ABCDEFG.md"))
        assertTrue(Conflicts.isConflict("a.b.sync-conflict-20240101-123456.md"))
        assertFalse(Conflicts.isConflict("회의록.md"))
        assertFalse(Conflicts.isConflict("sync-conflict.md"))
    }

    @Test fun originalName() {
        assertEquals("회의록.md", Conflicts.originalName("회의록.sync-conflict-20240101-123456-ABCDEFG.md"))
        assertEquals("a.b.md", Conflicts.originalName("a.b.sync-conflict-20240101-123456-XY12.md"))
        assertEquals(null, Conflicts.originalName("회의록.md"))
        assertEquals("20240101-123456", Conflicts.stamp("x.sync-conflict-20240101-123456-AB.md"))
    }
}
