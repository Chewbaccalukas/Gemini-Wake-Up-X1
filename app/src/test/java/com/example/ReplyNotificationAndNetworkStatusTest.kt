package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.util.NetworkConnectionState
import com.example.util.NetworkStatusTracker
import com.example.util.ReplyNotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReplyNotificationAndNetworkStatusTest {

    @Test
    fun shouldNotifyForActiveReply_onlyNotifiesWhenBackgroundedOrScreenOff() {
        // When setting is disabled, never notify
        assertFalse(
            ReplyNotificationHelper.shouldNotifyForActiveReply(
                notifySettingEnabled = false,
                isAppInForeground = false,
                isScreenInteractive = false
            )
        )

        // When setting is enabled, but app is in foreground AND screen is interactive (on), do NOT notify
        assertFalse(
            ReplyNotificationHelper.shouldNotifyForActiveReply(
                notifySettingEnabled = true,
                isAppInForeground = true,
                isScreenInteractive = true
            )
        )

        // When setting is enabled and app is backgrounded, notify
        assertTrue(
            ReplyNotificationHelper.shouldNotifyForActiveReply(
                notifySettingEnabled = true,
                isAppInForeground = false,
                isScreenInteractive = true
            )
        )

        // When setting is enabled and screen is off (not interactive), notify even if activity was last on top
        assertTrue(
            ReplyNotificationHelper.shouldNotifyForActiveReply(
                notifySettingEnabled = true,
                isAppInForeground = true,
                isScreenInteractive = false
            )
        )
    }

    @Test
    fun networkStatusTracker_queriesConnectivityAndExposesLabels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val tracker = NetworkStatusTracker(context)
        tracker.refresh()
        val current = tracker.networkState.value
        assertNotNull(current)
        assertTrue(current.displayLabel.isNotBlank())
        assertTrue(current.shortLabel.isNotBlank())

        assertEquals("Wi-Fi", NetworkConnectionState.ONLINE_WIFI.shortLabel)
        assertEquals("Cellular", NetworkConnectionState.ONLINE_CELLULAR.shortLabel)
        assertEquals("Offline", NetworkConnectionState.OFFLINE.shortLabel)
        tracker.stopTracking()
    }
}
