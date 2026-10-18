package com.easemic.app.feature.update

/** #E1: the one entry point the app root calls on resume. [check] may block on the network: call it off the main thread. */
class UpdateChecker(
    private val store: UpdatePrefsStore,
    private val installedCode: Int,
    private val releaseUpdatesInstallable: Boolean = true,
    private val fetch: () -> AppRelease?,
) {
    // The first launch's resume restarts the check while the first one is still fetching; the second must wait for
    // that answer instead of reading an empty cache. [dismiss] stays outside: the dialog calls it on the main thread.
    private val checkLock = Any()

    fun check(nowMs: Long): UpdateOffer? {
        // A debug build is signed with a different key, so the release APK cannot be installed.
        if (!releaseUpdatesInstallable) return null
        return synchronized(checkLock) {
            if (UpdateCheckPolicy.shouldCheck(store.isEnabled(), store.lastCheckAtMs(), nowMs)) {
                store.setLastCheckAtMs(nowMs)
                fetch()?.let(store::setCachedRelease)
            }
            if (!store.isEnabled()) null
            else UpdateCheckPolicy.offer(installedCode, store.cachedRelease(), store.dismissedCode())
        }
    }

    fun dismiss(release: AppRelease) = store.setDismissedCode(release.versionCode)
}
