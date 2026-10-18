package com.easemic.app.feature.download

import com.easemic.app.i18n.AppStrings

/**
 * English empty-state copy for More → Download queue (spec §4.3).
 */
object DownloadQueueEmptyCopy {
    val TITLE: String get() = AppStrings.noDownloadsQueue
    val SUBTITLE: String get() = AppStrings.downloadsQueueSubtitle
}

/**
 * Consumption-queue membership: exactly WAIT + DOWNLOAD, FIFO by enqueue time
 * then job id (matches Room `ORDER BY enqueuedAtMillis ASC, id ASC`), further
 * excluding jobs still inside a QUOTA/ban/pacing idle window
 * ([DownloadJob.resumeNotBeforeMs] in the future relative to [now]) — matches
 * Room's `consumptionQueueJobs` `WHERE resumeNotBeforeMs <= :now` clause so
 * the JVM in-memory oracle ([DownloadCore]) and production Room storage agree
 * on when a parked job becomes runnable again.
 *
 * Spec default is FIFO by enqueue time; EH DownloadManager was unavailable in
 * this worktree's research clone, so we keep that recorded plan default.
 */
fun filterConsumptionQueueFifo(jobs: List<DownloadJob>, now: Long): List<DownloadJob> =
    jobs
        .filter { it.state.isInConsumptionQueue() && it.resumeNotBeforeMs <= now }
        .sortedWith(compareBy({ it.enqueuedAtMillis }, { it.id }))

/**
 * Earliest future [DownloadJob.resumeNotBeforeMs] among still-runnable
 * (WAIT/DOWNLOAD) jobs relative to [now], or null when none are windowed
 * into the future. Startup replay (spec §1.4 FIX2):
 * [filterConsumptionQueueFifo] (and Room's matching `consumptionQueueJobs`
 * query) already excludes a job still inside a QUOTA/ban idle window, so a
 * cold start whose only WAIT/DOWNLOAD jobs are parked rather than gone for
 * good sees an empty consumption queue — nothing else re-checks
 * `resumeNotBeforeMs` once it lapses, so without this the pump would sit
 * inert forever instead of just until the window passes. Callers use this to
 * schedule a one-time delayed kick instead of the immediate one.
 */
fun earliestResumeAfter(jobs: List<DownloadJob>, now: Long): Long? =
    jobs
        .asSequence()
        .filter { it.state.isInConsumptionQueue() && it.resumeNotBeforeMs > now }
        .map { it.resumeNotBeforeMs }
        .minOrNull()
