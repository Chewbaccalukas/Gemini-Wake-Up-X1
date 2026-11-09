package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioPermissionFlowTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testPendingAudioActionTypes() {
        val toggleAction: PendingAudioAction = PendingAudioAction.ToggleListening
        assertNotNull(toggleAction)

        val voiceAction: PendingAudioAction = PendingAudioAction.StartVoiceRecording
        assertNotNull(voiceAction)

        val snippetAction = PendingAudioAction.RecordWakeSnippet(chatId = 88L, slotIndex = 4)
        assertEquals(88L, snippetAction.chatId)
        assertEquals(4, snippetAction.slotIndex)
    }

    @Test
    fun testApplicationDetailsSettingsIntentStructure() {
        val packageName = context.packageName
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:$packageName", intent.dataString)
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun testRecordAudioPermissionString() {
        assertEquals("android.permission.RECORD_AUDIO", Manifest.permission.RECORD_AUDIO)
    }
}
