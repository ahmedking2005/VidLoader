package com.ahmed.vidloader.data

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject

data class PlaylistEntry(
    /** 1-based position inside the playlist (used with yt-dlp --playlist-items). */
    val index: Int,
    val title: String,
    val durationSeconds: Long,
    var selected: Boolean = true
)

data class PlaylistInfo(
    val title: String,
    val entries: List<PlaylistEntry>
)

/**
 * Detects and lists playlists. The library's VideoInfo mapper has no playlist
 * fields, so this runs yt-dlp with --flat-playlist -J and parses the raw JSON.
 * Returns null when [url] resolves to a single video (or anything that is not
 * a playlist with entries).
 */
object PlaylistFetcher {

    fun fetch(context: Context, url: String): PlaylistInfo? {
        val request = YoutubeDLRequest(url)
        request.addOption("--flat-playlist")
        request.addOption("-J")
        // The user's optional cookies file applies here too, so playlists
        // behind a login resolve the same way downloads do.
        DownloadSettings.applyCookies(context, request)
        val response = YoutubeDL.getInstance().execute(request)
        val out = response.out?.trim().orEmpty()
        if (out.isEmpty()) return null

        val root = JSONObject(out)
        if (root.optString("_type") != "playlist") return null
        val entriesJson = root.optJSONArray("entries") ?: return null

        val entries = mutableListOf<PlaylistEntry>()
        for (i in 0 until entriesJson.length()) {
            val e = entriesJson.optJSONObject(i) ?: continue
            val title = e.optString("title").ifBlank { e.optString("id") }
            val duration = e.optDouble("duration", 0.0).toLong()
            entries += PlaylistEntry(index = i + 1, title = title, durationSeconds = duration)
        }
        if (entries.isEmpty()) return null
        return PlaylistInfo(title = root.optString("title"), entries = entries)
    }
}
