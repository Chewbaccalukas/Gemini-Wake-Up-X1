package com.example.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.SyncStatus
import com.example.data.model.VoiceCommandEntity
import com.example.data.model.WakeWordSample
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for [ChatSession] (`Chat`) and related session tables,
 * supporting full CRUD, soft-delete tombstones (`isDeleted`), and hybrid Room + Cloud Firestore sync.
 */
@Dao
interface ChatDao {
    // =========================================================================
    // Chat (`ChatSession`) Reactive Flow & Lookup Queries
    // =========================================================================

    @Query("SELECT * FROM chat_sessions WHERE isDeleted = 0 ORDER BY isPinned DESC, lastUpdatedAt DESC")
    fun getAllChats(): Flow<List<ChatSession>>

    @Query("SELECT * FROM chat_sessions WHERE isDeleted = 0 ORDER BY isPinned DESC, lastUpdatedAt DESC")
    fun observeAllChats(): Flow<List<ChatSession>>

    @Query("SELECT * FROM chat_sessions WHERE id = :id AND isDeleted = 0 LIMIT 1")
    fun getChatById(id: Long): Flow<ChatSession?>

    @Query("SELECT COUNT(*) FROM chat_sessions WHERE isPersistent = 1 AND (syncStatus != 'SYNCED' OR isDeleted = 1)")
    fun observePendingSyncChatCount(): Flow<Int>

    @Query("SELECT * FROM chat_sessions WHERE id = :id LIMIT 1")
    suspend fun getChatByIdSync(id: Long): ChatSession?

