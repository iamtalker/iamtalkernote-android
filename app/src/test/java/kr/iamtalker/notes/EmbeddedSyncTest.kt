package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddedSyncTest {
    private val id = "ABCDEFG-HIJKLMN-OPQRSTU-VWXYZ23-4567ABC-DEFGHIJ-KLMNOPQ-RSTUVWX"

    @Test fun deviceIdIsFoundInQrText() {
        assertEquals(id, EmbeddedSync.parseDeviceId(id))
        assertEquals(id, EmbeddedSync.parseDeviceId("  " + id.lowercase() + "\n"))          // 소문자·공백도 허용
        assertEquals(id, EmbeddedSync.parseDeviceId("syncthing://$id?name=pc"))              // 앞뒤에 다른 글이 붙어도
        assertNull(EmbeddedSync.parseDeviceId("https://example.com/hello"))
        assertNull(EmbeddedSync.parseDeviceId(id.substring(0, 40)))                           // 모자란 ID
    }

    @Test fun generatedKeyLooksLikeSyncthingKey() {
        val k = EmbeddedSync.newKey()
        assertEquals(32, k.length)
        assertEquals(k, ForkSync.keyFrom(k))   // 영문·숫자 32자 → 마법사 쪽 키 검사에도 통과
        assertTrue(EmbeddedSync.newKey() != k)
    }

    @Test fun commandListensOnlyOnLoopback() {
        val c = EmbeddedSync.command("/bin/st", "/home/x", "K")
        assertEquals("/bin/st", c[0])
        assertTrue("--gui-address=http://127.0.0.1:${EmbeddedSync.PORT}" in c)
        assertTrue("--gui-apikey=K" in c)
        assertTrue("--home=/home/x" in c)
    }
}
