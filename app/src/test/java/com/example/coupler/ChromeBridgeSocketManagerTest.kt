package com.example.coupler

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChromeBridgeSocketManagerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        ChromeBridgeSocketManager.shutdown()
    }

    @Test
    fun testDynamicEphemeralPortBindingAndNonce() {
        val port = ChromeBridgeSocketManager.start(context)
        assertTrue("Port must be dynamic positive ephemeral port", port > 0)

        val state = ChromeBridgeSocketManager.bridgeState.value
        assertTrue("Bridge must be running", state.isRunning)
        assertEquals(port, state.boundPort)
        assertTrue("Nonce must not be blank", state.sessionNonce.isNotBlank())
        assertEquals("Cryptographic nonce should be 32 hex characters (128-bit)", 32, state.sessionNonce.length)

        val bridgeUrl = ChromeBridgeSocketManager.getBridgeUrl()
        assertTrue("URL must point to loopback 127.0.0.1", bridgeUrl.startsWith("http://127.0.0.1:$port/?nonce="))
        assertTrue("URL must contain session nonce", bridgeUrl.contains(state.sessionNonce))
    }

    @Test
    fun testForceResetAndClearRebindsFreshPortAndClearsBuffer() {
        val initialPort = ChromeBridgeSocketManager.start(context)
        val initialNonce = ChromeBridgeSocketManager.bridgeState.value.sessionNonce
        assertTrue(initialPort > 0)

        // Seed a temporary file in bridge cache directory
        val cacheDir = File(context.cacheDir, "chrome_bridge")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val tempTestFile = File(cacheDir, "test_stale_cache.tmp")
        tempTestFile.writeText("sample stale data")
        assertTrue(tempTestFile.exists())

        // Execute forceResetAndClear
        val newPort = ChromeBridgeSocketManager.forceResetAndClear(context)
        assertTrue("New port must be a valid positive port", newPort > 0)

        val newState = ChromeBridgeSocketManager.bridgeState.value
        assertTrue("Bridge must still be running after reset", newState.isRunning)
        assertEquals(newPort, newState.boundPort)

        // Cryptographic nonce must be regenerated to prevent stale locks & session replay
        assertNotEquals("Session nonce must change after reset", initialNonce, newState.sessionNonce)
        assertEquals(32, newState.sessionNonce.length)

        // Stale cache file must be deleted during wipe
        assertTrue("Stale test cache file must be wiped", !tempTestFile.exists())

        // Buffer must have been cleared and now contain the fresh reset packet
        val packets = ChromeBridgeSocketManager.getBufferedPackets()
        assertTrue("Packets should contain reset event", packets.any { it.type.startsWith("FORCE_RESET") })
    }

    @Test
    fun testForceResetWithCachePreserved() {
        ChromeBridgeSocketManager.start(context)

        // Seed a temporary file in bridge cache directory
        val cacheDir = File(context.cacheDir, "chrome_bridge")
        if (!cacheDir.exists()) cacheDir.mkdirs()
        val preservedFile = File(cacheDir, "preserved_chat_state.json")
        preservedFile.writeText("{\"chatId\": 42, \"preserved\": true}")
        assertTrue(preservedFile.exists())

        // Reset with preserveDiskCache = true
        val reboundPort = ChromeBridgeSocketManager.forceResetAndClear(context, preserveDiskCache = true)
        assertTrue(reboundPort > 0)

        // File must STILL exist!
        assertTrue("Cache file must be preserved across session reset", preservedFile.exists())
        assertEquals("{\"chatId\": 42, \"preserved\": true}", preservedFile.readText())

        val packets = ChromeBridgeSocketManager.getBufferedPackets()
        assertTrue("Packets should indicate preserved cache reset", packets.any { it.type == "FORCE_RESET_PRESERVED" })
    }

    @Test
    fun testHeartbeatModeTransitions() {
        ChromeBridgeSocketManager.start(context)
        assertTrue(ChromeBridgeSocketManager.bridgeState.value.isRunning)

        // Enter heartbeat mode
        ChromeBridgeSocketManager.enterHeartbeatMode(context)
        val stateInHeartbeat = ChromeBridgeSocketManager.bridgeState.value
        assertTrue("Heartbeat mode flag should be true", stateInHeartbeat.isHeartbeatMode)
        assertTrue("Heavy HTTP bridge should not be running in heartbeat mode", !stateInHeartbeat.isRunning)

        // Record heartbeat ping
        ChromeBridgeSocketManager.recordHeartbeatPing()
        val packets = ChromeBridgeSocketManager.getBufferedPackets()
        assertTrue("Should record heartbeat ping", packets.any { it.type == "HEARTBEAT_PING" })

        // Exit heartbeat mode
        val restoredPort = ChromeBridgeSocketManager.exitHeartbeatMode(context)
        assertTrue(restoredPort > 0)
        val stateRestored = ChromeBridgeSocketManager.bridgeState.value
        assertTrue("Heartbeat mode should be false after exit", !stateRestored.isHeartbeatMode)
        assertTrue("Bridge should be running again", stateRestored.isRunning)
    }

    @Test
    fun testPacketRingBufferBoundedCapacity() {
        val ringBuffer = PacketRingBuffer(capacity = 5)
        assertEquals(0, ringBuffer.size())

        for (i in 1..10) {
            ringBuffer.add(
                BridgePacket(
                    id = i.toLong(),
                    timestamp = System.currentTimeMillis(),
                    type = "PACKET_$i",
                    sizeBytes = i * 10,
                    summary = "Packet number $i"
                )
            )
        }

        // Ring buffer must only keep latest 5 entries
        assertEquals(5, ringBuffer.size())
        val packets = ringBuffer.toList()
        assertEquals("PACKET_6", packets.first().type)
        assertEquals("PACKET_10", packets.last().type)

        // Clearing ring buffer
        ringBuffer.clear()
        assertEquals(0, ringBuffer.size())
    }

    @Test
    fun testGeminiCouplerBridgeChromeHelpers() {
        val port = ChromeBridgeSocketManager.start(context)
        assertTrue(port > 0)

        assertEquals(port, GeminiCouplerBridge.getActiveBridgePort())
        val nonce = GeminiCouplerBridge.getActiveBridgeNonce()
        assertTrue(nonce.isNotBlank())
        assertEquals(32, nonce.length)

        val bridgeUrl = GeminiCouplerBridge.getBridgeUrl()
        assertTrue(bridgeUrl.startsWith("http://127.0.0.1:$port/?nonce=$nonce"))
    }

    @Test
    fun testPassiveChromeBridgeAndRelayStagingWithoutExternalActivityLaunch() {
        val app = context as android.app.Application
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        shadowApp.clearNextStartedActivities()

        val chat = com.example.data.model.ChatSession(
            id = 77L,
            title = "Passive Relay Chat",
            wakeWord = "Hey Relay",
            systemInstruction = "Helpful assistant"
        )
        val messages = listOf(
            com.example.data.model.ChatMessage(
                id = 1L,
                chatId = 77L,
                sender = "user",
                content = "Stage this conversation passively",
                timestamp = 1730000001000L
            )
        )

        // 1. ChromeBridgeSocketManager.launchChromeBridge stages passively without startActivity()
        val launched = ChromeBridgeSocketManager.launchChromeBridge(context, chat)
        assertTrue(launched)
        org.junit.Assert.assertNull(
            "launchChromeBridge must never fire startActivity()",
            shadowApp.nextStartedActivity
        )
        val bridgeState = ChromeBridgeSocketManager.bridgeState.value
        assertEquals(77L, bridgeState.stagedChatId)
        assertEquals("Passive Relay Chat", bridgeState.stagedChatTitle)
        assertTrue(bridgeState.stagedUrlPayload.contains("chatId=77"))

        // 2. ChromeAppBridge.openChatInChrome stages HTML & JSON in cache + socket buffer without startActivity()
        val appBridge = com.example.bridge.ChromeAppBridge(context)
        appBridge.openChatInChrome(chat, messages)
        org.junit.Assert.assertNull(
            "openChatInChrome must never fire startActivity()",
            shadowApp.nextStartedActivity
        )
        assertEquals(1, ChromeBridgeSocketManager.bridgeState.value.stagedMessagesCount)
        assertTrue(ChromeBridgeSocketManager.stagedJsonPayload.contains("Stage this conversation passively"))

        // 3. LocalSyncRelayServer stages chat and URL payload on 127.0.0.1:8765
        val relay = com.example.bridge.LocalSyncRelayServer(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            onMessageReceivedFromChrome = {}
        )
        relay.stageChatAndUrlPayload(chat, messages, "https://gemini.google.com/app")
        assertEquals(77L, relay.serverState.value.stagedChatId)
        assertEquals("Passive Relay Chat", relay.serverState.value.stagedChatTitle)
        assertEquals("https://gemini.google.com/app", relay.serverState.value.stagedUrlPayload)
        assertEquals(1, relay.serverState.value.stagedMessageCount)
    }
}
