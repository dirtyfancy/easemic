package com.easemic.app.work

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.easemic.app.AppGraph
import com.easemic.app.data.room.AuthorizedSourceEntity
import com.easemic.app.model.SourceId
import com.easemic.app.model.WorkRequestId
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

internal object Api35TimeoutProbeContract {
    const val ACTION = "com.easemic.app.debug.API35_TIMEOUT_PROBE"
    const val EXTRA_ARM_TOKEN = "arm-token"
    /** One-shot token required by matrix `am start`; not a security boundary beyond debug builds. */
    const val ARM_TOKEN = "api35-timeout-probe-arm-v1"
    const val PREFS = "api35-timeout-probe"
    const val RUN_ID = "run-id"
    const val REQUEST_ID = "request-id"
}

class Api35TimeoutProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isAuthorizedProbeLaunch()) {
            finish()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                enqueueProbe(this@Api35TimeoutProbeActivity)
            } finally {
                runOnUiThread { finish() }
            }
        }
    }

    private fun isAuthorizedProbeLaunch(): Boolean {
        if (intent.action != Api35TimeoutProbeContract.ACTION) return false
        if (intent.getStringExtra(Api35TimeoutProbeContract.EXTRA_ARM_TOKEN) !=
            Api35TimeoutProbeContract.ARM_TOKEN
        ) {
            return false
        }
        // Matrix uses adb shell am start; reject other installed apps' launches.
        // launchedFromPackage is API 34+; older platforms cannot spoof it here.
        val from = if (android.os.Build.VERSION.SDK_INT >= 34) {
            launchedFromPackage
        } else {
            null
        }
        return from == null || from == "com.android.shell" || from == packageName
    }

    private suspend fun enqueueProbe(context: Context) {
        val graph = AppGraph.get(context)
        val source = SourceId(
            graph.database.sourceDao().insertSource(
                AuthorizedSourceEntity(
                    grantKind = "TREE",
                    adapterKind = "GENERIC_DIRECTORY",
                    contentUri = "content://api35-timeout-probe",
                    providerIdentity = "api35-timeout-probe-${System.nanoTime()}",
                    displayLabel = "Synthetic timeout probe",
                    grantState = "READY",
                    activeGeneration = 0,
                    completedGeneration = null,
                    createdAtMillis = 1,
                    updatedAtMillis = 1,
                ),
            ),
        )
        val run = graph.scanRunRepository.createOrResume(source)
        val uuid = UUID.randomUUID()
        val request = WorkRequestId(uuid.toString())
        check(graph.scanRunRepository.associateWorkRequest(run, null, request))
        // commit=true: probe must persist run/request ids before WorkManager enqueue returns.
        context.getSharedPreferences(Api35TimeoutProbeContract.PREFS, Context.MODE_PRIVATE)
            .edit(commit = true) {
                clear()
                putString(Api35TimeoutProbeContract.RUN_ID, run.value)
                putString(Api35TimeoutProbeContract.REQUEST_ID, uuid.toString())
            }
        WorkManager.getInstance(context).enqueueUniqueWork(
            WorkManagerScanWorkScheduler.workName(run),
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequest.Builder(Api35TimeoutWorker::class.java)
                .setId(uuid)
                .setInputData(workDataOf(WorkManagerScanWorkScheduler.KEY_RUN_ID to run.value))
                .addTag(WorkManagerScanWorkScheduler.COMMON_SCAN_TAG)
                .build(),
        ).result.get()
    }
}
