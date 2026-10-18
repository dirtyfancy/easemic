package com.easemic.app.feature.settings.backup

import com.easemic.app.feature.collect.CollectBookmarkStore
import com.easemic.app.feature.browse.RemoteReadingProgressStore
import com.easemic.app.feature.download.DownloadCommitMetadata
import com.easemic.app.feature.download.GalleryIdentityV1
import com.easemic.app.feature.history.BrowseHistoryStore
import com.easemic.app.feature.library.galleryDedupKey
import com.easemic.app.feature.providers.EhentaiEnableStore
import com.easemic.app.feature.providers.NhentaiEnableStore
import com.easemic.app.feature.providers.OpdsProviderStore
import com.easemic.app.feature.reader.ReaderKeepScreenOnStore
import com.easemic.app.feature.reader.ReaderRtlPagingStore
import com.easemic.app.feature.reader.ReaderVolumeKeyPagingStore
import com.easemic.app.feature.settings.CacheBudgetStore
import com.easemic.app.feature.settings.CloudReleaseAfterUploadStore
import com.easemic.app.feature.settings.LibraryLayoutStore
import com.easemic.app.feature.settings.MaxConcurrentTransfersStore
import com.easemic.app.model.LibraryItem
import com.easemic.app.model.LibraryItemId
import com.easemic.app.sync.archive.ArchiveComic
import com.easemic.app.sync.archive.ArchiveCredentialScrub
import com.easemic.app.sync.archive.ArchiveProgress
import com.easemic.app.sync.archive.ArchiveSettings
import com.easemic.app.sync.archive.ArchiveState
import com.easemic.app.sync.archive.ArchiveWriter
import com.easemic.app.ui.PreferenceMap
import java.io.File
import java.net.URI

/**
 * #D7 export: gathers what a new phone needs (spec §4–5) into an [ExportPlan] — sizes first, so the screen can show
 * "N comics · X GB" before anything is written — then streams it into a `.pclarchive`.
 *
 * A comic is carried when a download owns its Library row ([downloadIdentity] != null) and at least one of its page
 * files is on disk; folder imports are the user's own files and stay out. A comic missing page files travels as
 * truncated with the count it had, so the new phone badges it exactly like this one would.
 */
