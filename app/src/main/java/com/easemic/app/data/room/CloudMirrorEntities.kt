package com.easemic.app.data.room

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Local Room mirror of the cloud collection (loyal-pipelines Slice B). Every
 * row is scoped by [bucketFingerprint] so rebinding to a different bucket
 * (spec: [com.easemic.app.feature.bridge.BucketFingerprint])
 * wipes and re-hydrates rather than mixing rows from two libraries.
 *
 * Tier-1 shelf data — enough for the Cloud shelf to render instantly/offline.
 * [chunkIndex] records which index chunk this card was hydrated from, so a
 * resumed hydration knows where it left off. [detailHydrated] tracks whether
 * tier-2 ([CloudMirrorDetailEntity]) has been fetched for this comic yet.
 * [generation] is the manifest generation this card was last upserted under —
 * a generation-bump re-walk stamps every surviving card with the new
 * generation as it re-walks chunk 0..N, so cards left behind on the old
 * generation once the re-walk finishes are ghosts (removed upstream) and are
 * pruned by [com.easemic.app.feature.collect.RoomCloudMirrorStore.pruneStaleCards].
 */
@Entity(
    tableName = "cloud_mirror_cards",
    primaryKeys = ["bucketFingerprint", "cloudComicId"],
    indices = [Index(value = ["bucketFingerprint"])],
)
data class CloudMirrorCardEntity(
    val bucketFingerprint: String,
    val cloudComicId: String,
    val title: String,
    val coverThumbObjectKey: String,
    val collectedAtEpochMs: Long,
    val pageCount: Int?,
    val language: String?,
    val acquisitionRef: String?,
    val trashedAtEpochMs: Long?,
    val chunkIndex: Int,
    val detailHydrated: Boolean,
    val generation: Int = 0,
    /**
     * S4 Task 1 (owner bug C4): absolute path to a durable on-device cover
     * JPEG once ingest or hydration has written one; null until then, in
     * which case the Cloud shelf falls back to a `downloadThumb(id, 0)` R2
     * read (see `com.easemic.app.ui.PrivateComicLibraryApp`'s
     * `coverUrlFromCacheOrNull`). Added by MIGRATION_11_12 (v11 -> v12),
     * additive-only.
     */
    val coverPath: String? = null,
    @ColumnInfo(defaultValue = "SINGLE_CHAPTER")
    val layoutKind: String = "SINGLE_CHAPTER",
    @ColumnInfo(defaultValue = "1")
    val chapterCount: Int = 1,
    @ColumnInfo(defaultValue = "1")
    val readableChapterCount: Int = 1,
    val seriesMetaObjectKey: String? = null,
    @ColumnInfo(defaultValue = "0")
    val seriesGeneration: Int = 0,
    val providerId: String? = null,
    val providerSeriesId: String? = null,
)

@Entity(
    tableName = "cloud_mirror_chapters",
    primaryKeys = ["bucketFingerprint", "cloudComicId", "chapterIdentity"],
    indices = [Index(value = ["bucketFingerprint", "cloudComicId", "ordinal"])],
    foreignKeys = [
        ForeignKey(
            entity = CloudMirrorCardEntity::class,
            parentColumns = ["bucketFingerprint", "cloudComicId"],
            childColumns = ["bucketFingerprint", "cloudComicId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CloudMirrorChapterEntity(
    val bucketFingerprint: String,
    val cloudComicId: String,
    val chapterIdentity: String,
    val chapterKey: String? = null,
    val ordinal: Int,
    val label: String?,
    val groupLabel: String?,
    val pageCount: Int?,
    val coverObjectKey: String?,
    val acquisitionRef: String?,
    @ColumnInfo(defaultValue = "ACTIVE")
    val sourceState: String = "ACTIVE",
    val trashedAtEpochMs: Long?,
    @ColumnInfo(defaultValue = "0")
    val detailHydrated: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val generation: Int = 0,
)

/**
 * Tier-2 per-comic detail mirror. Lists ([tagsJson]/[artistsJson]/[groupsJson])
 * are JSON-encoded (`org.json`, matching [com.easemic.app.sync.CloudComicMeta]'s
 * own codec style) rather than normalized child tables — this mirror is a
 * read cache, never a source of truth to query by tag.
 */
@Entity(
    tableName = "cloud_mirror_detail",
    primaryKeys = ["bucketFingerprint", "cloudComicId"],
)
data class CloudMirrorDetailEntity(
    val bucketFingerprint: String,
    val cloudComicId: String,
    val tagsJson: String,
    val artistsJson: String,
    val groupsJson: String,
    val category: String?,
    val providerId: String?,
    val truncatedFetchedPages: Int?,
    val seriesSnapshotJson: String? = null,
)

/**
 * Hydration checkpoint per bucket. [totalCards] is the manifest's declared
 * comic count ("M" in the spec's streaming-hydration math); [lastCompletedChunk]
 * is the last tier-1 index chunk fully written (-1 = none yet, so hydration
 * resumes from chunk 0); [generation] is the manifest generation used to
 * detect an upstream collection change mid- or post-hydration.
 */
@Entity(tableName = "cloud_mirror_state")
data class CloudMirrorStateEntity(
    @PrimaryKey val bucketFingerprint: String,
    val totalCards: Int,
    val lastCompletedChunk: Int,
    val hydrationComplete: Boolean,
    val generation: Int,
)
