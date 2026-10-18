package com.easemic.app.data.room

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.easemic.app.feature.providers.LegacySourceIds
import com.easemic.app.storage.SafProviderIdentity

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cloud_mirror_cards ADD COLUMN generation INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Additive migration for the durable per-comic sync ledger (S3 Task 1, owner
 * bug C3/A3). A brand-new table, so a trivial `CREATE TABLE IF NOT EXISTS`
 * with the composite primary key Room generates for
 * [com.easemic.app.data.room.CloudSyncLedgerEntity]. No
 * existing rows to backfill — an established install simply re-populates the
 * ledger the next time each comic uploads (or is confirmed present).
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cloud_sync_ledger (
                bucketFingerprint TEXT NOT NULL,
                acquisitionKey TEXT NOT NULL,
                cloudComicId TEXT NOT NULL,
                generation INTEGER NOT NULL,
                syncedAtMs INTEGER NOT NULL,
                PRIMARY KEY(bucketFingerprint, acquisitionKey)
            )
            """.trimIndent(),
        )
    }
}

/**
 * Additive migration for the bounded auto-revive attempt counter (S3 Task 4,
 * owner bug A6): a FAILED cloud-intent download job never re-enters the
 * consumption queue on its own, so [com.easemic.app.feature.download.downloadJobsToRevive]
 * needs a persisted counter to cap unattended retries of a
 * permanently-broken source. Single nullable-free column, default 0 for all
 * existing rows.
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE download_jobs ADD COLUMN autoRetryAttempts INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Additive migration for the auto-retry cooldown timestamp (S3 Task 8, owner
 * bug #6): [com.easemic.app.feature.download.downloadJobsToRevive]
 * retains the last auto-retry time for diagnostics and migration
 * compatibility. The attempt cap is lifetime-bounded. Single nullable-free
 * column, default 0 (never auto-retried) for all existing rows.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE download_jobs ADD COLUMN lastAutoRetryAtMs INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Additive migration for the durable on-device cloud cover (S4 Task 1, owner
 * bug C4): before this fix, the Cloud shelf re-read a live R2 thumb object
 * (`thumbs/0000.*`) into an ephemeral `cacheDir` file on every cold shelf
 * render — the file could be evicted at any time, forcing another R2 read.
 * [com.easemic.app.data.room.CloudMirrorCardEntity.coverPath]
 * instead names a durable `filesDir` JPEG written once at ingest (or lazily
 * at hydration/first-read fallback) so the shelf browses with zero R2 calls
 * once synced. Single nullable column, no default data migration needed —
 * existing rows simply have `coverPath = NULL` until the next sync/hydration
 * pass or a one-off `downloadThumb` fallback backfills it.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE cloud_mirror_cards ADD COLUMN coverPath TEXT")
    }
}

/**
 * Additive migration for A47 container archives (spec D1): one candidate row per archive
 * still, but a container's sub-comics persist durably before commit. New table
 * `scan_candidate_sub_comics` (one row per sub-comic), plus a `subComicOrdinal` discriminator
 * on `scan_candidate_pages` and `scan_candidate_claims` so sub-comic-owned rows are told
 * apart from flat rows. Defaults (-1 = flat) mean every pre-existing row reads exactly as
 * before -- no data migration, only schema.
 */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE scan_candidate_pages ADD COLUMN subComicOrdinal INTEGER NOT NULL DEFAULT -1",
        )
        db.execSQL(
            "ALTER TABLE scan_candidate_claims ADD COLUMN subComicOrdinal INTEGER NOT NULL DEFAULT -1",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS scan_candidate_sub_comics (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                candidateId INTEGER NOT NULL,
                ordinal INTEGER NOT NULL,
                relativePath TEXT NOT NULL,
                metadataJson TEXT NOT NULL,
                expectedPageCount INTEGER NOT NULL,
                structuralSignature TEXT NOT NULL,
                completeness TEXT NOT NULL,
                FOREIGN KEY(candidateId) REFERENCES scan_candidates(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_scan_candidate_sub_comics_candidateId_ordinal " +
                "ON scan_candidate_sub_comics (candidateId, ordinal)",
        )
    }
}

/**
 * Additive migration for A62 (spec D2): a page whose bytes were undecodable at import keeps
 * its slot and is marked, instead of being silently dropped by the indexer. The marker is
 * persisted on both the committed `pages` rows and the durable scan-candidate rows, so a
 * crash before commit loses nothing and the reader can fast-fail on the flag without
 * touching the archive. Defaults (0 = intact) mean every pre-existing row reads exactly as
 * before -- schema only, no data migration.
 */
val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pages ADD COLUMN corrupted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE scan_candidate_pages ADD COLUMN corrupted INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * C9 (2026-09-16): the Activity feed keeps a GONE (source delisted) row as a
 * Dismiss-only trace. Dismissal must not change the state — NONE would be
 * revived by the next enqueue and would drop the shelf's DELISTED projection —
 * so it is a timestamp mark. Additive; 0 = not dismissed, every existing row
 * reads exactly as before.
 */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE download_jobs ADD COLUMN dismissedAtMillis INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE reading_units ADD COLUMN sourceChapterId TEXT NOT NULL DEFAULT 'legacy:0'",
        )
        db.execSQL(
            "ALTER TABLE reading_units ADD COLUMN sourceGroup TEXT",
        )
        db.execSQL(
            "ALTER TABLE reading_units ADD COLUMN sourceState TEXT NOT NULL DEFAULT 'ACTIVE'",
        )
        db.execSQL(
            "ALTER TABLE reading_units ADD COLUMN sourceUpdatedAtMillis INTEGER",
        )
        db.execSQL(
            "UPDATE reading_units SET sourceChapterId = 'legacy:' || ordinal",
        )
        db.execSQL(
            "ALTER TABLE download_jobs ADD COLUMN chapterIdentity TEXT NOT NULL DEFAULT 'legacy:0'",
        )
        db.execSQL(
            "DROP INDEX IF EXISTS index_download_jobs_identityVersion_providerNamespace_canonicalItemId",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "index_download_jobs_identityVersion_providerNamespace_canonicalItemId_chapterIdentity " +
                "ON download_jobs (identityVersion, providerNamespace, canonicalItemId, chapterIdentity)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_reading_units_itemId_sourceChapterId " +
                "ON reading_units (itemId, sourceChapterId)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_provenance_claims_namespace_claimKey_claimValue " +
                "ON provenance_claims (namespace, claimKey, claimValue)",
        )
    }
}

val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE reading_units ADD COLUMN completed INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE reading_units ADD COLUMN lastReadAtMillis INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE metadata_snapshots ADD COLUMN coverLocator TEXT")
    }
}

val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE cloud_mirror_cards ADD COLUMN layoutKind TEXT NOT NULL DEFAULT 'SINGLE_CHAPTER'",
        )
        db.execSQL(
            "ALTER TABLE cloud_mirror_cards ADD COLUMN chapterCount INTEGER NOT NULL DEFAULT 1",
        )
        db.execSQL(
            "ALTER TABLE cloud_mirror_cards ADD COLUMN readableChapterCount INTEGER NOT NULL DEFAULT 1",
        )
        db.execSQL("ALTER TABLE cloud_mirror_cards ADD COLUMN seriesMetaObjectKey TEXT")
        db.execSQL("ALTER TABLE cloud_mirror_cards ADD COLUMN seriesGeneration INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE cloud_mirror_cards ADD COLUMN providerId TEXT")
        db.execSQL("ALTER TABLE cloud_mirror_cards ADD COLUMN providerSeriesId TEXT")

        db.execSQL(
            """
            CREATE TABLE cloud_sync_ledger_new (
                bucketFingerprint TEXT NOT NULL,
                acquisitionKey TEXT NOT NULL,
                cloudComicId TEXT NOT NULL,
                generation INTEGER NOT NULL,
                syncedAtMs INTEGER NOT NULL,
                chapterIdentity TEXT NOT NULL DEFAULT 'legacy:0',
                PRIMARY KEY(bucketFingerprint, acquisitionKey, chapterIdentity)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO cloud_sync_ledger_new (
                bucketFingerprint, acquisitionKey, cloudComicId, generation, syncedAtMs, chapterIdentity
            )
            SELECT bucketFingerprint, acquisitionKey, cloudComicId, generation, syncedAtMs, 'legacy:0'
            FROM cloud_sync_ledger
            """.trimIndent(),
        )
        db.execSQL("DROP TABLE cloud_sync_ledger")
        db.execSQL("ALTER TABLE cloud_sync_ledger_new RENAME TO cloud_sync_ledger")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_cloud_sync_ledger_bucketFingerprint_cloudComicId_chapterIdentity " +
                "ON cloud_sync_ledger (bucketFingerprint, cloudComicId, chapterIdentity)",
        )

        db.execSQL(
            """
            CREATE TABLE cloud_mirror_chapters (
                bucketFingerprint TEXT NOT NULL,
                cloudComicId TEXT NOT NULL,
                chapterIdentity TEXT NOT NULL,
                chapterKey TEXT,
                ordinal INTEGER NOT NULL,
                label TEXT,
                groupLabel TEXT,
                pageCount INTEGER,
                coverObjectKey TEXT,
                acquisitionRef TEXT,
                sourceState TEXT NOT NULL DEFAULT 'ACTIVE',
                trashedAtEpochMs INTEGER,
                detailHydrated INTEGER NOT NULL DEFAULT 0,
                generation INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(bucketFingerprint, cloudComicId, chapterIdentity),
                FOREIGN KEY(bucketFingerprint, cloudComicId)
                    REFERENCES cloud_mirror_cards(bucketFingerprint, cloudComicId)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_cloud_mirror_chapters_bucketFingerprint_cloudComicId_ordinal " +
                "ON cloud_mirror_chapters (bucketFingerprint, cloudComicId, ordinal)",
        )
        db.execSQL(
            """
            INSERT INTO cloud_mirror_chapters (
                bucketFingerprint, cloudComicId, chapterIdentity, ordinal, label, groupLabel,
                chapterKey, pageCount, coverObjectKey, acquisitionRef, sourceState, trashedAtEpochMs,
                detailHydrated, generation
            )
            SELECT bucketFingerprint, cloudComicId, 'legacy:0', 0, NULL, NULL, NULL,
                   pageCount, coverThumbObjectKey, acquisitionRef, 'ACTIVE', trashedAtEpochMs,
                   detailHydrated, generation
            FROM cloud_mirror_cards
            """.trimIndent(),
        )
    }
}

