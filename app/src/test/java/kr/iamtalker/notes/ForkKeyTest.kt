package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class ForkKeyTest {
    private val key = "ymW267zYawVQE4sFwxYsVMEyKeRTevcL"

    @Test fun picksThe32CharKeyOutOfPastedText() {
        assertEquals(key, ForkSync.keyFrom(key))
        assertEquals(key, ForkSync.keyFrom("  $key \n"))
        assertEquals(key, ForkSync.keyFrom("API Key: $key (복사됨)"))
    }

    @Test fun keepsOtherTextAsIsSoTheLengthWarningShows() {
        assertEquals("짧은키", ForkSync.keyFrom(" 짧은키 "))
        assertEquals("abc123", ForkSync.keyFrom("abc123"))
    }
}
