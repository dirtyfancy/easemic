package com.easemic.app.feature.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.easemic.app.data.LibraryRepository
import com.easemic.app.data.LocalDetailRow
import com.easemic.app.model.LibraryItemId
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

sealed interface DetailUiState {
    data object Loading : DetailUiState

    data class Ready(val row: LocalDetailRow) : DetailUiState

    data object Unavailable : DetailUiState
}

/**
 * A32 §1/§5: the Room→UI binding for one library row — item, progress,
 * availability and the persisted metadata snapshot. Deliberately **not** a
 * second design: the rendering lives in the one
 * `com.easemic.app.feature.browse.ComicDetailScreen`, and
 * this class no longer owns any preview-paging state (a fixed-height lazy grid
 * takes every page, so `previewLimit` / `canShowMorePreviews` / `onMorePreviews`
 * and the "More" button are gone).
 *
 * Collect / cloud / download-job facts are app-scope singletons, not
 * repository state, so the `AppRoute.Detail` branch — not this ViewModel —
 * completes the [DetailSubject.Local].
 */
class DetailViewModel(
    itemId: Long,
    repository: LibraryRepository,
) : ViewModel() {

    val uiState: StateFlow<DetailUiState> =
        repository.observeItemDetail(LibraryItemId(itemId))
            .map { row -> if (row == null) DetailUiState.Unavailable else DetailUiState.Ready(row) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = DetailUiState.Loading,
            )

    class Factory(
        private val itemId: Long,
        private val repository: LibraryRepository,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            check(modelClass.isAssignableFrom(DetailViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            @Suppress("UNCHECKED_CAST")
            return DetailViewModel(itemId, repository) as T
        }
    }
}
