package kr.iamtalker.notes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 컴퓨터·폰의 동기화 규칙이 같아야 동기화하고 다르면 멈추는 규칙(SyncSettings) */
class SyncSettingsTest {
    private val pol = JSONObject("""{"vault":"ab12","modules":{"markdown":{"kind":"markdown","direction":"pc2mobile"},
        "media":{"kind":"media","direction":"pc2mobile"},"imported":{"kind":"imported","direction":"pc2mobile"},
        "history":{"kind":"history","direction":"off"},"trash":{"kind":"trash","direction":"off"},
        "ext:aaa":{"kind":"external","label":"외부 폴더 · 음악","direction":"both"}},
        "simple":{"direction":"pc2mobile","include":{"media":true,"imported":true,"history":false,"trash":false}}}""")
    private val ida = ForkSync.folderId("ab12", "external", "ext:aaa")

    private fun tmp(): String = File.createTempFile("vault", "").also { it.delete(); it.mkdirs() }.absolutePath

    @Test fun rulesFromPolicy() {
        val r = SyncSettings.fromPolicy(pol)!!
        assertEquals("pc2mobile", r.direction)
        assertEquals(mapOf("media" to true, "imported" to true, "history" to false, "trash" to false), r.include)
        assertEquals(mapOf(ida to "both"), r.ext)
        assertEquals(mapOf(ida to "ext:aaa"), SyncSettings.extModules(pol))
    }

    @Test fun pcRulesFileWinsOverPolicyAndFallsBack() {
        val v = tmp()
        assertEquals(SyncSettings.fromPolicy(pol), SyncSettings.pcRules(v, pol))   // 설정 폴더에 파일이 없으면(옛 컴퓨터 앱) 정책에서
        val d = SyncSettings.dir(v).apply { mkdirs() }
        File(d, SyncSettings.PC_FILE).writeText("""{"notes":{"direction":"both","include":{"media":false,"imported":true,"history":false,"trash":false}},"ext":{"$ida":"off"}}""")
        val r = SyncSettings.pcRules(v, pol)!!
        assertEquals("both", r.direction)
        assertEquals(false, r.include["media"])
        assertEquals("off", r.ext[ida])
        File(d, SyncSettings.PC_FILE).writeText("{oops")
        assertEquals(SyncSettings.fromPolicy(pol), SyncSettings.pcRules(v, pol))    // 깨진 파일은 없는 것처럼
    }

    @Test fun noOverridesFollowsTheComputer() {
        val pc = SyncSettings.fromPolicy(pol)!!
        val eff = SyncSettings.effective(pc, SyncSettings.Over())
        assertEquals(pc, eff)
        assertTrue(SyncSettings.mismatched(pc, eff).isEmpty())
    }

    @Test fun differentRulesStopOnlyThatFolder() {
        val pc = SyncSettings.fromPolicy(pol)!!
        // 노트 폴더 방향이 다르면 노트 폴더만
        var eff = SyncSettings.effective(pc, SyncSettings.Over(direction = "both"))
        assertEquals(setOf(SyncSettings.NOTES), SyncSettings.mismatched(pc, eff))
        // 함께 보낼 것이 다르면 노트 폴더
        eff = SyncSettings.effective(pc, SyncSettings.Over(include = mapOf("media" to false)))
        assertEquals(setOf(SyncSettings.NOTES), SyncSettings.mismatched(pc, eff))
        // 외부 폴더 방향이 다르면 그 폴더만
        eff = SyncSettings.effective(pc, SyncSettings.Over(ext = mapOf(ida to "off")))
        assertEquals(setOf(ida), SyncSettings.mismatched(pc, eff))
        // 폰이 적은 값이 컴퓨터와 같으면 멈추지 않는다
        eff = SyncSettings.effective(pc, SyncSettings.Over(direction = "pc2mobile", include = mapOf("media" to true), ext = mapOf(ida to "both")))
        assertTrue(SyncSettings.mismatched(pc, eff).isEmpty())
        // 폴더 ID 로 바꾸기
        assertEquals(setOf(ForkSync.folderId("ab12", "markdown", "markdown"), ida), SyncSettings.blockedFolderIds("ab12", setOf(SyncSettings.NOTES, ida)))
    }

    @Test fun overlayAppliesThePhonesRules() {
        val pc = SyncSettings.fromPolicy(pol)!!
        val eff = SyncSettings.effective(pc, SyncSettings.Over(direction = "both", include = mapOf("media" to false, "history" to true), ext = mapOf(ida to "mobile2pc")))
        val o = SyncSettings.overlay(pol, eff)
        assertEquals("both", o.getJSONObject("simple").getString("direction"))
        assertEquals("both", o.getJSONObject("modules").getJSONObject("markdown").getString("direction"))
        assertEquals("off", o.getJSONObject("modules").getJSONObject("media").getString("direction"))
        assertEquals("both", o.getJSONObject("modules").getJSONObject("history").getString("direction"))
        assertEquals("mobile2pc", o.getJSONObject("modules").getJSONObject("ext:aaa").getString("direction"))
        assertEquals("pc2mobile", pol.getJSONObject("simple").getString("direction"))   // 원본은 그대로
        val ig = ForkSync.ignoreLines(o.getJSONObject("simple"))
        assertTrue("/.resources" in ig && "/.history" !in ig)   // 가림 목록도 폰의 규칙을 따른다
    }

