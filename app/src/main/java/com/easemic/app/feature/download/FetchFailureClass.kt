package com.easemic.app.feature.download

import com.easemic.app.importing.ehentai.EhentaiGalleryVerdict
import com.easemic.app.importing.ehentai.isEhentaiExplicitTakedownWording
import com.easemic.app.importing.ehentai.judgeEhentaiGalleryBody

/**
 * Content-based fetch failure classification per design spec §1.2.
 *
 * Operates on enriched HTTP responses (status + resolved URL + headers + body),
 * mapping playbook symptoms to actionable classes. Replaces the bounded-retry
 * budget (CloudSyncIngestAutoRetry MAX_ATTEMPTS) with indefinite polite looping
 * for transient classes and explicit hard-stop for abuse/quota conditions.
 */
sealed class FetchFailureClass {
    /** Transient jitter: timeout, connection reset, H@H node fail → retry immediately. */
    data object JITTER : FetchFailureClass()

    /**
     * Polite pacing: an actual 429, a REPEATED same-ordinal 5xx within the
     * escalation window, "opening pages too fast" → wait configured seconds.
     * A lone 5xx (no prior same-ordinal 5xx to repeat) is transport jitter —
     * see [classifyFetchFailure] — not PACED.
     */
    data class PACED(val retryAfterSeconds: Int?) : FetchFailureClass()

    /** Quota exhausted (e-h only): 509 gif, image-limit HTML → hard idle until regen. */
    data class QUOTA(val estimatedResumeAtMs: Long?) : FetchFailureClass()

    /** Abuse/ban: IP ban page, CF challenge → surface to user or hard stop. */
    data class ABUSE_STOP(val detail: String) : FetchFailureClass()

    /**
     * Permanent takedown → truncation wind-down. Reached only from an explicit
     * existence verdict, never from a bare status code (#C14):
     *
     *  * e-h: a REPEATED 200 carrying the verified `Gallery not found. …`
     *    sentinel or the removal notice, OR (#C55, 2026-09-21) a FIRST-strike
     *    404 whose body carries that SAME removal notice — measured on the
     *    owner's phone: e-h can answer a removed gallery with 404, not only
     *    200.
     *  * nh: a 404 from a gallery endpoint WHOSE BODY carries the measured
     *    not-found text, authoritative on the first hit.
     *
     * Deliberately NOT permanent, though earlier docs here said otherwise: a
     * bad/stale gallery token (the gallery is alive, the token needs
     * re-resolving), an `expunged` gallery (verified to serve every page), an
     * e-h 404 with no removal wording in its body (an H@H/edge miss — #C14
     * still holds for the absence case: e-h never 404s an absent gallery),
     * and unproven unavailability wording.
     */
    data object GONE : FetchFailureClass()
}

/**
 * Retry-history context for [classifyFetchFailure], populated by the caller
 * (today only [DownloadWorkerLoop], from its own per-(job, ordinal) retry
 * state map) so the classifier can tell a first symptom apart from a repeat
 * of the SAME symptom against the SAME ordinal/manifest. [classifyFetchFailure]
 * itself stays pure and stateless — it never tracks time, counts, or jobs; it
 * only receives this one already-computed boolean.
 *
 * S3 Task 9 (owner bug backlog): rate limiting is per-IP, so "one gallery
 * limited while the next downloads fine" is by construction a
 * misclassification — a transient blip (dropped connection, CDN edge error,
 * one-off 404) must never alone be treated as a server-verdict limit/removal.
 * Only a REPEAT of the same symptom for the same ordinal within a short
 * escalation window earns the harder classification (PACED/GONE); a single
 * occurrence is always JITTER and gets retried. Defaults to "not a repeat" so
 * every pre-existing call site keeps first-hit behavior unless the caller
 * opts in by threading its own retry history through.
 */
data class FetchRetryContext(
    val isRepeatForOrdinal: Boolean = false,
)

/**
 * Classify a fetch failure. All playbook symptoms mapped (design spec §1.2 table,
 * research doc `docs/research/2026-08-29-source-rate-limit-playbook.md`).
 *
 * Content-based: e-h's worst failures arrive as HTTP 200 (509 gif redirect, ban
 * page body), so status alone is insufficient. URL inspection detects redirects
 * to sentinel images; body inspection catches ban pages and CF challenge HTML.
 *
 * Timing/speed NEVER classifies limiting on its own: a slow response, a 0 B/s
 * stall, or a single dropped/errored request is transport jitter (JITTER),
 * not a server verdict. Only an explicit server signal — a repeated 404/5xx
 * against the same ordinal, an actual 429, or e-h's own 200-with-509-gif /
 * ban-text / removed-text markers — may classify PACED/QUOTA/ABUSE_STOP/GONE.
 * See [FetchRetryContext].
 *
 * @param providerNamespace [GalleryIdentityV1] namespace ("ehentai", "nhentai", "opds:*")
 * @param statusCode HTTP status code (0 or <100 = network layer failure, no HTTP response)
 * @param resolvedUrl final URL after redirects (null on network error); used for 509.gif detection
 * @param responseBody byte array (may be empty on network error or HEAD request)
 * @param errorMessage exception message if network-layer throw (null on HTTP success)
 * @param responseHeaders case-insensitive map of HTTP headers (empty if unavailable)
 * @param retryContext caller-supplied retry history; see [FetchRetryContext]
 */
