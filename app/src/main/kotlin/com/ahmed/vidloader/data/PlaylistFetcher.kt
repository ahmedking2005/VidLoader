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

/** One flat-playlist entry with a stable id and a downloadable URL (v1.10, monitoring). */
data class FlatEntry(
    val id: String,
    val title: String,
    val url: String
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

    /**
     * Flat entries (id + title + URL) for a channel or playlist URL — the
     * monitoring worker's view. Same --flat-playlist -J call as [fetch], but
     * it does NOT require the root to be a plain playlist with a non-empty
     * entry list the same way: it returns whatever entries carry an id and a
     * URL, or null when the fetch itself failed / yielded nothing usable.
     */
    fun fetchFlatEntries(context: Context, url: String): List<FlatEntry>? {
        val request = YoutubeDLRequest(url)
        request.addOption("--flat-playlist")
        request.addOption("-J")
        DownloadSettings.applyCookies(context, request)
        val response = YoutubeDL.getInstance().execute(request)
        val out = response.out?.trim().orEmpty()
        if (out.isEmpty()) return null

        val root = JSONObject(out)
        val entriesJson = root.optJSONArray("entries") ?: return null
        val entries = mutableListOf<FlatEntry>()
        for (i in 0 until entriesJson.length()) {
            val e = entriesJson.optJSONObject(i) ?: continue
            val rawUrl = e.optString("url")
            val webpageUrl = e.optString("webpage_url")
            val entryId = e.optString("id").ifBlank { webpageUrl.ifBlank { rawUrl } }
            val entryUrl = when {
                webpageUrl.startsWith("http") -> webpageUrl
                rawUrl.startsWith("http") -> rawUrl
                entryId.isNotBlank() && e.optString("ie_key") == "Youtube" ->
                    "https://www.youtube.com/watch?v=$entryId"
                rawUrl.isNotBlank() -> rawUrl
                else -> ""
            }
            if (entryId.isBlank() || entryUrl.isBlank()) continue
            entries += FlatEntry(
                id = entryId,
                title = e.optString("title").ifBlank { entryId },
                url = entryUrl
            )
        }
        return entries
    }
}
