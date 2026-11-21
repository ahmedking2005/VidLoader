package com.ahmed.vidloader

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.ahmed.vidloader.data.QuickSettings
import com.ahmed.vidloader.download.DownloadService
import com.ahmed.vidloader.util.FormatUtils

/**
 * Clipboard trampoline behind the Quick Settings tile and the home-screen
 * app shortcut (v1.8). Since Android 10 an app may read the clipboard only
 * while it holds window focus, so this transparent activity briefly comes to
 * the foreground, reads the clip once focus lands, and acts:
 *
 * - one or more URLs → quick-downloaded in the background through the exact
 *   entry points the Home quick button uses (a single link goes through
 *   [DownloadService.startQuickPlaylistIntent] so a playlist link still
 *   becomes a background batch; several links are queued one by one through
 *   [DownloadService.startQuickIntent] at the user's quick preset);
 * - no URL → opens MainActivity so the user can paste the link manually.
 */
class QuickDownloadActivity : Activity() {

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nothing to draw: the trampoline theme is fully transparent. The
        // clipboard is read in onWindowFocusChanged — before focus arrives
        // the read would silently come back empty on Android 10+.
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !handled) {
            handled = true
            handleClipboard()
        }
    }

    private fun handleClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
        val urls = FormatUtils.extractUrls(text)

        if (urls.isEmpty()) {
            Toast.makeText(this, R.string.copy_link_first, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java))
        } else {
            val spec = QuickSettings.spec(this)
            if (urls.size == 1) {
                ContextCompat.startForegroundService(
                    this, DownloadService.startQuickPlaylistIntent(this, urls[0], spec)
                )
            } else {
                for (url in urls) {
                    ContextCompat.startForegroundService(
                        this, DownloadService.startQuickIntent(this, url, spec)
                    )
                }
            }
            Toast.makeText(this, R.string.quick_download_started, Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
