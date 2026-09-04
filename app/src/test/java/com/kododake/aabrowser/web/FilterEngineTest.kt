package com.kododake.aabrowser.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterEngineTest {
    @Test
    fun `host rules include subdomains but not lookalikes`() {
        val engine = engine("||ads.example.com^")
        assertTrue(engine.blocks("https://cdn.ads.example.com/banner.js"))
        assertFalse(engine.blocks("https://ads.example.com.evil.test/banner.js"))
    }

    @Test
    fun `legacy domain lists remain supported`() {
        val engine = engine("tracker.test")
        assertTrue(engine.blocks("https://pixel.tracker.test/p.gif"))
        assertFalse(engine.blocks("https://example.test/?next=tracker.test"))
    }

    @Test
    fun `exception overrides blocking rule`() {
        val engine = engine("||ads.test^", "@@||ads.test/required.js")
        assertTrue(engine.blocks("https://ads.test/banner.js"))
        assertFalse(engine.blocks("https://ads.test/required.js"))
    }

    @Test
    fun `third party and resource options are applied`() {
        val engine = engine("||cdn.test^${'$'}third-party,script")
        assertTrue(engine.blocks("https://cdn.test/ad.js", "https://site.test", FilterEngine.ResourceType.SCRIPT))
        assertFalse(engine.blocks("https://cdn.test/ad.png", "https://site.test", FilterEngine.ResourceType.IMAGE))
        assertFalse(engine.blocks("https://static.cdn.test/app.js", "https://www.cdn.test", FilterEngine.ResourceType.SCRIPT))
    }

    @Test
    fun `domain options include and exclude page hosts`() {
        val engine = engine("/advert.js${'$'}domain=news.test|~paid.news.test")
        assertTrue(engine.blocks("https://cdn.test/advert.js", "https://news.test"))
        assertFalse(engine.blocks("https://cdn.test/advert.js", "https://paid.news.test"))
        assertFalse(engine.blocks("https://cdn.test/advert.js", "https://other.test"))
    }

    @Test
    fun `wildcards separators and anchors match urls`() {
        val engine = engine("|https://ads.test/*/banner^")
        assertTrue(engine.blocks("https://ads.test/123/banner?size=2"))
        assertFalse(engine.blocks("http://ads.test/123/banner?size=2"))
    }

    @Test
    fun `cosmetic rules respect domains and exceptions`() {
        val engine = engine(
            "##.generic-ad",
            "news.test##.sponsor",
            "news.test#@#.generic-ad",
            "~shop.news.test,news.test##.newsletter"
        )
        val newsCss = engine.cosmeticCss("https://news.test/article")
        assertTrue(".sponsor" in newsCss)
        assertTrue(".newsletter" in newsCss)
        assertFalse(".generic-ad" in newsCss)

        val shopCss = engine.cosmeticCss("https://shop.news.test")
        assertFalse(".newsletter" in shopCss)
    }

    @Test
    fun `hosts file entries are accepted`() {
        val engine = engine("0.0.0.0 metrics.example.test # tracker")
        assertTrue(engine.blocks("https://metrics.example.test/collect"))
    }

    @Test
    fun `unsupported action modifiers never become broad blocking rules`() {
        val engine = engine(
            "*${'$'}removeparam=utm_source",
            "||cdn.example.test^${'$'}redirect=noopjs",
            "||known.example.test^"
        )
        assertFalse(engine.blocks("https://unrelated.test/page.js"))
        assertFalse(engine.blocks("https://cdn.example.test/app.js"))
        assertTrue(engine.blocks("https://known.example.test/app.js"))
    }

    @Test
    fun `badfilter disables its matching network rule`() {
        val engine = engine(
            "||disabled.example.test^${'$'}script",
            "||disabled.example.test^${'$'}script,badfilter"
        )
        assertFalse(engine.blocks(
            "https://disabled.example.test/app.js",
            type = FilterEngine.ResourceType.SCRIPT
        ))
    }

    private fun engine(vararg rules: String) = FilterEngine.parse(rules.asSequence())

    private fun FilterEngine.blocks(
        url: String,
        pageUrl: String = "https://publisher.test/article",
        type: FilterEngine.ResourceType = FilterEngine.ResourceType.OTHER
    ) = shouldBlock(FilterEngine.Request(url, pageUrl, type))
}
