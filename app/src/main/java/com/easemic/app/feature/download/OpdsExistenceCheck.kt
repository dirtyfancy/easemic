package com.easemic.app.feature.download

import com.easemic.app.feature.browse.BrowseFeedRepository
import com.easemic.app.importing.opds.OpdsAtomParser
import com.easemic.app.importing.opds.OpdsFeed
import com.easemic.app.importing.opds.OpdsHttpGet
import com.easemic.app.importing.opds.OpdsHttpRequest
import com.easemic.app.importing.opds.OpdsHttpResponse
import com.easemic.app.importing.opds.OpdsLinkRel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

/**
 * #C12 (2026-09-18): does the OPDS server still serve this comic? Measured on
 * the lab servers, no status code answers that on its own — Komga 404s a
 * deleted acquisition, Kavita 500s it, Ubooquity serves its web UI with a 200.
 * So the check reads the catalog, cheapest authoritative signal first:
 *
 *  1. the acquisition URL's PARENT resource (the chapter feed on Kavita):
 *     an Atom feed that still links this acquisition → [Liveness.ALIVE];
 *     401/403 or a network error → [Liveness.UNKNOWN], decided (an auth
 *     problem or an outage is never a verdict); 406 is Kavita's "no such
 *     chapter" shape but another server may 406 a LIVE resource on content
 *     negotiation, so it is only a first signal, confirmed by step 2;
 *  2. the feed the comic was collected from ([DownloadLocator.feedUrl]),
 *     following `rel="next"` up to [maxFeedPages]: an Atom feed listing the
 *     acquisition → ALIVE — from ANY feed. "Not listed" (or the feed itself
 *     404/406/410) is DEAD only when the feed is a path ANCESTOR of the
 *     acquisition (Kavita's series feed over its chapters' downloads; a
 *     Calibre-web `books` feed over `books/<id>/download`): the default Browse
 *     landing is a rolling "latest" view (Komga `books/latest`, Ubooquity
 *     `?latest=true` — 12 entries, no next, no total) that stops listing a
 *     LIVE comic once newer ones push it out, so a non-ancestor feed can only
 *     vouch for life, never pronounce death → UNKNOWN.
 *
 * Like C14's oracle it is consulted at most once per (job, failure). A
 * captive portal / proxy login page answers HTML for BOTH requests, which
 * parses as neither feed → UNKNOWN → the job stays on the transient ladder.
 * Komga's deleted acquisition 404 needs none of this: the classifier seals it
 * and this check can only VETO it (ALIVE from its series feed).
 */
