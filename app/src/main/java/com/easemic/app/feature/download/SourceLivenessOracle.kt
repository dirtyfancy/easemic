package com.easemic.app.feature.download

import com.easemic.app.feature.browse.BrowseFeedRepository
import com.easemic.app.importing.ehentai.EHENTAI_GALLERY_NOT_FOUND_SENTINEL
import com.easemic.app.importing.ehentai.EhentaiHttpResponse
import com.easemic.app.importing.nhentai.NhentaiHttpResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * C14 (2026-09-16): authoritative existence oracle for the download loop's GONE
 * seal boundary. See docs/superpowers/specs/2026-09-16-c14-source-existence-verdict-design.md.
 *
 * It is a VETO, never a gate ([DownloadWorkerLoop.vetoGoneIfAlive]): it can only
 * PREVENT a false seal ([Liveness.ALIVE]), never cause one — so anything it
 * cannot positively resolve is [Liveness.UNKNOWN], and the loop then seals as it
 * would today. Signals re-measured live 2026-09-16:
 *
 *  * e-h: `POST api.php gdata` — `gmetadata[0]` with metadata → ALIVE (expunged
 *    included); `error` = the `Gallery not found.` sentinel → DEAD; `error` =
 *    `Key missing…` (a TOKEN problem, gid may be alive) → ALIVE (bias to keep,
 *    matching the classifier's TokenStale=alive stance); anything else →
 *    UNKNOWN. Works anonymously, for ExHentai galleries too (no cookie needed),
 *    which is why it sidesteps the Sad Panda empty-body ambiguity.
 *  * nh: `GET /api/v2/galleries/<id>` — 200 → ALIVE; 404 whose body carries the
 *    measured not-found text → DEAD; anything else (403/CF/5xx/bodiless-404) →
 *    UNKNOWN.
 */
class SourceLivenessOracle(
    private val ehApiEndpoint: String,
    private val ehPost: (url: String, body: ByteArray, contentType: String) -> EhentaiHttpResponse,
    private val nhApiOrigin: String,
    private val nhGet: (url: String) -> NhentaiHttpResponse,
) {
    /** Dispatch on namespace; parse the id/token out of [acquisitionUrl]. */
    suspend fun check(providerNamespace: String, acquisitionUrl: String): Liveness =
        when (providerNamespace) {
            GalleryIdentityV1.EHENTAI -> {
                val parsed = BrowseFeedRepository.parseEhentaiAcquisition(acquisitionUrl)
                if (parsed == null) Liveness.UNKNOWN else checkEhentai(parsed.first, parsed.second)
            }
            GalleryIdentityV1.NHENTAI -> {
                val id = BrowseFeedRepository.parseNhentaiAcquisition(acquisitionUrl)
                if (id == null) Liveness.UNKNOWN else checkNhentai(id)
            }
            else -> Liveness.UNKNOWN
        }

    private suspend fun checkEhentai(gid: Long, token: String): Liveness {
        val payload = JSONObject()
            .put("method", "gdata")
            .put("gidlist", org.json.JSONArray().put(org.json.JSONArray().put(gid).put(token)))
            .put("namespace", 1)
            .toString()
        val response = try {
            withContext(Dispatchers.IO) { ehPost(ehApiEndpoint, payload.toByteArray(), "application/json") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Liveness.UNKNOWN
        }
        if (response.code != 200) return Liveness.UNKNOWN
        val meta = try {
            JSONObject(response.body.decodeToString())
                .optJSONArray("gmetadata")
                ?.optJSONObject(0)
        } catch (_: Exception) {
            null
        } ?: return Liveness.UNKNOWN

        val error = meta.optString("error", "")
        return when {
            // A real absence: the gdata error IS the 'Gallery not found.' sentinel.
            error.contains(EHENTAI_GALLERY_NOT_FOUND_SENTINEL, ignoreCase = true) -> Liveness.DEAD
            // A token problem (or any non-not-found error) cannot confirm death; keep the gallery.
            error.isNotBlank() -> Liveness.ALIVE
            // Real metadata present → alive (expunged is alive too).
            meta.has("filecount") || meta.has("title") || meta.has("expunged") -> Liveness.ALIVE
            else -> Liveness.UNKNOWN
        }
    }

    private suspend fun checkNhentai(id: Long): Liveness {
        val response = try {
            withContext(Dispatchers.IO) { nhGet("$nhApiOrigin/api/v2/galleries/$id") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Liveness.UNKNOWN
        }
        val bodyText = if (response.body.isNotEmpty()) response.body.decodeToString() else ""
        return when {
            response.code == 200 -> Liveness.ALIVE
            response.code == 404 && bodyText.contains("Gallery not found", ignoreCase = true) -> Liveness.DEAD
            // A bodiless 404, a 403/CF, a 5xx: not authoritative — seal as today.
            else -> Liveness.UNKNOWN
        }
    }
}
