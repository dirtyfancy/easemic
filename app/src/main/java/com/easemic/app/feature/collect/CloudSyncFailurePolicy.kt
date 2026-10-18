package com.easemic.app.feature.collect

import com.easemic.app.feature.bridge.ByoCloudIoCopy
import com.easemic.app.feature.bridge.CloudSignInCopy
import com.easemic.app.sync.CloudSignIn
import com.easemic.app.sync.DamagedLibraryLockException
import com.easemic.app.sync.LibraryLockedException
import com.easemic.app.sync.NewerCloudFormatException
import com.easemic.app.sync.UnreadableCloudLibraryException

/** Replay cannot repair these inputs. These failures park one row, never the whole pump. */
object CloudSyncFailurePolicy {
    private val inputRepairDetails = mapOf(
        "CLOUD_SERIALIZED_FACTS_MISSING" to "chapter info or saved pages are missing",
        "CLOUD_SERIALIZED_IDENTITY_MISSING" to "the saved chapter has no series identity",
        "CLOUD_SERIALIZED_ID_MISSING" to "the cloud series could not be identified",
        "CLOUD_INGEST_PAGES_MISSING_POST_SEAL" to "saved pages are missing or unreadable",
        "CLOUD_INGEST_UNSUPPORTED_SOURCE" to "this source cannot be uploaded this way",
    )
    private val actionDetails = mapOf(
        ByoCloudIoCopy.RECONNECT to "reconnect cloud storage",
        LibraryLockedException.MESSAGE to "unlock your cloud library",
        NewerCloudFormatException.MESSAGE to "update Easemic to use this cloud library",
        DamagedLibraryLockException.MESSAGE to "the cloud library's key file is damaged",
        UnreadableCloudLibraryException.MESSAGE to "this cloud library cannot be read",
    )

    // Preflight persists the same reason inside its own exact wrapper.
    private fun matches(message: String?, reason: String): Boolean =
        message == reason || message == CollectPreflightStatus.rowMessage(reason)

    fun requiresInputRepair(message: String?): Boolean = inputRepairDetails.keys.any { matches(message, it) }

    /** Existing reconnect/unlock/update/key repair flows keep their own runner outcomes. */
    fun requiresUserAction(message: String?): Boolean =
        CloudSignIn.rejected(message) || actionDetails.keys.any { matches(message, it) }

    fun canRetry(message: String?): Boolean = !requiresInputRepair(message) && !requiresUserAction(message)

    /** Plain words only; diagnostics remain in the queue, never in Activity. */
    fun attentionLine(message: String?): String? {
        if (CloudSignIn.rejected(message)) {
            return if (CollectPreflightStatus.isPreflightFailure(message)) CloudSignInCopy.CHECK_LINE
            else CloudSignInCopy.UPLOAD_LINE
        }
        val detail = (inputRepairDetails.entries + actionDetails.entries)
            .firstOrNull { matches(message, it.key) }?.value ?: return null
        return "failed · $detail"
    }
}
