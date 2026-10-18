package com.easemic.app.work

/** Durable-facing progress emitted while a scan/import worker advances. */
data class ImportWorkProgress(
    val completed: Int,
    val total: Int? = null,
)
