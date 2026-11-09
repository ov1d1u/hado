package com.baer.hado.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.baer.hado.data.local.TokenManager
import com.baer.hado.widget.TodoWidgetWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class TodoSyncWorker @AssistedInject constructor(
    @Assisted private val appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val tokenManager: TokenManager
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        if (!tokenManager.isLoggedIn || tokenManager.isDemoMode) {
            return Result.success()
        }

        val targetAccountId = inputData.getString(KEY_ACCOUNT_ID)
        val accountsToSync = if (targetAccountId != null) {
            listOf(targetAccountId)
        } else {
            tokenManager.accountIds
        }

        var allDrained = true
        for (accountId in accountsToSync) {
            val drained = TodoSyncManager.drainQueue(appContext, accountId)
            if (!drained) {
                allDrained = false
            }
        }

        if (allDrained) {
            TodoWidgetWorker.enqueueOneTime(appContext)
            return Result.success()
        }
        return Result.retry()
    }

    companion object {
        private const val WORK_NAME_PREFIX = "todo_pending_sync_"
        private const val KEY_ACCOUNT_ID = "account_id"

        fun enqueue(context: Context, accountId: String?) {
            val workName = WORK_NAME_PREFIX + (accountId ?: TokenManager.PRIMARY_ACCOUNT_ID)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<TodoSyncWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_ACCOUNT_ID to accountId))
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
}
