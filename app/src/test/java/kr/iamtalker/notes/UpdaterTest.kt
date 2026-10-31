package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test fun versionCompare() {
        assertTrue(Updater.newer("1.4.7", "1.4.6"))
        assertTrue(Updater.newer("v1.5.0", "1.4.9"))
        assertTrue(Updater.newer("1.10.0", "1.9.9"))      // 문자열이 아니라 숫자로 비교
        assertFalse(Updater.newer("1.4.6", "1.4.6"))
        assertFalse(Updater.newer("1.4.5", "1.4.6"))
        assertTrue(Updater.newer("1.4.6.1", "1.4.6"))
        assertFalse(Updater.newer("", "1.4.6"))
    }

    @Test fun parsesLatestRelease() {
        val j = """{"tag_name":"v1.4.7","body":"고침","assets":[{"name":"other.txt","browser_download_url":"https://x/o"},
            {"name":"iamtalkernote-android.apk","browser_download_url":"https://github.com/a/b/releases/download/v1.4.7/iamtalkernote-android.apk"}]}"""
        val i = Updater.parseRelease(j)!!
        assertEquals("1.4.7", i.version)
        assertTrue(i.url.endsWith("iamtalkernote-android.apk"))
        assertEquals("고침", i.notes)
        assertNull(Updater.parseRelease("""{"tag_name":"v1.4.7","assets":[]}"""))      // 앱 파일이 없으면 무시
        assertNull(Updater.parseRelease("not json"))
    }
}
