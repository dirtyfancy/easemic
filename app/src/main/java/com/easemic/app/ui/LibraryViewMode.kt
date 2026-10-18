package com.easemic.app.ui

enum class LibraryViewMode {
    GRID,
    LIST,
}

interface PreferenceMap {
    fun getString(key: String, default: String?): String?
    fun putString(key: String, value: String)

    /**
     * Synchronous durable write: returns only after the value is persisted.
     * For queue/journal writes that must survive an immediate process kill;
     * call off the main thread.
     */
    fun putStringSync(key: String, value: String) = putString(key, value)
}
