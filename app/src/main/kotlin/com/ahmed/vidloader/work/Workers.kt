package com.ahmed.vidloader.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ahmed.vidloader.VidLoaderApp
import com.ahmed.vidloader.data.PlaylistFetcher
import com.ahmed.vidloader.data.ScheduledJobsRepository
import com.ahmed.vidloader.data.WatchEntry
import com.ahmed.vidloader.data.WatchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Periodic watch check (v1.10): for every enabled watch, flat-fetch its
 * current entries; anything whose id is not in the watch's seen set is a
 * NEW video and is submitted as a quick download at the user's preset.
 * Failures are silent — the watch is simply retried on the next cycle; the
 * only user signal is DownloadService's own download notifications.
 */
class WatchCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = WatchRepository(applicationContext)
        val watches = repo.load().filter { it.enabled }
        for (watch in watches) {
            try {
                checkWatch(repo, watch)
            } catch (e: Exception) {
                // Network/site hiccup for this one watch: leave its state
                // untouched so the next cycle retries it. Never crash.
                Log.w(TAG, "watch check failed for ${watch.url}", e)
            }
        }
        Result.success()
    }

    private fun checkWatch(repo: WatchRepository, watch: WatchEntry) {
        val app = VidLoaderApp.instance
        app.initEngineBlocking()
        if (!app.engineReady) return
        val entries = PlaylistFetcher.fetchFlatEntries(applicationContext, watch.url) ?: return

        if (!watch.seeded) {
            // First successful fetch for a watch whose add-time seeding
            // failed: record what exists WITHOUT downloading any of it.
            repo.update(
                watch.copy(
                    seenEntryIds = entries.map { it.id }.take(WatchEntry.MAX_SEEN),
                    lastCheckedAt = System.currentTimeMillis(),
                    seeded = true
                )
            )
            return
        }

        val seen = watch.seenEntryIds.toMutableSet()
        for (entry in entries) {
            if (entry.id in seen) continue
            try {
                DownloadStarter.quick(applicationContext, entry.url)
                seen += entry.id
            } catch (e: Exception) {
                // Could not submit now: keep the id unseen so the next
                // cycle tries this video again instead of losing it.
                Log.w(TAG, "could not submit monitored download ${entry.url}", e)
            }
        }
        // Newest first: current entries first, then previously seen ids that
        // fell out of the listing; ids that failed to submit stay OUT of the
        // seen set (they were never added) and are simply absent here.
        val mergedSeen = (entries.map { it.id }.filter { it in seen } + watch.seenEntryIds)
            .distinct()
            .take(WatchEntry.MAX_SEEN)
        repo.update(
            watch.copy(
                seenEntryIds = mergedSeen,
                lastCheckedAt = System.currentTimeMillis()
            )
        )
    }

    companion object {
        private const val TAG = "WatchCheckWorker"
    }
}

/**
 * One scheduled download (v1.10): fires at (or after — WorkManager may run
 * late when the device was off) the user-picked time, submits the URL as a
 * quick download at the user's preset, and removes the persisted record.
 * A record that no longer exists means the user cancelled: do nothing.
 */
class ScheduledDownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return@withContext Result.failure()
        val repo = ScheduledJobsRepository(applicationContext)
        val job = repo.get(jobId) ?: return@withContext Result.success()
        try {
            DownloadStarter.quickPlaylist(applicationContext, job.url)
        } catch (e: Exception) {
            // Keep the record and let WorkManager retry with backoff; the
            // job is only removed once the download was actually submitted.
            Log.w(TAG, "scheduled download submit failed for ${job.url}", e)
            return@withContext Result.retry()
        }
        repo.remove(jobId)
        Result.success()
    }

    companion object {
        private const val TAG = "ScheduledDownloadWorker"
        const val KEY_JOB_ID = "job_id"
        const val KEY_URL = "url"
    }
}
