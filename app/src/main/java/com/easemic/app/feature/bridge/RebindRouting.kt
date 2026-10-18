package com.easemic.app.feature.bridge

import com.easemic.app.sync.CloudCollectionCard

/**
 * Outcome of authoritatively probing the (possibly newly-bound) bucket's
 * collection index — never off the still-hydrating Slice-B mirror (spec §3
 * "Implementation constraints", emptiness-probe note). Splits what a prior
 * revision collapsed into a single `nonEmptyOrUnreadable: Boolean`: an
 * actually-read non-empty bucket carries its cards forward (so a caller that
 * decides to reconcile against it doesn't have to re-fetch), while a throw
 * (locked/foreign/network) is kept distinct so it is never mistaken for a
 * legitimately-read non-empty library.
 */
sealed interface CloudBucketProbe {
    data object Empty : CloudBucketProbe
    data class NonEmpty(val cards: List<CloudCollectionCard>) : CloudBucketProbe
    data object Unreadable : CloudBucketProbe
}

/**
 * What the catch-up gate (PrivateComicLibraryApp) should do once its
 * authoritative probe of the bound bucket comes back. #C61: one rule for
 * every epoch and every bind — the device's collection is the truth (#C56),
 * so a confirmed read always runs the full reconcile.
 */
sealed interface FastPathCatchUpPlan {
    /** A confirmed read (empty or non-empty): safe to run the full reconcile
     *  (FAILED-row retry + absent-bookmark upload) against [cards] — empty
     *  for a genuinely empty bucket, populated for a non-empty one. */
    data class FullReconcile(val cards: List<CloudCollectionCard>) : FastPathCatchUpPlan

    /** Owner bug A3/C5 fix: the probe threw (locked/foreign/network/transient
     *  R2 hiccup right after app reopen) — this is NOT proof the bucket is
     *  empty. Collapsing it into [FullReconcile] with no cards would drive a
     *  mass false re-upload of every bookmark. Only the FAILED-row retry pass
     *  runs. */
    data object FailedRowsOnly : FastPathCatchUpPlan
}

/**
 * Pure decision for the catch-up gate. An [CloudBucketProbe.Unreadable]
 * probe must never be treated as equivalent to a confirmed
 * [CloudBucketProbe.Empty] — see [FastPathCatchUpPlan.FailedRowsOnly].
 */
fun catchUpPlanFor(probe: CloudBucketProbe): FastPathCatchUpPlan = when (probe) {
    CloudBucketProbe.Empty -> FastPathCatchUpPlan.FullReconcile(emptyList())
    is CloudBucketProbe.NonEmpty -> FastPathCatchUpPlan.FullReconcile(probe.cards)
    CloudBucketProbe.Unreadable -> FastPathCatchUpPlan.FailedRowsOnly
}
