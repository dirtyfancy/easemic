package com.easemic.app.storage

/**
 * User-Agent for every image this loader fetches, for any source.
 *
 * It used to read `(nhentai; polite)`, which was sent to e-hentai.org, OPDS
 * servers and R2 alike — a mislabel on every request but one source's. Naming
 * no source is both accurate and less identifying.
 */
internal const val PAGE_IMAGE_USER_AGENT = "PrivateComicLibrary/1.0 (polite)"

/**
 * Hosts that own the E-Hentai session, and so are the only hosts allowed to
 * receive it. exhentai.org serves a blank placeholder instead of the cover when
 * the request arrives without `ipb_member_id`/`ipb_pass_hash`/`igneous`, which
 * is why covers were coming up empty.
 */
private val SESSION_HOSTS = listOf("exhentai.org", "e-hentai.org")

/**
 * The session cookie header to attach for [host], or null to send none.
 *
 * Deliberately an allowlist of exact hosts and their subdomains: an OPDS
 * server, an R2 bucket, a Hath image node or nhentai must never be handed this
 * user's credentials. Note the subdomain check requires a `.` boundary, so
 * `evil-exhentai.org` — which a naive `endsWith` would accept — is refused.
 */
internal fun sessionCookieForImageHost(host: String, sessionHeader: String?): String? {
    val session = sessionHeader?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val normalized = host.trim().lowercase().removeSuffix(".")
    val owned = SESSION_HOSTS.any { normalized == it || normalized.endsWith(".$it") }
    return if (owned) session else null
}
