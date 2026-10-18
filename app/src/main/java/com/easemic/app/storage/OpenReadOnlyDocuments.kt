package com.easemic.app.storage

import android.app.Activity
import android.content.Intent

object OpenReadOnlyDocuments {
    private val acceptedMimeTypes = arrayOf(
        "application/zip",
        "application/vnd.comicbook+zip",
        "application/x-cbz",
    )

    fun createIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        type = acceptedMimeTypes.first()
        putExtra(Intent.EXTRA_MIME_TYPES, acceptedMimeTypes)
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    }

    fun parseResult(resultCode: Int, data: Intent?): List<GrantedUri> {
        if (resultCode != Activity.RESULT_OK || data == null ||
            data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0
        ) return emptyList()

        return buildList {
            data.clipData?.let { clip ->
                for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
            }
            data.data?.let(::add)
        }.distinct().map { GrantedUri(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }
}
