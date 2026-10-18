package com.easemic.app.feature.collect

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job

/** In-process Cloud Collection ingest jobs, keyed by acquisition URL or cloudComicId. */
class BackgroundIngestRegistry {
    private val jobs = ConcurrentHashMap<String, Job>()

    /**
     * Register [job] for [key]. If another active job already owns the key, keep
     * it and return false — do not cancel mid-FETCH/UPLOAD (that silently drops
     * the Cloud sync queue row via CancellationException).
     *
     * Explicit [cancel] remains the Uncollect/Discard path.
     */
    fun register(key: String, job: Job): Boolean {
        while (true) {
            val existing = jobs[key]
            if (existing != null && existing.isActive && existing !== job) {
                return false
            }
            if (existing == null) {
                if (jobs.putIfAbsent(key, job) == null) {
                    job.invokeOnCompletion { jobs.remove(key, job) }
                    return true
                }
                continue
            }
            if (existing === job) {
                return true
            }
            // Stale completed entry — replace.
            if (jobs.replace(key, existing, job)) {
                job.invokeOnCompletion { jobs.remove(key, job) }
                return true
            }
        }
    }

    fun cancel(key: String) {
        jobs.remove(key)?.cancel()
    }

    fun isActive(key: String): Boolean = jobs[key]?.isActive == true
}
