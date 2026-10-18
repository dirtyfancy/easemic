package com.easemic.app.feature.source.jm

import com.easemic.app.feature.download.FetchFailureClass
import com.easemic.app.feature.download.FetchRetryContext
import com.easemic.app.feature.download.FetchTransportException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

internal enum class JmRole { API, IMAGE, PUBLICATION }
internal enum class JmEndpoint(val path: String) {
    SETTING("setting"), CATALOGUE("categories/filter"), SEARCH("search"), ALBUM("album"),
    CHAPTER("chapter"), TEMPLATE("chapter_view_template")
}
internal sealed class JmPayload {
    data class Present(val json: org.json.JSONObject) : JmPayload()
    data object Missing : JmPayload()
}

/** Only the endpoint decoder produces Missing. Wire headers never carry local provenance. */
internal object JmFailure {
    const val PROVENANCE_HEADER = "x-easemic-jm-local-absence"
    const val ERROR_ENVELOPE_HEADER = "x-easemic-jm-error-envelope"
    private val nonce = UUID.randomUUID().toString()
    private val errorNonce = UUID.randomUUID().toString()
    private val absence = "JM validated anonymous metadata absence".toByteArray(Charsets.US_ASCII)
    private val sourceError = "JM validated source error envelope".toByteArray(Charsets.US_ASCII)
    // A safe local marker, never decrypted metadata or request material. Schema/validation
    // failures can also occur after the wire envelope has been decoded (e.g. chapterPlan).
    fun invalid(): Nothing = throw FetchTransportException(
        200, null, "JM protocol unavailable".toByteArray(Charsets.US_ASCII), message = "JM protocol unavailable",
    )
    fun missing(): FetchTransportException = FetchTransportException(
        200, null, absence.copyOf(), mapOf(PROVENANCE_HEADER to nonce), "JM metadata unavailable",
    )
    fun errorEnvelope(): Nothing = throw FetchTransportException(
        200, null, sourceError.copyOf(), mapOf(ERROR_ENVELOPE_HEADER to errorNonce), "JM request unavailable",
    )
    fun isErrorEnvelope(error: FetchTransportException): Boolean =
        error.headers.entries.any { it.key.equals(ERROR_ENVELOPE_HEADER, true) && it.value == errorNonce }
    fun classify(status: Int, body: ByteArray, headers: Map<String, String>, retry: FetchRetryContext,
                 nowMillis: Long = System.currentTimeMillis()): FetchFailureClass {
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value
        if (header("cf-mitigated")?.split(',')?.any { it.trim().equals("challenge", true) } == true) {
            return FetchFailureClass.ABUSE_STOP("Source temporarily blocked")
        }
        if (status == 429) return FetchFailureClass.PACED(retryAfter(header("retry-after"), nowMillis))
        if (status == 200 && header(PROVENANCE_HEADER) == nonce && body.contentEquals(absence)) return FetchFailureClass.GONE
        if (status in 500..599 && retry.isRepeatForOrdinal) return FetchFailureClass.PACED(null)
        // Bare 404/410, non-JSON 200 and every ambiguous metadata shape stay transient, including repeats.
        return FetchFailureClass.JITTER
    }
    private fun retryAfter(raw: String?, now: Long): Int? {
        val value = raw?.trim() ?: return null
        value.toLongOrNull()?.let { return it.takeIf { seconds -> seconds in 0..Int.MAX_VALUE.toLong() }?.toInt() }
        return runCatching {
            val millis = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
            val seconds = ((millis - now).coerceAtLeast(0) + 999) / 1000
            seconds.takeIf { it <= Int.MAX_VALUE }?.toInt()
        }.getOrNull()
    }
}
