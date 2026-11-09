package com.example.heartbeat

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.coupler.ChromeBridgeSocketManager

/**
 * HeartbeatAlarmReceiver provides low-power background heartbeat maintenance and
 * timer/alarm interception when the application is backgrounded or minimized.
 *
 * It routes timer expirations into actionable heads-up notifications with one-tap
 * navigation directly back to the active chat session context.
 */
class HeartbeatAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d(TAG, "HeartbeatAlarmReceiver received action: $action")

        when (action) {
            ACTION_HEARTBEAT_PING -> {
                handleHeartbeatPing(context)
            }
            ACTION_TIMER_ALARM_EXPIRATION -> {
                handleTimerAlarmExpiration(context, intent)
            }
        }
    }

    private fun handleHeartbeatPing(context: Context) {
        Log.i(TAG, "Low-power heartbeat ping received. Re-verifying bridge state...")
        ChromeBridgeSocketManager.recordHeartbeatPing()

        // Re-arm next ping if still in background heartbeat mode
        if (ChromeBridgeSocketManager.bridgeState.value.isHeartbeatMode) {
            scheduleHeartbeatPing(context, HEARTBEAT_INTERVAL_MS)
        }
    }

    private fun handleTimerAlarmExpiration(context: Context, intent: Intent) {
        val chatId = intent.getLongExtra(EXTRA_TARGET_CHAT_ID, -1L)
        val chatTitle = intent.getStringExtra(EXTRA_CHAT_TITLE) ?: "Active Chat"
        val customMessage = intent.getStringExtra(EXTRA_ALARM_MESSAGE)
        val timerSeconds = intent.getLongExtra(EXTRA_TIMER_SECONDS, 0L)

        Log.i(TAG, "Timer/Alarm expired for chatId=$chatId ($chatTitle)")

        // Post actionable heads-up notification with one-tap deep link
        postTimerAlarmNotification(context, chatId, chatTitle, customMessage, timerSeconds)
    }

    private fun postTimerAlarmNotification(
        context: Context,
        chatId: Long,
        chatTitle: String,
        customMessage: String?,
        timerSeconds: Long
    ) {
        createNotificationChannel(context)

        // Launch Intent to jump directly back to the chat session
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_TIMER_ALARM_EXPIRATION
            putExtra(EXTRA_TARGET_CHAT_ID, chatId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }

        val requestCode = if (chatId > 0) chatId.toInt() else 1001
        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val titleText = "⏰ Timer Expired: $chatTitle"
        val bodyText = customMessage ?: if (timerSeconds > 0) {
            val mins = timerSeconds / 60
            val secs = timerSeconds % 60
            "Your ${mins}m ${secs}s session timer completed. Tap to resume chat."
        } else {
            "Your scheduled session timer completed. Tap to resume chat."
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(titleText)
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .addAction(
                android.R.drawable.ic_menu_send,
                "Open Chat",
                pendingIntent
            )
            .build()

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            val notificationId = if (chatId > 0) chatId.toInt() else NOTIFICATION_ID_BASE
            notificationManager.notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing POST_NOTIFICATIONS permission to display heads-up alert: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error posting timer expiration notification: ${e.message}", e)
        }
    }

    companion object {
        const val TAG = "HeartbeatAlarmReceiver"
        const val CHANNEL_ID = "gemini_wake_heartbeat_alarms"
        const val CHANNEL_NAME = "Gemini Wake Timer & Alarms"

        const val ACTION_HEARTBEAT_PING = "com.example.action.HEARTBEAT_PING"
        const val ACTION_TIMER_ALARM_EXPIRATION = "com.example.action.TIMER_ALARM_EXPIRATION"

        const val EXTRA_TARGET_CHAT_ID = "com.example.extra.TARGET_CHAT_ID"
        const val EXTRA_CHAT_TITLE = "com.example.extra.CHAT_TITLE"
        const val EXTRA_ALARM_MESSAGE = "com.example.extra.ALARM_MESSAGE"
        const val EXTRA_TIMER_SECONDS = "com.example.extra.TIMER_SECONDS"

        private const val NOTIFICATION_ID_BASE = 4200
        const val HEARTBEAT_INTERVAL_MS = 60_000L // 1 minute interval

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                val existingChannel = notificationManager?.getNotificationChannel(CHANNEL_ID)
                if (existingChannel == null) {
                    val channel = NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = "Actionable heads-up alerts for chat timers and background heartbeat pings."
                        enableVibration(true)
                        enableLights(true)
                    }
                    notificationManager?.createNotificationChannel(channel)
                }
            }
        }

        fun scheduleHeartbeatPing(context: Context, delayMillis: Long = HEARTBEAT_INTERVAL_MS) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, HeartbeatAlarmReceiver::class.java).apply {
                action = ACTION_HEARTBEAT_PING
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                8801,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerAtMillis = SystemClock.elapsedRealtime() + delayMillis
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
                }
                Log.d(TAG, "Scheduled heartbeat ping in ${delayMillis / 1000}s")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule heartbeat ping: ${e.message}")
            }
        }

        fun cancelHeartbeatPing(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, HeartbeatAlarmReceiver::class.java).apply {
                action = ACTION_HEARTBEAT_PING
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                8801,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
            Log.d(TAG, "Heartbeat ping cancelled")
        }

        fun scheduleChatTimerAlarm(
            context: Context,
            chatId: Long,
            chatTitle: String,
            delaySeconds: Long,
            alarmMessage: String? = null
        ) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, HeartbeatAlarmReceiver::class.java).apply {
                action = ACTION_TIMER_ALARM_EXPIRATION
                putExtra(EXTRA_TARGET_CHAT_ID, chatId)
                putExtra(EXTRA_CHAT_TITLE, chatTitle)
                putExtra(EXTRA_TIMER_SECONDS, delaySeconds)
                alarmMessage?.let { putExtra(EXTRA_ALARM_MESSAGE, it) }
            }

            val requestCode = (9000 + chatId).toInt()
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val triggerAtMillis = SystemClock.elapsedRealtime() + (delaySeconds * 1000L)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
                }
                Log.i(TAG, "Scheduled chat timer alarm for chatId=$chatId in ${delaySeconds}s")
            } catch (_: SecurityException) {
                // If exact alarm permission is restricted on Android 12+, fallback to inexact idle
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to schedule chat timer alarm: ${e.message}")
            }
        }

        fun cancelChatTimerAlarm(context: Context, chatId: Long) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, HeartbeatAlarmReceiver::class.java).apply {
                action = ACTION_TIMER_ALARM_EXPIRATION
            }
            val requestCode = (9000 + chatId).toInt()
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                requestCode,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
                Log.d(TAG, "Cancelled chat timer alarm for chatId=$chatId")
            }
        }
    }
}
