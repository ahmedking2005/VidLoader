package com.ahmed.vidloader.data

import android.content.Context
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate

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
}
