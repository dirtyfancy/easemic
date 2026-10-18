package com.easemic.app.feature.download

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.security.MessageDigest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.withTimeout

/**
 * File-backed READY page publication (spec §4.5).
 *
 * A page is locally readable only after temp write → close → validate → atomic
 * publish. Readers never open `.part` / in-progress temps. Pages under a
 * superseded download revision are never READY for the current revision.
 *
 * Resolution precedence for ordinal N:
 * 1. verified READY for identity + revision + ordinal
 * 2. shared in-flight request (single-flight with downloader)
 * 3. provider-adapter upstream fetch (caller-supplied)
 */
class ReadyPageStore(
    private val root: Path,
    /**
     * One follower wait window (#E15 Task 3b). The wait renews while the
     * leader's `.part` file changes size, so a follower no longer fails after
     * a single fixed window while a large page is still streaming.
     */
    private val followerWindowMs: Long = 30_000L,
    private val scheduleLeaseCleanup: ((() -> Unit) -> Unit) = { it() },
) {
    private val publishedKeys = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Path>>()
    private val committedPages = ConcurrentHashMap<String, List<Path>>()
    private val readers = mutableMapOf<String, Int>()
    private val pendingRelease = mutableMapOf<String, Pair<GalleryIdentityV1, Int>>()

    /** A reader may already hold a staging URI when FINISH commits. Keep it valid
     * until that session closes; new resolutions prefer the committed catalog. */
    @Synchronized
    fun retainReader(identity: GalleryIdentityV1, revision: Int): AutoCloseable {
        val key = revisionKeyPrefix(identity, revision)
        readers[key] = (readers[key] ?: 0) + 1
        var closed = false
        return AutoCloseable {
            synchronized(this) {
                if (!closed) {
                    closed = true
                    val remaining = (readers[key] ?: 1) - 1
                    if (remaining > 0) readers[key] = remaining else {
                        readers.remove(key)
                        pendingRelease[key]?.let { (id, rev) ->
                            val pages = committedPages[key].orEmpty()
                            scheduleLeaseCleanup {
                                releaseStagingAfterCommit(id, rev, pages)
                            }
                        }
                    }
                }
            }
        }
    }

    /** Called ONLY after a durable FINISH commit. False means absent/deferred/
     * incomplete/failed cleanup, never permission to drop the job or READY rows. */
    @Synchronized
    fun releaseStagingAfterCommit(
        identity: GalleryIdentityV1,
        revision: Int,
        catalogPages: List<Path>,
    ): Boolean {
        if (catalogPages.isEmpty()) return false
        val normalizedRoot = root.toAbsolutePath().normalize()
        val realRoot = runCatching { root.toRealPath() }.getOrNull() ?: return false
        val valid = runCatching {
            catalogPages.withIndex().all { (ordinal, path) ->
                !path.toAbsolutePath().normalize().startsWith(normalizedRoot) &&
                    path.fileName.toString() == ordinal.toString() &&
                    path.parent == catalogPages.first().parent &&
                    Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(path) &&
                        Files.size(path) > 0L &&
                        !path.toRealPath().startsWith(realRoot)
            }
        }.getOrDefault(false)
        if (!valid) return false
        val key = revisionKeyPrefix(identity, revision)
        committedPages[key] = catalogPages.toList()
        if (!Files.isDirectory(pageDir(identity, revision))) return false
        pendingRelease[key] = identity to revision
        if ((readers[key] ?: 0) > 0) return false
        val removed = deleteStagingRevision(identity, revision)
        if (removed) pendingRelease.remove(key)
        return removed
    }


    fun flightKey(identity: GalleryIdentityV1, revision: Int, ordinal: Int): String =
        listOf(
            GalleryIdentityV1.IDENTITY_VERSION.toString(),
            identity.providerNamespace,
            identity.canonicalItemId,
            revision.toString(),
            ordinal.toString(),
        ).joinToString("\u0000")

    fun readyPath(identity: GalleryIdentityV1, revision: Int, ordinal: Int): Path =
        pageDir(identity, revision).resolve("$ordinal")

    fun partPath(identity: GalleryIdentityV1, revision: Int, ordinal: Int): Path =
        pageDir(identity, revision).resolve("$ordinal.part")

    /** #E101: the staging revision directory for size accounting; null when absent. */
    fun stagingDirectory(identity: GalleryIdentityV1, revision: Int): File? =
        pageDir(identity, revision).toFile().takeIf { it.isDirectory }

    /**
     * Reader-visible progress of the in-flight transfer for this exact flight
     * key (#E15 Task 3b), or null when nothing is in flight.
     * [FollowProgress.bytes] is the current `.part` size (0 before the
     * leader's first byte); [FollowProgress.expectedBytes] comes from the
     * `.part.meta` provenance when it holds `<sha1>-<bytes>` (E-Hentai writes
     * it before requesting any bytes) and is null otherwise.
     */
    fun followProgress(
        identity: GalleryIdentityV1,
        revision: Int,
        ordinal: Int,
    ): FollowProgress? {
        if (!inFlight.containsKey(flightKey(identity, revision, ordinal))) return null
        val part = partPath(identity, revision, ordinal)
        val bytes = partSizeOrNull(part) ?: 0L
        val expectedBytes = runCatching {
            parseProvenanceExpectedBytes(
                String(
                    Files.readAllBytes(part.resolveSibling("${part.fileName}.meta")),
                    StandardCharsets.UTF_8,
                ).trim(),
            )
        }.getOrNull()
        return FollowProgress(bytes = bytes, expectedBytes = expectedBytes)
    }

    /** `<sha1>-<bytes>` → bytes; anything else (no separator, or a bare id) → null. */
    private fun parseProvenanceExpectedBytes(provenance: String): Long? {
        val separator = provenance.lastIndexOf('-')
        if (separator < 0) return null
        return provenance.substring(separator + 1).toLongOrNull()
    }

    /**
     * Publish bytes as READY: write `.part`, close, validate, atomic rename.
     * File existence alone does not mark READY — only a successful publish does.
     */
    fun publish(
        identity: GalleryIdentityV1,
        revision: Int,
        ordinal: Int,
        bytes: ByteArray,
        validate: (ByteArray) -> Boolean = { it.isNotEmpty() },
    ): Path {
        require(validate(bytes)) { "page bytes failed validation before write" }

        val part = partPath(identity, revision, ordinal)
        val ready = readyPath(identity, revision, ordinal)
        val key = flightKey(identity, revision, ordinal)
        // Keep any already-verified READY if a re-publish attempt fails (§4.7).
        val alreadyReady = key in publishedKeys
        Files.createDirectories(part.parent)

        try {
            Files.write(part, bytes)
            // Validate the same bytes that were already validated before the
            // write. Re-reading a multi-megabyte page here only creates a
            // second page-sized heap allocation and adds no information: the
            // file was just written from this exact array.
            require(validate(bytes)) { "page bytes failed validation after close" }
            atomicPublish(part, ready)
        } catch (t: Throwable) {
            runCatching { Files.deleteIfExists(part) }
            if (!alreadyReady) {
                runCatching { Files.deleteIfExists(ready) }
                publishedKeys.remove(key)
            }
            throw t
        }

        publishedKeys.add(flightKey(identity, revision, ordinal))
        return ready
    }

    /**
     * Returns the published READY path for [currentRevision], or null.
     * Never opens `.part`. Superseded revisions are not READY.
     */
    fun resolveReady(
        identity: GalleryIdentityV1,
        currentRevision: Int,
        ordinal: Int,
    ): Path? {
        committedPages[revisionKeyPrefix(identity, currentRevision)]?.getOrNull(ordinal)?.let { path ->
            if (Files.isRegularFile(path)) return path
        }
        val key = flightKey(identity, currentRevision, ordinal)
        if (key !in publishedKeys) {
            return null
        }
        val path = readyPath(identity, currentRevision, ordinal)
        if (!Files.isRegularFile(path)) {
            publishedKeys.remove(key)
            return null
        }
        if (path.fileName.toString().endsWith(".part")) {
            return null
        }
        return path
    }

    /**
     * Resolve ordinal N per §4.5 precedence. Upstream fetch is single-flighted
     * per [flightKey]; successful fetch publishes as READY.
     */
    fun resolve(
        identity: GalleryIdentityV1,
        currentRevision: Int,
        ordinal: Int,
        fetchUpstream: () -> ByteArray,
        validate: (ByteArray) -> Boolean = { it.isNotEmpty() },
    ): ResolvedPage {
        resolveReady(identity, currentRevision, ordinal)?.let {
            return ResolvedPage(path = it, source = PageResolveSource.READY)
        }

        val key = flightKey(identity, currentRevision, ordinal)
        var leader = false
        val future = inFlight.computeIfAbsent(key) {
            leader = true
            CompletableFuture()
        }

        if (leader) {
            try {
                val bytes = fetchUpstream()
                val path = publish(
                    identity = identity,
                    revision = currentRevision,
                    ordinal = ordinal,
                    bytes = bytes,
                    validate = validate,
                )
                future.complete(path)
                return ResolvedPage(path = path, source = PageResolveSource.UPSTREAM)
            } catch (t: Throwable) {
                future.completeExceptionally(t)
                throw t
            } finally {
                inFlight.remove(key, future)
            }
        }

        val path = awaitInFlightFollower(identity, currentRevision, ordinal, future)
        return ResolvedPage(path = path, source = PageResolveSource.IN_FLIGHT)
    }

    /**
     * Follower wait shared by [resolve] and [resolveTransfer] (#E15 Task 3b).
     * Each window expiry checks the leader's `.part` file exactly once (no
     * polling inside a window): if its size changed since the previous window
     * the wait renews — a 20 MiB animated page may stream for many minutes,
     * and a leader that truncates and restarts is still moving. No `.part`
     * file (a bytes-only adapter) keeps a single window, and a part that
     * stopped changing fails the wait with the same exception the fixed window
     * produced ([TimeoutException] here, the leader's own failure otherwise).
     * [InterruptedException] is not caught: a cancelled reader must leave the
     * blocked `Future.get` instead of taking another window.
     */
    private fun awaitInFlightFollower(
        identity: GalleryIdentityV1,
        revision: Int,
        ordinal: Int,
        future: CompletableFuture<Path>,
    ): Path {
        val part = partPath(identity, revision, ordinal)
        var previousSize = partSizeOrNull(part)
        while (true) {
            try {
                return future.get(followerWindowMs, TimeUnit.MILLISECONDS)
            } catch (timeout: TimeoutException) {
                val currentSize = partSizeOrNull(part)
                if (leaderPartGrew(previousSize, currentSize)) {
                    previousSize = currentSize
                    continue
                }
                throw timeout
            }
        }
    }

    private suspend fun awaitInFlightTransferFollower(
        identity: GalleryIdentityV1,
        revision: Int,
        ordinal: Int,
        future: CompletableFuture<Path>,
    ): Path {
        val part = partPath(identity, revision, ordinal)
        var previousSize = partSizeOrNull(part)
        while (true) {
            try {
                return withTimeout(followerWindowMs) { future.asDeferred().await() }
            } catch (timeout: TimeoutCancellationException) {
                val currentSize = partSizeOrNull(part)
                if (leaderPartGrew(previousSize, currentSize)) {
                    previousSize = currentSize
                    continue
                }
                throw timeout
            }
        }
    }

    /**
     * Renew when the part's size changed, including a shrink. A leader that
     * truncates and restarts (a 200 over a resume, a provenance mismatch) is
     * still healthy. No part file (`currentSize == null`) does not renew.
     */
    private fun leaderPartGrew(previousSize: Long?, currentSize: Long?): Boolean =
        currentSize != null && currentSize != previousSize

    private fun partSizeOrNull(part: Path): Long? =
        if (Files.isRegularFile(part)) runCatching { Files.size(part) }.getOrNull() else null

    /**
     * Disk-backed counterpart to [resolve]. The producer owns the response
     * stream and appends to the unpublished `.part` file. Only protocol,
     * size, digest, or provenance failures discard the prefix. Cancellation,
     * HTTP and IO failures retain it; the explicit user-cancel path removes
     * it via [cleanPartTemps].
     */
    suspend fun resolveTransfer(
        identity: GalleryIdentityV1,
        currentRevision: Int,
        ordinal: Int,
        transferUpstream: suspend (destination: Path, offset: Long) -> PageTransferResult,
        validate: (Path, PageTransferResult) -> Boolean = { path, result ->
            val size = Files.size(path)
            val sizeMatches = size > 0L && size == result.bytesWritten &&
                (result.expectedBytes == null || size == result.expectedBytes)
            if (!sizeMatches) {
                false
            } else {
                result.expectedSha1?.let { expected ->
                    val digest = MessageDigest.getInstance("SHA-1")
                    Files.newInputStream(path).use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count > 0) digest.update(buffer, 0, count)
                        }
                    }
                    digest.digest().joinToString("") {
                        (it.toInt() and 0xff).toString(16).padStart(2, '0')
                    }.equals(expected, true)
                } ?: true
            }
        },
    ): ResolvedPage {
        resolveReady(identity, currentRevision, ordinal)?.let {
            return ResolvedPage(it, PageResolveSource.READY)
        }
        val key = flightKey(identity, currentRevision, ordinal)
        var leader = false
        val future = inFlight.computeIfAbsent(key) {
            leader = true
            CompletableFuture()
        }
        if (!leader) {
            return ResolvedPage(
                awaitInFlightTransferFollower(identity, currentRevision, ordinal, future),
                PageResolveSource.IN_FLIGHT,
            )
        }
        val part = partPath(identity, currentRevision, ordinal)
        val ready = readyPath(identity, currentRevision, ordinal)
        Files.createDirectories(part.parent)
        try {
            val offset = if (Files.isRegularFile(part)) Files.size(part) else 0L
            val result = transferUpstream(part, offset)
            if (!validate(part, result)) {
                throw InvalidPageTransferException("page transfer failed size/hash validation")
            }
            atomicPublish(part, ready)
            runCatching { Files.deleteIfExists(part.resolveSibling("${part.fileName}.meta")) }
            publishedKeys.add(key)
            future.complete(ready)
            return ResolvedPage(ready, PageResolveSource.UPSTREAM)
        } catch (t: Throwable) {
            // Only bytes proven bad are discarded. Cancellation, HTTP errors,
            // timeouts, watchdog cuts and other IO failures retain the prefix.
            if (t is InvalidPageTransferException) {
                runCatching { Files.deleteIfExists(part) }
                runCatching { Files.deleteIfExists(part.resolveSibling("${part.fileName}.meta")) }
            }
            future.completeExceptionally(t)
            throw t
        } finally {
            inFlight.remove(key, future)
        }
    }

    /**
     * Re-admit READY pages after process restart when Room (or an equivalent
     * durable marker) lists [ordinals] and the published file still exists and
     * validates. File existence alone is never enough — callers must only pass
     * ordinals that were marked READY after a successful [publish].
     *
     * @return count of ordinals admitted into the in-memory READY set
     */
    fun hydrateTrusted(
        identity: GalleryIdentityV1,
        revision: Int,
        ordinals: Iterable<Int>,
    ): Int = hydrateTrustedOrdinals(identity, revision, ordinals).size

    /**
     * A36 review O3: [hydrateTrusted]'s answer as the ordinals it actually
     * admitted, not just how many.
     *
     * A durable READY marker can outlive its file — a process death between
     * `ReadyPageStore.deletePublishedRevision` and
     * `DownloadDao.deleteReadyPagesForJob` leaves rows pointing at nothing.
     * Callers that treat every Room READY ordinal as present then skip
     * fetching a page they do not have, and every later read of it fails.
     * The set says which markers are backed by a real file, so the rest can
     * be re-fetched instead of trusted.
     */
    fun hydrateTrustedOrdinals(
        identity: GalleryIdentityV1,
        revision: Int,
        ordinals: Iterable<Int>,
    ): Set<Int> {
        val admitted = sortedSetOf<Int>()
        for (ordinal in ordinals) {
            if (admitTrusted(identity, revision, ordinal)) {
                admitted += ordinal
            }
        }
        return admitted
    }

    /**
     * A18 (owner bug backlog): admit every page ALREADY PUBLISHED on disk for
     * [identity] + [revision] and return their ordinals (ascending). Unlike
     * [hydrateTrusted] this does not need a Room list: the reader
     * ([DownloadPageResolver]) publishes through this same store without
     * writing a READY row, so a FAILED job can hold many verified files and
     * zero rows (Kyouei 2308928: 11 files, 0 rows). Trust comes from the
     * publish protocol, not from mere existence — a canonically-named
     * (see [canonicalOrdinalOrNull]) regular file in the revision dir can
     * only be produced by [atomicPublish] after validation; `.part` temps,
     * empty files and every non-canonical name are skipped. [limitExclusive]
     * (the pinned page count)
     * keeps a stale out-of-range page (e.g. ordinal 42 of a 35-page pin) on
     * disk but un-admitted so progress never reads "36 of 35". Ordinals in
     * [skip] (the caller's Room READY set, already handled by
     * [hydrateTrusted]) are neither re-read nor reported, so a pump pass only
     * ever re-validates orphaned files.
     *
     * Idempotent: an ordinal already in the READY set (and not in [skip]) is
     * re-verified and reported again so callers can upsert rows with
     * INSERT-OR-IGNORE.
     */
    fun reconcilePublished(
        identity: GalleryIdentityV1,
        revision: Int,
        limitExclusive: Int?,
        skip: Set<Int> = emptySet(),
    ): List<Int> {
        val dir = pageDir(identity, revision)
        if (!Files.isDirectory(dir)) {
            return emptyList()
        }
        // DirectoryStream + Kotlin collections on purpose: a java.util.stream
        // pipeline ending in `Stream.toList()` is JDK 16 / Android API 34
        // (minSdk 29) and trips lint NewApi while passing JVM tests.
        val candidates = Files.newDirectoryStream(dir).use { entries ->
            entries
                .filter { Files.isRegularFile(it) }
                .mapNotNull { path -> canonicalOrdinalOrNull(path.fileName.toString()) }
                .filter { ordinal ->
                    ordinal !in skip &&
                        (limitExclusive == null || ordinal < limitExclusive)
                }
                .sorted()
        }
        return candidates.filter { ordinal -> admitTrusted(identity, revision, ordinal) }
    }

    /**
     * P3 (A18 follow-up, polish backlog): a name is an ordinal only when it
     * is EXACTLY what [readyPath] would have written — a canonical
     * non-negative decimal. `String.toIntOrNull` is too permissive here: it
     * accepts a leading `+` and leading zeros, so `"03"` and `"+3"` both
     * parsed as 3 and their bytes were credited to ordinal 3's real (or
     * entirely absent) file. `.part` temps, `"-1"`, ` "3"`, `"3.0"` and any
     * other name are left on disk untouched and unreported.
     */
    private fun canonicalOrdinalOrNull(name: String): Int? {
        if (name.isEmpty() || name.length > 10) return null
        if (name.any { it !in '0'..'9' }) return null
        if (name.length > 1 && name[0] == '0') return null
        return name.toIntOrNull()
    }

    /**
     * Delete download-owned READY files (and leftover `.part` temps) for
     * [identity] + [revision]. Clears in-memory publication for those pages.
     * Used by FINISH Detail delete (spec §4.2).
     *
     * P2 (polish backlog): the now-empty `<ns>/<id>/` skeleton above the
     * deleted revision dir is pruned too — otherwise `download-ready-pages/`
     * accumulated one empty two-level directory pair per gallery ever
     * downloaded and then deleted/released. Pruning walks UP and stops at the
     * first non-empty directory, and never removes [root] itself.
     */
    /**
     * Drops ONE ordinal's READY file (and any `.part`) and forgets it. Used by
     * the parallel page window: pages fetched in the same window AFTER the
     * first failing ordinal are un-published so the on-disk state equals what
     * the serial loop would have produced — the 404→GONE / short→SEALED
     * verdicts keep reasoning about a contiguous READY prefix.
     */
    fun discardPublished(identity: GalleryIdentityV1, revision: Int, ordinal: Int) {
        runCatching { Files.deleteIfExists(readyPath(identity, revision, ordinal)) }
        runCatching { Files.deleteIfExists(partPath(identity, revision, ordinal)) }
        runCatching { Files.deleteIfExists(partPath(identity, revision, ordinal).resolveSibling("${ordinal}.part.meta")) }
        publishedKeys.remove(flightKey(identity, revision, ordinal))
    }

    @Synchronized
    fun deletePublishedRevision(identity: GalleryIdentityV1, revision: Int) {
        val key = revisionKeyPrefix(identity, revision)
        committedPages.remove(key)
        pendingRelease.remove(key)
        deleteStagingRevision(identity, revision)
    }

    private fun deleteStagingRevision(identity: GalleryIdentityV1, revision: Int): Boolean {
        val dir = pageDir(identity, revision)
        val existed = Files.isDirectory(dir)
        if (existed) {
            runCatching {
                Files.newDirectoryStream(dir).use { entries ->
                    entries.forEach { path -> runCatching { Files.deleteIfExists(path) } }
                }
                Files.deleteIfExists(dir)
                pruneEmptyParents(dir.parent)
            }
        }
        publishedKeys.removeIf { key -> key.startsWith(revisionKeyPrefix(identity, revision)) }
        return existed && !Files.exists(dir)
    }


    /**
     * Removes [start] and each of its parents while they are empty, stopping
     * below [root] (never at or above it) and at the first directory that
     * still holds anything — a sibling revision of the same gallery, or a
     * sibling gallery under the same namespace. `Files.delete` on a
     * non-empty directory fails, so the emptiness check and the delete are
     * the same operation; a failure simply ends the walk.
     */
    private fun pruneEmptyParents(start: Path?) {
        var current = start
        while (current != null && current != root && current.startsWith(root)) {
            val removed = runCatching { Files.deleteIfExists(current) }.getOrDefault(false)
            if (!removed) return
            current = current.parent
        }
    }

    /**
     * Remove orphan `.part` temps for [identity] + [revision]. Verified READY
     * pages are left untouched (cancel / stop path, spec §4.7).
     */
    fun cleanPartTemps(identity: GalleryIdentityV1, revision: Int) {
        val dir = pageDir(identity, revision)
        if (!Files.isDirectory(dir)) {
            return
        }
        Files.list(dir).use { stream ->
            stream
                .filter {
                    Files.isRegularFile(it) &&
                    (it.fileName.toString().endsWith(".part") || it.fileName.toString().endsWith(".part.meta"))
                }
                .forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    private fun admitTrusted(
        identity: GalleryIdentityV1,
        revision: Int,
        ordinal: Int,
    ): Boolean {
        val path = readyPath(identity, revision, ordinal)
        if (!Files.isRegularFile(path)) {
            return false
        }
        if (path.fileName.toString().endsWith(".part")) {
            return false
        }
        // Legacy rule: readAllBytes succeeds and the body is non-empty.
        // isReadable is the success half; a positive size is the non-empty
        // half. Neither one reads the body.
        if (!Files.isReadable(path)) {
            return false
        }
        if (!runCatching { Files.size(path) > 0L }.getOrDefault(false)) {
            return false
        }
        publishedKeys.add(flightKey(identity, revision, ordinal))
        return true
    }

    private fun revisionKeyPrefix(identity: GalleryIdentityV1, revision: Int): String =
        listOf(
            GalleryIdentityV1.IDENTITY_VERSION.toString(),
            identity.providerNamespace,
            identity.canonicalItemId,
            revision.toString(),
            "",
        ).joinToString("\u0000")

    private fun pageDir(identity: GalleryIdentityV1, revision: Int): Path =
        root
            .resolve(sanitize(identity.providerNamespace))
            .resolve(sanitize(identity.canonicalItemId))
            .resolve("r$revision")

    /**
     * #C18: an OPDS id is a whole acquisition URL, so percent-encoded CJK blows
     * the 255-BYTE name limit and the page write fails with `ENAMETOOLONG`.
     * Unlike the locator store that crashed outright, the failure here was
     * swallowed into the retry ladder and surfaced as `JITTER_EXHAUSTED`.
     * [BoundedPathSegment.of] leaves short segments verbatim, so nothing that
     * an earlier build wrote to disk moves.
     */
    private fun sanitize(segment: String): String =
        BoundedPathSegment.of(segment.replace(Regex("[\\\\/\\u0000]"), "_"))

    private fun atomicPublish(part: Path, ready: Path) {
        try {
            Files.move(
                part,
                ready,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(part, ready, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

enum class PageResolveSource {
    READY,
    IN_FLIGHT,
    UPSTREAM,
}

/**
 * A follower's snapshot of one in-flight transfer (#E15 Task 3b):
 * [bytes] on disk so far and, when the leader knows it, the
 * [expectedBytes] total.
 */
data class FollowProgress(
    val bytes: Long,
    val expectedBytes: Long?,
)

data class ResolvedPage(
    val path: Path,
    val source: PageResolveSource,
)
