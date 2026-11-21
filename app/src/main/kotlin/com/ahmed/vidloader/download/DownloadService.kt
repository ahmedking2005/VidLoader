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
import com.ahmed.vidloader.util.FormatUtils
import com.ahmed.vidloader.util.friendlyError
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.Executors

enum class DownloadKind { VIDEO, AUDIO_M4A, AUDIO_MP3, DIRECT }

data class DownloadSpec(
    val kind: DownloadKind,
    val selector: String? = null,
    val label: String = ""
)

class DownloadService : Service() {

    /** One downloadable unit. [playlistIndex] != null means: download entry #[playlistIndex] (1-based) of the playlist at [url]. */
    private data class WorkItem(
        val url: String,
        val title: String,
        val kind: DownloadKind,
        val selector: String?,
        val playlistIndex: Int? = null
    )

    /** A group of items started together (a single video = a job of one item; a playlist selection = one batch job). */
    private data class WorkJob(
        val label: String,
        val isBatch: Boolean,
        val items: List<WorkItem>
    )

    private enum class ItemOutcome { SUCCESS, FAILED, CANCELLED }

    private val executor = Executors.newSingleThreadExecutor()

    private val pendingJobs = ArrayDeque<WorkJob>()
    private val queueLock = Any()

    @Volatile
    private var draining: Boolean = false

