package com.ahmed.vidloader

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.ahmed.vidloader.util.FormatUtils

/**
 * Share-sheet trampoline: appears in Share → More as «منزّل الفيديو».
 * Forwards the shared URL straight into InfoActivity (which auto-fetches and
 * shows the quality picker, or playlist mode for playlists), then finishes —
 * no UI of its own, no silent background download.
 */
class ShareActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = when (intent?.action) {
            Intent.ACTION_SEND -> FormatUtils.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
            Intent.ACTION_VIEW -> FormatUtils.extractUrl(intent.dataString) ?: intent.dataString
            else -> null
        }

        if (url.isNullOrBlank()) {
            Toast.makeText(this, R.string.error_invalid_url, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        InfoActivity.start(this, url)
        finish()
    }
}
