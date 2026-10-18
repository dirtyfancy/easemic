package com.easemic.app.work

import android.os.Build

/**
 * C43 (2026-09-15): what a pump should do when `setForeground` throws.
 *
 * On Android 12+ a pump (re)started while the app is backgrounded is DENIED
 * its foreground service (`ForegroundServiceStartNotAllowedException`, AMS
 * "Background started FGS: Disallowed"). Every self-restart the pumps perform
 * — exit-path `kickAfter`, the cancel re-kick, the connectivity re-kick, a
 * WorkManager reschedule after process death — happens in the background, so
 * this is the common case, not an edge. Running the pass anyway leaves the
 * worker in a cached process with no FGS and no JobScheduler job holding it
 * (WorkManager also keeps it in its foreground map and refuses to stop it),
 * where progress simply freezes — the owner's "switched apps, it stopped".
 * See docs/research/2026-09-15-c43-background-stop-root-cause.md.
 *
 * Any OTHER failure (a notification the OS declines to show, an odd OEM
 * SecurityException) keeps the pre-C43 behaviour: log and keep pumping — the
 * FGS itself was not refused, only its notification.
 */
enum class ForegroundFailureAction {
    /** The FGS start was refused: do not run unprotected; record, re-kick later, exit. */
    SKIP_PASS_AND_RETRY,

    /** Something else failed around the notification: keep pumping as before. */
    CONTINUE_UNPROTECTED,
}

private const val FGS_NOT_ALLOWED_CLASS = "android.app.ForegroundServiceStartNotAllowedException"

/** Pure: walks the cause chain so a wrapped platform exception is still recognised. */
fun foregroundFailureAction(error: Throwable, sdk: Int = Build.VERSION.SDK_INT): ForegroundFailureAction {
    if (sdk < Build.VERSION_CODES.S) return ForegroundFailureAction.CONTINUE_UNPROTECTED
    var cursor: Throwable? = error
    var hops = 0
    while (cursor != null && hops < 8) {
        if (cursor.javaClass.name == FGS_NOT_ALLOWED_CLASS) return ForegroundFailureAction.SKIP_PASS_AND_RETRY
        cursor = cursor.cause
        hops++
    }
    return ForegroundFailureAction.CONTINUE_UNPROTECTED
}

/**
 * #C72 (C43 audit H2): whether a pump whose `setForeground` returned normally is actually protected.
 * Once the Android 15+ `dataSync` budget (6 h / 24 h, reset by a foreground visit) is spent, the
 * in-service `startForeground` throws "Time limit already exhausted" and WorkManager 2.11.2's
 * `SystemForegroundService$Api31Impl` catches and only logs it — so a normal return proves nothing.
 * A visible app always may run (the visit resets the budget); below API 35 there is no budget.
 */
fun pumpIsProtected(sdk: Int, appVisible: Boolean, holdsForegroundService: Boolean): Boolean =
    sdk < 35 || appVisible || holdsForegroundService

/**
 * How long a pump waits before trying its foreground start again after a
 * denial. Flat and short on purpose: the retry is cheap (the pass is skipped,
 * not run) and is only a probe for a later temp-allowlist window (a
 * notification tap, an exact-alarm/boot window) — it cannot catch the ~10 s
 * foreground-to-background grace, which has lapsed by then. The real recovery
 * is the next foreground moment, which `AppGraph.onAppForeground` turns into
 * a user-initiated kick; that is why the probe is bounded
 * ([FGS_DENIED_MAX_SELF_RETRIES]).
 */
const val FGS_DENIED_RETRY_MS = 30_000L

/**
 * C43 audit B1: how many CONSECUTIVE denials may schedule that self-retry.
 * Unbounded, two transfer pumps would each cost a JobScheduler execution
 * every 30 s for as long as the app stays backgrounded — and, once the
 * process has been reclaimed, a full `Application.onCreate` per wake — with
 * nothing to show for it, because a backgrounded app cannot obtain the FGS
 * anyway. After the budget the pump goes quiet and waits for the kicks that
 * can actually succeed: foreground return, connectivity regain, cold start.
 * The streak resets on a granted start.
 */
const val FGS_DENIED_MAX_SELF_RETRIES = 3

/** Pure: may the [consecutiveDenials]-th denial in a row schedule a self-retry? */
fun deniedSelfRetryAllowed(consecutiveDenials: Int): Boolean =
    consecutiveDenials in 1..FGS_DENIED_MAX_SELF_RETRIES

/**
 * C43 audit H1: whether a pump's system-stop record is worth writing.
 *
 * - [PlatformStopKind.CANCELLED_BY_APP] is the app's own REPLACE /
 *   `cancelUniqueWork`, not a system stop: never recorded. Below API 31
 *   `getStopReason()` is unavailable and an app cancel maps to
 *   PLATFORM_STOPPED, so the REPLACE issuer also stamps the moment it
 *   cancelled the chain ([lastAppCancelMarkMs]): a pump constructed at or
 *   before that stamp is the one the app cancelled; the replacement is newer.
 * - The cancel of a REPLACE'd pump surfaces only when its detached upload
 *   returns — minutes later — after the replacement pump has already been
 *   granted its FGS and cleared the markers. A grant stamped NEWER than
 *   [ownMarkMs] (this worker's own start stamp) means a successor is alive
 *   and this stop is stale: writing it would show "Paused while in
 *   background · resuming" with no pump left to clear it. A grant stamped in
 *   the future (wall clock set back since) is not "newer": otherwise every
 *   genuine stop would be suppressed until the clock caught up.
 */
