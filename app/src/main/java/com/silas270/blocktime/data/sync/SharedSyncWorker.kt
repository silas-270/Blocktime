package com.silas270.blocktime.data.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.silas270.blocktime.data.repository.SyncReason
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * The background half of the interval sync (docs/shared-challenges.md "Sync moments"): every 15
 * minutes, WorkManager's minimum, and only with a connection, it posts the pilot's progress and
 * fetches the crew's through the process's one syncer, so a crew sees a pilot's landings and the
 * pilot comes back to fresh rooms even when the app was not opened in between.
 *
 * Scheduled while sharing is on and the build has a server, cancelled when either is not (see
 * [SharedSyncGraph]). A run with no shared row and no pending leave does not even probe the
 * server. A run that fails is not retried early: the next period is soon enough, and a dead
 * server should not be asked again and again.
 */
class SharedSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val graph = SharedSyncGraph.get(applicationContext)
        try {
            // A worker can be the first thing to run after an update, before any Activity has
            // refreshed the airport database a route challenge's progress is read against.
            graph.airportRepository.ensureDatabaseCopied()
            if (graph.syncer.hasAnythingToSync()) {
                graph.syncer.syncNow(SyncReason.PERIODIC)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Background sync failed", e)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "SharedSyncWorker"
        private const val WORK_NAME = "shared-challenge-sync"
        private const val INTERVAL_MINUTES = 15L

        /** Schedules the periodic sync, or cancels it. Scheduling again keeps the existing
         *  period, so calling this on every start does not push the next run back. */
        fun schedule(context: Context, enabled: Boolean) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<SharedSyncWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
