package com.easemic.app.work

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Pure debounce decision for [CloudConnectivityReKick]'s `NetworkCallback`
 * handlers: a single real reconnect event can fire `onAvailable` and one or
 * more `onCapabilitiesChanged` (VALIDATED) callbacks in quick succession, and
 * must re-kick the pumps only once, not once per callback.
 */
fun shouldReKickOnAvailable(lastKickMs: Long?, nowMs: Long, debounceMs: Long = 5_000): Boolean =
    lastKickMs == null || nowMs - lastKickMs >= debounceMs

/** Pumps a reconnect re-kicks, in order (A22 §2 adds [HYDRATION]). */
enum class ReconnectPump { CLOUD_INGEST, DOWNLOAD, HYDRATION }

/**
 * Pure list of pumps [CloudConnectivityReKick]'s `onReconnect` kicks after
 * the revive sweep — Context-free so [CloudConnectivityReKickTest] can pin
 * the contract (this module's JVM tests have no Robolectric/Mockito; the
 * actual kick wiring is proved on the emulator only).
 */
fun pumpsToKickOnReconnect(): List<ReconnectPump> =
    listOf(ReconnectPump.CLOUD_INGEST, ReconnectPump.DOWNLOAD, ReconnectPump.HYDRATION)

/**
 * Pure, Context-free core of the reconnect debounce + epoch bump (owner bug
 * #3, S3 Task 5 task D): [CloudConnectivityReKick] itself needs a real
 * Android `Context` (this module's JVM test deps have no Mockito/Robolectric
 * — see [CloudConnectivityReKickTest], which only exercises
 * [shouldReKickOnAvailable] directly for that reason), so this class carries
 * the actual accept/reject + epoch-increment decision in a form a plain JVM
 * test can exercise. Every ACCEPTED reconnect (same [shouldReKickOnAvailable]
 * gate the pump re-kick already used) bumps a monotonic epoch; a debounced
 * duplicate callback in the same burst does not. [CloudConnectivityReKick]
 * exposes the resulting count as [CloudConnectivityReKick.connectivityEpoch]
 * so the cloud-reachability grace loop can react to a fresh reconnect without
 * polling.
 */
class ReconnectEpochTracker(
    private val debounceMs: Long = 5_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lastKickMs = AtomicLong(-1)
    private val epoch = AtomicInteger(0)

    /** Current epoch value (0 until the first accepted reconnect). */
    fun currentEpoch(): Int = epoch.get()

    /**
     * Records a reconnect attempt at [nowMs]. Returns the bumped epoch if
     * accepted, or null if this is a debounced duplicate of the last
     * accepted reconnect.
     */
    fun onReconnectAttempt(nowMs: Long = clock()): Int? {
        val previous = lastKickMs.get()
        if (!shouldReKickOnAvailable(lastKickMs = previous.takeIf { it >= 0 }, nowMs = nowMs, debounceMs = debounceMs)) {
            return null
        }
        if (!lastKickMs.compareAndSet(previous, nowMs)) {
            // Lost the race to another callback in the same burst; that one
            // already bumped the epoch instead.
            return null
        }
        return epoch.incrementAndGet()
    }
}

/**
 * Re-kicks [CloudIngestWorker], [DownloadPumpWorker] and (A22) [HydrationWorker]
 * when the default network becomes available again (S3 Task 4 gap A): WorkManager's
 * `NetworkType.CONNECTED` constraint only gates a worker that was already
 * enqueued — nothing currently re-enqueues either pump when the device comes
 * back online after being offline (no prior [android.net.ConnectivityManager.NetworkCallback]
 * existed anywhere in the app). Without this, a job that failed offline sits
 * until the next unrelated app-start or manual action re-kicks the pump.
 *
 * Registered once at app start against the default network
 * ([NetworkRequest.Builder] with [NetworkCapabilities.NET_CAPABILITY_INTERNET]
 * `+` [NetworkCapabilities.NET_CAPABILITY_VALIDATED]) so a captive portal or a
 * network with no real internet access does not spuriously re-kick. Always
 * uses [Context.getApplicationContext] — never holds an Activity context.
 *
 * [reviveSweep] runs before every re-kick so a stuck FAILED cloud-intent job
 * (owner bug A6, see [com.easemic.app.feature.download.downloadJobsToRevive])
 * gets a chance to re-enter the consumption queue on the very reconnect that
 * would otherwise be its only chance to make progress.
 */
class CloudConnectivityReKick(
    context: Context,
    private val debounceMs: Long = 5_000,
    private val clock: () -> Long = System::currentTimeMillis,
    private val reviveSweep: suspend () -> Unit = {},
) {
    private val appContext: Context = context.applicationContext
    private val epochTracker = ReconnectEpochTracker(debounceMs = debounceMs, clock = clock)
    private val _connectivityEpoch = MutableStateFlow(0)

    /**
     * Bumped once per ACCEPTED reconnect (owner bug #3, task D) — the
     * cloud-reachability grace loop in `PrivateComicLibraryApp` keys a
     * `LaunchedEffect` on this so a reconnect re-arms the probe loop instead
     * of leaving a stale [com.easemic.app.feature.bridge.GraceState.Unreachable]
     * banner up until some unrelated recomposition happens to re-run it.
     */
    val connectivityEpoch: StateFlow<Int> = _connectivityEpoch.asStateFlow()

    /**
     * Synchronous point-in-time check of whether the device currently has a
     * VALIDATED default network (owner bug #3, task D): the first cloud
     * probe after app start must wait for this rather than firing on a
     * fixed delay or immediately on cold start, when the radio/DNS may not
     * have finished negotiating yet.
     */
    fun isValidatedNow(): Boolean {
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Registers the callback; safe to call once per process lifetime. */
    fun register(scope: CoroutineScope) {
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        connectivityManager.registerNetworkCallback(
            request,
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    onReconnect(scope)
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                        onReconnect(scope)
                    }
                }
            },
        )
    }

    private fun onReconnect(scope: CoroutineScope) {
        val newEpoch = epochTracker.onReconnectAttempt(clock()) ?: return
        _connectivityEpoch.value = newEpoch
        scope.launch {
            reviveSweep()
            for (pump in pumpsToKickOnReconnect()) {
                when (pump) {
                    ReconnectPump.CLOUD_INGEST -> CloudIngestWorker.kick(appContext)
                    ReconnectPump.DOWNLOAD -> DownloadPumpWorker.kick(appContext)
                    // A22 §2: an already-hydrated device must re-read the
                    // manifest when it comes back online — same VALIDATED +
                    // debounce path as the other pumps; KEEP makes this a
                    // no-op when a hydration run is already enqueued.
                    ReconnectPump.HYDRATION -> HydrationWorker.kick(appContext)
                }
            }
        }
    }
}
