package com.easemic.app.feature.collect

import com.easemic.app.sync.CloudIndexGenerations
import com.easemic.app.ui.PreferenceMap

/** A22 §3: minimum gap between two foreground-triggered manifest re-checks. */
const val HYDRATION_RECHECK_MIN_INTERVAL_MS: Long = 5 * 60_000L

/**
 * A22 §3 foreground throttle: kick the hydration pump on ON_START only when
 * the last manifest check ([lastCheckAtMs]; null = never) is at least
 * [minIntervalMs] old. A clock that moved backwards re-checks rather than
 * waiting out a bogus interval. Mirrors `shouldReKickOnAvailable`'s shape.
 */
fun shouldRecheckHydration(
    lastCheckAtMs: Long?,
    nowMs: Long,
    minIntervalMs: Long = HYDRATION_RECHECK_MIN_INTERVAL_MS,
): Boolean {
    if (lastCheckAtMs == null) return true
    if (nowMs < lastCheckAtMs) return true
    return nowMs - lastCheckAtMs >= minIntervalMs
}

/**
 * A22 §4 own-upload generation stamp: this device's upload advanced the cloud
 * manifest from [CloudIndexGenerations.previousGeneration] to
 * [CloudIndexGenerations.newGeneration]. The local mirror may adopt the new
 * generation without a re-walk only when it was FULLY hydrated at exactly the
 * previous generation. Anything else — never hydrated, mid-walk (a stamp
 * would be overwritten by the walk's own putState and the live card pruned
 * by its last chunk), another device changed the bucket in between, or a
 * first-ever manifest — leaves the state alone so the next check re-walks.
 */
fun ownUploadGenerationStampApplies(
    localState: CloudMirrorHydrationState?,
    generations: CloudIndexGenerations,
): Boolean {
    if (localState == null) return false
    if (!localState.hydrationComplete) return false
    val previous = generations.previousGeneration ?: return false
    if (localState.generation != previous) return false
    return generations.newGeneration > previous
}

/**
 * A22 §3: timestamp of the last manifest GET that actually read a manifest
 * (generation-equal included), written by [CloudHydrationEngine] through its
 * `onManifestChecked` hook and read by `AppGraph.onAppForeground`. Prefs
 * template: `feature/settings/BackgroundNotificationAskedStore`.
 */
class HydrationRecheckStore(private val prefs: PreferenceMap) {
    fun lastCheckAtMs(): Long? = prefs.getString(KEY, null)?.toLongOrNull()

    fun markChecked(nowMs: Long) {
        prefs.putString(KEY, nowMs.toString())
    }

    /** Disconnect hygiene: the next bind's first foreground must re-check. */
    fun clear() {
        prefs.putString(KEY, "")
    }

    companion object {
        const val KEY = "cloud.hydration_last_manifest_check_ms"
    }
}