class ArchiveExporter(
    private val prefs: PreferenceMap,
    private val allItems: suspend () -> List<LibraryItem>,
    private val downloadIdentity: suspend (LibraryItemId) -> GalleryIdentityV1?,
    private val metadataFor: suspend (LibraryItemId) -> DownloadCommitMetadata,
    private val appVersion: String,
    private val serializedSeriesFor: suspend (LibraryItemId) -> ArchiveWriter.SeriesSource? = { null },
    private val isSerializedSeries: suspend (LibraryItemId) -> Boolean = { false },
) {
    data class ExportPlan(
        val state: ArchiveState,
        val comics: List<ArchiveWriter.ComicSource>,
        val pageBytes: Long,
        /** OPDS catalogs whose URL is itself a key: named so the user can add them again on the new phone. */
        val leftBehindCatalogs: List<String>,
        val series: List<ArchiveWriter.SeriesSource> = emptyList(),
    )

    suspend fun plan(includeDownloads: Boolean): ExportPlan {
        val items = allItems()
        val serializedSeriesIds = mutableSetOf<LibraryItemId>()
        for (item in items) if (isSerializedSeries(item.id)) serializedSeriesIds += item.id
        val seriesByItem = mutableMapOf<LibraryItemId, ArchiveWriter.SeriesSource>()
        for (item in items) {
            if (item.id !in serializedSeriesIds) continue
            val source = serializedSeriesFor(item.id)
            if (source != null) seriesByItem[item.id] = source
        }
        // Serialized rows are classified before the gallery callback. This keeps the gallery-only ownership parser
        // and delete identity out of the series archive path, including followed series with no saved chapters.
        val galleryItems = items.filter { it.id !in serializedSeriesIds }
        val identities = mutableMapOf<LibraryItemId, GalleryIdentityV1?>()
        for (item in galleryItems) identities[item.id] = downloadIdentity(item.id)
        val comics = if (includeDownloads) {
            val result = mutableListOf<ArchiveWriter.ComicSource>()
            for (item in galleryItems) {
                identities[item.id]?.let { identity -> comicSource(item, identity)?.let(result::add) }
            }
            result
        } else {
            emptyList()
        }
        val opds = ArchiveCredentialScrub.opdsCatalogs(OpdsProviderStore(prefs).readAll())
        val state = ArchiveState(
            bookmarksJson = CollectBookmarkStore(prefs).exportRaw(),
            historyJson = BrowseHistoryStore(prefs).exportRaw(),
            progress = galleryItems.mapNotNull { item ->
                val identity = identities[item.id] ?: return@mapNotNull null
                ArchiveProgress(
                    key = progressKey(identity),
                    title = item.title,
                    pageCount = item.pageCount,
                    ordinal = item.progressOrdinal,
                )
            },
            settings = currentSettings(),
            opdsCatalogs = opds.carried,
            opdsLeftBehind = opds.leftBehind,
            remoteProgressJson = RemoteReadingProgressStore(prefs).exportRaw(),
        )
        return ExportPlan(
            state = state,
            comics = comics,
            series = seriesByItem.values.map { source ->
                if (includeDownloads) source else source.withoutPages()
            },
            pageBytes = comics.sumOf { c -> c.pageFiles.sumOf { it.length() } } +
                if (includeDownloads) {
                    seriesByItem.values.sumOf { source ->
                        (source.coverFile?.length() ?: 0L) + source.chapters.sumOf { chapter -> chapter.pageFiles.sumOf { it.length() } }
                    }
                } else {
                    0L
                },
            leftBehindCatalogs = opds.leftBehind,
        )
    }

    fun export(
        out: File,
        passphrase: CharArray,
        plan: ExportPlan,
        onPage: (comicIndex: Int, bytesWritten: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        ArchiveWriter.write(out, passphrase, plan.state, appVersion, plan.comics, onPage, isCancelled, series = plan.series)
    }

    /** Streams into [output] (e.g. the document the user picked); closes it. */
    fun export(
        output: java.io.OutputStream,
        passphrase: CharArray,
        plan: ExportPlan,
        onPage: (comicIndex: Int, bytesWritten: Long) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ) {
        ArchiveWriter.write(output, passphrase, plan.state, appVersion, plan.comics, onPage, isCancelled, series = plan.series)
    }

    private suspend fun comicSource(item: LibraryItem, identity: GalleryIdentityV1): ArchiveWriter.ComicSource? {
        val present = item.pages.sortedBy { it.ordinal }.mapNotNull { page ->
            val uri = page.documentUri ?: return@mapNotNull null
            val file = runCatching { File(URI(uri)) }.getOrNull() ?: return@mapNotNull null
            file.takeIf { uri.startsWith("file:") && it.isFile && it.length() > 0 }?.let { page.ordinal to it }
        }
        if (present.isEmpty()) return null
        val names = present.mapIndexed { ordinal, (_, file) -> "$ordinal.${extensionOf(file)}" }
        val metadata = metadataFor(item.id)
        val pinned = item.pageCount.coerceAtLeast(item.pages.size)
        return ArchiveWriter.ComicSource(
            comic = ArchiveComic(
                providerNamespace = identity.providerNamespace,
                canonicalItemId = identity.canonicalItemId,
                title = item.title,
                tags = metadata.normalizedTags(),
                language = metadata.language,
                category = metadata.category,
                pageFiles = names,
                expectedPageCount = pinned.takeIf { present.size < it },
                progressOrdinal = present.count { (originalOrdinal, _) -> originalOrdinal < item.progressOrdinal }
                    .coerceIn(0, present.size - 1),
            ),
            pageFiles = present.map { it.second },
        )
    }

    private fun ArchiveWriter.SeriesSource.withoutPages(): ArchiveWriter.SeriesSource {
        val chapters = chapters.map { chapter ->
            val metadata = chapter.chapter.copy(pageFiles = emptyList())
            ArchiveWriter.ChapterSource(metadata, emptyList())
        }
        return ArchiveWriter.SeriesSource(series.copy(chapters = chapters.map { it.chapter }), coverFile = null, chapters = chapters)
    }

    private fun currentSettings() = ArchiveSettings(
        keepScreenOn = ReaderKeepScreenOnStore(prefs).read(),
        volumeKeys = ReaderVolumeKeyPagingStore(prefs).read(),
        rtl = ReaderRtlPagingStore(prefs).read(),
        libraryLayout = LibraryLayoutStore(prefs).read().name,
        ehentaiBrowse = EhentaiEnableStore(prefs).isEnabled(),
        nhentaiBrowse = NhentaiEnableStore(prefs).isEnabled(),
        jmBrowse = com.easemic.app.feature.providers.JmEnableStore(prefs).isEnabled(),
        cacheMb = CacheBudgetStore(prefs).readMb(),
        keepOnlyCloudCopy = CloudReleaseAfterUploadStore(prefs).read(),
        maxTransfers = MaxConcurrentTransfersStore(prefs).read(),
    )

    companion object {
        fun progressKey(identity: GalleryIdentityV1): String =
            galleryDedupKey("${identity.providerNamespace}://${identity.canonicalItemId}")
                ?: "${identity.providerNamespace}://${identity.canonicalItemId}"

        private fun extensionOf(file: File): String =
            file.extension.lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,5}")) } ?: "img"
    }
}
