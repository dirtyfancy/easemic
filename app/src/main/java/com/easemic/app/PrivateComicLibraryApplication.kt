package com.easemic.app

import android.app.Application
import android.content.Context
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.easemic.app.feature.providers.EhentaiAccountStore
import com.easemic.app.storage.buildPageImageLoader
import com.easemic.app.ui.SharedPreferencesMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PrivateComicLibraryApplication :
    Application(),
    Configuration.Provider,
    SingletonImageLoader.Factory {
    val graph by lazy { AppGraph.get(this) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        graph.start(applicationScope)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(graph.workerFactory)
            .build()

    /**
     * Reads the same SharedPreferences as the graph's own store. A separate
     * instance is deliberate: it keeps the image loader off AppGraph's private
     * members, and a read-only supplier cannot diverge from a shared backing
     * store.
     */
    private val ehentaiAccountStore by lazy {
        EhentaiAccountStore(SharedPreferencesMap.from(this))
    }

    override fun newImageLoader(context: Context): ImageLoader =
        buildPageImageLoader(
            context,
            graph.pageContentOpener,
            // Read per request, so signing in mid-scroll starts filling covers
            // without rebuilding the loader.
            sessionCookie = { ehentaiAccountStore.read().normalizedCookie() },
            // Read the setting directly here: touching graph.cacheBudget would
            // register/scan cache roots on the main thread while the singleton
            // loader is being constructed. The shared budget is applied by the
            // cache writers and Settings on IO; this loader captures the
            // selected bound for its own public DiskCache instance.
            diskCacheMaxSizeBytes = com.easemic.app.storage.CacheBudget.COIL_PARTITION_BYTES,
            diskCache = graph.coilDiskCache.diskCache,
            coverInterceptor = graph.jmCoverInterceptor,
            authorizationFor = { url -> graph.resolveOpdsAuthorization(url) },
        )
}