val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE metadata_snapshots ADD COLUMN synopsis TEXT")
        db.execSQL("ALTER TABLE metadata_snapshots ADD COLUMN seriesFactsJson TEXT")
        db.execSQL("ALTER TABLE cloud_mirror_detail ADD COLUMN seriesSnapshotJson TEXT")
    }
}

val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS chapter_updates (
                providerNamespace TEXT NOT NULL,
                providerSeriesId TEXT NOT NULL,
                providerChapterId TEXT NOT NULL,
                seriesTitle TEXT NOT NULL,
                chapterLabel TEXT NOT NULL,
                groupLabel TEXT,
                sourceUpdatedAtMillis INTEGER,
                discoveredAtMillis INTEGER NOT NULL,
                seenAtMillis INTEGER,
                PRIMARY KEY(providerNamespace, providerSeriesId, providerChapterId)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_chapter_updates_seenAtMillis_discoveredAtMillis " +
                "ON chapter_updates (seenAtMillis, discoveredAtMillis)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_chapter_updates_providerNamespace_providerSeriesId " +
                "ON chapter_updates (providerNamespace, providerSeriesId)",
        )
    }
}

/**
 * Value migration (audit finding F4): pre-rename download jobs still carry
 * the retired code-name provider namespaces. Left in place they would fall
 * into the OPDS-default throttle policy, miss every namespace-keyed failure
 * classifier, and never match CF-challenge surfacing. Rewrite them to the
 * current namespaces in place. Row identity
 * (`(identityVersion, providerNamespace, canonicalItemId)`) changes by
 * design — the rewrite is what makes the row match newly enqueued jobs
 * again. A same-identity row under the new namespace can only exist if the
 * user re-enqueued after the rename, in which case the legacy row is a
 * zombie duplicate and is dropped to keep the unique index satisfiable.
 *
 * The legacy literals live only in [SourceKeyMigration] (the whitelisted
 * shim); they are interpolated here via [LegacySourceIds].
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val front = LegacySourceIds.EHENTAI
        val index = LegacySourceIds.NHENTAI
        db.execSQL(
            """
            DELETE FROM download_jobs
            WHERE providerNamespace IN ('$front', '$index')
              AND EXISTS (
                  SELECT 1 FROM download_jobs newer
                  WHERE newer.identityVersion = download_jobs.identityVersion
                    AND newer.canonicalItemId = download_jobs.canonicalItemId
                    AND newer.providerNamespace = CASE download_jobs.providerNamespace
                        WHEN '$front' THEN 'ehentai'
                        WHEN '$index' THEN 'nhentai'
                    END
              )
            """.trimIndent(),
        )
        db.execSQL(
            "UPDATE download_jobs SET providerNamespace = 'ehentai' " +
                "WHERE providerNamespace = '$front'",
        )
        db.execSQL(
            "UPDATE download_jobs SET providerNamespace = 'nhentai' " +
                "WHERE providerNamespace = '$index'",
        )
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cloud_mirror_cards (
                bucketFingerprint TEXT NOT NULL,
                cloudComicId TEXT NOT NULL,
                title TEXT NOT NULL,
                coverThumbObjectKey TEXT NOT NULL,
                collectedAtEpochMs INTEGER NOT NULL,
                pageCount INTEGER,
                language TEXT,
                acquisitionRef TEXT,
                trashedAtEpochMs INTEGER,
                chunkIndex INTEGER NOT NULL,
                detailHydrated INTEGER NOT NULL,
                PRIMARY KEY(bucketFingerprint, cloudComicId)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_cloud_mirror_cards_bucketFingerprint " +
                "ON cloud_mirror_cards (bucketFingerprint)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cloud_mirror_detail (
                bucketFingerprint TEXT NOT NULL,
                cloudComicId TEXT NOT NULL,
                tagsJson TEXT NOT NULL,
                artistsJson TEXT NOT NULL,
                groupsJson TEXT NOT NULL,
                category TEXT,
                providerId TEXT,
                truncatedFetchedPages INTEGER,
                PRIMARY KEY(bucketFingerprint, cloudComicId)
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cloud_mirror_state (
                bucketFingerprint TEXT NOT NULL,
                totalCards INTEGER NOT NULL,
                lastCompletedChunk INTEGER NOT NULL,
                hydrationComplete INTEGER NOT NULL,
                generation INTEGER NOT NULL,
                PRIMARY KEY(bucketFingerprint)
            )
            """.trimIndent(),
        )
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE download_jobs ADD COLUMN intentLocal INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE download_jobs ADD COLUMN intentCloud INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE download_jobs ADD COLUMN sealedTruncatedAt INTEGER")
        db.execSQL("ALTER TABLE download_jobs ADD COLUMN failureClass TEXT")
        db.execSQL("ALTER TABLE download_jobs ADD COLUMN failureDetail TEXT")
        db.execSQL(
            "ALTER TABLE download_jobs ADD COLUMN resumeNotBeforeMs INTEGER NOT NULL DEFAULT 0",
        )
        // Backfill: every pre-v5 download job predates cloud-intent tracking and
        // was, by definition, a LOCAL download (v5 introduces intentCloud for the
        // upload-handoff path). Without this, legacy WAIT/DOWNLOAD/FINISH jobs
        // would carry intentLocal=0/intentCloud=0 — an intent-less job that the
        // loyal-pipelines fetch pump would never route.
        db.execSQL("UPDATE download_jobs SET intentLocal = 1")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS download_jobs (
                id TEXT NOT NULL,
                identityVersion INTEGER NOT NULL,
                providerNamespace TEXT NOT NULL,
                canonicalItemId TEXT NOT NULL,
                fallbackUrlDerived INTEGER NOT NULL,
                state TEXT NOT NULL,
                expectedPageCount INTEGER,
                downloadRevision INTEGER NOT NULL,
                enqueuedAtMillis INTEGER NOT NULL,
                updatedAtMillis INTEGER NOT NULL,
                PRIMARY KEY(id)
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS " +
                "index_download_jobs_identityVersion_providerNamespace_canonicalItemId " +
                "ON download_jobs (identityVersion, providerNamespace, canonicalItemId)",
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS download_ready_pages (
                jobId TEXT NOT NULL,
                downloadRevision INTEGER NOT NULL,
                ordinal INTEGER NOT NULL,
                readyAtMillis INTEGER NOT NULL,
                PRIMARY KEY(jobId, downloadRevision, ordinal),
                FOREIGN KEY(jobId) REFERENCES download_jobs(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_download_ready_pages_jobId " +
                "ON download_ready_pages (jobId)",
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS scan_work_requests (
                runId TEXT NOT NULL,
                requestId TEXT NOT NULL,
                updatedAtMillis INTEGER NOT NULL,
                PRIMARY KEY(runId),
                FOREIGN KEY(runId) REFERENCES scan_runs(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_scan_work_requests_requestId " +
                "ON scan_work_requests (requestId)",
        )
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE library_items RENAME TO legacy_library_items")
        db.execSQL("ALTER TABLE pages RENAME TO legacy_pages")
        createSchemaV2(db)

        db.query(
            """
            SELECT id, sourceTreeUri, sourceDisplayName, title, series, alternateSeries,
                   creators, genres, tags, language, pageCount, isAvailable, progressOrdinal
            FROM legacy_library_items
            ORDER BY id
            """.trimIndent(),
        ).use { items ->
            while (items.moveToNext()) {
                val itemId = items.getLong(0)
                val sourceUri = items.getString(1)
                val sourceLabel = items.getString(2)
                val parsedIdentity = SafProviderIdentity.treeIdentity(sourceUri)
                val providerIdentity = parsedIdentity?.providerIdentity
                    ?: SafProviderIdentity.legacyTree(sourceUri)
                val providerDocumentId = parsedIdentity?.treeDocumentId
                    ?: providerIdentity
                val isAvailable = items.getInt(11) != 0

                db.execSQL(
                    """
                    INSERT OR IGNORE INTO authorized_sources (
                        grantKind, adapterKind, contentUri, providerIdentity, displayLabel,
                        grantState, activeGeneration, completedGeneration, createdAtMillis,
                        updatedAtMillis
                    ) VALUES ('TREE', 'GENERIC_DIRECTORY', ?, ?, ?, 'NEEDS_VALIDATION', 0, NULL, 0, 0)
                    """.trimIndent(),
                    arrayOf<Any?>(sourceUri, providerIdentity, sourceLabel),
                )
                val sourceId = db.query(
                    """
                    SELECT id FROM authorized_sources
                    WHERE grantKind = 'TREE' AND providerIdentity = ?
                    """.trimIndent(),
                    arrayOf<Any?>(providerIdentity),
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                }

                db.execSQL(
                    """
                    INSERT OR IGNORE INTO comic_locations (
                        sourceId, providerDocumentId, documentUri, locationKind,
                        relativeDisplayPath, structuralSignature, lastCompleteGeneration, state
                    ) VALUES (?, ?, ?, 'DIRECTORY', ?, NULL, NULL, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        sourceId,
                        providerDocumentId,
                        sourceUri,
                        sourceLabel,
                        if (isAvailable) "PRESENT" else "UNAVAILABLE",
                    ),
                )
                val locationId = db.query(
                    """
                    SELECT id FROM comic_locations
                    WHERE sourceId = ? AND providerDocumentId = ?
                    """.trimIndent(),
                    arrayOf<Any?>(sourceId, providerDocumentId),
                ).use { cursor ->
                    check(cursor.moveToFirst())
                    cursor.getLong(0)
                }

                db.execSQL(
                    """
                    INSERT INTO library_items (id, historicalAvailable, createdAtMillis, updatedAtMillis)
                    VALUES (?, ?, 0, 0)
                    """.trimIndent(),
                    arrayOf<Any?>(itemId, if (isAvailable) 1 else 0),
                )
                db.execSQL(
                    """
                    INSERT INTO metadata_snapshots (
                        itemId, title, series, alternateSeries, creators, genres, tags,
                        language, expectedPageCount, createdAtMillis
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        itemId,
                        items.getString(3),
                        items.getNullableString(4),
                        items.getNullableString(5),
                        items.getString(6),
                        items.getString(7),
                        items.getString(8),
                        items.getNullableString(9),
                        items.getInt(10),
                    ),
                )
                val metadataId = db.lastInsertRowId()
                db.execSQL(
                    "INSERT INTO current_metadata (itemId, metadataSnapshotId) VALUES (?, ?)",
                    arrayOf<Any?>(itemId, metadataId),
                )

                db.execSQL(
                    """
                    INSERT INTO reading_units (itemId, label, ordinal, progressOrdinal, state)
                    VALUES (?, ?, 0, ?, 'READY')
                    """.trimIndent(),
                    arrayOf<Any?>(itemId, sourceLabel, items.getInt(12)),
                )
                val readingUnitId = db.lastInsertRowId()
                db.execSQL(
                    """
                    INSERT INTO media_revisions (
                        readingUnitId, locationId, structuralSignature, createdAtMillis
                    ) VALUES (?, ?, ?, 0)
                    """.trimIndent(),
                    arrayOf<Any?>(readingUnitId, locationId, "migrated-v1:$itemId"),
                )
                val revisionId = db.lastInsertRowId()
                db.execSQL(
                    """
                    INSERT INTO current_media_revisions (readingUnitId, mediaRevisionId)
                    VALUES (?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(readingUnitId, revisionId),
                )

                db.query(
                    """
                    SELECT ordinal, documentUri, displayName
                    FROM legacy_pages
                    WHERE itemId = ?
                    ORDER BY ordinal
                    """.trimIndent(),
                    arrayOf<Any?>(itemId),
                ).use { pages ->
                    while (pages.moveToNext()) {
                        db.execSQL(
                            """
                            INSERT INTO pages (
                                revisionId, ordinal, locatorKind, documentId, documentUri,
                                archiveEntryPath, displayName, mimeType, size,
                                lastModifiedMillis, width, height
                            ) VALUES (?, ?, 'DOCUMENT', NULL, ?, NULL, ?, NULL, NULL, NULL, 0, 0)
                            """.trimIndent(),
                            arrayOf<Any?>(
                                revisionId,
                                pages.getInt(0),
                                pages.getString(1),
                                pages.getString(2),
                            ),
                        )
                    }
                }
            }
        }

        db.execSQL("DROP TABLE legacy_pages")
        db.execSQL("DROP TABLE legacy_library_items")
    }
}

private fun createSchemaV2(db: SupportSQLiteDatabase) {
    SCHEMA_V2_STATEMENTS.forEach(db::execSQL)
}

private fun SupportSQLiteDatabase.lastInsertRowId(): Long =
    query("SELECT last_insert_rowid()").use { cursor ->
        check(cursor.moveToFirst())
        cursor.getLong(0)
    }

private fun android.database.Cursor.getNullableString(index: Int): String? =
    if (isNull(index)) null else getString(index)

private val SCHEMA_V2_STATEMENTS = listOf(
    """CREATE TABLE IF NOT EXISTS authorized_sources (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, grantKind TEXT NOT NULL, adapterKind TEXT NOT NULL, contentUri TEXT NOT NULL, providerIdentity TEXT NOT NULL, displayLabel TEXT NOT NULL, grantState TEXT NOT NULL, activeGeneration INTEGER NOT NULL, completedGeneration INTEGER, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL)""",
    """CREATE UNIQUE INDEX IF NOT EXISTS index_authorized_sources_grantKind_providerIdentity ON authorized_sources (grantKind, providerIdentity)""",
    """CREATE TABLE IF NOT EXISTS import_batches (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, createdAtMillis INTEGER NOT NULL)""",
    """CREATE TABLE IF NOT EXISTS import_batch_sources (batchId INTEGER NOT NULL, sourceId INTEGER NOT NULL, PRIMARY KEY(batchId, sourceId), FOREIGN KEY(batchId) REFERENCES import_batches(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(sourceId) REFERENCES authorized_sources(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    """CREATE INDEX IF NOT EXISTS index_import_batch_sources_sourceId ON import_batch_sources (sourceId)""",
    """CREATE TABLE IF NOT EXISTS comic_locations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, sourceId INTEGER NOT NULL, providerDocumentId TEXT NOT NULL, documentUri TEXT NOT NULL, locationKind TEXT NOT NULL, relativeDisplayPath TEXT NOT NULL, structuralSignature TEXT, lastCompleteGeneration INTEGER, state TEXT NOT NULL, FOREIGN KEY(sourceId) REFERENCES authorized_sources(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    """CREATE UNIQUE INDEX IF NOT EXISTS index_comic_locations_sourceId_providerDocumentId ON comic_locations (sourceId, providerDocumentId)""",
    """CREATE TABLE IF NOT EXISTS library_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, historicalAvailable INTEGER NOT NULL, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL)""",
    """CREATE TABLE IF NOT EXISTS metadata_snapshots (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, itemId INTEGER NOT NULL, title TEXT NOT NULL, series TEXT, alternateSeries TEXT, creators TEXT NOT NULL, genres TEXT NOT NULL, tags TEXT NOT NULL, language TEXT, expectedPageCount INTEGER, createdAtMillis INTEGER NOT NULL, FOREIGN KEY(itemId) REFERENCES library_items(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    """CREATE INDEX IF NOT EXISTS index_metadata_snapshots_itemId ON metadata_snapshots (itemId)""",
    """CREATE TABLE IF NOT EXISTS current_metadata (itemId INTEGER NOT NULL, metadataSnapshotId INTEGER NOT NULL, PRIMARY KEY(itemId), FOREIGN KEY(itemId) REFERENCES library_items(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(metadataSnapshotId) REFERENCES metadata_snapshots(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    """CREATE UNIQUE INDEX IF NOT EXISTS index_current_metadata_metadataSnapshotId ON current_metadata (metadataSnapshotId)""",
    """CREATE TABLE IF NOT EXISTS reading_units (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, itemId INTEGER NOT NULL, label TEXT NOT NULL, ordinal INTEGER NOT NULL, progressOrdinal INTEGER NOT NULL, state TEXT NOT NULL, FOREIGN KEY(itemId) REFERENCES library_items(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    """CREATE UNIQUE INDEX IF NOT EXISTS index_reading_units_itemId_ordinal ON reading_units (itemId, ordinal)""",
    """CREATE TABLE IF NOT EXISTS media_revisions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, readingUnitId INTEGER NOT NULL, locationId INTEGER NOT NULL, structuralSignature TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, FOREIGN KEY(readingUnitId) REFERENCES reading_units(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(locationId) REFERENCES comic_locations(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    """CREATE INDEX IF NOT EXISTS index_media_revisions_locationId ON media_revisions (locationId)""",
    """CREATE UNIQUE INDEX IF NOT EXISTS index_media_revisions_readingUnitId_structuralSignature ON media_revisions (readingUnitId, structuralSignature)""",
    """CREATE TABLE IF NOT EXISTS current_media_revisions (readingUnitId INTEGER NOT NULL, mediaRevisionId INTEGER NOT NULL, PRIMARY KEY(readingUnitId), FOREIGN KEY(readingUnitId) REFERENCES reading_units(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(mediaRevisionId) REFERENCES media_revisions(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    """CREATE UNIQUE INDEX IF NOT EXISTS index_current_media_revisions_mediaRevisionId ON current_media_revisions (mediaRevisionId)""",
    """CREATE TABLE IF NOT EXISTS pages (revisionId INTEGER NOT NULL, ordinal INTEGER NOT NULL, locatorKind TEXT NOT NULL, documentId TEXT, documentUri TEXT, archiveEntryPath TEXT, displayName TEXT NOT NULL, mimeType TEXT, size INTEGER, lastModifiedMillis INTEGER, width INTEGER NOT NULL, height INTEGER NOT NULL, PRIMARY KEY(revisionId, ordinal), FOREIGN KEY(revisionId) REFERENCES media_revisions(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    """CREATE INDEX IF NOT EXISTS index_pages_revisionId ON pages (revisionId)""",
    """CREATE TABLE IF NOT EXISTS provenance_claims (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, itemId INTEGER NOT NULL, namespace TEXT NOT NULL, claimKey TEXT NOT NULL, claimValue TEXT NOT NULL, FOREIGN KEY(itemId) REFERENCES library_items(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    """CREATE UNIQUE INDEX IF NOT EXISTS index_provenance_claims_itemId_namespace_claimKey_claimValue ON provenance_claims (itemId, namespace, claimKey, claimValue)""",
    """CREATE TABLE IF NOT EXISTS scan_runs (id TEXT NOT NULL, sourceId INTEGER NOT NULL, generation INTEGER NOT NULL, phase TEXT NOT NULL, state TEXT NOT NULL, enumerationComplete INTEGER NOT NULL, pauseRequested INTEGER NOT NULL, enumerationAttemptId TEXT, reviewAttemptId TEXT, checkpointLastCandidateId INTEGER, checkpointProcessedCount INTEGER NOT NULL, discoveredCount INTEGER NOT NULL, selectedCount INTEGER NOT NULL, committedCount INTEGER NOT NULL, highConfidenceCount INTEGER NOT NULL, lowConfidenceCount INTEGER NOT NULL, incompleteCount INTEGER NOT NULL, rejectedCount INTEGER NOT NULL, failedCount INTEGER NOT NULL, problem TEXT, recoveryAction TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, PRIMARY KEY(id), FOREIGN KEY(sourceId) REFERENCES authorized_sources(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    """CREATE UNIQUE INDEX IF NOT EXISTS index_scan_runs_sourceId_generation ON scan_runs (sourceId, generation)""",
    """CREATE TABLE IF NOT EXISTS active_scan_runs (sourceId INTEGER NOT NULL, runId TEXT NOT NULL, PRIMARY KEY(sourceId), FOREIGN KEY(sourceId) REFERENCES authorized_sources(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(runId) REFERENCES scan_runs(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    """CREATE UNIQUE INDEX IF NOT EXISTS index_active_scan_runs_runId ON active_scan_runs (runId)""",
    """CREATE TABLE IF NOT EXISTS scan_candidates (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, runId TEXT NOT NULL, providerDocumentId TEXT NOT NULL, documentUri TEXT NOT NULL, enumerationAttemptId TEXT NOT NULL, seedDisplayName TEXT NOT NULL, locationKind TEXT NOT NULL, selectedDirectly INTEGER NOT NULL, recognitionKind TEXT NOT NULL, relativeDisplayPath TEXT, confidence TEXT, completeness TEXT, outcome TEXT NOT NULL, selected INTEGER NOT NULL, metadataJson TEXT, expectedPageCount INTEGER, structuralSignature TEXT, warningsJson TEXT, rejectionReason TEXT, rejectionArchiveFailure TEXT, retryReason TEXT, FOREIGN KEY(runId) REFERENCES scan_runs(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    """CREATE UNIQUE INDEX IF NOT EXISTS index_scan_candidates_runId_enumerationAttemptId_providerDocumentId ON scan_candidates (runId, enumerationAttemptId, providerDocumentId)""",
    """CREATE INDEX IF NOT EXISTS index_scan_candidates_runId_enumerationAttemptId ON scan_candidates (runId, enumerationAttemptId)""",
    """CREATE TABLE IF NOT EXISTS scan_candidate_pages (candidateId INTEGER NOT NULL, ordinal INTEGER NOT NULL, locatorKind TEXT NOT NULL, documentId TEXT, documentUri TEXT, archiveEntryPath TEXT, displayName TEXT NOT NULL, mimeType TEXT, size INTEGER, lastModifiedMillis INTEGER, width INTEGER NOT NULL, height INTEGER NOT NULL, PRIMARY KEY(candidateId, ordinal), FOREIGN KEY(candidateId) REFERENCES scan_candidates(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    """CREATE INDEX IF NOT EXISTS index_scan_candidate_pages_candidateId ON scan_candidate_pages (candidateId)""",
    """CREATE TABLE IF NOT EXISTS scan_candidate_claims (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, candidateId INTEGER NOT NULL, namespace TEXT NOT NULL, claimKey TEXT NOT NULL, claimValue TEXT NOT NULL, FOREIGN KEY(candidateId) REFERENCES scan_candidates(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
    """CREATE INDEX IF NOT EXISTS index_scan_candidate_claims_candidateId ON scan_candidate_claims (candidateId)""",
)
