package com.baer.hado.data.local

import android.content.Context

/**
 * Controls whether new items are added to the top (prepend) or bottom (append) of a list.
 */
enum class AddItemPosition {
    TOP,
    BOTTOM;
}

/**
 * Consolidated manager for app-level user preferences stored in SharedPreferences.
 */
object AppPreferencesManager {

    private const val PREFS_NAME = "hado_app_prefs"
    private const val KEY_ADD_ITEM_POSITION = "add_item_position"
    private const val KEY_CHECKBOX_ONLY = "app_checkbox_only"
    private const val KEY_HIDE_COMPLETED = "app_hide_completed"
    private const val KEY_HIDDEN_LISTS = "app_hidden_lists"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Add Item Position ---

    fun loadAddItemPosition(context: Context): AddItemPosition {
        val value = prefs(context).getString(KEY_ADD_ITEM_POSITION, null)
        return try {
            if (value != null) AddItemPosition.valueOf(value) else AddItemPosition.TOP
        } catch (_: Exception) {
            AddItemPosition.TOP
        }
    }

    fun saveAddItemPosition(context: Context, position: AddItemPosition) {
        prefs(context).edit()
            .putString(KEY_ADD_ITEM_POSITION, position.name)
            .apply()
    }

    // --- Checkbox Only ---

    fun loadCheckboxOnly(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CHECKBOX_ONLY, false)
    }

    fun saveCheckboxOnly(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_CHECKBOX_ONLY, enabled)
            .apply()
    }

    // --- Hide Completed ---

    fun loadHideCompleted(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HIDE_COMPLETED, false)
    }

    fun saveHideCompleted(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_HIDE_COMPLETED, enabled)
            .apply()
    }

    // --- Lists hidden from the app ---
    // Scoped per server, since entity ids of two servers can collide. Storing the hidden
    // ids (rather than the shown ones) keeps new lists visible and lets every list be hidden.

    /** Entity ids the app does not show; everything else is shown. */
    fun loadHiddenLists(context: Context, accountId: String?): Set<String> {
        // The returned set must not be modified, so hand back a copy.
        return prefs(context)
            .getStringSet(TokenManager.scopedKey(accountId, KEY_HIDDEN_LISTS), null)
            ?.toSet()
            .orEmpty()
    }

    fun saveHiddenLists(context: Context, accountId: String?, entityIds: Set<String>) {
        prefs(context).edit()
            .putStringSet(TokenManager.scopedKey(accountId, KEY_HIDDEN_LISTS), entityIds)
            .apply()
    }
}
