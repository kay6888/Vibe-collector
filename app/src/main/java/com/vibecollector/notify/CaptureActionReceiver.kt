package com.vibecollector.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.vibecollector.Vibe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles Save / Discard / Save all straight from a notification, with no need to
 * open the app. Runs its work on an application-scoped coroutine so it completes
 * even if the receiver is torn down immediately after onReceive returns.
 */
class CaptureActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val vibe = Vibe.get()
        val coordinator = vibe.captureCoordinator
        val pendingScope = CoroutineScope(Dispatchers.IO)

        when (intent.action) {
            ACTION_SAVE -> {
                val capture = coordinator.find(id)
                if (capture == null) {
                    CaptureNotifier.showResult(context, "Already handled", "That capture was saved or discarded.")
                    return
                }
                if (capture.hasUnnamed) {
                    // A guessed filename is worth one confirmation, so send the user
                    // to the rename screen rather than writing snippet-3.txt silently.
                    openCapture(context, id)
                    return
                }
                pendingScope.launch {
                    val report = coordinator.save(id)
                    withMain {
                        CaptureNotifier.showResult(
                            context,
                            if (report.ok) "Saved" else "Could not save",
                            report.summary,
                        )
                    }
                }
            }

            ACTION_DISCARD -> {
                coordinator.remove(id)
                CaptureNotifier.showResult(context, "Discarded", "The capture was not written to disk.")
            }

            ACTION_SAVE_ALL -> {
                pendingScope.launch {
                    val reports = coordinator.saveAll()
                    withMain {
                        val ok = reports.count { it.ok }
                        CaptureNotifier.showResult(
                            context,
                            "Saved $ok capture${if (ok == 1) "" else "s"}",
                            reports.joinToString("\n") { it.summary },
                        )
                    }
                }
            }
        }
    }

    private fun withMain(block: () -> Unit) {
        val app = Vibe.get().app
        app.mainHandler.post {
            runCatching(block)
        }
    }

    private fun openCapture(context: Context, id: String) {
        context.startActivity(
            Intent(context, com.vibecollector.MainActivity::class.java).apply {
                action = com.vibecollector.MainActivity.ACTION_OPEN_CAPTURE
                putExtra(com.vibecollector.MainActivity.EXTRA_CAPTURE_ID, id)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        )
    }

    @Suppress("unused")
    private fun unusedToast(context: Context, msg: String) =
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val ACTION_SAVE = "com.vibecollector.action.SAVE"
        const val ACTION_DISCARD = "com.vibecollector.action.DISCARD"
        const val ACTION_SAVE_ALL = "com.vibecollector.action.SAVE_ALL"
        const val EXTRA_ID = "capture_id"
    }
}
