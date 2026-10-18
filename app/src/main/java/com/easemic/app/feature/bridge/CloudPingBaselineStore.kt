package com.easemic.app.feature.bridge

import com.easemic.app.ui.PreferenceMap

/**
 * Persisted rolling window of real R2 ping latencies (owner bug #3 fix),
 * feeding [CloudProbeGrace.plan]'s adaptive timeout so the grace loop's
 * budget tracks this device's actual measured link quality instead of one
 * fixed constant for every network. Keeps only the most recent
 * [MAX_SAMPLES] readings — old samples from a since-changed network (e.g.
 * switching from Wi-Fi to a slow cellular link) age out rather than
 * permanently dragging the baseline toward stale conditions.
 */
class CloudPingBaselineStore(private val prefs: PreferenceMap) {
    /** Median of the persisted samples, or null when none have been recorded yet. */
    fun baselineMs(): Long? {
        val samples = read()
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[mid]
        } else {
            (sorted[mid - 1] + sorted[mid]) / 2
        }
    }

    /** Records a newly-measured round-trip latency, evicting the oldest sample past [MAX_SAMPLES]. */
    fun record(latencyMs: Long) {
        val updated = (read() + latencyMs).takeLast(MAX_SAMPLES)
        prefs.putString(KEY, updated.joinToString(","))
    }

    private fun read(): List<Long> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return raw.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
    }

    companion object {
        private const val KEY = "cloud_ping_samples_v1"
        private const val MAX_SAMPLES = 8
    }
}
