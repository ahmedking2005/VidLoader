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

enum class DownloadKind { VIDEO, AUDIO_M4A, AUDIO_MP3 }

data class DownloadSpec(
    val kind: DownloadKind,
    val selector: String? = null,
    val label: String = ""
)

class DownloadService : Service() {

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var currentProcessId: String? = null

    @Volatile
    private var running: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            DownloadCenter.cancelRequested = true
            val pid = currentProcessId
            if (pid != null) {
                Thread { YoutubeDL.getInstance().destroyProcessById(pid) }.start()
            }
            return START_NOT_STICKY
        }

        val url = intent?.getStringExtra(EXTRA_URL)
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: ""
        val kindName = intent?.getStringExtra(EXTRA_KIND)
        val selector = intent?.getStringExtra(EXTRA_SELECTOR)
        if (url == null || kindName == null) {
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        val kind = runCatching { DownloadKind.valueOf(kindName) }.getOrNull() ?: DownloadKind.VIDEO

        if (DownloadCenter.isBusy()) {
            android.widget.Toast.makeText(this, R.string.download_already_running, android.widget.Toast.LENGTH_SHORT).show()
            return START_NOT_STICKY
        }

        val processId = "dl_" + System.currentTimeMillis()
        currentProcessId = processId
        running = true
        DownloadCenter.reset()
        DownloadCenter.state.value = DownloadCenter.State(
            status = DownloadCenter.Status.RUNNING,
            processId = processId,
            title = title
        )

        val notification = buildProgressNotification(title, -1f, -1, "")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_PROGRESS, notification)
        }

        executor.execute {
            runDownload(processId, url, title, kind, selector)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        currentProcessId?.let { pid ->
            Thread { YoutubeDL.getInstance().destroyProcessById(pid) }.start()
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun runDownload(processId: String, url: String, title: String, kind: DownloadKind, selector: String?) {
        val staging = File(getExternalFilesDir(null), "staging/$processId")
        try {
            val app = VidLoaderApp.instance
            app.initEngineBlocking()
            if (!app.engineReady) {
                throw IllegalStateException("engine init failed: ${app.engineError}")
            }

            staging.deleteRecursively()
            staging.mkdirs()

            val request = YoutubeDLRequest(url)
            request.addOption("--no-playlist")
            request.addOption("--no-mtime")
            request.addOption("--retries", "3")
            request.addOption("-o", File(staging, "%(id)s.%(ext)s").absolutePath)
            when (kind) {
                DownloadKind.VIDEO -> {
                    request.addOption("-f", selector ?: "bestvideo+bestaudio/best")
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
                    notifyProgress(title, progress, eta, speed ?: current.speed)
                }
            }

            if (DownloadCenter.cancelRequested) {
                DownloadCenter.state.value = DownloadCenter.state.value.copy(status = DownloadCenter.Status.CANCELLED)
                return
            }

            DownloadCenter.state.value = DownloadCenter.state.value.copy(status = DownloadCenter.Status.PROCESSING)
            notifyProcessing(title)

            val result = staging.listFiles()
                ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !it.name.endsWith(".tmp") && !it.name.endsWith(".webp") && !it.name.endsWith(".jpg") && !it.name.endsWith(".png") }
                ?.maxByOrNull { it.length() }
                ?: throw IOException("no output file produced")

            val ext = result.extension.lowercase().ifBlank { if (kind == DownloadKind.VIDEO) "mp4" else "m4a" }
            val displayName = FormatUtils.sanitizeFileName(title) + "." + ext
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: if (kind == DownloadKind.VIDEO) "video/mp4" else "audio/mpeg"

            val savedUri = publishFile(result, displayName, mime)

            val item = LibraryItem(
                id = processId,
                title = title,
                fileName = displayName,
                size = result.length(),
                dateMillis = System.currentTimeMillis(),
                uri = savedUri,
                mime = mime,
                sourceUrl = url,
                isAudio = kind != DownloadKind.VIDEO
            )
            LibraryRepository(this).add(item)

            DownloadCenter.state.value = DownloadCenter.state.value.copy(
                status = DownloadCenter.Status.DONE,
                itemId = item.id,
                progress = 100f
            )
            notifyFinished(title, failed = false)
        } catch (e: YoutubeDL.CanceledException) {
            DownloadCenter.state.value = DownloadCenter.state.value.copy(status = DownloadCenter.Status.CANCELLED)
        } catch (e: Exception) {
            if (DownloadCenter.cancelRequested) {
                DownloadCenter.state.value = DownloadCenter.state.value.copy(status = DownloadCenter.Status.CANCELLED)
            } else {
                DownloadCenter.state.value = DownloadCenter.state.value.copy(
                    status = DownloadCenter.Status.ERROR,
                    error = friendlyError(e)
                )
                notifyFinished(title, failed = true)
            }
        } finally {
            staging.deleteRecursively()
            currentProcessId = null
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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

    private fun buildProgressNotification(title: String, progress: Float, eta: Long, speed: String): Notification {
        val pct = progress.toInt()
        val detail = StringBuilder()
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
            .setContentText(getString(R.string.processing))
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        manager.notify(NOTIF_PROGRESS, notification)
    }

    private fun notifyFinished(title: String, failed: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        val openIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_LIBRARY, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(this, 2, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = baseBuilder()
            .setContentTitle(getString(if (failed) R.string.notif_failed else R.string.notif_done, title))
            .setContentText(getString(R.string.notif_tap_to_open_library))
            .setContentIntent(pending)
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
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_PROGRESS = 1001
        private const val NOTIF_RESULT = 1002
        private val SPEED_REGEX = Regex("at\\s+([0-9.]+\\s*[KMG]i?B/s)")

        fun startIntent(context: Context, url: String, title: String, spec: DownloadSpec): Intent {
            return Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_KIND, spec.kind.name)
                putExtra(EXTRA_SELECTOR, spec.selector)
            }
        }

        fun cancelIntent(context: Context): Intent {
            return Intent(context, DownloadService::class.java).setAction(ACTION_CANCEL)
        }
    }
}