fun classifyFetchFailure(
    providerNamespace: String,
    statusCode: Int,
    resolvedUrl: String?,
    responseBody: ByteArray,
    errorMessage: String?,
    responseHeaders: Map<String, String> = emptyMap(),
    retryContext: FetchRetryContext = FetchRetryContext(),
): FetchFailureClass {
    // Network layer exception (status 0 or <100, no resolvedUrl) → JITTER
    if (statusCode == 0 || statusCode < 100) {
        return FetchFailureClass.JITTER
    }

    // OPDS acquisition URLs are the durable boundary for one catalog item.
    // The acquisition/page transport preserves the upstream status, so an
    // OPDS 404 or 410 is an explicit source verdict rather than the generic
    // repeat-gated jitter rule used for unknown endpoints. This is deliberately
    // scoped to OPDS; a 404 from an e-h image edge or an nhentai image shard
    // must not make a live gallery terminal.
    if (providerNamespace.startsWith(GalleryIdentityV1.OPDS_PREFIX) &&
        (statusCode == 404 || statusCode == 410)
    ) {
        return FetchFailureClass.GONE
    }

    // 404, per source (#C14, measured 2026-09-10 — this rule used to be one
    // status-code check for every source and was wrong at both ends):
    //
    //  * e-h NEVER 404s an ABSENT gallery; it answers 200 with a text
    //    sentinel (see the e-h block below). So an e-h 404 with no removal
    //    wording in its body is an H@H node or edge miss, and sealing on it
    //    would permanently skip a gallery the site still serves. Always
    //    retried, never escalated by repetition.
    //  * nh DOES 404 an absent gallery on both gallery endpoints, and that
    //    first 404 is already authoritative — no repeat needed. Measured on
    //    the GALLERY endpoints only, so an IMAGE shard 404 (a rebalanced CDN
    //    host losing one page, not the gallery) keeps the repeat gate.
    if (statusCode == 404) {
        if (providerNamespace == GalleryIdentityV1.EHENTAI) {
            // #C55 (2026-09-21): the owner's phone measured e-h answering a
            // REMOVED gallery with 404 too — same explicit removal wording
            // (`judgeEhentaiGalleryBody` -> Removed) the 200 path already
            // seals on. That premise gap (#C14 only measured ABSENCE arriving
            // as 200; it said nothing about a removed page arriving as 404)
            // is what let the client throw a bare Http(404) straight past
            // the classifier and retry the takedown forever. An e-h 404 with
            // an empty or unrelated body stays JITTER exactly as before.
            if (responseBody.isNotEmpty()) {
                val bodyText = responseBody.decodeToString(throwOnInvalidSequence = false)
                if (judgeEhentaiGalleryBody(bodyText) == EhentaiGalleryVerdict.Removed) {
                    return FetchFailureClass.GONE
                }
            }
            return FetchFailureClass.JITTER
        }
        if (providerNamespace == GalleryIdentityV1.NHENTAI &&
            isNhentaiGalleryEndpoint(resolvedUrl) &&
            hasNhentaiNotFoundBody(responseBody)
        ) {
            return FetchFailureClass.GONE
        }
        return if (retryContext.isRepeatForOrdinal) FetchFailureClass.GONE else FetchFailureClass.JITTER
    }

    // 429 anywhere → PACED (playbook §1b nh, e-h rarely sends it)
    if (statusCode == 429) {
        // TODO: parse Retry-After header when available
        return FetchFailureClass.PACED(retryAfterSeconds = null)
    }

    // 5xx server errors
    if (statusCode in 500..599) {
        // Special case: nh Cloudflare challenge often arrives as 503
        if (providerNamespace == GalleryIdentityV1.NHENTAI || providerNamespace.startsWith("opds:")) {
            val isCfResponse = responseHeaders.entries.any { (k, v) ->
                k.equals("server", ignoreCase = true) && v.contains("cloudflare", ignoreCase = true)
            }
            if (isCfResponse) {
                val cfMitigated = responseHeaders.entries.any { (k, v) ->
                    k.equals("cf-mitigated", ignoreCase = true) && v.contains("challenge", ignoreCase = true)
                }
                val bodyText = if (responseBody.isNotEmpty()) {
                    responseBody.decodeToString(throwOnInvalidSequence = false)
                } else ""
                val bodyHasChallenge = bodyText.contains("_cf_chl_opt") ||
                        bodyText.contains("jschl-answer") ||
                        bodyText.contains("name=\"captcha-bypass\"")

                if (cfMitigated || bodyHasChallenge) {
                    return FetchFailureClass.ABUSE_STOP("Cloudflare challenge (nh) — WebView solve required")
                }
            }
        }
        // Generic 5xx without CF markers (S3 Task 9, owner bug backlog
        // defect 2): a transient 503/500 on an image/page/manifest fetch
        // used to be an instant PACED park with zero retry — a single blip
        // looked exactly like a real rate limit. Only an explicit server
        // verdict (429, a CF challenge, or a REPEATED same-ordinal 5xx) may
        // park a source; a lone non-Cloudflare 5xx anywhere — e-h, nh, OPDS
        // alike — is transport jitter and retries as JITTER. PACED only
        // once the SAME ordinal 5xx's again within the escalation window (a
        // DIFFERENT ordinal's first 5xx stays JITTER, never counts as a
        // repeat).
        return if (!retryContext.isRepeatForOrdinal) {
            FetchFailureClass.JITTER
        } else {
            FetchFailureClass.PACED(retryAfterSeconds = null)
        }
    }

    // e-h specific: HTTP 200 can carry quota/ban markers
    if (providerNamespace == GalleryIdentityV1.EHENTAI && statusCode == 200) {
        val url = resolvedUrl ?: ""

        // Image quota exhausted: URL ends /509.gif or /509s.gif (playbook §1a)
        if (url.endsWith("/509.gif") || url.endsWith("/509s.gif")) {
            // TODO: compute estimatedResumeAtMs from known regen rate (3-5 pts/min idle)
            return FetchFailureClass.QUOTA(estimatedResumeAtMs = null)
        }

        // IP ban: body contains "temporarily banned" text (playbook §1a)
        if (responseBody.isNotEmpty()) {
            val bodyText = responseBody.decodeToString(throwOnInvalidSequence = false)
            if (bodyText.contains("temporarily banned", ignoreCase = true) ||
                bodyText.contains("Your IP address has been", ignoreCase = true)) {
                // TODO: parse expiry from "expires in X hours Y minutes" regex
                return FetchFailureClass.ABUSE_STOP("e-h IP address temporarily banned (see body for expiry)")
            }

            // Existence verdict (#C14). Read by judgeEhentaiGalleryBody, the
            // one place that interprets an e-h body, so this classifier and
            // detectEhentaiInterstitial cannot disagree about what "gone"
            // looks like. Sealing yields truncation-with-keep on any READY
            // pages already fetched (DownloadWorkerLoop.routeManifestFailure)
            // rather than discarding them.
            //
            // Only NotFound and Removed may seal, and only on a REPEAT: the
            // sentinel's own text says the gallery "may have to wait a short
            // while before it becomes available … might also have been
            // reverted", so one sighting is not a verdict. Everything else —
            // an expunged gallery (alive, verified), a stale token (alive,
            // needs re-resolving) and unproven unavailability wording — is
            // retried, because skipping a living gallery forever is the
            // failure the owner explicitly ruled out.
            return when (judgeEhentaiGalleryBody(bodyText)) {
                // A flat statement of removal, sealed on sight since Task 8.
                EhentaiGalleryVerdict.Removed -> FetchFailureClass.GONE
                // The not-found sentinel hedges in its own words — "you may
                // have to wait a short while before it becomes available …
                // might also have been reverted" — so one sighting is not a
                // verdict. This is also what a freshly posted gallery serves.
                EhentaiGalleryVerdict.NotFound ->
                    if (retryContext.isRepeatForOrdinal) {
                        FetchFailureClass.GONE
                    } else {
                        FetchFailureClass.JITTER
                    }
                // C14: the offensive/content-warning gate is not a death —
                // the account has not cleared a content type. Retried/surfaced,
                // never sealed.
                EhentaiGalleryVerdict.ContentGated -> FetchFailureClass.JITTER
                else -> FetchFailureClass.JITTER
            }
        }
    }

    // Fallback: unclassified success or error → JITTER (retry immediately)
    return FetchFailureClass.JITTER
}

