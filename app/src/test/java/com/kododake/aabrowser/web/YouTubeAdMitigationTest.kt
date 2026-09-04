package com.kododake.aabrowser.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAdMitigationTest {
    @Test
    fun `only applies to youtube origins`() {
        assertTrue(YouTubeAdMitigation.appliesTo("https://www.youtube.com/watch?v=abc"))
        assertTrue(YouTubeAdMitigation.appliesTo("https://m.youtube.com/watch?v=abc"))
        assertTrue(YouTubeAdMitigation.appliesTo("https://www.youtube-nocookie.com/embed/abc"))
        assertFalse(YouTubeAdMitigation.appliesTo("https://youtube.com.evil.test/watch"))
        assertFalse(YouTubeAdMitigation.appliesTo("https://example.com/"))
    }

    @Test
    fun `document start script covers player data and visible ads`() {
        val script = YouTubeAdMitigation.script
        assertTrue("adPlacements" in script)
        assertTrue("ytInitialPlayerResponse" in script)
        assertTrue("ad-showing" in script)
        assertTrue("MutationObserver" in script)
    }
}
