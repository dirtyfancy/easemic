package com.easemic.app.sync.adfilter

import com.easemic.app.sync.DiagLog
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * #E118: Classification of a trailing comic page for the extraneous-ads filter.
 */
enum class TrailingPageKind {
    /** External commercial sponsor ad, game/casino promo, or commercial QR flyer — trim and fingerprint. */
    SPONSOR_AD,
    /** Non-commercial scanlation group credit, recruitment, or translator notes — preserve, keep scanning earlier. */
    SCANLATION_CREDIT,
    /** Comic story page, illustration, cover, or original creator afterword/Fanbox — preserve and stop after 2 consecutive. */
    COMIC_OR_CREATOR,
}

data class JevPageJudgment(
    val kind: TrailingPageKind,
    val confidence: Double,
    val commercialAdProbability: Double,
    val inputTokens: Int = 0,
) {
    /**
     * Conservative decision rule calibrated on live E-Hentai extraneous-ads galleries:
     * a page is stripped as a commercial sponsor ad only when Jev selects `sponsor_ad`
     * AND the parallel `is_commercial_ad` Noul probability is at least [MIN_COMMERCIAL_AD_NOUL].
     */
    val isConfirmedSponsorAd: Boolean
        get() = kind == TrailingPageKind.SPONSOR_AD && commercialAdProbability >= MIN_COMMERCIAL_AD_NOUL

    companion object {
        const val MIN_COMMERCIAL_AD_NOUL = 0.75
    }
}

fun interface JevPageJudge {
    fun judge(apiKey: String, normalizedJpegBytes: ByteArray): JevPageJudgment?
}

/**
 * #E118: HTTP client for TypeSafe's `POST https://api.typesafe.ai/v1/systemone`
 * endpoint using `model = "jev-latest"` and header `TypeSafe-Danger-Zone-Images: true`.
 *
 * Uses an explicit IPv4-first [OkHttpClient] because Android dual-stack networks
 * with split-tunnel proxies often route IPv4 through the proxy while sending
 * IPv6 (`240e:...`) direct to domestic broadband, where Cloudflare returns
 * `HTTP 451 Unavailable For Legal Reasons` on `api.typesafe.ai`.
 */
object JevSystemOneHttpJudge : JevPageJudge {
    const val ENDPOINT = "https://api.typesafe.ai/v1/systemone"
    const val MODEL_ID = "jev-latest"
    const val HEADER_DANGER_ZONE_IMAGES = "TypeSafe-Danger-Zone-Images"
    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val READ_TIMEOUT_MS = 25_000L
    private const val MAX_ATTEMPTS = 2
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    internal fun orderAddressesIpv4First(addresses: List<InetAddress>): List<InetAddress> =
        addresses.sortedWith(compareBy { it is Inet6Address || it !is Inet4Address })

