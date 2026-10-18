package com.easemic.app.feature.update

import java.util.Locale
import org.json.JSONObject

/** #E1 / #E123: what easemic.com's `/api/app/latest` says about the current release. */
data class AppRelease(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val downloadUrl: String,
    val sizeBytes: Long? = null,
    val sha256: String? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("versionCode", versionCode)
        put("versionName", versionName)
        put("notes", notes)
        put("downloadUrl", downloadUrl)
        if (sizeBytes != null && sizeBytes > 0L) put("sizeBytes", sizeBytes)
        if (!sha256.isNullOrBlank()) put("sha256", sha256)
    }.toString()

    companion object {
        private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")

        /** Null for anything unusable: malformed JSON, a missing field, or a download address that is not https. */
        fun parse(json: String): AppRelease? = try {
            val o = JSONObject(json)
            val code = o.getInt("versionCode")
            val name = o.getString("versionName")
            val url = o.getString("downloadUrl")
            if (code <= 0 || name.isBlank() || !url.startsWith("https://")) {
                null
            } else {
                val rawSize = if (o.has("sizeBytes") && !o.isNull("sizeBytes")) o.optLong("sizeBytes", -1L) else -1L
                val sizeBytes = rawSize.takeIf { it > 0L }
                val rawSha = if (o.has("sha256") && !o.isNull("sha256")) {
                    o.optString("sha256", "").trim().lowercase(Locale.US)
                } else {
                    ""
                }
                val sha256 = rawSha.takeIf { SHA256_REGEX.matches(it) }
                AppRelease(
                    versionCode = code,
                    versionName = name,
                    notes = o.optString("notes", ""),
                    downloadUrl = url,
                    sizeBytes = sizeBytes,
                    sha256 = sha256,
                )
            }
        } catch (_: Exception) {
            null
        }
    }
}
