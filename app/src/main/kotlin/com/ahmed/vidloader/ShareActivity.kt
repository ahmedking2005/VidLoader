package com.ahmed.vidloader

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.ahmed.vidloader.data.QuickSettings
import com.ahmed.vidloader.download.DownloadService
import com.ahmed.vidloader.util.FormatUtils

/**
 * Share-sheet trampoline: appears in Share → More as «منزّل الفيديو».
 * Default (v1.5): the shared link quick-downloads in the background at the
 * user's preset quality — no UI opens, just a «بدأ التنزيل في الخلفية» toast.
 * A playlist link becomes a quick batch (expanded in the background by the
 * service). When the Settings toggle «فتح شاشة الجودة عند المشاركة» is ON,
 * the v1.1 behaviour is kept instead: InfoActivity opens with the quality
 * picker (or playlist mode).
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

        if (QuickSettings.shareOpensPicker(this)) {
            InfoActivity.start(this, url)
        } else {
            val intent = DownloadService.startQuickPlaylistIntent(this, url, QuickSettings.spec(this))
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, R.string.quick_download_started, Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