    private val ipv4FirstDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            orderAddressesIpv4First(Dns.SYSTEM.lookup(hostname))
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(ipv4FirstDns)
        .retryOnConnectionFailure(true)
        .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    override fun judge(apiKey: String, normalizedJpegBytes: ByteArray): JevPageJudgment? {
        val cleanedKey = apiKey.trim()
        if (cleanedKey.isEmpty() || normalizedJpegBytes.isEmpty()) return null
        val assetRef = "page-${UUID.randomUUID()}"
        val payloadBytes = buildRequestPayload(assetRef, normalizedJpegBytes)
        for (attempt in 1..MAX_ATTEMPTS) {
            val result = runCatching {
                val request = Request.Builder()
                    .url(ENDPOINT)
                    .post(payloadBytes.toRequestBody(JSON_MEDIA_TYPE))
                    .header("Authorization", "Bearer $cleanedKey")
                    .header(HEADER_DANGER_ZONE_IMAGES, "true")
                    .header("User-Agent", "Easemic/1.0")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    val code = response.code
                    val bodyBytes = response.body?.bytes() ?: ByteArray(0)
                    if (code !in 200..299) {
                        DiagLog.w("AdFilter", "jev-http-err code=$code attempt=$attempt bytes=${normalizedJpegBytes.size}")
                        null
                    } else {
                        parseResponsePayload(bodyBytes)
                    }
                }
            }.onFailure { err ->
                DiagLog.w("AdFilter", "jev-ex attempt=$attempt err=${err.javaClass.simpleName}")
            }.getOrNull()
            if (result != null) return result
        }
        return null
    }

    internal fun buildRequestPayload(assetRef: String, jpegBytes: ByteArray): ByteArray {
        val b64 = Base64.getEncoder().encodeToString(jpegBytes)
        val assetObj = JSONObject()
            .put("type", "image")
            .put("format", "jpg")
            .put("data_base64", b64)
        val assetsObj = JSONObject().put(assetRef, assetObj)
        val stateObj = JSONObject().put("page", assetRef)

        val choiceCriteria = JSONObject()
            .put(
                "sponsor_ad",
                "A commercial sponsor advertisement or promotional flyer unrelated to the comic story (such as adult game ads, gacha/mobile game ads, gambling/casino ads, app download promos, QR codes or URLs for commercial websites/Telegram channels).",
            )
            .put(
                "scanlation_credit",
                "A non-commercial scanlation group credit, recruitment, or translator notes page without commercial sponsor ads.",
            )
            .put(
                "comic_or_creator",
                "An actual manga/comic story page, cover, illustration, or original artist afterword/Fanbox/Twitter page.",
            )

        val pageKindQuestion = JSONObject()
            .put("type", "choice")
            .put("instructions", "Classify the manga/comic page in `page`.")
            .put("criteria", choiceCriteria)

        val isCommercialAdQuestion = JSONObject()
            .put("type", "noul")
            .put(
                "instructions",
                "Is `page` an external commercial advertisement or promotional poster (e.g., promoting an external game, app, website, or sponsor with QR codes/links) rather than part of the comic or scanlation credits?",
            )

        val questionsObj = JSONObject()
            .put("page_kind", pageKindQuestion)
            .put("is_commercial_ad", isCommercialAdQuestion)

        val root = JSONObject()
            .put("model", MODEL_ID)
            .put("state", stateObj)
            .put("assets", assetsObj)
            .put("questions", questionsObj)
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    internal fun parseResponsePayload(bytes: ByteArray): JevPageJudgment? {
        if (bytes.isEmpty()) return null
        return runCatching {
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            val answers = root.optJSONObject("answers") ?: return null
            val kindObj = answers.optJSONObject("page_kind") ?: return null
            val adObj = answers.optJSONObject("is_commercial_ad") ?: return null
            val rawChoice = kindObj.optString("choice")
            val kind = when (rawChoice) {
                "sponsor_ad" -> TrailingPageKind.SPONSOR_AD
                "scanlation_credit" -> TrailingPageKind.SCANLATION_CREDIT
                "comic_or_creator" -> TrailingPageKind.COMIC_OR_CREATOR
                else -> return null
            }
            val confidence = kindObj.optDouble("confidence", 0.0)
            val noul = adObj.optDouble("noul", 0.0)
            val inputTokens = root.optJSONObject("usage")?.optInt("input_tokens", 0) ?: 0
            JevPageJudgment(
                kind = kind,
                confidence = confidence,
                commercialAdProbability = noul,
                inputTokens = inputTokens,
            )
        }.getOrNull()
    }
}

data class TrailingAdStripResult(
    val keptFiles: List<File>,
    val trimmedCount: Int,
    val newlyDiscoveredAdHashes: Set<String> = emptySet(),
)

