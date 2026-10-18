package com.easemic.app.feature.update

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * #E1: one anonymous GET of easemic.com's current release. No cookie jar, no auth, no app or device identity —
 * OkHttp's default User-Agent only. Blocking: call off the main thread. Null on any failure.
 */
class UpdateCheckClient(
    private val endpoint: String = ENDPOINT,
    private val http: OkHttpClient = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build(),
) {
    fun fetch(): AppRelease? = try {
        http.newCall(Request.Builder().url(endpoint).get().build()).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()?.let(AppRelease::parse)
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val ENDPOINT = "https://easemic.com/api/app/latest"
    }
}
