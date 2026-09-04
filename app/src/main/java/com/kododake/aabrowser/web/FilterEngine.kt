package com.kododake.aabrowser.web

import java.net.URI
import java.util.Locale

/** Immutable parser/matcher for the commonly used subset of uBlock static filters. */
class FilterEngine private constructor(
    private val networkRules: List<NetworkRule>,
    private val cosmeticRules: List<CosmeticRule>
) {
    enum class ResourceType { SCRIPT, IMAGE, STYLESHEET, FONT, MEDIA, XHR, SUBDOCUMENT, OTHER }

    data class Request(
        val url: String,
        val pageUrl: String?,
        val resourceType: ResourceType = ResourceType.OTHER
    )

    fun shouldBlock(request: Request): Boolean {
        val requestHost = hostOf(request.url) ?: return false
        val pageHost = hostOf(request.pageUrl)
        val context = MatchContext(
            request,
            pageHost,
            pageHost != null && siteKey(requestHost) != siteKey(pageHost)
        )
        if (networkRules.any { it.exception && it.matches(context) }) return false
        return networkRules.any { !it.exception && it.matches(context) }
    }

    fun cosmeticCss(pageUrl: String?): String {
        val host = hostOf(pageUrl) ?: return ""
        val hidden = LinkedHashSet<String>()
        val exceptions = HashSet<String>()
        cosmeticRules.forEach { rule ->
            if (rule.appliesTo(host)) {
                if (rule.exception) exceptions += rule.selector else hidden += rule.selector
            }
        }
        hidden.removeAll(exceptions)
        return hidden.joinToString(",\n") { "$it { display: none !important; }" }
    }

    val ruleCount: Int get() = networkRules.size + cosmeticRules.size

    companion object {
        private val DOMAIN_ONLY = Regex("^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$")
        private val TYPE_OPTIONS = mapOf(
            "script" to ResourceType.SCRIPT, "image" to ResourceType.IMAGE,
            "stylesheet" to ResourceType.STYLESHEET, "font" to ResourceType.FONT,
            "media" to ResourceType.MEDIA, "xmlhttprequest" to ResourceType.XHR,
            "xhr" to ResourceType.XHR, "subdocument" to ResourceType.SUBDOCUMENT,
            "frame" to ResourceType.SUBDOCUMENT
        )

        fun parse(lines: Sequence<String>): FilterEngine {
            val network = ArrayList<NetworkRule>()
            val cosmetic = ArrayList<CosmeticRule>()
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("!") || line.startsWith("[") || line.startsWith("# ")) return@forEach
                parseCosmetic(line)?.let { cosmetic += it; return@forEach }
                parseNetwork(line)?.let(network::add)
            }
            return FilterEngine(network, cosmetic)
        }

        private fun parseCosmetic(line: String): CosmeticRule? {
            val marker = when { "#@#" in line -> "#@#"; "##" in line -> "##"; else -> return null }
            val split = line.indexOf(marker)
            val selector = line.substring(split + marker.length).trim()
            if (selector.isEmpty() || selector.startsWith("+js(") || ":has-text(" in selector || ":matches-css(" in selector) return null
            val domains = line.substring(0, split).split(',').map(String::trim).filter(String::isNotEmpty)
            val included = domains.filterNot { it.startsWith("~") }.map(::normalizeHost).toSet()
            val excluded = domains.filter { it.startsWith("~") }.map { normalizeHost(it.drop(1)) }.toSet()
            return CosmeticRule(selector, marker == "#@#", included, excluded)
        }

        private fun parseNetwork(source: String): NetworkRule? {
            var line = source
            val exception = line.startsWith("@@")
            if (exception) line = line.drop(2)
            if (line.isBlank() || line.startsWith("#")) return null
            val optionAt = optionSeparator(line)
            val patternText = if (optionAt >= 0) line.substring(0, optionAt) else line
            val optionText = if (optionAt >= 0) line.substring(optionAt + 1) else ""
            if (patternText.isBlank()) return null

            var thirdParty: Boolean? = null
            var matchCase = false
            val includeTypes = mutableSetOf<ResourceType>()
            val excludeTypes = mutableSetOf<ResourceType>()
            val includeDomains = mutableSetOf<String>()
            val excludeDomains = mutableSetOf<String>()
            optionText.split(',').map(String::trim).filter(String::isNotEmpty).forEach { raw ->
                val negated = raw.startsWith("~")
                val option = raw.removePrefix("~").lowercase(Locale.ROOT)
                when {
                    option == "third-party" -> thirdParty = !negated
                    option == "match-case" -> matchCase = !negated
                    option.startsWith("domain=") -> option.substringAfter('=').split('|').forEach { domain ->
                        if (domain.startsWith("~")) excludeDomains += normalizeHost(domain.drop(1))
                        else if (domain.isNotBlank()) includeDomains += normalizeHost(domain)
                    }
                    option in TYPE_OPTIONS -> {
                        val type = TYPE_OPTIONS.getValue(option)
                        if (negated) excludeTypes += type else includeTypes += type
                    }
                }
            }
            return NetworkRule(
                compilePattern(patternText, matchCase) ?: return null, exception, thirdParty,
                includeTypes, excludeTypes, includeDomains, excludeDomains
            )
        }

        private fun optionSeparator(line: String): Int {
            if (line.startsWith('/') && line.lastIndexOf('/') > 0) return line.indexOf('$', line.lastIndexOf('/') + 1)
            return line.indexOf('$')
        }

        private fun compilePattern(text: String, matchCase: Boolean): Regex? {
            val flags = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
            if (text.length > 2 && text.startsWith('/') && text.endsWith('/')) {
                return runCatching { Regex(text.drop(1).dropLast(1), flags) }.getOrNull()
            }
            val normalized = text.lowercase(Locale.ROOT)
            if (DOMAIN_ONLY.matches(normalized)) {
                val host = Regex.escape(normalizeHost(normalized))
                return Regex("^[a-z][a-z0-9+.-]*://(?:[^/?#]*\\.)?$host(?=[:/?#]|$)", flags)
            }
            var pattern = text
            val hostAnchored = pattern.startsWith("||")
            val startAnchored = !hostAnchored && pattern.startsWith('|')
            val endAnchored = pattern.endsWith('|')
            pattern = when { hostAnchored -> pattern.drop(2); startAnchored -> pattern.drop(1); else -> pattern }
            if (endAnchored) pattern = pattern.dropLast(1)
            val regex = StringBuilder()
            when { hostAnchored -> regex.append("^[a-z][a-z0-9+.-]*://(?:[^/?#]*\\.)?"); startAnchored -> regex.append('^') }
            pattern.forEach { char ->
                when (char) {
                    '*' -> regex.append(".*")
                    '^' -> regex.append("(?:[^A-Za-z0-9_.%-]|$)")
                    else -> regex.append(Regex.escape(char.toString()))
                }
            }
            if (endAnchored) regex.append('$')
            return runCatching { Regex(regex.toString(), flags) }.getOrNull()
        }

        internal fun hostOf(url: String?): String? = runCatching { url?.let(::URI)?.host?.let(::normalizeHost) }.getOrNull()
        private fun normalizeHost(host: String) = host.trim().trimEnd('.').lowercase(Locale.ROOT)
        private fun hostMatches(host: String, domain: String) = host == domain || host.endsWith(".$domain")
        private val COMMON_SECOND_LEVEL_SUFFIXES = setOf("co.uk", "org.uk", "com.au", "net.au", "co.jp", "co.nz", "com.br", "com.cn", "com.sg", "co.in")
        private fun siteKey(host: String): String {
            val labels = normalizeHost(host).split('.')
            if (labels.size <= 2) return labels.joinToString(".")
            val lastTwo = labels.takeLast(2).joinToString(".")
            return if (lastTwo in COMMON_SECOND_LEVEL_SUFFIXES) labels.takeLast(3).joinToString(".") else lastTwo
        }
    }

    private data class MatchContext(val request: Request, val pageHost: String?, val thirdParty: Boolean)

    private data class NetworkRule(
        val pattern: Regex, val exception: Boolean, val thirdParty: Boolean?,
        val includeTypes: Set<ResourceType>, val excludeTypes: Set<ResourceType>,
        val includeDomains: Set<String>, val excludeDomains: Set<String>
    ) {
        fun matches(context: MatchContext): Boolean {
            if (thirdParty != null && context.thirdParty != thirdParty) return false
            if (includeTypes.isNotEmpty() && context.request.resourceType !in includeTypes) return false
            if (context.request.resourceType in excludeTypes) return false
            val host = context.pageHost
            if (includeDomains.isNotEmpty() && (host == null || includeDomains.none { hostMatches(host, it) })) return false
            if (host != null && excludeDomains.any { hostMatches(host, it) }) return false
            return pattern.containsMatchIn(context.request.url)
        }
    }

    private data class CosmeticRule(
        val selector: String, val exception: Boolean,
        val includedDomains: Set<String>, val excludedDomains: Set<String>
    ) {
        fun appliesTo(host: String): Boolean {
            if (excludedDomains.any { hostMatches(host, it) }) return false
            return includedDomains.isEmpty() || includedDomains.any { hostMatches(host, it) }
        }
    }
}
