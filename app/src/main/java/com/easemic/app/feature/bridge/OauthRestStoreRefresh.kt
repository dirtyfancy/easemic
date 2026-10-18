package com.easemic.app.feature.bridge

import com.easemic.app.sync.R2BearerTokens
import com.easemic.app.sync.R2RestObjectStore
import com.easemic.app.sync.ObjectStore

/**
 * Builds the in-memory OAuth R2 REST store for the bound bucket. The token
 * exchange itself belongs to [OauthAccessTokenSource] (C37-c) — this only
 * verifies the grant can still produce a token before publishing a store, and
 * hands that same source to the store as its bearer. A grant the server has
 * revoked is a visible [Failed] with [ByoCloudIoCopy.RECONNECT] — never a
 * silent no-op, never something a retry loop can fix.
 */
sealed interface OauthRestStoreRefreshResult {
    data class Ready(
        val cacheKey: String,
        val store: ObjectStore,
    ) : OauthRestStoreRefreshResult

    data class Failed(val message: String) : OauthRestStoreRefreshResult

    /** No secrets, blank client id, or the cache already holds this account/bucket's store. */
    data object Skipped : OauthRestStoreRefreshResult
}

object OauthRestStoreRefresh {
    /** Cache identity for the OAuth store: the bucket, not the rotating refresh token. */
    fun cacheKeyFor(secrets: BridgeOAuthDeviceSecrets): String =
        "${secrets.accountId.trim()}|${secrets.bucket.trim()}"

    /**
     * C37-c review (F5): whether `AppGraph.validCachedOauthStore()` may hand
     * back the store it has cached. The `accountId|bucket` key answers "is
     * this store pointed at the bucket we are still bound to", but not "can
     * its bearer still produce a token" — and a grant can die MID-session,
     * leaving a perfectly matching key in front of a store whose every request
     * will 401 forever. [dead] closes that hole, and the full chain it opens
     * is: the cached store is rejected here → `resolveGatedObjectStore` falls
     * through to `refreshOauthRestStore` → [refresh]'s dead check (which sits
     * above its own cache-key skip for the same reason) returns
     * [OauthRestStoreRefreshResult.Failed] with [ByoCloudIoCopy.RECONNECT] →
     * the gated store resolves to null → `CloudIngestRunner`'s dead-grant
     * branch parks the row with that copy. The Cloud storage screen's
     * `refreshOauthRestStore()` consumer surfaces the same copy to the user.
     */
    fun cachedOauthStoreUsable(
        cachedKey: String?,
        secrets: BridgeOAuthDeviceSecrets?,
        dead: Boolean,
    ): Boolean {
        if (secrets == null || cachedKey == null || dead) return false
        return cachedKey == cacheKeyFor(secrets)
    }

    fun refresh(
        secrets: BridgeOAuthDeviceSecrets?,
        clientIdBlank: Boolean,
        cachedKey: String?,
        tokenSource: OauthAccessTokenSource,
        failureMessage: String = BridgeR2BindCopy.CONNECT_FAILED,
        buildStore: (accountId: String, bucket: String, tokens: R2BearerTokens) -> ObjectStore =
            { accountId, bucket, tokens ->
                R2RestObjectStore(accountId = accountId, bucket = bucket, tokens = tokens)
            },
    ): OauthRestStoreRefreshResult {
        if (secrets == null || clientIdBlank) return OauthRestStoreRefreshResult.Skipped
        // Above the cache-key early return on purpose: a grant that died
        // mid-session leaves the `accountId|bucket` key perfectly valid, so
        // skipping on the key first would swallow the one report that makes
        // the dead grant visible.
        if (tokenSource.isDead()) return OauthRestStoreRefreshResult.Failed(ByoCloudIoCopy.RECONNECT)
        val key = cacheKeyFor(secrets)
        if (cachedKey == key) return OauthRestStoreRefreshResult.Skipped
        // Prove the grant works once (and warm the token) before publishing a store.
        if (tokenSource.current() == null) {
            return OauthRestStoreRefreshResult.Failed(
                if (tokenSource.isDead()) ByoCloudIoCopy.RECONNECT else failureMessage,
            )
        }
        return OauthRestStoreRefreshResult.Ready(
            cacheKey = key,
            store = buildStore(secrets.accountId, secrets.bucket, tokenSource.asBearerTokens()),
        )
    }
}
