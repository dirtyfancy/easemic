package com.easemic.app.feature.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

/**
 * Reader-facing page resolution helper (spec §4.5 / plan Task 9).
 *
 * During DOWNLOAD/FINISH (and any non-[DownloadJobState.NONE] job with a
 * current revision), ordinal N prefers verified READY via [ReadyPageStore],
 * then single-flight with the downloader, then upstream fetch that publishes
 * READY. Readers never open `.part` / temps.
 *
 * When no applicable job exists, returns the caller-supplied upstream display
 * URI without publishing into the READY store.
 *
 * Upstream failure throws [DownloadPageResolveException] — never a blank
 * success and never a FINISH transition (this helper does not mutate job state).
 */
class DownloadPageResolver(
    private val readyPageStore: ReadyPageStore,
    private val readerLease: AutoCloseable? = null,
    private val findJob: (GalleryIdentityV1) -> DownloadJob?,
) : AutoCloseable {
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    override fun close() {
        if (closed.compareAndSet(false, true)) readerLease?.close()
    }

    /**
     * Resolve a Coil/reader display URI for [ordinal].
     *
     * @param upstreamDisplayUri HTTPS (or other) URI used when there is no
     *   download job to prefer READY against.
     * @param fetchUpstreamBytes page bytes for READY publication when a job
     *   is active and the ordinal is not yet READY / in-flight.
     */
    fun resolveDisplayUri(
        identity: GalleryIdentityV1,
        ordinal: Int,
        upstreamDisplayUri: () -> String,
        fetchUpstreamBytes: () -> ByteArray,
    ): DownloadPageResolveResult {
        val job = findJob(identity)
        if (job != null && job.state != DownloadJobState.NONE) {
            try {
                val resolved = readyPageStore.resolve(
                    identity = identity,
                    currentRevision = job.downloadRevision,
                    ordinal = ordinal,
                    fetchUpstream = fetchUpstreamBytes,
                )
                val uri = resolved.path.toUri().toString()
                require(!uri.endsWith(".part")) {
                    "READY resolution must never return a .part path"
                }
                return DownloadPageResolveResult(
                    displayUri = uri,
                    source = resolved.source,
                )
            } catch (e: DownloadPageResolveException) {
                throw e
            } catch (e: InterruptedException) {
                throw e
            } catch (t: Throwable) {
                throw DownloadPageResolveException(
                    ordinal = ordinal,
                    message = "Couldn't load this page.",
                    cause = t,
                )
            }
        }

        val uri = upstreamDisplayUri().trim()
        if (uri.isEmpty()) {
            throw DownloadPageResolveException(
                ordinal = ordinal,
                message = "Couldn't load this page.",
            )
        }
        return DownloadPageResolveResult(
            displayUri = uri,
            source = PageResolveSource.UPSTREAM,
        )
    }

    /**
     * [resolveDisplayUri] on [Dispatchers.IO]. [runInterruptible] so cancelling
     * the reader interrupts the thread blocked in the follower's `Future.get`.
     * [withContext] would leave that thread parked until the leader finishes.
     */
    suspend fun resolveDisplayUriCancellable(
        identity: GalleryIdentityV1,
        ordinal: Int,
        upstreamDisplayUri: () -> String,
        fetchUpstreamBytes: () -> ByteArray,
    ): DownloadPageResolveResult = runInterruptible(Dispatchers.IO) {
        resolveDisplayUri(
            identity = identity,
            ordinal = ordinal,
            upstreamDisplayUri = upstreamDisplayUri,
            fetchUpstreamBytes = fetchUpstreamBytes,
        )
    }

    /**
     * Live progress of the download lane fetching [ordinal], for the reader's
     * "Downloading · N%" overlay (#E15 Task 3b). Mirrors [resolveDisplayUri]'s
     * job predicate: without an applicable job the reader would take the
     * upstream path and could never follow a flight, so there is none to
     * report.
     */
    fun followProgress(identity: GalleryIdentityV1, ordinal: Int): FollowProgress? {
        val job = findJob(identity) ?: return null
        if (job.state == DownloadJobState.NONE) return null
        return readyPageStore.followProgress(job.identity, job.downloadRevision, ordinal)
    }
}

data class DownloadPageResolveResult(
    val displayUri: String,
    val source: PageResolveSource,
)

/**
 * Explicit page error for reader UI. Does not imply download job FINISH/FAILED.
 */
class DownloadPageResolveException(
    val ordinal: Int,
    message: String = "Couldn't load this page.",
    cause: Throwable? = null,
) : Exception(message, cause)
