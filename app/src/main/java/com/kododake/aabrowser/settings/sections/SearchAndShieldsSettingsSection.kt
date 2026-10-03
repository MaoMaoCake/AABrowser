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

package com.kododake.aabrowser.settings.sections

import android.content.Context
import android.text.InputType
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import com.kododake.aabrowser.R
import com.kododake.aabrowser.data.BrowserPreferences
import com.kododake.aabrowser.model.SearchEngine
import com.kododake.aabrowser.settings.SettingsCallbacks
import com.kododake.aabrowser.settings.components.SettingsComponentBuilder
import com.kododake.aabrowser.web.adblock.AdBlocker
import com.kododake.aabrowser.web.adblock.RemoteFilterListManager

object SearchAndShieldsSettingsSection {
    fun create(context: Context, callbacks: SettingsCallbacks): LinearLayout =
        with(SettingsComponentBuilder(context)) {
            val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val searchCard = createStyledCard()
            val searchInner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(16), dp(8), dp(16))
            }
            searchInner.addView(
                createSectionTitle(
                    context.getString(R.string.settings_search_engine),
                    R.drawable.search_24px,
                    bottomPaddingDp = 4
                )
            )
            searchInner.addView(TextView(context).apply {
                text = context.getString(R.string.settings_search_engine_description)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(onSurfaceVariantColor)
                setPadding(dp(12), 0, dp(12), dp(8))
            })

            var currentEngine = BrowserPreferences.getSearchEngine(context)
            val engineOrder = SearchEngine.entries.toList()
            var searchEngineStatusView: TextView? = null
            val searchEngineRow = createSettingRow(
                title = context.getString(R.string.settings_search_engine),
                statusText = context.getString(currentEngine.titleRes),
                iconRes = R.drawable.search_24px
            ) {
                val engineLabels = engineOrder.map { context.getString(it.titleRes) }.toTypedArray()
                MaterialAlertDialogBuilder(context, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                    .setTitle(R.string.settings_search_engine_dialog_title)
                    .setSingleChoiceItems(engineLabels, engineOrder.indexOf(currentEngine)) { dialog, which ->
                        dialog.dismiss()
                        val selected = engineOrder[which]
                        if (selected != currentEngine) {
                            currentEngine = selected
                            BrowserPreferences.setSearchEngine(context, selected)
                            searchEngineStatusView?.text = context.getString(selected.titleRes)
                        }
                    }
                    .show()
            }
            searchEngineStatusView =
                ((searchEngineRow.getChildAt(1) as? LinearLayout)?.getChildAt(1) as? TextView)
            searchInner.addView(searchEngineRow)

            searchCard.addView(searchInner)
            container.addView(searchCard)

            val shieldsCard = createStyledCard()
            val shieldsInner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(16), dp(8), dp(16))
            }
            shieldsInner.addView(
                createSectionTitle(
                    context.getString(R.string.settings_shields),
                    R.drawable.security_24px,
                    bottomPaddingDp = 4
                )
            )

            fun shieldsStatusText(enabled: Boolean): String = if (enabled) {
                context.getString(R.string.settings_shields_blocked_count, AdBlocker.blockedThisSession.toInt())
            } else {
                context.getString(R.string.settings_shields_disabled)
            }

            val shieldsStatusView = TextView(context).apply {
                text = shieldsStatusText(BrowserPreferences.isShieldsEnabled(context))
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(onSurfaceVariantColor)
                setPadding(dp(12), 0, dp(12), dp(8))
            }
            shieldsInner.addView(shieldsStatusView)

            val shieldsRow = createSettingSwitchRow(
                title = context.getString(R.string.settings_shields_title),
                description = context.getString(R.string.settings_shields_description),
                iconRes = R.drawable.security_24px,
                isCheckedValue = BrowserPreferences.isShieldsEnabled(context)
            ) { isChecked ->
                BrowserPreferences.setShieldsEnabled(context, isChecked)
                shieldsStatusView.text = shieldsStatusText(isChecked)
                callbacks.onShieldsChanged()
            }
            shieldsInner.addView(shieldsRow)

            val filterListsStatus = TextView(context).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(onSurfaceVariantColor)
                setPadding(dp(12), dp(4), dp(12), dp(4))
            }
            fun updateFilterListsStatus() {
                val lists = RemoteFilterListManager.subscriptions(context)
                val enabled = lists.count { it.enabled }
                val rules = lists.filter { it.enabled }.sumOf { it.ruleCount }
                filterListsStatus.text = context.getString(R.string.settings_filter_lists_summary, enabled, lists.size, rules)
            }
            updateFilterListsStatus()
            shieldsInner.addView(filterListsStatus)

            lateinit var showManageLists: () -> Unit
            val showAddCustomList = {
                val form = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), 0, dp(20), 0)
                }
                val nameLayout = TextInputLayout(context).apply { hint = context.getString(R.string.settings_filter_list_name) }
                val nameInput = TextInputEditText(context).apply { inputType = InputType.TYPE_CLASS_TEXT }
                nameLayout.addView(nameInput)
                form.addView(nameLayout)
                val urlLayout = TextInputLayout(context).apply { hint = context.getString(R.string.settings_filter_list_url) }
                val urlInput = TextInputEditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                }
                urlLayout.addView(urlInput)
                form.addView(urlLayout)
                val dialog = MaterialAlertDialogBuilder(context, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                    .setTitle(R.string.settings_filter_lists_add)
                    .setView(form)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.settings_action_add, null)
                    .create()
                dialog.setOnShowListener {
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val url = urlInput.text?.toString().orEmpty()
                        val result = runCatching {
                            RemoteFilterListManager.addCustom(context, nameInput.text?.toString().orEmpty(), url)
                        }
                        if (result.isFailure) {
                            urlLayout.error = result.exceptionOrNull()?.message ?: context.getString(R.string.settings_filter_list_invalid)
                        } else {
                            dialog.dismiss()
                            updateFilterListsStatus()
                            Toast.makeText(context, R.string.settings_filter_lists_updating, Toast.LENGTH_SHORT).show()
                            RemoteFilterListManager.refresh(context, force = true) { update ->
                                updateFilterListsStatus()
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.settings_filter_lists_update_result, update.updated, update.failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
                dialog.show()
            }
            showManageLists = {
                val lists = RemoteFilterListManager.subscriptions(context)
                val checked = BooleanArray(lists.size) { lists[it].enabled }
                val labels = lists.map { item ->
                    val suffix = when {
                        item.lastError != null -> context.getString(R.string.settings_filter_list_failed)
                        item.ruleCount > 0 -> context.getString(R.string.settings_filter_list_rules, item.ruleCount)
                        else -> context.getString(R.string.settings_filter_list_not_downloaded)
                    }
                    "${item.title} · $suffix"
                }.toTypedArray()
                MaterialAlertDialogBuilder(context, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                    .setTitle(R.string.settings_filter_lists_manage)
                    .setMultiChoiceItems(labels, checked) { _, which, enabled -> checked[which] = enabled }
                    .setNegativeButton(android.R.string.cancel, null)
                    .setNeutralButton(R.string.settings_filter_lists_remove) { _, _ ->
                        val custom = lists.filterNot { it.builtIn }
                        if (custom.isEmpty()) {
                            Toast.makeText(context, R.string.settings_filter_lists_no_custom, Toast.LENGTH_SHORT).show()
                        } else {
                            MaterialAlertDialogBuilder(context, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                                .setTitle(R.string.settings_filter_lists_remove)
                                .setItems(custom.map { it.title }.toTypedArray()) { _, which ->
                                    RemoteFilterListManager.removeCustomAsync(context, custom[which].id) {
                                        updateFilterListsStatus()
                                    }
                                }
                                .setNegativeButton(android.R.string.cancel, null)
                                .show()
                        }
                    }
                    .setPositiveButton(R.string.settings_action_save) { _, _ ->
                        val enabledIds = lists.indices.filter { checked[it] }.mapTo(HashSet()) { lists[it].id }
                        RemoteFilterListManager.setEnabledAsync(context, enabledIds) { updateFilterListsStatus() }
                    }
                    .show()
            }
            shieldsInner.addView(createSettingRow(
                title = context.getString(R.string.settings_filter_lists_manage),
                statusText = context.getString(R.string.settings_filter_lists_manage_description),
                iconRes = R.drawable.public_24px,
                onClick = { showManageLists() }
            ))
            shieldsInner.addView(createListButton(
                R.id.settings_filter_list_add_button,
                context.getString(R.string.settings_filter_lists_add),
                R.drawable.arrow_forward_24px
            ).apply { setOnClickListener { showAddCustomList() } })
            shieldsInner.addView(createListButton(
                R.id.settings_filter_list_update_button,
                context.getString(R.string.settings_filter_lists_update),
                R.drawable.refresh_24px
            ).apply {
                setOnClickListener {
                    isEnabled = false
                    Toast.makeText(context, R.string.settings_filter_lists_updating, Toast.LENGTH_SHORT).show()
                    RemoteFilterListManager.refresh(context, force = true) { result ->
                        isEnabled = true
                        updateFilterListsStatus()
                        Toast.makeText(
                            context,
                            context.getString(R.string.settings_filter_lists_update_result, result.updated, result.failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            })

            shieldsCard.addView(shieldsInner)
            container.addView(shieldsCard)

            container
        }
}
