package com.vibecollector.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vibecollector.MainActivity
import com.vibecollector.R
import com.vibecollector.Vibe
import com.vibecollector.data.PendingCapture
import com.vibecollector.data.VibeSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * System notifications for captures.
 *
 * Every notification carries Save / Discard actions as [PendingIntent]s, so the
 * code can be accepted or rejected without opening the app.
 */
object CaptureNotifier {

    const val CHANNEL_CAPTURE = "vibe_capture"
    const val CHANNEL_SERVICE = "vibe_service"

    private const val RESULT_ID = 4201
    private const val CAPTURE_TAG = "vibe-capture:"
    private const val RESULT_TAG = "vibe-capture-result"

    private fun captureNotificationId(captureId: String): Int = (captureId.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_CAPTURE) == null) {
            val ch = NotificationChannel(
                CHANNEL_CAPTURE,
                context.getString(R.string.notification_channel_capture),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notification_channel_capture_desc)
                enableVibration(true)
                setShowBadge(true)
            }
            nm.createNotificationChannel(ch)
        }
        if (nm.getNotificationChannel(CHANNEL_SERVICE) == null) {
            val ch = NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.notification_channel_service),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = context.getString(R.string.notification_channel_service_desc)
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    fun canPost(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.getSystemService(NotificationManager::class.java)
                ?.getNotificationChannel(CHANNEL_CAPTURE)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    /** Prompt the user to save a capture. Does nothing if notifications are off. */
    fun showCapturePrompt(context: Context, capture: PendingCapture, settings: VibeSettings) {
        if (!settings.notificationsEnabled) return
        ensureChannels(context)

        val open = PendingIntent.getActivity(
            context,
            capture.id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_CAPTURE
                data = Uri.parse("vibe-collector://open-capture/${capture.id}")
                putExtra(MainActivity.EXTRA_CAPTURE_ID, capture.id)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val save = actionIntent(context, CaptureActionReceiver.ACTION_SAVE, capture.id, capture.id.hashCode() xor 0x51A7)
        val discard = actionIntent(context, CaptureActionReceiver.ACTION_DISCARD, capture.id, capture.id.hashCode() xor 0xD15C)
        val saveAll = if (Vibe.get().captureCoordinator.count() > 1 && Vibe.get().captureCoordinator.list().none { it.hasUnnamed }) {
            actionIntent(context, CaptureActionReceiver.ACTION_SAVE_ALL, "", 3)
        } else {
            null
        }

        val content = if (settings.hideNotificationPreview) {
            "${capture.fileCount} code file(s) ready"
        } else when {
            capture.isScaffoldOnly -> "Created ${capture.scaffoldedDirs} folders and ${capture.scaffoldedFiles} files in ${capture.project}"
            capture.fileCount == 1 -> "${capture.files.first().path}  •  ${capture.files.first().lineCount} lines"
            else -> "${capture.fileCount} files  •  ${capture.sourceLabel.ifBlank { "clipboard" }}"
        }

        val details = if (settings.hideNotificationPreview) content else buildString {
            append(content)
            append("\n\n")
            capture.files.take(4).forEach { append("• ").append(it.path).append('\n') }
            if (capture.files.size > 4) append("… and ${capture.files.size - 4} more")
            if (capture.hasUnnamed) append("\nSome filenames were guessed — tap to confirm before saving.")
        }
        val style = NotificationCompat.BigTextStyle().bigText(details)

        val builder = NotificationCompat.Builder(context, CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_vibe)
            .setContentTitle(if (settings.hideNotificationPreview) "Code capture ready" else "Save code from ${capture.sourceLabel.ifBlank { "clipboard" }}?")
            .setContentText(content)
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(0, "Save", save)
            .addAction(0, "Discard", discard)
            .also { if (saveAll != null) it.addAction(0, "Save all", saveAll) }
            .addAction(0, "Open", open)

        post(context, captureNotificationId(capture.id), builder.build(), CAPTURE_TAG + capture.id)
    }

    /** Confirmation after a save or discard. */
    fun showResult(context: Context, title: String, text: String) {
        val appContext = context.applicationContext
        Vibe.app.appScope.launch {
            val settings = Vibe.settings.flow.first()
            if (!settings.notificationsEnabled) return@launch
            postResult(appContext, title, if (settings.hideNotificationPreview) "Capture action completed." else text)
        }
    }

    private fun postResult(context: Context, title: String, text: String) {
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context,
            title.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_CAPTURE)
            .setSmallIcon(R.drawable.ic_stat_vibe)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        post(context, RESULT_ID, n, RESULT_TAG)
    }

    fun clearCapturePrompt(context: Context, captureId: String) {
        NotificationManagerCompat.from(context).cancel(CAPTURE_TAG + captureId, captureNotificationId(captureId))
    }

    fun clearCaptureNotifications(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.activeNotifications
            .filter { it.tag?.startsWith(CAPTURE_TAG) == true || it.tag == RESULT_TAG }
            .forEach { manager.cancel(it.tag, it.id) }
    }

    fun serviceNotification(context: Context, text: String): Notification {
        ensureChannels(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_vibe)
            .setContentTitle("vibe-collector is watching")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun actionIntent(context: Context, action: String, id: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, CaptureActionReceiver::class.java).apply {
            this.action = action
            putExtra(CaptureActionReceiver.EXTRA_ID, id)
            data = Uri.parse("vibe-collector://capture/$id/$action")
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun post(context: Context, id: Int, notification: Notification, tag: String? = null) {
        if (!canPost(context)) return
        runCatching {
            if (tag == null) NotificationManagerCompat.from(context).notify(id, notification)
            else NotificationManagerCompat.from(context).notify(tag, id, notification)
        }
    }
}