/**
 * #E118: Scans the tail of a comic tagged with `extraneous ads` and strips
 * commercial sponsor ad pages while preserving comic story pages and
 * non-commercial scanlation group credit pages.
 *
 * Rules:
 * 1. **Tag gate**: Runs only when [hasExtraneousAdsTag] is true for the gallery's tags.
 * 2. **Protected prefix**: Pages at index `0..2` (the first 3 pages) are never
 *    scanned or removed.
 * 3. **Bounded tail window**: Scans backward from `lastIndex` for at most
 *    [MAX_TAIL_SCAN_PAGES] (12) pages.
 * 4. **3-tier per-page evaluation**:
 *    - Tier 1: Local + cloud `AdFingerprintStore` 64-bit dHash match (`hammingDistance <= 5`)
 *      confirms `SPONSOR_AD` in <2 ms with zero API tokens.
 *    - Tier 2: Session non-ad dHash memo skips re-querying identical story/credit pages.
 *    - Tier 3: Calls [jevJudge] (`POST /v1/systemone`, `jev-latest`).
 * 5. **Stop &礼仪 (etiquette) rule**:
 *    - `SPONSOR_AD`: removed, and its dHash is added to `newlyDiscoveredAdHashes`.
 *    - `SCANLATION_CREDIT`: preserved! Does not increment the story-page stop counter,
 *      so sponsor ads placed *before* a final scanlation credit page are still caught.
 *    - `COMIC_OR_CREATOR`: preserved. Once [CONSECUTIVE_STORY_PAGES_TO_STOP] (2)
 *      consecutive story/creator pages are seen, backward scanning stops immediately.
 * 6. **Fail-open safety**: Any decoding or network failure on a page treats that
 *    page as `COMIC_OR_CREATOR` (never deletes on uncertainty).
 */
