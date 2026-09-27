package com.vibecollector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.vibecollector.overlay.BubbleService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restores the floating button after a reboot or an app update.
 *
 * The accessibility service is rebound by the system on its own; only the overlay
 * needs nudging, because a foreground service does not survive a reboot.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val enabled = Vibe.app.settingsStore.flow.first().bubbleEnabled
                if (enabled && BubbleService.canDrawOverlay(app)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        app.startForegroundService(Intent(app, BubbleService::class.java))
                    } else {
                        app.startService(Intent(app, BubbleService::class.java))
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
