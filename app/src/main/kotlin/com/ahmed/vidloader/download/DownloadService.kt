package com.ahmed.vidloader.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.ahmed.vidloader.MainActivity
import com.ahmed.vidloader.R
import com.ahmed.vidloader.VidLoaderApp
import com.ahmed.vidloader.data.DownloadSettings
import com.ahmed.vidloader.data.FailedJob
import com.ahmed.vidloader.data.FailedJobsRepository
import com.ahmed.vidloader.data.LibraryItem
import com.ahmed.vidloader.data.LibraryRepository
import com.ahmed.vidloader.data.LinkHistoryEntry
import com.ahmed.vidloader.data.LinkHistoryRepository
import com.ahmed.vidloader.data.PausedJob
import com.ahmed.vidloader.data.PausedJobItem
import com.ahmed.vidloader.data.PausedJobsRepository
import com.ahmed.vidloader.data.PlaylistFetcher
import com.ahmed.vidloader.data.QueueJob
import com.ahmed.vidloader.data.QueueJobsRepository
import com.ahmed.vidloader.data.Staging
import com.ahmed.vidloader.util.FormatUtils
import com.ahmed.vidloader.util.PageVideo
import com.ahmed.vidloader.util.friendlyError
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

enum class DownloadKind { VIDEO, AUDIO_M4A, AUDIO_MP3, DIRECT }

data class DownloadSpec(
    val kind: DownloadKind,
    val selector: String? = null,
    val label: String = "",
    /** Also fetch subtitles (ar, then en) as .srt sidecars. Video downloads only. */
    val subtitles: Boolean = false,
    /** Clip cut: keep only [sectionStartSec, sectionEndSec) of the video. Null start = from 0. */
    val sectionStartSec: Long? = null,
    /** Clip cut end in seconds; null = to the end of the video. */
    val sectionEndSec: Long? = null
)

class DownloadService : Service() {

    /**
     * One downloadable unit. [playlistIndex] != null means: download entry #[playlistIndex] (1-based)
     * of the playlist at [url]. [slot] is the item's stable position inside its job and keys its
     * staging directory — it never changes across pause/resume, which is what lets yt-dlp continue
     * from the partial files of the previous attempt.
     */
    private data class WorkItem(
        val url: String,
        /** Mutable: a quick item starts with a placeholder and learns its real title from the download. */
        var title: String,
        val kind: DownloadKind,
        val selector: String?,
        val subtitles: Boolean = false,
        val playlistIndex: Int? = null,
        val slot: Int = 0,
        /** Quick-download item: no info was fetched up front, so the title is learned from the .info.json the download itself writes. */
        val quick: Boolean = false,
        /** Clip cut (seconds from the start), or null for a full download. Rides through pause/resume/queue persistence. */
        val sectionStartSec: Long? = null,
        /** Clip cut end (seconds), or null = to the end of the video. */
        val sectionEndSec: Long? = null
    )

    /** A group of items started together (a single video = a job of one item; a playlist selection = one batch job). */
    private data class WorkJob(
        /** Stable id; also the name of the job's staging directory. Survives pause/resume. */
        val id: String,
        val label: String,
        val isBatch: Boolean,
        val items: List<WorkItem>,
        /**
         * How many items of the original job sit before [items] (nonzero only for a
         * resumed job, whose [items] are the not-yet-finished tail). Keeps the
         * displayed "k من N" position honest across pause/resume cycles.
         */
        val positionOffset: Int = 0,
        /**
         * Quick playlist only: the entries are not known yet. When both are set,
         * [items] is empty and the worker expands [expandUrl] with
         * [expandSpec] in the background before the first item runs; if the URL
         * turns out not to be a playlist, it falls back to one quick item.
         */
        val expandUrl: String? = null,
        val expandSpec: DownloadSpec? = null
    )

    private enum class ItemOutcome { SUCCESS, FAILED, CANCELLED, PAUSED }

    /** Per-job stop flags. Pause/cancel now act on ONE job, never on a global flag. */
    private class JobControl {
        @Volatile var cancelRequested = false
        @Volatile var pauseRequested = false
    }

    /**
     * Runtime state of one job being executed by a worker thread. Every field
     * is per-job: two running jobs never share a process id, a staging tree,
     * a notification id or a progress record.
     */
    private inner class JobRuntime(
        var job: WorkJob,
        val seq: Long
    ) {
        val control = JobControl()

        @Volatile var activeStatus = DownloadCenter.ActiveStatus.QUEUED
        @Volatile var currentItem: WorkItem? = null
        @Volatile var currentIndex = 0
        @Volatile var progress = -1f
        @Volatile var etaSeconds = -1L
        @Volatile var speed = ""
        @Volatile var processing = false
        @Volatile var processId: String? = null
        @Volatile var lastItemId: String? = null
        var succeeded = 0
        var failed = 0

        /**
         * Index of the first not-yet-finished item of [job]. The persisted
         * queue record stores items from here on, so a job interrupted by
         * process death is restored with exactly its remaining tail.
         */
        @Volatile var remainingFromIndex = 0

        /** Items that failed after all retries, with their final error — feeds the failed-jobs record. */
        val failedItems = mutableListOf<Pair<WorkItem, String?>>()

        @Volatile var lastError: String? = null
    }

    private enum class NetKind { WIFI_LIKE, CELLULAR, NONE }

    private val workerPool = Executors.newCachedThreadPool()
    private val coordinatorExecutor = Executors.newSingleThreadExecutor()

    /** Jobs waiting for a worker slot (or for a network that may start them). Guarded by [queueLock]. */
    private val pendingJobs = ArrayDeque<WorkJob>()

    /** Jobs currently executed by a worker, by job id. Guarded by [queueLock]. */
    private val runtimes = LinkedHashMap<String, JobRuntime>()

    /** Ids of pending jobs currently blocked by the mobile-data policy. Guarded by [queueLock]. */
    private val waitingWifiIds = LinkedHashSet<String>()

    private val queueLock: Object = Object()

    @Volatile
    private var coordinatorRunning = false

    @Volatile
    private var foregroundStarted = false

    private var seqCounter = 0L

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Publish the persisted paused + failed jobs, restore the persisted
        // queue (jobs that still owe work — pending, Wi-Fi-waiting, or
        // interrupted mid-run by process death), and prune staging
        // directories that belong to no surviving job.
        val pausedRepo = PausedJobsRepository(this)
        val paused = pausedRepo.load()
        DownloadCenter.pausedJobs.value = paused
        DownloadCenter.failedJobs.value = FailedJobsRepository(this).load()

