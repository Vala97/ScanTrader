package com.scantrader.app.vision

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.scantrader.app.ScanTraderApp

/** Periodic catalog check. No notification channel and no user-visible prompt. */
class CatalogUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? ScanTraderApp ?: return Result.failure()
        return if (app.catalogUpdates.stageAll()) Result.success() else Result.retry()
    }

    companion object {
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<CatalogUpdateWorker>(
                SilentCatalogUpdates.INTERVAL_HOURS,
                SilentCatalogUpdates.INTERVAL_UNIT,
            )
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                SilentCatalogUpdates.WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
