package com.ahmed.vidloader.util

import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

object FormatUtils {

    fun humanSize(bytes: Long): String {
        if (bytes <= 0) return "غير معروف"
        val units = arrayOf("B", "KB", "MB", "GB")
        val i = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceIn(0, units.lastIndex)
        val value = bytes / 1024.0.pow(i)
        return String.format(Locale.US, "%.1f %s", value, units[i])
    }

    /** Size label for format rows; prefixes ≈ when the size is an estimate. */
    fun sizeLabel(bytes: Long, approx: Boolean = false): String {
        val label = humanSize(bytes)
        return if (approx && bytes > 0) "≈ $label" else label
    }

    /** seconds -> "12:34" or "1:02:03" */
    fun duration(totalSeconds: Double): String {
        val s = totalSeconds.toLong().coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }

    fun eta(totalSeconds: Long): String {
        if (totalSeconds < 0) return "—"
        return duration(totalSeconds.toDouble())
    }

    fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
        return cleaned.take(120).ifBlank { "video" }
    }

    private val URL_REGEX = Regex("https?://[^\\s<>\"']+")
    private val URL_TRAILING_CHARS = charArrayOf('.', ',', ')', ']', '}', '»', '"', '\'')

    /**
     * All http(s) URLs in [text], in order of appearance, de-duplicated.
     * Used for multi-link paste: one URL per line (or anywhere in the text).
     */
    fun extractUrls(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()
        val seen = LinkedHashSet<String>()
        for (match in URL_REGEX.findAll(text)) {
            seen += match.value.trimEnd(*URL_TRAILING_CHARS)
        }
        return seen.toList()
    }

    /** Extracts the first http(s) URL from arbitrary text (e.g. shared text), or null. */
    fun extractUrl(text: String?): String? = extractUrls(text).firstOrNull()
}
