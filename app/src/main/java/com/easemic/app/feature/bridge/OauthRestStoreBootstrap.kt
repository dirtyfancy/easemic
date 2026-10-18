package com.easemic.app.feature.bridge

import com.easemic.app.sync.R2BearerTokens
import com.easemic.app.sync.R2RestObjectStore
import com.easemic.app.sync.ObjectStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Publishes the OAuth REST store for the bound bucket, at most one
 * construction at a time.
 *
 * C37-c: this no longer exchanges anything. [tokenSource] owns the token —
 * it is synchronous and single-flight in its own right, so the mutex here
 * only serialises store construction and publication: a waiting caller reads
 * [loadCachedKey] inside the lock, sees the winner's published
 * `accountId|bucket` key and Skips instead of building a second store for the
 * same bucket.
 *
 * [NonCancellable] still wraps the body so a cancelled caller cannot drop a
 * store the token exchange already paid for — otherwise oauthRestCache stays
 * empty until the user opens Cloud storage.
 *
 * The (blocking) token read runs on [ioDispatcher] (default [Dispatchers.IO]).
 * [onReady] runs after that hop so Compose cache updates stay on the caller
 * context (typically Main from LaunchedEffect).
 */
class OauthRestStoreBootstrap(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()

    suspend fun refresh(
        loadSecrets: () -> BridgeOAuthDeviceSecrets?,
        clientIdBlank: Boolean,
        loadCachedKey: () -> String?,
        tokenSource: OauthAccessTokenSource,
        failureMessage: String = BridgeR2BindCopy.CONNECT_FAILED,
        buildStore: (accountId: String, bucket: String, tokens: R2BearerTokens) -> ObjectStore =
            { accountId, bucket, tokens ->
                R2RestObjectStore(accountId = accountId, bucket = bucket, tokens = tokens)
            },
        onReady: (cacheKey: String, store: ObjectStore) -> Unit,
    ): OauthRestStoreRefreshResult = mutex.withLock {
        withContext(NonCancellable) {
            val result = withContext(ioDispatcher) {
                OauthRestStoreRefresh.refresh(
                    secrets = loadSecrets(),
                    clientIdBlank = clientIdBlank,
                    cachedKey = loadCachedKey(),
                    tokenSource = tokenSource,
                    failureMessage = failureMessage,
                    buildStore = buildStore,
                )
            }
            if (result is OauthRestStoreRefreshResult.Ready) {
                onReady(result.cacheKey, result.store)
            }
            result
        }
    }
}
