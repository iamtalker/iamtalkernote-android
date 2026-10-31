package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FavoritesTest {
    private fun vault(): String = File.createTempFile("vault", "").also { it.delete(); it.mkdirs() }.absolutePath

    @Test fun mergeNewerWins() {
        val a = mapOf("x.md" to (true to 10L), "y.md" to (true to 5L))
        val b = mapOf("x.md" to (false to 20L), "z.md" to (true to 1L))
        assertEquals(mapOf("x.md" to false, "y.md" to true, "z.md" to true), Favorites.merge(a, b))
        assertEquals(mapOf("x.md" to true), Favorites.merge(mapOf("x.md" to (true to 30L)), mapOf("x.md" to (false to 20L))))
    }

    @Test fun writesOwnFileAndReadsPcFile() {
        val v = vault()
        assertTrue(Favorites.load(v).isEmpty())
        Favorites.set(v, "폴더/가나다.md", true, now = 100)
        assertEquals(mapOf("폴더/가나다.md" to true), Favorites.load(v))
        assertTrue(File(SyncSettings.dir(v), Favorites.OWN).exists())
        assertFalse(File(SyncSettings.dir(v), Favorites.OTHER).exists())            // 컴퓨터 파일은 건드리지 않는다
        File(SyncSettings.dir(v), Favorites.OTHER).writeText("""{"items":{"폴더/가나다.md":[0,200],"b.md":[1,150]}}""")
        assertEquals(mapOf("폴더/가나다.md" to false, "b.md" to true), Favorites.load(v))   // 컴퓨터가 더 나중에 끔
        Favorites.set(v, "폴더/가나다.md", true, now = 300)
        assertEquals(true, Favorites.load(v)["폴더/가나다.md"])                                 // 내가 더 나중에 켬
        Favorites.set(v, "c.md", true, now = 400)
        assertEquals(setOf("폴더/가나다.md", "c.md"), File(SyncSettings.dir(v), Favorites.OWN).readText().let { org.json.JSONObject(it).getJSONObject("items").keys().asSequence().toSet() })   // 앞서 적은 것도 유지
    }

    @Test fun brokenFilesAreIgnored() {
        val v = vault()
        SyncSettings.dir(v).mkdirs()
        File(SyncSettings.dir(v), Favorites.OTHER).writeText("{깨짐")
        Favorites.set(v, "a.md", true, now = 1)
        assertEquals(mapOf("a.md" to true), Favorites.load(v))
    }

    @Test fun relPath() {
        assertEquals("ScrapBook/하위/한글 제목.md", Favorites.relOf("ScrapBook/하위", "한글 제목.md"))
        assertEquals("a.md", Favorites.relOf("", "a.md"))
    }
}
