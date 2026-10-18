package com.easemic.app.feature.source.jm

import com.easemic.app.feature.browse.JmSearchScope

internal sealed interface JmSearchFallbackDecision {
    data class Fallback(val scope: JmSearchScope) : JmSearchFallbackDecision
}

internal object JmSearchFallback {
    fun decide(page: Int, scope: JmSearchScope?, itemCount: Int): JmSearchFallbackDecision? =
        if (page == 1 && scope != null && itemCount == 0) {
            JmSearchFallbackDecision.Fallback(scope)
        } else null
}
