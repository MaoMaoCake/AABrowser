/*
 * Copyright (C) 2025 AABrowser Contributors (https://github.com/kododake/AABrowser)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://gnu.org>.
 */

package com.kododake.aabrowser.data.prefs

import android.net.Uri
import android.content.Context
import com.kododake.aabrowser.data.BrowserPreferences
import com.kododake.aabrowser.model.SearchEngine

object UrlFormatter {
    const val DEFAULT_URL = "https://www.google.com"

    fun formatNavigableUrl(raw: String, context: Context? = null): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        if (isHttpOrHttps(trimmed)) return trimmed
        if (trimmed.contains(" ") || !trimmed.contains(".")) {
            return toSearchUrl(trimmed, context)
        }
        val candidate = "https://$trimmed"
        return runCatching {
            val uri = Uri.parse(candidate)
            if (uri.host?.contains(".") == true) candidate else toSearchUrl(trimmed, context)
        }.getOrDefault(toSearchUrl(trimmed, context))
    }

    fun toSearchUrl(query: String, context: Context? = null): String =
        (context?.let { BrowserPreferences.getSearchEngine(it) } ?: SearchEngine.DEFAULT).buildSearchUrl(query)

    fun defaultUrl(): String = DEFAULT_URL

    fun isHttpOrHttps(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }
}
