package com.easemic.app.data.room

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AuthorizedSourceEntity::class, ImportBatchEntity::class, ImportBatchSourceEntity::class,
        ComicLocationEntity::class, LibraryItemEntity::class, MetadataSnapshotEntity::class,
        CurrentMetadataEntity::class, ReadingUnitEntity::class, MediaRevisionEntity::class,
        CurrentMediaRevisionEntity::class, PageEntity::class, ProvenanceClaimEntity::class,
        ScanRunEntity::class, ActiveScanRunEntity::class, ScanCandidateEntity::class,
        ScanCandidatePageEntity::class, ScanCandidateClaimEntity::class, ScanWorkRequestEntity::class,
        ScanCandidateSubComicEntity::class,
        DownloadJobEntity::class, DownloadReadyPageEntity::class,
        CloudMirrorCardEntity::class, CloudMirrorDetailEntity::class, CloudMirrorStateEntity::class,
        CloudMirrorChapterEntity::class,
        CloudSyncLedgerEntity::class,
        ChapterUpdateEntity::class,
    ],
    version = 20,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun sourceDao(): SourceDao
    abstract fun scanRunDao(): ScanRunDao
    abstract fun downloadDao(): DownloadDao
    abstract fun cloudMirrorDao(): CloudMirrorDao
    abstract fun cloudSyncLedgerDao(): CloudSyncLedgerDao
    abstract fun chapterUpdateDao(): ChapterUpdateDao
}