    @Query("SELECT * FROM chat_sessions WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getChatByRemoteIdSync(remoteId: String): ChatSession?

    @Query("SELECT * FROM chat_sessions WHERE isDefaultWakeChat = 1 AND isDeleted = 0 LIMIT 1")
    suspend fun getDefaultWakeChat(): ChatSession?

    @Query("SELECT * FROM chat_sessions WHERE isDeleted = 0")
    suspend fun getAllChatsSync(): List<ChatSession>

    // =========================================================================
    // Chat Sync Queue, State Transitions & Account Re-Binding
    // =========================================================================

    @Query("SELECT * FROM chat_sessions WHERE isPersistent = 1 AND isDeleted = 0 AND syncStatus != 'SYNCED' ORDER BY lastUpdatedAt ASC")
    suspend fun getPendingSyncChats(): List<ChatSession>

    @Query("SELECT * FROM chat_sessions WHERE isDeleted = 1 AND syncStatus != 'SYNCED' ORDER BY lastUpdatedAt ASC")
    suspend fun getPendingDeletedChats(): List<ChatSession>

    @Query("UPDATE chat_sessions SET userId = :userId, syncStatus = CASE WHEN userId IS NULL OR userId != :userId THEN 'PENDING' ELSE syncStatus END WHERE isPersistent = 1 AND isDeleted = 0 AND (userId IS NULL OR userId != :userId)")
    suspend fun rebindChatsForUser(userId: String)

    @Query("UPDATE chat_messages SET userId = :userId, syncStatus = CASE WHEN userId IS NULL OR userId != :userId THEN 'PENDING' ELSE syncStatus END WHERE isDeleted = 0 AND (userId IS NULL OR userId != :userId)")
    suspend fun rebindMessagesForUser(userId: String)

    @Query("UPDATE chat_sessions SET remoteId = :remoteId, userId = :userId, syncStatus = :syncStatus, lastSyncedAt = :syncedAt WHERE id = :id")
    suspend fun updateChatSyncState(
        id: Long,
        remoteId: String,
        userId: String?,
        syncStatus: String = SyncStatus.SYNCED,
        syncedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE chat_sessions SET syncStatus = 'FAILED' WHERE id = :id")
    suspend fun markChatSyncFailed(id: Long)

    // =========================================================================
    // Chat Soft-Delete Tombstones, Restore & Purge Operations
    // =========================================================================

    @Query("UPDATE chat_sessions SET isDeleted = 1, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END, lastUpdatedAt = :updatedAt WHERE id = :id")
    suspend fun markChatSoftDeleted(id: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE chat_messages SET isDeleted = 1, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END, updatedAt = :updatedAt WHERE chatId = :chatId")
    suspend fun markMessagesForChatSoftDeleted(chatId: Long, updatedAt: Long = System.currentTimeMillis())

    @Transaction
    suspend fun softDeleteChatAndMessages(chatId: Long, updatedAt: Long = System.currentTimeMillis()) {
        markMessagesForChatSoftDeleted(chatId, updatedAt)
        markChatSoftDeleted(chatId, updatedAt)
    }

    @Query("UPDATE chat_sessions SET isDeleted = 0, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END, lastUpdatedAt = :updatedAt WHERE id = :id")
    suspend fun restoreSoftDeletedChat(id: Long, updatedAt: Long = System.currentTimeMillis())

    @Transaction
    suspend fun purgeDeletedChat(chatId: Long) {
        deleteMessagesForChat(chatId)
        deleteChatById(chatId)
    }

    @Query("DELETE FROM chat_sessions WHERE isDeleted = 1 AND syncStatus = 'SYNCED'")
    suspend fun purgeAllSyncedDeletedChats()

    // =========================================================================
    // Chat CRUD & Last-Write-Wins Remote Upsert
    // =========================================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatSession): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChats(chats: List<ChatSession>): List<Long>

    @Update
    suspend fun updateChat(chat: ChatSession)

    @Delete
    suspend fun deleteChat(chat: ChatSession)

    /**
     * Merges an incoming remote Cloud Firestore chat document into the local Room cache
     * using Last-Write-Wins conflict resolution on [ChatSession.lastUpdatedAt], preserving
     * local-only fields (`themeMode`, `totalTrackedSeconds`) if a local row already exists.
     */
    @Transaction
    suspend fun upsertRemoteChat(remoteChat: ChatSession): Long {
        val resolvedRemoteId = remoteChat.resolvedRemoteId
        val existing = (if (remoteChat.id > 0L) getChatByIdSync(remoteChat.id) else null)
            ?: getChatByRemoteIdSync(resolvedRemoteId)

        val now = System.currentTimeMillis()
        if (existing == null) {
            val toInsert = remoteChat.copy(
                remoteId = resolvedRemoteId,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = if (remoteChat.lastSyncedAt > 0L) remoteChat.lastSyncedAt else now,
                isDeleted = false
            )
            return insertChat(toInsert)
        }

        // Last-Write-Wins: only apply remote update if its timestamp is >= local lastUpdatedAt
        // or if local record is already in SYNCED state
        if (remoteChat.lastUpdatedAt >= existing.lastUpdatedAt || existing.syncStatus == SyncStatus.SYNCED) {
            val merged = existing.copy(
                title = remoteChat.title,
                wakeWord = remoteChat.wakeWord,
                isDefaultWakeChat = remoteChat.isDefaultWakeChat,
                isPersistent = remoteChat.isPersistent,
                systemInstruction = remoteChat.systemInstruction,
                greetingMessage = remoteChat.greetingMessage.ifBlank { existing.greetingMessage },
                iconName = remoteChat.iconName,
                themeColorHex = remoteChat.themeColorHex,
                lastUpdatedAt = maxOf(remoteChat.lastUpdatedAt, existing.lastUpdatedAt),
                remoteId = resolvedRemoteId,
                userId = remoteChat.userId ?: existing.userId,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = if (remoteChat.lastSyncedAt > 0L) remoteChat.lastSyncedAt else now,
                isDeleted = remoteChat.isDeleted
            )
            updateChat(merged)
        }
        return existing.id
    }

    @Query("UPDATE chat_sessions SET isDefaultWakeChat = 0")
    suspend fun clearDefaultWakeFlags()

    @Transaction
    suspend fun setDefaultWakeChat(chatId: Long) {
        clearDefaultWakeFlags()
        setChatAsDefault(chatId)
    }

    @Query("UPDATE chat_sessions SET isDefaultWakeChat = 1, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END WHERE id = :chatId")
    suspend fun setChatAsDefault(chatId: Long)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun deleteChatById(id: Long)

    @Query("DELETE FROM chat_messages WHERE chatId = :chatId")
    suspend fun deleteMessagesForChat(chatId: Long)

    @Transaction
    suspend fun deleteChatAndMessages(chatId: Long) {
        deleteMessagesForChat(chatId)
        deleteChatById(chatId)
    }

    // =========================================================================
    // Messages (bounded to most recent 100 items to prevent OutOfMemoryError)
    // =========================================================================

    @Query("SELECT * FROM chat_messages WHERE isDeleted = 0 ORDER BY timestamp DESC LIMIT 100")
    fun getAllMessages(): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE isDeleted = 0 ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentMessages(limit: Int = 100): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_sessions WHERE isDeleted = 0 AND id != :excludeChatId AND TRIM(wakeWord) = TRIM(:wakeWord) COLLATE NOCASE LIMIT 1")
    suspend fun findChatByWakeWordSync(wakeWord: String, excludeChatId: Long = -1L): ChatSession?

    @Query("SELECT * FROM chat_messages WHERE chatId = :chatId AND isDeleted = 0 ORDER BY timestamp ASC, id ASC")
    fun getMessagesForChat(chatId: Long): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE chatId = :chatId AND isDeleted = 0 ORDER BY timestamp ASC, id ASC")
    suspend fun getMessagesForChatSync(chatId: Long): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE chatId = :chatId AND isDeleted = 0 ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getRecentMessages(chatId: Long, limit: Int = 30): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE chatId = :chatId AND isDeleted = 0 AND timestamp < :beforeTimestamp ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getMessagesBefore(chatId: Long, beforeTimestamp: Long, limit: Int = 30): List<ChatMessage>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE chatId = :chatId AND isDeleted = 0")
    suspend fun getMessageCountForChatSync(chatId: Long): Int

    @Query("SELECT COUNT(*) FROM chat_messages WHERE chatId = :chatId AND isDeleted = 0 AND timestamp < :beforeTimestamp")
    suspend fun countMessagesBefore(chatId: Long, beforeTimestamp: Long): Int

    @Query("SELECT * FROM chat_messages WHERE isDeleted = 0 ORDER BY timestamp ASC, id ASC")
    suspend fun getAllActiveMessagesChronologicalSync(): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE id = :id LIMIT 1")
    suspend fun getMessageByIdSync(id: Long): ChatMessage?

    @Query("SELECT * FROM chat_messages WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getMessageByRemoteIdSync(remoteId: String): ChatMessage?

    @Query("SELECT * FROM chat_messages WHERE isDeleted = 0 AND syncStatus != 'SYNCED' ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getPendingSyncMessages(limit: Int = 100): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE isDeleted = 1 AND syncStatus != 'SYNCED' ORDER BY updatedAt ASC LIMIT :limit")
    suspend fun getPendingDeletedMessages(limit: Int = 100): List<ChatMessage>

    @Query("UPDATE chat_messages SET isDeleted = 1, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END, updatedAt = :updatedAt WHERE id = :messageId")
    suspend fun markMessageSoftDeleted(messageId: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE chat_messages SET isDeleted = 0, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END, updatedAt = :updatedAt WHERE id = :messageId")
    suspend fun restoreSoftDeletedMessage(messageId: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE chat_messages SET remoteId = :remoteId, userId = :userId, syncStatus = :syncStatus, lastSyncedAt = :syncedAt WHERE id = :id")
    suspend fun updateMessageSyncState(
        id: Long,
        remoteId: String,
        userId: String?,
        syncStatus: String = SyncStatus.SYNCED,
        syncedAt: Long = System.currentTimeMillis()
    )

    @Query("SELECT * FROM chat_messages WHERE chatId = :chatId AND isDeleted = 0 ORDER BY timestamp DESC LIMIT 1")
    fun getLastMessageForChat(chatId: Long): Flow<ChatMessage?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: ChatMessage): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessages(messages: List<ChatMessage>): List<Long>

    @Update
    suspend fun updateMessage(message: ChatMessage)

    /**
     * Atomically verifies the parent chat session exists and the message has persistable text or
     * a valid non-audio attachment before inserting a message turn.
     * Increments [ChatSession.totalVoiceCommands] when [ChatMessage.triggeredByWakeWord] is true.
     */
    @Transaction
    suspend fun insertMessageSafely(message: ChatMessage): Long {
        val sanitized = message.sanitizeForPersistence()
        if (!sanitized.hasPersistableContent) return -1L
        val parent = getChatByIdSync(sanitized.chatId) ?: return -1L
        val id = insertMessage(sanitized)
        if (id <= 0L) return -1L
        if (sanitized.triggeredByWakeWord) {
            incrementChatVoiceCommands(parent.id, sanitized.timestamp)
        } else {
            updateChatTimestamp(parent.id, sanitized.timestamp)
        }
        return id
    }

    /**
     * Merges an incoming remote Cloud Firestore message document into the local Room cache
     * using Last-Write-Wins conflict resolution on [ChatMessage.updatedAt] while verifying
     * that the parent [ChatSession] exists locally.
     */
    @Transaction
    suspend fun upsertRemoteMessage(remoteMessage: ChatMessage): Long {
        val sanitizedRemote = remoteMessage.sanitizeForPersistence()
        if (!sanitizedRemote.hasPersistableContent && !sanitizedRemote.isDeleted) return -1L
        val parent = getChatByIdSync(sanitizedRemote.chatId) ?: return -1L
        val resolvedRemoteId = sanitizedRemote.resolvedRemoteId
        val existing = (if (sanitizedRemote.id > 0L) getMessageByIdSync(sanitizedRemote.id) else null)
            ?: getMessageByRemoteIdSync(resolvedRemoteId)
        val now = System.currentTimeMillis()

        if (existing == null) {
            val toInsert = sanitizedRemote.copy(
                remoteId = resolvedRemoteId,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = if (sanitizedRemote.lastSyncedAt > 0L) sanitizedRemote.lastSyncedAt else now,
                isDeleted = false
            )
            val id = insertMessage(toInsert)
            if (id > 0L && sanitizedRemote.timestamp > parent.lastUpdatedAt) {
                updateChatTimestamp(sanitizedRemote.chatId, sanitizedRemote.timestamp)
            }
            return id
        }

        if (sanitizedRemote.updatedAt >= existing.updatedAt || existing.syncStatus == SyncStatus.SYNCED) {
            val merged = existing.copy(
                sender = sanitizedRemote.sender,
                content = sanitizedRemote.content,
                timestamp = sanitizedRemote.timestamp,
                triggeredByWakeWord = sanitizedRemote.triggeredByWakeWord,
                updatedAt = maxOf(sanitizedRemote.updatedAt, existing.updatedAt),
                remoteId = resolvedRemoteId,
                userId = sanitizedRemote.userId ?: existing.userId,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = if (sanitizedRemote.lastSyncedAt > 0L) sanitizedRemote.lastSyncedAt else now,
                isDeleted = sanitizedRemote.isDeleted
            )
            updateMessage(merged)
        }
        return existing.id
    }

    /**
     * Atomically verifies the parent chat session exists before inserting a batch of messages.
     * Filters out empty or legacy audio-placeholder-only messages and increments [ChatSession.totalVoiceCommands]
     * for newly inserted wake-word-triggered turns.
     */
    @Transaction
    suspend fun insertMessagesSafely(messages: List<ChatMessage>): List<Long> {
        if (messages.isEmpty()) return emptyList()
        val chatId = messages.first().chatId
        val parent = getChatByIdSync(chatId) ?: return emptyList()
        val validMessages = messages
            .map { it.sanitizeForPersistence() }
            .filter { it.chatId == chatId && it.hasPersistableContent }
        if (validMessages.isEmpty()) return emptyList()
        val ids = insertMessages(validMessages)
        val insertedMessages = validMessages.zip(ids).filter { (_, id) -> id > 0L }.map { it.first }
        if (insertedMessages.isNotEmpty()) {
            val maxTimestamp = insertedMessages.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis()
            val wakeCount = insertedMessages.count { it.triggeredByWakeWord }
            if (wakeCount > 0) {
                incrementChatVoiceCommandsBy(parent.id, wakeCount, maxTimestamp)
            } else {
                updateChatTimestamp(parent.id, maxTimestamp)
            }
        }
        return ids
    }

    @Query("UPDATE chat_sessions SET lastUpdatedAt = :timestamp, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END WHERE id = :chatId")
    suspend fun updateChatTimestamp(chatId: Long, timestamp: Long)

    @Query("UPDATE chat_sessions SET totalVoiceCommands = totalVoiceCommands + 1, lastUpdatedAt = :timestamp, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END WHERE id = :chatId")
    suspend fun incrementChatVoiceCommands(chatId: Long, timestamp: Long)

    @Query("UPDATE chat_sessions SET totalVoiceCommands = totalVoiceCommands + :delta, lastUpdatedAt = :timestamp, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END WHERE id = :chatId")
    suspend fun incrementChatVoiceCommandsBy(chatId: Long, delta: Int, timestamp: Long)

    @Query("UPDATE chat_sessions SET totalTrackedSeconds = :seconds WHERE id = :chatId")
    suspend fun updateTrackedSeconds(chatId: Long, seconds: Long)

    @Query("UPDATE chat_sessions SET themeMode = :themeMode WHERE id = :chatId")
    suspend fun updateChatTheme(chatId: Long, themeMode: com.example.ui.theme.AppTheme)

    @Query("UPDATE chat_sessions SET isPinned = :isPinned, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END WHERE id = :chatId")
    suspend fun updateChatPinned(chatId: Long, isPinned: Boolean)

    @Query("UPDATE chat_sessions SET colorPaletteId = :colorPaletteId, backgroundStyle = :backgroundStyle, themeColorHex = :themeColorHex, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END WHERE id = :chatId")
    suspend fun updateChatAppearance(
        chatId: Long,
        colorPaletteId: String,
        backgroundStyle: String,
        themeColorHex: String
    )

    @Query("UPDATE chat_sessions SET greetingMessage = :greetingMessage, lastUpdatedAt = :updatedAt, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END WHERE id = :chatId")
    suspend fun updateChatGreeting(chatId: Long, greetingMessage: String, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: Long)

    /**
     * Deletes any legacy audio placeholder or empty text rows in `chat_messages` that do not have
     * a valid non-audio attachment.
     */
    @Query(
        """
        DELETE FROM chat_messages
        WHERE (
            TRIM(content) = ''
            OR LOWER(TRIM(content)) IN (
                '🎙️ [voice input audio turn]',
                '[voice input audio turn]',
                '[audio placeholder]',
                '[voice message]',
                '[audio turn]',
                '[audio]',
                '[voice turn]',
                'audio message',
                'voice message',
                'voice input audio turn'
            )
        )
        AND (
            attachmentName IS NULL
            OR TRIM(attachmentName) = ''
            OR LOWER(attachmentMimeType) LIKE 'audio/%'
            OR LOWER(attachmentName) LIKE '%.pcm'
            OR LOWER(attachmentName) LIKE '%.wav'
            OR LOWER(attachmentName) LIKE '%.m4a'
        )
        """
    )
    suspend fun pruneLegacyAudioPlaceholderMessages(): Int

    // =========================================================================
    // Voice Command History (bounded to most recent 100 items)
    // =========================================================================

    @Query("SELECT * FROM voice_command_history ORDER BY timestamp DESC LIMIT 100")
    fun getRecentVoiceCommands(): Flow<List<VoiceCommandEntity>>

    @Query("SELECT * FROM voice_command_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentVoiceCommandsSync(limit: Int = 100): List<VoiceCommandEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoiceCommand(command: VoiceCommandEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoiceCommands(commands: List<VoiceCommandEntity>)

    @Query("DELETE FROM voice_command_history WHERE id = :id")
    suspend fun deleteVoiceCommandById(id: String)

    @Query("DELETE FROM voice_command_history")
    suspend fun clearAllVoiceCommands()

    @Query(
        """
        DELETE FROM voice_command_history
        WHERE TRIM(recognizedTranscript) = ''
        OR LOWER(TRIM(recognizedTranscript)) IN (
            '🎙️ [voice input audio turn]',
            '[voice input audio turn]',
            '[audio placeholder]',
            '[voice message]',
            '[audio turn]',
            '[audio]',
            '[voice turn]',
            'audio message',
            'voice message',
            'voice input audio turn'
        )
        """
    )
    suspend fun pruneLegacyVoiceCommands(): Int

    // =========================================================================
    // Wake Word Samples
    // =========================================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWakeWordSample(sample: WakeWordSample): Long

    @Query("SELECT * FROM wake_word_samples ORDER BY recordedAt ASC")
    suspend fun getAllWakeWordSamples(): List<WakeWordSample>

    @Query("SELECT * FROM wake_word_samples ORDER BY recordedAt ASC")
    fun getAllWakeWordSamplesFlow(): Flow<List<WakeWordSample>>

    @Query("SELECT * FROM wake_word_samples WHERE chatId = :chatId ORDER BY recordedAt ASC")
    suspend fun getSamplesForChat(chatId: Long): List<WakeWordSample>

    @Query("SELECT * FROM wake_word_samples WHERE chatId = :chatId ORDER BY recordedAt ASC")
    fun getSamplesForChatFlow(chatId: Long): Flow<List<WakeWordSample>>

    @Query("SELECT * FROM wake_word_samples WHERE wakeWordId = :wakeWordId ORDER BY recordedAt ASC")
    suspend fun getSamplesForWakeWord(wakeWordId: Long): List<WakeWordSample>

    @Query("SELECT * FROM wake_word_samples WHERE wakeWordId = :wakeWordId ORDER BY recordedAt ASC")
    fun getSamplesForWakeWordFlow(wakeWordId: Long): Flow<List<WakeWordSample>>

    @Query("UPDATE wake_word_samples SET wakeWordId = :wakeWordId, wakePhrase = :wakePhrase WHERE chatId = :chatId AND (wakeWordId IS NULL OR wakeWordId = 0 OR wakeWordId = :wakeWordId)")
    suspend fun associateSamplesWithWakeWord(chatId: Long, wakeWordId: Long, wakePhrase: String)

    @Query("DELETE FROM wake_word_samples WHERE chatId = :chatId")
    suspend fun deleteSamplesForChat(chatId: Long)

    @Query("DELETE FROM wake_word_samples WHERE id = :sampleId")
    suspend fun deleteSampleById(sampleId: Long)
}
