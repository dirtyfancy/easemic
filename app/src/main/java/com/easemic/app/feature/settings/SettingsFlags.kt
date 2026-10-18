package com.easemic.app.feature.settings

import com.easemic.app.ui.PreferenceMap

/** Tolerant "1"/"0" parse shared by the settings stores. Anything else keeps [default]. */
internal fun readSettingsFlag(prefs: PreferenceMap, key: String, default: Boolean): Boolean {
    val raw = prefs.getString(key, null) ?: return default
    return when (raw) {
        "1", "true", "TRUE", "yes" -> true
        "0", "false", "FALSE", "no" -> false
        else -> default
    }
}

internal fun readOptionalLong(prefs: PreferenceMap, key: String): Long? {
    val raw = prefs.getString(key, null)?.trim().orEmpty()
    if (raw.isEmpty()) return null
    return raw.toLongOrNull()
}
