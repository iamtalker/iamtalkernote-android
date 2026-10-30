package kr.iamtalker.notes

import org.junit.Assert.assertEquals
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
        val def = JSONObject("""{"direction":"both","include":{"media":true,"imported":true,"history":false,"trash":false}}""")
        assertEquals(listOf("/.history", "/.trash", "/.snapshots") + common, ForkSync.ignoreLines(def))
        // 미디어를 빼고 이력을 켠 경우
        val other = JSONObject("""{"direction":"pc2mobile","include":{"media":false,"imported":true,"history":true,"trash":false}}""")
        assertEquals(listOf("/.resources", "/.trash", "/.snapshots") + common, ForkSync.ignoreLines(other))
        // include 가 비어 있으면 모두 안 보냄으로 본다
        assertEquals(listOf("/.resources", "/.imported", "/.history", "/.trash", "/.snapshots") + common, ForkSync.ignoreLines(JSONObject("""{"direction":"both"}""")))
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
}
