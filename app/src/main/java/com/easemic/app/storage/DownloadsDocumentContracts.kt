package com.easemic.app.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Backup pickers start in the shared Download folder. Honor's open-document picker lists only cloud drives and other
 * apps' roots, never internal storage, so without a starting folder a saved backup cannot be found to restore it;
 * the save picker otherwise reopens the last folder used, which can be an imported comic folder.
 */
val DOWNLOADS_DOCUMENT_URI: Uri =
    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")

class OpenDocumentInDownloads : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, DOWNLOADS_DOCUMENT_URI)
}

class CreateDocumentInDownloads(mimeType: String) : ActivityResultContracts.CreateDocument(mimeType) {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).putExtra(DocumentsContract.EXTRA_INITIAL_URI, DOWNLOADS_DOCUMENT_URI)
}
