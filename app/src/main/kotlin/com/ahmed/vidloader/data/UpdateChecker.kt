package com.ahmed.vidloader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import com.ahmed.vidloader.BuildConfig
import com.ahmed.vidloader.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The newest published build, as described by the release's version.json asset. */
data class LatestVersion(
    val versionCode: Int,
    val versionName: String,
    /** Direct APK download URL for this device's ABI. */
    val apkUrl: String,
    val notes: String
)

/**
 * Self-update check against the GitHub Releases of this app. Every release
 * carries a small version.json asset; the stable "latest" redirect always
 * points at the newest one, so no API token or release-list parsing is
 * needed. All failures (offline, 404, bad JSON) collapse to null — the
 * check is best-effort and must never disturb normal app use.
 */
object UpdateChecker {

    private const val VERSION_JSON_URL =
        "https://github.com/ahmedking2005/VidLoader/releases/latest/download/version.json"

    private const val PREFS = "update_check"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val KEY_DISMISSED_VERSION_CODE = "dismissed_version_code"

    /** The automatic start-up check runs at most once per this interval. */
    private const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    /** Fetches and parses version.json; null on ANY failure. Never throws. */
    suspend fun fetchLatest(): LatestVersion? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(VERSION_JSON_URL).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "VidLoader/" + BuildConfig.VERSION_NAME)
                setRequestProperty("Accept", "application/json")
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            parse(text)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parse(text: String): LatestVersion? {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            return null
        }
        val versionCode = json.optInt("versionCode", -1)
        if (versionCode <= 0) return null
        val arm64Url = json.optString("arm64Url")
        val v7aUrl = json.optString("v7aUrl")
        val isArm64 = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"
        var apkUrl = if (isArm64) arm64Url else v7aUrl
        if (apkUrl.isBlank()) apkUrl = if (isArm64) v7aUrl else arm64Url
        if (apkUrl.isBlank()) return null
        return LatestVersion(
            versionCode = versionCode,
            versionName = json.optString("versionName"),
            apkUrl = apkUrl,
            notes = json.optString("notes")
        )
    }

    fun installedVersionCode(context: Context): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        PackageInfoCompat.getLongVersionCode(info).toInt()
    } catch (e: Exception) {
        BuildConfig.VERSION_CODE
    }

    fun installedVersionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: BuildConfig.VERSION_NAME
    } catch (e: Exception) {
        BuildConfig.VERSION_NAME
    }

    /** True when the automatic check is due (last run at least 24h ago). */
    fun shouldAutoCheck(context: Context): Boolean {
        val last = prefs(context).getLong(KEY_LAST_CHECK_MS, 0L)
        return System.currentTimeMillis() - last >= AUTO_CHECK_INTERVAL_MS
    }

    /**
     * Stamps the throttle BEFORE the fetch starts, so a configuration-change
     * recreation (or any second onCreate in the same cold start) cannot
     * launch a second automatic check.
     */
    fun markAutoCheckStarted(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis()).apply()
    }

    /** True when the user already tapped «لاحقًا» for exactly this remote version. */
    fun isDismissed(context: Context, versionCode: Int): Boolean =
        prefs(context).getInt(KEY_DISMISSED_VERSION_CODE, -1) == versionCode

    fun markDismissed(context: Context, versionCode: Int) {
        prefs(context).edit().putInt(KEY_DISMISSED_VERSION_CODE, versionCode).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** The «يتوفر إصدار جديد» prompt, shared by the automatic and manual checks. */
object UpdatePrompt {

    fun show(context: Context, latest: LatestVersion) {
        val message = buildString {
            append(
                context.getString(
                    R.string.update_available_body,
                    latest.versionName,
                    UpdateChecker.installedVersionName(context)
                )
            )
            if (latest.notes.isNotBlank()) {
                append("\n\n")
                append(latest.notes)
            }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_available_title)
            .setMessage(message)
            .setPositiveButton(R.string.download_now) { _, _ ->
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(latest.apkUrl)))
                } catch (e: Exception) {
                    Toast.makeText(context, R.string.error_no_app_to_open, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.later) { _, _ ->
                UpdateChecker.markDismissed(context, latest.versionCode)
            }
            .show()
    }
}
