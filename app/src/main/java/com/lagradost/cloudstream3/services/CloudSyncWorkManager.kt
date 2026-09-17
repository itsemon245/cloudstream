package com.lagradost.cloudstream3.services

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lagradost.cloudstream3.cloudsync.CloudSyncManager
import com.lagradost.cloudstream3.cloudsync.CloudSyncResult
import com.lagradost.cloudstream3.cloudsync.CloudSyncSettings
import java.util.concurrent.TimeUnit

class CloudSyncWorkManager(
    context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result {
        return when (CloudSyncManager(applicationContext).sync()) {
            is CloudSyncResult.Unavailable -> Result.retry()
            CloudSyncResult.AuthorizationRequired,
            CloudSyncResult.Disabled,
            is CloudSyncResult.Success -> Result.success()
        }
    }

    companion object {
        private const val PERIODIC_WORK_NAME = "cloud_sync_periodic"
        private const val IMMEDIATE_WORK_NAME = "cloud_sync_immediate"
        private const val INTERVAL_MINUTES = 15L

        private val networkConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun ensureScheduled(context: Context?) {
            if (context == null) return
            if (!CloudSyncSettings.hasAnyConfiguredAccount(context)) {
                WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
                return
            }

            val request = PeriodicWorkRequestBuilder<CloudSyncWorkManager>(
                INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(networkConstraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    INTERVAL_MINUTES,
                    TimeUnit.MINUTES,
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun enqueueNow(context: Context?) {
            if (context == null) return
            ensureScheduled(context)
            val request = OneTimeWorkRequestBuilder<CloudSyncWorkManager>()
                .setConstraints(networkConstraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    INTERVAL_MINUTES,
                    TimeUnit.MINUTES,
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }

        fun configurationChanged(context: Context?) {
            if (context == null) return
            if (CloudSyncSettings.hasAnyConfiguredAccount(context)) {
                enqueueNow(context)
            } else {
                WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
                WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK_NAME)
            }
        }
    }
}
