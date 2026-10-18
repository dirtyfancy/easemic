package com.easemic.app.sync

import java.io.File
import kotlinx.coroutines.CancellationException

/** Deletes top-level cloud-ingest entries no live queue row maps to. Null from [liveIds] means "cannot tell": do nothing. */
class CloudIngestWorkSweep(
    private val workRoot: File,
    private val liveIds: suspend () -> Set<String>?,
) {
    /**
     * Number of top-level entries deleted. Fail closed throughout: a live set
     * that cannot be resolved, an unreadable root, or an entry that refuses to
     * delete all leave the sweep counting only what actually went away, and
     * nothing here ever propagates an I/O problem to the caller.
     */
    suspend fun run(): Int {
        val live = try {
            liveIds()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return 0
        val entries = try {
            workRoot.listFiles()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return 0
        var deleted = 0
        for (entry in entries) {
            if (entry.name in live) continue
            val gone = try {
                entry.deleteRecursively()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (gone) deleted++
        }
        return deleted
    }
}
