package com.easemic.app.storage

import androidx.annotation.VisibleForTesting
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * Shared byte budget for rebuildable on-device cache roots.
 *
 * Roots are deliberately registered explicitly. This prevents a broad
 * "delete app cache" action from touching formal downloads, databases, or
 * credentials, and makes the deletion boundary reviewable at each call site.
 *
 * ## Accounting model (C34-a)
 *
 * The budget keeps a running `trackedBytes` total instead of re-walking the
 * cache tree to answer "am I over budget?":
 * - [registerRoot] walks **only the root being added** (one walk per root, so
 *   registering four roots is four walks, not ten) and adds its bytes.
 * - [recordWrite] adds the bytes the writer just published. No walk.
 * - A full sweep of every root runs **only when the running total crosses
 *   [maxBytes]** — the bound is still enforced at registration and on every
 *   write, just without a walk per write.
 * - A sweep evicts down to a **low-water mark** of
 *   `maxBytes - maxBytes / [EVICTION_HEADROOM_DIVISOR]`, not merely back to
 *   the cap. One sweep therefore buys `maxBytes / 10` of headroom, so a
 *   long-lived install whose cache sits at the cap sweeps once per `cap/10`
 *   bytes written — not once per write, which is what evicting exactly the
 *   overflow produced (each sweep left ~half a file of room, and the next
 *   write crossed again).
 * - Every measurement resyncs the total: a sweep, [usageBytes], [clearAll],
 *   [evictNow] and the [maxBytes] setter all set `trackedBytes` from what they
 *   actually measured, so drift cannot accumulate.
 *
 * Drift is bounded in both directions, and every measurement corrects it.
 * Files deleted behind the budget's back (e.g. `CloudPageDiskCache`'s own
 * page-count eviction, or the OS reclaiming the cache dir) make the total an
 * **over**-estimate; it costs one early sweep, which re-measures and corrects
 * it. Under-estimates are rarer but real: writers report the delta they
 * published, and a root whose walk fails (an IO error inside
 * `walkRegularFiles`, which returns empty rather than throwing) measures 0
 * until a later walk succeeds. Both directions self-correct at the next
 * successful measurement; neither can accumulate silently.
 */
