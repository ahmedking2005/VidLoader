package com.ahmed.vidloader.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ahmed.vidloader.R
import com.ahmed.vidloader.VidLoaderApp
import com.ahmed.vidloader.data.LibraryItem
import com.ahmed.vidloader.data.LibraryRepository
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Quick-convert for completed library items (v1.9): «تحويل إلى MP3» and
 * «ضغط الفيديو», run as a foreground service so a long conversion survives
 * the UI going away.
 *
 * HOW FFMPEG IS RUN — verified against the bundled 0.18.1 artifacts, not
 * assumed: the `com.yausername.ffmpeg.FFmpeg` class in
 * youtubedl-android:ffmpeg:0.18.1 exposes ONLY `init(Context)` /
 * `getInstance()` — there is no execute() API in this version. What init()
 * does is extract the bundled `libffmpeg.zip.so` into
 * `<noBackupFilesDir>/youtubedl-android/packages/ffmpeg/…`, leaving a real
 * ffmpeg executable on disk (that is how yt-dlp itself invokes it). This
 * service therefore locates that extracted executable at runtime and runs
 * it directly with ProcessBuilder. If the binary cannot be found, the
 * conversion fails with a clear Arabic error instead of pretending to run.
 */
class ConvertService : Service() {

    private val executor = Executors.newSingleThreadExecutor()
    private val running = mutableSetOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val itemId = intent?.getStringExtra(EXTRA_ITEM_ID)
        val mode = intent?.getStringExtra(EXTRA_MODE)
        if (itemId == null || mode == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val item = LibraryRepository(this).load().firstOrNull { it.id == itemId }
        if (item == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val notifId = notificationId(itemId)
        startForeground(notifId, progressNotification(item, mode))
        synchronized(running) { running += itemId }
        executor.execute {
            try {
                convert(item, mode)
                notifyResult(notifId, item, mode, success = true)
            } catch (e: Exception) {
                Log.e(TAG, "conversion failed", e)
                notifyResult(notifId, item, mode, success = false)
            } finally {
                val last = synchronized(running) {
                    running -= itemId
                    running.isEmpty()
                }
                if (last) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    /** Runs one conversion end to end on the worker thread. Throws on any failure. */
    private fun convert(item: LibraryItem, mode: String) {
        // The engine init is what extracts the ffmpeg binary; make sure it ran.
        VidLoaderApp.instance.initEngineBlocking()

        val staging = File(cacheDir, "convert/${item.id}").apply { mkdirs() }
        try {
            val source = materializeSource(item, staging)
            val ffmpeg = findFfmpegBinary()
                ?: throw IllegalStateException(getString(R.string.convert_no_ffmpeg))

            val isMp3 = mode == MODE_MP3
            val base = item.fileName.substringBeforeLast('.').ifBlank { "video" }
            val outName = if (isMp3) "$base [MP3].mp3" else "$base [${getString(R.string.convert_compressed_suffix)}].mp4"
            val output = File(staging, outName)

            val args = mutableListOf(ffmpeg.absolutePath, "-y", "-i", source.absolutePath)
            if (isMp3) {
                args += listOf("-vn", "-codec:a", "libmp3lame", "-q:a", "2")
            } else {
                args += listOf(
                    "-vf", "scale=-2:min(480\\,ih)",
                    "-codec:v", "libx264", "-preset", "veryfast", "-crf", "28",
                    "-codec:a", "aac", "-b:a", "128k",
                    "-movflags", "+faststart"
                )
            }
            args += output.absolutePath
            runFfmpeg(args)
            if (!output.exists() || output.length() == 0L) {
                throw IllegalStateException(getString(R.string.convert_failed))
            }

            val mime = if (isMp3) "audio/mpeg" else "video/mp4"
            val savedUri = FilePublisher.publish(this, output, outName, mime)
            LibraryRepository(this).add(
                LibraryItem(
                    id = UUID.randomUUID().toString(),
                    title = item.title.ifBlank { base },
                    fileName = outName,
                    size = output.length(),
                    dateMillis = System.currentTimeMillis(),
                    uri = savedUri,
                    mime = mime,
                    sourceUrl = item.sourceUrl,
                    isAudio = isMp3
                )
            )
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Copies the source into staging when it only exists behind a content:// uri. */
    private fun materializeSource(item: LibraryItem, staging: File): File {
        if (!item.uri.startsWith("content://")) {
            val direct = File(item.uri)
            if (direct.exists()) return direct
        }
        val ext = item.fileName.substringAfterLast('.', "mp4")
        val copy = File(staging, "input.$ext")
        contentResolver.openInputStream(Uri.parse(item.uri))?.use { input ->
            copy.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException(getString(R.string.file_missing))
        return copy
    }

    /**
     * Finds the ffmpeg executable that FFmpeg.init() extracted under
     * noBackupFilesDir/youtubedl-android (packages/ffmpeg/…). Prefers a file
     * named exactly «ffmpeg»; falls back to the largest ffmpeg-named file.
     */
    private fun findFfmpegBinary(): File? {
        val roots = listOfNotNull(noBackupFilesDir, filesDir)
        var best: File? = null
        for (root in roots) {
            val base = File(root, "youtubedl-android")
            if (!base.exists()) continue
            base.walkTopDown()
                .filter { it.isFile && it.name.contains("ffmpeg", ignoreCase = true) }
                .forEach { candidate ->
                    val current = best
                    if (candidate.name == "ffmpeg") {
                        best = candidate
                    } else if (current == null || (current.name != "ffmpeg" && candidate.length() > current.length())) {
                        best = candidate
                    }
                }
        }
        val found = best ?: return null
        if (!found.canExecute()) {
            runCatching { found.setExecutable(true) }
        }
        return found
    }

    private fun runFfmpeg(args: List<String>) {
        val process = ProcessBuilder(args)
            .redirectErrorStream(true)
            .start()
        // Drain output so the process never blocks on a full pipe.
        val drain = Thread {
            try {
                process.inputStream.bufferedReader().forEachLine { Log.d(TAG, it) }
            } catch (e: Exception) {
                // Stream closed because the process ended — nothing to do.
            }
        }
        drain.start()
        val exit = process.waitFor()
        drain.join(2000)
        if (exit != 0) {
            throw IllegalStateException(getString(R.string.convert_failed))
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

    private fun progressNotification(item: LibraryItem, mode: String): android.app.Notification {
        val title = if (mode == MODE_MP3) {
            getString(R.string.convert_to_mp3)
        } else {
            getString(R.string.convert_compress)
        }
        return baseBuilder()
            .setContentTitle(title)
            .setContentText(item.fileName)
            .setProgress(0, 0, true)
            .setOngoing(true)
            .build()
    }

    private fun notifyResult(notifId: Int, item: LibraryItem, mode: String, success: Boolean) {
        val manager = getSystemService(NotificationManager::class.java)
        val text = if (success) {
            getString(R.string.convert_done, item.fileName)
        } else {
            getString(R.string.convert_failed_for, item.fileName)
        }
        val notification = baseBuilder()
            .setContentTitle(if (mode == MODE_MP3) getString(R.string.convert_to_mp3) else getString(R.string.convert_compress))
            .setContentText(text)
            .setOngoing(false)
            .setAutoCancel(true)
            .build()
        // Replaces the foreground progress notification (same id) when the
        // service is about to stop; if other conversions still run, this
        // posts a separate result notification under a nearby id instead.
        manager.notify(if (success) notifId + 5000 else notifId + 9000, notification)
        manager.cancel(notifId)
    }

    private fun notificationId(itemId: String): Int = NOTIF_BASE + (itemId.hashCode() and 0x3FF)

    // endregion

    companion object {
        private const val TAG = "ConvertService"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_BASE = 9200
        private const val EXTRA_ITEM_ID = "convert_item_id"
        private const val EXTRA_MODE = "convert_mode"
        const val MODE_MP3 = "mp3"
        const val MODE_COMPRESS = "compress"

        fun convertIntent(context: Context, itemId: String, mode: String): Intent =
            Intent(context, ConvertService::class.java)
                .putExtra(EXTRA_ITEM_ID, itemId)
                .putExtra(EXTRA_MODE, mode)
    }
}
