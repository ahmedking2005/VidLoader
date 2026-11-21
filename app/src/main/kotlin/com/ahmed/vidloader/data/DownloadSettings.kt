package com.ahmed.vidloader.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.appcompat.app.AppCompatDelegate
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/**
 * SharedPreferences-backed «إعدادات التنزيل» (download settings) plus the app
 * theme choice. Everything lives in one prefs file, `download_settings`:
 * download path (SAF tree), parallel-task limits per network type, the
 * yt-dlp rate limit, the mobile-data master switch and the theme mode.
 */
object DownloadSettings {

    private const val PREFS = "download_settings"
    private const val KEY_TREE_URI = "tree_uri"
    private const val KEY_LIMIT_WIFI = "limit_wifi"
    private const val KEY_LIMIT_MOBILE = "limit_mobile"
    private const val KEY_RATE_LIMIT = "rate_limit"
    private const val KEY_MOBILE_DATA = "mobile_data"
    private const val KEY_THEME = "theme"
    private const val KEY_SPONSOR_BLOCK = "sponsor_block"
    private const val KEY_COOKIES_NAME = "cookies_name"

    const val THEME_SYSTEM = "system"
    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"

    /** yt-dlp --limit-rate choices; "" means unlimited. The order is the dialog order. */
    val RATE_OPTIONS = listOf("", "256K", "512K", "1M", "2M", "5M", "10M")

    /** Selectable parallel-task limits (1..5). */
    val LIMIT_OPTIONS = listOf(1, 2, 3, 4, 5)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // region download path

    /** The user-chosen download folder (SAF tree uri), or null for the default Downloads/VidLoader. */
    fun treeUri(context: Context): Uri? =
        prefs(context).getString(KEY_TREE_URI, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { Uri.parse(it) }

    fun setTreeUri(context: Context, uri: Uri?) {
        val editor = prefs(context).edit()
        if (uri == null) editor.remove(KEY_TREE_URI) else editor.putString(KEY_TREE_URI, uri.toString())
        editor.apply()
    }

    // endregion

    // region parallel limits

    fun wifiLimit(context: Context): Int =
        prefs(context).getInt(KEY_LIMIT_WIFI, 2).coerceIn(1, 5)

    fun setWifiLimit(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_LIMIT_WIFI, value.coerceIn(1, 5)).apply()
    }

    fun mobileLimit(context: Context): Int =
        prefs(context).getInt(KEY_LIMIT_MOBILE, 1).coerceIn(1, 5)

    fun setMobileLimit(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_LIMIT_MOBILE, value.coerceIn(1, 5)).apply()
    }

    // endregion

    // region rate limit

    /** yt-dlp --limit-rate value, or "" for unlimited. */
    fun rateLimit(context: Context): String =
        prefs(context).getString(KEY_RATE_LIMIT, "") ?: ""

    fun setRateLimit(context: Context, value: String) {
        prefs(context).edit().putString(KEY_RATE_LIMIT, value).apply()
    }

    // endregion

    // region mobile data

    /** When false, jobs never START on mobile data — they wait for Wi-Fi. Default true (v1.5 behaviour). */
    fun mobileDataAllowed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MOBILE_DATA, true)

    fun setMobileDataAllowed(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_MOBILE_DATA, value).apply()
    }

    // endregion

    // region theme

    fun theme(context: Context): String =
        prefs(context).getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM

    fun setTheme(context: Context, value: String) {
        prefs(context).edit().putString(KEY_THEME, value).apply()
        AppCompatDelegate.setDefaultNightMode(nightMode(value))
    }

    fun nightMode(theme: String): Int = when (theme) {
        THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /** Applies the persisted theme; called once at app start. */
    fun applyTheme(context: Context) {
        AppCompatDelegate.setDefaultNightMode(nightMode(theme(context)))
    }

    // endregion

    // region SponsorBlock

    /**
     * When true, YouTube downloads also remove sponsor/self-promo/intro/
     * outro/interaction segments via SponsorBlock. Default OFF. The flag is
     * added per request by the service — YouTube URLs only (see PageVideo).
     */
    fun sponsorBlock(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPONSOR_BLOCK, false)

    fun setSponsorBlock(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPONSOR_BLOCK, value).apply()
    }

    // endregion

    // region cookies file

    /** Where the imported cookies file lives (internal storage — never leaves the device). */
    fun cookiesFile(context: Context): File = File(context.filesDir, "cookies.txt")

    /** Absolute path of the imported cookies file, or null when none is set. */
    fun cookiesPath(context: Context): String? {
        val file = cookiesFile(context)
        return if (file.isFile && file.length() > 0) file.absolutePath else null
    }

    /** Display name of the imported cookies file as shown in settings, or null when none is set. */
    fun cookiesName(context: Context): String? =
        prefs(context).getString(KEY_COOKIES_NAME, null)?.takeIf { cookiesPath(context) != null }

    /**
     * Copies the picked document into internal storage as the cookies file.
     * Returns the file's display name, or null when the copy failed.
     */
    fun importCookies(context: Context, uri: Uri): String? {
        val name = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "cookies.txt"
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { stream ->
                cookiesFile(context).outputStream().use { stream.copyTo(it) }
            }
            if (cookiesPath(context) == null) return null
            prefs(context).edit().putString(KEY_COOKIES_NAME, name).apply()
            name
        } catch (e: Exception) {
            null
        }
    }

    /** Deletes the imported cookies file (if any) and forgets its name. */
    fun clearCookies(context: Context) {
        runCatching { cookiesFile(context).delete() }
        prefs(context).edit().remove(KEY_COOKIES_NAME).apply()
    }

    /**
     * Adds `--cookies <path>` to [request] when a cookies file is set. Every
     * request in the app is built fresh and passes through here exactly once,
     * so the option can never be duplicated on one request.
     */
    fun applyCookies(context: Context, request: YoutubeDLRequest) {
        cookiesPath(context)?.let { request.addOption("--cookies", it) }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else {
                        null
                    }
                }
        } catch (e: Exception) {
            null
        }
    }

    // endregion
}
