package com.easemic.app.feature.collect

import com.easemic.app.sync.CloudCollectionStore
import com.easemic.app.sync.LibraryLockedException
import com.easemic.app.sync.NewerCloudFormatException
import com.easemic.app.sync.UnreadableCloudLibraryException
import kotlinx.coroutines.CancellationException

/** #C62: why the bucket side of an uncollect did not reach [UncollectCloudOutcome.Tombstoned]. */
enum class UncollectCloudFailureKind { LOCKED, NEWER, UNREADABLE, UNREACHABLE, SYNCING }

sealed class UncollectCloudOutcome {
    /** The card moved to trash at [tombstonedAtMs]. */
    data class Tombstoned(val cloudComicId: String, val tombstonedAtMs: Long) : UncollectCloudOutcome()

    /**
     * An id resolved (directly or via [fallback]) but the active index
     * already lacks it — the divergence
     * `UncollectFromCloudShelfIndexDivergenceTest` pins. Nothing moved in the
     * bucket, but the caller must still drop the mirror row exactly as it
     * would for [Tombstoned]: the old unconditional `setTrashed` call ran
     * regardless of whether the bucket-side match succeeded.
     */
    data class NotInIndex(val cloudComicId: String, val atMs: Long) : UncollectCloudOutcome()

    /** No id resolved at all — nothing to trash, nothing to un-mirror. */
    object NotFound : UncollectCloudOutcome()

    /** The bucket call failed; the comic is still collected. */
    data class Failed(val kind: UncollectCloudFailureKind) : UncollectCloudOutcome()
}

/**
 * #C62: the bucket side of `uncollectBookmark`, split out so it can never
 * throw into `rememberCoroutineScope`'s uncaught-exception path (that scope
 * has no handler, so an offline `tombstoneComic` used to kill the process —
 * see `docs/superpowers/specs/2026-09-25-c62-uncollect-offline-crash-design.md`).
 * [cloudComicId] is the id `resolveCloudComicId` already resolved for free;
 * [fallback] is the caller's `listAllIndexCards()` scan, invoked ONLY when
 * [cloudComicId] is null, matching `discardCloudCollection`'s cheaper
 * resolution order. [fallback] runs INSIDE the same guarded region as the
 * tombstone call itself (review fix, #C62): it is also a bucket read and can
 * throw offline exactly like `tombstoneComic` can.
 */
fun tombstoneForUncollect(
    store: CloudCollectionStore,
    cloudComicId: String?,
    fallback: () -> String?,
    nowMs: Long,
): UncollectCloudOutcome {
    return try {
        val id = cloudComicId ?: fallback() ?: return UncollectCloudOutcome.NotFound
        if (store.tombstoneComic(id, nowMs)) {
            UncollectCloudOutcome.Tombstoned(id, nowMs)
        } else {
            UncollectCloudOutcome.NotInIndex(id, nowMs)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: LibraryLockedException) {
        UncollectCloudOutcome.Failed(UncollectCloudFailureKind.LOCKED)
    } catch (error: NewerCloudFormatException) {
        UncollectCloudOutcome.Failed(UncollectCloudFailureKind.NEWER)
    } catch (error: UnreadableCloudLibraryException) {
        UncollectCloudOutcome.Failed(UncollectCloudFailureKind.UNREADABLE)
    } catch (error: Exception) {
        UncollectCloudOutcome.Failed(UncollectCloudFailureKind.UNREACHABLE)
    }
}
