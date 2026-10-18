package com.easemic.app.feature.collect

import com.easemic.app.feature.bridge.ByoCloudIoCopy
import com.easemic.app.sync.LibraryLockedException

/**
 * #C63: every message a row can only be FAILED-with because of a condition
 * that a (re)bind or an unlock clears -- BIND (no bucket was configured yet),
 * RECONNECT (the OAuth grant died), and now the locked-library message (the
 * shared [com.easemic.app.sync.CloudLibraryGate] was
 * locked) -- is revived here. Extracted out of `AppGraph.onBucketBound` so
 * this fan-out is directly testable without the rest of that hook's Android
 * plumbing; `onBucketBound` calls this and then kicks the pump, same as
 * before.
 */
fun reviveRowsOnBind(queue: CloudSyncQueue, nowMs: Long) {
    queue.reviveFailedWithMessage(ByoCloudIoCopy.BIND, nowMs)
    queue.reviveFailedWithMessage(ByoCloudIoCopy.RECONNECT, nowMs)
    queue.reviveFailedWithMessage(LibraryLockedException.MESSAGE, nowMs)
    // #C81: a rebind (a bigger drive, or the same one after freeing space) gets full-drive rows moving again.
    queue.reviveFailedWithMessage(com.easemic.app.sync.WebDavObjectStore.QUOTA_FULL, nowMs)
    // #D16: a renewal or a reconnect makes a refused sign-in accepted again — parked rows move again.
    queue.reviveFailedWithMessage(com.easemic.app.sync.CloudSignIn.KEY, nowMs)
}
