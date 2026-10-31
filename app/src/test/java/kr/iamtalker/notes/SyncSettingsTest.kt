package kr.iamtalker.notes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SyncSettingsTest {
    private val pol = JSONObject("""{"vault":"ab12","modules":{"markdown":{"kind":"markdown","direction":"pc2mobile"},
        "media":{"kind":"media","direction":"pc2mobile"},"imported":{"kind":"imported","direction":"pc2mobile"},
        "history":{"kind":"history","direction":"off"},"trash":{"kind":"trash","direction":"off"}},
        "simple":{"direction":"pc2mobile","updated":100,"include":{"media":true,"imported":true,"history":false,"trash":false}}}""")

    private fun tmp(): String = File.createTempFile("vault", "").also { it.delete(); it.mkdirs() }.absolutePath

    @Test fun jsonRoundTrip() {
        val s = SyncSettings.fromPolicy(pol)!!
        assertEquals(s, SyncSettings.parse(SyncSettings.toJson(s)))
        assertNull(SyncSettings.parse("{oops"))
        assertNull(SyncSettings.parse("""{"updated":1,"direction":"zzz","include":{}}"""))
    }

    @Test fun newerWins() {
        val p = SyncSettings.fromPolicy(pol)!!
        assertEquals(p, SyncSettings.effective(p, p.copy(updated = 50, direction = "both")))
        assertEquals("both", SyncSettings.effective(p, p.copy(updated = 200, direction = "both")).direction)
        assertEquals(p, SyncSettings.effective(p, p.copy(direction = "both")))   // 같은 시각이면 정책
    }

    @Test fun overlayChangesModulesToo() {
        val s = SyncSettings.fromPolicy(pol)!!.copy(updated = 300, direction = "both", include = mapOf("media" to false, "imported" to true, "history" to true, "trash" to false))
        val o = SyncSettings.overlay(pol, s)
        assertEquals("both", o.getJSONObject("simple").getString("direction"))
        assertEquals("both", o.getJSONObject("modules").getJSONObject("markdown").getString("direction"))
        assertEquals("off", o.getJSONObject("modules").getJSONObject("media").getString("direction"))
        assertEquals("both", o.getJSONObject("modules").getJSONObject("history").getString("direction"))
        assertEquals("pc2mobile", pol.getJSONObject("simple").getString("direction"))   // 원본은 그대로
        val ig = ForkSync.ignoreLines(o.getJSONObject("simple"))
        assertTrue("/.resources" in ig && "/.history" !in ig)   // 가림 목록도 바뀐 설정을 따른다
    }

    @Test fun changeWritesOnlyWhenDifferent() {
        val v = tmp()
        assertFalse(SyncSettings.change(v, pol, direction = "pc2mobile", now = 500))
        assertNull(SyncSettings.read(v))
        assertTrue(SyncSettings.change(v, pol, direction = "both", now = 500))
        assertEquals("both", SyncSettings.read(v)!!.direction)
        assertEquals(500L, SyncSettings.read(v)!!.updated)
        assertTrue(SyncSettings.change(v, pol, key = "history", on = true, now = 600))
        val r = SyncSettings.read(v)!!
        assertEquals("both", r.direction)           // 앞서 바꾼 방향이 유지됨
        assertTrue(r.include["history"] == true && r.include["media"] == true)
        assertEquals(600L, r.updated)
    }
}

class PhoneProgressTest {
    @org.junit.Test fun writesOnlyWhenSettingsFolderExists() {
        val v = java.io.File.createTempFile("vault", "").also { it.delete(); it.mkdirs() }.absolutePath
        val o = Overall(1, 2, 3, 4)
        org.junit.Assert.assertFalse(PhoneProgress.write(v, o, 5))
        SyncSettings.dir(v).mkdirs()
        org.junit.Assert.assertTrue(PhoneProgress.write(v, o, 5))
        val j = org.json.JSONObject(java.io.File(SyncSettings.dir(v), PhoneProgress.FILE).readText())
        org.junit.Assert.assertEquals(5L, j.getLong("updated")); org.junit.Assert.assertEquals(3, j.getInt("mediaHave")); org.junit.Assert.assertEquals(2, j.getInt("notesAll"))
    }
}
