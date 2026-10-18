package com.easemic.app.feature.browse

import coil3.network.HttpException
import com.easemic.app.sync.CloudHttpException
import com.easemic.app.sync.CloudSignIn
import java.io.IOException

/**
 * A62 (spec D3): what a remote (online catalog / cloud bucket) page failure IS, reduced
 * from the raw throwable to one glyph language: 429 (rate limited), 403 (blocked), 5xx
 * (source trouble), network, or unknown — plus the HTTP status digits when the transport
 * carried a code. Pure, so both the online reader (Coil's [HttpException] on 4xx/5xx) and
 * the cloud reader ([CloudHttpException], message shape `CLOUD_HTTP_<code>`) classify the
 * same way and the JVM tests pin the mapping.
 */
data class RemotePageFailure(
    val kind: Kind,
    /** The HTTP status the transport reported, when there was one; digits for the glyph. */
    val statusCode: Int?,
) {
    enum class Kind {
        RATE_LIMITED, FORBIDDEN, SERVER_ERROR, NETWORK, UNKNOWN,

        /** #D16: the cloud refused the saved sign-in. */
        SIGN_IN_REJECTED,
    }

    /** The digits drawn under the glyph ("429"), or null when no status exists. */
    fun statusDigits(): String? = statusCode?.toString()

    companion object {
        val Network = RemotePageFailure(Kind.NETWORK, statusCode = null)
        fun Unknown(statusCode: Int? = null) = RemotePageFailure(Kind.UNKNOWN, statusCode = statusCode)
    }
}

fun classifyRemotePageFailure(throwable: Throwable?): RemotePageFailure {
    val httpCode = generateSequence(throwable, Throwable::cause)
        .mapNotNull(::httpStatusCodeOf)
        .firstOrNull()
    if (httpCode != null) {
        return when (httpCode) {
            429 -> RemotePageFailure(RemotePageFailure.Kind.RATE_LIMITED, httpCode)
            403 -> RemotePageFailure(RemotePageFailure.Kind.FORBIDDEN, httpCode)
            in 500..599 -> RemotePageFailure(RemotePageFailure.Kind.SERVER_ERROR, httpCode)
            else -> RemotePageFailure.Unknown(httpCode)
        }
    }
    val ioFailure = generateSequence(throwable, Throwable::cause).any { it is IOException }
    if (ioFailure) return RemotePageFailure.Network
    return RemotePageFailure.Unknown()
}

/** #D16: the cloud reader's classifier — a refused sign-in is its own kind; online sources never see it. */
fun classifyCloudPageFailure(throwable: Throwable?): RemotePageFailure {
    val base = classifyRemotePageFailure(throwable)
    return if (CloudSignIn.rejected(throwable)) base.copy(kind = RemotePageFailure.Kind.SIGN_IN_REJECTED) else base
}

private fun httpStatusCodeOf(throwable: Throwable): Int? = when (throwable) {
    is HttpException -> throwable.response.code
    is CloudHttpException -> CLOUD_HTTP_STATUS_PATTERN
        .find(throwable.message.orEmpty())
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
    else -> null
}

private val CLOUD_HTTP_STATUS_PATTERN = Regex("CLOUD_HTTP_(\\d+)")