/**
 * C14 (owner decision 2026-09-16): is this GONE classification an EXPLICIT
 * takedown — the site's flat statement that the pages are no longer served
 * (the recorded copyright page `This gallery is unavailable due to a copyright
 * claim by …`, `This gallery is pining for the fjords.`, `This gallery has
 * been removed`)? Whether a rightsholder or the uploader pulled it, the result
 * is the same, so it seals on sight and the liveness oracle is NOT consulted:
 * the gid's gdata metadata record may well still exist, which would otherwise
 * veto a real takedown into a retry-forever.
 *
 * NOT explicit (stays oracle-vetoed): the hedged `Gallery not found.` sentinel
 * ("may have to wait a short while"), and any other "This gallery is
 * unavailable …" wording — removal-class, but an unrecorded variant must not
 * seal a gallery the site still serves without the oracle's say
 * ([isEhentaiExplicitTakedownWording]).
 *
 * Why a real gallery page can never land here: the only e-h → transport
 * conversions that carry a body are `EhentaiException.Interstitial`, which
 * `EhentaiClient.getGalleryPageHandlingInterstitial` throws only after the
 * FULL body failed the `id="gn"`/expunged alive guards — at either status
 * code it recognizes (200, and 404 since #C55, 2026-09-21: the owner's phone
 * measured the same removed-gallery wording arriving under a bare 404).
 * [responseBody] is the retained sample (≤ [FetchTransportException.BODY_SAMPLE_LIMIT]);
 * a sample AT the cap may be truncated and is not trusted to skip the oracle.
 * Only e-h carries body verdicts; an nh 404 is decided by its own endpoint
 * and stays vetoable.
 */
