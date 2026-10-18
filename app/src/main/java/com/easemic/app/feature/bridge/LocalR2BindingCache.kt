package com.easemic.app.feature.bridge

/**
 * Process-wide cache of the unlocked S3 binding. Accountless: there is no
 * remote binding row; the durable copy lives in [BridgeDeviceR2SecretStore].
 *
 * [persistedLoader] is the durable store's `load` (see `AppGraph.r2BindingCache`).
 * C46 Task 8: a background `CloudIngestWorker` cold-starts the process WITHOUT
 * the compose UI ever running, so nothing calls
 * [BridgeR2BindPresenter.restoreFromDevice] to hydrate this cache from the
 * durable S3 secret. The pump's `resolveGatedObjectStore()` then saw a null
 * [workingBinding] and deferred every pass ("store transiently unavailable")
 * for an S3-bound device — an upload that never resumed after a process death.
 * So a cold read self-hydrates from the durable binding and warms the cache.
 * The OAuth path never had this gap: `resolveGatedObjectStore()` bootstraps a
 * REST store from the persisted refresh token headlessly.
 *
 * The loader only fills the startup gap where neither [cacheWorking] nor
 * [clearWorking] has run yet: every bind/unbind mutation in
 * [BridgeR2BindPresenter] pairs a durable `save`/`clear` with a
 * [cacheWorking]/[clearWorking] here, so a warmed cache is always kept in sync,
 * and an unbind that cleared the durable store makes the loader return null —
 * the cache cannot resurrect a disconnected binding.
 */
class LocalR2BindingCache(
    private val persistedLoader: () -> BridgeR2Binding? = { null },
) {
    @Volatile
    private var working: BridgeR2Binding? = null

    fun cacheWorking(binding: BridgeR2Binding) {
        working = binding
    }

    fun workingBinding(): BridgeR2Binding? = working ?: persistedLoader()?.also { working = it }

    fun clearWorking() {
        working = null
    }
}
