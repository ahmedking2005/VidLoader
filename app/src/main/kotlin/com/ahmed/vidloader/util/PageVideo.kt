package com.ahmed.vidloader.util

import android.content.Context
import android.net.Uri
import androidx.annotation.StringRes
import com.ahmed.vidloader.R

/** A page whose URL alone identifies it as a video page on a supported site. */
data class PageVideoMatch(
    /** Human-readable site name, localised where the brand has a local form. */
    val siteName: String,
    /** The page URL to hand to the extractor. */
    val url: String
)

/**
 * Recognises video pages from their URL — YouTube watch pages, Reels, TikTok
 * videos and friends. These sites stream through URLs that carry no media
 * file extension, so request sniffing can never see them; the page URL is
 * the only reliable signal, and the extractor resolves the actual streams.
 */
object PageVideo {

    private class Rule(
        @StringRes val siteNameRes: Int = 0,
        val siteNameLiteral: String = "",
        val matches: (Uri) -> Boolean
    )

    private fun hostIs(uri: Uri, vararg domains: String): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return domains.any { host == it || host.endsWith(".$it") }
    }

    private fun firstSegment(uri: Uri): String? = uri.pathSegments.firstOrNull()

    private val RULES: List<Rule> = listOf(
        // youtube.com/watch?v=… and youtube.com/shorts/<id>
        Rule(siteNameRes = R.string.site_youtube) { uri ->
            hostIs(uri, "youtube.com") &&
                (firstSegment(uri) == "shorts" ||
                    (uri.path == "/watch" && !uri.getQueryParameter("v").isNullOrBlank()))
        },
        // youtu.be/<id>
        Rule(siteNameRes = R.string.site_youtube) { uri ->
            hostIs(uri, "youtu.be") && !firstSegment(uri).isNullOrBlank()
        },
        // instagram.com/reel/<id>, instagram.com/reels/<id>
        Rule(siteNameRes = R.string.site_instagram) { uri ->
            hostIs(uri, "instagram.com") && firstSegment(uri) in listOf("reel", "reels")
        },
        // facebook.com/watch…, facebook.com/reel/<id>
        Rule(siteNameRes = R.string.site_facebook) { uri ->
            hostIs(uri, "facebook.com") && firstSegment(uri) in listOf("watch", "reel", "reels")
        },
        // tiktok.com/@user/video/<id>
        Rule(siteNameRes = R.string.site_tiktok) { uri ->
            hostIs(uri, "tiktok.com") &&
                uri.pathSegments.getOrNull(0)?.startsWith("@") == true &&
                uri.pathSegments.getOrNull(1) == "video"
        },
        // vm.tiktok.com/<code> short links always lead to a video
        Rule(siteNameRes = R.string.site_tiktok) { uri ->
            hostIs(uri, "vm.tiktok.com", "vt.tiktok.com") && !firstSegment(uri).isNullOrBlank()
        },
        // dailymotion.com/video/<id>
        Rule(siteNameLiteral = "Dailymotion") { uri ->
            hostIs(uri, "dailymotion.com") && firstSegment(uri) == "video"
        },
        // dai.ly/<id> short links always lead to a video
        Rule(siteNameLiteral = "Dailymotion") { uri ->
            hostIs(uri, "dai.ly") && !firstSegment(uri).isNullOrBlank()
        },
        // vimeo.com/<digits>
        Rule(siteNameLiteral = "Vimeo") { uri ->
            hostIs(uri, "vimeo.com") && firstSegment(uri)?.all { it.isDigit() } == true
        },
        // ok.ru/video/<id>
        Rule(siteNameLiteral = "OK") { uri ->
            hostIs(uri, "ok.ru") && firstSegment(uri) == "video"
        },
        // x.com/<user>/status/<id> (and twitter.com) — may or may not hold a video;
        // the extractor fails gracefully when it does not.
        Rule(siteNameLiteral = "X") { uri ->
            hostIs(uri, "x.com", "twitter.com") &&
                uri.pathSegments.getOrNull(1) == "status" &&
                uri.pathSegments.getOrNull(2)?.all { it.isDigit() } == true
        }
    )

    /** Returns the match for [url], or null when it is not a known video page. */
    fun detect(context: Context, url: String?): PageVideoMatch? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in listOf("http", "https")) return null
        val rule = RULES.firstOrNull { runCatching { it.matches(uri) }.getOrDefault(false) }
        return rule?.let {
            val name = if (it.siteNameRes != 0) context.getString(it.siteNameRes) else it.siteNameLiteral
            PageVideoMatch(siteName = name, url = url)
        }
    }

    /**
     * True when [url] is hosted on YouTube (youtube.com or youtu.be, any
     * subdomain). Used to gate YouTube-only yt-dlp options (SponsorBlock)
     * so they never reach extractors that would reject them.
     */
    fun isYouTube(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase() !in listOf("http", "https")) return false
        return hostIs(uri, "youtube.com", "youtu.be")
    }
}
