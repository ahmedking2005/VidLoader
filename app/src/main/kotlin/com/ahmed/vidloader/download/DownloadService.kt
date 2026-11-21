package com.ahmed.vidloader.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.ahmed.vidloader.MainActivity
import com.ahmed.vidloader.R
import com.ahmed.vidloader.VidLoaderApp
import com.ahmed.vidloader.data.LibraryItem
import com.ahmed.vidloader.data.LibraryRepository
import com.ahmed.vidloader.data.PausedJob
import com.ahmed.vidloader.data.PausedJobItem
import com.ahmed.vidloader.data.PausedJobsRepository
import com.ahmed.vidloader.data.PlaylistFetcher
import com.ahmed.vidloader.data.Staging
import com.ahmed.vidloader.util.FormatUtils
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
    val subtitles: Boolean = false
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
        val quick: Boolean = false
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
         * [items] is empty and the drain loop expands [expandUrl] with
         * [expandSpec] in the background before the first item runs; if the URL
         * turns out not to be a playlist, it falls back to one quick item.
         */
        val expandUrl: String? = null,
        val expandSpec: DownloadSpec? = null
    )

    private enum class ItemOutcome { SUCCESS, FAILED, CANCELLED, PAUSED }

    private val executor = Executors.newSingleThreadExecutor()

    private val pendingJobs = ArrayDeque<WorkJob>()
    private val queueLock = Any()

    @Volatile
    private var draining: Boolean = false

    @Volatile
    private var currentProcessId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Publish the persisted paused jobs, and prune staging directories that
        // belong to no paused job (leftovers of jobs that died with the process:
        // killed mid-download, cancelled, or finished without cleanup).
        val repo = PausedJobsRepository(this)
        val paused = repo.load()
        DownloadCenter.pausedJobs.value = paused
        val keep = paused.map { it.id }.toSet()
        Staging.root(this).listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name !in keep) dir.deleteRecursively()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                DownloadCenter.cancelRequested = true
                synchronized(queueLock) { pendingJobs.clear() }
                val pid = currentProcessId
                if (pid != null) {
                    Thread { YoutubeDL.getInstance().destroyProcessById(pid) }.start()
                }
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                requestPause()
                if (!draining && currentProcessId == null) stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                val job = intent.getStringExtra(EXTRA_JOB_ID)?.let { jobId ->
                    PausedJobsRepository(this).getById(jobId)?.toWorkJob()
                }
                if (job != null) {
                    submitJob(job)
                } else if (!draining) {
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_DISCARD -> {
                intent.getStringExtra(EXTRA_JOB_ID)?.let { discardPaused(it) }
                if (!draining) stopSelf()
                return START_NOT_STICKY
            }
        }

        val job = intent?.let { parseJob(it) }
        if (job == null) {
            if (!draining) stopSelf()
            return START_NOT_STICKY
        }
        submitJob(job)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        currentProcessId?.let { pid ->
            Thread { YoutubeDL.getInstance().destroyProcessById(pid) }.start()
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    /**
     * ⏸ Pause: kill the running process but keep its partial files. The drain
     * loop notices [DownloadCenter.pauseRequested], persists a [PausedJob] for
     * the remaining items and moves on to the next queued job. No-op when
     * nothing is running.
     */
    private fun requestPause() {
        if (!draining && currentProcessId == null) return
        DownloadCenter.pauseRequested = true
        val pid = currentProcessId
        if (pid != null) {
            Thread { YoutubeDL.getInstance().destroyProcessById(pid) }.start()
        }
    }

    /** Deletes a paused job's record, its partial files and its notification. */
    private fun discardPaused(jobId: String) {
        val repo = PausedJobsRepository(this)
        repo.remove(jobId)
        Staging.deleteJob(this, jobId)
        cancelPausedNotification(jobId)
        DownloadCenter.pausedJobs.value = repo.load()
    }

    private fun PausedJob.toWorkJob(): WorkJob = WorkJob(
        id = id,
        label = label,
        isBatch = isBatch,
        positionOffset = (queuePosition - 1).coerceAtLeast(0),
        items = items.map { item ->
            WorkItem(
                url = item.url,
                title = item.title,
                kind = runCatching { DownloadKind.valueOf(item.kind) }.getOrDefault(DownloadKind.VIDEO),
                selector = item.selector,
                subtitles = item.subtitles,
                playlistIndex = item.playlistIndex,
                slot = item.slot,
                quick = item.quick
            )
        }
    )

    /** Enqueues [job]; starts the drain loop (and the foreground notification) when idle. */
    private fun submitJob(job: WorkJob) {
        val startNow = synchronized(queueLock) {
            pendingJobs.addLast(job)
            if (draining) {
                false
            } else {
                draining = true
                true
            }
        }

        if (!startNow) {
            Toast.makeText(this, R.string.download_queued, Toast.LENGTH_SHORT).show()
            return
        }

        DownloadCenter.reset()
        DownloadCenter.state.value = DownloadCenter.State(
            status = DownloadCenter.Status.RUNNING,
            title = job.items.firstOrNull()?.title ?: job.label,
            queuePosition = if (job.items.isEmpty()) 0 else job.positionOffset + 1,
            queueTotal = job.positionOffset + job.items.size
        )
        val notification = buildProgressNotification(DownloadCenter.state.value.title, -1f, -1, "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_PROGRESS, notification)
        }

        executor.execute { drainQueue() }
    }

    private fun parseJob(intent: Intent): WorkJob? {
        val kind = intent.getStringExtra(EXTRA_KIND)
            ?.let { runCatching { DownloadKind.valueOf(it) }.getOrNull() }
            ?: DownloadKind.VIDEO
        val selector = intent.getStringExtra(EXTRA_SELECTOR)
        val subtitles = intent.getBooleanExtra(EXTRA_SUBTITLES, false)
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: UUID.randomUUID().toString()

        // Quick playlist: entries unknown — the drain loop expands the URL in
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
        val rawTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val title = if (quick && rawTitle.isBlank()) placeholderTitle(url) else rawTitle
        return WorkJob(
            id = jobId,
            label = title,
            isBatch = false,
            items = listOf(
                WorkItem(
                    url = url, title = title, kind = kind, selector = selector,
                    subtitles = subtitles, slot = 0, quick = quick
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

    /** Sequentially drains the job queue. A failed item is skipped and counted; cancel stops everything. */
    private fun drainQueue() {
        try {
            while (true) {
                var job = synchronized(queueLock) {
                    val next = if (pendingJobs.isEmpty()) null else pendingJobs.removeFirst()
                    if (next == null) draining = false
                    next
                } ?: break

                if (DownloadCenter.cancelRequested) {
                    DownloadCenter.state.value = DownloadCenter.state.value.copy(
                        status = DownloadCenter.Status.CANCELLED
                    )
                    synchronized(queueLock) { pendingJobs.clear() }
                    break
                }

                // Fresh job: clear any stale cancel/pause flags / state from the previous one.
                DownloadCenter.reset()

                // A resumed job stops being "paused" the moment it actually starts:
                // drop its record + notification (a later re-pause writes a fresh record).
                val pausedRepo = PausedJobsRepository(this)
                if (pausedRepo.getById(job.id) != null) {
                    pausedRepo.remove(job.id)
                    cancelPausedNotification(job.id)
                    DownloadCenter.pausedJobs.value = pausedRepo.load()
                }

                // Quick playlist: the entries are not known yet — expand the
                // URL in the background now. A pause/cancel pressed meanwhile
                // is honoured by the item loop's checks below, and a pause
                // persists the expanded items (or the single fallback item).
                if (job.expandUrl != null) {
                    job = expandQuickPlaylist(job)
                }

                val total = job.items.size
                var succeeded = 0
                var failed = 0
                var lastError: String? = null
                var cancelled = false
                var pausedAt = -1
                var pausedProgress = 0

                for ((i, item) in job.items.withIndex()) {
                    if (DownloadCenter.cancelRequested) {
                        cancelled = true
                        break
                    }
                    if (DownloadCenter.pauseRequested) {
                        // Paused while between items (e.g. during the previous item's publish step).
                        pausedAt = i
                        pausedProgress = 0
                        break
                    }
                    val processId = "dl_" + job.id + "_" + item.slot
                    currentProcessId = processId
                    DownloadCenter.state.value = DownloadCenter.State(
                        status = DownloadCenter.Status.RUNNING,
                        processId = processId,
                        title = item.title,
                        queuePosition = job.positionOffset + i + 1,
                        queueTotal = job.positionOffset + total,
                        failedCount = failed
                    )
                    notifyProgress(item.title, -1f, -1, "")

                    when (downloadOne(processId, job.id, item)) {
                        ItemOutcome.SUCCESS -> succeeded++
                        ItemOutcome.FAILED -> {
                            failed++
                            lastError = DownloadCenter.state.value.error ?: lastError
                            DownloadCenter.state.value = DownloadCenter.state.value.copy(failedCount = failed)
                        }
                        ItemOutcome.CANCELLED -> cancelled = true
                        ItemOutcome.PAUSED -> {
                            pausedAt = i
                            pausedProgress = DownloadCenter.state.value.progress.toInt()
                        }
                    }
                    currentProcessId = null
                    if (cancelled || pausedAt >= 0) break
                }

                when {
                    pausedAt >= 0 -> {
                        persistPaused(job, pausedAt, pausedProgress, failed)
                        DownloadCenter.state.value = DownloadCenter.State(
                            status = DownloadCenter.Status.PAUSED,
                            title = job.items.getOrNull(pausedAt)?.title ?: job.label,
                            queuePosition = job.positionOffset + pausedAt + 1,
                            queueTotal = job.positionOffset + total,
                            failedCount = failed
                        )
                        DownloadCenter.pauseRequested = false
                        // Keep draining: the next queued job (if any) runs normally.
                    }
                    cancelled || DownloadCenter.cancelRequested -> {
                        DownloadCenter.state.value = DownloadCenter.state.value.copy(
                            status = DownloadCenter.Status.CANCELLED
                        )
                        synchronized(queueLock) { pendingJobs.clear() }
                        break
                    }
                    succeeded > 0 -> {
                        DownloadCenter.state.value = DownloadCenter.state.value.copy(
                            status = DownloadCenter.Status.DONE,
                            progress = 100f,
                            failedCount = failed
                        )
                        notifyJobFinished(job.withCurrentLabel(), succeeded, failed)
                    }
                    else -> {
                        DownloadCenter.state.value = DownloadCenter.state.value.copy(
                            status = DownloadCenter.Status.ERROR,
                            error = lastError,
                            failedCount = failed
                        )
                        notifyJobFailed(job.withCurrentLabel())
                    }
                }
            }
        } finally {
            synchronized(queueLock) { draining = false }
            currentProcessId = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * Expands a quick playlist job in the background (runs on the drain
     * thread): fetches the playlist's entries and turns them into ordinary
     * batch items at the job's preset. If the URL is not a playlist — or the
     * fetch fails — the job falls back to a single quick download of the URL
     * itself. Pause/cancel flags are deliberately NOT consumed here: the item
     * loop's checks right after this call act on them.
     */
    private fun expandQuickPlaylist(job: WorkJob): WorkJob {
        val url = job.expandUrl ?: return job
        val spec = job.expandSpec ?: DownloadSpec(DownloadKind.VIDEO)
        DownloadCenter.state.value = DownloadCenter.State(
            status = DownloadCenter.Status.RUNNING,
            title = job.label
        )
        notifyFetchingPlaylist(job.label)

        val app = VidLoaderApp.instance
        app.initEngineBlocking()
        val info = if (app.engineReady) {
            try {
                PlaylistFetcher.fetch(url)
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
            items = remaining.map { item ->
                PausedJobItem(
                    url = item.url,
                    title = item.title,
                    kind = item.kind.name,
                    selector = item.selector,
                    subtitles = item.subtitles,
                    playlistIndex = item.playlistIndex,
                    slot = item.slot,
                    quick = item.quick
                )
            },
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
     * Downloads one item (publish + library add included). Never throws; the outcome says how it ended.
     * The staging directory is a pure function of (job id, item slot): a resumed attempt reuses the
     * previous attempt's directory untouched, so yt-dlp finds its .part files and continues. Staging
     * is deleted for every outcome except PAUSED.
     */
    private fun downloadOne(processId: String, jobId: String, item: WorkItem): ItemOutcome {
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
            if (item.playlistIndex != null) {
                request.addOption("--playlist-items", item.playlistIndex.toString())
            } else {
                request.addOption("--no-playlist")
            }
            request.addOption("--no-mtime")
            request.addOption("--retries", "3")
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

            var lastPercent = -2
            YoutubeDL.getInstance().execute(request, processId) { progress, eta, line ->
                val speed = SPEED_REGEX.find(line)?.groupValues?.get(1)?.trim()
                val current = DownloadCenter.state.value
                DownloadCenter.state.value = current.copy(
                    progress = progress,
                    etaSeconds = eta,
                    speed = speed ?: current.speed
                )
                val pct = progress.toInt()
                if (pct != lastPercent) {
                    lastPercent = pct
                    notifyProgress(item.title, progress, eta, speed ?: current.speed)
                }
            }

            // A quick download learns its real title from the info json the
            // download just wrote — before the pause check, so a job paused
            // right at the end still persists (and displays) the real title.
            if (item.quick) learnTitle(staging, item)

            // Pause also covers the merge/post-processing tail: if it was pressed while
            // execute() was finishing, keep everything and let the resume re-check the parts.
            if (DownloadCenter.pauseRequested) {
                outcome = ItemOutcome.PAUSED
                return outcome
            }
            if (DownloadCenter.cancelRequested) {
                outcome = ItemOutcome.CANCELLED
                return outcome
            }

            DownloadCenter.state.value = DownloadCenter.state.value.copy(status = DownloadCenter.Status.PROCESSING)
            notifyProcessing(item.title)

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
            val displayName = FormatUtils.sanitizeFileName(item.title) + "." + ext
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

            DownloadCenter.state.value = DownloadCenter.state.value.copy(
                itemId = libraryItem.id,
                progress = 100f
            )
            outcome = ItemOutcome.SUCCESS
            return outcome
        } catch (e: YoutubeDL.CanceledException) {
            outcome = if (DownloadCenter.pauseRequested) ItemOutcome.PAUSED else ItemOutcome.CANCELLED
            // The info json is written during extraction, so even a killed
            // download usually already knows its real title.
            if (outcome == ItemOutcome.PAUSED && item.quick) learnTitle(staging, item)
            return outcome
        } catch (e: Exception) {
            outcome = when {
                DownloadCenter.pauseRequested -> ItemOutcome.PAUSED
                DownloadCenter.cancelRequested -> ItemOutcome.CANCELLED
                else -> {
                    DownloadCenter.state.value = DownloadCenter.state.value.copy(error = friendlyError(e))
                    ItemOutcome.FAILED
                }
            }
            if (outcome == ItemOutcome.PAUSED && item.quick) learnTitle(staging, item)
            return outcome
        } finally {
            // Pause is the only outcome that keeps the partial files for a later resume.
            // (If the partials vanished externally, the resume simply downloads fresh:
            // yt-dlp treats an empty staging directory as a new download.)
            if (outcome != ItemOutcome.PAUSED) {
                staging.deleteRecursively()
            }
        }
    }

    /**
     * Learns a quick item's real title from the `<id>.info.json` its download
     * wrote into [staging] and adopts it everywhere the item is displayed.
     * Best effort: any failure keeps the placeholder title.
     */
    private fun learnTitle(staging: File, item: WorkItem) {
        try {
            val infoFile = staging.listFiles()
                ?.firstOrNull { it.isFile && it.name.endsWith(".info.json") }
                ?: return
            val title = JSONObject(infoFile.readText()).optString("title")
            if (title.isNotBlank() && title != item.title) {
                item.title = title
                DownloadCenter.state.value = DownloadCenter.state.value.copy(title = title)
            }
        } catch (e: Exception) {
            // Placeholder stays — a title must never fail a download.
        }
    }

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

    /** Copies the finished file into Downloads/VidLoader. Returns a content:// uri (API 29+) or an absolute path (older). */
    private fun publishFile(src: File, displayName: String, mime: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
        } else {
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
    }

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

    private fun pausePendingIntent(): PendingIntent {
        val intent = Intent(this, DownloadService::class.java).setAction(ACTION_PAUSE)
        return PendingIntent.getService(this, 3, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
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
    private fun queuePrefix(): String {
        val s = DownloadCenter.state.value
        return if (s.queueTotal > 1) getString(R.string.playlist_progress, s.queuePosition, s.queueTotal) + " • " else ""
    }

    private fun buildProgressNotification(title: String, progress: Float, eta: Long, speed: String): Notification {
        val pct = progress.toInt()
        val detail = StringBuilder(queuePrefix())
        if (pct >= 0) detail.append(getString(R.string.download_progress, pct))
        if (eta > 0) detail.append(" • ").append(getString(R.string.eta_format, FormatUtils.eta(eta)))
        if (speed.isNotBlank()) detail.append(" • ").append(speed)
        val builder = baseBuilder()
            .setContentTitle(getString(R.string.notif_downloading, title))
            .setOngoing(true)
            .setProgress(100, pct.coerceIn(0, 100), pct < 0)
            .addAction(0, getString(R.string.pause), pausePendingIntent())
            .addAction(0, getString(R.string.cancel), cancelPendingIntent())
        if (detail.isNotEmpty()) builder.setContentText(detail.toString())
        return builder.build()
    }

    private fun notifyProgress(title: String, progress: Float, eta: Long, speed: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIF_PROGRESS, buildProgressNotification(title, progress, eta, speed))
    }

    private fun notifyProcessing(title: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = baseBuilder()
            .setContentTitle(getString(R.string.notif_downloading, title))
            .setContentText(queuePrefix() + getString(R.string.processing))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .addAction(0, getString(R.string.pause), pausePendingIntent())
            .addAction(0, getString(R.string.cancel), cancelPendingIntent())
            .build()
        manager.notify(NOTIF_PROGRESS, notification)
    }

    /** Shown while a quick playlist's entries are being fetched in the background. */
    private fun notifyFetchingPlaylist(label: String) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = baseBuilder()
            .setContentTitle(getString(R.string.notif_downloading, label))
            .setContentText(getString(R.string.playlist_fetching))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .addAction(0, getString(R.string.pause), pausePendingIntent())
            .addAction(0, getString(R.string.cancel), cancelPendingIntent())
            .build()
        manager.notify(NOTIF_PROGRESS, notification)
    }

    private fun resultPendingIntent(): PendingIntent {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_LIBRARY, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(this, 2, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun notifyJobFinished(job: WorkJob, succeeded: Int, failed: Int) {
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
        manager.notify(NOTIF_RESULT, notification)
    }

    private fun notifyJobFailed(job: WorkJob) {
        val manager = getSystemService(NotificationManager::class.java)
        val notification = baseBuilder()
            .setContentTitle(getString(R.string.notif_failed, job.label))
            .setContentText(getString(R.string.notif_tap_to_open_library))
            .setContentIntent(resultPendingIntent())
            .setAutoCancel(true)
            .setOngoing(false)
            .build()
        manager.notify(NOTIF_RESULT, notification)
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
        const val ACTION_CANCEL = "com.ahmed.vidloader.action.CANCEL"
        const val ACTION_PAUSE = "com.ahmed.vidloader.action.PAUSE"
        const val ACTION_RESUME = "com.ahmed.vidloader.action.RESUME"
        const val ACTION_DISCARD = "com.ahmed.vidloader.action.DISCARD"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_SELECTOR = "selector"
        private const val EXTRA_SUBTITLES = "subtitles"
        private const val EXTRA_QUICK = "quick"
        private const val EXTRA_QUICK_PLAYLIST_URL = "quick_playlist_url"
        private const val EXTRA_JOB_ID = "job_id"
        private const val EXTRA_PLAYLIST_URL = "playlist_url"
        private const val EXTRA_PLAYLIST_TITLE = "playlist_title"
        private const val EXTRA_INDICES = "playlist_indices"
        private const val EXTRA_TITLES = "playlist_titles"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_PROGRESS = 1001
        private const val NOTIF_RESULT = 1002
        private val SPEED_REGEX = Regex("at\\s+([0-9.]+\\s*[KMG]i?B/s)")
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")

        /** Stable per-job notification id for the "paused" notification (3000+, clear of 1001/1002). */
        fun pausedNotificationId(jobId: String): Int = 3000 + (jobId.hashCode() and 0x3FF)

        fun startIntent(context: Context, url: String, title: String, spec: DownloadSpec): Intent {
            return Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_KIND, spec.kind.name)
                putExtra(EXTRA_SELECTOR, spec.selector)
                putExtra(EXTRA_SUBTITLES, spec.subtitles)
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

        fun resumeIntent(context: Context, jobId: String): Intent {
            return Intent(context, DownloadService::class.java).setAction(ACTION_RESUME)
                .putExtra(EXTRA_JOB_ID, jobId)
        }

        fun discardIntent(context: Context, jobId: String): Intent {
            return Intent(context, DownloadService::class.java).setAction(ACTION_DISCARD)
                .putExtra(EXTRA_JOB_ID, jobId)
        }
    }
}
