package com.easemic.app.feature.settings

import com.easemic.app.storage.CacheBudget
import kotlin.math.abs

/**
 * Preset snap for the cache panel. An equal distance resolves to the smaller
 * option, the same rule [CacheBudgetStore] uses when it writes the budget.
 */
object CacheBudgetPanel {
    fun nearestOptionMb(budgetMb: Int): Int {
        val value = budgetMb.toLong()
        return CacheBudget.OPTIONS_MB.minWithOrNull(
            compareBy<Long> { abs(it - value) }.thenBy { it },
        )?.toInt() ?: CacheBudget.DEFAULT_MB
    }
}
