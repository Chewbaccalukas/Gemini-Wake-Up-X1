package com.example.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.cache.ChatMemoryBufferManager
import com.example.coupler.ChromeBridgeSocketManager
import com.example.data.dao.ChatDao
import com.example.data.db.AppDatabase
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.repository.ChatRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatSessionLifecycleTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: ChatDao
    private lateinit var repository: ChatRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.chatDao()
        repository = ChatRepository(dao)
    }

    @After
    fun tearDown() {
        db.close()
        ChromeBridgeSocketManager.shutdown()
    }

    @Test
    fun testForeignKeySafetyAbortsNonExistentParentWithoutConstraintException() = runBlocking {
        // Attempting to insert a message into a non-existent parent chatId (e.g. 99999L)
        val orphanMessage = ChatMessage(
            chatId = 99999L,
            sender = "user",
            content = "This parent chat was already deleted"
        )

        // Must safely return -1 without throwing SQLiteConstraintException
        val insertedId = repository.insertMessage(orphanMessage)
        assertEquals(-1L, insertedId)

        // Batch inserting for orphan chat also returns empty list safely
        val batchInserted = repository.insertMessages(listOf(orphanMessage))
        assertTrue(batchInserted.isEmpty())
    }

    @Test
    fun testForeignKeySafetyInsertsSuccessfullyForValidParent() = runBlocking {
        val chat = ChatSession(
            title = "Test Parent Chat",
            wakeWord = "Test Wake"
        )
        val chatId = repository.saveChat(chat)
        assertTrue(chatId > 0)

        val message = ChatMessage(
            chatId = chatId,
            sender = "user",
            content = "Hello Gemini"
        )

        val msgId = repository.insertMessage(message)
        assertTrue("Message must be inserted successfully with valid parent", msgId > 0)

        val retrievedChat = repository.getChatByIdSync(chatId)
        assertTrue(retrievedChat != null)
        assertTrue("Chat timestamp should be updated on message insertion", retrievedChat!!.lastUpdatedAt >= message.timestamp)
    }

    @Test
    fun testBufferManagerDetachChatPurgesMemory() = runBlocking {
        val bufferManager = ChatMemoryBufferManager(context, dao)

        val chat = ChatSession(title = "Buffer Chat", wakeWord = "Buffer Word")
        val chatId = repository.saveChat(chat)

        val messages = listOf(
            ChatMessage(chatId = chatId, sender = "user", content = "Message 1"),
            ChatMessage(chatId = chatId, sender = "gemini", content = "Message 2")
        )
        bufferManager.updateFullMessageList(chatId, messages)
        assertEquals(2, bufferManager.hotMessages.value.size)

        // Detach chat on session switch or close
        bufferManager.detachChat()
        assertEquals(0, bufferManager.hotMessages.value.size)
        assertEquals(0, bufferManager.bufferMetrics.value.hotMemoryCount)
        assertEquals("Detached", bufferManager.bufferMetrics.value.preloadStatus)

        // Stale update for another chat is rejected
        bufferManager.updateFullMessageList(chatId, messages)
        // If currentChatId is null, it attaches to the new chat
        assertEquals(2, bufferManager.hotMessages.value.size)

        // An update for a different chat when already attached is rejected
        bufferManager.updateFullMessageList(8888L, listOf(ChatMessage(chatId = 8888L, sender = "user", content = "Other")))
        assertEquals(2, bufferManager.hotMessages.value.size)
    }

    @Test
    fun testChromeBridgeSocketResetOnLifecycleTrigger() {
        val initialPort = ChromeBridgeSocketManager.start(context)
        val initialNonce = ChromeBridgeSocketManager.bridgeState.value.sessionNonce
        assertTrue(initialPort > 0)

        // Trigger lifecycle reset
        val reboundPort = ChromeBridgeSocketManager.forceResetAndClear(context)
        assertTrue(reboundPort > 0)

        val state = ChromeBridgeSocketManager.bridgeState.value
        assertTrue(state.isRunning)
        assertEquals(reboundPort, state.boundPort)
        assertNotEquals("Session nonce must rotate on reset to avoid stale session bleed", initialNonce, state.sessionNonce)
    }
}
