package com.easemic.app.feature.download

import com.easemic.app.sync.redactIdentity
import com.easemic.app.sync.DiagLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps [GalleryIdentityV1] → mutable [DownloadLocator] (tokens / acquisition URLs).
 * Locators are never identity keys — Front token rotation overwrites the entry.
 */
interface DownloadLocatorStore {
    fun put(identity: GalleryIdentityV1, locator: DownloadLocator)
    fun get(identity: GalleryIdentityV1): DownloadLocator?

    /**
     * F3: drop the resume locator for [identity]. A full delete of a downloaded
     * comic must leave nothing behind — without this the orphan
     * `download-locators/<ns>/<id>.locator` outlived the comic it resumed.
     */
    fun remove(identity: GalleryIdentityV1)
}

/**
 * A32 §2: writes [locator] without letting a fact-less writer erase catalog
 * facts an earlier one recorded.
 *
 * The store is last-write-wins per identity, and several legitimate writers
 * have no catalog row in hand — the cloud catch-up sweep rebuilds its item
 * from a bookmark (id/title/acquisition/cover only), the Local Detail collect
 * switch has just the local row. Before this, collecting a gallery after its
 * download was enqueued wiped the tags the worker was going to commit at
 * FINISH. Acquisition URL, title and cloud id stay last-write-wins: those the
 * new writer genuinely knows better. Empty stays empty when nothing was
 * recorded — nothing is invented.
 */
fun DownloadLocatorStore.putKeepingCatalogFacts(
    identity: GalleryIdentityV1,
    locator: DownloadLocator,
) {
    val existing = get(identity)
    val merged = if (existing == null) {
        locator
    } else {
        locator.copy(
            tags = locator.tags.ifEmpty { existing.tags },
            language = locator.language ?: existing.language,
            category = locator.category ?: existing.category,
            // #C12: the containing feed is a fact about the SOURCE, like the
            // tags — a re-tap that rebuilt its item without it must not wipe it.
            feedUrl = locator.feedUrl ?: existing.feedUrl,
            browseSourceId = locator.browseSourceId ?: existing.browseSourceId,
        )
    }
    put(identity, merged)
}

/**
 * #C65: the reverse direction of [putKeepingCatalogFacts] — the download
 * worker just fetched the gallery plan's OWN [facts] (via
 * [DownloadPageManifest.catalogFacts]) and offers them to whatever locator is
 * already stored for [identity], filling only what that locator lacks (an
 * empty [DownloadLocator.tags], a null language, a null category).
 *
 * A locator that already has a fact keeps it — an enqueue tap or a catch-up
 * sweep may have recorded a value the plan doesn't carry (nhentai's tag list
 * is capped at parse time; #C65 spec) or simply recorded it first, and the
 * plan is not treated as more authoritative just because it arrived second.
 * Nothing else about the locator moves: acquisitionUrl/title/cloudComicId/
 * coverUrl/feedUrl are what the enqueue tap or a later collect knows, not
 * what a gallery plan fetch has an opinion about. No locator on file for
 * [identity] (job failed/removed between the fetch and this call) is a
 * no-op — there is nothing left to fill.
 */
fun DownloadLocatorStore.fillMissingCatalogFacts(
    identity: GalleryIdentityV1,
    facts: DownloadCommitMetadata,
) {
    val existing = get(identity) ?: return
    if (existing.tags.isNotEmpty() && existing.language != null && existing.category != null) return
    val merged = existing.copy(
        tags = existing.tags.ifEmpty { facts.tags },
        language = existing.language ?: facts.language,
        category = existing.category ?: facts.category,
    )
    put(identity, merged)
}

class InMemoryDownloadLocatorStore : DownloadLocatorStore {
    private val map = ConcurrentHashMap<String, DownloadLocator>()

    override fun put(identity: GalleryIdentityV1, locator: DownloadLocator) {
        map[key(identity)] = locator
    }

    override fun get(identity: GalleryIdentityV1): DownloadLocator? = map[key(identity)]

    override fun remove(identity: GalleryIdentityV1) {
        map.remove(key(identity))
    }

    companion object {
        fun key(identity: GalleryIdentityV1): String =
            listOf(
                GalleryIdentityV1.IDENTITY_VERSION.toString(),
                identity.providerNamespace,
                identity.canonicalItemId,
            ).joinToString("\u0000")
    }
}

/**
 * File-backed locators so WAIT/DOWNLOAD jobs can resume page fetch after process
 * restart without forking identity when Front tokens rotate.
 */