class TrailingAdStripper(
    private val fingerprintStore: AdFingerprintStore,
    private val jevJudge: JevPageJudge = JevSystemOneHttpJudge,
    private val dHashComputer: (File) -> String? = PerceptualImageHash::computeFileDHashHex,
    private val jpegNormalizer: (File) -> ByteArray? = PerceptualImageHash::encodeNormalizedJpegForJev,
) {
    private val knownSafeHashes = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun stripIfApplicable(
        pageFiles: List<File>,
        tags: List<String>,
        apiKey: String?,
        cloudStore: com.easemic.app.sync.ObjectStore? = null,
        shouldAbort: () -> Boolean = { false },
    ): TrailingAdStripResult {
        val cleanedKey = apiKey?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanedKey == null || !hasExtraneousAdsTag(tags) || pageFiles.size <= PROTECTED_LEADING_PAGES) {
            return TrailingAdStripResult(keptFiles = pageFiles, trimmedCount = 0)
        }

        // Best-effort pull of cloud ad fingerprints before scanning so any ads
        // already discovered on another device hit Tier 1 for free.
        fingerprintStore.syncWithCloudBestEffort(cloudStore)

        val lowestCandidateIndex = maxOf(
            PROTECTED_LEADING_PAGES,
            pageFiles.size - MAX_TAIL_SCAN_PAGES,
        )
        val indicesToDrop = HashSet<Int>()
        val newAdHashes = LinkedHashSet<String>()
        var consecutiveStoryPages = 0

        for (index in pageFiles.lastIndex downTo lowestCandidateIndex) {
            if (shouldAbort()) {
                return TrailingAdStripResult(keptFiles = pageFiles, trimmedCount = 0)
            }
            val file = pageFiles[index]
            val dHash = dHashComputer(file)

            // Tier 1: Known sponsor ad fingerprint match (0 tokens, <2ms)
            if (dHash != null && fingerprintStore.matchesKnownAd(dHash)) {
                indicesToDrop.add(index)
                consecutiveStoryPages = 0
                DiagLog.i("AdFilter", "tier1-hit page=${index + 1}/${pageFiles.size} dHash=$dHash")
                continue
            }

            // Tier 2: Known safe story/creator page in this session
            if (dHash != null && knownSafeHashes.contains(dHash)) {
                consecutiveStoryPages++
                if (consecutiveStoryPages >= CONSECUTIVE_STORY_PAGES_TO_STOP) break
                continue
            }

            // Tier 3: Ask TypeSafe Jev image judgment
            val jpegBytes = jpegNormalizer(file)
            val judgment = if (jpegBytes != null) {
                jevJudge.judge(cleanedKey, jpegBytes)
            } else {
                null
            }

            if (judgment == null) {
                // Fail-open on network/decode error: keep the page, but do NOT
                // increment consecutiveStoryPages so a transient error on two
                // pages does not prematurely abort the tail scan.
                DiagLog.w("AdFilter", "tier3-null page=${index + 1}/${pageFiles.size} dHash=${dHash ?: "none"}")
                continue
            }

            when {
                judgment.isConfirmedSponsorAd -> {
                    indicesToDrop.add(index)
                    if (dHash != null) {
                        newAdHashes.add(dHash)
                        fingerprintStore.addAll(listOf(dHash))
                    }
                    consecutiveStoryPages = 0
                    DiagLog.i(
                        "AdFilter",
                        "tier3-ad page=${index + 1}/${pageFiles.size} dHash=${dHash ?: "none"} conf=${"%.2f".format(judgment.confidence)} noul=${"%.2f".format(judgment.commercialAdProbability)}",
                    )
                }
                judgment.kind == TrailingPageKind.SCANLATION_CREDIT -> {
                    // Preserve scanlation group's own credit/recruitment page out of respect,
                    // but keep scanning earlier in case sponsor ads precede it.
                    if (dHash != null) knownSafeHashes.add(dHash)
                    DiagLog.i(
                        "AdFilter",
                        "tier3-credit-kept page=${index + 1}/${pageFiles.size} dHash=${dHash ?: "none"} conf=${"%.2f".format(judgment.confidence)}",
                    )
                }
                else -> {
                    if (dHash != null) knownSafeHashes.add(dHash)
                    consecutiveStoryPages++
                    DiagLog.i(
                        "AdFilter",
                        "tier3-story page=${index + 1}/${pageFiles.size} dHash=${dHash ?: "none"} conf=${"%.2f".format(judgment.confidence)} noul=${"%.2f".format(judgment.commercialAdProbability)}",
                    )
                    if (consecutiveStoryPages >= CONSECUTIVE_STORY_PAGES_TO_STOP) break
                }
            }
        }

        if (indicesToDrop.isEmpty()) {
            return TrailingAdStripResult(keptFiles = pageFiles, trimmedCount = 0)
        }

        val kept = pageFiles.filterIndexed { index, _ -> index !in indicesToDrop }
        if (kept.size < PROTECTED_LEADING_PAGES) {
            return TrailingAdStripResult(keptFiles = pageFiles, trimmedCount = 0)
        }

        if (newAdHashes.isNotEmpty()) {
            fingerprintStore.syncWithCloudBestEffort(cloudStore, extraNewHashes = newAdHashes)
        }

        return TrailingAdStripResult(
            keptFiles = kept,
            trimmedCount = pageFiles.size - kept.size,
            newlyDiscoveredAdHashes = newAdHashes,
        )
    }

    companion object {
        const val PROTECTED_LEADING_PAGES = 3
        const val MAX_TAIL_SCAN_PAGES = 12
        const val CONSECUTIVE_STORY_PAGES_TO_STOP = 2

        /**
         * Matches `extraneous ads`, `other:extraneous ads`, `tag:extraneous ads`,
         * `extraneous-ads`, `other:extraneous_ads`, or `外部广告` (case-insensitive).
         */
        fun hasExtraneousAdsTag(tags: Iterable<String>): Boolean =
            tags.any { raw ->
                val normalized = raw.trim().lowercase()
                    .substringAfter(':')
                    .replace('_', ' ')
                    .replace('-', ' ')
                    .trim()
                normalized == "extraneous ads" || normalized == "外部广告"
            }
    }
}
