package com.example.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.firebase.FirestoreChatDoc
import com.example.data.firebase.FirestoreMessageDoc
import com.example.data.firebase.FirestoreWakeWordDoc
import com.example.data.model.Chat
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.Message
import com.example.data.model.SyncState
import com.example.data.model.SyncStatus
import com.example.data.model.TriggerType
import com.example.data.model.WakeWord
import com.example.data.repository.ChatRepository
import com.example.data.repository.MessageRepository
import com.example.data.repository.WakeWordRepository
import com.example.voice.WakeWordManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HybridRoomSyncDatabaseTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var chatRepository: ChatRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var wakeWordRepository: WakeWordRepository
    private lateinit var wakeWordManager: WakeWordManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        chatRepository = ChatRepository(database.chatDao(), database.messageDao())
        messageRepository = MessageRepository(database.messageDao(), database.chatDao())
        wakeWordRepository = WakeWordRepository(database.wakeWordDao(), database.chatDao())
        wakeWordManager = WakeWordManager(context, database.wakeWordDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `local Chat, Message, and WakeWord entities initialize as PENDING and transition to SYNCED`() = runBlocking {
        val uid = "user_alpha_123"

        // 1. Save a local Chat via ChatRepository
        val chatId = chatRepository.saveChat(
            Chat(
                title = "System Design",
                wakeWord = "Hey Architect",
                isDefaultWakeChat = true,
                isPersistent = true
            )
        )
        assertTrue(chatId > 0L)

        val pendingChats = chatRepository.getPendingSyncChats()
        assertEquals(1, pendingChats.size)
        val savedChat = pendingChats.first()
        assertEquals(SyncStatus.PENDING, savedChat.syncStatus)
        assertEquals(SyncState.PENDING, savedChat.syncStateEnum)
        assertEquals("chat_$chatId", savedChat.resolvedRemoteId)
        assertFalse(savedChat.isSynced)
        assertTrue(savedChat.needsSync)
        assertFalse(savedChat.isDeleted)

        // Mark Chat as SYNCED
        chatRepository.markChatSynced(id = chatId, remoteId = "chat_$chatId", userId = uid, syncedAt = 5000L)
        assertTrue(chatRepository.getPendingSyncChats().isEmpty())
        val syncedChat = chatRepository.getChatByIdSync(chatId)
        assertNotNull(syncedChat)
        assertEquals(SyncStatus.SYNCED, syncedChat?.syncStatus)
        assertEquals(SyncState.SYNCED, syncedChat?.syncStateEnum)
        assertEquals(uid, syncedChat?.userId)
        assertTrue(syncedChat?.isSynced == true)
        assertFalse(syncedChat?.needsSync == true)

        // Updating a SYNCED chat marks it DIRTY for the next cloud sync cycle
        chatRepository.saveChat(syncedChat!!.copy(title = "Updated System Design"))
        val dirtyChats = chatRepository.getPendingSyncChats()
        assertEquals(1, dirtyChats.size)
        assertEquals(SyncStatus.DIRTY, dirtyChats.first().syncStatus)
        assertEquals(SyncState.DIRTY, dirtyChats.first().syncStateEnum)

        // 2. Insert a local Message via MessageRepository
        val msgId = messageRepository.insertMessage(
            Message(
                chatId = chatId,
                sender = "user",
                content = "Compare Room and Firestore hybrid caching.",
                timestamp = 10_000L,
                triggeredByWakeWord = true
            )
        )
        assertTrue(msgId > 0L)
        val pendingMessages = messageRepository.getPendingSyncMessages()
        assertEquals(1, pendingMessages.size)
        assertEquals("msg_$msgId", pendingMessages.first().resolvedRemoteId)
        assertFalse(pendingMessages.first().isDeleted)

        messageRepository.markMessageSynced(id = msgId, remoteId = "msg_$msgId", userId = uid, syncedAt = 11_000L)
        assertTrue(messageRepository.getPendingSyncMessages().isEmpty())

        // 3. Insert a local WakeWord via WakeWordRepository
        val wakeId = wakeWordRepository.addCustomChatWakeWord(
            chatId = chatId,
            chatTitle = "Updated System Design",
            phrase = "Hey Architect",
            sensitivity = 0.84f
        )
        assertTrue(wakeId > 0L)
        val pendingWakeWords = wakeWordRepository.getPendingSyncWakeWords()
        assertEquals(1, pendingWakeWords.size)
        assertEquals("wake_$wakeId", pendingWakeWords.first().resolvedRemoteId)
        assertFalse(pendingWakeWords.first().isDeleted)

        wakeWordRepository.markWakeWordSynced(id = wakeId, remoteId = "wake_$wakeId", userId = uid, syncedAt = 12_000L)
        assertTrue(wakeWordRepository.getPendingSyncWakeWords().isEmpty())
    }

    @Test
    fun `Last-Write-Wins upsertRemoteChat, upsertRemoteMessage, and upsertRemoteWakeWord resolve conflicts deterministically`() = runBlocking {
        val uid = "user_beta_456"

        // Upsert remote chat into empty Room cache
        val remoteChatDoc = FirestoreChatDoc(
            id = "chat_42",
            userId = uid,
            localChatId = 42L,
            title = "Cloud Research",
            wakeWord = "Research Mode",
            systemInstruction = "Cite primary sources.",
            themeColorHex = "#10B981",
            iconName = "Book",
            isDefaultWakeChat = false,
            isPersistent = true
        )
        val mappedChat = remoteChatDoc.toChatSession().copy(lastUpdatedAt = 20_000L)
        val localChatId = chatRepository.upsertRemoteChat(mappedChat)
        assertEquals(42L, localChatId)

        val storedChat = chatRepository.getChatByIdSync(42L)
        assertNotNull(storedChat)
        assertEquals("Cloud Research", storedChat?.title)
        assertEquals(SyncStatus.SYNCED, storedChat?.syncStatus)
        assertEquals("chat_42", storedChat?.remoteId)

        // Newer remote update overwrites older local record
        val newerRemoteChat = mappedChat.copy(
            title = "Cloud Research v2",
            wakeWord = "Deep Research",
            lastUpdatedAt = 30_000L
        )
        chatRepository.upsertRemoteChat(newerRemoteChat)
        assertEquals("Cloud Research v2", chatRepository.getChatByIdSync(42L)?.title)

        // If local chat has an un-synced DIRTY edit at timestamp 50_000L, an older remote snapshot (40_000L) does not overwrite it
        database.chatDao().updateChat(
            chatRepository.getChatByIdSync(42L)!!.copy(
                title = "Local Dirty Edit",
                lastUpdatedAt = 50_000L,
                syncStatus = SyncStatus.DIRTY
            )
        )
        chatRepository.upsertRemoteChat(newerRemoteChat.copy(title = "Stale Remote Snapshot", lastUpdatedAt = 40_000L))
        assertEquals("Local Dirty Edit", chatRepository.getChatByIdSync(42L)?.title)

        // Remote message upsert & Last-Write-Wins via MessageRepository
        val remoteMsgDoc = FirestoreMessageDoc(
            id = "msg_99",
            userId = uid,
            localChatId = 42L,
            localMessageId = 99L,
            sender = "gemini",
            content = "Initial cloud response",
            triggeredByWakeWord = true,
            messageTimestamp = 25_000L
        )
        val mappedMsg = remoteMsgDoc.toChatMessage().copy(updatedAt = 25_000L)
        val msgId = messageRepository.upsertRemoteMessage(mappedMsg)
        assertEquals(99L, msgId)
        assertEquals("Initial cloud response", messageRepository.getMessageByIdSync(99L)?.content)

        // Remote wake word upsert & Last-Write-Wins
        val remoteWakeDoc = FirestoreWakeWordDoc(
            id = "wake_77",
            userId = uid,
            localWakeWordId = 77L,
            phrase = "Deep Research",
            triggerType = TriggerType.CHAT.name,
            chatId = 42L,
            chatTitle = "Cloud Research v2",
            isEnabled = true,
            sensitivity = 0.88,
            metadata = "Cloud Research v2"
        )
        val mappedWake = remoteWakeDoc.toWakeWord().copy(updatedAt = 28_000L)
        val wakeId = wakeWordRepository.upsertRemoteWakeWord(mappedWake)
        assertEquals(77L, wakeId)
        val storedWake = database.wakeWordDao().getWakeWordByIdSync(77L)
        assertNotNull(storedWake)
        assertEquals("Deep Research", storedWake?.phrase)
        assertEquals(SyncStatus.SYNCED, storedWake?.syncStatus)
        assertEquals(0.88f, storedWake?.sensitivity ?: 0f, 0.001f)
    }

    @Test
    fun `soft-delete tombstones hide Chat, Message, and WakeWord from active flows and expose pending deletion queue`() = runBlocking {
        val uid = "user_gamma_789"
        val chatId = chatRepository.saveChat(
            Chat(
                title = "Voice Sandbox",
                wakeWord = "Hey Sandbox",
                isDefaultWakeChat = false,
                isPersistent = true
            )
        )
        val msgId = messageRepository.insertMessage(
            Message(
                chatId = chatId,
                sender = "user",
                content = "Test message for soft delete",
                timestamp = 60_000L
            )
        )
        val wakeId = wakeWordRepository.addCustomChatWakeWord(
            chatId = chatId,
            chatTitle = "Voice Sandbox",
            phrase = "Hey Sandbox",
            sensitivity = 0.79f
        )

        chatRepository.markChatSynced(chatId, "chat_$chatId", uid, 61_000L)
        messageRepository.markMessageSynced(msgId, "msg_$msgId", uid, 61_000L)
        wakeWordRepository.markWakeWordSynced(wakeId, "wake_$wakeId", uid, 61_000L)

        // Soft-delete wake word and message first
        wakeWordRepository.softDeleteWakeWord(wakeId)
        messageRepository.softDeleteMessage(msgId)

        assertTrue("Soft-deleted wake word should be hidden from active Flow", wakeWordRepository.allWakeWords.first().isEmpty())
        assertTrue("Soft-deleted message should be hidden from active chat messages Flow", messageRepository.getMessagesForChat(chatId).first().isEmpty())

        val pendingDeletedWakeWords = wakeWordRepository.getPendingDeletedWakeWords()
        assertEquals(1, pendingDeletedWakeWords.size)
        assertTrue(pendingDeletedWakeWords.first().isDeleted)
        assertTrue(pendingDeletedWakeWords.first().needsSync)

        val pendingDeletedMessages = messageRepository.getPendingDeletedMessages()
        assertEquals(1, pendingDeletedMessages.size)
        assertTrue(pendingDeletedMessages.first().isDeleted)
        assertTrue(pendingDeletedMessages.first().needsSync)

        // Restore soft-deleted message and wake word via repositories
        messageRepository.restoreSoftDeletedMessage(msgId)
        wakeWordRepository.restoreSoftDeletedWakeWord(wakeId)
        assertEquals(1, messageRepository.getMessagesForChat(chatId).first().size)
        assertEquals(1, wakeWordRepository.allWakeWords.first().size)

        // Soft-delete again for purge verification
        wakeWordRepository.softDeleteWakeWord(wakeId)
        messageRepository.softDeleteMessage(msgId)

        // Soft-delete parent chat
        chatRepository.softDeleteChat(chatId)
        assertTrue("Soft-deleted chat should be hidden from active allChats Flow", chatRepository.allChats.first().isEmpty())

        val pendingDeletedChats = chatRepository.getPendingDeletedChats()
        assertEquals(1, pendingDeletedChats.size)
        assertTrue(pendingDeletedChats.first().isDeleted)
        assertEquals(SyncStatus.DIRTY, pendingDeletedChats.first().syncStatus)

        // Purge tombstones after remote Firestore deletion
        wakeWordRepository.purgeDeletedWakeWord(wakeId)
        messageRepository.purgeDeletedMessage(msgId)
        chatRepository.purgeDeletedChat(chatId)

        assertTrue(wakeWordRepository.getPendingDeletedWakeWords().isEmpty())
        assertTrue(messageRepository.getPendingDeletedMessages().isEmpty())
        assertTrue(chatRepository.getPendingDeletedChats().isEmpty())
        assertNull(chatRepository.getChatByIdSync(chatId))
    }

    @Test
    fun `AppDatabase exposes chatDao, messageDao, and wakeWordDao and TypeConverters round-trip cleanly`() = runBlocking {
        val chatDao = database.chatDao()
        val messageDao = database.messageDao()
        val wakeWordDao = database.wakeWordDao()

        assertNotNull(chatDao)
        assertNotNull(messageDao)
        assertNotNull(wakeWordDao)

        val chatId = chatDao.insertChat(
            Chat(
                title = "RoomDatabase Access Point Test",
                wakeWord = "Hey Room",
                isDefaultWakeChat = true,
                isPersistent = true
            )
        )
        val msgId = messageDao.insertMessage(
            Message(
                chatId = chatId,
                sender = "user",
                content = "Testing MessageDao from AppDatabase",
                timestamp = 70_000L,
                updatedAt = 70_000L
            )
        )
        val wakeId = wakeWordDao.insertWakeWord(
            WakeWord(
                phrase = "Hey Room",
                triggerType = TriggerType.CHAT,
                chatId = chatId,
                chatTitle = "RoomDatabase Access Point Test"
            )
        )

        assertEquals(1, messageDao.getPendingSyncMessages().size)
        assertEquals(1, chatDao.observePendingSyncChatCount().first())
        assertEquals(1, messageDao.observePendingSyncMessageCount().first())
        assertEquals(1, wakeWordDao.observePendingSyncWakeWordCount().first())

        messageDao.updateMessageSyncState(msgId, "msg_$msgId", "user_room_1", SyncStatus.SYNCED, 71_000L)
        assertTrue(messageDao.getPendingSyncMessages().isEmpty())
        assertEquals(0, messageDao.observePendingSyncMessageCount().first())
        assertNotNull(wakeWordDao.getWakeWordByIdSync(wakeId))

        // Test soft-delete and restore on ChatDao, MessageDao, and WakeWordDao
        chatDao.markChatSoftDeleted(chatId, 72_000L)
        messageDao.markMessageSoftDeleted(msgId, 72_000L)
        wakeWordDao.markWakeWordSoftDeleted(wakeId, 72_000L)
        assertTrue(chatDao.getAllChatsSync().isEmpty())
        assertTrue(messageDao.getMessagesForChatSync(chatId).isEmpty())
        assertTrue(wakeWordDao.getAllWakeWordsSync().isEmpty())

        chatDao.restoreSoftDeletedChat(chatId, 73_000L)
        messageDao.restoreSoftDeletedMessage(msgId, 73_000L)
        wakeWordDao.restoreSoftDeletedWakeWord(wakeId, 73_000L)
        assertEquals(1, chatDao.getAllChatsSync().size)
        assertEquals(1, messageDao.getMessagesForChatSync(chatId).size)
        assertEquals(1, wakeWordDao.getAllWakeWordsSync().size)

        // Test sync failure marking
        chatDao.markChatSyncFailed(chatId)
        messageDao.markMessageSyncFailed(msgId)
        wakeWordDao.markWakeWordSyncFailed(wakeId)
        assertEquals(SyncStatus.FAILED, chatDao.getChatByIdSync(chatId)?.syncStatus)
        assertEquals(SyncStatus.FAILED, messageDao.getMessageByIdSync(msgId)?.syncStatus)
        assertEquals(SyncStatus.FAILED, wakeWordDao.getWakeWordByIdSync(wakeId)?.syncStatus)

        val converters = AppDatabase.Converters()
        assertEquals(SyncState.DIRTY, converters.toSyncState(converters.fromSyncState(SyncState.DIRTY)))
        assertEquals(TriggerType.HANDS_FREE, converters.toTriggerType(converters.fromTriggerType(TriggerType.HANDS_FREE)))
    }

    @Test
    fun `WakeWordManager uses WakeWordDao to persist, retrieve, update, enable, disable, and match custom wake words`() = runBlocking {
        // 1. Create a chat via ChatRepository and register it with WakeWordManager
        val chatId = chatRepository.saveChat(
            Chat(
                title = "Orion Assistant",
                wakeWord = "Hey Orion",
                isDefaultWakeChat = true,
                isPersistent = true
            )
        )
        val savedChat = chatRepository.getChatByIdSync(chatId)!!
        wakeWordManager.updateActiveChats(listOf(savedChat))

        // 2. Persist custom wake word configuration through WakeWordManager -> WakeWordDao
        val wakeId = wakeWordManager.persistCustomWakeWord(
            phrase = "Captain Orion",
            chatId = chatId,
            chatTitle = "Orion Assistant",
            triggerType = TriggerType.CHAT,
            sensitivity = 0.85f,
            userId = "user_orion_1"
        )
        assertTrue("WakeWordManager should persist custom wake word and return positive ID", wakeId > 0L)

        // Verify WakeWordManager retrieved and cached the persisted custom wake word
        val retrievedCustom = wakeWordManager.retrieveCustomWakeWords()
        assertEquals(1, retrievedCustom.size)
        assertEquals("Captain Orion", retrievedCustom.first().phrase)
        assertEquals(0.85f, retrievedCustom.first().sensitivity, 0.001f)
        assertEquals("wake_$wakeId", retrievedCustom.first().resolvedRemoteId)

        // Verify transcript matching uses the WakeWordDao-persisted custom wake word
        val matchResult = wakeWordManager.matchWakePhraseInTranscript("Hey Captain Orion summarize the release notes")
        assertNotNull(matchResult)
        assertEquals("Captain Orion", matchResult?.matchedWakeWord)
        assertEquals(chatId, matchResult?.matchedChat?.id)
        assertEquals("summarize the release notes", matchResult?.followUpQuery)

        // 3. Update custom wake word phrase and sensitivity via WakeWordManager -> WakeWordDao
        val updatedOk = wakeWordManager.updateCustomWakeWordPhrase(wakeId, "Starlight")
        assertTrue(updatedOk)
        wakeWordManager.updateCustomWakeWordSensitivity(0.90f)
        assertEquals(0.90f, wakeWordManager.wakeWordSensitivity, 0.001f)

        val afterUpdate = wakeWordManager.retrieveCustomWakeWordsForChat(chatId)
        assertEquals(1, afterUpdate.size)
        assertEquals("Starlight", afterUpdate.first().phrase)
        assertEquals(0.90f, afterUpdate.first().sensitivity, 0.001f)

        // 4. Disable custom wake word and verify transcript matcher ignores disabled wake word
        wakeWordManager.setCustomWakeWordEnabled(wakeId, false)
        assertFalse(wakeWordManager.retrieveCustomWakeWords().first().isEnabled)
        assertNull(wakeWordManager.matchWakePhraseInTranscript("Starlight run diagnostics"))

        // Re-enable custom wake word
        wakeWordManager.setCustomWakeWordEnabled(wakeId, true)
        assertNotNull(wakeWordManager.matchWakePhraseInTranscript("Starlight run diagnostics"))

        // 5. Persist system hands-free trigger via WakeWordManager -> WakeWordDao
        val dictateId = wakeWordManager.persistSystemTrigger(WakeWord.ROLE_DICTATE, "Begin Dictation", 0.82f)
        assertTrue(dictateId > 0L)
        assertEquals("Begin Dictation", wakeWordManager.dictateTrigger)

        // 6. Soft-delete and restore custom wake word via WakeWordManager -> WakeWordDao
        wakeWordManager.deleteCustomWakeWord(wakeId, softDelete = true)
        assertTrue(wakeWordManager.retrieveCustomWakeWords().isEmpty())
        assertEquals(1, wakeWordRepository.getPendingDeletedWakeWords().size)

        wakeWordManager.restoreSoftDeletedWakeWord(wakeId)
        assertEquals(1, wakeWordManager.retrieveCustomWakeWords().size)
    }
}

