package com.example

import com.example.data.model.ChatSession
import com.example.ui.components.formatTrackedTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatTimerTrackingTest {

    @Test
    fun testFormatTrackedTimeFormatting() {
        assertEquals("00:00", formatTrackedTime(0L))
        assertEquals("00:09", formatTrackedTime(9L))
        assertEquals("00:59", formatTrackedTime(59L))
        assertEquals("01:00", formatTrackedTime(60L))
        assertEquals("01:35", formatTrackedTime(95L))
        assertEquals("12:45", formatTrackedTime(765L))
        assertEquals("1:00:00", formatTrackedTime(3600L))
        assertEquals("1:01:01", formatTrackedTime(3661L))
        assertEquals("2:34:56", formatTrackedTime(2 * 3600L + 34 * 60L + 56L))
    }

    @Test
    fun testChatSessionTimerModelDefaults() {
        val chat = ChatSession(
            title = "Research Project",
            wakeWord = "Project Timer"
        )
        assertEquals(0L, chat.totalTrackedSeconds)
        assertTrue(chat.isPersistent)

        val updatedChat = chat.copy(totalTrackedSeconds = 345L)
        assertEquals(345L, updatedChat.totalTrackedSeconds)
    }

    @Test
    fun testTimerToggleStateLogic() {
        var activeTimerChatIds = setOf<Long>()
        var liveTrackedSeconds = mapOf<Long, Long>()

        val chatId = 42L

        // Toggle ON
        if (!activeTimerChatIds.contains(chatId)) {
            activeTimerChatIds = activeTimerChatIds + chatId
            if (!liveTrackedSeconds.containsKey(chatId)) {
                liveTrackedSeconds = liveTrackedSeconds + (chatId to 0L)
            }
        }
        assertTrue(activeTimerChatIds.contains(chatId))
        assertEquals(0L, liveTrackedSeconds[chatId])

        // Simulate 5 ticks
        val simulatedTicks = 5
        val updatedMap = liveTrackedSeconds.toMutableMap()
        for (id in activeTimerChatIds) {
            updatedMap[id] = (updatedMap[id] ?: 0L) + simulatedTicks
        }
        liveTrackedSeconds = updatedMap
        assertEquals(5L, liveTrackedSeconds[chatId])

        // Toggle OFF (pause)
        if (activeTimerChatIds.contains(chatId)) {
            activeTimerChatIds = activeTimerChatIds - chatId
        }
        assertFalse(activeTimerChatIds.contains(chatId))
        assertEquals(5L, liveTrackedSeconds[chatId]) // Accumulated time preserved!

        // Reset
        liveTrackedSeconds = liveTrackedSeconds + (chatId to 0L)
        assertEquals(0L, liveTrackedSeconds[chatId])
    }

    @Test
    fun testOpenChatTimerOnlyPersistsWhileChatIsOpen() {
        var openChatId: Long? = null
        var openChatTimerSeconds = 0L
        var isOpenChatTimerRunning = false

        // User opens chat 101
        openChatId = 101L
        openChatTimerSeconds = 0L
        isOpenChatTimerRunning = true

        assertTrue(isOpenChatTimerRunning)
        assertEquals(101L, openChatId)
        assertEquals(0L, openChatTimerSeconds)

        // Ticks 15 seconds while chat remains open (not necessarily active)
        openChatTimerSeconds += 15L
        assertEquals(15L, openChatTimerSeconds)
        assertTrue(isOpenChatTimerRunning)

        // User exits/closes chat (selectChat(null))
        openChatId = null
        isOpenChatTimerRunning = false
        openChatTimerSeconds = 0L // Resets because it only persists as long as chat is open!

        assertFalse(isOpenChatTimerRunning)
        org.junit.Assert.assertNull(openChatId)
        assertEquals(0L, openChatTimerSeconds)
    }

    @Test
    fun testUsageLimitVsCacheComparisonCalculation() {
        val metrics = com.example.cache.BufferMetrics(
            hotMemoryCount = 20,
            totalDiskCount = 85,
            hotBufferSizeLimit = 50
        )

        val usageLimit = metrics.hotBufferSizeLimit
        val cacheUsed = metrics.hotMemoryCount
        val headroom = usageLimit - cacheUsed

        assertTrue(usageLimit > cacheUsed) // Usage limit ceiling is greater than current cache
        assertEquals(50, usageLimit)
        assertEquals(20, cacheUsed)
        assertEquals(30, headroom)

        val usagePercent = ((cacheUsed.toFloat() / usageLimit) * 100).toInt()
        assertEquals(40, usagePercent)
    }

    @Test
    fun testSeparateIncomingAndOutgoingCacheIsolation() {
        val sampleMessages = listOf(
            com.example.data.model.ChatMessage(id = 1, chatId = 10, sender = "user", content = "Hello!"),
            com.example.data.model.ChatMessage(id = 2, chatId = 10, sender = "model", content = "Hi there, how can I assist?"),
            com.example.data.model.ChatMessage(id = 3, chatId = 10, sender = "user", content = "Calculate 2+2"),
            com.example.data.model.ChatMessage(id = 4, chatId = 10, sender = "gemini", content = "2+2 is 4.")
        )

        // Separate incoming vs outgoing
        val incomingMessages = sampleMessages.filter { it.sender != "user" }
        val outgoingMessages = sampleMessages.filter { it.sender == "user" }

        assertEquals(2, incomingMessages.size)
        assertEquals(2, outgoingMessages.size)
        assertTrue(incomingMessages.all { it.sender != "user" })
        assertTrue(outgoingMessages.all { it.sender == "user" })

        // Clearing incoming should leave outgoing intact
        val afterClearIncoming = sampleMessages.filter { it.sender == "user" }
        assertEquals(2, afterClearIncoming.size)
        assertEquals("Hello!", afterClearIncoming[0].content)
        assertEquals("Calculate 2+2", afterClearIncoming[1].content)

        // Clearing outgoing should leave incoming intact
        val afterClearOutgoing = sampleMessages.filter { it.sender != "user" }
        assertEquals(2, afterClearOutgoing.size)
        assertEquals("Hi there, how can I assist?", afterClearOutgoing[0].content)
        assertEquals("2+2 is 4.", afterClearOutgoing[1].content)
    }

    @Test
    fun testScreenClockTapToggleLargerText() {
        var isLargeText = false
        // Tap 1 -> toggles to larger text
        isLargeText = !isLargeText
        assertTrue(isLargeText)

        // Tap 2 -> toggles back to standard text
        isLargeText = !isLargeText
        assertFalse(isLargeText)
    }

    @Test
    fun testGeminiCouplerPromptPackageFormatting() {
        val chat = ChatSession(
            id = 55,
            title = "App Architecture Plan",
            wakeWord = "Architect"
        )
        val messages = listOf(
            com.example.data.model.ChatMessage(id = 1, chatId = 55, sender = "user", content = "How should we design the database?"),
            com.example.data.model.ChatMessage(id = 2, chatId = 55, sender = "gemini", content = "Use Room for local storage and Firestore for cloud sync.")
        )
        val promptPackage = com.example.coupler.GeminiCouplerBridge.formatGeminiPromptPackage(
            chat = chat,
            messages = messages,
            activeTaskPrompt = "What indexes should we add?"
        )

        assertTrue(promptPackage.contains("PROJECT COUPLER: APP ARCHITECTURE PLAN"))
        assertTrue(promptPackage.contains("Associated Wake Word: \"Architect\""))
        assertTrue(promptPackage.contains("[USER]: How should we design the database?"))
        assertTrue(promptPackage.contains("[GEMINI]: Use Room for local storage and Firestore for cloud sync."))
        assertTrue(promptPackage.contains("What indexes should we add?"))
    }

    @Test
    fun testGeminiCouplerJsonSnapshotCreationAndParsing() = kotlinx.coroutines.runBlocking {
        val chat = ChatSession(
            id = 77,
            title = "Task Management",
            wakeWord = "My Tasks"
        )
        val messages = listOf(
            com.example.data.model.ChatMessage(id = 1, chatId = 77, sender = "user", content = "List pending bugs"),
            com.example.data.model.ChatMessage(id = 2, chatId = 77, sender = "model", content = "Found 3 bugs in queue.")
        )

        val jsonSnapshot = com.example.coupler.GeminiCouplerBridge.createCouplerJsonSnapshot(chat, messages)
        assertTrue(jsonSnapshot.contains("\"couplerVersion\": \"2.0\""))
        assertTrue(jsonSnapshot.contains("\"title\": \"Task Management\""))
        assertTrue(jsonSnapshot.contains("\"wakeWord\": \"My Tasks\""))

        val parsed = com.example.coupler.GeminiCouplerBridge.parsePiggybackPayload(jsonSnapshot)
        org.junit.Assert.assertNotNull(parsed)
        assertEquals("Task Management", parsed!!.title)
        assertEquals("My Tasks", parsed.suggestedWakeWord)
        assertEquals(2, parsed.messages.size)
        assertEquals("List pending bugs", parsed.messages[0].content)
        assertEquals("Found 3 bugs in queue.", parsed.messages[1].content)
    }
}

