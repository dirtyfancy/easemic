package com.easemic.app.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.easemic.app.feature.collect.CloudHydrationEngine
import com.easemic.app.feature.collect.CloudIngestRunner
import com.easemic.app.feature.collect.CloudSyncQueue
import com.easemic.app.feature.download.DownloadWorkerLoop
import com.easemic.app.model.ScanRunId
import com.easemic.app.model.WorkRequestId
import kotlinx.coroutines.CancellationException

class ScanWorkerFactory(
    private val engine: ScanEngineRunner?,
    private val foregroundInfo: ImportForegroundInfo,
    private val recoverForegroundFailure: suspend (ScanRunId, WorkRequestId) -> Boolean,
    private val cloudSyncQueue: CloudSyncQueue? = null,
    private val cloudIngestRunner: CloudIngestRunner? = null,
    private val pumpForegroundInfo: PumpForegroundInfo? = null,
    private val downloadWorkerLoop: DownloadWorkerLoop? = null,
    private val cloudHydrationEngine: CloudHydrationEngine? = null,
    private val checkCfChallenge: (suspend () -> Unit)? = null,
    /** S3 Task 6 (owner bug 2026-09-04): see [CloudIngestWorker]'s `isKnownSynced` doc. */
    private val cloudIngestIsKnownSynced: (suspend (String) -> Boolean)? = null,
    /** S6-A (owner bug A7): see [CloudIngestWorker]'s `maxConcurrent` doc. */
    private val cloudIngestMaxConcurrent: (() -> Int)? = null,
    private val downloadNotificationText: (suspend () -> String)? = null,
    private val cloudNotificationText: (suspend () -> String)? = null,
    private val collectedSeriesRefresh: CollectedSeriesRefreshWorker.Deps? = null,
    private val repairSeriesIndexes: suspend (Boolean) -> Boolean = { true },
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        when (workerClassName) {
            ScanWorker::class.java.name -> ScanWorker(
                appContext,
                workerParameters,
                engine,
                { runId, requestId, progress ->
                    foregroundInfo.create(runId, requestId, progress)
                },
                recoverForegroundFailure,
            )
            CloudIngestWorker::class.java.name -> CloudIngestWorker(
                appContext,
                workerParameters,
                cloudSyncQueue,
                cloudIngestRunner,
                pumpForegroundInfo?.let {
                    suspend {
                        it.create(
                            PumpForegroundInfo.CLOUD_INGEST_NOTIFICATION_ID,
                            try {
                                cloudNotificationText?.invoke() ?: PumpForegroundInfo.CLOUD_INGEST_TEXT
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                PumpForegroundInfo.CLOUD_INGEST_TEXT
                            },
                        )
                    }
                },
                cloudIngestIsKnownSynced,
                cloudIngestMaxConcurrent,
                repairSeriesIndexes = repairSeriesIndexes,
            )
            DownloadPumpWorker::class.java.name -> {
                var currentDownloadText = PumpForegroundInfo.DOWNLOAD_TEXT
                var firstForegroundTextRead = true
                val resolveDownloadText: suspend () -> String = {
                    try {
                        (downloadNotificationText?.invoke() ?: PumpForegroundInfo.DOWNLOAD_TEXT).also {
                            currentDownloadText = it
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        PumpForegroundInfo.DOWNLOAD_TEXT
                    }
                }
                DownloadPumpWorker(
                    appContext,
                    workerParameters,
                    downloadWorkerLoop,
                    pumpForegroundInfo?.let {
                        suspend {
                            val text = resolveDownloadText()
                            it.create(
                                PumpForegroundInfo.DOWNLOAD_NOTIFICATION_ID,
                                text,
                            )
                        }
                    },
                    foregroundText = {
                        if (firstForegroundTextRead) {
                            firstForegroundTextRead = false
                            currentDownloadText
                        } else {
                            resolveDownloadText()
                        }
                    },
                    checkCfChallenge = checkCfChallenge,
                )
            }
            CollectedSeriesRefreshWorker::class.java.name -> collectedSeriesRefresh?.let { deps ->
                CollectedSeriesRefreshWorker(appContext, workerParameters, deps)
            }
            HydrationWorker::class.java.name -> HydrationWorker(
                appContext,
                workerParameters,
                cloudHydrationEngine,
                pumpForegroundInfo?.let {
                    {
                        it.create(
                            PumpForegroundInfo.HYDRATION_NOTIFICATION_ID,
                            PumpForegroundInfo.HYDRATION_TEXT,
                        )
                    }
                },
            )
            else -> null
        }
}
