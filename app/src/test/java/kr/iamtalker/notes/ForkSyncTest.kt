package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Test

class ForkSyncTest {
    @Test fun treeIdBecomesRealPath() {
        assertEquals("/storage/emulated/0/Documents/demo", ForkSync.treeIdToPath("primary:Documents/demo"))
        assertEquals("/storage/1234-ABCD/data/iamtalkernote", ForkSync.treeIdToPath("1234-ABCD:data/iamtalkernote"))
        assertEquals("/storage/emulated/0", ForkSync.treeIdToPath("primary:"))
        assertEquals("/storage/emulated/0/a", ForkSync.treeIdToPath("primary:a/"))
        assertNull(ForkSync.treeIdToPath("garbage"))
    }

    @Test fun folderIdsMatchTheComputerApp() {
        // 컴퓨터 앱(syncpolicy.folder_id)이 vault=abcd1234 로 계산한 값
        assertEquals("inn-abcd1234-mar-90320b91", ForkSync.folderId("abcd1234", "markdown", "markdown"))
        assertEquals("inn-abcd1234-med-bb362a46", ForkSync.folderId("abcd1234", "media", "media"))
        assertEquals("inn-abcd1234-imp-a671a412", ForkSync.folderId("abcd1234", "imported", "imported"))
        assertEquals("inn-abcd1234-his-66f79d8a", ForkSync.folderId("abcd1234", "history", "history"))
        assertEquals("inn-abcd1234-tra-6aa46034", ForkSync.folderId("abcd1234", "trash", "trash"))
        assertEquals("inn-abcd1234-not-ad67a841", ForkSync.folderId("abcd1234", "notebook", "nb:업무/회의"))
        assertEquals("inn-abcd1234-ext-b39f0a7c", ForkSync.folderId("abcd1234", "external", "ext:x1"))
    }

    @Test fun localPathsFollowKindAndLabel() {
        val v = "/storage/1234-ABCD/data/iamtalkernote"
        assertEquals(v, ForkSync.localPath(v, "inn-abcd1234-mar-90320b91", "Markdown 노트 전체"))
        assertEquals("$v/.resources", ForkSync.localPath(v, "inn-abcd1234-med-bb362a46", "미디어"))
        assertEquals("$v/.imported", ForkSync.localPath(v, "inn-abcd1234-imp-a671a412", "가져온 문서 요약"))
        assertEquals("$v/.history", ForkSync.localPath(v, "inn-abcd1234-his-66f79d8a", "변경 이력"))
        assertEquals("$v/.trash", ForkSync.localPath(v, "inn-abcd1234-tra-6aa46034", "휴지통"))
        assertEquals("$v/업무/회의", ForkSync.localPath(v, "inn-abcd1234-not-ad67a841", "노트북 · 업무/회의"))
        assertEquals("$v-files/자료", ForkSync.localPath(v, "inn-abcd1234-ext-b39f0a7c", "외부 폴더 · 자료"))
        // 노트 폴더가 앱 안이면 외부 폴더는 다른 앱이 볼 수 있게 공유 저장소에 둔다
        val app = "/data/user/0/kr.iamtalker.notes/files/iamtalkernote"
        assertEquals("/storage/emulated/0/iamtalkernote-files/Music", ForkSync.localPath(app, "inn-abcd1234-ext-b39f0a7c", "외부 폴더 · Music"))
        assertEquals("$app/.resources", ForkSync.localPath(app, "inn-abcd1234-med-bb362a46", "미디어"))
        // 설정에서 받을 곳(SD카드)을 정하면 그 안에 이름별로 만든다. 끝의 슬래시·공백은 무시
        assertEquals("/storage/1234-ABCD/동기화/PDF", ForkSync.localPath(app, "inn-abcd1234-ext-b39f0a7c", "외부 폴더 · PDF", "/storage/1234-ABCD/동기화/"))
        assertEquals("/storage/emulated/0/iamtalkernote-files/PDF", ForkSync.localPath(app, "inn-abcd1234-ext-b39f0a7c", "외부 폴더 · PDF", "  "))
        // 외부 폴더가 하나뿐이고 위치를 골랐으면 고른 폴더가 곧 받는 폴더(Data/Cloud 를 골랐는데 Data/Cloud/Cloud 가 생기지 않는다)
        assertEquals("/storage/0000-0000/Data/Cloud", ForkSync.localPath(app, "inn-abcd1234-ext-b39f0a7c", "외부 폴더 · Cloud", "/storage/0000-0000/Data/Cloud/", true))
        // 위치를 안 골랐으면(자동) 하나여도 이름별 폴더, 둘 이상이면 이름별 하위 폴더
        assertEquals("/storage/emulated/0/iamtalkernote-files/Cloud", ForkSync.localPath(app, "inn-abcd1234-ext-b39f0a7c", "외부 폴더 · Cloud", null, true))
        assertEquals("/storage/0000-0000/Data/Cloud/Cloud", ForkSync.localPath(app, "inn-abcd1234-ext-b39f0a7c", "외부 폴더 · Cloud", "/storage/0000-0000/Data/Cloud", false))
        // 노트 폴더 등 다른 종류는 이 규칙의 영향을 받지 않는다
        assertEquals("$app/.resources", ForkSync.localPath(app, "inn-abcd1234-med-bb362a46", "미디어", "/storage/x", true))
    }

