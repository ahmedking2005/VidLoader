package com.ahmed.vidloader

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL

class VidLoaderApp : Application() {

    @Volatile
    var engineReady: Boolean = false
        private set

    @Volatile
    var engineError: String? = null
        private set

    private val initLock = Any()

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Apply the persisted theme choice before any activity is created.
        com.ahmed.vidloader.data.DownloadSettings.applyTheme(this)
        // v1.10 background work: ensure the periodic watch check exists and
        // re-register any persisted scheduled downloads (unique names + KEEP
        // policy inside, so this can never create duplicates).
        try {
            com.ahmed.vidloader.work.WorkScheduler.registerAll(this)
        } catch (e: Exception) {
            Log.e(TAG, "background work registration failed", e)
        }
        Thread { initEngineBlocking() }.start()
    }

    /** Initializes the yt-dlp + ffmpeg engine once. Safe to call from any thread, any number of times. */
    fun initEngineBlocking() {
        synchronized(initLock) {
            if (engineReady) return
            try {
                YoutubeDL.getInstance().init(this)
                FFmpeg.getInstance().init(this)
                engineReady = true
                engineError = null
            } catch (e: Exception) {
                Log.e(TAG, "engine init failed", e)
                engineError = e.message
            }
        }
    }

    companion object {
        private const val TAG = "VidLoaderApp"
        lateinit var instance: VidLoaderApp
            private set
    }
}
