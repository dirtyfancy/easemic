package com.easemic.app.feature.bridge

import com.easemic.app.sync.CloudSignIn

/**
 * A single probe attempt's budget, plus the delays before any subsequent
 * retries — see [CloudProbeGrace.plan].
 */
data class ProbePlan(val timeoutMs: Long, val retryDelaysMs: List<Long>)

/**
 * Owner bug #3 fix: the old code set a blocked banner the instant the very
 * first R2 round-trip after app start failed, with no grace period — a cold
 * network stack (radio still negotiating, DNS not warm yet) reliably raced
 * this and lost, showing a scary "cloud unavailable" banner on an otherwise
 * healthy connection. The owner's design input (root-cause conversation)
 * is: grace/timeouts must be ADAPTIVE from a persisted rolling baseline of
 * measured pings ([CloudPingBaselineStore]), not fixed constants — a device
 * on a slow/high-latency link naturally gets a longer budget than one on a
 * fast one, instead of one fixed number that is either too impatient for the
 * former or wastefully slow for the latter.
 */
object CloudProbeGrace {
    /** Used only when there is no persisted baseline yet (first run ever). */
    const val DEFAULT_TIMEOUT_MS = 8_000L

    private const val MIN_TIMEOUT_MS = 2_000L
    private const val MAX_TIMEOUT_MS = 10_000L
    private const val MIN_DELAY_MS = 3_000L
    private const val MAX_DELAY_MS = 60_000L
    private val DELAY_MULTIPLIERS = listOf(2L, 6L, 18L)

    /**
     * Derives this attempt's timeout from [baselineMs] (the persisted median
     * round-trip latency, or null when none has been recorded yet) — three
     * times the baseline, clamped to a sane [MIN_TIMEOUT_MS]..[MAX_TIMEOUT_MS]
     * range so neither a suspiciously-fast nor a suspiciously-slow baseline
     * produces an unusable timeout. The three retry delays back off from that
     * same timeout (2x/6x/18x), each independently clamped to
     * [MIN_DELAY_MS]..[MAX_DELAY_MS].
     */
    fun plan(baselineMs: Long?): ProbePlan {
        val timeoutMs = if (baselineMs == null) {
            DEFAULT_TIMEOUT_MS
        } else {
            (baselineMs * 3).coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        }
        val delays = DELAY_MULTIPLIERS.map { multiplier ->
            (timeoutMs * multiplier).coerceIn(MIN_DELAY_MS, MAX_DELAY_MS)
        }
        return ProbePlan(timeoutMs = timeoutMs, retryDelaysMs = delays)
    }
}

/** Result of one real R2 round-trip probe attempt. */
sealed interface ProbeOutcome {
    data class Reachable(val latencyMs: Long) : ProbeOutcome
    data class Unreachable(val cause: ProbeCause, val message: String) : ProbeOutcome
}

/** Why a probe attempt reported [ProbeOutcome.Unreachable]. */
enum class ProbeCause { TOKEN_REFRESH_FAILED, NETWORK, STORE_ERROR, SIGN_IN_REJECTED }

/**
 * The grace loop's visible state — drives what `PrivateComicLibraryApp`
 * shows while probing. [Checking] is deliberately not a blocked state: no
 * banner is shown while retries remain, only after the loop has exhausted
 * every attempt in a [ProbePlan].
 */
sealed interface GraceState {
    data object Checking : GraceState
    data class Ready(val latencyMs: Long) : GraceState
    data class Unreachable(val cause: ProbeCause, val message: String) : GraceState
}

/**
 * Pure state transition for one probe attempt within the grace loop.
 * [attemptIndex] is 0-based; the loop is exhausted (no more retries left)
 * once `attemptIndex == totalAttempts - 1`. A [ProbeOutcome.Reachable]
 * always resolves to [GraceState.Ready] regardless of which attempt it was —
 * there is no reason to keep retrying once the network answers.
 */
fun graceStateAfter(attemptIndex: Int, totalAttempts: Int, outcome: ProbeOutcome): GraceState =
    when (outcome) {
        is ProbeOutcome.Reachable -> GraceState.Ready(outcome.latencyMs)
        is ProbeOutcome.Unreachable ->
            // #D16: a refused sign-in is final at once — retrying the same password cannot help.
            if (outcome.cause == ProbeCause.SIGN_IN_REJECTED || attemptIndex >= totalAttempts - 1) {
                GraceState.Unreachable(outcome.cause, outcome.message)
            } else {
                GraceState.Checking
            }
    }

/**
 * Spec-review fix (correction #1): classifies a failed GET of the
 * bucket-root lock object ([com.easemic.app.sync.CloudCollectionKeys.lockKey])
 * into a [ProbeOutcome]. [httpStatusMessage] is the failing exception's
 * message — `"CLOUD_HTTP_<code>"` for a non-2xx HTTP response (see
 * `CloudHttpException`), or any other string/null for a raw IO failure
 * (timeout, DNS, refused connection) or a non-HTTP-status failure such as
 * `"OAUTH_REFRESH_FAILED"`.
 *
 * A 404 (object not found) is [ProbeOutcome.Reachable]: the bucket answered
 * the request — the lock object just isn't there yet (an unencrypted or
 * brand-new library). 401 is a refused sign-in ([ProbeCause.SIGN_IN_REJECTED],
 * #D16 — asking again with the same saved password cannot help); 403 is a
 * store error ([ProbeCause.STORE_ERROR]) — the bucket is reachable but this
 * device isn't authorized (and 403 also means e.g. B2's daily cap or a
 * permission problem, so it stays a store error). Everything else — 5xx,
 * other HTTP codes, unparseable messages, a null message — is treated as
 * [ProbeCause.NETWORK]: judgment call, since none of those indicate the
 * request was actually answered.
 */