fun shouldRecordSystemStop(
    kind: PlatformStopKind,
    ownMarkMs: Long,
    latestGrantedAtMs: Long?,
    lastAppCancelMarkMs: Long = 0L,
    nowMs: Long = System.currentTimeMillis(),
): Boolean {
    if (kind == PlatformStopKind.CANCELLED_BY_APP) return false
    // #C72: the dataSync budget is shared by every pump, so a successor's grant cannot mean it is
    // protected — the timeout is the stop the Activity line must show.
    if (kind == PlatformStopKind.FOREGROUND_SERVICE_TIMEOUT) return true
    if (lastAppCancelMarkMs > 0L && lastAppCancelMarkMs >= ownMarkMs) return false
    if (latestGrantedAtMs != null && latestGrantedAtMs > ownMarkMs && latestGrantedAtMs <= nowMs) return false
    return true
}

/**
 * C43 audit H3/M2/M3: what the foreground moment does for one transfer pump.
 * It runs on EVERY `onStart`, so it must be idempotent and never chain work.
 */
enum class ForegroundReturnAction {
    /**
     * Runnable work AND a denial/stop marker — the C43 recovery case: a
     * user-initiated kick that REPLACEs the parked/self-retrying request so
     * the pump starts now, inside the foreground window (a RUNNING pump is a
     * KEEP no-op).
     */
    KICK_REPLACE,

    /**
     * Runnable work, no marker: a plain KEEP kick. The pump is either running,
     * waiting on a constraint, or sitting in its own SF-3 backoff — none of
     * which a foreground return may cancel (re-audit H-1: REPLACE here would
     * reset the backoff and cut an upload on every onStart).
     */
    KICK_KEEP,

    /**
     * No runnable work, but a job parked in a QUOTA/ban resume window: make
     * sure its delayed wake is pending (KEEP — a denied background run
     * consumed the one the exit path left) and clear any stale marker (there
     * is nothing to resume right now, re-audit L-2). Not a REPLACE: that
     * would run an empty pass, flash the notification, and re-derive the
     * same delay.
     */
    RESTORE_PARKED_WAKE,

    /** No work but a stale denial/stop marker: nothing to resume, so the marker (and the Activity line) goes away. */
    CLEAR_MARKERS,

    /** Idle pump, no marker: leave it alone. */
    NOTHING,
}

fun foregroundReturnAction(hasWork: Boolean, parked: Boolean, markerSet: Boolean): ForegroundReturnAction = when {
    hasWork && markerSet -> ForegroundReturnAction.KICK_REPLACE
    hasWork -> ForegroundReturnAction.KICK_KEEP
    parked -> ForegroundReturnAction.RESTORE_PARKED_WAKE
    markerSet -> ForegroundReturnAction.CLEAR_MARKERS
    else -> ForegroundReturnAction.NOTHING
}

/**
 * What the Activity tab shows when a transfer pump was denied its foreground
 * service or stopped by the system since its last protected start. Pure
 * over the persisted markers (blank = absent). Null when nothing is recorded.
 */
data class BackgroundPauseState(
    val downloadDeniedAtMs: Long?,
    val cloudDeniedAtMs: Long?,
    val downloadStoppedAtMs: Long?,
    val cloudStoppedAtMs: Long?,
    val downloadStopReason: String?,
    val cloudStopReason: String?,
) {
    val anyDenied: Boolean get() = downloadDeniedAtMs != null || cloudDeniedAtMs != null
    val anyStopped: Boolean get() = downloadStoppedAtMs != null || cloudStoppedAtMs != null
}

fun backgroundPauseState(
    downloadDeniedAt: String?,
    cloudDeniedAt: String?,
    downloadStoppedAt: String?,
    cloudStoppedAt: String?,
    downloadStopReason: String?,
    cloudStopReason: String?,
): BackgroundPauseState? {
    fun ms(raw: String?): Long? = raw?.takeIf { it.isNotBlank() }?.toLongOrNull()
    fun reason(raw: String?): String? = raw?.takeIf { it.isNotBlank() }
    val state = BackgroundPauseState(
        downloadDeniedAtMs = ms(downloadDeniedAt),
        cloudDeniedAtMs = ms(cloudDeniedAt),
        downloadStoppedAtMs = ms(downloadStoppedAt),
        cloudStoppedAtMs = ms(cloudStoppedAt),
        downloadStopReason = reason(downloadStopReason),
        cloudStopReason = reason(cloudStopReason),
    )
    return state.takeIf { it.anyDenied || it.anyStopped }
}
