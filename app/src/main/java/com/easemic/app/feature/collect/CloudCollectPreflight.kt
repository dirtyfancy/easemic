package com.easemic.app.feature.collect

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Publishes the accepted tap before scheduling any remote work. Completion
 * retires only this placeholder: either a durable download job now owns the
 * request, an upload row replaced it, or preflight stopped without uploading.
 */
fun launchCloudCollectPreflight(
    scope: CoroutineScope,
    queue: CloudSyncQueue,
    row: CloudSyncQueueItem,
    onFailure: (Exception) -> Unit,
    block: suspend CoroutineScope.() -> Unit,
): Job? {
    val pending = queue.beginPreflight(row) ?: return null
    return scope.launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onFailure(error)
        }
    }.also { job ->
        // Also runs when the scope was cancelled before launch entered its body.
        job.invokeOnCompletion { queue.removePreflightPending(pending) }
    }
}