fun isExplicitTakedown(providerNamespace: String, statusCode: Int, responseBody: ByteArray): Boolean {
    if (providerNamespace != GalleryIdentityV1.EHENTAI ||
        (statusCode != 200 && statusCode != 404) ||
        responseBody.isEmpty()
    ) {
        return false
    }
    if (responseBody.size >= FetchTransportException.BODY_SAMPLE_LIMIT) return false
    val body = responseBody.decodeToString(throwOnInvalidSequence = false)
    return isEhentaiExplicitTakedownWording(body)
}

/**
 * Single source of truth for the failure-class string persisted via
 * [DownloadWorkerHost.recordFailure] / [DownloadJob.failureClass]. Replaces
 * hand-written "JITTER"/"PACED"/"QUOTA"/"ABUSE_STOP"/"GONE" literals that used
 * to be scattered across the worker loop and its tests.
 */
fun FetchFailureClass.label(): String = when (this) {
    is FetchFailureClass.JITTER -> "JITTER"
    is FetchFailureClass.PACED -> "PACED"
    is FetchFailureClass.QUOTA -> "QUOTA"
    is FetchFailureClass.ABUSE_STOP -> "ABUSE_STOP"
    is FetchFailureClass.GONE -> "GONE"
}

/**
 * Whether [resolvedUrl] is one of nhentai's GALLERY endpoints, where a 404 was
 * measured to be authoritative (`/g/<id>/` and `/api/v2/galleries/<id>`), as
 * opposed to an image shard on `i*.nhentai.net`.
 *
 * The trap worth spelling out: the SINGULAR `/api/v2/gallery/<id>` 404s even
 * for galleries that are alive. Matching the plural path segment is what keeps
 * that from mass-misjudging live galleries as dead. A null/unknown URL is
 * treated as not-a-gallery-endpoint, so it falls back to the repeat gate
 * instead of sealing on one response.
 */
private fun isNhentaiGalleryEndpoint(resolvedUrl: String?): Boolean {
    val url = resolvedUrl ?: return false
    if (url.contains("/api/v2/galleries/")) return true
    return Regex("//(?:www\\.)?nhentai\\.net/g/\\d+/?$").containsMatchIn(url)
}

/**
 * Whether a 404 body is nhentai's own not-found answer rather than a bare edge
 * miss.
 *
 * The distinction the measurement actually supports: probing an absent id was
 * observed to yield 404, which makes 404 NECESSARY for death but not
 * SUFFICIENT — a rebalanced CDN or edge hiccup can 404 a gallery that is very
 * much alive, and it does so with an empty body. Requiring the text keeps a
 * transient bodiless 404 on the repeat gate, where one extra retry is the whole
 * cost, instead of sealing a live gallery on a single response.
 */
private fun hasNhentaiNotFoundBody(responseBody: ByteArray): Boolean {
    if (responseBody.isEmpty()) return false
    val body = responseBody.decodeToString(throwOnInvalidSequence = false)
    return body.contains("Gallery not found", ignoreCase = true) ||
        body.contains("Error 404", ignoreCase = true)
}
