package com.easemic.app.importing

import com.easemic.app.model.PageDescriptor
import com.easemic.app.model.LocationId
import com.easemic.app.model.LocationState
import com.easemic.app.model.ProvenanceValue
import com.easemic.app.model.SourceId
import java.security.MessageDigest

object StructuralSignature {
    val equalityClaim = StructuralEquality.STRUCTURAL_ONLY

    fun from(pages: List<PageDescriptor>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        pages.forEach { page ->
            listOf(
                page.documentId ?: page.archiveEntryPath.orEmpty(),
                page.mimeType.orEmpty(),
                page.size?.toString().orEmpty(),
                page.lastModifiedMillis?.toString().orEmpty(),
                page.width.toString(),
                page.height.toString(),
            ).forEach { value ->
                digest.update(value.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

enum class StructuralEquality { STRUCTURAL_ONLY }

data class StructuralLocation(
    val id: LocationId,
    val sourceId: SourceId,
    val providerDocumentId: String,
    val state: LocationState,
    val structuralSignature: String,
    val provenance: List<ProvenanceValue>,
)

sealed interface StructuralReconnect {
    data class SameLocation(val locationId: LocationId) : StructuralReconnect
    data class Reconnected(val locationId: LocationId) : StructuralReconnect
    data object Conflicted : StructuralReconnect
    data object Separate : StructuralReconnect
}

object StructuralIdentity {
    fun reconnect(
        sourceId: SourceId,
        providerDocumentId: String,
        structuralSignature: String,
        provenance: List<ProvenanceValue>,
        existing: List<StructuralLocation>,
    ): StructuralReconnect {
        existing.singleOrNull {
            it.sourceId == sourceId && it.providerDocumentId == providerDocumentId
        }?.let { return StructuralReconnect.SameLocation(it.id) }

        val matchingMissing = existing.filter {
            it.sourceId == sourceId &&
                it.state == LocationState.MISSING &&
                it.structuralSignature == structuralSignature &&
                provenance.isNotEmpty() &&
                it.provenance == provenance
        }
        return when (matchingMissing.size) {
            1 -> StructuralReconnect.Reconnected(matchingMissing.single().id)
            0 -> StructuralReconnect.Separate
            else -> StructuralReconnect.Conflicted
        }
    }
}
