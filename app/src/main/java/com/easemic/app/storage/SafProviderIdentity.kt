package com.easemic.app.storage

import android.provider.DocumentsContract
import androidx.core.net.toUri
import java.security.MessageDigest

object SafProviderIdentity {
    data class TreeIdentity(
        val providerIdentity: String,
        val treeDocumentId: String,
    )

    fun treeIdentity(uri: String): TreeIdentity? = runCatching {
        uri.toUri().let { parsed ->
            val authority = parsed.authority ?: return null
            val documentId = DocumentsContract.getTreeDocumentId(parsed)
            TreeIdentity(
                providerIdentity = "$authority:tree:$documentId",
                treeDocumentId = documentId,
            )
        }
    }.getOrNull()

    fun tree(uri: String): String? = treeIdentity(uri)?.providerIdentity

    fun document(uri: String): String? = runCatching {
        uri.toUri().let { parsed ->
            val authority = parsed.authority ?: return null
            "$authority:document:${DocumentsContract.getDocumentId(parsed)}"
        }
    }.getOrNull()

    fun legacyTree(uri: String): String = "legacy-tree:${sha256(uri)}"

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
