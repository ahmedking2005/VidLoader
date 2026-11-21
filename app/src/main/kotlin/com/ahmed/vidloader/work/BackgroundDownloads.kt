package com.ahmed.vidloader.work

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.ahmed.vidloader.data.QuickSettings
import com.ahmed.vidloader.data.ScheduledJob
import com.ahmed.vidloader.data.ScheduledJobsRepository
import com.ahmed.vidloader.download.DownloadService
import java.util.concurrent.TimeUnit

/**
 * Submits a download to [DownloadService] from a background context (a
 * WorkManager worker). This is the same intent funnel every quick entry
 * point uses — the service's own queue/persist machinery takes it from
 * there. A foreground-service start is tried first; a plain start is the
 * fallback, and any failure propagates so the caller can retry later
 * instead of silently losing the download.
 */
object DownloadStarter {

    /** Single-video quick download at the user's preset (--no-playlist per item). */
    fun quick(context: Context, url: String) {
        start(context, DownloadService.startQuickIntent(context, url, QuickSettings.spec(context)))
    }

    /** Quick download that lets the service expand a playlist URL in the background. */
    fun quickPlaylist(context: Context, url: String) {
        start(context, DownloadService.startQuickPlaylistIntent(context, url, QuickSettings.spec(context)))
    }

    private fun start(context: Context, intent: Intent) {
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            context.startService(intent)
        }
    }
}

/**
 * All WorkManager registration for v1.10's background features. Unique work
 * names everywhere, so re-registering can never create duplicates:
 * one periodic watch check, and one one-time request per scheduled job.
 * No exact alarms are used — Android may shift the actual run times.
 */
object WorkScheduler {

    const val WATCH_PERIODIC_NAME = "watch_periodic_check"
    private const val SCHEDULED_PREFIX = "scheduled_download_"

    fun scheduledWorkName(jobId: String): String = SCHEDULED_PREFIX + jobId

    /** Enqueues the ~6-hourly watch check once (KEEP: an existing schedule wins). */
    fun ensureWatchPeriodic(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<WatchCheckWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WATCH_PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    /** (Re-)registers the one-time request for [job], delayed until its trigger time. */
    fun enqueueScheduled(context: Context, job: ScheduledJob) {
        val delayMs = (job.triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<ScheduledDownloadWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    ScheduledDownloadWorker.KEY_JOB_ID to job.id,
                    ScheduledDownloadWorker.KEY_URL to job.url
                )
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            scheduledWorkName(job.id),
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancelScheduled(context: Context, jobId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(scheduledWorkName(jobId))
    }

    /**
     * App-start housekeeping: makes sure the periodic watch check exists and
     * every persisted scheduled job still has its one-time request (KEEP, so
     * a live request is never reset; a missing one is recreated with the
     * remaining delay — a job whose time passed while the device was off
     * gets delay 0 and fires at the next opportunity).
     */
    fun registerAll(context: Context) {
        ensureWatchPeriodic(context)
        val workManager = WorkManager.getInstance(context)
        for (job in ScheduledJobsRepository(context).load()) {
            val delayMs = (job.triggerAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<ScheduledDownloadWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(
                        ScheduledDownloadWorker.KEY_JOB_ID to job.id,
                        ScheduledDownloadWorker.KEY_URL to job.url
                    )
                )
                .build()
            workManager.enqueueUniqueWork(
                scheduledWorkName(job.id),
                ExistingWorkPolicy.KEEP,
                request
            )
        }
    }
}
