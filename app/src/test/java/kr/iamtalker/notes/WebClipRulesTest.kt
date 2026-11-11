package kr.iamtalker.notes

import org.junit.Assert.assertEquals
import org.junit.Test

class WebClipRulesTest {
    private val bundled = """{"updated": "2026-11-11", "alias": {"m.ruliweb.com": "bbs.ruliweb.com"}}"""
    private val oldCache = """{"alias": {"m.clien.net": "www.clien.net"}}"""             // 날짜표 없는 옛 사본
    private val sameCache = """{"updated": "2026-11-11", "alias": {}}"""
    private val newerCache = """{"updated": "2026-11-20", "alias": {}}"""
    private val olderCache = """{"updated": "2026-11-01", "alias": {}}"""

    @Test fun noCacheUsesBundled() {
        assertEquals(bundled, WebClip.pickRules(null, bundled))
    }

    @Test fun oldCacheWithoutStampDoesNotHideBundledRules() {
        assertEquals(bundled, WebClip.pickRules(oldCache, bundled))
    }

    @Test fun olderDatedCacheDoesNotHideBundledRules() {
        assertEquals(bundled, WebClip.pickRules(olderCache, bundled))
    }

    @Test fun sameOrNewerCacheIsUsed() {
        assertEquals(sameCache, WebClip.pickRules(sameCache, bundled))
        assertEquals(newerCache, WebClip.pickRules(newerCache, bundled))
    }

    @Test fun stampReadsDateOrEmpty() {
        assertEquals("2026-11-11", WebClip.rulesStamp(bundled))
        assertEquals("", WebClip.rulesStamp(oldCache))
    }
}
