package dev.vcal.calendar.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.vcal.calendar.VCalApp
import dev.vcal.calendar.data.CalendarSyncSchedulerPort
import java.util.concurrent.TimeUnit

class CalendarSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? VCalApp ?: return Result.success()
        app.repository.initialize()
        if (!app.repository.state.value.config.isConfigured) return Result.success()
        return app.repository.syncAll().fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "vcal_periodic_caldav_sync"
    }
}

class WorkManagerCalendarSyncScheduler(
    private val context: Context
) : CalendarSyncSchedulerPort {

    override fun schedulePeriodicSync(intervalMinutes: Int) {
        val wm = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        if (intervalMinutes <= 0) {
            wm.cancelUniqueWork(CalendarSyncWorker.UNIQUE_WORK_NAME)
            return
        }
        val effective = intervalMinutes.coerceAtLeast(15).toLong()
        val request = PeriodicWorkRequestBuilder<CalendarSyncWorker>(effective, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        wm.enqueueUniquePeriodicWork(
            CalendarSyncWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
