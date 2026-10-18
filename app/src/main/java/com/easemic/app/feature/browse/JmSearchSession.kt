package com.easemic.app.feature.browse

/** Ephemeral JM metadata-search scope; it is never persisted with Browse filters. */
data class JmSearchSession(
    val scope: JmSearchScope? = null,
    val awaitingSubmit: Boolean = false,
) {
    fun edited(): JmSearchSession =
        if (scope != null || awaitingSubmit) JmSearchSession(awaitingSubmit = true) else this

    fun submitted(): JmSearchSession = copy(awaitingSubmit = false)

    fun cleared(): JmSearchSession = JmSearchSession()

    fun switched(oldSource: String, newSource: String): JmSearchSession =
        if (oldSource == newSource) this else cleared()
}
