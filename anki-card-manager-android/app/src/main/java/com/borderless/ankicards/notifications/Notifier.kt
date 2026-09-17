package com.borderless.ankicards.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.borderless.ankicards.MainActivity
import com.borderless.ankicards.R

/**
 * Posts and clears notifications for queued cards.
 *
 * On Android 13+ posting requires runtime POST_NOTIFICATIONS permission. If the
 * user denied it, we silently no-op rather than crashing — the in-app inbox
 * still updates either way.
 */
class Notifier(private val context: Context) {

    init {
        ensureChannel()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Card generation",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Tells you when a queued card has finished generating."
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    fun notifyCardReady(itemId: String, word: String, hasWarning: Boolean) {
        if (!canPost()) return

        val title = if (hasWarning) "Card ready (with warning)" else "Card ready"
        val text = "\"$word\" is queued for review. Tap to open the inbox."

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppPendingIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(idFor(itemId), notification)
    }

    fun notifyCardFailed(itemId: String, word: String, errorMessage: String) {
        if (!canPost()) return

        val text = "\"$word\" — $errorMessage. Open the inbox to retry."
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Card generation failed")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppPendingIntent())
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(idFor(itemId), notification)
    }

    fun cancel(itemId: String) {
        NotificationManagerCompat.from(context).cancel(idFor(itemId))
    }

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAVIGATE_TO, MainActivity.DEST_INBOX)
        }
        return PendingIntent.getActivity(
            context,
            /* requestCode = */ 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun idFor(itemId: String): Int = itemId.hashCode()

    companion object {
        private const val CHANNEL_ID = "card_generation"
    }
}
