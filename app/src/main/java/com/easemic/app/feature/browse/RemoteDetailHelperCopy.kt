package com.easemic.app.feature.browse

/**
 * English Detail helpers, shown for every cloud provider. "Cloud" means the user's own storage (S3, Cloudflare R2,
 * WebDAV, OneDrive or Google Drive), not a service we run. #D19: name the cloud in the general, never "R2" or
 * "bucket" — those read as false on a WebDAV, OneDrive or Google Drive library.
 */
object RemoteDetailHelperCopy {
    fun serialized(presence: ResourcePresence, cloudBound: Boolean, keepOnlyCloud: Boolean): String = when (presence) {
        ResourcePresence.TOMBSTONED -> "This series is in your cloud storage's recycle bin for 30 days. Collect restores it."
        ResourcePresence.CLOUD -> "Read uses saved chapters on this phone first, then your cloud copy."
        else -> "Collect follows this series and saves available chapters. New chapters are saved as they appear. " + when {
            !cloudBound -> "Chapters are saved on this phone."
            keepOnlyCloud -> "Saved chapters go to your cloud; the phone copy is removed after upload."
            else -> "Saved chapters go to your cloud and stay on this phone."
        }
    }
    const val CLOUD_COLLECTION =
        "This comic is in your cloud storage, and Read uses the copy on this phone first. Download saves a copy to this phone; canceling the collection moves the cloud copy to a 30-day recycle bin."
    const val BROWSE_COLLECT =
        "Collect saves this comic to your cloud storage and keeps the pages on this phone unless \"Keep only the cloud copy\" is on; with no storage connected, it saves a bookmark. Download saves a copy to this phone without uploading it."
    const val BOOKMARKED =
        "This comic is collected. Tap Collect again to cancel the collection; Download saves a copy to this phone."
    const val TOMBSTONED =
        "This comic is in your cloud storage's recycle bin for 30 days. Collect restores it; Download saves a copy to this phone."
    const val OPDS_LIBRARY =
        "Collect saves this comic to your cloud storage and keeps the pages on this phone unless \"Keep only the cloud copy\" is on; with no storage connected, it saves a bookmark. Read streams pages from the server only if it supports page streaming; Download saves a copy to this phone."
}
