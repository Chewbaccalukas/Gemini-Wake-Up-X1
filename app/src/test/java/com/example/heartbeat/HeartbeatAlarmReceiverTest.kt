package com.example.heartbeat

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeartbeatAlarmReceiverTest {

    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    @Test
    fun testNotificationChannelCreated() {
        HeartbeatAlarmReceiver.createNotificationChannel(context)
        val channel = notificationManager.getNotificationChannel(HeartbeatAlarmReceiver.CHANNEL_ID)
        assertNotNull("Notification channel must be created", channel)
        assertEquals(HeartbeatAlarmReceiver.CHANNEL_NAME, channel.name.toString())
    }

    @Test
    fun testTimerAlarmExpirationPostsNotificationWithChatExtra() {
        val receiver = HeartbeatAlarmReceiver()
        val testChatId = 77L
        val testChatTitle = "Coding Sprint Session"

        val intent = Intent(HeartbeatAlarmReceiver.ACTION_TIMER_ALARM_EXPIRATION).apply {
            putExtra(HeartbeatAlarmReceiver.EXTRA_TARGET_CHAT_ID, testChatId)
            putExtra(HeartbeatAlarmReceiver.EXTRA_CHAT_TITLE, testChatTitle)
            putExtra(HeartbeatAlarmReceiver.EXTRA_TIMER_SECONDS, 120L)
            putExtra(HeartbeatAlarmReceiver.EXTRA_ALARM_MESSAGE, "Time's up for sprint!")
        }

        receiver.onReceive(context, intent)

        // Verify notification posted
        val shadowNotificationManager = shadowOf(notificationManager)
        val notifications = shadowNotificationManager.allNotifications
        assertTrue("Notification must be posted on timer expiration", notifications.isNotEmpty())

        val notification = notifications.first()
        val shadowNotification = shadowOf(notification)
        assertTrue(
            "Notification title should contain chat title",
            shadowNotification.contentTitle.toString().contains(testChatTitle)
        )
        assertTrue(
            "Notification text should contain message",
            shadowNotification.contentText.toString().contains("Time's up for sprint!")
        )
    }

    @Test
    fun testScheduleAndCancelHeartbeatPing() {
        HeartbeatAlarmReceiver.scheduleHeartbeatPing(context, 30_000L)
        // Should cancel without exception
        HeartbeatAlarmReceiver.cancelHeartbeatPing(context)
    }

    @Test
    fun testScheduleAndCancelChatTimerAlarm() {
        HeartbeatAlarmReceiver.scheduleChatTimerAlarm(
            context = context,
            chatId = 123L,
            chatTitle = "Focus Session",
            delaySeconds = 60L
        )
        // Should cancel without exception
        HeartbeatAlarmReceiver.cancelChatTimerAlarm(context, 123L)
    }
}
