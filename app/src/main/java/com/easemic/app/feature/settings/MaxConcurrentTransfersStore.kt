package com.easemic.app.feature.settings

import com.easemic.app.ui.PreferenceMap

/**
 * Owner bug A7 (S6-A): the ONE shared "downloads at once" cap read by
 * both the download pump (`DownloadWorkerLoop.tick` gallery slots) and the
 * cloud ingest pump (`runCloudIngestPumpLoop` fan-out). Governs job SLOTS,
 * not request rate — same-source page GETs are additionally paced by
 * `DownloadWorkerLoop`'s per-source `SourcePacingGate` (one shared permit +
 * spacing timer per `providerNamespace`, so N same-source galleries never
 * multiply that source's request rate). `SourcePacingGate` is the ONLY pacer
 * on that path: #C10 removed the `CatalogRequestGate.Shared` 750 ms floor
 * that used to sit underneath it, because two limiters in series collapsed
 * the worker's parallel window to one page at a time and made this very
 * setting look inert. Interactive browse/list callers keep their own gate.
 * Clamped to [MIN]..[MAX] on both read and write so a corrupt or hand-edited value
 * can never fan a pump out beyond 5 or collapse it to 0. Default [DEFAULT]
 * = 1 keeps the phone-verified pre-S6 behavior until the user opts up.
 */
class MaxConcurrentTransfersStore(
    private val prefs: PreferenceMap,
) {
    fun read(): Int {
        val parsed = prefs.getString(KEY, null)?.toIntOrNull() ?: return DEFAULT
        return clampMaxConcurrentTransfers(parsed)
    }

    fun write(value: Int) {
        prefs.putString(KEY, clampMaxConcurrentTransfers(value).toString())
    }

    companion object {
        const val KEY = "max_concurrent_transfers_v1"
        const val MIN = 1
        const val MAX = 5
        const val DEFAULT = 1
    }
}

/** Pure clamp shared by the store and the Settings stepper. */
fun clampMaxConcurrentTransfers(value: Int): Int =
    value.coerceIn(MaxConcurrentTransfersStore.MIN, MaxConcurrentTransfersStore.MAX)
