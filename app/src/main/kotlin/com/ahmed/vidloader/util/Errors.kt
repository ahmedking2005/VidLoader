package com.ahmed.vidloader.util

import android.content.Context
import com.ahmed.vidloader.R

/** Maps raw yt-dlp / library errors to friendly localised messages. */
fun friendlyError(context: Context, e: Throwable): String {
    val raw = (e.message ?: "").trim()
    val m = raw.lowercase()
    return when {
        m.contains("unsupported url") ->
            context.getString(R.string.err_unsupported_url)
        m.contains("private video") || m.contains("video is private") || m.contains("this video is private") ->
            context.getString(R.string.err_private_video)
        m.contains("confirm your age") || m.contains("age-restricted") || m.contains("age restricted") ->
            context.getString(R.string.err_age_restricted)
        m.contains("sign in to confirm") || m.contains("not a bot") || m.contains("captcha") ->
            context.getString(R.string.err_verification_required)
        m.contains("requested format is not available") ->
            context.getString(R.string.err_format_unavailable)
        m.contains("video unavailable") || m.contains("this video is no longer available") || m.contains("removed") ->
            context.getString(R.string.err_video_unavailable)
        m.contains("unable to download") || m.contains("timed out") || m.contains("timeout") ||
            m.contains("failed to resolve") || m.contains("network is unreachable") ||
            m.contains("connection reset") || m.contains("connection refused") ||
            m.contains("unreachable host") || m.contains("http error 5") ->
            context.getString(R.string.err_network)
        m.contains("http error 404") ->
            context.getString(R.string.err_not_found)
        m.contains("http error 403") ->
            context.getString(R.string.err_forbidden)
        m.contains("ffmpeg") ->
            context.getString(R.string.err_merge_failed)
        m.contains("engine") && m.contains("init") ->
            context.getString(R.string.err_engine_not_ready)
        raw.isBlank() -> context.getString(R.string.err_unexpected)
        else -> {
            val errorLine = raw.lineSequence()
                .firstOrNull { it.contains("ERROR", ignoreCase = true) }
                ?.substringAfter("ERROR:")?.substringAfter("ERROR")?.trim()
            if (!errorLine.isNullOrBlank()) context.getString(R.string.err_with_detail, errorLine.take(180))
            else context.getString(R.string.err_unexpected)
        }
    }
}
