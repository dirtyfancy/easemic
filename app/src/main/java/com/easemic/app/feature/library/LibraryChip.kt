package com.easemic.app.feature.library

sealed class LibraryChip {
    data object OnDevice : LibraryChip()
    data object Collected : LibraryChip()
    data object Downloads : LibraryChip()
    data object Cloud : LibraryChip()
    data class Opds(val providerId: String, val label: String) : LibraryChip()
}
