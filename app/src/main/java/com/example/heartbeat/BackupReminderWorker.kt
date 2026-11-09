package com.example.heartbeat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.data.db.AppDatabase
import com.example.util.AppSettingsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private const val TAG = "BackupReminderWorker"

/**
 * Periodic [CoroutineWorker] that checks whether the user has not exported a JSON or CSV backup
 * in over 30 days (`AppSettingsManager.BACKUP_REMINDER_THRESHOLD_DAYS`) and posts a local
 * reminder notification that deep-links into the app's Backup Export dialog.
 */
class BackupReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val context = applicationContext
            AppSettingsManager.ensureInitialized(context)

            val db = AppDatabase.getDatabase(context)
            val activeChats = db.chatDao().getAllChatsSync().filter { !it.isDeleted }
            if (activeChats.isEmpty()) {
                return@withContext Result.success()
            }

            val earliestChatCreatedAt = activeChats
                .map { it.createdAt }
                .filter { it > 0L }
                .minOrNull()

            val now = System.currentTimeMillis()
            val overdue = AppSettingsManager.isBackupOverdue(
                context = context,
                earliestChatCreatedAtMs = earliestChatCreatedAt,
                nowMs = now
            )

            if (overdue) {
                val daysElapsed = AppSettingsManager.daysSinceLastBackup(
                    context = context,
                    earliestChatCreatedAtMs = earliestChatCreatedAt,
                    nowMs = now
                ).coerceAtLeast(AppSettingsManager.BACKUP_REMINDER_THRESHOLD_DAYS)

                postBackupReminderNotification(
                    context = context,
                    daysSinceLastBackup = daysElapsed,
                    chatCount = activeChats.size
                )
            }

            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "BackupReminderWorker check failed", e)
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "gemini_wake_30d_backup_reminder_work"
        const val CHANNEL_ID = "gemini_wake_backup_reminder_channel"
        const val NOTIFICATION_ID = 44030
        const val EXTRA_OPEN_BACKUP_EXPORT = "extra_open_backup_export_dialog"

        /**
         * Enqueues the recurring periodic WorkManager check (runs every 24 hours) to evaluate
         * the 30-day backup reminder condition.
         */
        fun schedulePeriodicBackupReminder(context: Context) {
            try {
                AppSettingsManager.ensureInitialized(context)
                val periodicRequest = PeriodicWorkRequestBuilder<BackupReminderWorker>(
                    24,
                    TimeUnit.HOURS
                ).build()

                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    periodicRequest
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not schedule periodic BackupReminderWorker", e)
            }
        }

        /**
         * Posts the 30-day data backup reminder notification if notification permission is granted.
         */
        fun postBackupReminderNotification(
            context: Context,
            daysSinceLastBackup: Long = 30L,
            chatCount: Int = 1
        ): Boolean {
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
                putExtra(EXTRA_OPEN_BACKUP_EXPORT, true)
            }
            val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pendingIntent = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                launchIntent,
                pendingIntentFlags
            )

            val title = "30-Day Chat Backup Reminder"
            val body = "It has been $daysSinceLastBackup days since your last backup. Export your $chatCount chat sessions and usage statistics as CSV or JSON."

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            return try {
                NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
                true
            } catch (e: SecurityException) {
                Log.w(TAG, "Notification permission denied when posting backup reminder", e)
                false
            }
        }

        private fun ensureNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    ?: return
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Data Backup Reminders",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Reminds you to export a JSON or CSV backup if over 30 days have passed since your last export."
                }
                manager.createNotificationChannel(channel)
            }
        }
    }
}
