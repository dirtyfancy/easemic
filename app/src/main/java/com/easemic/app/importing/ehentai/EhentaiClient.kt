package com.easemic.app.importing.ehentai

import com.easemic.app.feature.providers.EhentaiAccount
import com.easemic.app.feature.providers.isExhentaiUnauthorizedResponse
import com.easemic.app.importing.CatalogRequestGate
import com.easemic.app.net.FirstByteDeadline
import com.easemic.app.net.SourceHttp
import com.easemic.app.net.stallReadTimeoutMs
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class EhentaiHttpResponse(
    val code: Int,
    val body: ByteArray,
    /** Final URL after redirects (for e-h /509.gif detection). Null when unavailable. */
    val resolvedUrl: String? = null,
    /** Response headers, lowercased keys (for Retry-After, cf-mitigated). */
    val headers: Map<String, String> = emptyMap(),
)

fun interface EhentaiHttpGet {
    fun get(url: String): EhentaiHttpResponse
}

/** Raw response metadata for the disk-backed H@H image transfer. */
data class EhentaiTransferResponse(
    val code: Int,
    val bytesWritten: Long,
    val transferredBytes: Long = bytesWritten,
    val expectedBytes: Long? = null,
    val resolvedUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

/** Injectable transfer seam used by the download adapter and its tests. */
typealias EhentaiHttpTransfer = suspend (
    url: String,
    destination: Path,
    offset: Long,
    cookieHeader: String?,
    firstByteMs: Long,
    floorWindowMs: Long,
    floorBytes: Long,
) -> EhentaiTransferResponse

/** Invalid range/protocol metadata, which the mapping layer turns into a bad-page failure. */
class EhentaiTransferProtocolException(message: String) : IOException(message)

/**
 * #E19 Task 4: this attempt's own rate projects past the H@H node's 180 s
 * session cap, so the node would cut the socket around second ~180 and the
 * adapter would spend the same `?nl=` reload anyway. Thrown at ~3 s instead:
 * quota-neutral (no reload beyond the one the doomed page already owed) and
 * the lane frees ~60x sooner. [bytesWritten] is what is already on disk, so
 * the adapter can resume the prefix after rotating to another node. An
 * [IOException], so if it ever escapes the adapter it classifies as
 * `statusCode=0` -> JITTER exactly like any other network-layer failure.
 */
class EhentaiSlowNodeException(val bytesWritten: Long, message: String) : IOException(message)

/** JSON POST seam for the `api.php` calls (showpage). */
fun interface EhentaiHttpPost {
    fun post(url: String, body: ByteArray, contentType: String): EhentaiHttpResponse
}

// Capture groups for the H@H node-reload link (Task 9 defect 4, owner bug
// backlog). href is checked first — it is the more specific/reliable form;
// onclick is the fallback for markup that only carries the JS handler.
private val RELOAD_HREF_REGEX = Regex("""href="[^"]*\?nl=([0-9a-zA-Z_-]+)"""", RegexOption.IGNORE_CASE)
private val RELOAD_ONCLICK_REGEX = Regex("""onclick="return nl\('([0-9a-zA-Z_-]+)'\)"""", RegexOption.IGNORE_CASE)

/**
 * Extracts the H@H node-reload token from a gallery-page-view HTML body —
 * the "click here if the image fails loading" link e-hentai embeds on every
 * page view, which resolves the image from a different H@H node than the
 * one the current page already points at. Returns null when no such link is
 * present (e.g. an interstitial page, or markup e-h has changed) — callers
 * then fall back to plain same-node retries.
 */
fun parseEhentaiReloadToken(html: String): String? =
    RELOAD_HREF_REGEX.find(html)?.groupValues?.getOrNull(1)
        ?: RELOAD_ONCLICK_REGEX.find(html)?.groupValues?.getOrNull(1)

/**
 * Task 9 defect 6 (owner bug backlog): detects a live-but-stalled transfer
 * FASTER than the outer absolute deadline
 * ([com.easemic.app.feature.download.DownloadWorkerLoop]'s
 * 90s/120s `withTimeout` wrapping the whole fetch) — a connection that keeps
 * the socket alive with a slow trickle (or nothing at all) below a minimum
 * throughput floor within any rolling window is aborted early, so JITTER
 * backoff/retry (Task 9 defect 5) kicks in sooner than waiting out the whole
 * outer deadline. A plain flat per-read socket timeout alone cannot catch a
 * "send one byte just often enough to keep resetting the timeout" trickle;
 * this tracks bytes accumulated within a rolling window instead.
 *
 * Pure state machine driven entirely by (nowMs, bytesRead) pairs supplied by
 * the caller — no I/O, no real clock, so it is fully unit-testable without a
 * socket. Per [FetchFailureClass][com.easemic.app.feature.download.FetchFailureClass]'s
 * classification principle, this is timing/throughput, so it NEVER produces
 * anything but a plain IO failure — only an explicit server verdict may
 * classify limiting (PACED/QUOTA/ABUSE_STOP). The caller ([defaultGetWithStallWatchdog]
 * / [readAllBytesWithStallWatchdog] — plain [defaultGet] never touches this
 * watchdog at all) turns an [onProgress] abort into a network-layer
 * [IOException], which [EhentaiClient.get] launders into
 * [EhentaiException.Parse] exactly like any other connection failure — that
 * maps to `statusCode=0` -> JITTER, automatically, with no separate
 * classification path to keep in sync.
 */
class StallWatchdog(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val minBytesPerWindow: Long = DEFAULT_MIN_BYTES_PER_WINDOW,
    /**
     * #C38/D7 (2026-09-15): how long to wait for the FIRST byte before
     * declaring a dead connection, SEPARATE from the throughput [windowMs].
     * A dead H@H node that never sends is dead in a few seconds; the old
     * 15 s (== windowMs) meant every dead node cost a full throughput window
     * before the abort. The post-first-byte trickle floor stays on [windowMs]
     * so a slow-but-live stream is still judged over a generous window.
     * Defaults to [windowMs] so pre-#C38 two-arg callers keep the old timing.
     */
    private val noFirstByteMs: Long = windowMs,
) {
    private var sawFirstByte = false
    private var waitingSinceMs: Long? = null
    private var windowStartMs: Long = 0L
    private var bytesInWindow: Long = 0L

    /**
     * Call once per read-loop iteration with the bytes just read (0 is a
     * valid "no progress this poll" heartbeat, e.g. after a caught
     * [SocketTimeoutException]) and the current clock. Returns a
     * human-readable abort reason once the transfer should be treated as
     * stalled, else null — once it returns non-null it keeps returning
     * non-null (the caller is expected to abort immediately on first
     * non-null, so further calls are not a normal code path).
     */
    fun onProgress(nowMs: Long, bytesRead: Int): String? {
        require(bytesRead >= 0) { "bytesRead must be >= 0, was $bytesRead" }
        if (!sawFirstByte) {
            if (bytesRead > 0) {
                sawFirstByte = true
                windowStartMs = nowMs
                bytesInWindow = bytesRead.toLong()
                return null
            }
            val since = waitingSinceMs ?: nowMs.also { waitingSinceMs = it }
            return if (nowMs - since >= noFirstByteMs) {
                "no first byte within ${noFirstByteMs}ms"
            } else {
                null
            }
        }
        bytesInWindow += bytesRead
        val elapsed = nowMs - windowStartMs
        if (elapsed < windowMs) return null
        if (bytesInWindow < minBytesPerWindow) {
            return "stalled: only $bytesInWindow bytes in ${elapsed}ms (floor $minBytesPerWindow bytes / ${windowMs}ms)"
        }
        // Floor met for this window — roll forward to a fresh one.
        windowStartMs = nowMs
        bytesInWindow = 0L
        return null
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 15_000L
        const val DEFAULT_MIN_BYTES_PER_WINDOW = 64 * 1024L

        /**
         * #C38/D7: the production first-byte deadline for the page-image path.
         * Well below [DEFAULT_WINDOW_MS] so a dead H@H node aborts in ~5 s and
         * the loop's in-place retry rotates to another node, instead of paying
         * a 15 s window per dead node.
         */
        const val DEFAULT_NO_FIRST_BYTE_MS = 5_000L
    }
}

sealed class EhentaiException(message: String) : Exception(message) {
    class Http(val code: Int) : EhentaiException("EHENTAI_HTTP_$code")
    class RateLimited : EhentaiException("EHENTAI_HTTP_429")
    class Parse(message: String = "EHENTAI_PARSE") : EhentaiException(message)

    /**
     * W7: exhentai.org answered 200 with the blank "sad panda" body — the
     * session is not authorized for this origin. Distinguished from
     * [Parse] so the load-failure seam can surface the honest sign-in-again
     * copy instead of an empty manifest; the e-hentai origin never produces
     * this (its anonymous listings are served without a session).
     */
    class ExhentaiUnauthorized :
        EhentaiException("EXHENTAI_UNAUTHORIZED")

    /**
     * A gallery-page fetch that returned HTTP 200 HTML which is NOT gallery
     * markup, but one of the two interstitials with an explicit server
     * verdict: an IP ban page or a "gallery removed" notice (Task 8, owner
     * bug #6). Carries the full raw [response] so [ehentaiErrorToTransport]
     * can launder it into a [FetchTransportException][com.easemic.app.feature.download.FetchTransportException]
     * with the real status/url/headers/body — the same pipeline every other
     * e-h failure goes through, rather than a parallel classifier.
     */
    class Interstitial(val response: EhentaiHttpResponse) :
        EhentaiException("EHENTAI_INTERSTITIAL_${response.code}")
}

/**
 * Front-catalog HTTP client. Default origin is anonymous e-hentai.org;
 * optional on-device cookies switch to exhentai.org. Tokens/cookies stay off
 * error surfaces where practical.
 */
class EhentaiClient(
    val origin: String = DEFAULT_ORIGIN,
    private val httpGet: EhentaiHttpGet = EhentaiHttpGet { url -> defaultGet(url) },
    private val httpPost: EhentaiHttpPost = EhentaiHttpPost { url, body, contentType ->
        defaultPost(url, body, contentType)
    },
    private val listParser: EhentaiListParser = EhentaiListParser(),
    private val galleryParser: EhentaiGalleryParser = EhentaiGalleryParser(),
    private val beforeRequest: () -> Unit = {},
    private val rateLimitSleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) },
    val maxConcurrentPageFetches: Int = DEFAULT_MAX_CONCURRENT_PAGE_FETCHES,
) {
    fun fetchLatest(pageIndex: Int = 0): EhentaiListPage =
        fetchList(EhentaiListQuery(pageIndex = pageIndex))

    fun fetchSearch(query: String, pageIndex: Int = 0): EhentaiListPage =
        fetchList(EhentaiListQuery(fSearch = query, pageIndex = pageIndex))

    fun fetchList(query: EhentaiListQuery): EhentaiListPage =
        fetchListPath(query.toPath())

    /**
     * #E117: Fetches one page of the signed-in user's favorites (`favorites.php`).
     * Supports seek pagination (`?next=<gid>`) and legacy page index (`?page=<index>`).
     * The returned HTML uses the same gallery table markup as standard list pages.
     */
    fun fetchFavoritesPage(
        nextSeekGid: Long? = null,
        pageIndex: Int? = null,
    ): EhentaiListPage {
        val params = buildList {
            if (nextSeekGid != null) {
                add("next=$nextSeekGid")
            } else if (pageIndex != null && pageIndex > 0) {
                add("page=$pageIndex")
            }
        }
        val path = if (params.isEmpty()) {
            "/favorites.php"
        } else {
            "/favorites.php?" + params.joinToString("&")
        }
        return fetchListPath(path)
    }

    /**
     * #E117: Adds or removes a gallery in the signed-in user's ExHentai / E-Hentai
     * favorites via `POST /gallerypopups.php?gid=<gid>&t=<token>&act=addfav`.
     *
     * @param favcat `"0"`..`"9"` to add to a favorite slot, or `"favdel"` to remove.
     */
    fun updateFavorite(
        galleryId: Long,
        token: String,
        favcat: String = FAVORITE_SLOT_DEFAULT,
        favnote: String = "",
    ) {
        beforeRequest()
        val encodedFavcat = java.net.URLEncoder.encode(favcat, "UTF-8")
        val encodedNote = java.net.URLEncoder.encode(favnote, "UTF-8")
        val formBody = "favcat=$encodedFavcat&favnote=$encodedNote&submit=Apply+Changes&update=1"
            .toByteArray(Charsets.UTF_8)
        val url = "${origin.trimEnd('/')}/gallerypopups.php?gid=$galleryId&t=$token&act=addfav"
        val response = try {
            httpPost.post(url, formBody, "application/x-www-form-urlencoded")
        } catch (error: Exception) {
            throw EhentaiException.Parse(redactUrl(error.message ?: "EHENTAI_NETWORK"))
        }
        when {
            response.code == 429 -> throw EhentaiException.RateLimited()
            response.code !in 200..399 -> throw EhentaiException.Http(response.code)
        }
    }

    fun fetchGallery(galleryId: Long, token: String): EhentaiGalleryPlan {
        val first = fetchGalleryThumbPage(galleryId = galleryId, token = token, thumbPageIndex = 0)
        val paths = LinkedHashSet(first.pageViewPaths)
        val thumbs = first.previewThumbs.toMutableList()
        val declared = first.declaredPageCount
        val lastIndex = lastThumbPageIndexToFetch(
            declaredPageCount = declared,
            firstGridCount = first.pageViewPaths.size,
            linkedHighestIndex = first.highestThumbPageIndex,
        )
        var p = 1
        while (p <= lastIndex && p <= MAX_THUMB_PAGES) {
            if (declared != null && paths.size >= declared) break
            mergeThumbPage(
                page = fetchGalleryThumbPage(galleryId, token, p),
                paths = paths,
                thumbs = thumbs,
            )
            p++
        }
        while (declared != null && paths.size < declared && p <= MAX_THUMB_PAGES) {
            val before = paths.size
            mergeThumbPage(
                page = fetchGalleryThumbPage(galleryId, token, p),
                paths = paths,
                thumbs = thumbs,
            )
            if (paths.size == before) break
            p++
        }
        // SEND_BACK item 9 (owner bug backlog): pagination can legitimately
        // give up early (a page yields no NEW paths, or MAX_THUMB_PAGES is
        // hit) while the gallery's own "Length:" field still declares more
        // pages exist. Returning a plan whose pageViewPaths undercounts its
        // OWN declared total used to flow straight into
        // FrontDownloadPageAdapter.fetchManifest's expectedPageCount, and
        // from there into DownloadWorkerLoop's PAGE_COUNT_MISMATCH
        // machinery — misclassifying a transient pagination shortfall as a
        // genuine server-side shrink and risking a silent re-pin down.
        // Throwing here instead is laundered by ehentaiErrorToTransport into
        // a network-layer (statusCode=0) failure, which classifyFetchFailure
        // treats as JITTER — DownloadWorkerLoop parks and retries the
        // manifest fetch on its next tick rather than trusting a truncated
        // plan. A genuine server-side shrink is unaffected: the NEXT fetch's
        // own declared Length is used as-is once pagination matches it, so
        // the PAGE_COUNT_MISMATCH machinery only ever sees a re-pin caused
        // by the declared Length itself being lower, never by pagination
        // falling short of that same call's own declared Length.
        if (declared != null && paths.size < declared) {
            throw EhentaiException.Parse(
                "EHENTAI_SHORT_MANIFEST: gallery $galleryId paged to ${paths.size}/$declared pages",
            )
        }
        return first.copy(
            pageViewPaths = paths.toList(),
            previewThumbs = thumbs,
        )
    }

    /**
     * Fetches one thumbnail-grid page without walking the rest of the gallery.
     * Grid index zero is the gallery URL itself; later grids use `?p=`.
     */
    fun fetchGalleryGridPage(
        galleryId: Long,
        token: String,
        gridIndex: Int,
    ): EhentaiGalleryPlan {
        require(gridIndex >= 0) { "gridIndex must be >= 0" }
        return fetchGalleryThumbPage(
            galleryId = galleryId,
            token = token,
            thumbPageIndex = gridIndex,
        )
    }

    fun fetchPageImage(pageViewPath: String, pageNumber: Int): EhentaiPageImage {
        val path = if (pageViewPath.startsWith("/")) pageViewPath else "/$pageViewPath"
        val response = get(path)
        return try {
            galleryParser.parsePageImage(response.body, pageNumber = pageNumber)
        } catch (error: Exception) {
            // #C75 (C38 D11a): the IP-ban page is served on page views too. Surface it as the same
            // Interstitial the gallery page throws (→ ABUSE_STOP), not a Parse (→ JITTER, keep fetching).
            // Only the ban verdict: a page view has no gallery markup, so the other detections would misfire.
            if (detectEhentaiInterstitial(response.body.toString(Charsets.UTF_8)) == EhentaiInterstitial.Banned) {
                throw EhentaiException.Interstitial(response)
            }
            throw EhentaiException.Parse(error.message ?: "EHENTAI_PARSE")
        }
    }

    /**
     * Resolves one page's image through the `api.php` `showpage` JSON call —
     * what the site's own reader and EhViewer use for every page after the
     * first. It is a small JSON round-trip, not a main-site page view, so it
     * does not feed the "opening pages too fast" counter the way a `/s/` HTML
     * fetch per page does (research 2026-09-10; live-verified request/response
     * shape the same day). [imgKey] is the page's own `/s/<imgkey>/<gid>-<n>`
     * token; [showKey] comes from any page-view HTML of this gallery
     * ([EhentaiPageImage.showKey]). A rejected pair answers
     * `{"error":"Key mismatch"}`, surfaced as [EhentaiException.Parse] so the
     * caller can fall back to the HTML page view and relearn the showkey.
     */
    fun fetchPageImageViaApi(
        galleryId: Long,
        pageNumber: Int,
        imgKey: String,
        showKey: String,
    ): EhentaiPageImage {
        beforeRequest()
        val body = org.json.JSONObject()
            .put("method", "showpage")
            .put("gid", galleryId)
            .put("page", pageNumber)
            .put("imgkey", imgKey)
            .put("showkey", showKey)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val response = try {
            httpPost.post(apiEndpoint(), body, "application/json")
        } catch (error: Exception) {
            throw EhentaiException.Parse(redactUrl(error.message ?: "EHENTAI_NETWORK"))
        }
        when {
            response.code == 429 -> throw EhentaiException.RateLimited()
            response.code !in 200..299 -> throw EhentaiException.Http(response.code)
        }
        return try {
            galleryParser.parsePageImageFromApi(
                response.body.toString(Charsets.UTF_8),
                pageNumber = pageNumber,
            )
        } catch (error: Exception) {
            throw EhentaiException.Parse(error.message ?: "EHENTAI_PARSE")
        }
    }

    /** `api.e-hentai.org` for the public origin, `s.exhentai.org` for the members mirror. */
    fun apiEndpoint(): String =
        if (origin.trimEnd('/').endsWith("exhentai.org")) EXHENTAI_API_ENDPOINT else DEFAULT_API_ENDPOINT

    private fun fetchGalleryThumbPage(
        galleryId: Long,
        token: String,
        thumbPageIndex: Int,
    ): EhentaiGalleryPlan {
        val path = if (thumbPageIndex <= 0) {
            "/g/$galleryId/$token/"
        } else {
            "/g/$galleryId/$token/?p=$thumbPageIndex"
        }
        val response = getGalleryPageHandlingInterstitial(path)
        return try {
            galleryParser.parseGallery(response.body, galleryId = galleryId, token = token)
        } catch (error: Exception) {
            throw EhentaiException.Parse(error.message ?: "EHENTAI_PARSE")
        }
    }

    /**
     * Wraps [get] with e-h gallery-page interstitial detection (Task 8, owner
     * bug #6) BEFORE the caller hands the body to [EhentaiGalleryParser] —
     * previously a ban/removed/content-warning/login page just produced an
     * empty parse with no recorded reason. [EhentaiInterstitial.Banned] and
     * [EhentaiInterstitial.Removed] carry an explicit server verdict, so they
     * throw [EhentaiException.Interstitial] to go through the same
     * `ehentaiErrorToTransport` -> `classifyFetchFailure` pipeline as every
     * other e-h failure. [EhentaiInterstitial.ContentWarning] is bypassable —
     * a single `nw=session` re-fetch is attempted ([allowContentWarningRetry]
     * bounds this to exactly one retry so a page that keeps showing Content
     * Warning after the bypass still terminates instead of looping).
     * [EhentaiInterstitial.EmptyOrLogin] has no server verdict to attach to,
     * so it throws the standalone [EhentaiManifestEmptyException] instead —
     * deliberately NOT an [EhentaiException], so it bypasses classification
     * entirely and the caller's `MANIFEST_ERROR` fallback records the page's
     * own text as the failure detail.
     */
    private fun getGalleryPageHandlingInterstitial(
        path: String,
        allowContentWarningRetry: Boolean = true,
    ): EhentaiHttpResponse {
        // #C55 (2026-09-21): the removed-gallery notice can also arrive under
        // a bare HTTP 404 (measured on the owner's phone) rather than the 200
        // this method otherwise expects — checkRemovedInterstitialOn404 lets
        // [get] inspect a 404 body for exactly that one verdict BEFORE it
        // throws the ordinary Http(code), scoped to the gallery-page fetch
        // only (see [get]'s KDoc).
        val response = get(path, checkRemovedInterstitialOn404 = true)
        val html = response.body.toString(Charsets.UTF_8)
        return when (detectEhentaiInterstitial(html)) {
            EhentaiInterstitial.Banned, EhentaiInterstitial.Removed ->
                throw EhentaiException.Interstitial(response)
            EhentaiInterstitial.ContentWarning -> if (allowContentWarningRetry) {
                val retryPath = path + (if (path.contains("?")) "&nw=session" else "?nw=session")
                getGalleryPageHandlingInterstitial(retryPath, allowContentWarningRetry = false)
            } else {
                throw EhentaiManifestEmptyException(html.take(300))
            }
            EhentaiInterstitial.EmptyOrLogin ->
                throw EhentaiManifestEmptyException(html.take(300))
            null -> response
        }
    }

    private fun fetchListPath(path: String): EhentaiListPage {
        val response = get(path)
        // W7 break #2: on the exhentai origin a 200 listing that is blank
        // is the measured sad-panda refusal, not a real empty catalog —
        // surface it as an explicit auth failure so the caller can say
        // "sign in again in Settings" instead of "no galleries". The
        // e-hentai origin (including /toplist.php, which exhentai.org does
        // not host) never gets this treatment.
        if (effectiveOriginFor(path).endsWith("exhentai.org") &&
            isExhentaiUnauthorizedResponse(response.code, response.body)
        ) {
            throw EhentaiException.ExhentaiUnauthorized()
        }
        return try {
            listParser.parse(response.body)
        } catch (error: Exception) {
            throw EhentaiException.Parse(error.message ?: "EHENTAI_PARSE")
        }
    }

    private fun effectiveOriginFor(path: String): String {
        val base = origin.trimEnd('/')
        return if (path.startsWith("/toplist.php") && base.endsWith("exhentai.org")) {
            DEFAULT_ORIGIN
        } else {
            base
        }
    }

    /**
     * @param checkRemovedInterstitialOn404 #C55 (2026-09-21): the owner's
     * phone measured e-h answering a removed gallery with HTTP 404 carrying
     * the SAME removal wording the 200 path already recognizes — this used
     * to be indistinguishable from an ordinary H@H/edge-miss 404 because
     * [EhentaiException.Http] was thrown before the body was ever inspected.
     * Scoped to exactly the gallery-page fetch ([getGalleryPageHandlingInterstitial]):
     * default `false` keeps [fetchPageImage] and [fetchListPath] byte-for-byte
     * unchanged — a bare non-2xx there is still always [EhentaiException.Http],
     * never body-sniffed.
     */
    private fun get(path: String, checkRemovedInterstitialOn404: Boolean = false): EhentaiHttpResponse {
        var attempt = 0
        while (true) {
            beforeRequest()
            val url = effectiveOriginFor(path) + path
            val response = try {
                httpGet.get(url)
            } catch (error: Exception) {
                throw EhentaiException.Parse(redactUrl(error.message ?: "EHENTAI_NETWORK"))
            }
            when {
                response.code == 429 || response.code == 503 -> {
                    if (attempt >= 1) {
                        // S3 Task 9 defect 1 (owner bug backlog): 429 is an
                        // explicit server rate-limit verdict, but 503 is not
                        // — it's a generic 5xx (bad gateway / maintenance /
                        // transient infra error) that must flow through the
                        // ordinary Http(code) -> classifyFetchFailure 5xx
                        // path (JITTER-then-PACED escalation), not be
                        // collapsed into the same hard RateLimited() as 429.
                        throw if (response.code == 429) {
                            EhentaiException.RateLimited()
                        } else {
                            EhentaiException.Http(response.code)
                        }
                    }
                    attempt += 1
                    rateLimitSleeper(RATE_LIMIT_RETRY_MS)
                }
                // #C55: a bare 404 stays Http(404) below (unchanged) unless
                // the caller opted in AND the body itself carries the
                // explicit removal verdict — an ordinary bodyless/unrelated
                // 404 never takes this branch.
                checkRemovedInterstitialOn404 && response.code == 404 &&
                    detectEhentaiInterstitial(response.body.toString(Charsets.UTF_8)) == EhentaiInterstitial.Removed ->
                    throw EhentaiException.Interstitial(response)
                response.code !in 200..299 -> throw EhentaiException.Http(response.code)
                else -> return response
            }
        }
    }

    companion object {
        const val DEFAULT_ORIGIN = "https://e-hentai.org"
        const val EXHENTAI_ORIGIN = "https://exhentai.org"
        const val DEFAULT_API_ENDPOINT = "https://api.e-hentai.org/api.php"
        const val EXHENTAI_API_ENDPOINT = "https://s.exhentai.org/api.php"
        const val DEFAULT_MAX_CONCURRENT_PAGE_FETCHES = 2
        const val FAVORITE_SLOT_DEFAULT = "0"
        const val FAVORITE_REMOVE = "favdel"
        private const val RATE_LIMIT_RETRY_MS = 1_500L
        private const val MAX_THUMB_PAGES = 200

        private fun lastThumbPageIndexToFetch(
            declaredPageCount: Int?,
            firstGridCount: Int,
            linkedHighestIndex: Int,
        ): Int {
            val fromLength = if (declaredPageCount != null && firstGridCount > 0 &&
                firstGridCount < declaredPageCount
            ) {
                (declaredPageCount - 1) / firstGridCount
            } else {
                0
            }
            return maxOf(linkedHighestIndex, fromLength)
        }

        private fun mergeThumbPage(
            page: EhentaiGalleryPlan,
            paths: LinkedHashSet<String>,
            thumbs: MutableList<EhentaiPreviewThumb>,
        ) {
            paths.addAll(page.pageViewPaths)
            thumbs += page.previewThumbs
        }

        // Capture groups — Android ICU rejects unbounded lookbehind (`(?<=/g/\d+/)`).
        private val GALLERY_TOKEN = Regex("""(/g/\d+/)([0-9a-fA-F]+)""")
        private val PAGE_TOKEN = Regex("""(/s/)([0-9a-zA-Z]+)(/\d+-\d+)""")

        fun fromAccount(
            account: EhentaiAccount,
            beforeRequest: () -> Unit = { CatalogRequestGate.Shared.acquire() },
        ): EhentaiClient {
            val cookie = account.normalizedCookie()
            val origin = if (account.effectiveUseExhentai()) EXHENTAI_ORIGIN else DEFAULT_ORIGIN
            return EhentaiClient(
                origin = origin,
                httpGet = { url -> defaultGet(url, cookieHeader = cookie) },
                httpPost = { url, body, contentType ->
                    defaultPost(url, body, contentType, cookieHeader = cookie)
                },
                beforeRequest = beforeRequest,
            )
        }

        fun redactUrl(url: String): String =
            GALLERY_TOKEN.replace(url) { match ->
                match.groupValues[1] + "<redacted>"
            }.let { partially ->
                PAGE_TOKEN.replace(partially) { match ->
                    match.groupValues[1] + "<redacted>" + match.groupValues[3]
                }
            }

        /**
         * The shared e-h transfer client (#E19): seeded by [SourceHttp] so the
         * family-preference Dns and its connect listener apply, with the exact
         * HttpURLConnection-era timeouts (connect 15s / read 30s). `callTimeout`
         * stays unset — total duration stays owned by the transfer budget and the
         * callers' outer deadlines. Per-call read timeouts derive from it with
         * `newBuilder()`, which shares the connection pool.
         */
        private val sourceClient: OkHttpClient by lazy {
            SourceHttp.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build()
        }

        /**
         * Plain GET — no [StallWatchdog], full 30s read timeout. SEND_BACK
         * item 4 (owner bug backlog): this used to silently accept
         * `stallWindowMs`/`stallMinBytesPerWindow` overrides that, by
         * DEFAULT, halved every caller's read timeout (30s -> 15s) and
         * applied the 64 KiB/15s throughput floor to every single
         * [defaultGet] call site (list pages, gallery manifests, imports,
         * page views, ...) regardless of whether that caller actually
         * wanted early-abort-on-stall. Only [defaultGetWithStallWatchdog]
         * (a separate, explicit entry point) carries that behavior now.
         *
         * Runs on the shared [sourceClient] unless a client is injected —
         * the #E19 Dns seam. Redirects are followed and [EhentaiHttpResponse.resolvedUrl]
         * is the final URL, as before.
         */
        fun defaultGet(
            url: String,
            cookieHeader: String? = null,
            client: OkHttpClient = sourceClient,
        ): EhentaiHttpResponse {
            val builder = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,image/*,*/*;q=0.8")
            cookieHeader?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.header("Cookie", it) }
            return client.newCall(builder.build()).execute().use { response ->
                EhentaiHttpResponse(
                    code = response.code,
                    body = response.body?.bytes() ?: ByteArray(0),
                    resolvedUrl = response.request.url.toString(),
                    headers = flattenHeaders(response.headers),
                )
            }
        }

        /** JSON POST for `api.php` (showpage, and C14 gdata liveness). Same UA/cookie posture as [defaultGet]. */
        fun defaultPost(
            url: String,
            body: ByteArray,
            contentType: String,
            cookieHeader: String? = null,
            client: OkHttpClient = sourceClient,
        ): EhentaiHttpResponse {
            val builder = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json,*/*;q=0.8")
                // OkHttp sets Content-Length from the body itself.
                .post(body.toRequestBody(contentType.toMediaType()))
            cookieHeader?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.header("Cookie", it) }
            return client.newCall(builder.build()).execute().use { response ->
                EhentaiHttpResponse(
                    code = response.code,
                    body = response.body?.bytes() ?: ByteArray(0),
                    resolvedUrl = response.request.url.toString(),
                    headers = flattenHeaders(response.headers),
                )
            }
        }

        /**
         * Watchdog-enabled GET (SEND_BACK item 4, owner bug backlog): for
         * callers that specifically want a stalled-but-still-open connection
         * aborted faster than the outer absolute deadline — namely the
         * page-IMAGE fetch, since a dead/near-dead H@H node is exactly the
         * "keeps the socket alive with a trickle (or nothing)" case
         * [StallWatchdog] exists for. NOT wired as [defaultGet]'s default
         * behavior — every OTHER defaultGet caller (gallery/list/page-view
         * HTML, imports) keeps the plain, un-watchdogged 30s timeout.
         *
         * The per-read socket timeout is the longer of [noFirstByteMs] and the floor
         * window. OkHttp closes the socket when that timeout fires, so it cannot
         * be the short first-byte window or a pause inside the floor would abort.
         * A first-byte deadline cancels the call instead, and reports the same
         * `EHENTAI_STALL: no first byte` the socket timeout used to.
         */
        fun defaultGetWithStallWatchdog(
            url: String,
            cookieHeader: String? = null,
            stallWindowMs: Long = StallWatchdog.DEFAULT_WINDOW_MS,
            stallMinBytesPerWindow: Long = StallWatchdog.DEFAULT_MIN_BYTES_PER_WINDOW,
            noFirstByteMs: Long = StallWatchdog.DEFAULT_NO_FIRST_BYTE_MS,
            client: OkHttpClient = sourceClient,
        ): EhentaiHttpResponse {
            val builder = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,image/*,*/*;q=0.8")
                .header("Accept-Encoding", "identity")
            cookieHeader?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.header("Cookie", it) }
            // newBuilder() shares the connection pool; the per-read timeout is the
            // longer of the first-byte window and the floor, so a pause inside the
            // floor does not close the socket.
            val call = client.newBuilder()
                .readTimeout(stallReadTimeoutMs(noFirstByteMs, stallWindowMs), TimeUnit.MILLISECONDS)
                .build()
                .newCall(builder.build())
            val closed = AtomicBoolean(false)
            fun closeOnce() {
                if (closed.compareAndSet(false, true)) {
                    runCatching { call.cancel() }
                }
            }
            val sawBodyByte = AtomicBoolean(false)
            val deadline = FirstByteDeadline("EHENTAI_STALL", noFirstByteMs, sawBodyByte) { closeOnce() }
            try {
                return call.execute().use { response ->
                    val watchdog = StallWatchdog(
                        windowMs = stallWindowMs,
                        minBytesPerWindow = stallMinBytesPerWindow,
                        noFirstByteMs = noFirstByteMs,
                    )
                    EhentaiHttpResponse(
                        code = response.code,
                        body = response.body?.byteStream()
                            ?.let { readAllBytesWithStallWatchdog(it, watchdog, deadline, sawBodyByte) }
                            ?: ByteArray(0),
                        resolvedUrl = response.request.url.toString(),
                        headers = flattenHeaders(response.headers),
                    )
                }
            } catch (error: IOException) {
                deadline.stallIfDue(error)?.let { throw it }
                throw error
            } finally {
                deadline.close()
                closeOnce()
            }
        }

        /**
         * Watchdog-enabled disk transfer for a page image. The caller owns
         * response classification; this method only owns the HTTP call,
         * range protocol, bounded copy, and cancellation close. Runs on the
         * shared [sourceClient] (per-call read timeout via `newBuilder()`,
         * pool shared) unless a client is injected — the #E19 Dns seam.
         *
         * #E19 Task 4 / #E24 Task 8: while [allowSlowSwitch] is true, judge the
         * attempt once [slowGraceMs] has passed and again at most every
         * [SLOW_RECHECK_MS]. The grace check uses this attempt's average rate
         * and the time to fetch the bytes it started with. Each later check
         * uses the trailing [SLOW_WINDOW_MS] (or every byte so far, until that
         * window exists) and throws [EhentaiSlowNodeException] on the second
         * consecutive doomed re-check — rate under [slowRateBytesPerSec] and
         * projected total past [nodeSessionCapMs]. One healthy re-check clears
         * the streak. The flag defaults to FALSE (#E19 review 4): with it
         * false there is no check at all, and the attempt runs until it
         * finishes or the node cuts it. [clockMs] drives these checks and the
         * transfer budget only (monotonic by default); the stall watchdog and
         * first-byte deadline stay on the wall clock.
         */
        @OptIn(InternalCoroutinesApi::class)
        suspend fun defaultTransferWithStallWatchdog(
            url: String,
            destination: Path,
            offset: Long = 0L,
            cookieHeader: String? = null,
            firstByteMs: Long = StallWatchdog.DEFAULT_NO_FIRST_BYTE_MS,
            floorWindowMs: Long = StallWatchdog.DEFAULT_WINDOW_MS,
            floorBytes: Long = StallWatchdog.DEFAULT_MIN_BYTES_PER_WINDOW,
            beforeBody: (code: Int, resolvedUrl: String, headers: Map<String, String>) -> Unit = { _, _, _ -> },
            client: OkHttpClient = sourceClient,
            slowGraceMs: Long = SLOW_GRACE_MS,
            slowRateBytesPerSec: Long = SLOW_RATE_BYTES_PER_SEC,
            nodeSessionCapMs: Long = NODE_SESSION_CAP_MS,
            clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
            allowSlowSwitch: Boolean = false,
        ): EhentaiTransferResponse {
            val fileIdBytes = HATH_FILE_ID_SIZE_REGEX.find(url)?.groupValues?.getOrNull(1)?.toLongOrNull()

            suspend fun attempt(startOffset: Long, restarted: Boolean): EhentaiTransferResponse =
                withContext(Dispatchers.IO) {
                    val attemptClient = client.newBuilder()
                        .readTimeout(stallReadTimeoutMs(firstByteMs, floorWindowMs), TimeUnit.MILLISECONDS)
                        .build()
                    val call = attemptClient.newCall(buildTransferRequest(url, startOffset, cookieHeader))
                    val closed = AtomicBoolean(false)
                    val cancelled = AtomicBoolean(false)
                    fun closeOnce() {
                        if (closed.compareAndSet(false, true)) {
                            runCatching { call.cancel() }
                        }
                    }
                    val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion(
                        onCancelling = true,
                        invokeImmediately = true,
                    ) {
                        cancelled.set(true)
                        closeOnce()
                    }
                    val sawBodyByte = AtomicBoolean(false)
                    val deadline = FirstByteDeadline("EHENTAI_STALL", firstByteMs, sawBodyByte) { closeOnce() }
                    var restartFromZero = false
                    try {
                        val transferred = call.execute().use { response ->
                            val code = response.code
                            val headers = flattenHeaders(response.headers)
                            val resolvedUrl = response.request.url.toString()
                            val range = parseTransferContentRange(headers["content-range"])
                            val contentLength = headers["content-length"]?.toLongOrNull()?.takeIf { it >= 0L }
                            val rangeLengthMatches = range == null || contentLength == null ||
                                range.second - range.first + 1L == contentLength
                            val rangeIsValid = range?.let { (rangeStart, rangeEnd, rangeTotal) ->
                                rangeStart >= 0L && rangeEnd >= rangeStart &&
                                    rangeTotal != null && rangeTotal > rangeEnd && rangeLengthMatches
                            } == true
                            val resumeAccepted = if (startOffset == 0L) {
                                code in 200..299 && (code != 206 ||
                                    (rangeIsValid && range?.first == 0L &&
                                        (fileIdBytes == null || range.third == fileIdBytes)))
                            } else {
                                code == 206 &&
                                    rangeIsValid &&
                                    range?.first == startOffset &&
                                    (fileIdBytes == null || range.third == fileIdBytes)
                            }

                            if (startOffset > 0L && !resumeAccepted && !restarted &&
                                (code == 206 || code == 416)
                            ) {
                                // Cancel this call before the restart: the rejected 206/416 response
                                // was not drained, so cancelling takes its connection out of the
                                // shared pool instead of leaving a half-read socket behind. The
                                // restarted call dials fresh.
                                closeOnce()
                                restartFromZero = true
                                null
                            } else {
                                if (code == 206 && !resumeAccepted) {
                                    throw EhentaiTransferProtocolException("page HTTP $code or invalid Content-Range")
                                }

                                beforeBody(code, resolvedUrl, headers)
                                if (code !in 200..299) {
                                    EhentaiTransferResponse(
                                        code = code,
                                        bytesWritten = 0L,
                                        transferredBytes = 0L,
                                        expectedBytes = fileIdBytes,
                                        resolvedUrl = resolvedUrl,
                                        headers = headers,
                                    )
                                } else {
                                    val input = BufferedInputStream(response.body!!.byteStream())
                                    Files.createDirectories(destination.parent)
                                    val append = startOffset > 0L && code == 206
                                    val outputOptions = arrayOf(
                                        StandardOpenOption.CREATE,
                                        StandardOpenOption.WRITE,
                                        if (append) StandardOpenOption.APPEND else StandardOpenOption.TRUNCATE_EXISTING,
                                    )
                                    val attemptStart = if (append) startOffset else 0L
                                    var written = attemptStart
                                    val expected = fileIdBytes ?: range?.third ?: contentLength
                                    val totalBudgetMs = transferBudgetMs(expected, floorWindowMs, floorBytes)
                                    val startedAt = clockMs()
                                    var slowNodeChecked = false
                                    var doomedRechecks = 0
                                    var nextRecheckAtMs = Long.MAX_VALUE
                                    val slowWindow = ArrayDeque<Pair<Long, Long>>()
                                    val watchdog = StallWatchdog(floorWindowMs, floorBytes, firstByteMs)
                                    Files.newOutputStream(destination, *outputOptions).use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            val read = try {
                                                input.read(buffer)
                                            } catch (error: IOException) {
                                                deadline.stallIfDue(error)?.let { throw it }
                                                if (error is SocketTimeoutException) {
                                                    watchdog.onProgress(System.currentTimeMillis(), 0)?.let {
                                                        throw IOException("EHENTAI_STALL: $it", error)
                                                    }
                                                    continue
                                                }
                                                throw error
                                            }
                                            if (read < 0) break
                                            if (read == 0) continue
                                            sawBodyByte.set(true)
                                            output.write(buffer, 0, read)
                                            written += read
                                            watchdog.onProgress(System.currentTimeMillis(), read)?.let {
                                                throw IOException("EHENTAI_STALL: $it")
                                            }
                                            val elapsedMs = clockMs() - startedAt
                                            // #E19 Task 4 grace check, then #E24 re-checks. Only while a
                                            // re-roll is actually possible. Count THIS attempt's bytes —
                                            // a resumed prefix says nothing about the new node (review 4).
                                            if (allowSlowSwitch && expected != null) {
                                                val transferredThisAttempt = written - attemptStart
                                                if (transferredThisAttempt > 0L && elapsedMs > 0L) {
                                                    slowWindow.addLast(elapsedMs to transferredThisAttempt)
                                                    val cutoff = elapsedMs - SLOW_WINDOW_MS
                                                    while (slowWindow.size >= 2 && slowWindow[1].first <= cutoff) {
                                                        slowWindow.removeFirst()
                                                    }
                                                    if (!slowNodeChecked && elapsedMs >= slowGraceMs) {
                                                        slowNodeChecked = true
                                                        val rateBytesPerSec =
                                                            transferredThisAttempt * 1000L / elapsedMs
                                                        val remainingBytes = expected - attemptStart
                                                        val projectedTotalMs =
                                                            elapsedMs * remainingBytes / transferredThisAttempt
                                                        if (rateBytesPerSec < slowRateBytesPerSec &&
                                                            projectedTotalMs > nodeSessionCapMs
                                                        ) {
                                                            throw EhentaiSlowNodeException(
                                                                bytesWritten = written,
                                                                "node rate $rateBytesPerSec B/s " +
                                                                    "projects ${projectedTotalMs}ms > cap ${nodeSessionCapMs}ms",
                                                            )
                                                        }
                                                        nextRecheckAtMs = elapsedMs + SLOW_RECHECK_MS
                                                    } else if (slowNodeChecked && elapsedMs >= nextRecheckAtMs) {
                                                        val oldest = slowWindow.first()
                                                        val spanMs = elapsedMs - oldest.first
                                                        val windowBytes: Long
                                                        val windowMs: Long
                                                        if (elapsedMs >= SLOW_WINDOW_MS &&
                                                            spanMs >= SLOW_WINDOW_MS &&
                                                            slowWindow.size >= 2
                                                        ) {
                                                            windowBytes = transferredThisAttempt - oldest.second
                                                            windowMs = spanMs
                                                        } else {
                                                            windowBytes = transferredThisAttempt
                                                            windowMs = elapsedMs
                                                        }
                                                        if (windowMs > 0L && windowBytes > 0L) {
                                                            val recentRate = windowBytes * 1000L / windowMs
                                                            val bytesLeft = (expected - written).coerceAtLeast(0L)
                                                            val projectedTotalMs = if (recentRate <= 0L) {
                                                                Long.MAX_VALUE
                                                            } else {
                                                                elapsedMs + bytesLeft * 1000L / recentRate
                                                            }
                                                            val doomed = recentRate < slowRateBytesPerSec &&
                                                                projectedTotalMs > nodeSessionCapMs
                                                            if (doomed) {
                                                                doomedRechecks += 1
                                                                if (doomedRechecks >= 2) {
                                                                    throw EhentaiSlowNodeException(
                                                                        bytesWritten = written,
                                                                        "node recent rate $recentRate B/s " +
                                                                            "projects ${projectedTotalMs}ms " +
                                                                            "> cap ${nodeSessionCapMs}ms",
                                                                    )
                                                                }
                                                            } else {
                                                                doomedRechecks = 0
                                                            }
                                                        }
                                                        nextRecheckAtMs = elapsedMs + SLOW_RECHECK_MS
                                                    }
                                                }
                                            }
                                            if (elapsedMs > totalBudgetMs) {
                                                throw IOException("page transfer exceeded ${totalBudgetMs}ms")
                                            }
                                        }
                                    }
                                    if (expected != null && written < expected) {
                                        throw IOException("page disconnected at $written of $expected bytes")
                                    }
                                    EhentaiTransferResponse(
                                        code = code,
                                        bytesWritten = written,
                                        transferredBytes = written - attemptStart,
                                        expectedBytes = expected,
                                        resolvedUrl = resolvedUrl,
                                        headers = headers,
                                    )
                                }
                            }
                        }
                        if (restartFromZero) {
                            deadline.close()
                            Files.newOutputStream(
                                destination,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.TRUNCATE_EXISTING,
                            ).use { }
                            return@withContext attempt(0L, restarted = true)
                        }
                        transferred!!
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: IOException) {
                        if (cancelled.get() || !currentCoroutineContext().isActive) {
                            throw CancellationException("page transfer cancelled", error)
                        }
                        deadline.stallIfDue(error)?.let { throw it }
                        throw error
                    } finally {
                        deadline.close()
                        cancellation?.dispose()
                        closeOnce()
                    }
                }

            return attempt(offset.coerceAtLeast(0L), restarted = false)
        }

        /** The transfer's verbatim request shape: UA/Accept/Cookie plus `Range` on resume. */
        private fun buildTransferRequest(url: String, startOffset: Long, cookieHeader: String?): Request {
            val builder = Request.Builder().url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,image/*,*/*;q=0.8")
                .header("Accept-Encoding", "identity")
            cookieHeader?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.header("Cookie", it) }
            if (startOffset > 0L) builder.header("Range", "bytes=$startOffset-")
            return builder.build()
        }

        /**
         * Streams [stream] to completion, feeding every read (including a
         * zero-byte heartbeat on a caught [SocketTimeoutException] AFTER the
         * first byte has already arrived) through [watchdog]. Throws
         * [IOException] the moment the watchdog declares a stall — the
         * caller (the [get] retry loop's catch-all) launders any such
         * exception into [EhentaiException.Parse], which maps to
         * `statusCode=0` -> [FetchFailureClass.JITTER][com.easemic.app.feature.download.FetchFailureClass.JITTER],
         * same as any other network-layer failure — never a limiting
         * verdict.
         *
         * A missing first byte is [FirstByteDeadline]'s report, not a second
         * watchdog window. The socket timeout is at least the floor window, so
         * a pause inside that window stays a heartbeat.
         */
        private fun readAllBytesWithStallWatchdog(
            stream: InputStream,
            watchdog: StallWatchdog,
            deadline: FirstByteDeadline,
            sawBodyByte: AtomicBoolean,
        ): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = try {
                    stream.read(buffer)
                } catch (error: IOException) {
                    deadline.stallIfDue(error)?.let { throw it }
                    if (error is SocketTimeoutException) {
                        val reason = watchdog.onProgress(System.currentTimeMillis(), 0)
                        if (reason != null) throw IOException("EHENTAI_STALL: $reason", error)
                        continue
                    }
                    throw error
                }
                if (read < 0) break
                if (read > 0) sawBodyByte.set(true)
                output.write(buffer, 0, read)
                val reason = watchdog.onProgress(System.currentTimeMillis(), read)
                if (reason != null) throw IOException("EHENTAI_STALL: $reason")
            }
            return output.toByteArray()
        }

        private fun flattenHeaders(headers: Headers): Map<String, String> =
            headers.names().associate { name -> name.lowercase() to headers.values(name).joinToString(", ") }

        private fun parseTransferContentRange(value: String?): Triple<Long, Long, Long?>? {
            val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
                .matchEntire(value?.trim().orEmpty()) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].toLongOrNull()
            return Triple(start, end, total)
        }

        internal fun transferBudgetMs(expectedBytes: Long?, floorWindowMs: Long, floorBytes: Long): Long {
            if (expectedBytes == null || expectedBytes <= 0L || floorBytes <= 0L) {
                return 60L * 60L * 1000L
            }
            val allowance = try {
                Math.addExact(Math.multiplyExact(expectedBytes / floorBytes, floorWindowMs), 90_000L)
            } catch (_: ArithmeticException) {
                return 60L * 60L * 1000L
            }
            val remainder = if (expectedBytes % floorBytes == 0L) 0L else floorWindowMs
            val total = try { Math.addExact(allowance, remainder) } catch (_: ArithmeticException) {
                return 60L * 60L * 1000L
            }
            return maxOf(90_000L, total).coerceAtMost(60L * 60L * 1000L)
        }

        /**
         * H@H 1.6.4 `HTTPSession.doTimeoutCheck` closes a file session at 180 s
         * of age (30 s idle) — e19-research/notes.md §2. A page whose transfer
         * cannot finish inside this window is cut by the node today; the
         * slow-node switch fires only on those pages, so it spends no extra
         * quota.
         */
        const val NODE_SESSION_CAP_MS = 180_000L

        /** Transferred time observed before the slow-node projection is judged (once per attempt). */
        const val SLOW_GRACE_MS = 3_000L

        /** Minimum gap between trailing-window re-checks after [SLOW_GRACE_MS]. */
        const val SLOW_RECHECK_MS = 2_000L

        /** Trailing span used for a re-check's rate. Shorter attempts use every byte so far. */
        const val SLOW_WINDOW_MS = 6_000L

        /** Below this rate the projected total cannot stay inside [NODE_SESSION_CAP_MS] for ordinary pages. */
        const val SLOW_RATE_BYTES_PER_SEC = 100L * 1024

        private const val USER_AGENT = "PrivateComicLibrary/1.0 (E-Hentai; polite)"
        private val HATH_FILE_ID_SIZE_REGEX = Regex("/h/[0-9a-fA-F]{40}-(\\d+)-")
    }
}