        val restored = restoreQueue(paused.map { it.id }.toSet())
        val keep = paused.map { it.id }.toSet() + restored
        Staging.root(this).listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name !in keep) dir.deleteRecursively()
        }
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelAll()
                if (!hasWork()) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                pauseAll()
                if (!hasWork()) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE_JOB -> {
                intent.getStringExtra(EXTRA_JOB_ID)?.let { pauseJob(it) }
                if (!hasWork()) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CANCEL_JOB -> {
                intent.getStringExtra(EXTRA_JOB_ID)?.let { cancelJob(it) }
                if (!hasWork()) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                val job = intent.getStringExtra(EXTRA_JOB_ID)?.let { jobId ->
                    PausedJobsRepository(this).getById(jobId)?.toWorkJob()
                }
                if (job != null) {
                    submitJob(job)
                } else if (!hasWork()) {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_DISCARD -> {
                intent.getStringExtra(EXTRA_JOB_ID)?.let { discardPaused(it) }
                if (!hasWork()) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RETRY_FAILED -> {
                // Re-enqueue a failed record's items as a FRESH job (new id,
                // fresh staging); the record itself is consumed.
                val record = intent.getStringExtra(EXTRA_JOB_ID)?.let { recordId ->
                    val repo = FailedJobsRepository(this)
                    repo.getById(recordId)?.also {
                        repo.remove(recordId)
                        DownloadCenter.failedJobs.value = repo.load()
                    }
                }
                if (record != null) {
                    submitJob(record.toWorkJob())
                } else if (!hasWork()) {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }

        val job = intent?.let { parseJob(it) }
        if (job == null) {
            if (!hasWork()) stopSelf()
            return START_NOT_STICKY
        }
        submitJob(job)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let { cb ->
            try {
                getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                // Already unregistered — nothing to clean up.
            }
        }
        val pids = synchronized(queueLock) { runtimes.values.mapNotNull { it.processId } }
        pids.forEach { destroyProcess(it) }
        workerPool.shutdownNow()
        coordinatorExecutor.shutdownNow()
        super.onDestroy()
    }

    // region queue / coordinator

    private fun hasWork(): Boolean = synchronized(queueLock) {
        pendingJobs.isNotEmpty() || runtimes.isNotEmpty() || coordinatorRunning
    }

    /** Enqueues [job] and makes sure the coordinator (and the foreground notification) are running. */
    private fun submitJob(job: WorkJob) {
        // Link history (v1.9): every flow funnels through here — InfoActivity,
        // quick download, playlists, the share trampoline, the in-app browser —
        // so recording at submission covers them all. De-duped by URL inside.
        val history = LinkHistoryRepository(this)
        if (job.expandUrl != null) {
            history.record(job.expandUrl, job.label, LinkHistoryEntry.STATUS_STARTED)
        }
        for (item in job.items) {
            history.record(item.url, item.title, LinkHistoryEntry.STATUS_STARTED)
        }
        val startCoordinator = synchronized(queueLock) {
            pendingJobs.addLast(job)
            if (coordinatorRunning) {
                queueLock.notifyAll()
                false
            } else {
                coordinatorRunning = true
                true
            }
        }
        val busy = synchronized(queueLock) { runtimes.isNotEmpty() || pendingJobs.size > 1 }
        persistQueue()
        publishActiveItems()
        ensureForeground(job)
        if (startCoordinator) {
            coordinatorExecutor.execute { coordinatorLoop() }
        } else if (busy) {
            Toast.makeText(this, R.string.download_queued, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Loads the persisted queue back into [pendingJobs] (process-death
     * restore, called once from onCreate) and starts the coordinator when
     * anything was restored. Records whose id also exists in the paused
     * store are skipped — the paused record wins, so a job paused in the
     * same instant the process died is restored once, as PAUSED. Returns
     * the restored job ids (their staging directories must survive the
     * onCreate prune).
     */
    private fun restoreQueue(pausedIds: Set<String>): Set<String> {
        val records = QueueJobsRepository(this).load()
            .filter { it.id !in pausedIds }
        if (records.isEmpty()) return emptySet()
        val jobs = records.map { it.toWorkJob() }
        val startCoordinator = synchronized(queueLock) {
            jobs.forEach { pendingJobs.addLast(it) }
            if (coordinatorRunning) {
                queueLock.notifyAll()
                false
            } else {
                coordinatorRunning = true
                true
            }
        }
        // Rewrite the store in its deduped, normalized form.
        persistQueue()
        publishActiveItems()
        jobs.firstOrNull()?.let { ensureForeground(it) }
        if (startCoordinator) {
            coordinatorExecutor.execute { coordinatorLoop() }
        }
        return jobs.map { it.id }.toSet()
    }

    /**
     * Rewrites queue_jobs.json from the service's live state: every pending
     * job in order, plus every running job with only its not-yet-finished
     * items (from [JobRuntime.remainingFromIndex] — an unexpanded quick
     * playlist keeps its expansion target instead). Called on EVERY queue
     * mutation, so the disk always mirrors the work still owed; a job that
     * finished, was cancelled, or moved to the paused store simply drops
     * out of the snapshot and can never be resurrected.
     */
    private fun persistQueue() {
        val snapshot = synchronized(queueLock) {
            val records = mutableListOf<QueueJob>()
            runtimes.values.sortedBy { it.seq }.forEach { rt ->
                // A job being cancelled is already gone from the user's
                // point of view: never write it back to disk, not even in
                // the window before its worker notices the flag.
                if (rt.control.cancelRequested) return@forEach
                val job = rt.job
                records += if (job.expandUrl != null) {
                    QueueJob(
                        id = job.id,
                        label = job.label,
                        isBatch = job.isBatch,
                        items = emptyList(),
                        positionOffset = job.positionOffset,
                        expandUrl = job.expandUrl,
                        expandKind = job.expandSpec?.kind?.name,
                        expandSelector = job.expandSpec?.selector
                    )
                } else {
                    QueueJob(
                        id = job.id,
                        label = job.label,
                        isBatch = job.isBatch,
                        items = job.items.drop(rt.remainingFromIndex).map { it.toPersistedItem() },
                        positionOffset = job.positionOffset + rt.remainingFromIndex
                    )
                }
            }
            pendingJobs.forEach { job ->
                records += QueueJob(
                    id = job.id,
                    label = job.label,
                    isBatch = job.isBatch,
                    items = job.items.map { it.toPersistedItem() },
                    positionOffset = job.positionOffset,
                    expandUrl = job.expandUrl,
                    expandKind = job.expandSpec?.kind?.name,
                    expandSelector = job.expandSpec?.selector
                )
            }
            records
        }
        try {
            QueueJobsRepository(this).replaceAll(snapshot)
        } catch (e: Exception) {
            // Persistence must never break a download; the in-memory queue
            // stays authoritative for this process lifetime.
            Log.w(TAG, "queue persist failed", e)
        }
    }

    /**
     * The coordinator owns job STARTS. It runs while any job is pending or
     * running and, on every wake (job submitted, job finished, network
     * changed, or a 1s tick that re-reads the settings), starts queued jobs
     * until the parallel limit for the current network is reached. With the
     * limit at 1 this is exactly the v1.5 sequential flow.
     */
    private fun coordinatorLoop() {
        try {
            while (true) {
                val toStart = mutableListOf<JobRuntime>()
                val exit = synchronized(queueLock) {
                    // Recompute the policy-wait set for this pass.
                    waitingWifiIds.clear()
                    if (startBlockedByPolicy()) {
                        pendingJobs.forEach { waitingWifiIds += it.id }
                    }
                    while (runtimes.size < startLimit()) {
                        val job = pendingJobs.firstOrNull() ?: break
                        if (startBlockedByPolicy()) break
                        pendingJobs.removeFirst()
                        val rt = JobRuntime(job, ++seqCounter)
                        runtimes[job.id] = rt
                        toStart += rt
                    }
                    val done = pendingJobs.isEmpty() && runtimes.isEmpty()
                    if (done) coordinatorRunning = false
                    done
                }
                if (toStart.isNotEmpty()) persistQueue()
                toStart.forEach { rt -> workerPool.execute { runJob(rt) } }
                publishActiveItems()
                refreshJobNotifications()
                if (exit) break
                synchronized(queueLock) {
                    if (coordinatorRunning) queueLock.wait(1000)
                }
            }
        } finally {
            // A submit racing this exit flips coordinatorRunning back to true
            // atomically with its enqueue (same lock), so finding it set here
            // means a fresh loop owns the service now — do not stop anything.
            val shouldStop = synchronized(queueLock) {
                if (coordinatorRunning) {
                    false
                } else {
                    pendingJobs.isEmpty() && runtimes.isEmpty()
                }
            }
            if (shouldStop) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
                stopSelf()
            }
        }
    }

    /** Wakes the coordinator (network changes must re-evaluate limits and the Wi-Fi wait). */
    private fun wakeCoordinator() {
        synchronized(queueLock) { queueLock.notifyAll() }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = wakeCoordinator()
            override fun onLost(network: Network) = wakeCoordinator()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                wakeCoordinator()
        }
        networkCallback = callback
        try {
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "network callback registration failed", e)
        }
    }

    private fun netKind(): NetKind {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return NetKind.NONE
        val caps = try {
            cm.getNetworkCapabilities(cm.activeNetwork)
        } catch (e: Exception) {
            null
        } ?: return NetKind.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetKind.WIFI_LIKE
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetKind.CELLULAR
            else -> NetKind.NONE
        }
    }

    /** How many jobs may run at once on the current network (0 = none may start). */
    private fun startLimit(): Int = when (netKind()) {
        NetKind.WIFI_LIKE -> DownloadSettings.wifiLimit(this)
        NetKind.CELLULAR -> DownloadSettings.mobileLimit(this)
        NetKind.NONE -> 0
    }

    /** True when the mobile-data switch forbids STARTING jobs on the current network. */
    private fun startBlockedByPolicy(): Boolean =
        netKind() == NetKind.CELLULAR && !DownloadSettings.mobileDataAllowed(this)

    // endregion

    // region pause / cancel

    /** ⏸ Pause ALL running jobs (the legacy global action: InfoActivity card + main notification). */
    private fun pauseAll() {
        val pids = synchronized(queueLock) {
            runtimes.values.forEach { it.control.pauseRequested = true }
            runtimes.values.mapNotNull { it.processId }
        }
        pids.forEach { destroyProcess(it) }
    }

    /** ⏸ Pause one job. Only running jobs can pause; a queued job simply has nothing to pause yet. */
    private fun pauseJob(jobId: String) {
        val rt = synchronized(queueLock) { runtimes[jobId] } ?: return
        rt.control.pauseRequested = true
        rt.processId?.let { destroyProcess(it) }
    }

    /** ✕ Cancel EVERYTHING (the legacy global action): clears the queue and stops every running job. */
    private fun cancelAll() {
        val pids = synchronized(queueLock) {
            pendingJobs.clear()
            waitingWifiIds.clear()
            runtimes.values.forEach { it.control.cancelRequested = true }
            val ids = runtimes.values.mapNotNull { it.processId }
            queueLock.notifyAll()
            ids
        }
        pids.forEach { destroyProcess(it) }
        // Queued jobs vanish from the persisted queue at once; running jobs
        // drop out when their workers finish (runJob's final snapshot).
        persistQueue()
        publishActiveItems()
    }

    /** ✕ Cancel one job: removed from the queue if waiting, stopped if running. Other jobs are untouched. */
    private fun cancelJob(jobId: String) {
        var pid: String? = null
        synchronized(queueLock) {
            pendingJobs.removeIf { it.id == jobId }
            waitingWifiIds.remove(jobId)
            val rt = runtimes[jobId]
            if (rt != null) {
                rt.control.cancelRequested = true
                pid = rt.processId
            }
            queueLock.notifyAll()
        }
        pid?.let { destroyProcess(it) }
        // A queued job's persisted record is removed here and now, so a
        // cancel can never be resurrected by a later process-death restore;
        // a running job drops out via runJob's final snapshot.
        persistQueue()
        val manager = getSystemService(NotificationManager::class.java)
        manager.cancel(jobProgressId(jobId))
        publishActiveItems()
    }

    private fun destroyProcess(pid: String) {
        Thread {
            runCatching { YoutubeDL.getInstance().destroyProcessById(pid) }
        }.start()
    }

    /** Deletes a paused job's record, its partial files and its notification. */
    private fun discardPaused(jobId: String) {
        val repo = PausedJobsRepository(this)
        repo.remove(jobId)
        Staging.deleteJob(this, jobId)
        cancelPausedNotification(jobId)
        DownloadCenter.pausedJobs.value = repo.load()
    }

    // endregion

    private fun PausedJobItem.toWorkItem(): WorkItem = WorkItem(
        url = url,
        title = title,
        kind = runCatching { DownloadKind.valueOf(kind) }.getOrDefault(DownloadKind.VIDEO),
        selector = selector,
        subtitles = subtitles,
        playlistIndex = playlistIndex,
        slot = slot,
        quick = quick,
        sectionStartSec = sectionStartSec,
        sectionEndSec = sectionEndSec
    )

    private fun WorkItem.toPersistedItem(): PausedJobItem = PausedJobItem(
        url = url,
        title = title,
        kind = kind.name,
        selector = selector,
        subtitles = subtitles,
        playlistIndex = playlistIndex,
        slot = slot,
        quick = quick,
        sectionStartSec = sectionStartSec,
        sectionEndSec = sectionEndSec
    )

    private fun PausedJob.toWorkJob(): WorkJob = WorkJob(
        id = id,
        label = label,
        isBatch = isBatch,
        positionOffset = (queuePosition - 1).coerceAtLeast(0),
        items = items.map { it.toWorkItem() }
    )

    private fun QueueJob.toWorkJob(): WorkJob = WorkJob(
        id = id,
        label = label,
        isBatch = isBatch,
        positionOffset = positionOffset,
        items = items.map { it.toWorkItem() },
        expandUrl = expandUrl,
        expandSpec = expandUrl?.let {
            DownloadSpec(
                kind = runCatching { DownloadKind.valueOf(expandKind ?: "VIDEO") }
                    .getOrDefault(DownloadKind.VIDEO),
                selector = expandSelector
            )
        }
    )

    /**
     * A failed record becomes a FRESH job: new id (hence fresh staging —
     * the original partials were deleted with each final failure) and the
     * failed items re-slotted from 0, exactly like a newly built batch.
     */
    private fun FailedJob.toWorkJob(): WorkJob {
        val newItems = items.mapIndexed { i, item -> item.copy(slot = i).toWorkItem() }
        return WorkJob(
            id = UUID.randomUUID().toString(),
            label = label,
            isBatch = isBatch || newItems.size > 1,
            items = newItems
        )
    }

    private fun parseJob(intent: Intent): WorkJob? {
        val kind = intent.getStringExtra(EXTRA_KIND)
            ?.let { runCatching { DownloadKind.valueOf(it) }.getOrNull() }
            ?: DownloadKind.VIDEO
        val selector = intent.getStringExtra(EXTRA_SELECTOR)
        val subtitles = intent.getBooleanExtra(EXTRA_SUBTITLES, false)
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: UUID.randomUUID().toString()

        // Quick playlist: entries unknown — the worker expands the URL in
        // the background (or falls back to a single quick download).
        val quickPlaylistUrl = intent.getStringExtra(EXTRA_QUICK_PLAYLIST_URL)
        if (quickPlaylistUrl != null) {
            return WorkJob(
                id = jobId,
                label = placeholderTitle(quickPlaylistUrl),
                isBatch = true,
                items = emptyList(),
                expandUrl = quickPlaylistUrl,
                expandSpec = DownloadSpec(kind, selector)
            )
        }

        val indices = intent.getIntArrayExtra(EXTRA_INDICES)
        val playlistUrl = intent.getStringExtra(EXTRA_PLAYLIST_URL)
        if (indices != null && playlistUrl != null) {
            val titles = intent.getStringArrayExtra(EXTRA_TITLES) ?: emptyArray()
            val items = indices.mapIndexed { i, index ->
                val rawTitle = titles.getOrNull(i).orEmpty()
                WorkItem(
                    url = playlistUrl,
                    title = rawTitle.ifBlank { getString(R.string.playlist_item_fallback, index) },
                    kind = kind,
                    selector = selector,
                    subtitles = subtitles,
                    playlistIndex = index,
                    slot = i
                )
            }
            if (items.isEmpty()) return null
            return WorkJob(
                id = jobId,
                label = intent.getStringExtra(EXTRA_PLAYLIST_TITLE).orEmpty(),
                isBatch = true,
                items = items
            )
        }

        val url = intent.getStringExtra(EXTRA_URL) ?: return null
        val quick = intent.getBooleanExtra(EXTRA_QUICK, false)
        val sectionStart = intent.getLongExtra(EXTRA_SECTION_START, -1L).takeIf { it >= 0 }
        val sectionEnd = intent.getLongExtra(EXTRA_SECTION_END, -1L).takeIf { it >= 0 }
        val rawTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val title = if (quick && rawTitle.isBlank()) placeholderTitle(url) else rawTitle
        return WorkJob(
            id = jobId,
            label = title,
            isBatch = false,
            items = listOf(
                WorkItem(
                    url = url, title = title, kind = kind, selector = selector,
                    subtitles = subtitles, slot = 0, quick = quick,
                    sectionStartSec = sectionStart, sectionEndSec = sectionEnd
                )
            )
        )
    }

    /** Display placeholder for a download whose real title is not known yet: the URL's host. */
    private fun placeholderTitle(url: String): String =
        runCatching { java.net.URI(url).host }
            .getOrNull()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
            ?: getString(R.string.shared_video)

    // region job execution (worker threads)

    /**
     * Executes ONE job end to end on a worker thread: optional quick-playlist
     * expansion, then the item loop. A failed item is skipped and counted;
     * pause persists the remaining tail; cancel drops the job. Everything the
     * job touches — control flags, process id, staging, notifications — is
     * its own, so parallel jobs cannot interfere.
     */
    private fun runJob(rt: JobRuntime) {
        var job = rt.job
        try {
            // A resumed job stops being "paused" the moment it actually starts:
            // drop its record + notification (a later re-pause writes a fresh record).
            val pausedRepo = PausedJobsRepository(this)
            if (pausedRepo.getById(job.id) != null) {
                pausedRepo.remove(job.id)
                cancelPausedNotification(job.id)
                DownloadCenter.pausedJobs.value = pausedRepo.load()
            }
            publishActiveItems()

            // Quick playlist: the entries are not known yet — expand the
            // URL in the background now. A pause/cancel pressed meanwhile
            // is honoured by the item loop's checks below, and a pause
            // persists the expanded items (or the single fallback item).
            if (job.expandUrl != null) {
                rt.activeStatus = DownloadCenter.ActiveStatus.FETCHING
                publishActiveItems()
                notifyJobProgress(rt)
                job = expandQuickPlaylist(job)
                rt.job = job
                // The persisted record held the expansion target; now that
                // the real items exist, persist them so a death from here
                // on restores the expanded job, not the expansion.
                rt.remainingFromIndex = 0
                persistQueue()
            }

            val total = job.items.size
            var pausedAt = -1
            var pausedProgress = 0
            var cancelled = false

            for ((i, item) in job.items.withIndex()) {
                if (rt.control.cancelRequested) {
                    cancelled = true
                    break
                }
                if (rt.control.pauseRequested) {
                    // Paused while between items (e.g. during the previous item's publish step).
                    pausedAt = i
                    pausedProgress = 0
                    break
                }
                rt.currentItem = item
                rt.currentIndex = i
                rt.remainingFromIndex = i
                rt.progress = -1f
                rt.etaSeconds = -1
                rt.speed = ""
                rt.processing = false
                rt.activeStatus = DownloadCenter.ActiveStatus.RUNNING
                val processId = "dl_" + job.id + "_" + item.slot
                rt.processId = processId
                publishActiveItems()
                notifyJobProgress(rt)

                when (downloadWithRetries(rt, item)) {
                    ItemOutcome.SUCCESS -> rt.succeeded++
                    ItemOutcome.FAILED -> {
                        rt.failed++
                        rt.failedItems += item to rt.lastError
                    }
                    ItemOutcome.CANCELLED -> cancelled = true
                    ItemOutcome.PAUSED -> {
                        pausedAt = i
                        pausedProgress = rt.progress.toInt()
                    }
                }
                rt.processId = null
                if (cancelled || pausedAt >= 0) break
                // The item is finished either way (downloaded or given up
                // on): shrink the persisted remaining tail right now, so a
                // process death after this point never re-runs it.
                rt.remainingFromIndex = i + 1
                persistQueue()
            }

            when {
                pausedAt >= 0 -> {
                    persistPaused(job, pausedAt, pausedProgress, rt.failed)
                    if (isPrimary(rt)) {
                        DownloadCenter.state.value = DownloadCenter.State(
                            status = DownloadCenter.Status.PAUSED,
                            jobId = job.id,
                            title = job.items.getOrNull(pausedAt)?.title ?: job.label,
                            queuePosition = job.positionOffset + pausedAt + 1,
                            queueTotal = job.positionOffset + total,
                            failedCount = rt.failed
                        )
                    }
                    rt.control.pauseRequested = false
                }
                cancelled || rt.control.cancelRequested -> {
                    // The current item's staging was already deleted by
                    // downloadOne; drop any remaining partials of this job too.
                    Staging.deleteJob(this, job.id)
                    if (isPrimary(rt)) {
                        DownloadCenter.state.value = DownloadCenter.state.value.copy(
                            status = DownloadCenter.Status.CANCELLED,
                            jobId = job.id
                        )
                    }
                }
                rt.succeeded > 0 -> {
                    if (isPrimary(rt)) {
                        DownloadCenter.state.value = DownloadCenter.state.value.copy(
                            status = DownloadCenter.Status.DONE,
                            jobId = job.id,
                            progress = 100f,
                            failedCount = rt.failed
                        )
                    }
                    // Partial success still owes the user its failed items.
                    if (rt.failed > 0) recordFailedJob(job.withCurrentLabel(), rt)
                    notifyJobFinished(job.withCurrentLabel(), rt.succeeded, rt.failed, job.id)
                }
                else -> {
                    if (isPrimary(rt)) {
                        DownloadCenter.state.value = DownloadCenter.state.value.copy(
                            status = DownloadCenter.Status.ERROR,
                            jobId = job.id,
                            error = rt.lastError,
                            failedCount = rt.failed
                        )
                    }
                    recordFailedJob(job.withCurrentLabel(), rt)
                    notifyJobFailed(job.withCurrentLabel(), job.id)
                }
            }
        } finally {
            synchronized(queueLock) {
                runtimes.remove(job.id)
                queueLock.notifyAll()
            }
            // The job is over (done / failed / paused / cancelled): it drops
            // out of the persisted queue. A paused job lives on in the
            // paused store instead — never in both.
            persistQueue()
            val manager = getSystemService(NotificationManager::class.java)
            manager.cancel(jobProgressId(job.id))
            publishActiveItems()
            refreshJobNotifications()
        }
    }

    /**
     * Persists a failed-jobs record holding exactly [rt]'s failed items
     * (post-retry), so the user can re-run just those from the library.
     * Items that succeeded on any attempt are not in [JobRuntime.failedItems]
     * and never land here.
     */
    private fun recordFailedJob(job: WorkJob, rt: JobRuntime) {
        if (rt.failedItems.isEmpty()) return
        val history = LinkHistoryRepository(this)
        for ((failedItem, _) in rt.failedItems) {
            history.updateStatus(failedItem.url, LinkHistoryEntry.STATUS_FAILED, failedItem.title)
        }
        val repo = FailedJobsRepository(this)
        repo.add(
            FailedJob(
                id = UUID.randomUUID().toString(),
                label = job.label,
                isBatch = job.isBatch,
                items = rt.failedItems.map { (item, _) -> item.toPersistedItem() },
                lastError = rt.failedItems.lastOrNull()?.second ?: rt.lastError,
                failedAtMillis = System.currentTimeMillis()
            )
        )
        DownloadCenter.failedJobs.value = repo.load()
    }

    /**
     * Runs one item with automatic retries: up to [MAX_ITEM_ATTEMPTS]
     * attempts, waiting ~10s then ~30s between them. Every attempt is a
     * FRESH [downloadOne] call — a brand-new YoutubeDLRequest executed from
     * scratch — so the stream URLs are re-extracted on each attempt; for
     * extractor downloads that re-extraction IS the expired-link refresh.
     * (DIRECT items have no extractor: their retries reuse the same URL,
     * and a final 403/410 surfaces as «انتهى الرابط».) A pause/cancel
     * requested during the wait aborts the retry loop immediately and the
     * item ends PAUSED/CANCELLED, never FAILED.
     */
    private fun downloadWithRetries(rt: JobRuntime, item: WorkItem): ItemOutcome {
        var attempt = 0
        while (true) {
            val outcome = downloadOne(rt, item, keepStagingOnFailure = attempt < MAX_ITEM_ATTEMPTS - 1)
            if (outcome != ItemOutcome.FAILED) return outcome
            if (attempt >= MAX_ITEM_ATTEMPTS - 1) return ItemOutcome.FAILED
            val delayMs = if (attempt == 0) RETRY_DELAY_FIRST_MS else RETRY_DELAY_SECOND_MS
            attempt++
            when (waitRetryDelay(rt, delayMs)) {
                ItemOutcome.PAUSED -> return ItemOutcome.PAUSED
                ItemOutcome.CANCELLED -> return ItemOutcome.CANCELLED
                else -> Unit // delay elapsed — fall through to the next attempt
            }
            // Reset the per-attempt display state so the new attempt starts clean.
            rt.progress = -1f
            rt.etaSeconds = -1
            rt.speed = ""
            rt.processing = false
            publishActiveItems()
            notifyJobProgress(rt)
        }
    }

    /**
     * Interruptible retry wait: sleeps in short slices so a pause/cancel
     * lands within ~250ms instead of after the full delay. Returns the
     * PAUSED/CANCELLED outcome to propagate, or SUCCESS when the delay
     * simply elapsed (SUCCESS here means "proceed", not a finished item).
     */
    private fun waitRetryDelay(rt: JobRuntime, delayMs: Long): ItemOutcome {
        var waited = 0L
        while (waited < delayMs) {
            if (rt.control.cancelRequested) return ItemOutcome.CANCELLED
            if (rt.control.pauseRequested) return ItemOutcome.PAUSED
            val slice = minOf(RETRY_POLL_SLICE_MS, delayMs - waited)
            try {
                Thread.sleep(slice)
            } catch (e: InterruptedException) {
                // Treated as a wake-up: the flag checks above decide what happens.
            }
            waited += slice
        }
        return if (rt.control.cancelRequested) {
            ItemOutcome.CANCELLED
        } else if (rt.control.pauseRequested) {
            ItemOutcome.PAUSED
        } else {
            ItemOutcome.SUCCESS
        }
    }

    /**
     * Expands a quick playlist job in the background (runs on the job's
     * worker thread): fetches the playlist's entries and turns them into
     * ordinary batch items at the job's preset. If the URL is not a
     * playlist — or the fetch fails — the job falls back to a single quick
     * download of the URL itself. Pause/cancel flags are deliberately NOT
     * consumed here: the item loop's checks right after this call act on them.
     */
    private fun expandQuickPlaylist(job: WorkJob): WorkJob {
        val url = job.expandUrl ?: return job
        val spec = job.expandSpec ?: DownloadSpec(DownloadKind.VIDEO)

        val app = VidLoaderApp.instance
        app.initEngineBlocking()
        val info = if (app.engineReady) {
            try {
                PlaylistFetcher.fetch(this, url)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        return if (info != null) {
            job.copy(
                label = info.title.ifBlank { job.label },
                isBatch = true,
                items = info.entries.mapIndexed { i, entry ->
                    WorkItem(
                        url = url,
                        title = entry.title,
                        kind = spec.kind,
                        selector = spec.selector,
                        playlistIndex = entry.index,
                        slot = i
                    )
                },
                expandUrl = null,
                expandSpec = null
            )
        } else {
            job.copy(
                isBatch = false,
                items = listOf(
                    WorkItem(
                        url = url, title = job.label, kind = spec.kind,
                        selector = spec.selector, slot = 0, quick = true
                    )
                ),
                expandUrl = null,
                expandSpec = null
            )
        }
    }

    /** Single jobs notify under their item's (possibly just-learned) title; batches under the playlist label. */
    private fun WorkJob.withCurrentLabel(): WorkJob =
        if (isBatch) this
        else copy(label = items.firstOrNull()?.title?.takeIf { it.isNotBlank() } ?: label)

    /** Persists the not-yet-finished tail of [job] (from item index [fromIndex]) as a paused job and notifies. */
    private fun persistPaused(job: WorkJob, fromIndex: Int, progressPercent: Int, failedCount: Int) {
        val remaining = job.items.drop(fromIndex)
        if (remaining.isEmpty()) return
        val record = PausedJob(
            id = job.id,
            label = job.label,
            isBatch = job.isBatch,
            items = remaining.map { item -> item.toPersistedItem() },
            progressPercent = progressPercent.coerceIn(0, 100),
            failedCount = failedCount,
            queuePosition = job.positionOffset + fromIndex + 1,
            queueTotal = job.positionOffset + job.items.size,
            pausedAtMillis = System.currentTimeMillis()
        )
        val repo = PausedJobsRepository(this)
        repo.addOrUpdate(record)
        DownloadCenter.pausedJobs.value = repo.load()
        notifyPaused(record)
    }

    /**
     * Downloads one item of [rt]'s job (publish + library add included). Never
     * throws; the outcome says how it ended. The staging directory is a pure
     * function of (job id, item slot): a resumed attempt reuses the previous
     * attempt's directory untouched, so yt-dlp finds its .part files and
     * continues. Staging is deleted for every outcome except PAUSED — and
     * except a FAILED attempt that [downloadWithRetries] is about to retry
     * ([keepStagingOnFailure]), whose partials the next attempt continues.
     */
    private fun downloadOne(rt: JobRuntime, item: WorkItem, keepStagingOnFailure: Boolean = false): ItemOutcome {
        val jobId = rt.job.id
        val staging = Staging.itemDir(this, jobId, item.slot)
        var outcome = ItemOutcome.FAILED
        try {
            val app = VidLoaderApp.instance
            app.initEngineBlocking()
            if (!app.engineReady) {
                throw IllegalStateException("engine init failed: ${app.engineError}")
            }

            // No deleteRecursively here: on a resume this directory holds the partials we must keep.
            staging.mkdirs()

            val request = YoutubeDLRequest(item.url)
            // The user's optional cookies file (content behind a login).
            // Added exactly once, here, on the freshly built request.
            DownloadSettings.applyCookies(this, request)
            if (item.playlistIndex != null) {
                request.addOption("--playlist-items", item.playlistIndex.toString())
            } else {
                request.addOption("--no-playlist")
            }
            request.addOption("--no-mtime")
            request.addOption("--retries", "3")
            val rateLimit = DownloadSettings.rateLimit(this)
            if (rateLimit.isNotBlank()) {
                request.addOption("--limit-rate", rateLimit)
            }
            // SponsorBlock removal: YouTube URLs only. The postprocessor
            // errors on unsupported extractors, so this host gate (the same
            // host logic PageVideo uses) is load-bearing — a non-YouTube
            // request must never carry the flag.
            if (DownloadSettings.sponsorBlock(this) && PageVideo.isYouTube(item.url)) {
                request.addOption("--sponsorblock-remove", SPONSORBLOCK_CATEGORIES)
            }
            // Clip cut: fetch only the requested section of the video.
            if (item.sectionStartSec != null || item.sectionEndSec != null) {
                val start = formatSectionTime(item.sectionStartSec ?: 0)
                val end = item.sectionEndSec?.let { formatSectionTime(it) } ?: ""
                request.addOption("--download-sections", "*$start-$end")
                request.addOption("--force-keyframes-at-cuts")
            }
            request.addOption("-o", File(staging, "%(id)s.%(ext)s").absolutePath)
            if (item.quick) {
                // No info was fetched up front: let the download itself write
                // the metadata we learn the real title from (see learnTitle).
                request.addOption("--write-info-json")
            }
            when (item.kind) {
                DownloadKind.VIDEO -> {
                    request.addOption("-f", item.selector ?: "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best")
                    request.addOption("--merge-output-format", "mp4")
                    if (item.subtitles) {
                        // Manual subs, auto-generated as fallback; Arabic first, then English; forced to SRT.
                        request.addOption("--write-subs")
                        request.addOption("--write-auto-subs")
                        request.addOption("--sub-langs", "ar.*,en.*")
                        request.addOption("--convert-subs", "srt")
                    }
                }
                DownloadKind.AUDIO_M4A -> {
                    request.addOption("-f", "bestaudio/best")
                    request.addOption("-x")
                    request.addOption("--audio-format", "m4a")
                }
                DownloadKind.AUDIO_MP3 -> {
                    request.addOption("-f", "bestaudio/best")
                    request.addOption("-x")
                    request.addOption("--audio-format", "mp3")
                    request.addOption("--audio-quality", "0")
                }
                DownloadKind.DIRECT -> {
                    // yt-dlp handles direct media URLs natively; "best" fetches the file as-is.
                    request.addOption("-f", item.selector ?: "best")
                }
            }

            val processId = rt.processId ?: ("dl_" + jobId + "_" + item.slot)
            var lastPercent = -2
            YoutubeDL.getInstance().execute(request, processId) { progress, eta, line ->
                val speed = SPEED_REGEX.find(line)?.groupValues?.get(1)?.trim()
                rt.progress = progress
                rt.etaSeconds = eta
                if (speed != null) rt.speed = speed
                val pct = progress.toInt()
                if (pct != lastPercent) {
                    lastPercent = pct
                    notifyJobProgress(rt)
                    publishActiveItems()
                }
            }

            // A quick download learns its real title from the info json the
            // download just wrote — before the pause check, so a job paused
            // right at the end still persists (and displays) the real title.
            if (item.quick) learnTitle(rt, staging, item)

            // Pause also covers the merge/post-processing tail: if it was pressed while
            // execute() was finishing, keep everything and let the resume re-check the parts.
            if (rt.control.pauseRequested) {
                outcome = ItemOutcome.PAUSED
                return outcome
            }
            if (rt.control.cancelRequested) {
                outcome = ItemOutcome.CANCELLED
                return outcome
            }

            rt.processing = true
            notifyJobProgress(rt)
            publishActiveItems()

            val result = staging.listFiles()
                ?.filter {
                    it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.endsWith(".tmp") &&
                        !it.name.endsWith(".info.json") &&
                        !it.name.endsWith(".webp") && !it.name.endsWith(".jpg") && !it.name.endsWith(".png") &&
                        !it.name.endsWith(".srt") && !it.name.endsWith(".vtt")
                }
                ?.maxByOrNull { it.length() }
                ?: throw IOException("no output file produced")

            val ext = result.extension.lowercase().ifBlank {
                if (item.kind == DownloadKind.VIDEO) "mp4" else "m4a"
            }
            val displayName = FormatUtils.sanitizeFileName(item.title) + sectionFileSuffix(item) + "." + ext
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: if (item.kind == DownloadKind.VIDEO) "video/mp4" else "audio/mpeg"

            val savedUri = publishFile(result, displayName, mime)

            // Subtitle sidecars ride next to the video under the same base name
            // (name.ar.srt / name.en.srt) so players pick them up automatically.
            // Best effort: missing or unpublishable subtitles never fail the download.
            if (item.subtitles) {
                publishSubtitles(staging, displayName)
            }

            val libraryItem = LibraryItem(
                id = processId,
                title = item.title,
                fileName = displayName,
                size = result.length(),
                dateMillis = System.currentTimeMillis(),
                uri = savedUri,
                mime = mime,
                sourceUrl = item.url,
                isAudio = item.kind == DownloadKind.AUDIO_M4A || item.kind == DownloadKind.AUDIO_MP3 ||
                    ext in AUDIO_EXTENSIONS
            )
            LibraryRepository(this).add(libraryItem)
            LinkHistoryRepository(this).updateStatus(
                item.url, LinkHistoryEntry.STATUS_COMPLETED, item.title
            )

            rt.lastItemId = libraryItem.id
            rt.progress = 100f
            outcome = ItemOutcome.SUCCESS
            return outcome
        } catch (e: YoutubeDL.CanceledException) {
            outcome = if (rt.control.pauseRequested) ItemOutcome.PAUSED else ItemOutcome.CANCELLED
            // The info json is written during extraction, so even a killed
            // download usually already knows its real title.
            if (outcome == ItemOutcome.PAUSED && item.quick) learnTitle(rt, staging, item)
            return outcome
        } catch (e: Exception) {
            outcome = when {
                rt.control.pauseRequested -> ItemOutcome.PAUSED
                rt.control.cancelRequested -> ItemOutcome.CANCELLED
                else -> {
                    // A direct link that answers 403/410 is simply dead
                    // (expired signature / removed file) — say so plainly
                    // instead of the generic extractor wording.
                    rt.lastError = if (item.kind == DownloadKind.DIRECT && isExpiredLinkError(e)) {
                        getString(R.string.error_link_expired)
                    } else {
                        friendlyError(e)
                    }
                    ItemOutcome.FAILED
                }
            }
            if (outcome == ItemOutcome.PAUSED && item.quick) learnTitle(rt, staging, item)
            return outcome
        } finally {
            // Pause is the only outcome that keeps the partial files for a later resume.
            // (If the partials vanished externally, the resume simply downloads fresh:
            // yt-dlp treats an empty staging directory as a new download.)
            // A failed attempt that will be retried keeps them too, so the
            // retry continues the transfer instead of restarting it.
            if (outcome != ItemOutcome.PAUSED &&
                !(outcome == ItemOutcome.FAILED && keepStagingOnFailure)
            ) {
                staging.deleteRecursively()
            }
        }
    }

    /** True for the HTTP 403/410 failures that mean a direct link died (expired signature / removed file). */
    private fun isExpiredLinkError(e: Throwable): Boolean {
        val m = (e.message ?: "").lowercase()
        return m.contains("http error 403") || m.contains("http error 410")
    }

    /** yt-dlp section time: 90 → "1:30", 3661 → "1:01:01". */
    private fun formatSectionTime(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** Filename-safe section time (no colons): 90 → "1m30s", 3661 → "1h01m01s". */
    private fun formatFileTime(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%dh%02dm%02ds".format(h, m, sec) else "%dm%02ds".format(m, sec)
    }

    /** " [1m30s-3m45s]" filename suffix for a clip-cut download; "" for full downloads. */
    private fun sectionFileSuffix(item: WorkItem): String {
        if (item.sectionStartSec == null && item.sectionEndSec == null) return ""
        val start = formatFileTime(item.sectionStartSec ?: 0)
        val end = item.sectionEndSec?.let { formatFileTime(it) } ?: "end"
        return " [$start-$end]"
    }

    /**
     * Learns a quick item's real title from the `<id>.info.json` its download
     * wrote into [staging] and adopts it everywhere the item is displayed.
     * Best effort: any failure keeps the placeholder title.
     */
    private fun learnTitle(rt: JobRuntime, staging: File, item: WorkItem) {
        try {
            val infoFile = staging.listFiles()
                ?.firstOrNull { it.isFile && it.name.endsWith(".info.json") }
                ?: return
            val title = JSONObject(infoFile.readText()).optString("title")
            if (title.isNotBlank() && title != item.title) {
                item.title = title
                publishActiveItems()
            }
        } catch (e: Exception) {
            // Placeholder stays — a title must never fail a download.
        }
    }

    // endregion

    // region active-items publishing (library «قيد التنزيل» + legacy state mirror)

    /**
     * Rebuilds [DownloadCenter.activeItems] from the service's real state:
     * running jobs first (start order), then the pending queue in order —
     * policy-blocked jobs as WAITING_WIFI, the rest as QUEUED. Also refreshes
     * the legacy [DownloadCenter.state] mirror of the primary running job.
     */
    private fun publishActiveItems() {
        val rows = mutableListOf<DownloadCenter.ActiveItem>()
        synchronized(queueLock) {
            runtimes.values.sortedBy { it.seq }.forEach { rt ->
                val job = rt.job
                rows += DownloadCenter.ActiveItem(
                    jobId = job.id,
                    title = rt.currentItem?.title ?: job.label,
                    status = rt.activeStatus,
                    progress = rt.progress,
                    etaSeconds = rt.etaSeconds,
                    speed = rt.speed,
                    isBatch = job.isBatch,
                    queuePosition = job.positionOffset + rt.currentIndex + 1,
                    queueTotal = job.positionOffset + job.items.size,
                    failedCount = rt.failed
                )
            }
            pendingJobs.forEach { job ->
                rows += DownloadCenter.ActiveItem(
                    jobId = job.id,
                    title = job.items.firstOrNull()?.title ?: job.label,
                    status = if (job.id in waitingWifiIds) {
                        DownloadCenter.ActiveStatus.WAITING_WIFI
                    } else {
                        DownloadCenter.ActiveStatus.QUEUED
                    },
                    isBatch = job.isBatch,
                    queuePosition = job.positionOffset + 1,
                    queueTotal = job.positionOffset + job.items.size
                )
            }
        }
        DownloadCenter.activeItems.value = rows
        mirrorPrimary()
    }

    private fun primaryRuntime(): JobRuntime? = synchronized(queueLock) {
        runtimes.values.minByOrNull { it.seq }
    }

    private fun isPrimary(rt: JobRuntime): Boolean = primaryRuntime() === rt

    /**
     * Mirrors the primary (earliest started) running job into the legacy
     * [DownloadCenter.state] that InfoActivity's card renders. When nothing
     * is running the state is left untouched, so a terminal DONE/ERROR/PAUSED
     * value written by the finishing worker stays visible.
     */
    private fun mirrorPrimary() {
        val rt = primaryRuntime() ?: return
        val job = rt.job
        DownloadCenter.state.value = DownloadCenter.State(
            status = if (rt.processing) DownloadCenter.Status.PROCESSING else DownloadCenter.Status.RUNNING,
            processId = rt.processId,
            jobId = job.id,
            title = rt.currentItem?.title ?: job.label,
            progress = rt.progress,
            etaSeconds = rt.etaSeconds,
            speed = rt.speed,
            itemId = rt.lastItemId,
            queuePosition = job.positionOffset + rt.currentIndex + 1,
            queueTotal = job.positionOffset + job.items.size,
            failedCount = rt.failed
        )
    }

    // endregion

    // region publishing finished files

    /** Publishes every .srt produced in [staging] next to the video, keeping yt-dlp's language suffix. */
    private fun publishSubtitles(staging: File, videoDisplayName: String) {
        val base = videoDisplayName.substringBeforeLast('.')
        val subs = staging.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".srt", ignoreCase = true) }
            ?: return
        for (sub in subs) {
            try {
                // yt-dlp names subtitles "<id>.<lang>.srt" (lang may be "ar", "en", "ar-orig", …).
                val lang = sub.name.substringBeforeLast('.').substringAfter('.', "")
                val name = if (lang.isBlank()) "$base.srt" else "$base.$lang.srt"
                publishFile(sub, name, "application/x-subrip")
            } catch (e: Exception) {
                // Best effort only — a subtitle must never fail the video download.
            }
        }
    }

    /**
     * Copies the finished file to the download folder and returns its uri:
     * the user-chosen SAF tree when one is set (falling back to the default
     * folder if the tree became unreachable), else Downloads/VidLoader via
     * MediaStore (API 29+) or the public directory (older). The returned
     * value is always a content:// uri except on the legacy path, where it
     * is an absolute file path — the library handles both.
     */
    private fun publishFile(src: File, displayName: String, mime: String): String {
        val tree = DownloadSettings.treeUri(this)
        if (tree != null) {
            try {
                return publishToTree(src, displayName, mime, tree)
            } catch (e: Exception) {
                // Permission revoked / removable storage gone: keep the file
                // by falling back to the default folder instead of failing.
                Log.w(TAG, "publish to chosen folder failed; falling back to Downloads/VidLoader", e)
            }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            publishToMediaStore(src, displayName, mime)
        } else {
            publishToLegacyDir(src, displayName)
        }
    }

    /** Writes [src] into the SAF tree the user picked in «إعدادات التنزيل». Returns the document uri. */
    private fun publishToTree(src: File, displayName: String, mime: String, treeUri: Uri): String {
        val resolver = contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
        val name = uniqueTreeName(treeUri, treeDocId, displayName)
        val docUri = DocumentsContract.createDocument(resolver, parentDocUri, mime, name)
            ?: throw IOException("createDocument returned null")
        try {
            val out = resolver.openOutputStream(docUri) ?: throw IOException("openOutputStream returned null")
            out.use { output ->
                FileInputStream(src).use { it.copyTo(output) }
            }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, docUri) }
            throw e
        }
        return docUri.toString()
    }

    /** Picks a collision-free name inside the SAF tree ("name (1).ext", …), mirroring the MediaStore path. */
    private fun uniqueTreeName(treeUri: Uri, treeDocId: String, displayName: String): String {
        val existing = mutableSetOf<String>()
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
            contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (idx >= 0) cursor.getString(idx)?.let { existing += it }
                }
            }
        } catch (e: Exception) {
            // Best effort: if the listing fails, createDocument's own rename behaviour applies.
            return displayName
        }
        if (displayName !in existing) return displayName
        val base = displayName.substringBeforeLast('.')
        val ext = displayName.substringAfterLast('.', "")
        var attempt = 1
        while (attempt <= 100) {
            val candidate = if (ext.isEmpty()) "$base ($attempt)" else "$base ($attempt).$ext"
            if (candidate !in existing) return candidate
            attempt++
        }
        return displayName
    }

    /** Copies the finished file into Downloads/VidLoader via MediaStore. Returns a content:// uri. */
    private fun publishToMediaStore(src: File, displayName: String, mime: String): String {
        val resolver = contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        var name = displayName
        var attempt = 0
        while (true) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/VidLoader")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
            if (uri != null) {
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(src).use { it.copyTo(out) }
                    }
                    val done = android.content.ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(uri, done, null, null)
                    return uri.toString()
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            }
            attempt++
            if (attempt > 20) throw IOException("mediastore insert failed")
            val base = displayName.substringBeforeLast('.')
            val ext = displayName.substringAfterLast('.', "")
            name = if (ext.isEmpty()) "$base ($attempt)" else "$base ($attempt).$ext"
        }
    }

    /** Pre-API-29 path: copies into the public Downloads/VidLoader directory. Returns an absolute path. */
    private fun publishToLegacyDir(src: File, displayName: String): String {
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VidLoader")
        dir.mkdirs()
        var dest = File(dir, displayName)
        var attempt = 1
        while (dest.exists()) {
            val base = displayName.substringBeforeLast('.')
            val ext = displayName.substringAfterLast('.', "")
            dest = File(dir, if (ext.isEmpty()) "$base ($attempt)" else "$base ($attempt).$ext")
            attempt++
        }
        src.copyTo(dest, overwrite = false)
        return dest.absolutePath
    }

    // endregion

    // region notifications

    private fun channel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_downloads), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun baseBuilder(): NotificationCompat.Builder {
        channel()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setOnlyAlertOnce(true)
    }

    private fun cancelPendingIntent(): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL)
        return PendingIntent.getService(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun pauseJobPendingIntent(jobId: String): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE_JOB)
            .putExtra(EXTRA_JOB_ID, jobId)
        return PendingIntent.getService(
            this, jobProgressId(jobId), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelJobPendingIntent(jobId: String): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL_JOB)
            .putExtra(EXTRA_JOB_ID, jobId)
        return PendingIntent.getService(
            this, jobProgressId(jobId) + 10000, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun resumePendingIntent(jobId: String): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).setAction(ACTION_RESUME)
            .putExtra(EXTRA_JOB_ID, jobId)
        return PendingIntent.getService(
            this, pausedNotificationId(jobId), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun discardPendingIntent(jobId: String): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).setAction(ACTION_DISCARD)
            .putExtra(EXTRA_JOB_ID, jobId)
        return PendingIntent.getService(
            this, pausedNotificationId(jobId) + 1, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** "٣ من ١٠ • " prefix while a multi-item job is running, else empty. */
    private fun queuePrefix(position: Int, total: Int): String =
        if (total > 1) getString(R.string.playlist_progress, position, total) + " • " else ""

    /** Starts the foreground service with an initial notification for [firstJob]. Called once per service lifetime. */
    private fun ensureForeground(firstJob: WorkJob) {
        if (foregroundStarted) return
        val notification = buildProgressNotification(
            title = firstJob.items.firstOrNull()?.title ?: firstJob.label,
            progress = -1f,
            eta = -1,
            speed = "",
            position = firstJob.positionOffset + 1,
            total = firstJob.positionOffset + firstJob.items.size,
            jobId = firstJob.id
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_PROGRESS, notification)
        }
        foregroundStarted = true
    }

    /**
     * Structural notification refresh (job started / finished / primary
     * changed / queue blocked): the primary running job owns the foreground
     * notification NOTIF_PROGRESS; every other running job gets its own
     * progress notification; with nothing running, NOTIF_PROGRESS shows the
     * Wi-Fi wait or the head of the queue.
     */
    private fun refreshJobNotifications() {
        if (!foregroundStarted) return
        val manager = getSystemService(NotificationManager::class.java)
        val primary = primaryRuntime()
        val snapshot = synchronized(queueLock) {
            Triple(runtimes.values.toList(), pendingJobs.firstOrNull(), waitingWifiIds.size)
        }
        val allRuntimes = snapshot.first
        if (primary != null) {
            // The primary's updates now go to NOTIF_PROGRESS; drop its per-job copy.
            manager.cancel(jobProgressId(primary.job.id))
        } else if (snapshot.third > 0) {
            manager.notify(NOTIF_PROGRESS, buildWaitingNotification())
        } else if (snapshot.second != null) {
            val head = snapshot.second!!
            manager.notify(
                NOTIF_PROGRESS,
                buildProgressNotification(
                    title = head.items.firstOrNull()?.title ?: head.label,
                    progress = -1f,
                    eta = -1,
                    speed = "",
                    position = head.positionOffset + 1,
                    total = head.positionOffset + head.items.size,
                    jobId = head.id
                )
            )
        }
        allRuntimes.filter { it !== primary }.forEach { notifyJobProgress(it) }
        if (primary != null) notifyJobProgress(primary)
    }

    /** Posts/updates [rt]'s progress notification (the foreground one when [rt] is primary, else its own). */
    private fun notifyJobProgress(rt: JobRuntime) {
        val manager = getSystemService(NotificationManager::class.java)
        val job = rt.job
        val title = rt.currentItem?.title ?: job.label
        val position = job.positionOffset + rt.currentIndex + 1
        val total = job.positionOffset + job.items.size
        val notification = when {
            rt.activeStatus == DownloadCenter.ActiveStatus.FETCHING -> baseBuilder()
                .setContentTitle(getString(R.string.notif_downloading, title))
                .setContentText(getString(R.string.playlist_fetching))
                .setOngoing(true)
                .setProgress(0, 0, true)
                .addAction(0, getString(R.string.pause), pauseJobPendingIntent(job.id))
                .addAction(0, getString(R.string.cancel), cancelJobPendingIntent(job.id))
                .build()
            rt.processing -> baseBuilder()
                .setContentTitle(getString(R.string.notif_downloading, title))
                .setContentText(queuePrefix(position, total) + getString(R.string.processing))
                .setOngoing(true)
                .setProgress(0, 0, true)
                .addAction(0, getString(R.string.pause), pauseJobPendingIntent(job.id))
                .addAction(0, getString(R.string.cancel), cancelJobPendingIntent(job.id))
                .build()
            else -> buildProgressNotification(title, rt.progress, rt.etaSeconds, rt.speed, position, total, job.id)
        }
        val id = if (isPrimary(rt)) NOTIF_PROGRESS else jobProgressId(job.id)
        manager.notify(id, notification)
    }

    private fun buildProgressNotification(
        title: String,
        progress: Float,
        eta: Long,
        speed: String,
        position: Int,
        total: Int,
        jobId: String
    ): Notification {
        val pct = progress.toInt()
        val detail = StringBuilder(queuePrefix(position, total))
        if (pct >= 0) detail.append(getString(R.string.download_progress, pct))
        if (eta > 0) detail.append(" • ").append(getString(R.string.eta_format, FormatUtils.eta(eta)))
        if (speed.isNotBlank()) detail.append(" • ").append(speed)
        val builder = baseBuilder()
            .setContentTitle(getString(R.string.notif_downloading, title))
            .setOngoing(true)
            .setProgress(100, pct.coerceIn(0, 100), pct < 0)
            .addAction(0, getString(R.string.pause), pauseJobPendingIntent(jobId))
            .addAction(0, getString(R.string.cancel), cancelJobPendingIntent(jobId))
        if (detail.isNotEmpty()) builder.setContentText(detail.toString())
        return builder.build()
    }

    /** Shown on the foreground notification while jobs may not start because mobile data is off. */
    private fun buildWaitingNotification(): Notification =
        baseBuilder()
            .setContentTitle(getString(R.string.waiting_wifi))
            .setContentText(getString(R.string.notif_waiting_wifi_body))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .addAction(0, getString(R.string.cancel), cancelPendingIntent())
            .build()

    private fun resultPendingIntent(): PendingIntent {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_LIBRARY, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(this, 2, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notifyJobFinished(job: WorkJob, succeeded: Int, failed: Int, jobId: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val title: String
        val text: String
        if (job.isBatch) {
            title = getString(R.string.notif_batch_done, job.label)
            text = if (failed > 0) {
                getString(R.string.notif_batch_partial, succeeded, failed)
            } else {
                getString(R.string.notif_tap_to_open_library)
            }
        } else {
            title = getString(R.string.notif_done, job.label)
            text = getString(R.string.notif_tap_to_open_library)
        }
        val notification = baseBuilder()
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(resultPendingIntent())
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        manager.notify(resultNotificationId(jobId), notification)
    }

    private fun notifyJobFailed(job: WorkJob, jobId: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = baseBuilder()
            .setContentTitle(getString(R.string.notif_failed, job.label))
            .setContentText(getString(R.string.notif_tap_to_open_library))
            .setContentIntent(resultPendingIntent())
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        manager.notify(resultNotificationId(jobId), notification)
    }

    /** Persistent (non-ongoing) notification for a paused job, with استئناف / إلغاء actions. */
    private fun notifyPaused(record: PausedJob) {
        val manager = getSystemService(NotificationManager::class.java)
        val text = if (record.progressPercent > 0) {
            getString(R.string.paused_progress, record.progressPercent)
        } else {
            getString(R.string.notif_paused_text)
        }
        val notification = baseBuilder()
            .setContentTitle(getString(R.string.notif_paused, record.title))
            .setContentText(text)
            .setContentIntent(resultPendingIntent())
            .setAutoCancel(true)
            .setOngoing(false)
            .addAction(0, getString(R.string.resume), resumePendingIntent(record.id))
            .addAction(0, getString(R.string.cancel), discardPendingIntent(record.id))
            .build()
        manager.notify(pausedNotificationId(record.id), notification)
    }

    private fun cancelPausedNotification(jobId: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.cancel(pausedNotificationId(jobId))
    }

    // endregion

    companion object {
        private const val TAG = "DownloadService"
        const val ACTION_CANCEL = "com.ahmed.vidloader.action.CANCEL"
        const val ACTION_PAUSE = "com.ahmed.vidloader.action.PAUSE"
        const val ACTION_PAUSE_JOB = "com.ahmed.vidloader.action.PAUSE_JOB"
        const val ACTION_CANCEL_JOB = "com.ahmed.vidloader.action.CANCEL_JOB"
        const val ACTION_RESUME = "com.ahmed.vidloader.action.RESUME"
        const val ACTION_DISCARD = "com.ahmed.vidloader.action.DISCARD"
        const val ACTION_RETRY_FAILED = "com.ahmed.vidloader.action.RETRY_FAILED"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_SELECTOR = "selector"
        private const val EXTRA_SUBTITLES = "subtitles"
        private const val EXTRA_SECTION_START = "section_start"
        private const val EXTRA_SECTION_END = "section_end"
        private const val EXTRA_QUICK = "quick"
        private const val EXTRA_QUICK_PLAYLIST_URL = "quick_playlist_url"
        private const val EXTRA_JOB_ID = "job_id"
        private const val EXTRA_PLAYLIST_URL = "playlist_url"
        private const val EXTRA_PLAYLIST_TITLE = "playlist_title"
        private const val EXTRA_INDICES = "playlist_indices"
        private const val EXTRA_TITLES = "playlist_titles"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_PROGRESS = 1001

        /** Total attempts per item: the first try plus two automatic retries. */
        private const val MAX_ITEM_ATTEMPTS = 3
        private const val RETRY_DELAY_FIRST_MS = 10_000L
        private const val RETRY_DELAY_SECOND_MS = 30_000L
        private const val RETRY_POLL_SLICE_MS = 250L

        /** SponsorBlock categories removed when the user enables the setting (YouTube only). */
        private const val SPONSORBLOCK_CATEGORIES = "sponsor,selfpromo,intro,outro,interaction"
        private val SPEED_REGEX = Regex("at\\s+([0-9.]+\\s*[KMG]i?B/s)")
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")

        /** Stable per-job notification id for the "paused" notification (3000+, clear of the other ranges). */
        fun pausedNotificationId(jobId: String): Int = 3000 + (jobId.hashCode() and 0x3FF)

        /** Stable per-job id for a running job's own progress notification (20000+). */
        fun jobProgressId(jobId: String): Int = 20000 + (jobId.hashCode() and 0x1FFF)

        /** Stable per-job id for the finished/failed result notification (30000+). */
        fun resultNotificationId(jobId: String): Int = 30000 + (jobId.hashCode() and 0x1FFF)

        fun startIntent(context: Context, url: String, title: String, spec: DownloadSpec): Intent {
            return Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_KIND, spec.kind.name)
                putExtra(EXTRA_SELECTOR, spec.selector)
                putExtra(EXTRA_SUBTITLES, spec.subtitles)
                putExtra(EXTRA_SECTION_START, spec.sectionStartSec ?: -1L)
                putExtra(EXTRA_SECTION_END, spec.sectionEndSec ?: -1L)
            }
        }

        /**
         * Quick download of one URL at the user's preset: no info fetch, no
         * playlist expansion (--no-playlist applies, so a playlist link counts
         * as one video). The title is learned from the download itself.
         */
        fun startQuickIntent(context: Context, url: String, spec: DownloadSpec): Intent {
            return Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, "")
                putExtra(EXTRA_KIND, spec.kind.name)
                putExtra(EXTRA_SELECTOR, spec.selector)
                putExtra(EXTRA_SUBTITLES, false)
                putExtra(EXTRA_QUICK, true)
            }
        }

        /**
         * Quick download of a URL that MIGHT be a playlist: the service expands
         * it in the background and downloads every entry at the preset; a
         * non-playlist URL falls back to a single quick download.
         */
        fun startQuickPlaylistIntent(context: Context, url: String, spec: DownloadSpec): Intent {
            return Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_QUICK_PLAYLIST_URL, url)
                putExtra(EXTRA_KIND, spec.kind.name)
                putExtra(EXTRA_SELECTOR, spec.selector)
            }
        }

        /** Starts (or queues) a playlist batch: entries #[indices] of [playlistUrl], one shared [spec] for all. */
        fun startBatchIntent(
            context: Context,
            playlistUrl: String,
            playlistTitle: String,
            indices: IntArray,
            titles: Array<String>,
            spec: DownloadSpec
        ): Intent {
            return Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_PLAYLIST_URL, playlistUrl)
                putExtra(EXTRA_PLAYLIST_TITLE, playlistTitle)
                putExtra(EXTRA_INDICES, indices)
                putExtra(EXTRA_TITLES, titles)
                putExtra(EXTRA_KIND, spec.kind.name)
                putExtra(EXTRA_SELECTOR, spec.selector)
                putExtra(EXTRA_SUBTITLES, spec.subtitles)
            }
        }

        fun cancelIntent(context: Context): Intent {
            return Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL)
        }

        fun pauseIntent(context: Context): Intent {
            return Intent(context, DownloadService::class.java).setAction(ACTION_PAUSE)
        }

        /** ⏸ Pause for ONE job (library in-progress list). */
        fun pauseJobIntent(context: Context, jobId: String): Intent {
            return Intent(context, DownloadService::class.java).setAction(ACTION_PAUSE_JOB)
                .putExtra(EXTRA_JOB_ID, jobId)
        }

        /** ✕ Cancel for ONE job (library in-progress list). */
        fun cancelJobIntent(context: Context, jobId: String): Intent {
            return Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL_JOB)
                .putExtra(EXTRA_JOB_ID, jobId)
        }

        fun resumeIntent(context: Context, jobId: String): Intent {
            return Intent(context, DownloadService::class.java).setAction(ACTION_RESUME)
                .putExtra(EXTRA_JOB_ID, jobId)
        }

        fun discardIntent(context: Context, jobId: String): Intent {
            return Intent(context, DownloadService::class.java).setAction(ACTION_DISCARD)
                .putExtra(EXTRA_JOB_ID, jobId)
        }

        /** ↻ Re-run a failed record's items as a fresh job (library in-progress list). [recordId] is the FailedJob id. */
        fun retryFailedIntent(context: Context, recordId: String): Intent {
            return Intent(context, DownloadService::class.java).setAction(ACTION_RETRY_FAILED)
                .putExtra(EXTRA_JOB_ID, recordId)
        }
    }
}