    @Volatile
    private var currentProcessId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            DownloadCenter.cancelRequested = true
            synchronized(queueLock) { pendingJobs.clear() }
            val pid = currentProcessId
            if (pid != null) {
                Thread { YoutubeDL.getInstance().destroyProcessById(pid) }.start()
            }
            return START_NOT_STICKY
        }

        val job = intent?.let { parseJob(it) }
        if (job == null) {
            if (!draining) stopSelf()
            return START_NOT_STICKY
        }

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
            return START_NOT_STICKY
        }

        DownloadCenter.reset()
        DownloadCenter.state.value = DownloadCenter.State(
            status = DownloadCenter.Status.RUNNING,
            title = job.items.firstOrNull()?.title ?: job.label,
            queuePosition = if (job.items.isEmpty()) 0 else 1,
            queueTotal = job.items.size
        )
        val notification = buildProgressNotification(DownloadCenter.state.value.title, -1f, -1, "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_PROGRESS, notification)
        }

        executor.execute { drainQueue() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        currentProcessId?.let { pid ->
            Thread { YoutubeDL.getInstance().destroyProcessById(pid) }.start()
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun parseJob(intent: Intent): WorkJob? {
        val kind = intent.getStringExtra(EXTRA_KIND)
            ?.let { runCatching { DownloadKind.valueOf(it) }.getOrNull() }
            ?: DownloadKind.VIDEO
        val selector = intent.getStringExtra(EXTRA_SELECTOR)

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
                    playlistIndex = index
                )
            }
            if (items.isEmpty()) return null
            return WorkJob(
                label = intent.getStringExtra(EXTRA_PLAYLIST_TITLE).orEmpty(),
                isBatch = true,
                items = items
            )
        }

        val url = intent.getStringExtra(EXTRA_URL) ?: return null
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        return WorkJob(
            label = title,
            isBatch = false,
            items = listOf(WorkItem(url = url, title = title, kind = kind, selector = selector))
        )
    }

    /** Sequentially drains the job queue. A failed item is skipped and counted; cancel stops everything. */
    private fun drainQueue() {
        try {
            while (true) {
                val job = synchronized(queueLock) {
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

                // Fresh job: clear any stale cancel flag / state from the previous one.
                DownloadCenter.reset()
                val total = job.items.size
                var succeeded = 0
                var failed = 0
                var lastError: String? = null
                var cancelled = false

                for ((i, item) in job.items.withIndex()) {
                    if (DownloadCenter.cancelRequested) {
                        cancelled = true
                        break
                    }
                    val processId = "dl_" + System.currentTimeMillis() + "_" + i
                    currentProcessId = processId
                    DownloadCenter.state.value = DownloadCenter.State(
                        status = DownloadCenter.Status.RUNNING,
                        processId = processId,
                        title = item.title,
                        queuePosition = i + 1,
                        queueTotal = total,
                        failedCount = failed
                    )
                    notifyProgress(item.title, -1f, -1, "")

                    when (downloadOne(processId, item)) {
                        ItemOutcome.SUCCESS -> succeeded++
                        ItemOutcome.FAILED -> {
                            failed++
                            lastError = DownloadCenter.state.value.error ?: lastError
                            DownloadCenter.state.value = DownloadCenter.state.value.copy(failedCount = failed)
                        }
                        ItemOutcome.CANCELLED -> cancelled = true
                    }
                    currentProcessId = null
                    if (cancelled) break
                }

                when {
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
                        notifyJobFinished(job, succeeded, failed)
                    }
                    else -> {
                        DownloadCenter.state.value = DownloadCenter.state.value.copy(
                            status = DownloadCenter.Status.ERROR,
                            error = lastError,
                            failedCount = failed
                        )
                        notifyJobFailed(job)
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

    /** Downloads one item (publish + library add included). Never throws; the outcome says how it ended. */
    private fun downloadOne(processId: String, item: WorkItem): ItemOutcome {
        val staging = File(getExternalFilesDir(null), "staging/$processId")
        try {
            val app = VidLoaderApp.instance
            app.initEngineBlocking()
            if (!app.engineReady) {
                throw IllegalStateException("engine init failed: ${app.engineError}")
            }

            staging.deleteRecursively()
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
            when (item.kind) {
                DownloadKind.VIDEO -> {
                    request.addOption("-f", item.selector ?: "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best")
                    request.addOption("--merge-output-format", "mp4")
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

            if (DownloadCenter.cancelRequested) return ItemOutcome.CANCELLED

            DownloadCenter.state.value = DownloadCenter.state.value.copy(status = DownloadCenter.Status.PROCESSING)
            notifyProcessing(item.title)

            val result = staging.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.endsWith(".tmp") && !it.name.endsWith(".webp") && !it.name.endsWith(".jpg") && !it.name.endsWith(".png") }
                ?.maxByOrNull { it.length() }
                ?: throw IOException("no output file produced")

            val ext = result.extension.lowercase().ifBlank {
                if (item.kind == DownloadKind.VIDEO) "mp4" else "m4a"
            }
            val displayName = FormatUtils.sanitizeFileName(item.title) + "." + ext
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: if (item.kind == DownloadKind.VIDEO) "video/mp4" else "audio/mpeg"

            val savedUri = publishFile(result, displayName, mime)

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
            return ItemOutcome.SUCCESS
        } catch (e: YoutubeDL.CanceledException) {
            return ItemOutcome.CANCELLED
        } catch (e: Exception) {
            return if (DownloadCenter.cancelRequested) {
                ItemOutcome.CANCELLED
            } else {
                DownloadCenter.state.value = DownloadCenter.state.value.copy(error = friendlyError(e))
                ItemOutcome.FAILED
            }
        } finally {
            staging.deleteRecursively()
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

    // endregion

    companion object {
        const val ACTION_CANCEL = "com.ahmed.vidloader.action.CANCEL"
        private const val EXTRA_URL = "url"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_SELECTOR = "selector"
        private const val EXTRA_PLAYLIST_URL = "playlist_url"
        private const val EXTRA_PLAYLIST_TITLE = "playlist_title"
        private const val EXTRA_INDICES = "playlist_indices"
        private const val EXTRA_TITLES = "playlist_titles"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_PROGRESS = 1001
        private const val NOTIF_RESULT = 1002
        private val SPEED_REGEX = Regex("at\\s+([0-9.]+\\s*[KMG]i?B/s)")
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac")

        fun startIntent(context: Context, url: String, title: String, spec: DownloadSpec): Intent {
            return Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
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
            }
        }

        fun cancelIntent(context: Context): Intent {
            return Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL)
        }
    }
}
