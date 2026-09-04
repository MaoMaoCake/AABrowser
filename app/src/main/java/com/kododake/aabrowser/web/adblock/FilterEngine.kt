package com.kododake.aabrowser.web.adblock

import java.net.URI
import java.util.Locale

/** Immutable parser/matcher for the commonly used subset of uBlock static filters. */
class FilterEngine private constructor(
    private val networkRules: List<NetworkRule>,
    private val cosmeticRules: List<CosmeticRule>,
    private val scriptletRules: List<ScriptletRule>
) {
    private val indexedBlockingRules = networkRules.filterNot(NetworkRule::exception)
        .filter { it.token != null }.groupBy { it.token!! }
    private val unindexedBlockingRules = networkRules.filterNot(NetworkRule::exception).filter { it.token == null }
    private val indexedExceptionRules = networkRules.filter(NetworkRule::exception)
        .filter { it.token != null }.groupBy { it.token!! }
    private val unindexedExceptionRules = networkRules.filter(NetworkRule::exception).filter { it.token == null }

    enum class ResourceType { SCRIPT, IMAGE, STYLESHEET, FONT, MEDIA, XHR, SUBDOCUMENT, OTHER }

    data class Request(
        val url: String,
        val pageUrl: String?,
        val resourceType: ResourceType = ResourceType.OTHER
    )

    data class SourceLine(val text: String, val trusted: Boolean = false)

    data class ScriptletInvocation(
        val name: String,
        val arguments: List<String>,
        val trusted: Boolean
    )

    fun shouldBlock(request: Request): Boolean {
        val requestHost = hostOf(request.url) ?: return false
        val pageHost = hostOf(request.pageUrl)
        val context = MatchContext(
            request,
            requestHost,
            pageHost,
            pageHost != null && siteKey(requestHost) != siteKey(pageHost)
        )
        if (candidates(request.url, indexedExceptionRules, unindexedExceptionRules).any { it.matches(context) }) return false
        return candidates(request.url, indexedBlockingRules, unindexedBlockingRules).any { it.matches(context) }
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

    fun scriptletsFor(pageUrl: String?): List<ScriptletInvocation> {
        val host = hostOf(pageUrl) ?: return emptyList()
        val exceptions = scriptletRules.filter { it.exception && it.appliesTo(host) }
        if (exceptions.any { it.arguments.isEmpty() }) return emptyList()
        val excepted = exceptions.mapTo(HashSet()) { it.key }
        val selected = LinkedHashMap<Pair<String, List<String>>, ScriptletInvocation>()
        scriptletRules.asSequence()
            .filter { !it.exception && it.appliesTo(host) && it.key !in excepted }
            .forEach { rule ->
                val existing = selected[rule.key]
                if (existing == null || (!existing.trusted && rule.trusted)) {
                    selected[rule.key] = ScriptletInvocation(rule.name, rule.arguments, rule.trusted)
                }
            }
        return selected.values.toList()
    }

    val ruleCount: Int get() = networkRules.size + cosmeticRules.size + scriptletRules.size

    private fun candidates(
        url: String,
        index: Map<String, List<NetworkRule>>,
        unindexed: List<NetworkRule>
    ): Sequence<NetworkRule> = sequence {
        yieldAll(unindexed)
        URL_TOKENS.findAll(url.lowercase(Locale.ROOT)).map { it.value }.toSet().forEach { token ->
            index[token]?.let { yieldAll(it) }
        }
    }

    companion object {
        private val DOMAIN_ONLY = Regex("^[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?$")
        private val URL_TOKENS = Regex("[a-z0-9%]{4,}")
        private val UNHELPFUL_TOKENS = setOf("http", "https", "html", "com", "org", "net")
        private val TYPE_OPTIONS = mapOf(
            "script" to ResourceType.SCRIPT, "image" to ResourceType.IMAGE,
            "stylesheet" to ResourceType.STYLESHEET, "font" to ResourceType.FONT,
            "css" to ResourceType.STYLESHEET,
            "media" to ResourceType.MEDIA, "xmlhttprequest" to ResourceType.XHR,
            "xhr" to ResourceType.XHR, "subdocument" to ResourceType.SUBDOCUMENT,
            "frame" to ResourceType.SUBDOCUMENT, "other" to ResourceType.OTHER,
            "object" to ResourceType.MEDIA
        )

        fun parse(lines: Sequence<String>): FilterEngine =
            parseSources(lines.map { SourceLine(it) })

        fun parseSources(lines: Sequence<SourceLine>): FilterEngine {
            val network = ArrayList<NetworkRule>()
            val cosmetic = ArrayList<CosmeticRule>()
            val scriptlets = ArrayList<ScriptletRule>()
            val disabledNetworkRules = HashSet<String>()
            lines.forEach { sourceLine ->
                val line = sourceLine.text.trim()
                if (line.isEmpty() || line.startsWith("!") || line.startsWith("[") || line.startsWith("# ")) return@forEach
                parseScriptlet(line, sourceLine.trusted)?.let { scriptlets += it; return@forEach }
                parseCosmetic(line)?.let { cosmetic += it; return@forEach }
                badFilterTarget(line)?.let { target ->
                    disabledNetworkRules += target
                    network.removeAll { it.source == target }
                    return@forEach
                }
                if (line !in disabledNetworkRules) parseNetwork(line)?.let(network::add)
            }
            return FilterEngine(network, cosmetic, scriptlets)
        }

        private fun parseScriptlet(line: String, trusted: Boolean): ScriptletRule? {
            val marker = when {
                "#@#+js(" in line -> "#@#+js("
                "##+js(" in line -> "##+js("
                else -> return null
            }
            if (!line.endsWith(')')) return null
            val split = line.indexOf(marker)
            val arguments = parseScriptletArguments(line.substring(split + marker.length, line.length - 1))
                ?: return null
            if (arguments.isEmpty() && marker == "##+js(") return null
            val domains = line.substring(0, split).split(',').map(String::trim).filter(String::isNotEmpty)
            val included = domains.filterNot { it.startsWith("~") }.map(::normalizeHost).toSet()
            val excluded = domains.filter { it.startsWith("~") }.map { normalizeHost(it.drop(1)) }.toSet()
            if (included.isEmpty() && marker == "##+js(") return null
            return ScriptletRule(
                name = canonicalScriptletName(arguments.firstOrNull().orEmpty()),
                arguments = arguments.drop(1),
                exception = marker == "#@#+js(",
                includedDomains = included,
                excludedDomains = excluded,
                trusted = trusted
            )
        }

        internal fun parseScriptletArguments(source: String): List<String>? {
            if (source.isBlank()) return emptyList()
            val output = ArrayList<String>()
            val current = StringBuilder()
            var quote: Char? = null
            var atArgumentStart = true
            var index = 0
            while (index < source.length) {
                val char = source[index]
                val next = source.getOrNull(index + 1)
                if (char == '\\' && ((quote != null && next == quote) || (quote == null && next == ','))) {
                    current.append(next)
                    index += 2
                    continue
                }
                if (quote != null) {
                    if (char == quote) quote = null else current.append(char)
                } else if (atArgumentStart && char.isWhitespace()) {
                    // uBO ignores whitespace before the optional opening quote.
                } else if (atArgumentStart && char in charArrayOf('\'', '"', '`')) {
                    quote = char
                    atArgumentStart = false
                } else if (char == ',') {
                    output += current.toString().trim()
                    current.clear()
                    atArgumentStart = true
                } else {
                    current.append(char)
                    atArgumentStart = false
                }
                index++
            }
            if (quote != null) return null
            output += current.toString().trim()
            return output
        }

        private fun canonicalScriptletName(rawName: String): String {
            val name = rawName.removeSuffix(".js")
            return SCRIPTLET_ALIASES[name] ?: name
        }

        private val SCRIPTLET_ALIASES = mapOf(
            "abort-current-inline-script" to "abort-current-script",
            "acis" to "abort-current-script", "acs" to "abort-current-script",
            "ra" to "remove-attr", "urlskip" to "href-sanitizer",
            "aost" to "abort-on-stack-trace", "prevent-eval-if" to "noeval-if",
            "addEventListener-defuser" to "prevent-addEventListener",
            "aeld" to "prevent-addEventListener", "bab-defuser" to "prevent-bab",
            "nobab" to "prevent-bab", "no-fetch-if" to "prevent-fetch",
            "no-setTimeout-if" to "prevent-setTimeout", "nostif" to "prevent-setTimeout",
            "setTimeout-defuser" to "prevent-setTimeout",
            "no-setInterval-if" to "prevent-setInterval", "nosiif" to "prevent-setInterval",
            "setInterval-defuser" to "prevent-setInterval",
            "no-requestAnimationFrame-if" to "prevent-requestAnimationFrame",
            "norafif" to "prevent-requestAnimationFrame", "set" to "set-constant",
            "trusted-set" to "trusted-set-constant", "no-xhr-if" to "prevent-xhr",
            "cookie-remover" to "remove-cookie", "aopr" to "abort-on-property-read",
            "aopw" to "abort-on-property-write",
            "nano-setInterval-booster" to "adjust-setInterval", "nano-sib" to "adjust-setInterval",
            "nano-setTimeout-booster" to "adjust-setTimeout", "nano-stb" to "adjust-setTimeout",
            "refresh-defuser" to "prevent-refresh", "rc" to "remove-class",
            "nowoif" to "prevent-window-open", "no-window-open-if" to "prevent-window-open",
            "window.open-defuser" to "prevent-window-open", "window-close-if" to "close-window",
            "rmnt" to "remove-node-text", "trusted-rpnt" to "trusted-replace-node-text",
            "replace-node-text" to "trusted-replace-node-text", "rpnt" to "trusted-replace-node-text",
            "trusted-rpfr" to "trusted-replace-fetch-response"
        )

        private fun badFilterTarget(line: String): String? {
            val split = optionSeparator(line)
            if (split < 0) return null
            val options = line.substring(split + 1).split(',')
            if (options.none { it.removePrefix("~").equals("badfilter", ignoreCase = true) }) return null
            val retained = options.filterNot { it.removePrefix("~").equals("badfilter", ignoreCase = true) }
            val optionsWithoutBadFilter = retained.joinToString(",")
            return line.substring(0, split) + if (optionsWithoutBadFilter.isEmpty()) {
                ""
            } else {
                "${'$'}$optionsWithoutBadFilter"
            }
        }

        private fun parseCosmetic(line: String): CosmeticRule? {
            val marker = when { "#@#" in line -> "#@#"; "##" in line -> "##"; else -> return null }
            val split = line.indexOf(marker)
            val selector = line.substring(split + marker.length).trim()
            val unsupportedOperators = listOf(
                ":has-text(", ":matches-css(", ":matches-attr(", ":remove(",
                ":style(", ":upward(", ":xpath(", ":others(", ":watch-attr("
            )
            if (selector.isEmpty() || selector.startsWith("+") || selector.startsWith("^") ||
                unsupportedOperators.any(selector::contains)) return null
            val domains = line.substring(0, split).split(',').map(String::trim).filter(String::isNotEmpty)
            val included = domains.filterNot { it.startsWith("~") }.map(::normalizeHost).toSet()
            val excluded = domains.filter { it.startsWith("~") }.map { normalizeHost(it.drop(1)) }.toSet()
            return CosmeticRule(selector, marker == "#@#", included, excluded)
        }

        private fun parseNetwork(source: String): NetworkRule? {
            var line = source
            val hostsEntry = Regex("^(?:0\\.0\\.0\\.0|127\\.0\\.0\\.1|::1)\\s+([^\\s#]+)").find(line)
            if (hostsEntry != null) line = hostsEntry.groupValues[1]
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
            var unsupported = false
            optionText.split(',').map(String::trim).filter(String::isNotEmpty).forEach { raw ->
                val negated = raw.startsWith("~")
                val option = raw.removePrefix("~").lowercase(Locale.ROOT)
                when {
                    option == "third-party" || option == "3p" -> thirdParty = !negated
                    option == "first-party" || option == "1p" -> thirdParty = negated
                    option == "match-case" -> matchCase = !negated
                    option == "all" && !negated -> Unit
                    option == "important" && !negated -> Unit
                    option.startsWith("domain=") -> option.substringAfter('=').split('|').forEach { domain ->
                        if (domain.startsWith("~")) excludeDomains += normalizeHost(domain.drop(1))
                        else if (domain.isNotBlank()) includeDomains += normalizeHost(domain)
                    }
                    option in TYPE_OPTIONS -> {
                        val type = TYPE_OPTIONS.getValue(option)
                        if (negated) excludeTypes += type else includeTypes += type
                    }
                    else -> unsupported = true
                }
            }
            // Silently ignoring an action option such as removeparam or redirect
            // would turn it into a much broader blocking rule, so skip the rule.
            if (unsupported) return null
            return NetworkRule(
                compilePattern(patternText, matchCase) ?: return null, source, exception, extractToken(patternText), thirdParty,
                includeTypes, excludeTypes, includeDomains, excludeDomains
            )
        }

        private fun extractToken(pattern: String): String? {
            if (pattern.length > 2 && pattern.startsWith('/') && pattern.endsWith('/')) return null
            return URL_TOKENS.findAll(pattern.lowercase(Locale.ROOT))
                .map { it.value }
                .filterNot { it in UNHELPFUL_TOKENS }
                .maxByOrNull(String::length)
        }

        private fun optionSeparator(line: String): Int {
            if (line.startsWith('/') && line.lastIndexOf('/') > 0) return line.indexOf('$', line.lastIndexOf('/') + 1)
            return line.indexOf('$')
        }

        private fun compilePattern(text: String, matchCase: Boolean): UrlPattern? {
            val flags = if (matchCase) emptySet() else setOf(RegexOption.IGNORE_CASE)
            if (text.length > 2 && text.startsWith('/') && text.endsWith('/')) {
                return runCatching { RegexPattern(Regex(text.drop(1).dropLast(1), flags)) }.getOrNull()
            }
            val normalized = text.lowercase(Locale.ROOT)
            if (DOMAIN_ONLY.matches(normalized)) {
                return HostPattern(normalizeHost(normalized))
            }
            val hostOnly = Regex("^\\|\\|([a-zA-Z0-9.-]+)\\^$").matchEntire(text)
            if (hostOnly != null) {
                return HostPattern(normalizeHost(hostOnly.groupValues[1]))
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
            return runCatching { RegexPattern(Regex(regex.toString(), flags)) }.getOrNull()
        }

        internal fun hostOf(url: String?): String? = runCatching { url?.let(::URI)?.host?.let(::normalizeHost) }.getOrNull()
        private fun normalizeHost(host: String) = host.trim().trimEnd('.').lowercase(Locale.ROOT)
        private fun hostMatches(host: String, domain: String): Boolean {
            if (domain.startsWith('/') && domain.endsWith('/') && domain.length > 2) {
                return runCatching { Regex(domain.drop(1).dropLast(1)).containsMatchIn(host) }.getOrDefault(false)
            }
            if (domain.endsWith(".*")) {
                return siteKey(host).substringBefore('.') == domain.dropLast(2).substringAfterLast('.')
            }
            return host == domain || host.endsWith(".$domain")
        }
        private val COMMON_SECOND_LEVEL_SUFFIXES = setOf("co.uk", "org.uk", "com.au", "net.au", "co.jp", "co.nz", "com.br", "com.cn", "com.sg", "co.in")
        private fun siteKey(host: String): String {
            val labels = normalizeHost(host).split('.')
            if (labels.size <= 2) return labels.joinToString(".")
            val lastTwo = labels.takeLast(2).joinToString(".")
            return if (lastTwo in COMMON_SECOND_LEVEL_SUFFIXES) labels.takeLast(3).joinToString(".") else lastTwo
        }
    }

    private data class MatchContext(
        val request: Request,
        val requestHost: String,
        val pageHost: String?,
        val thirdParty: Boolean
    )

    private sealed interface UrlPattern {
        fun matches(url: String, requestHost: String): Boolean
    }

    private data class HostPattern(val host: String) : UrlPattern {
        override fun matches(url: String, requestHost: String): Boolean = hostMatches(requestHost, host)
    }

    private data class RegexPattern(val regex: Regex) : UrlPattern {
        override fun matches(url: String, requestHost: String): Boolean = regex.containsMatchIn(url)
    }

    private data class NetworkRule(
        val pattern: UrlPattern, val source: String, val exception: Boolean, val token: String?, val thirdParty: Boolean?,
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
            return pattern.matches(context.request.url, context.requestHost)
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

    private data class ScriptletRule(
        val name: String,
        val arguments: List<String>,
        val exception: Boolean,
        val includedDomains: Set<String>,
        val excludedDomains: Set<String>,
        val trusted: Boolean
    ) {
        val key: Pair<String, List<String>> get() = name to arguments

        fun appliesTo(host: String): Boolean {
            if (excludedDomains.any { hostMatches(host, it) }) return false
            return includedDomains.isEmpty() || includedDomains.any { hostMatches(host, it) }
        }
    }
}