    @Test fun directionsBecomePhoneFolderTypes() {
        assertEquals("sendreceive", ForkSync.typeFor("both"))
        assertEquals("receiveonly", ForkSync.typeFor("pc2mobile"))   // 컴퓨터는 보내기만 → 폰은 받기만
        assertEquals("sendonly", ForkSync.typeFor("mobile2pc"))
        assertNull(ForkSync.typeFor("off"))
    }

    @Test fun ignoreLinesMatchTheComputerPlan() {
        val common = listOf("(?d)Thumbs.db", "(?d)desktop.ini", "(?d).DS_Store", "~*.tmp")
        // 컴퓨터 앱(syncpolicy.plan, 새 방식)이 만든 제외 목록과 같다: 기본(미디어·가져온 요약 보냄, 이력·휴지통 안 보냄)
        val def = JSONObject("""{"direction":"both","include":{"media":true,"history":false,"trash":false}}""")
        assertEquals(listOf("/.history", "/.trash", "/.snapshots", "/.imported") + common, ForkSync.ignoreLines(def))   // .imported(예전 가져온 문서 요약)는 늘 뺀다
        // 미디어를 빼고 이력을 켠 경우
        val other = JSONObject("""{"direction":"pc2mobile","include":{"media":false,"history":true,"trash":false}}""")
        assertEquals(listOf("/.resources", "/.trash", "/.snapshots", "/.imported") + common, ForkSync.ignoreLines(other))
        // include 가 비어 있으면 모두 안 보냄으로 본다
        assertEquals(listOf("/.resources", "/.history", "/.trash", "/.snapshots", "/.imported") + common, ForkSync.ignoreLines(JSONObject("""{"direction":"both"}""")))
    }

    @Test fun staleFoldersAreOnlyThisVaultsOldKinds() {
        val ids = listOf(
            "inn-abcd1234-mar-90320b91", "inn-abcd1234-med-bb362a46", "inn-abcd1234-imp-a671a412", "inn-abcd1234-his-66f79d8a",
            "inn-abcd1234-tra-6aa46034", "inn-abcd1234-not-ad67a841", "inn-abcd1234-ext-b39f0a7c",
            "inn-ffff0000-med-bb362a46", "default", "photos-sync")
        assertEquals(
            listOf("inn-abcd1234-med-bb362a46", "inn-abcd1234-imp-a671a412", "inn-abcd1234-his-66f79d8a", "inn-abcd1234-tra-6aa46034", "inn-abcd1234-not-ad67a841"),
            ForkSync.staleFolders(ids, "abcd1234"))   // 노트 폴더·외부 폴더·다른 볼트·다른 앱의 폴더는 남긴다
    }

    @Test fun removeOldImportedOnlyWhenAppMadeFilesRemain() {
        val v = java.io.File.createTempFile("vault", "").also { it.delete(); it.mkdirs() }
        assertFalse(ForkSync.removeOldImported(v))                                              // 폴더가 없으면 아무 일도 없다
        val d = java.io.File(v, ".imported").apply { mkdirs() }
        java.io.File(d, "docs.jsonl").writeText("{}"); java.io.File(d, "meta.json").writeText("{}"); java.io.File(d, ".stfolder.removed-1").mkdirs()
        assertTrue(ForkSync.removeOldImported(v) && !d.exists())                                // 앱이 만든 파일만 있으면 지운다
        d.mkdirs(); java.io.File(d, "docs.jsonl").writeText("{}"); java.io.File(d, "내 파일.txt").writeText("보관")
        assertFalse(ForkSync.removeOldImported(v)); assertTrue(java.io.File(d, "내 파일.txt").exists())   // 다른 파일이 섞여 있으면 그대로
    }
}
