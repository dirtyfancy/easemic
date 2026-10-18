package com.easemic.app.feature.source.jm

import com.easemic.app.feature.download.FetchFailureClass
import com.easemic.app.feature.download.FetchRetryContext
import com.easemic.app.feature.download.FetchTransportException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

internal data class JmDomainState(val api: List<String>, val image: String?, val refreshedAt: Long, val imageRefreshedAt: Long? = null)
internal interface JmDomainStorage {
    fun load(): JmDomainState?
    fun save(state: JmDomainState)
}

/** Constructors read local storage only. Production trust never permits cleartext or local origins. */
internal class JmDomains(
    private val storage: JmDomainStorage,
    private val publication: suspend () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val apiOrigins: List<HttpUrl>? = null,
    private val imageOrigins: List<HttpUrl>? = null,
    private val publicationDeadlineMillis: Long = 10_000,
    /** #E84: further addresses for the same publication, tried in order when the one before fails. */
    private val fallbackPublications: List<suspend () -> String> = emptyList(),
) {
    private val refreshLock = Mutex()
    private val imageLock = Mutex()
    private val stateLock = Any()
    private val saved = runCatching { storage.load() }.getOrNull()
    @Volatile private var apis = apiOrigins?.toList() ?: validatedSaved(saved?.api) ?: BUNDLED
    @Volatile private var images = imageOrigins?.toList() ?: saved?.image?.let { runCatching { listOf(origin(it)) }.getOrNull() } ?: emptyList()
    private var refreshedAt = saved?.refreshedAt ?: 0L
    private var imageRefreshedAt = saved?.imageRefreshedAt
    private var lastImageAttempt = saved?.imageRefreshedAt
    private var lastAttempt: Long? = saved?.takeIf { validatedSaved(it.api) != null }?.refreshedAt
    @Volatile var generation: Long = 0; private set
    val apiPool: List<HttpUrl> get() = apis.toList()
    val imagePool: List<HttpUrl> get() = images.toList()

    suspend fun refresh() = refreshLock.withLock {
        val now = clock()
        if (lastAttempt?.let { now >= it && now - it < 86_400_000 } == true) return@withLock
        try {
            val replacement = (listOf(publication) + fallbackPublications).firstNotNullOfOrNull { source ->
                try {
                    publishedPool(withTimeoutOrNull(publicationDeadlineMillis) { source() } ?: JmFailure.invalid())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            } ?: JmFailure.invalid()
            synchronized(stateLock) {
                // A failed durable write must not replace the working process state either.
                storage.save(JmDomainState(replacement.map { it.toString() }, images.firstOrNull()?.toString(), now, imageRefreshedAt))
                if (apis != replacement) generation++
                apis = replacement
                refreshedAt = now
            }
            lastAttempt = now
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            lastAttempt = now // Failed refreshes are also bounded, rather than repeated on every request.
        }
    }
    private fun publishedPool(text: String): List<HttpUrl> {
        val json = JmCrypto.publication(text)
        val servers = json.optJSONArray("Server") ?: JmFailure.invalid()
        val replacement = List(servers.length()) { index -> origin(servers.opt(index) as? String ?: JmFailure.invalid()) }.distinct()
        if (replacement.isEmpty() || replacement.size > 16) JmFailure.invalid()
        return replacement
    }
    suspend fun imageOrigin(setting: suspend () -> String): HttpUrl = imageLock.withLock {
        images.firstOrNull() ?: run {
            setImage(setting())
            images.first()
        }
    }

    /** The failed request has already released its wire permit before discovery starts. */
    suspend fun refreshImage(failed: HttpUrl, setting: suspend () -> String): Boolean = imageLock.withLock {
        if (images.firstOrNull() != failed) return@withLock true
        val now = clock()
        if (lastImageAttempt?.let { now >= it && now - it < 86_400_000 } == true) return@withLock false
        try {
            setImage(setting(), now)
            lastImageAttempt = now
            true // One retry is also useful when setting still advertises the same origin.
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            lastImageAttempt = now
            false // Retain the last validated/persisted image pool on discovery or write failure.
        }
    }

    fun setImage(value: String) = setImage(value, imageRefreshedAt)

    private fun setImage(value: String, refreshed: Long?) {
        if (!value.startsWith("https://")) JmFailure.invalid()
        val replacement = origin(value)
        synchronized(stateLock) {
            storage.save(JmDomainState(apis.map { it.toString() }, replacement.toString(), refreshedAt, refreshed))
            if (images != listOf(replacement)) generation++
            images = listOf(replacement)
            imageRefreshedAt = refreshed
        }
    }
    fun owns(role: JmRole, url: HttpUrl): Boolean = pool(role).any {
        it.scheme == url.scheme && it.host == url.host && it.port == url.port
    }
    private fun pool(role: JmRole): List<HttpUrl> = when (role) {
        JmRole.API -> apiPool
        JmRole.IMAGE -> imagePool
        JmRole.PUBLICATION -> emptyList() // Publication is fetched by the injected transport, never an API mirror.
    }
    suspend fun <T> attempt(role: JmRole, onConnectionFailure: (suspend (HttpUrl) -> Boolean)? = null,
        block: suspend (HttpUrl) -> T): T {
        val tried = HashSet<HttpUrl>()
        var failure: FetchTransportException? = null
        var attempt = 0
        while (true) {
            val host = pool(role).firstOrNull { it !in tried } ?: break
            tried.add(host)
            try {
                return block(host).also { remember(role, host) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: FetchTransportException) {
                failure = error
                val classified = JmFailure.classify(error.statusCode, error.bodySample, error.headers, FetchRetryContext())
                if (JmFailure.isErrorEnvelope(error) || classified is FetchFailureClass.PACED || classified is FetchFailureClass.ABUSE_STOP ||
                    (role == JmRole.IMAGE && error.statusCode != 0) ||
                    !(error.statusCode == 0 || error.statusCode in 500..599 || (role == JmRole.API && error.statusCode == 200))) throw error
                if (role == JmRole.API && error.statusCode == 0) refresh()
                if (role == JmRole.IMAGE && error.statusCode == 0 && attempt == 0 && onConnectionFailure?.invoke(host) == true) {
                    tried.remove(host)
                }
                attempt++
            }
        }
        throw (failure ?: JmFailure.invalid())
    }
    private fun remember(role: JmRole, host: HttpUrl) {
        if (role != JmRole.API) return
        synchronized(stateLock) {
            if (apis.firstOrNull() != host) apis = listOf(host) + apis.filterNot { it == host }
        }
    }
    companion object {
        val BUNDLED = listOf("www.cdnhjk.net", "www.cdngwc.cc", "www.cdngwc.net", "www.cdngwc.club")
            .map { "https://$it".toHttpUrl() }
        private fun validatedSaved(entries: List<String>?): List<HttpUrl>? = runCatching {
            entries?.map { origin(it) }?.distinct()?.takeIf { it.isNotEmpty() && it.size <= 16 }
        }.getOrNull()
        fun origin(value: String): HttpUrl {
            val raw = if (value.contains("://")) value else "https://$value"
            // Inspect unnormalised components: HttpUrl removes dot segments and empty userinfo.
            val uri = runCatching { URI(raw) }.getOrNull() ?: JmFailure.invalid()
            if (uri.scheme != "https" || uri.host == null || uri.rawUserInfo != null ||
                uri.port !in listOf(-1, 443) || uri.rawAuthority.endsWith(':') ||
                uri.rawPath !in listOf("", "/") || uri.rawQuery != null || uri.rawFragment != null) JmFailure.invalid()
            val url = runCatching { raw.toHttpUrl() }.getOrNull() ?: JmFailure.invalid()
            val host = url.host
            if (url.scheme != "https" || url.port != 443 || url.username.isNotEmpty() || url.password.isNotEmpty() ||
                url.encodedPath != "/" || url.query != null || url.fragment != null ||
                host.length > 253 || !host.contains('.') || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
                !Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?").matches(host) ||
                host.split('.').any { it.isEmpty() || it.startsWith('-') || it.endsWith('-') } ||
                host.split('.').all { it.toIntOrNull() != null }) JmFailure.invalid()
            return url
        }
    }
}
