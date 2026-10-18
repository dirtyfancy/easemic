package com.easemic.app.feature.settings

import com.easemic.app.ui.PreferenceMap

/**
 * #C4a (owner 2026-09-10): "delete the local copy after uploading to cloud",
 * a More-tab setting that the UI shows only once a cloud library is bound.
 *
 * Default OFF, and every unparseable value clamps to OFF rather than to
 * [DEFAULT]-as-written-by-someone-else, because ON deletes files: a corrupt
 * pref must fail towards keeping the user's comics.
 *
 * Shape follows `feature/reader/ReaderVolumeKeyPagingStore` (tolerant parse,
 * canonical "1"/"0" writes, a `DEFAULT` constant) so the two read alike.
 */
class CloudReleaseAfterUploadStore(
    private val prefs: PreferenceMap,
) {
    fun read(): Boolean = readFlag(KEY, DEFAULT)

    fun write(enabled: Boolean) {
        prefs.putString(KEY, if (enabled) "1" else "0")
    }

    /**
     * Whether the one-time "also clear the local copies of comics already
     * uploaded?" prompt has been put to the user. Durable so it is asked once
     * per turn-on rather than on every visit to the More tab; the UI clears it
     * when the setting goes back OFF so a later turn-on asks again.
     */
    fun readCatchUpAsked(): Boolean = readFlag(KEY_CATCH_UP_ASKED, false)

    fun writeCatchUpAsked(asked: Boolean) {
        prefs.putString(KEY_CATCH_UP_ASKED, if (asked) "1" else "0")
    }

    private fun readFlag(key: String, default: Boolean): Boolean =
        readSettingsFlag(prefs, key, default)

    companion object {
        const val KEY = "cloud.release_local_after_upload"
        const val KEY_CATCH_UP_ASKED = "cloud.release_local_after_upload.catch_up_asked"

        /** OFF: downloading keeps the pages on the phone, which is the pre-#C4a behavior. */
        const val DEFAULT = false
    }
}
