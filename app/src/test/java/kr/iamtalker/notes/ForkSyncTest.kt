package kr.iamtalker.notes

import org.junit.Assert.assertEquals
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
}
