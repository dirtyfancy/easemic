package com.easemic.app.feature.providers

import com.easemic.app.importing.opds.OpdsCatalogClient
import com.easemic.app.importing.opds.OpdsCatalogException
import com.easemic.app.sync.DiagLog
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

sealed class OpdsConnectResult {
    data class Connected(val providers: List<OpdsProviderConfig>) : OpdsConnectResult()
    data object AlreadyConnected : OpdsConnectResult()

    /** Failure states. Each names a different next step for the owner. */
    sealed class Failure : OpdsConnectResult()

    /** Nothing answered: wrong host, wrong port, server down, no route. */
    data object Unreachable : Failure()

    /** The server answered and refused the credentials (401/403). */
    data class AuthRejected(val code: Int) : Failure()

    /**
     * Something answered, but it is not an OPDS catalog: a 404 at this path,
     * or a body that will not parse as an Atom feed. Kavita in particular puts
     * its OPDS key in the URL path, so a bare base URL lands here rather than
     * on [Unreachable] — that distinction is the whole point of this split.
     */
    data class NotAFeed(val code: Int) : Failure()

    /** The server answered with a fault of its own (5xx, or anything unmapped). */
    data class ServerError(val code: Int) : Failure()
}

sealed class OpdsCatalogConnectEditResult {
    data class Edited(val providers: List<OpdsProviderConfig>) : OpdsCatalogConnectEditResult()
    data object NotFound : OpdsCatalogConnectEditResult()
}

object OpdsCatalogConnect {
    /**
     * Performs the lightweight Browse health probe. Any catalog response,
     * including auth/HTTP or parse failures, is a completed probe whose
     * reachability is false; coroutine cancellation is never swallowed.
     *
     * #C51: the probe used to be silent, so a run that produced no verdict
     * (neither [OpdsProviderConfig.lastSeenOkEpochMs] nor
     * [OpdsProviderConfig.unreachableSinceEpochMs] gets written) left nothing
     * to look at. [log] carries a start line and a result line through
     * [DiagLog] by default — never the catalog URL itself, which carries the
     * auth key in its path; only the provider id and host are named.
     */
    suspend fun probeReachability(
        provider: OpdsProviderConfig,
        client: OpdsCatalogClient,
        log: (String) -> Unit = { DiagLog.i("OpdsProbe", it) },
    ): Boolean {
        val host = runCatching { java.net.URI(provider.catalogUrl).host }.getOrNull()
        log("probe start id=${provider.id} host=$host")
        val startNanos = System.nanoTime()
        fun elapsedMs() = (System.nanoTime() - startNanos) / 1_000_000
        return try {
            client.fetchFeed(
                url = provider.catalogUrl,
                username = provider.username.ifBlank { null },
                password = provider.password.ifBlank { null },
            )
            log("probe result id=${provider.id} reachable=true ms=${elapsedMs()} cause=ok")
            true
        } catch (error: kotlinx.coroutines.CancellationException) {
            log("probe cancelled id=${provider.id} ms=${elapsedMs()}")
            throw error
        } catch (error: Exception) {
            log(
                "probe result id=${provider.id} reachable=false ms=${elapsedMs()} " +
                    "cause=${error::class.simpleName}",
            )
            false
        }
    }

    /** The caller owns the non-suspending, latest-state commit on its UI context. */
    suspend fun probeAndPersistIncrementally(
        snapshot: List<OpdsProviderConfig>,
        probe: suspend (OpdsProviderConfig) -> Boolean,
        atEpochMs: () -> Long,
        commitOutcome: (OpdsProviderConfig, Boolean, Long) -> Unit,
    ) {
        coroutineScope {
            snapshot.map { provider ->
                launch {
                    val reachable = probe(provider)
                    currentCoroutineContext().ensureActive()
                    commitOutcome(provider, reachable, atEpochMs())
                }
            }.joinAll()
        }
    }

    /**
     * Applies one completed probe to the latest rows. Call this synchronously
     * on the UI owner context so settings edits and probe commits are
     * serialized instead of racing through suspendable read/write callbacks.
     */
    fun applyProbeOutcome(
        fresh: List<OpdsProviderConfig>,
        snapshotProvider: OpdsProviderConfig,
        reachable: Boolean,
        atEpochMs: Long,
    ): List<OpdsProviderConfig> {
        val current = fresh.firstOrNull { it.id == snapshotProvider.id } ?: return fresh
        if (!sameConnection(snapshotProvider, current)) return fresh
        return recordProbeOutcome(
            existing = fresh,
            id = snapshotProvider.id,
            reachable = reachable,
            atEpochMs = atEpochMs,
        )
    }

