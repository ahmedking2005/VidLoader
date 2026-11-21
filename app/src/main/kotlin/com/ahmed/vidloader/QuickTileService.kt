package com.ahmed.vidloader

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Quick Settings tile «تنزيل سريع» (v1.8): one tap launches the clipboard
 * trampoline ([QuickDownloadActivity]), which quick-downloads the copied
 * link in the background — no need to open the app first.
 *
 * Android 14 deprecates startActivityAndCollapse(Intent) in favour of the
 * PendingIntent variant, so the launch goes through whichever form the
 * platform supports.
 */
class QuickTileService : TileService() {

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, QuickDownloadActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