fun classifyLockProbeFailure(httpStatusMessage: String?, latencyMs: Long): ProbeOutcome {
    if (httpStatusMessage == null) {
        return ProbeOutcome.Unreachable(ProbeCause.NETWORK, "Cloud ping failed.")
    }
    if (CloudSignIn.rejected(httpStatusMessage)) {
        return ProbeOutcome.Unreachable(ProbeCause.SIGN_IN_REJECTED, httpStatusMessage)
    }
    val code = HTTP_STATUS_PATTERN.find(httpStatusMessage)?.groupValues?.get(1)?.toIntOrNull()
    return when {
        code == 404 -> ProbeOutcome.Reachable(latencyMs)
        code == 403 -> ProbeOutcome.Unreachable(ProbeCause.STORE_ERROR, httpStatusMessage)
        else -> ProbeOutcome.Unreachable(ProbeCause.NETWORK, httpStatusMessage)
    }
}

private val HTTP_STATUS_PATTERN = Regex("CLOUD_HTTP_(\\d+)")

/**
 * Spec-review fix (correction #2)'s JVM seam: whether a [ByoCloudIoResult.Blocked]
 * store resolution — which happens fresh on every attempt inside the grace
 * loop, not once before it — should be graded through the same grace/backoff
 * state machine as a failed ping, or treated as the pre-existing immediate,
 * non-graced case.
 *
 * When [hasBinding] is true (a bucket IS bound on this device;
 * `appGraph.currentBucketFingerprintOrNull() != null`), the Blocked result is
 * transient by construction — most commonly an OAuth refresh that hasn't
 * warmed up yet at cold start, the owner's original bug #3 symptom — so it is
 * folded into the loop as [ProbeCause.TOKEN_REFRESH_FAILED] via the returned
 * [ProbeOutcome.Unreachable], to be run through [graceStateAfter] exactly
 * like a failed ping (no banner until the final attempt).
 *
 * When [hasBinding] is false (nothing is bound at all), this returns null:
 * that is not a transient condition retries can fix, so the caller keeps the
 * pre-existing immediate-Blocked handling (set the banner now, no grace).
 */
fun graceOutcomeForBlockedStore(hasBinding: Boolean, message: String): ProbeOutcome? = when {
    !hasBinding -> null
    // #D16: C37-g's dead Cloudflare grant is a refused sign-in, not a network that may still wake up.
    message == ByoCloudIoCopy.RECONNECT -> ProbeOutcome.Unreachable(ProbeCause.SIGN_IN_REJECTED, message)
    else -> ProbeOutcome.Unreachable(ProbeCause.TOKEN_REFRESH_FAILED, message)
}

/**
 * Spec-review fix (correction #3): identifies which bucket/oauth/
 * connectivity identity a confirmed-[GraceState.Ready] probe was run
 * against. The cloud-readiness `LaunchedEffect` in `PrivateComicLibraryApp`
 * is also re-triggered by `libraryCloudEpoch` bumps after user actions
 * (Restore/Discard in Recently deleted expect a quick trash re-list) that
 * have nothing to do with which bucket is bound or whether the network is
 * reachable — re-running the full validated-network gate + ping + backoff
 * on those epoch-only re-runs would add seconds of latency to every cloud
 * action. [oauthCacheKey] is `oauthRestCache?.first`; [unlockedEpoch] is
 * `bridgeR2UnlockedEpoch`. `libraryCloudEpoch` is deliberately NOT a field
 * here — it is the one key that must NOT force a re-probe.
 */
data class CloudProbeKeys(
    val bucketLabel: String?,
    val unlockedEpoch: Int,
    val oauthCacheKey: String?,
    val connectivityEpoch: Int,
)

/**
 * Pure decision for whether the grace loop's full validated-network gate +
 * ping + backoff must run again, or whether this re-run of the
 * `LaunchedEffect` can skip straight to listing against the
 * already-confirmed store.
 *
 * Re-probes (returns `true`) unless [wasReady] is true (the loop's last
 * settled state was [GraceState.Ready], i.e. `cloudProbeReady == true`) AND
 * [newKeys] equals [prevKeys] (the identity recorded at that last Ready) —
 * covering both the initial call ([prevKeys] is null) and any
 * bucket/oauth/connectivity identity change. It also re-probes
 * unconditionally after an UNREACHABLE (or still-Checking) verdict, since
 * those clear [wasReady] to false before this is next consulted.
 */
fun shouldReprobe(prevKeys: CloudProbeKeys?, newKeys: CloudProbeKeys, wasReady: Boolean): Boolean =
    !(wasReady && newKeys == prevKeys)
