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

class SyncNoticeCompleteTest {
    private fun line(state: String = "idle", need: Long = 0) = ForkSync.FolderLine("iamtalkernote", state, 0, 0, needFiles = need, totalFiles = 10)
    private fun rep(connected: Boolean = true, error: String? = null, vararg f: ForkSync.FolderLine) = ForkSync.Report(connected = connected, folders = f.toList(), error = error)

    @Test fun completeOnlyWhenEverythingIsReceived() {
        assertTrue(SyncNotice.complete(rep(true, null, line(), line("idle")), 10, 10))
        assertTrue(SyncNotice.complete(rep(true, null, line(), line("paused")), 10, 10))   // 멈춰 둔 폴더는 받을 것이 없으면 끝난 것으로
        assertFalse(SyncNotice.complete(null, 10, 10))
        assertFalse(SyncNotice.complete(rep(false, null, line()), 10, 10))               // 컴퓨터와 연결이 끊김
        assertFalse(SyncNotice.complete(rep(true, "오류", line()), 10, 10))
        assertFalse(SyncNotice.complete(rep(true, null, line()), 9, 10))                 // 노트 전체를 다 못 받음
        assertFalse(SyncNotice.complete(rep(true, null, line()), 0, 0))                  // 개수를 아직 모름
        assertFalse(SyncNotice.complete(rep(true, null, line(), line(need = 3)), 10, 10)) // 외부 폴더에 받을 파일이 남음
        assertFalse(SyncNotice.complete(rep(true, null, line("syncing")), 10, 10))
    }
}

class SyncSettingsExtTest {
    private val pol = JSONObject("""{"vault":"ab12","modules":{"markdown":{"kind":"markdown","direction":"both"},
        "ext:aaa":{"kind":"external","label":"외부 폴더 · 음악","direction":"pc2mobile","updated":100},
        "ext:bbb":{"kind":"external","label":"외부 폴더 · 문서","direction":"both"}},
        "simple":{"direction":"both","updated":50,"include":{"media":false,"imported":false,"history":false,"trash":false}}}""")
    private val ida = ForkSync.folderId("ab12", "external", "ext:aaa")
    private val idb = ForkSync.folderId("ab12", "external", "ext:bbb")

    private fun tmp(): String = File.createTempFile("vault", "").also { it.delete(); it.mkdirs() }.absolutePath

    @Test fun policyListsExternalFolders() {
        assertEquals(mapOf(ida to "ext:aaa", idb to "ext:bbb"), SyncSettings.extModules(pol))
        val s = SyncSettings.fromPolicy(pol)!!
        assertEquals(SyncSettings.ExtDir("pc2mobile", 100L), s.ext[ida])
        assertEquals(SyncSettings.ExtDir("both", 0L), s.ext[idb])
    }

    @Test fun jsonRoundTripKeepsExt() {
        val s = SyncSettings.fromPolicy(pol)!!
        assertEquals(s, SyncSettings.parse(SyncSettings.toJson(s)))
        val old = SyncSettings.parse("""{"updated":1,"direction":"both","include":{}}""")!!   // ext 가 없는 옛 파일
        assertTrue(old.ext.isEmpty())
    }

    @Test fun eachFolderNewestWins() {
        val p = SyncSettings.fromPolicy(pol)!!
        val file = p.copy(updated = 10, direction = "off", ext = mapOf(ida to SyncSettings.ExtDir("mobile2pc", 200L), idb to SyncSettings.ExtDir("off", 0L)))
        val e = SyncSettings.effective(p, file)
        assertEquals("both", e.direction)                      // 노트 폴더는 정책이 더 최근(50 > 10)
        assertEquals("mobile2pc", e.ext[ida]!!.direction)      // 음악은 파일이 더 최근
        assertEquals("both", e.ext[idb]!!.direction)           // 문서는 같은 시각이면 정책
    }

    @Test fun overlayAppliesExtDirections() {
        val s = SyncSettings.fromPolicy(pol)!!.copy(ext = mapOf(ida to SyncSettings.ExtDir("mobile2pc", 300L), idb to SyncSettings.ExtDir("both", 0L)))
        val o = SyncSettings.overlay(pol, s)
        assertEquals("mobile2pc", o.getJSONObject("modules").getJSONObject("ext:aaa").getString("direction"))
        assertEquals(300L, o.getJSONObject("modules").getJSONObject("ext:aaa").getLong("updated"))
        assertEquals("pc2mobile", pol.getJSONObject("modules").getJSONObject("ext:aaa").getString("direction"))   // 원본은 그대로
    }

    @Test fun changeExtWritesOnlyThatFolder() {
        val v = tmp()
        assertFalse(SyncSettings.changeExt(v, pol, ida, "pc2mobile", now = 500))      // 같은 값
        assertFalse(SyncSettings.changeExt(v, pol, "inn-ab12-ext-ffffffff", "off", now = 500))   // 모르는 폴더
        assertFalse(SyncSettings.changeExt(v, pol, ida, "zzz", now = 500))
        assertNull(SyncSettings.read(v))
        assertTrue(SyncSettings.changeExt(v, pol, ida, "mobile2pc", now = 500))
        val r = SyncSettings.read(v)!!
        assertEquals(SyncSettings.ExtDir("mobile2pc", 500L), r.ext[ida])
        assertEquals(50L, r.updated)                           // 노트 폴더 설정의 시각은 그대로
        assertEquals("both", r.direction)
        assertEquals("both", r.ext[idb]!!.direction)
        assertTrue(SyncSettings.changeExt(v, pol, idb, "off", now = 600))
        val r2 = SyncSettings.read(v)!!
        assertEquals("mobile2pc", r2.ext[ida]!!.direction)     // 앞서 바꾼 것이 유지됨
        assertEquals(SyncSettings.ExtDir("off", 600L), r2.ext[idb])
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
