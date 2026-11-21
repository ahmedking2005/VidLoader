package com.ahmed.vidloader.data

import android.content.Context
import androidx.annotation.StringRes
import com.ahmed.vidloader.R
import com.ahmed.vidloader.download.DownloadKind
import com.ahmed.vidloader.download.DownloadSpec

/**
 * The quality Ahmed picks ONCE for «التنزيل السريع» (quick download). Every
 * quick entry point (Home button, share sheet, browser page video) builds its
 * [DownloadSpec] from the same preset, so the behaviour is identical
 * everywhere. Video presets reuse the height-capped selector family of the
 * playlist quality choices — nothing ever downloads above 1080p.
 */
enum class QuickPreset(@StringRes val labelRes: Int, val spec: DownloadSpec) {
    BEST(
        R.string.best_quality,
        DownloadSpec(
            DownloadKind.VIDEO,
            selector = "bestvideo[height<=1080]+bestaudio/best[height<=1080]/best",
            label = "1080p"
        )
    ),
    P720(
        R.string.preset_720,
        DownloadSpec(
            DownloadKind.VIDEO,
            selector = "bestvideo[height<=720]+bestaudio/best[height<=720]/best",
            label = "720p"
        )
    ),
    P480(
        R.string.preset_480,
        DownloadSpec(
            DownloadKind.VIDEO,
            selector = "bestvideo[height<=480]+bestaudio/best[height<=480]/best",
            label = "480p"
        )
    ),
    P360(
        R.string.preset_360,
        DownloadSpec(
            DownloadKind.VIDEO,
            selector = "bestvideo[height<=360]+bestaudio/best[height<=360]/best",
            label = "360p"
        )
    ),
    MP3(R.string.audio_mp3, DownloadSpec(DownloadKind.AUDIO_MP3, label = "MP3")),
    M4A(R.string.audio_m4a, DownloadSpec(DownloadKind.AUDIO_M4A, label = "M4A"))
}

/** SharedPreferences-backed storage for the quick-download settings. */
object QuickSettings {

    private const val PREFS = "quick_settings"
    private const val KEY_PRESET = "quick_preset"
    private const val KEY_SHARE_OPENS_PICKER = "share_opens_picker"

    fun preset(context: Context): QuickPreset {
        val name = prefs(context).getString(KEY_PRESET, null)
        return QuickPreset.values().firstOrNull { it.name == name } ?: QuickPreset.BEST
    }

    fun setPreset(context: Context, preset: QuickPreset) {
        prefs(context).edit().putString(KEY_PRESET, preset.name).apply()
    }

    /** The spec every quick entry point enqueues with. Subtitles stay off: the picker flow covers those. */
    fun spec(context: Context): DownloadSpec = preset(context).spec

    /**
     * When ON, sharing a link keeps the v1.1 behaviour (opens InfoActivity's
     * quality picker). Default OFF: a share quick-downloads in the background.
     */
    fun shareOpensPicker(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHARE_OPENS_PICKER, false)

    fun setShareOpensPicker(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHARE_OPENS_PICKER, value).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
