package com.example

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.util.VoiceCommandType
import com.example.util.WebLinkHistoryManager
import com.example.util.WebLinkSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WebLinkHistoryAndBrowserTest {

    private lateinit var context: Context
    private lateinit var manager: WebLinkHistoryManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = WebLinkHistoryManager(context)
        manager.clearAllHistory()
        manager.clearAllVoiceCommandHistory()
    }

    @Test
    fun recordSearchOrUrl_normalizesQueriesAndDirectUrls() {
        val searchEntry = manager.recordSearchOrUrl("kotlin coroutines guide")
        assertNotNull(searchEntry)
        assertEquals(WebLinkSourceType.WEB_SEARCH, searchEntry!!.sourceType)
        assertTrue(searchEntry.url.startsWith("https://www.google.com/search?q="))

        val domainEntry = manager.recordSearchOrUrl("developer.android.com/jetpack")
        assertNotNull(domainEntry)
        assertEquals(WebLinkSourceType.EXTERNAL_LINK, domainEntry!!.sourceType)
        assertEquals("https://developer.android.com/jetpack", domainEntry.url)

        val entries = manager.historyEntries.value
        assertEquals(2, entries.size)
        assertEquals(domainEntry.id, entries[0].id)
        assertEquals(searchEntry.id, entries[1].id)
    }

    @Test
    fun recordChatLink_andDeleteAndClear_updatesCombinedHistory() {
        val chatEntry = manager.recordChatLink(
            rawUrl = "https://firebase.google.com/docs/ai-logic",
            label = "Firebase AI Logic Docs",
            chatTitle = "Android Assistant"
        )
        assertNotNull(chatEntry)
        assertEquals(WebLinkSourceType.CHAT_LINK, chatEntry!!.sourceType)
        assertEquals("Firebase AI Logic Docs", chatEntry.titleOrQuery)
        assertEquals("Android Assistant", chatEntry.chatTitle)

        val incomingEntry = manager.recordIncomingExternalLink("https://github.com/google")
        assertNotNull(incomingEntry)
        assertEquals(2, manager.historyEntries.value.size)

        manager.deleteEntry(chatEntry.id)
        assertEquals(1, manager.historyEntries.value.size)
        assertEquals(incomingEntry!!.id, manager.historyEntries.value.first().id)

        manager.clearAllHistory()
        assertTrue(manager.historyEntries.value.isEmpty())
    }

    @Test
    fun extractLinksFromText_parsesMarkdownAndRawUrlsInGeminiMessages() {
        val sampleMessage = """
            Here are the official resources you requested:
            1. Read the [Android Developer Guide](https://developer.android.com/guide) for architecture tips.
            2. Visit https://kotlinlang.org/docs/home.html or www.google.com for further search.
        """.trimIndent()

        val extracted = WebLinkHistoryManager.extractLinksFromText(sampleMessage)
        assertEquals(3, extracted.size)

        assertEquals("Android Developer Guide", extracted[0].label)
        assertEquals("https://developer.android.com/guide", extracted[0].url)

        assertEquals("https://kotlinlang.org/docs/home.html", extracted[1].url)
        assertEquals("https://www.google.com", extracted[2].url)
    }

    @Test
    fun openUrlInDefaultBrowser_passivelyStagesUrlInChromeBridgeWithoutStartingActivity() {
        val app = context as android.app.Application
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        shadowApp.clearNextStartedActivities()

        val staged = WebLinkHistoryManager.openUrlInDefaultBrowser(context, "developer.android.com/compose")
        assertTrue(staged)
        assertEquals(
            "https://developer.android.com/compose",
            com.example.coupler.ChromeBridgeSocketManager.bridgeState.value.stagedUrlPayload
        )
        org.junit.Assert.assertNull(
            "openUrlInDefaultBrowser must NEVER call startActivity() or launch Chrome externally",
            shadowApp.nextStartedActivity
        )
    }

    @Test
    fun recordVoiceCommand_persistsTranscriptsAndSupportsDeleteAndClear() {
        val wakeEntry = manager.recordVoiceCommand(
            commandType = VoiceCommandType.WAKE_WORD,
            triggerPhrase = "Hey Orion",
            recognizedTranscript = "Hey Orion summarize today's release notes",
            chatTitle = "Daily Briefing"
        )
        assertNotNull(wakeEntry)
        assertEquals(VoiceCommandType.WAKE_WORD, wakeEntry!!.commandType)
        assertEquals("Hey Orion", wakeEntry.triggerPhrase)
        assertEquals("Hey Orion summarize today's release notes", wakeEntry.recognizedTranscript)
        assertEquals("Daily Briefing", wakeEntry.chatTitle)

        val dictateEntry = manager.recordVoiceCommand(
            commandType = VoiceCommandType.DICTATE,
            triggerPhrase = "Dictate",
            recognizedTranscript = "Check the latest Android 15 Jetpack Compose updates",
            chatTitle = "Research Chat"
        )
        assertNotNull(dictateEntry)

        val entries = manager.voiceCommandEntries.value
        assertEquals(2, entries.size)
        assertEquals(dictateEntry!!.id, entries[0].id)
        assertEquals(wakeEntry.id, entries[1].id)

        // Verify persistence across manager instances
        val reloadedManager = WebLinkHistoryManager(context)
        assertEquals(2, reloadedManager.voiceCommandEntries.value.size)
        assertEquals(
            "Check the latest Android 15 Jetpack Compose updates",
            reloadedManager.voiceCommandEntries.value[0].recognizedTranscript
        )

        manager.deleteVoiceCommandEntry(dictateEntry.id)
        assertEquals(1, manager.voiceCommandEntries.value.size)
        assertEquals(wakeEntry.id, manager.voiceCommandEntries.value.first().id)

        manager.clearAllVoiceCommandHistory()
        assertTrue(manager.voiceCommandEntries.value.isEmpty())
    }
}
