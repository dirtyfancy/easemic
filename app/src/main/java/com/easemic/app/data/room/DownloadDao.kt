package com.easemic.app.data.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertJob(job: DownloadJobEntity)

    @Update
    suspend fun updateJob(job: DownloadJobEntity)

    @Query("SELECT * FROM download_jobs WHERE id = :jobId LIMIT 1")
    suspend fun jobById(jobId: String): DownloadJobEntity?

    @Query(
        """
        SELECT * FROM download_jobs
        WHERE identityVersion = :identityVersion
          AND providerNamespace = :providerNamespace
          AND canonicalItemId = :canonicalItemId
          AND chapterIdentity = :chapterIdentity
        LIMIT 1
        """,
    )
    suspend fun jobByIdentity(
        identityVersion: Int,
        providerNamespace: String,
        canonicalItemId: String,
        chapterIdentity: String,
    ): DownloadJobEntity?

    @Query(
        """
        SELECT * FROM download_jobs
        WHERE identityVersion = :identityVersion
          AND providerNamespace = :providerNamespace
          AND canonicalItemId = :canonicalItemId
          AND chapterIdentity = :chapterIdentity
        LIMIT 1
        """,
    )
    fun observeJobByIdentity(
        identityVersion: Int,
        providerNamespace: String,
        canonicalItemId: String,
        chapterIdentity: String,
    ): Flow<List<DownloadJobEntity>>

    /** All chapter jobs for one serialized series; chapterIdentity is intentionally not filtered. */
    @Query(
        """
        SELECT * FROM download_jobs
        WHERE identityVersion = :identityVersion
          AND providerNamespace = :providerNamespace
          AND canonicalItemId = :canonicalItemId
        ORDER BY enqueuedAtMillis ASC, id ASC
        """,
    )
    fun observeJobsForSeries(
        identityVersion: Int,
        providerNamespace: String,
        canonicalItemId: String,
    ): Flow<List<DownloadJobEntity>>

    /** One-shot companion for destructive serialized-series actions. */
    @Query(
        """
        SELECT * FROM download_jobs
        WHERE identityVersion = :identityVersion
          AND providerNamespace = :providerNamespace
          AND canonicalItemId = :canonicalItemId
        ORDER BY enqueuedAtMillis ASC, id ASC
        """,
    )
    suspend fun jobsForSeries(
        identityVersion: Int,
        providerNamespace: String,
        canonicalItemId: String,
    ): List<DownloadJobEntity>

    /**
     * READY page counts at each job's current revision for one series. Reads `download_ready_pages`, so a page
     * becoming ready re-emits even though the job row itself is untouched ([observeJobsForSeries] alone would not).
     */
    @Query(
        """
        SELECT r.jobId AS jobId, COUNT(*) AS readyCount
        FROM download_ready_pages r
        JOIN download_jobs j ON j.id = r.jobId AND r.downloadRevision = j.downloadRevision
        WHERE j.identityVersion = :identityVersion
          AND j.providerNamespace = :providerNamespace
          AND j.canonicalItemId = :canonicalItemId
        GROUP BY r.jobId
        """,
    )
    fun observeReadyCountsForSeries(
        identityVersion: Int,
        providerNamespace: String,
        canonicalItemId: String,
    ): Flow<List<JobReadyCount>>

    @Query("DELETE FROM download_ready_pages WHERE jobId = :jobId")
    suspend fun deleteReadyPagesForJob(jobId: String)

    /**
     * #C19-F12: remove a job ROW outright (every other exit ends at NONE).
     * READY rows cascade through the FK on `download_ready_pages.jobId`. Only
     * the `opds:opds:` double-namespace sweep uses it.
     */
    @Query("DELETE FROM download_jobs WHERE id = :jobId")
    suspend fun deleteJobById(jobId: String)

    @Query("SELECT * FROM download_jobs ORDER BY enqueuedAtMillis ASC, id ASC")
    suspend fun allJobs(): List<DownloadJobEntity>

    @Query(
        """
        SELECT * FROM download_jobs
        WHERE (state = 'WAIT' OR state = 'DOWNLOAD')
          AND resumeNotBeforeMs <= :now
        ORDER BY enqueuedAtMillis ASC, id ASC
        """,
    )
    suspend fun consumptionQueueJobs(now: Long): List<DownloadJobEntity>

    @Query(
        """
        SELECT * FROM download_jobs
        WHERE state = 'WAIT' OR state = 'DOWNLOAD'
        ORDER BY enqueuedAtMillis ASC, id ASC
        """,
    )
    fun observeConsumptionQueueJobs(): Flow<List<DownloadJobEntity>>

    /**
     * A20 (owner bug backlog): Activity-tab membership = the consumption queue
     * PLUS hard-failed jobs, FIFO by enqueue time. FAILED is included so the
     * tab can show the recorded reason and offer a manual Retry — a job that
     * dies must never simply vanish from the only place a user looks. Terminal
     * successes (FINISH/SEALED) and NONE (cancelled/deleted) stay out. #C9
     * (owner ruling 2026-09-16): a GONE row (source delisted — nothing to
     * retry) STAYS as a Dismiss-only trace until the user dismisses it
     * ([DownloadJobEntity.dismissedAtMillis]); it used to vanish the tick it
     * was sealed, which made the verdict harder to notice, not easier. Used
     * ONLY by the Activity tab; [observeConsumptionQueueJobs] stays
     * WAIT/DOWNLOAD for the legacy More → Download queue route and the
     * Cloudflare-challenge banner.
     */
    @Query(
        """
        SELECT * FROM download_jobs
        WHERE state = 'WAIT' OR state = 'DOWNLOAD' OR state = 'FAILED'
           OR (state = 'GONE' AND dismissedAtMillis = 0)
        ORDER BY enqueuedAtMillis ASC, id ASC
        """,
    )
    fun observeActivityJobs(): Flow<List<DownloadJobEntity>>

    /** Chapter identity/state projection used by serialized Activity populations. */
    @Query(
        """
        SELECT providerNamespace, canonicalItemId, chapterIdentity, state
        FROM download_jobs
        WHERE chapterIdentity != 'legacy:0'
          AND state IN ('WAIT', 'DOWNLOAD', 'FINISH', 'FAILED')
        ORDER BY enqueuedAtMillis ASC, id ASC
        """,
    )
    fun observeSerializedChapterJobs(): Flow<List<SerializedChapterJob>>

    /** Live chapter totals for Activity's one-row-per-serialized-series view. */
    @Query(
        """
        SELECT providerNamespace,
               canonicalItemId,
               COUNT(*) AS total,
               SUM(CASE WHEN state = 'FINISH' THEN 1 ELSE 0 END) AS saved
        FROM download_jobs
        WHERE chapterIdentity != 'legacy:0'
          AND state != 'NONE'
        GROUP BY providerNamespace, canonicalItemId
        ORDER BY MIN(enqueuedAtMillis) ASC, providerNamespace ASC, canonicalItemId ASC
        """,
    )
    fun observeSerializedSeriesCounts(): Flow<List<SerializedSeriesJobCount>>

    /**
     * #C9: every job the source delisted — a zero-page GONE seal, or a
     * truncated seal under the GONE verdict — regardless of dismissal (that
     * is an Activity-row exit only). The Library shelf reduces these to
     * acquisition keys so a collected-only (Remote) tile can carry the
     * slashed cloud; Local tiles read the same fact from the shelf
     * projection's CASE. Mirrors [com.easemic.app.feature.download.isDelistedAtSource]
     * — pinned by `SourceDelistedSqlGuardTest`.
     */
    @Query(
        """
        SELECT * FROM download_jobs
        WHERE state = 'GONE' OR (state = 'SEALED' AND failureClass = 'GONE')
        ORDER BY enqueuedAtMillis ASC, id ASC
        """,
    )
    fun observeDelistedJobs(): Flow<List<DownloadJobEntity>>

    /**
     * Clears the QUOTA/ban idle window (and the now-stale failure record) for
     * every still-runnable job parked on ABUSE_STOP — called after a
     * successful Cloudflare-challenge solve so the pump's very next kick
     * treats the parked job as immediately runnable again instead of waiting
     * out its resume window. Scoped to WAIT/DOWNLOAD only: a terminal job
     * (FINISH/SEALED/GONE/FAILED) keeps its historical failure record.
     */
    @Query(
        """
        UPDATE download_jobs
        SET resumeNotBeforeMs = 0, failureClass = NULL, failureDetail = NULL
        WHERE failureClass = 'ABUSE_STOP'
          AND (state = 'WAIT' OR state = 'DOWNLOAD')
        """,
    )
    suspend fun clearResumeWindowForAbuseStop()

    /**
     * A31 (owner bug backlog): drops the cloud fetch intent for a dual-intent
     * job whose Collect bookmark was removed while the local download is
     * still wanted — the job keeps running/holding its terminal state
     * unchanged, only [DownloadJobEntity.intentCloud] flips off so
     * `enqueueCloudUploadForJob`/the cold-start repair sweep never derive an
     * upload row for it again. Deliberately unguarded by state (unlike
     * `cancel`/`markSealed`/etc.) — a dual-intent job can be uncollected from
     * WAIT, DOWNLOAD, or any terminal state alike.
     */
    @Query(
        """
        UPDATE download_jobs
        SET intentCloud = 0, updatedAtMillis = :nowMillis
        WHERE id = :jobId
        """,
    )
    suspend fun clearIntentCloud(jobId: String, nowMillis: Long)

    @Query("SELECT * FROM download_ready_pages")
    fun observeReadyPages(): Flow<List<DownloadReadyPageEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReadyPage(page: DownloadReadyPageEntity)

    @Query(
        """
        SELECT ordinal FROM download_ready_pages
        WHERE jobId = :jobId AND downloadRevision = :downloadRevision
        ORDER BY ordinal ASC
        """,
    )
    suspend fun readyOrdinals(jobId: String, downloadRevision: Int): List<Int>

    /**
     * @param libraryRowAvailable A36 review N1: whether the Library already has
     *   an AVAILABLE on-device row for this identity
     *   ([com.easemic.app.data.DownloadLibraryAvailability.downloadOnDeviceAssociationCount]).
     *   Only consulted for a SEALED row. Defaults to true — "assume the comic
     *   is already on the shelf" — which is exactly the pre-existing
     *   merge-only behaviour, so a caller that cannot check can never trigger
     *   the revive by omission.
     */
    @Transaction
    suspend fun upsertEnqueueJob(
        candidate: DownloadJobEntity,
        nowMillis: Long,
        libraryRowAvailable: Boolean = true,
    ): DownloadJobEntity {
        val existing = jobByIdentity(
            identityVersion = candidate.identityVersion,
            providerNamespace = candidate.providerNamespace,
            canonicalItemId = candidate.canonicalItemId,
            chapterIdentity = candidate.chapterIdentity,
        )
        if (existing != null) {
            val mergedLocal = existing.intentLocal || candidate.intentLocal
            val mergedCloud = existing.intentCloud || candidate.intentCloud
            // A36 review N1: a SEALED row with nothing on the shelf. That is a
            // cloud-only fetch (its pages went to R2 and are waiting to be
            // released), or a local one whose row is gone. Merging intents
            // here would make Download a no-op that ALSO stamps intentLocal on
            // a cloud-only seal — which then blocks S4-T3's release and A31's
            // discard forever, because both refuse a job with a local intent.
            // So either the tap really re-runs the fetch, or it must not
            // touch the row at all.
            val revivesSealed = existing.state == "SEALED" &&
                candidate.intentLocal &&
                !libraryRowAvailable
            if (existing.state != "NONE" && !revivesSealed) {
                if (mergedLocal == existing.intentLocal && mergedCloud == existing.intentCloud) {
                    return existing
                }
                val merged = existing.copy(intentLocal = mergedLocal, intentCloud = mergedCloud)
                updateJob(merged)
                return merged
            }
            val ready = readyOrdinals(existing.id, existing.downloadRevision)
            val revision = if (ready.isEmpty()) {
                existing.downloadRevision + 1
            } else {
                existing.downloadRevision
            }
            val revived = existing.copy(
                state = "WAIT",
                expectedPageCount = candidate.expectedPageCount ?: existing.expectedPageCount,
                downloadRevision = revision,
                updatedAtMillis = nowMillis,
                // C4 §2.2 (owner: "用户想拿回本地仍可点下载"): a NONE row is
                // finished business — its pages are gone (released to cloud,
                // deleted from Detail) and its old intents describe an attempt
                // that is over. The tap reviving it is what defines what the
                // new attempt is FOR, so it brings its own intents. Merging the
                // old ones back in is how a pull-back ("give me this cloud
                // comic on device again") became a DUAL-intent job whose ledger
                // row already proves a copy in the bound bucket — and
                // `AppGraph.releaseLocalPagesForCloudOnlyJobs`, which runs on
                // every foreground with the live "Keep only the cloud copy"
                // setting, then deleted the copy the user had just fetched.
                //
                // The `revivesSealed` path keeps the merge: that row is still
                // live work (a cloud-only seal waiting for its release), not a
                // finished one, so its cloud intent is a standing request.
                intentLocal = if (revivesSealed) mergedLocal else candidate.intentLocal,
                intentCloud = if (revivesSealed) mergedCloud else candidate.intentCloud,
                // P1: whatever ended the last attempt is not this attempt's
                // reason, and a WAIT row advertising GONE reads as terminal.
                failureClass = null,
                failureDetail = null,
                // A36 review O2: the old seal's truncation point describes a
                // fetch that is being re-run. Left behind, it survives into
                // the next completion and is read as this attempt's page
                // count — by the Detail note and by the cloud upload row's
                // CloudComicMeta.
                sealedTruncatedAt = null,
                // The pump's queue query filters on this, so a stale park
                // window from the old attempt would leave the revived job
                // invisible to it — a Download tap that changes nothing.
                resumeNotBeforeMs = 0L,
                // C9 audit M1: a dismissal is an Activity-row exit for the LAST
                // attempt; carried into a revive it would make the next GONE
                // seal invisible from birth.
                dismissedAtMillis = 0L,
            )
            updateJob(revived)
            return revived
        }
        insertJob(candidate)
        return candidate
    }
}

/** One job's READY page count at its current revision. */
data class JobReadyCount(val jobId: String, val readyCount: Int)

/** Read-only identity/state projection for a serialized chapter job. */
data class SerializedChapterJob(
    val providerNamespace: String,
    val canonicalItemId: String,
    val chapterIdentity: String,
    val state: String,
)

/** Count of active serialized chapter jobs for one provider series. */
data class SerializedSeriesJobCount(
    val providerNamespace: String,
    val canonicalItemId: String,
    val total: Int,
    val saved: Int,
)
