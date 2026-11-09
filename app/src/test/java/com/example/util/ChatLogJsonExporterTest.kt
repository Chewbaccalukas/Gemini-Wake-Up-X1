package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.model.ChatSession
import com.example.data.repository.ChatRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatLogJsonExporterTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var chatRepository: ChatRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        chatRepository = ChatRepository(db.chatDao(), db.messageDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun exportLogsToJsonFile_writesValidChronologicalRoomLogsAndShareIntent() = runTest {
        val workChatId = chatRepository.saveChat(
            ChatSession(
                title = "Work Chat",
                wakeWord = "Hey Work",
                greetingMessage = "Good morning, team! Here's what's on the agenda today."
            )
        )
        val studyChatId = chatRepository.saveChat(
            ChatSession(
                title = "Study Notes",
                wakeWord = "Hey Study",
                greetingMessage = "Ready to review flashcards."
            )
        )

        chatRepository.saveChatHistoryMessage(
            chatId = workChatId,
            sender = "user",
            content = "Summarize sprint goals",
            timestamp = 1_000L,
            triggeredByWakeWord = true
        )
        chatRepository.saveChatHistoryMessage(
            chatId = workChatId,
            sender = "gemini",
            content = "1. Ship JSON export\n2. Verify intent sharing",
            timestamp = 2_000L,
            triggeredByWakeWord = false
        )
        chatRepository.saveChatHistoryMessage(
            chatId = studyChatId,
            sender = "user",
            content = "Explain Kotlin Coroutines",
            timestamp = 3_000L,
            triggeredByWakeWord = true
        )

        chatRepository.insertVoiceCommand(
            VoiceCommandHistoryEntry(
                id = "vc_1",
                commandType = VoiceCommandType.WAKE_WORD,
                triggerPhrase = "Hey Work",
                recognizedTranscript = "Hey Work summarize sprint goals",
                chatTitle = "Work Chat",
                timestamp = 1_500L
            )
        )

        val exportDir = File(context.cacheDir, "test_exports")
        val jsonFile = chatRepository.exportChatInteractionLogsToJsonFile(
            context = context,
            chatId = null,
            outputDir = exportDir
        )

        assertTrue("Exported JSON file should exist on disk", jsonFile.exists())
        assertTrue("Exported file should end with .json", jsonFile.name.endsWith(".json"))

        val parsed = JSONObject(jsonFile.readText(Charsets.UTF_8))
        assertEquals(1, parsed.getInt("schemaVersion"))
        assertEquals(2, parsed.getInt("totalChats"))
        assertEquals(3, parsed.getInt("totalMessages"))
        assertEquals(1, parsed.getInt("totalVoiceCommands"))

        val interactions = parsed.getJSONArray("interactions")
        assertEquals(3, interactions.length())
        assertEquals("Summarize sprint goals", interactions.getJSONObject(0).getString("content"))
        assertEquals("1. Ship JSON export\n2. Verify intent sharing", interactions.getJSONObject(1).getString("content"))
        assertEquals("Explain Kotlin Coroutines", interactions.getJSONObject(2).getString("content"))

        // Verify single-chat filtered export
        val singleChatFile = chatRepository.exportChatInteractionLogsToJsonFile(
            context = context,
            chatId = workChatId,
            outputDir = exportDir
        )
        val singleParsed = JSONObject(singleChatFile.readText(Charsets.UTF_8))
        assertEquals(1, singleParsed.getInt("totalChats"))
        assertEquals(2, singleParsed.getInt("totalMessages"))
        assertEquals(workChatId, singleParsed.getLong("filterChatId"))

        // Verify Android share intent creation
        val chooserIntent = ChatLogJsonExporter.createShareJsonFileIntent(context, jsonFile)
        assertEquals(Intent.ACTION_CHOOSER, chooserIntent.action)
        @Suppress("DEPRECATION")
        val targetIntent = chooserIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertNotNull("Chooser should wrap an ACTION_SEND target intent", targetIntent)
        assertEquals(Intent.ACTION_SEND, targetIntent!!.action)
        assertEquals("application/json", targetIntent.type)
        assertTrue(
            "Target intent must grant read URI permission",
            (targetIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0
        )
        @Suppress("DEPRECATION")
        val streamUri = targetIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        assertNotNull("Target intent must include EXTRA_STREAM URI", streamUri)
    }
}