    @Test fun changeWritesOnlyTheDifferencesFromTheComputer() {
        val v = tmp()
        val pc = SyncSettings.fromPolicy(pol)!!
        assertFalse(SyncSettings.change(v, pc, direction = "pc2mobile"))              // 컴퓨터와 같은 값: 적을 것이 없다
        assertEquals(SyncSettings.Over(), SyncSettings.overrides(v))
        assertTrue(SyncSettings.change(v, pc, direction = "both"))
        assertEquals("both", SyncSettings.overrides(v).direction)
        assertTrue(SyncSettings.change(v, pc, key = "history", on = true))
        assertEquals(mapOf("history" to true), SyncSettings.overrides(v).include)
        assertEquals("both", SyncSettings.overrides(v).direction)                       // 앞서 정한 방향이 유지됨
        assertFalse(SyncSettings.change(v, pc, key = "media", on = true))              // 컴퓨터도 켜져 있다
        assertTrue(SyncSettings.change(v, pc, key = "history", on = false))            // 컴퓨터와 같아지면 지운다
        assertTrue(SyncSettings.overrides(v).include.isEmpty())
        assertTrue(SyncSettings.change(v, pc, direction = "pc2mobile"))                // 컴퓨터와 같아지면 지운다
        assertEquals(SyncSettings.Over(), SyncSettings.overrides(v))
    }

    @Test fun changeExtOnlyForKnownFolders() {
        val v = tmp()
        val pc = SyncSettings.fromPolicy(pol)!!
        assertFalse(SyncSettings.changeExt(v, pc, ida, "both"))                          // 같은 값
        assertFalse(SyncSettings.changeExt(v, pc, "inn-ab12-ext-ffffffff", "off"))       // 모르는 폴더
        assertFalse(SyncSettings.changeExt(v, pc, ida, "zzz"))
        assertTrue(SyncSettings.changeExt(v, pc, ida, "off"))
        assertEquals(mapOf(ida to "off"), SyncSettings.overrides(v).ext)
        assertTrue(SyncSettings.changeExt(v, pc, ida, "both"))                           // 컴퓨터와 같아지면 지운다
        assertTrue(SyncSettings.overrides(v).ext.isEmpty())
    }

    @Test fun overridesFileRoundTrip() {
        val o = SyncSettings.Over("both", mapOf("media" to false), mapOf(ida to "off"))
        assertEquals(o, SyncSettings.parseOver(SyncSettings.toJson(o)))
        assertEquals(SyncSettings.Over(), SyncSettings.parseOver("{oops"))
        assertEquals(SyncSettings.Over(), SyncSettings.parseOver("""{"notes":{"direction":"zzz"},"ext":{"$ida":"zzz"}}"""))
    }
}

class GoneExtTest {
    @Test fun onlyThisVaultsExternalFoldersMissingFromPolicy() {
        val a = ForkSync.folderId("ab12", "external", "ext:aaa")
        val b = ForkSync.folderId("ab12", "external", "ext:bbb")
        val ids = listOf(a, b, ForkSync.folderId("ab12", "markdown", "markdown"), ForkSync.folderId("zz99", "external", "ext:ccc"), "other-app-folder")
        assertEquals(listOf(b), ForkSync.goneExt(ids, "ab12", setOf(a)))          // 정책에 있는 a·노트 폴더·다른 볼트·다른 앱은 건드리지 않는다
        assertEquals(listOf(a, b), ForkSync.goneExt(ids, "ab12", emptySet()))
        assertTrue(ForkSync.goneExt(ids, "ab12", setOf(a, b)).isEmpty())
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
        assertTrue(SyncNotice.complete(rep(true, null, line("syncing")), 10, 10))            // 받을 것이 0이면 상태가 syncing·scanning 이어도 끝
        assertTrue(SyncNotice.complete(rep(true, null, line("scanning")), 10, 10))
        assertFalse(SyncNotice.complete(rep(true, null, line("syncing", need = 2)), 10, 10))  // 받을 파일이 남았으면 끝이 아님
        assertFalse(SyncNotice.complete(rep(true, null, line("error")), 10, 10))
    }

    @Test fun foldersTheComputerNoLongerSharesDoNotBlockCompletion() {
        val stale = ForkSync.FolderLine("외부 폴더 · 이멋세", "idle", 0, 0, needFiles = 16, totalFiles = 17, id = "inn-ab12-ext-1")
        val withGone = ForkSync.Report(connected = true, folders = listOf(line(), stale), gone = listOf("inn-ab12-ext-1" to "이멋세"))
        assertTrue(SyncNotice.complete(withGone, 10, 10))                                   // 안 받아지는 폴더는 빼고 판정
        assertFalse(SyncNotice.complete(withGone.copy(gone = emptyList()), 10, 10))          // 공유 중인 폴더에 받을 것이 남았으면 끝이 아님
    }

    @Test fun foldersStoppedByDifferentRulesMeanNotDone() {
        val r = ForkSync.Report(connected = true, folders = listOf(line()), blocked = setOf("inn-ab12-mar-1"))
        assertFalse(SyncNotice.complete(r, 10, 10))                                          // 규칙이 달라 멈춘 폴더가 있으면 끝난 것이 아니다
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
