package com.kododake.aabrowser.web

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/** Android/WebView adapter around the uBlock-style [FilterEngine]. */
object AdBlocker {
    private const val FILTERS_ASSET = "blocklist.txt"

    @Volatile
    private var engine = FilterEngine.parse(emptySequence())

    @Volatile
    private var loaded = false

    private val sessionBlockCount = AtomicLong(0)

    val blockedThisSession: Long
        get() = sessionBlockCount.get()

    fun resetSessionCount() {
        sessionBlockCount.set(0)
    }

    /** Parses the bundled static filter list once. Safe to call from any thread. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            engine = loadEngine(context.applicationContext)
            loaded = true
        }
        RemoteFilterListManager.scheduleAutoUpdate(context.applicationContext)
    }

    /** Rebuilds the immutable engine after subscription or cache changes. */
    fun reload(context: Context) {
        val replacement = loadEngine(context.applicationContext)
        synchronized(this) {
            engine = replacement
            loaded = true
        }
    }

    private fun loadEngine(context: Context): FilterEngine = runCatching {
        FilterEngine.parseSources(sequence {
            context.assets.open(FILTERS_ASSET).bufferedReader().use { reader ->
                while (true) yield(FilterEngine.SourceLine(reader.readLine() ?: break, trusted = true))
            }
            yieldAll(RemoteFilterListManager.cachedFilterLines(context))
        })
    }.getOrElse {
        FilterEngine.parseSources(context.assets.open(FILTERS_ASSET).bufferedReader().use { reader ->
            reader.readLines().asSequence().map { FilterEngine.SourceLine(it, trusted = true) }
        })
    }

    fun scriptletInvocations(pageUrl: String?): List<FilterEngine.ScriptletInvocation> =
        engine.scriptletsFor(pageUrl)

    fun interceptOrNull(
        requestUrl: Uri?,
        pageUrl: String?,
        isMainFrame: Boolean,
        requestHeaders: Map<String, String> = emptyMap()
    ): WebResourceResponse? {
        if (isMainFrame || requestUrl == null) return null
        val scheme = requestUrl.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return null
        if (!engine.shouldBlock(FilterEngine.Request(
                url = requestUrl.toString(),
                pageUrl = pageUrl,
                resourceType = inferResourceType(requestUrl, requestHeaders)
            ))) return null

        sessionBlockCount.incrementAndGet()
        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
    }

    fun cosmeticScript(pageUrl: String?): String? {
        val css = engine.cosmeticCss(pageUrl)
        if (css.isBlank()) return null
        val quotedCss = org.json.JSONObject.quote(css)
        return """
            (() => {
              const id = '__aabrowser_cosmetic_filters';
              let style = document.getElementById(id);
              if (!style) {
                style = document.createElement('style');
                style.id = id;
                (document.head || document.documentElement).appendChild(style);
              }
              style.textContent = $quotedCss;
            })();
        """.trimIndent()
    }

    private fun inferResourceType(uri: Uri, headers: Map<String, String>): FilterEngine.ResourceType {
        val accept = headers.entries.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
            ?.value.orEmpty().lowercase(Locale.ROOT)
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        return when {
            "text/css" in accept || path.endsWith(".css") -> FilterEngine.ResourceType.STYLESHEET
            "image/" in accept || IMAGE_EXTENSIONS.any(path::endsWith) -> FilterEngine.ResourceType.IMAGE
            "font/" in accept || FONT_EXTENSIONS.any(path::endsWith) -> FilterEngine.ResourceType.FONT
            "audio/" in accept || "video/" in accept || MEDIA_EXTENSIONS.any(path::endsWith) -> FilterEngine.ResourceType.MEDIA
            "javascript" in accept || SCRIPT_EXTENSIONS.any(path::endsWith) -> FilterEngine.ResourceType.SCRIPT
            "application/json" in accept || "text/event-stream" in accept -> FilterEngine.ResourceType.XHR
            "text/html" in accept -> FilterEngine.ResourceType.SUBDOCUMENT
            else -> FilterEngine.ResourceType.OTHER
        }
    }

    private val IMAGE_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".avif", ".ico")
    private val FONT_EXTENSIONS = setOf(".woff", ".woff2", ".ttf", ".otf", ".eot")
    private val MEDIA_EXTENSIONS = setOf(".mp3", ".mp4", ".webm", ".m3u8", ".ts", ".ogg", ".wav")
    private val SCRIPT_EXTENSIONS = setOf(".js", ".mjs")
}
