package com.easemic.app.feature.library

import com.easemic.app.sync.CloudCollectionCard

/**
 * C29 audit MEDIUM-1: what the Cloud shelf starts from on a warm restart.
 *
 * The shelf is seeded from
 * [com.easemic.app.feature.bridge.CloudProcessSession] so an
 * Activity recreation (rotation, locale, theme) keeps its tiles: Room's
 * `observeActiveCards` flow only emits a frame or two later, and the effect
 * that re-maps cards to shelf items runs BEFORE it — anything that treats "no
 * emission yet" as "no cards" blanks the shelf and refills it.
 *
 * 2026-09-21 review MEDIUM-1: that memo belongs to exactly ONE bucket. A direct
 * rebind A → B moves the fingerprint with no `null` in between, so the
 * "unbound" branch never fires and an unscoped seed would paint A's tiles on
 * B's shelf — and now persist them. The seed is therefore only valid while the
 * memo's fingerprint IS the bound one; otherwise the shelf starts empty and
 * fills from the mirror.
 */
fun seedCloudShelfCards(
    memoCards: List<CloudCollectionCard>,
    memoFingerprint: String?,
    currentFingerprint: String?,
): List<CloudCollectionCard> =
    if (currentFingerprint != null && memoFingerprint == currentFingerprint) memoCards else emptyList()