class CacheBudget(
    maxBytes: Long = DEFAULT_BYTES,
    /**
     * Trusted application-data boundary. Android may expose the system-managed
     * ancestors above this directory through symlinked mount paths; those
     * ancestors are outside the cache writer's control and do not need to be
     * rejected. Symlinks at or below this boundary remain forbidden.
     */
    private val trustedBase: File? = null,
) {
    private val lock = Any()
    private val roots = linkedMapOf<String, File>()
    private val retainedFiles = mutableMapOf<String, Int>()
    private var configuredMaxBytes = maxBytes.coerceAtLeast(1L)

    /**
     * Running total of cached bytes: measured once per root at registration,
     * then maintained by [recordWrite] and resynced by every measurement.
     */
    private var trackedBytes: Long = 0

    /**
     * Test seam: the running total as it stands, WITHOUT measuring anything.
     * [usageBytes] cannot answer this question — it re-walks and resyncs, so
     * it hides exactly the accounting drift a test wants to pin.
     */
    @VisibleForTesting
    fun trackedBytesForTest(): Long = synchronized(lock) { trackedBytes }

    /**
     * File count per registered root, keyed by the root directory's name, **as
     * counted by its one registration walk** (C34-d). Read by
     * [registeredFileCountsForTrace] so the `budget-init` phase line can say
     * what the init actually cost. Diagnostic only: never re-walked, and
     * deliberately not updated by later writes or evictions.
     */
    private val registeredFileCounts = linkedMapOf<String, Int>()

    /**
     * Test seam: how many times every registered root has been walked, i.e.
     * how many [cacheFilesLocked] calls have run. That is eviction sweeps
     * *and* measurements — [usageBytes], [evictNow] and the [maxBytes] setter
     * all go through it. A root's own registration measurement
     * ([measureRootLocked]) walks one root and does not count; [clearAll]
     * deletes without going through this seam and does not count either.
     * Read it **before** calling [usageBytes] when asserting on sweeps.
     */
    @Volatile
    var fullWalkCount: Int = 0
        private set

    /**
     * How many files a stale-`.part` sweep or [clearAll] asked the filesystem
     * to delete and could not (#C28 audit HIGH-2). A cache file the OS refuses
     * to remove is a *skipped* file, never a fatal error: the More tab reads
     * [usageBytes] from a `LaunchedEffect`, where a thrown exception takes the
     * process down. Diagnostic and test seam only — it is a running total for
     * the life of the budget, never reset.
     */
    @Volatile
    var skippedDeletionCount: Int = 0
        private set

    var maxBytes: Long
        get() = synchronized(lock) { configuredMaxBytes }
        set(value) {
            synchronized(lock) {
                configuredMaxBytes = value.coerceAtLeast(1L)
                evictLocked()
            }
        }

    fun registerRoot(root: File) {
        synchronized(lock) {
            val rootPath = root.toPath().toAbsolutePath().normalize()
            rejectSymlinkPath(rootPath, trustedBase?.toPath()?.toAbsolutePath()?.normalize())
            val absolute = rootPath.toFile()
            absolute.mkdirs()
            // Avoid double-counting when a test or a future call site
            // accidentally registers a nested root. The broader root owns
            // the subtree and remains the single eviction boundary.
            if (roots.keys.any { existing -> isWithin(absolute.path, existing) }) {
                return
            }
            roots.keys
                .filter { existing -> isWithin(existing, absolute.path) }
                .forEach { existing ->
                    // The superseded root's bytes are about to be counted
                    // again by the ancestor's walk below, so give them back
                    // first — leaving them in double-counted the whole
                    // subtree and bought a sweep that had nothing to evict
                    // (C34 audit S1).
                    trackedBytes -= measureRootLocked(File(existing))
                    // Drop the superseded root's trace count with it, so
                    // `budget-init` never names a root that is gone (C34-d).
                    roots.remove(existing)?.let { registeredFileCounts.remove(it.name) }
                }
            roots[absolute.path] = absolute
            // One walk of the newly registered root only. Walking every
            // already-registered root here made registration quadratic:
            // registering four roots walked root 1 four times (C34-a).
            trackedBytes += measureRootLocked(absolute)
            if (trackedBytes > configuredMaxBytes) evictLocked()
        }
    }

    /**
     * Serializes a cache operation with [clearAll] and budget eviction for a
     * registered root. Writers hold this from temp-file creation through
     * publication so a clear cannot race a late rename.
     */
    fun <T> withRootLock(root: File, block: () -> T): T =
        synchronized(lock) {
            val absolute = root.toPath().toAbsolutePath().normalize().toFile()
            if (roots.keys.none { existing -> isWithin(absolute.path, existing) }) {
                return@synchronized block()
            }
            block()
        }

    /**
     * Records a successful write of [bytes] **net new** bytes under [root].
     * The writer is responsible for completing its atomic rename before
     * calling this method, and for reporting the *delta* against whatever it
     * overwrote — `0` when the write published no new bytes (an already
     * present file whose mtime was merely touched, or a re-publish of the
     * same size). A shrinking overwrite may report a negative delta; it is
     * clamped to `0` here, leaving a bounded over-estimate that the next
     * measurement corrects. Reporting the gross size on an overwrite
     * double-counts bytes already on disk and buys needless sweeps.
     *
     * Accounting only: a sweep runs when the running total crosses the
     * budget, never on a fixed write interval.
     */
    fun recordWrite(root: File, bytes: Long) {
        synchronized(lock) {
            val absolute = root.toPath().toAbsolutePath().normalize().toFile()
            if (roots.keys.none { existing -> isWithin(absolute.path, existing) }) return
            trackedBytes += bytes.coerceAtLeast(0L)
            if (trackedBytes > configuredMaxBytes) evictLocked()
        }
    }

    /** Call under [withRootLock] before handing a completed file to an active reader. */
    fun retainFile(file: File): AutoCloseable = synchronized(lock) {
        val path = file.toPath().toAbsolutePath().normalize().toString()
        require(roots.keys.any { isWithin(path, it) }) { "reader file must belong to a registered cache root" }
        require(file.isFile) { "reader file must be published before it is retained" }
        retainedFiles[path] = (retainedFiles[path] ?: 0) + 1
        var released = false
        AutoCloseable {
            synchronized(lock) {
                if (!released) {
                    released = true
                    val count = checkNotNull(retainedFiles[path]) - 1
                    if (count == 0) retainedFiles.remove(path) else retainedFiles[path] = count
                }
            }
        }
    }

    private fun isRetained(file: File): Boolean =
        retainedFiles.containsKey(file.toPath().toAbsolutePath().normalize().toString())

    /** Restore the bound after a reader releases files; avoid a walk when already within budget. */
    fun evictIfOverBudget() {
        synchronized(lock) {
            if (trackedBytes > configuredMaxBytes) evictLocked()
        }
    }

    fun evictNow() {
        synchronized(lock) {
            evictLocked()
        }
    }

    fun usageBytes(): Long =
        synchronized(lock) {
            cleanupStaleProgressFilesLocked()
            val measured = cacheFilesLocked().sumOf(File::length)
            trackedBytes = measured
            measured
        }

    /**
     * Deletes only contents under explicitly registered roots, retaining the
     * roots themselves for future cache writes.
     */
    fun clearAll() {
        synchronized(lock) {
            roots.values.forEach { root ->
                if (!root.exists()) {
                    root.mkdirs()
                    return@forEach
                }
                cleanupStaleProgressFilesLocked(root)
                walkRegularFiles(root.toPath()).forEach { path ->
                    if (!isInProgressFile(path.toFile()) && !isRetained(path.toFile())) {
                        deleteQuietlyLocked(path)
                    }
                }
                walkDirectoriesBottomUp(root.toPath()).forEach { path ->
                    // The emptiness check is inside the guard too: listing a
                    // directory the OS will not open threw straight out of the
                    // Clear-cache button (#C28 audit HIGH-2).
                    runCatching {
                        val isEmptyDirectory = path != root.toPath() &&
                            Files.list(path).use { children -> !children.findAny().isPresent }
                        if (isEmptyDirectory) Files.deleteIfExists(path)
                    }.onFailure { skippedDeletionCount++ }
                }
                root.mkdirs()
            }
            trackedBytes = retainedFiles.keys.sumOf { File(it).length() }
        }
    }

    /**
     * Sweeps every registered root oldest-first down to the low-water mark
     * (`maxBytes` minus [EVICTION_HEADROOM_DIVISOR]'s share), and resyncs
     * [trackedBytes] to the post-eviction total it just measured. The
     * post-eviction total is derived from the measurement minus the bytes
     * actually deleted, so a sweep costs exactly one walk.
     *
     * Evicting only the overflow left ~half a file of headroom, so the very
     * next write at the cap crossed again and walked every root — one walk
     * per write. The low-water target keeps the same bound (post-sweep total
     * <= target <= `maxBytes`) while buying `maxBytes / 10` of room per walk.
     */
    private fun evictLocked() {
        val files = cacheFilesLocked()
        var total = files.sumOf(File::length)
        if (total <= configuredMaxBytes) {
            trackedBytes = total
            return
        }
        val target = configuredMaxBytes - configuredMaxBytes / EVICTION_HEADROOM_DIVISOR
        var remaining = total - target
        files.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.path })
            .forEach { file ->
                val length = file.length()
                if (remaining > 0L && !isRetained(file) && file.delete()) {
                    remaining -= length
                    total -= length
                }
            }
        trackedBytes = total
    }

    /**
     * Registration-walk file counts, `name:count` per root in registration
     * order — what the C34-d `budget-init` line reports. Counts come from the
     * single walk each root already paid for; this never walks anything.
     */
    fun registeredFileCountsForTrace(): String =
        synchronized(lock) {
            registeredFileCounts.entries.joinToString(",") { "${it.key}:${it.value}" }
        }

    /** One walk of a single root; completed files only. */
    private fun measureRootLocked(root: File): Long {
        val files = walkRegularFiles(root.toPath())
            .map(Path::toFile)
            .filterNot(::isInProgressFile)
        registeredFileCounts[root.name] = files.size
        return files.sumOf(File::length)
    }

    private fun cacheFilesLocked(): List<File> {
        fullWalkCount++
        return roots.values
            .flatMap { root ->
                walkRegularFiles(root.toPath()).map(Path::toFile)
            }
            .filterNot(::isInProgressFile)
    }

    private fun cleanupStaleProgressFilesLocked(
        onlyRoot: File? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val rootsToClean = onlyRoot?.let(::listOf) ?: roots.values.toList()
        rootsToClean.forEach { root ->
            walkRegularFiles(root.toPath()).forEach { path ->
                val file = path.toFile()
                if (isInProgressFile(file) &&
                    nowMs - file.lastModified() >= STALE_PROGRESS_AGE_MS
                ) {
                    deleteQuietlyLocked(path)
                }
            }
        }
    }

    private fun walkRegularFiles(root: Path): List<Path> {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) return emptyList()
        return runCatching {
            Files.walk(root).use { stream ->
                stream
                    .filter { path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) }
                    .collect(Collectors.toList())
            }
        }.getOrDefault(emptyList())
    }

    private fun walkDirectoriesBottomUp(root: Path): List<Path> {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) return emptyList()
        return runCatching {
            Files.walk(root).use { stream ->
                stream
                    .sorted(Comparator.reverseOrder())
                    .filter { path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) }
                    .collect(Collectors.toList())
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Deletes a cache path, **counting** rather than rethrowing a refusal
     * (#C28 audit HIGH-2). A cache file the filesystem will not let go of is a
     * skipped file, not a fatal error: the More tab reads [usageBytes] from a
     * `LaunchedEffect`, where a thrown exception takes the process down, and
     * `cacheBudget` is a `by lazy` whose failed initializer is not memoised —
     * so every later open re-threw.
     */
    private fun deleteQuietlyLocked(path: Path) {
        runCatching { Files.deleteIfExists(path) }
            .onFailure { skippedDeletionCount++ }
    }

    private fun isInProgressFile(file: File): Boolean =
        file.name.endsWith(".part", ignoreCase = true) ||
            file.name.endsWith(".tmp", ignoreCase = true)

    private fun rejectSymlinkPath(
        path: java.nio.file.Path,
        trustedBase: java.nio.file.Path?,
    ) {
        if (trustedBase != null) {
            require(
                path == trustedBase || path.startsWith("$trustedBase${File.separator}"),
            ) {
                "cache root must be inside trusted application data: $path"
            }
        }
        var current: java.nio.file.Path? = path
        while (current != null) {
            // Stop BEFORE testing the boundary itself (#C28 audit HIGH-1):
            // /data/user/0/<pkg> is created by installd and an OEM may
            // virtualise it, so the trusted base is as much out of the app's
            // control as the ancestors above it. Testing it first threw on
            // exactly those builds and crashed the More tab. Symlinks strictly
            // BELOW the boundary are still walked and still rejected.
            if (trustedBase != null && current == trustedBase) break
            require(!Files.isSymbolicLink(current)) {
                "cache root must not contain a symbolic-link ancestor: $path"
            }
            current = current.parent
        }
    }

    private fun isWithin(path: String, ancestor: String): Boolean =
        path == ancestor || path.startsWith("$ancestor${File.separator}")

    companion object {
        const val BYTES_PER_MB = 1024L * 1024L
        const val DEFAULT_MB = 512
        const val DEFAULT_BYTES = DEFAULT_MB * BYTES_PER_MB
        const val COIL_PARTITION_BYTES = 64L * BYTES_PER_MB
        const val STALE_PROGRESS_AGE_MS = 60L * 60L * 1000L

        /**
         * A sweep evicts down to `maxBytes - maxBytes / this`, i.e. it leaves
         * 10 % of the budget free. Steady-state writes at the cap then sweep
         * once per `maxBytes / 10` bytes instead of once per write.
         */
        const val EVICTION_HEADROOM_DIVISOR = 10L
        val OPTIONS_MB = listOf(256L, 512L, 1024L, 2048L)

        fun genericBudgetBytes(selectedTotalBytes: Long): Long =
            (selectedTotalBytes - COIL_PARTITION_BYTES).coerceAtLeast(BYTES_PER_MB)
    }
}
