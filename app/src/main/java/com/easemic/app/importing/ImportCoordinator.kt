package com.easemic.app.importing

import com.easemic.app.data.LegacyCatalogCompatibility
import com.easemic.app.model.ScanRejection
import kotlinx.coroutines.CancellationException

sealed interface ImportResult {
    data class Imported(
        val itemId: Long,
        val metadataWarning: Boolean,
    ) : ImportResult

    data class Failed(val reason: ImportFailure) : ImportResult
}

sealed interface ImportFailure {
    data class Scan(val reason: ScanRejection) : ImportFailure
    data object CatalogWriteFailed : ImportFailure
}

class ImportCoordinator(
    private val scanner: ImageDirectoryScanner,
    private val repository: LegacyCatalogCompatibility,
) {
    suspend fun importTree(treeUri: String): ImportResult = when (val scan = scanner.scan(treeUri)) {
        is com.easemic.app.model.ScanResult.Rejected ->
            ImportResult.Failed(ImportFailure.Scan(scan.reason))
        is com.easemic.app.model.ScanResult.Accepted ->
            try {
                ImportResult.Imported(
                    repository.upsertLegacyDirectory(scan.candidate).value,
                    scan.metadataWarning,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ImportResult.Failed(ImportFailure.CatalogWriteFailed)
            }
    }
}
