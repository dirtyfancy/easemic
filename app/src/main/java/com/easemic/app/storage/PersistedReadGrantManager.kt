package com.easemic.app.storage

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

sealed interface GrantPersistenceResult {
    data class Persisted(val uri: Uri) : GrantPersistenceResult
    data object MissingReadGrant : GrantPersistenceResult
    data class Failed(val cause: Throwable) : GrantPersistenceResult
}

interface PersistedReadGrantManager {
    fun persist(grant: GrantedUri): GrantPersistenceResult
    fun hasReadGrant(uri: Uri): Boolean
    fun release(uri: Uri): Boolean
}

data class PersistedPermission(val uri: Uri, val read: Boolean)

class AndroidPersistedReadGrantManager(
    private val take: (Uri, Int) -> Unit,
    private val release: (Uri, Int) -> Unit,
    private val persisted: () -> List<PersistedPermission>,
) : PersistedReadGrantManager {
    constructor(contentResolver: ContentResolver) : this(
        take = contentResolver::takePersistableUriPermission,
        release = contentResolver::releasePersistableUriPermission,
        persisted = { contentResolver.persistedUriPermissions.map { PersistedPermission(it.uri, it.isReadPermission) } },
    )

    override fun persist(grant: GrantedUri): GrantPersistenceResult {
        if (grant.readFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) return GrantPersistenceResult.MissingReadGrant
        return try {
            take(grant.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            GrantPersistenceResult.Persisted(grant.uri)
        } catch (error: SecurityException) {
            GrantPersistenceResult.Failed(error)
        }
    }

    override fun hasReadGrant(uri: Uri): Boolean = persisted().any { it.uri == uri && it.read }

    override fun release(uri: Uri): Boolean = try {
        release(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        true
    } catch (_: SecurityException) {
        false
    }
}