class FileDownloadLocatorStore(
    private val root: File,
) : DownloadLocatorStore {
    private val memory = InMemoryDownloadLocatorStore()

    init {
        root.mkdirs()
    }

    override fun put(identity: GalleryIdentityV1, locator: DownloadLocator) {
        memory.put(identity, locator)
        // #C18 audit F7: the on-disk copy exists to RESUME after a restart; a
        // write failure (ENOSPC, EACCES, a lost volume — or the ENAMETOOLONG
        // this store once threw) must never escape into the caller's coroutine,
        // where it killed the process. The memory copy already serves this
        // process; log and carry on.
        runCatching {
            val file = fileFor(identity)
            file.parentFile?.mkdirs()
            val title = locator.title.orEmpty().replace('\n', ' ')
            val cloudId = locator.cloudComicId.orEmpty().replace('\n', ' ').trim()
            val cover = locator.coverUrl.orEmpty().replace('\n', ' ').trim()
            val catalog = locator.catalogMetadataLine()
            val feed = locator.feedUrl.orEmpty().replace('\n', ' ').trim()
            val browseSource = locator.browseSourceId.orEmpty().replace('\n', ' ').trim()
            file.writeText(
                buildString {
                    append(locator.acquisitionUrl)
                    append('\n')
                    append(title)
                    append('\n')
                    // Positional file: once a later line exists, every earlier one
                    // must be written (even empty) or the reader's indices shift.
                    if (cloudId.isNotEmpty() || cover.isNotEmpty() || catalog.isNotEmpty() || feed.isNotEmpty() || browseSource.isNotEmpty()) {
                        append(cloudId)
                        append('\n')
                    }
                    if (cover.isNotEmpty() || catalog.isNotEmpty() || feed.isNotEmpty() || browseSource.isNotEmpty()) {
                        append(cover)
                        append('\n')
                    }
                    if (catalog.isNotEmpty() || feed.isNotEmpty() || browseSource.isNotEmpty()) {
                        append(catalog)
                        append('\n')
                    }
                    if (feed.isNotEmpty() || browseSource.isNotEmpty()) {
                        // #C12: sixth line, the containing OPDS feed.
                        append(feed)
                        append('\n')
                    }
                    if (browseSource.isNotEmpty()) {
                        // Seventh line: original Browse source, not canonical identity.
                        append(browseSource)
                        append('\n')
                    }
                },
            )
        }.onFailure { error ->
            // #C18 re-review item 2: NEVER the raw id (an OPDS acquisition URL
            // carries the catalog's auth key in its PATH) and NEVER the
            // throwable (a FileNotFoundException message IS the sanitised path,
            // key included, and redactUrl cannot match it once '/' became '_').
            DiagLog.w(
                "DownloadLocator",
                "locator write failed (${error::class.simpleName}) for ${redactIdentity(identity)}; resume-after-restart lost",
            )
        }
    }

    override fun get(identity: GalleryIdentityV1): DownloadLocator? {
        memory.get(identity)?.let { return it }
        // #C18 re-review item 10: same bare launches reach `get()` as `put()`;
        // `isFile` screens the common cases but `readLines()` can still throw
        // on EACCES or a stat/open race. An unreadable locator is "no locator".
        return runCatching {
            val file = fileFor(identity)
            if (!file.isFile) return@runCatching null
            val lines = file.readLines()
            val url = lines.getOrNull(0)?.trim().orEmpty()
            if (url.isEmpty()) return@runCatching null
            val title = lines.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            val cloudComicId = lines.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
            val coverUrl = lines.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
            val catalog = parseCatalogMetadataLine(lines.getOrNull(4))
            val feedUrl = lines.getOrNull(5)?.trim()?.takeIf { it.isNotEmpty() }
            val browseSourceId = lines.getOrNull(6)?.trim()?.takeIf { it.isNotEmpty() }
            val locator = DownloadLocator(
                acquisitionUrl = url,
                title = title,
                cloudComicId = cloudComicId,
                coverUrl = coverUrl,
                tags = catalog.tags,
                language = catalog.language,
                category = catalog.category,
                feedUrl = feedUrl,
                browseSourceId = browseSourceId,
            )
            memory.put(identity, locator)
            locator
        }.getOrNull()
    }

    private fun fileFor(identity: GalleryIdentityV1): File {
        val ns = identity.providerNamespace.replace(Regex("[\\\\/\\u0000:]"), "_")
        val id = identity.canonicalItemId.replace(Regex("[\\\\/\\u0000]"), "_")
        return File(File(root, BoundedPathSegment.of(ns)), "${BoundedPathSegment.of(id)}.locator")
    }

    override fun remove(identity: GalleryIdentityV1) {
        memory.remove(identity)
        fileFor(identity).delete()
    }
}

/** One line, so the positional locator file keeps exactly one line per field. */
private fun DownloadLocator.catalogMetadataLine(): String {
    val cleanTags = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val cleanLanguage = language?.trim()?.takeIf { it.isNotEmpty() }
    val cleanCategory = category?.trim()?.takeIf { it.isNotEmpty() }
    if (cleanTags.isEmpty() && cleanLanguage == null && cleanCategory == null) return ""
    val json = JSONObject()
    if (cleanTags.isNotEmpty()) json.put("tags", JSONArray(cleanTags))
    if (cleanLanguage != null) json.put("language", cleanLanguage)
    if (cleanCategory != null) json.put("category", cleanCategory)
    return json.toString().replace('\n', ' ')
}

/**
 * A missing or unparseable line degrades to nothing at all — a locator written
 * by an older build must still resume a download, and a Detail with no tags is
 * the honest outcome (A32 §6).
 */
private fun parseCatalogMetadataLine(raw: String?): DownloadCommitMetadata {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return DownloadCommitMetadata()
    val json = runCatching { JSONObject(text) }.getOrNull() ?: return DownloadCommitMetadata()
    val array = json.optJSONArray("tags")
    val tags = if (array == null) {
        emptyList()
    } else {
        (0 until array.length()).mapNotNull { index ->
            array.optString(index).trim().takeIf { it.isNotEmpty() }
        }
    }
    return DownloadCommitMetadata(
        tags = tags,
        language = json.optString("language").trim().takeIf { it.isNotEmpty() },
        category = json.optString("category").trim().takeIf { it.isNotEmpty() },
    )
}
