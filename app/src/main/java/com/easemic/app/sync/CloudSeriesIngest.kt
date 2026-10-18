package com.easemic.app.sync

import com.easemic.app.feature.collect.CloudComicIdStore
import com.easemic.app.feature.download.SeriesIdentityV2
import com.easemic.app.feature.download.serializedSeriesLedgerKey
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Serialized chapter upload request shared by the queue runner and writer. */
data class CloudSeriesChapterUploadRequest(
    val series: CloudSeriesManifest,
    val chapter: CloudChapterMeta,
    val pageFiles: List<File>,
    val thumbBytes: List<ByteArray> = emptyList(),
    val thumbSupplier: CloudCollectionStore.ThumbSupplier? = null,
    val packsDir: File? = null,
    val checkpointWorkDir: File? = null,
    val packTargetBytes: Long? = null,
    /** Locally cached source series cover, uploaded once under the series prefix. */
    val seriesCoverFile: File? = null,
    /** Local ledger evidence used to repair a summary lost by another device. */
    val syncedChapterKeys: Map<String, String> = emptyMap(),
    /**
     * #E21 tail 2: the decoded series.json this upload attempt started from.
     * The runner reads it once and threads it down so chapter-key resolution
     * does not GET the summary again per chapter. When null, the writer
     * reads the summary itself for direct callers. The summary merge does
     * not use this copy: the writer re-reads under the index lock. Never
     * held across attempts.
     */
    val existingSeriesManifest: CloudSeriesManifest? = null,
    /**
     * #E111: when this returns true for the series, the chapter's objects
     * land but the series summary and library index are NOT rewritten —
     * the chapter joins a per-series buffer that the last non-deferred
     * call of the batch writes once. O(chapters) shared-object rewrites
     * per upload storm became O(1); a process death inside the deferral
     * window leaves the chapters' ledger rows to repair the summary on the
     * series' next write (the merge's syncedChapterKeys path).
     */
    val deferTailWhile: ((seriesCloudId: String) -> Boolean)? = null,
)

/**
 * Resolver used by the queue caller. The callbacks receive the stable
 * serialized-series ledger key, never an acquisition URL. The ledger wins
 * over the local mirror, and neither may be replaced by a newly minted id.
 */
class CloudSeriesCloudIdResolver(
    private val ledgerId: (String) -> String?,
    private val activeMirrorId: (String) -> String?,
    private val mint: (String) -> String,
) {
    private val mintedIds = ConcurrentHashMap<String, String>()

    constructor(
        ledgerId: (String) -> String?,
        activeMirrorId: (String) -> String?,
        idStore: CloudComicIdStore,
    ) : this(ledgerId, activeMirrorId, { key -> idStore.getOrCreate(key) })

    fun resolve(identity: SeriesIdentityV2): String =
        resolve(serializedSeriesLedgerKey(identity))

    fun resolve(seriesLedgerKey: String): String =
        ledgerId(seriesLedgerKey)?.trim()?.takeIf { it.isNotEmpty() }
            ?: activeMirrorId(seriesLedgerKey)?.trim()?.takeIf { it.isNotEmpty() }
            ?: mintedIds.computeIfAbsent(seriesLedgerKey) {
                mint(seriesLedgerKey).trim().also { id ->
                    require(id.isNotEmpty()) { "series cloud id required" }
                }
            }
}

/** Small facade for callers that want the writer name without queue wiring. */
class CloudSeriesWriter(
    private val store: CloudCollectionStore,
) {
    fun upload(request: CloudSeriesChapterUploadRequest): CloudArchiveUpload {
        val id = request.series.seriesCloudId
        val series = request.series.copy(seriesCloudId = id)
        val chapter = request.chapter.copy(seriesCloudId = id)
        return store.uploadSeriesChapter(request.copy(series = series, chapter = chapter))
    }
}
