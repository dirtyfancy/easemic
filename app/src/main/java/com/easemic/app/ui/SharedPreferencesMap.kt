package com.easemic.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

private const val PREFS_NAME = "private_comic_library_ui_prefs"

/** [PreferenceMap] backed by the app's private [SharedPreferences]. */
class SharedPreferencesMap(
    private val prefs: SharedPreferences,
) : PreferenceMap {
    override fun getString(key: String, default: String?): String? =
        prefs.getString(key, default)

    override fun putString(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }

    override fun putStringSync(key: String, value: String) {
        prefs.edit(commit = true) { putString(key, value) }
    }

    companion object {
        fun from(context: Context): SharedPreferencesMap =
            SharedPreferencesMap(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            )
    }
}
