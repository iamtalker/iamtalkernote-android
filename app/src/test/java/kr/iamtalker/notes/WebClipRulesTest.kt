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

    @Test fun parsesOnlyAllowlistedTablesWithCommentsSingleQuotesAndTrailingCommas() {
        val source = """
            const SITE_CONTENT_SELECTORS = { // c
              'example.com': '.post',
            };
            const SITE_CLEANUP_SELECTORS = { 'example.com': ['.ad',], };
            const SITE_LABEL_FROM_TITLE_SELECTORS = { 'example.com': ['.tag',], };
            const SITE_IFRAME_SELECTORS = { 'example.com': 'iframe.article', };
            const MOBILE_HOST_ALIAS = { 'm.example.com': 'www.example.com', };
            const EXECUTABLE = 'must not be extracted';
        """.trimIndent()
        val parsed = org.json.JSONObject(WebClip.parseUpstreamRules(source))
        assertEquals(".post", parsed.getJSONObject("content").getString("example.com"))
        assertEquals(".ad", parsed.getJSONObject("cleanup").getJSONArray("example.com").getString(0))
        assertEquals("www.example.com", parsed.getJSONObject("alias").getString("m.example.com"))
        assertEquals(6, parsed.length())
    }

    @Test fun rejectsExecutableSyntaxAndInvalidSchema() {
        val bad = "const SITE_CONTENT_SELECTORS = getRules();"
        org.junit.Assert.assertThrows(RuntimeException::class.java) { WebClip.parseUpstreamRules(bad) }
    }

    @Test fun comparesRuleTablesIgnoringDateAndObjectKeyOrder() {
        val a = """{"updated":"2026-01-01","content":{"a":"x","b":"y"},"cleanup":{},"label":{},"iframe":{},"alias":{}}"""
        val b = """{"updated":"2026-12-01","alias":{},"iframe":{},"label":{},"cleanup":{},"content":{"b":"y","a":"x"}}"""
        org.junit.Assert.assertTrue(WebClip.sameRuleTables(a, b))
        val different = b.replace("\"y\"", "\"z\"")
        org.junit.Assert.assertFalse(WebClip.sameRuleTables(a, different))
    }

    @Test fun manualRulesWinSameDateButNewerRegularRulesWin() {
        val manual = """{"updated":"2026-01-01"}"""
        val sameDate = """{"updated":"2026-01-01","regular":true}"""
        val newer = """{"updated":"2026-01-02"}"""
        assertEquals(manual, WebClip.pickManualRules(manual, sameDate))
        assertEquals(newer, WebClip.pickManualRules(manual, newer))
    }
}
