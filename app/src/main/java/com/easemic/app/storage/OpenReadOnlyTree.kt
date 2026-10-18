package com.easemic.app.storage

import android.app.Activity
import android.content.Intent
import android.net.Uri

object OpenReadOnlyTree {
    fun createIntent(initialUri: Uri? = null): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        initialUri?.let { putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, it) }
    }

    fun parseResult(resultCode: Int, data: Intent?): GrantedUri? =
        data?.data
            ?.takeIf { resultCode == Activity.RESULT_OK }
            ?.takeIf { data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0 }
            ?.let { GrantedUri(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
}

data class GrantedUri(val uri: Uri, val readFlags: Int)
