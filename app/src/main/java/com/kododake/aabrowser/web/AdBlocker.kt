package com.kododake.aabrowser.web

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight ad/tracker blocker in the spirit of Brave Shields.
 *
 * Requests are matched by host against a bundled domain list (assets/blocklist.txt).
 * Matching walks the parent domains, so a single "doubleclick.net" entry also covers
 * "stats.g.doubleclick.net". Main-frame navigations are never blocked, and requests
 * back to the page's own domain are treated as first party and left alone.
 */
object AdBlocker {

    private const val BLOCKLIST_ASSET = "blocklist.txt"
    private const val MIN_MATCHABLE_LABELS = 2

    @Volatile
    private var blockedDomains: Set<String> = emptySet()

    @Volatile
    private var loaded = false

    private val sessionBlockCount = AtomicLong(0)

    val blockedThisSession: Long
        get() = sessionBlockCount.get()

    fun resetSessionCount() {
        sessionBlockCount.set(0)
    }

    /** Parses the bundled list once. Safe to call from any thread. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            blockedDomains = runCatching { parseBlocklist(context) }.getOrDefault(emptySet())
            loaded = true
        }
    }

    private fun parseBlocklist(context: Context): Set<String> {
        return context.applicationContext.assets.open(BLOCKLIST_ASSET).bufferedReader().useLines { lines ->
            lines.map { it.substringBefore('#').trim().lowercase() }
                .filter { it.isNotEmpty() }
                .toSet()
        }
    }

    fun isBlockedHost(host: String?): Boolean {
        if (host.isNullOrBlank() || blockedDomains.isEmpty()) return false
        val normalized = host.lowercase().removeSuffix(".")
        if (normalized in blockedDomains) return true

        var index = normalized.indexOf('.')
        while (index in 0 until normalized.lastIndex) {
            val parent = normalized.substring(index + 1)
            if (parent.count { it == '.' } + 1 < MIN_MATCHABLE_LABELS) break
            if (parent in blockedDomains) return true
            index = normalized.indexOf('.', index + 1)
        }
        return false
    }

    /**
     * Returns an empty response when [requestUrl] should be blocked, or null to let it through.
     * [pageUrl] is the URL of the page making the request, used for the first-party exemption.
     */
    fun interceptOrNull(requestUrl: Uri?, pageUrl: String?, isMainFrame: Boolean): WebResourceResponse? {
        if (isMainFrame || requestUrl == null) return null

        val scheme = requestUrl.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null

        val requestHost = requestUrl.host ?: return null
        if (isFirstParty(requestHost, pageUrl)) return null
        if (!isBlockedHost(requestHost)) return null

        sessionBlockCount.incrementAndGet()
        return emptyResponse()
    }

    private fun isFirstParty(requestHost: String, pageUrl: String?): Boolean {
        val pageHost = pageUrl?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Uri.parse(it).host }.getOrNull() }
            ?: return false
        return registrableDomain(requestHost) == registrableDomain(pageHost)
    }

    private fun registrableDomain(host: String): String {
        val labels = host.lowercase().removeSuffix(".").split('.')
        if (labels.size <= MIN_MATCHABLE_LABELS) return labels.joinToString(".")
        return labels.takeLast(MIN_MATCHABLE_LABELS).joinToString(".")
    }

    private fun emptyResponse(): WebResourceResponse {
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }
}
