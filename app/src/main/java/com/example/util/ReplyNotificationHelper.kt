package com.example.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.heartbeat.HeartbeatAlarmReceiver

/**
 * Dispatches system notifications for incoming Gemini model replies in the active conversation
 * when the setting is enabled and the app is in the background or the device screen is off.
 */
object ReplyNotificationHelper {

    const val CHANNEL_ID = "gemini_active_reply_channel"
    private const val CHANNEL_NAME = "Active Conversation Replies"
    private const val BASE_NOTIFICATION_ID = 41000

    /**
     * Checks whether a notification should be posted based on user setting, app foreground state,
     * and screen interactivity.
     */
    fun shouldNotifyForActiveReply(
        notifySettingEnabled: Boolean,
        isAppInForeground: Boolean,
        isScreenInteractive: Boolean
    ): Boolean {
        if (!notifySettingEnabled) return false
        return !isAppInForeground || !isScreenInteractive
    }

    fun isScreenInteractive(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isInteractive ?: true
        } catch (_: Exception) {
            true
        }
    }

    fun notifyModelReplyIfBackgroundedOrScreenOff(
        context: Context,
        notifySettingEnabled: Boolean,
        isAppInForeground: Boolean,
        chatId: Long,
        chatTitle: String,
        replyText: String
    ): Boolean {
        val interactive = isScreenInteractive(context)
        if (!shouldNotifyForActiveReply(notifySettingEnabled, isAppInForeground, interactive)) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }

        ensureNotificationChannel(context)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(HeartbeatAlarmReceiver.EXTRA_TARGET_CHAT_ID, chatId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            (BASE_NOTIFICATION_ID + (chatId % 1000).toInt()),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val preview = replyText.trim().let {
            if (it.length > 180) it.take(180) + "…" else it
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Gemini replied in $chatTitle")
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        return try {
            NotificationManagerCompat.from(context)
                .notify(BASE_NOTIFICATION_ID + (chatId % 1000).toInt(), notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifications when Gemini replies in the active conversation while backgrounded or screen is off"
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}
