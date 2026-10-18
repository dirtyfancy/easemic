package com.easemic.app.feature.providers

/**
 * Verdict function kept in ExhentaiAccessGate for downstream callers that built
 * against the old signature. Delegates to [judgeExhentaiAccess] which is the
 * canonical source of truth after lanes consolidated.
 *
 * Pass `probeBodyLength=null` to indicate "never probed", which maps to
 * [ExhentaiAccessVerdict.Unknown]. A real zero-length refusal outranks igneous
 * cookie presence.
 */
fun exhentaiAccessVerdict(
    session: EhentaiSessionCookies?,
    probeBodyLength: Int? = null,
): ExhentaiAccessVerdict =
    if (session == null) ExhentaiAccessVerdict.SignedOut
    else judgeExhentaiAccess(session, probeBodyLength)

/**
 * Detail copy for an unauthorised exhentai.org response. Surfaced through the
 * download failure classification so an expired session reads as an auth
 * problem instead of "this gallery is empty" (the old behaviour).
 */
const val EXHENTAI_UNAUTHORIZED_DETAIL: String =
    "ExHentai session is not authorized — sign in again in Settings"

/**
 * True when exhentai.org answered 200 with the blank "sad panda" body, which is
 * what the server serves in place of content when the session lacks access.
 * Only a success status is examined; 4xx/5xx keep their own classification.
 */
fun isExhentaiUnauthorizedResponse(status: Int, body: ByteArray): Boolean {
    if (status !in 200..299) return false
    if (body.size > SAD_PANDA_MAX_BYTES) return false
    return body.toString(Charsets.ISO_8859_1).isBlank()
}

/**
 * The sad panda response is an empty body (historically a tiny image). A real
 * listing or gallery page is far larger, so a small blank body is unambiguous.
 */
private const val SAD_PANDA_MAX_BYTES = 4096