class OpdsExistenceCheck(
    private val httpGet: OpdsHttpGet,
    private val maxFeedPages: Int = DEFAULT_MAX_FEED_PAGES,
    private val parser: OpdsAtomParser = OpdsAtomParser(),
    private val totalDeadlineMs: Long = DEFAULT_TOTAL_DEADLINE_MS,
) {
    suspend fun check(acquisitionUrl: String, feedUrl: String?, authorizationHeader: String?): Liveness =
        withTimeoutOrNull(totalDeadlineMs) {
            // #C12 audit B2: a stalled/throttling catalog must never hold the
            // pump — past [totalDeadlineMs] the whole check abandons to UNKNOWN.
            // Each fetch runs in runInterruptible so a blocked socket read is
            // actually cancellable at the deadline (a plain withContext(IO) +
            // synchronous read is not).
            val target = normalize(acquisitionUrl)
            when (val byParent = parentVerdict(acquisitionUrl, target, authorizationHeader)) {
                is ParentVerdict.Decided -> return@withTimeoutOrNull byParent.liveness
                ParentVerdict.Undecided, ParentVerdict.GoneShaped -> Unit
            }
            val feed = feedUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return@withTimeoutOrNull Liveness.UNKNOWN
            containingFeedVerdict(
                feedUrl = feed,
                target = target,
                auth = authorizationHeader,
                mayPronounceDeath = isPathAncestor(feed, acquisitionUrl),
            )
        } ?: Liveness.UNKNOWN

    private sealed interface ParentVerdict {
        data class Decided(val liveness: Liveness) : ParentVerdict
        /** 406: Kavita's deleted-chapter shape — a signal, not a verdict on its own. */
        data object GoneShaped : ParentVerdict
        data object Undecided : ParentVerdict
    }

    /**
     * A refused credential or an unreachable server DECIDES the check as
     * UNKNOWN — the containing feed would answer the same way, and a second
     * request must never turn an outage into a verdict. A parent that is
     * simply not a feed (Komga's `books/<id>/file` 404, Ubooquity's archive or
     * HTML) hands over to the containing feed; a 406 hands over too, as the
     * first of the two signals a death needs.
     */
    private suspend fun parentVerdict(acquisitionUrl: String, target: String, auth: String?): ParentVerdict {
        val parent = parentOf(acquisitionUrl) ?: return ParentVerdict.Undecided
        val response = fetch(parent, auth) ?: return ParentVerdict.Decided(Liveness.UNKNOWN)
        return when {
            response.code == 406 -> ParentVerdict.GoneShaped
            response.code == 401 || response.code == 403 -> ParentVerdict.Decided(Liveness.UNKNOWN)
            response.code in 200..299 -> {
                val feed = parseFeed(response) ?: return ParentVerdict.Undecided
                if (feed.listsAcquisition(parent, target)) ParentVerdict.Decided(Liveness.ALIVE) else ParentVerdict.Undecided
            }
            else -> ParentVerdict.Undecided
        }
    }

    /**
     * Walks the containing feed. ALIVE from any feed that lists the
     * acquisition; DEAD only when [mayPronounceDeath] — the feed is a path
     * ancestor of the acquisition — else UNKNOWN. #C12 audit B1: "not listed
     * after the last page" is a death only when the walk actually SAW a
     * listing (a page with entries, or a declared result total). A 200 feed
     * that is empty and declares no total is vacuous — a misparse or a
     * momentary blank — and vacuous absence must never pronounce a live comic
     * DEAD, so it downgrades to UNKNOWN. An explicit 404/406/410 on the feed
     * itself is a real "gone" signal, not vacuity, so it is unaffected.
     */
    private suspend fun containingFeedVerdict(feedUrl: String, target: String, auth: String?, mayPronounceDeath: Boolean): Liveness {
        var url: String? = feedUrl
        var pages = 0
        var sawListing = false
        while (url != null && pages < maxFeedPages) {
            pages++
            val response = fetch(url, auth) ?: return Liveness.UNKNOWN
            when {
                response.code == 404 || response.code == 406 || response.code == 410 ->
                    return if (mayPronounceDeath) Liveness.DEAD else Liveness.UNKNOWN
                response.code !in 200..299 -> return Liveness.UNKNOWN
            }
            val feed = parseFeed(response) ?: return Liveness.UNKNOWN
            if (feed.listsAcquisition(url, target)) return Liveness.ALIVE
            sawListing = sawListing || feed.entries.isNotEmpty() || feed.totalResults != null
            val next = feed.links.firstOrNull { it.rel == OpdsLinkRel.NEXT }?.href
                ?: return if (mayPronounceDeath && sawListing) Liveness.DEAD else Liveness.UNKNOWN
            url = BrowseFeedRepository.resolveHref(url, next)
        }
        return Liveness.UNKNOWN
    }

    private suspend fun fetch(url: String, auth: String?): OpdsHttpResponse? = try {
        runInterruptible(Dispatchers.IO) {
            httpGet.get(OpdsHttpRequest(url = url, authorizationHeader = auth, maxBytes = PROBE_MAX_BYTES))
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun parseFeed(response: OpdsHttpResponse): OpdsFeed? =
        runCatching { parser.parse(response.body) }.getOrNull()

    private fun OpdsFeed.listsAcquisition(baseUrl: String, target: String): Boolean =
        entries.any { entry ->
            entry.links.any { link ->
                (link.rel == OpdsLinkRel.ACQUISITION || link.rel == OpdsLinkRel.ACQUISITION_OPEN_ACCESS) &&
                    normalize(BrowseFeedRepository.resolveHref(baseUrl, link.href)) == target
            }
        }

    companion object {
        const val DEFAULT_MAX_FEED_PAGES = 5

        /**
         * #C12 audit B2: the whole check abandons to [Liveness.UNKNOWN] past
         * this wall-clock, so a stalled or throttling catalog can never hold
         * the download pump. Comfortably above two normal small-feed reads.
         */
        const val DEFAULT_TOTAL_DEADLINE_MS = 20_000L

        /** #C12 audit B2: a probe reads at most this much of any one response. */
        const val PROBE_MAX_BYTES = 256 * 1024

        /**
         * The resource the acquisition hangs off: the segment above
         * `download/<file>` (Kavita, Ubooquity), else the segment above the
         * file (Komga's `books/<id>/file`, which 404s and hands over to step 2).
         */
        fun parentOf(acquisitionUrl: String): String? {
            val base = acquisitionUrl.substringBefore('?').substringBefore('#').trimEnd('/')
            val segments = base.split('/')
            if (segments.size < 5) return null
            val cut = if (segments.size >= 6 && segments[segments.size - 2].equals("download", ignoreCase = true)) 2 else 1
            return segments.dropLast(cut).joinToString("/")
        }

        /**
         * #C12 audit M2: canonical identity for comparing an acquisition URL
         * against a feed's link hrefs. Collapses `.`/`..` path segments
         * (java.net.URI.normalize) and decodes percent-escapes (URI.getPath is
         * the decoded path), so `…/a/../download/x.cbz` and `…/download/x%2E cbz`
         * compare equal to `…/download/x.cbz`. Scheme/host/port + decoded path,
         * lowercased; the query is dropped (OPDS acquisitions are path-identified
         * — Kavita/Komga/Ubooquity all measured path-based — and a per-view token
         * in the query must not read the same comic as a different resource).
         * Falls back to the plain lowercased string when the URL will not parse.
         */
        private fun normalize(url: String): String {
            val cleaned = url.trim().replace("&amp;", "&")
            return runCatching {
                val uri = java.net.URI(cleaned).normalize()
                val scheme = uri.scheme ?: return@runCatching cleaned.lowercase()
                val host = uri.host ?: return@runCatching cleaned.lowercase()
                val port = if (uri.port == -1) "" else ":${uri.port}"
                "$scheme://$host$port${uri.path ?: ""}".lowercase()
            }.getOrElse { cleaned.lowercase() }
        }

        /**
         * Is [feedUrl] a path ancestor of [acquisitionUrl] on the same origin —
         * `…/series/5` over `…/series/5/volume/5/chapter/8/download/x.cbz`,
         * `…/opds/books` over `…/opds/books/42/download/x.epub`? Query strings
         * are ignored on the feed side (`?displayFiles=true`), so a feed whose
         * PATH is an ancestor still qualifies; a rolling view (`books/latest`,
         * `comics/?latest=true` over `comics/download/4/…`) never does.
         */
        fun isPathAncestor(feedUrl: String, acquisitionUrl: String): Boolean {
            val feed = feedUrl.trim().substringBefore('?').substringBefore('#').trimEnd('/').lowercase()
            val acq = acquisitionUrl.trim().substringBefore('?').substringBefore('#').lowercase()
            if (feed.isEmpty() || !acq.startsWith("$feed/")) return false
            val feedPath = feed.substringAfter("://", "").substringAfter('/', "")
            // The origin alone (`http://host/`) is nobody's ancestor.
            return feedPath.isNotEmpty()
        }
    }
}
