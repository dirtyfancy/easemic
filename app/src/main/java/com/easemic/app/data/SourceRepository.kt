package com.easemic.app.data

import com.easemic.app.model.AuthorizedSource
import com.easemic.app.model.AuthorizedSourceDraft
import com.easemic.app.model.ImportBatch
import com.easemic.app.model.ImportBatchId
import com.easemic.app.model.SourceGrantState
import com.easemic.app.model.SourceId
import com.easemic.app.model.SourceRelease
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

interface SourceRepository {
    fun observeSources(): Flow<List<AuthorizedSource>>
    fun observeBatches(): Flow<List<ImportBatch>>
    suspend fun source(sourceId: SourceId): AuthorizedSource?
    suspend fun upsertAuthorizedSource(draft: AuthorizedSourceDraft): SourceId
    suspend fun createBatch(sourceIds: List<SourceId>): ImportBatchId
    suspend fun setGrantState(sourceId: SourceId, state: SourceGrantState)
    suspend fun releaseSource(sourceId: SourceId): SourceRelease

    /**
     * A30 §2.2: optimistic hide for "Stop watching". Unlike [releaseSource] the
     * SAF grant is retained so [restoreStoppedSource] can undo without another
     * picker trip; the grant is only actually released once
     * [releasePendingStoppedGrants] sweeps it after the Undo window closes.
     */
    suspend fun stopWatchingSource(sourceId: SourceId): SourceRelease = releaseSource(sourceId)

    /**
     * A30 §2.2: reverses [stopWatchingSource] while its Undo window is still
     * open. Returns false if the folder was not in the stopped state (e.g.
     * already live, or its grant was already swept).
     */
    suspend fun restoreStoppedSource(sourceId: SourceId): Boolean = false

    /** A30 §2.2: releases grants for folders whose Undo window has closed. */
    suspend fun releasePendingStoppedGrants() {}

    /**
     * A30 §2.2 (S4b/B3): re-checks the persisted read grants of every READY folder and
     * marks the ones Android has taken away as `NEEDS_REAUTHORIZATION`, so the running app
     * states "Access lost · Choose folder again" instead of a misleading "0 comics" until
     * the next cold start. Reads the persisted-permission list only -- no tree I/O -- so it
     * is cheap enough for the app-foreground hook and for every rescan entry point.
     *
     * Returns the folders whose state changed. Never touches location state: losing access
     * is not evidence that any file is gone.
     */
    suspend fun revalidateReadyGrants(): List<SourceId> = emptyList()

    /** A30 §2.2/§5: live per-folder comic counts for the Folders row status line. */
    fun observeComicCountsBySource(): Flow<Map<SourceId, Int>> = flowOf(emptyMap())

    /** A30 §2.2/§5: last completed scan per folder, for the Folders row status line. */
    fun observeLastCompletedScanBySource(): Flow<Map<SourceId, Long>> = flowOf(emptyMap())
}