    fun connect(
        existing: List<OpdsProviderConfig>,
        displayName: String,
        catalogUrl: String,
        username: String,
        password: String,
        client: OpdsCatalogClient,
        idFactory: () -> String = { java.util.UUID.randomUUID().toString() },
        loginPost: OpdsLoginPost = OpdsConnectDiscovery.defaultLoginPost(),
    ): OpdsConnectResult {
        val url = catalogUrl.trim()
        val user = username.trim()
        if (url.isEmpty()) return OpdsConnectResult.Unreachable
        val identity = OpdsLibraryIdentity.of(url, user)
        val match = existing.firstOrNull {
            OpdsLibraryIdentity.of(it.catalogUrl, it.username) == identity
        }
        if (match != null && match.catalogUrl.trim() == url) {
            return OpdsConnectResult.AlreadyConnected
        }
        // Resolution covers the pasted URL first, then the auth and entry-point
        // shapes real servers use; the URL that actually served the feed is the
        // one that gets persisted.
        val resolution = try {
            OpdsConnectDiscovery.resolve(
                catalogUrl = url,
                username = user,
                password = password,
                client = client,
                loginPost = loginPost,
            )
        } catch (error: OpdsCatalogException) {
            OpdsFeedResolution.Failed(classify(error))
        } catch (_: java.io.IOException) {
            // Transport never reached an answering server: DNS, refused, timeout.
            OpdsFeedResolution.Failed(OpdsConnectResult.Unreachable)
        } catch (_: Exception) {
            OpdsFeedResolution.Failed(OpdsConnectResult.Unreachable)
        }
        val resolvedUrl = when (resolution) {
            is OpdsFeedResolution.Failed -> return resolution.failure
            is OpdsFeedResolution.Resolved -> resolution.catalogUrl
        }
        // Discovery can land on a URL an existing row already holds (pasting a
        // server root for a library connected by its full catalog path).
        val resolvedMatch = match ?: existing.firstOrNull {
            OpdsLibraryIdentity.of(it.catalogUrl, it.username) ==
                OpdsLibraryIdentity.of(resolvedUrl, user)
        }
        if (resolvedMatch != null && resolvedMatch.catalogUrl.trim() == resolvedUrl) {
            return OpdsConnectResult.AlreadyConnected
        }
        val typedName = displayName.trim()
        val name = typedName.ifEmpty { "OPDS library" }
        return if (resolvedMatch != null) {
            val updated = resolvedMatch.copy(
                displayName = typedName.ifEmpty { resolvedMatch.displayName },
                catalogUrl = resolvedUrl,
                username = user,
                password = password,
            )
            OpdsConnectResult.Connected(
                existing.map { if (it.id == resolvedMatch.id) updated else it },
            )
        } else {
            val added = OpdsProviderConfig(idFactory(), name, resolvedUrl, user, password)
            OpdsConnectResult.Connected(existing + added)
        }
    }

    /**
     * Turns what the client already knew into what the owner needs to do next.
     * The client throws [OpdsCatalogException.Http] with the real status and
     * [OpdsCatalogException.Parse] when a body is not an Atom feed; both used
     * to collapse into one opaque failure.
     */
    private fun classify(error: OpdsCatalogException): OpdsConnectResult.Failure = when (error) {
        is OpdsCatalogException.Parse -> OpdsConnectResult.NotAFeed(200)
        is OpdsCatalogException.Http -> when (error.code) {
            401, 403 -> OpdsConnectResult.AuthRejected(error.code)
            404, 410 -> OpdsConnectResult.NotAFeed(error.code)
            else -> OpdsConnectResult.ServerError(error.code)
        }
    }

    fun disconnect(existing: List<OpdsProviderConfig>, id: String): List<OpdsProviderConfig> =
        existing.filterNot { it.id == id }

    /**
     * Edit-save path for the settings surface: persists the edited fields
     * WITHOUT probing the server. This is the only way to fix a changed
     * password or port while that server is currently unreachable. The
     * [client] parameter is accepted but deliberately never used — keeping it
     * in the signature pins down that this path must not gain a fetch later.
     * Recorded health facts are preserved untouched; probing updates them via
     * [recordProbeOutcome] instead.
     */
    @Suppress("UNUSED_PARAMETER")
    fun saveEdit(
        existing: List<OpdsProviderConfig>,
        id: String,
        displayName: String,
        catalogUrl: String,
        username: String,
        password: String,
        client: OpdsCatalogClient? = null,
    ): OpdsCatalogConnectEditResult {
        val match = existing.firstOrNull { it.id == id } ?: return OpdsCatalogConnectEditResult.NotFound
        val url = catalogUrl.trim()
        if (url.isEmpty()) return OpdsCatalogConnectEditResult.NotFound
        val updated = match.copy(
            displayName = displayName.trim().ifEmpty { match.displayName },
            catalogUrl = url,
            username = username.trim(),
            password = password,
        )
        return OpdsCatalogConnectEditResult.Edited(
            existing.map { if (it.id == id) updated else it },
        )
    }

    /**
     * Applies the outcome of an on-demand light probe (settings page open,
     * or actual source use) to the persisted health fields. Reachable probes
     * set [OpdsProviderConfig.lastSeenOkEpochMs] and clear the unreachable
     * marker; failed probes set the unreachable marker (first failure only, so
     * "unreachable since" keeps meaning "down since", not "last tried").
     */
    fun recordProbeOutcome(
        existing: List<OpdsProviderConfig>,
        id: String,
        reachable: Boolean,
        atEpochMs: Long,
    ): List<OpdsProviderConfig> =
        existing.map { provider ->
            if (provider.id != id) {
                provider
            } else if (reachable) {
                provider.copy(
                    lastSeenOkEpochMs = atEpochMs,
                    unreachableSinceEpochMs = null,
                )
            } else {
                provider.copy(unreachableSinceEpochMs = provider.unreachableSinceEpochMs ?: atEpochMs)
            }
        }

    private fun sameConnection(
        left: OpdsProviderConfig,
        right: OpdsProviderConfig,
    ): Boolean =
        left.catalogUrl.trim() == right.catalogUrl.trim() &&
            left.username.trim() == right.username.trim() &&
            left.password == right.password

    fun mergeNew(
        current: List<OpdsProviderConfig>,
        added: OpdsProviderConfig,
    ): List<OpdsProviderConfig> {
        val identity = OpdsLibraryIdentity.of(added.catalogUrl, added.username)
        val index = current.indexOfFirst {
            OpdsLibraryIdentity.of(it.catalogUrl, it.username) == identity
        }
        if (index < 0) return current + added
        val existing = current[index]
        if (existing.catalogUrl.trim() == added.catalogUrl.trim()) return current
        val replaced = added.copy(id = existing.id)
        return current.toMutableList().also { it[index] = replaced }
    }
}
