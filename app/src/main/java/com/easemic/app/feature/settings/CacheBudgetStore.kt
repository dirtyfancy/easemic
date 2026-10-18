package com.easemic.app.feature.settings

import com.easemic.app.storage.CacheBudget
import com.easemic.app.ui.PreferenceMap

class CacheBudgetStore(
    private val prefs: PreferenceMap,
) {
    fun readMb(): Int {
        val raw = prefs.getString(KEY, null)?.toLongOrNull()
        return nearestOption(raw ?: CacheBudget.DEFAULT_MB.toLong()).toInt()
    }

    fun readBytes(): Long = readMb() * CacheBudget.BYTES_PER_MB

    fun writeMb(value: Int) {
        prefs.putString(KEY, nearestOption(value.toLong()).toString())
    }

    private fun nearestOption(value: Long): Long =
        CacheBudget.OPTIONS_MB.minWithOrNull(
            compareBy<Long> { kotlin.math.abs(it - value) }.thenBy { it },
        ) ?: CacheBudget.DEFAULT_MB.toLong()

    companion object {
        const val KEY = "cache_budget_mb_v1"
    }
}
