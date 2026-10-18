package com.easemic.app.storage

import android.content.Intent
import android.net.Uri
import java.io.File

fun isAppPrivateFilePath(path: String, privateRoots: List<File>): Boolean {
    val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
    return privateRoots.any { root ->
        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return@any false
        file == canonicalRoot || file.path.startsWith(canonicalRoot.path + File.separator)
    }
}

/**
 * App-private catalog/OPDS downloads use `file:` tree URIs. Those are not
 * DocumentsContract trees; readability is a local directory check.
 */
fun isReadableLocalFileTree(treeUri: String): Boolean {
    val uri = runCatching { java.net.URI(treeUri) }.getOrNull() ?: return false
    if (uri.scheme?.equals("file", ignoreCase = true) != true) return false
    val file = runCatching { File(uri).canonicalFile }.getOrNull() ?: return false
    return file.isDirectory && file.canRead()
}

/**
 * Treats app-private `file:` URIs under configured roots as granted, so OPDS
 * downloads stored in [android.content.Context.getFilesDir] can open without
 * SAF persistence.
 */
class AppPrivateReadGrantManager(
    private val privateRoots: List<File>,
    private val delegate: PersistedReadGrantManager,
) : PersistedReadGrantManager {
    override fun persist(grant: GrantedUri): GrantPersistenceResult {
        if (grant.uri.scheme.equals("file", ignoreCase = true)) {
            return if (isAppPrivate(grant.uri) &&
                grant.readFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
            ) {
                GrantPersistenceResult.Persisted(grant.uri)
            } else {
                GrantPersistenceResult.MissingReadGrant
            }
        }
        return delegate.persist(grant)
    }

    override fun hasReadGrant(uri: Uri): Boolean {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            return isAppPrivate(uri)
        }
        return delegate.hasReadGrant(uri)
    }

    override fun release(uri: Uri): Boolean {
        if (uri.scheme.equals("file", ignoreCase = true)) {
            return isAppPrivate(uri)
        }
        return delegate.release(uri)
    }

    private fun isAppPrivate(uri: Uri): Boolean {
        val path = uri.path ?: return false
        return isAppPrivateFilePath(path, privateRoots)
    }
}
