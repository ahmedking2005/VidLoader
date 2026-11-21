package com.ahmed.vidloader.util

import android.net.Uri

/** A page whose URL alone identifies it as a video page on a supported site. */
data class PageVideoMatch(
    /** Human-readable site name (Arabic where natural). */
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

    private class Rule(val siteName: String, val matches: (Uri) -> Boolean)

    private fun hostIs(uri: Uri, vararg domains: String): Boolean {
        val host = uri.host?.lowercase() ?: return false
        return domains.any { host == it || host.endsWith(".$it") }
    }

    private fun firstSegment(uri: Uri): String? = uri.pathSegments.firstOrNull()

    private val RULES: List<Rule> = listOf(
        // youtube.com/watch?v=… and youtube.com/shorts/<id>
        Rule("يوتيوب") { uri ->
            hostIs(uri, "youtube.com") &&
                (firstSegment(uri) == "shorts" ||
                    (uri.path == "/watch" && !uri.getQueryParameter("v").isNullOrBlank()))
        },
        // youtu.be/<id>
        Rule("يوتيوب") { uri ->
            hostIs(uri, "youtu.be") && !firstSegment(uri).isNullOrBlank()
        },
        // instagram.com/reel/<id>, instagram.com/reels/<id>
        Rule("إنستجرام") { uri ->
            hostIs(uri, "instagram.com") && firstSegment(uri) in listOf("reel", "reels")
        },
        // facebook.com/watch…, facebook.com/reel/<id>
        Rule("فيسبوك") { uri ->
            hostIs(uri, "facebook.com") && firstSegment(uri) in listOf("watch", "reel", "reels")
        },
        // tiktok.com/@user/video/<id>
        Rule("تيك توك") { uri ->
            hostIs(uri, "tiktok.com") &&
                uri.pathSegments.getOrNull(0)?.startsWith("@") == true &&
                uri.pathSegments.getOrNull(1) == "video"
        },
        // vm.tiktok.com/<code> short links always lead to a video
        Rule("تيك توك") { uri ->
            hostIs(uri, "vm.tiktok.com", "vt.tiktok.com") && !firstSegment(uri).isNullOrBlank()
        },
        // dailymotion.com/video/<id>
        Rule("Dailymotion") { uri ->
            hostIs(uri, "dailymotion.com") && firstSegment(uri) == "video"
        },
        // dai.ly/<id> short links always lead to a video
        Rule("Dailymotion") { uri ->
            hostIs(uri, "dai.ly") && !firstSegment(uri).isNullOrBlank()
        },
        // vimeo.com/<digits>
        Rule("Vimeo") { uri ->
            hostIs(uri, "vimeo.com") && firstSegment(uri)?.all { it.isDigit() } == true
        },
        // ok.ru/video/<id>
        Rule("OK") { uri ->
            hostIs(uri, "ok.ru") && firstSegment(uri) == "video"
        },
        // x.com/<user>/status/<id> (and twitter.com) — may or may not hold a video;
        // the extractor fails gracefully when it does not.
        Rule("X") { uri ->
            hostIs(uri, "x.com", "twitter.com") &&
                uri.pathSegments.getOrNull(1) == "status" &&
                uri.pathSegments.getOrNull(2)?.all { it.isDigit() } == true
        }
    )

    /** Returns the match for [url], or null when it is not a known video page. */
    fun detect(url: String?): PageVideoMatch? {
        if (url.isNullOrBlank()) return null
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in listOf("http", "https")) return null
        val rule = RULES.firstOrNull { runCatching { it.matches(uri) }.getOrDefault(false) }
        return rule?.let { PageVideoMatch(siteName = it.siteName, url = url) }
    }
}
