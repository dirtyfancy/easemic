package com.easemic.app.sync

import org.json.JSONObject

data class LibraryStateSnapshot(
    val historyJson: String,
    val bookmarksJson: String,
    val frontUseExhentai: Boolean,
    val frontEnabled: Boolean,
    val indexEnabled: Boolean,
    val progressJson: String = "[]",
    val formatVersion: Int = 3,
    /**
     * Always empty across the envelope boundary. [LibraryStateBackup.encode]
     * refuses to write it and [LibraryStateBackup.decode] refuses to read it;
     * the field survives only so callers that still read a live account
     * compile. An e-hentai session is a bearer credential — anyone holding it
     * is the account — so it must not travel to cloud storage in a form the
     * device cannot revoke. Restoring a backup therefore leaves this device's
     * session exactly as it was (a never-signed-in device stays signed out
     * and signs in through the WebView; a signed-in device stays signed in —
     * the restore never touches the session).
     */
    val frontCookie: String = "",
)

/**
 * JSON envelope for local library state. Callers wrap it with
 * [EncryptedBackupCodec] for the passphrase-sealed `.pclbackup` file; media
 * #D7: kept only so an older `.pclbackup` still restores; new backups are `.pclarchive` (sync/archive).
 */
object LibraryStateBackup {
    fun encode(snapshot: LibraryStateSnapshot): ByteArray {
        val root = JSONObject()
            .put("formatVersion", snapshot.formatVersion)
            .put("historyJson", snapshot.historyJson)
            .put("bookmarksJson", snapshot.bookmarksJson)
            // frontCookie is deliberately absent: see LibraryStateSnapshot.
            .put("frontUseExhentai", snapshot.frontUseExhentai)
            .put("frontEnabled", snapshot.frontEnabled)
            .put("indexEnabled", snapshot.indexEnabled)
            .put("progressJson", snapshot.progressJson)
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): LibraryStateSnapshot {
        val root = JSONObject(bytes.toString(Charsets.UTF_8))
        return LibraryStateSnapshot(
            formatVersion = root.optInt("formatVersion", 1),
            historyJson = root.optString("historyJson", "[]"),
            bookmarksJson = root.optString("bookmarksJson", "[]"),
            // A v2 envelope on disk may carry one. It is read past, not read in.
            frontCookie = "",
            frontUseExhentai = root.optBoolean("frontUseExhentai", false),
            frontEnabled = root.optBoolean("frontEnabled", false),
            indexEnabled = root.optBoolean("indexEnabled", false),
            progressJson = root.optString("progressJson", "[]"),
        )
    }
}
