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
import kotlinx.coroutines.flow.Flow

/**
 * Dedicated Data Access Object (DAO) for [ChatMessage] (`Message`) CRUD, soft-delete tombstones,
 * and hybrid Room + Cloud Firestore synchronization operations.
 */
@Dao
interface MessageDao {

    // =========================================================================
    // Reactive Flow & Lookup Queries
    // =========================================================================

    @Query("SELECT * FROM chat_messages WHERE isDeleted = 0 ORDER BY timestamp DESC LIMIT 100")
    fun getAllMessages(): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE isDeleted = 0 ORDER BY timestamp DESC LIMIT 100")
    fun observeAllMessages(): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE isDeleted = 0 AND timestamp >= :sinceTimestamp ORDER BY timestamp ASC, id ASC")
    fun observeActiveMessagesSince(sinceTimestamp: Long): Flow<List<ChatMessage>>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE isDeleted = 0")
    fun observeTotalActiveMessageCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE isDeleted = 0 AND triggeredByWakeWord = 1")
    fun observeTotalWakeWordMessageCount(): Flow<Int>

    @Query("SELECT * FROM chat_messages WHERE isDeleted = 0 ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentMessages(limit: Int = 100): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE chatId = :chatId AND isDeleted = 0 ORDER BY timestamp ASC, id ASC")
    fun getMessagesForChat(chatId: Long): Flow<List<ChatMessage>>

    @Query("SELECT * FROM chat_messages WHERE chatId = :chatId AND isDeleted = 0 ORDER BY timestamp DESC LIMIT 1")
    fun getLastMessageForChat(chatId: Long): Flow<ChatMessage?>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE syncStatus != 'SYNCED' OR isDeleted = 1")
    fun observePendingSyncMessageCount(): Flow<Int>

    @Query("SELECT * FROM chat_messages WHERE chatId = :chatId AND isDeleted = 0 ORDER BY timestamp ASC, id ASC")
    suspend fun getMessagesForChatSync(chatId: Long): List<ChatMessage>

    /**
     * Initial recent load for keyset pagination (Module 2 — Anti-Freeze Two-Tier Memory Buffer).
     * Returns the most recent [limit] messages ordered newest-first (`timestamp DESC`);
     * caller reverses in memory for chronological ascending display.
     */
    @Query("SELECT * FROM chat_messages WHERE chatId = :chatId AND isDeleted = 0 ORDER BY timestamp DESC, id DESC LIMIT :limit")
    suspend fun getRecentMessages(chatId: Long, limit: Int = 30): List<ChatMessage>

    /**
     * Keyset prefetch query for historical pagination (Module 2 — Anti-Freeze Two-Tier Memory Buffer).
     * Queries up to [limit] records strictly before [beforeTimestamp] (`timestamp < :beforeTimestamp`)
     * ordered newest-first (`timestamp DESC`); caller reverses in memory and prepends to hot window.
     */
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

    // =========================================================================
    // Sync Queue, State Transitions & Account Re-Binding
    // =========================================================================

    @Query("SELECT * FROM chat_messages WHERE isDeleted = 0 AND syncStatus != 'SYNCED' ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getPendingSyncMessages(limit: Int = 100): List<ChatMessage>

    @Query("SELECT * FROM chat_messages WHERE isDeleted = 1 AND syncStatus != 'SYNCED' ORDER BY updatedAt ASC LIMIT :limit")
    suspend fun getPendingDeletedMessages(limit: Int = 100): List<ChatMessage>

    @Query("UPDATE chat_messages SET userId = :userId, syncStatus = CASE WHEN userId IS NULL OR userId != :userId THEN 'PENDING' ELSE syncStatus END WHERE isDeleted = 0 AND (userId IS NULL OR userId != :userId)")
    suspend fun rebindMessagesForUser(userId: String)

    @Query("UPDATE chat_messages SET remoteId = :remoteId, userId = :userId, syncStatus = :syncStatus, lastSyncedAt = :syncedAt WHERE id = :id")
    suspend fun updateMessageSyncState(
        id: Long,
        remoteId: String,
        userId: String?,
        syncStatus: String = SyncStatus.SYNCED,
        syncedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE chat_messages SET syncStatus = 'FAILED' WHERE id = :id")
    suspend fun markMessageSyncFailed(id: Long)

    // =========================================================================
    // Soft-Delete Tombstones, Restore & Purge Operations
    // =========================================================================

    @Query("UPDATE chat_messages SET isDeleted = 1, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END, updatedAt = :updatedAt WHERE id = :messageId")
    suspend fun markMessageSoftDeleted(messageId: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE chat_messages SET isDeleted = 1, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END, updatedAt = :updatedAt WHERE chatId = :chatId")
    suspend fun markMessagesForChatSoftDeleted(chatId: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE chat_messages SET isDeleted = 0, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END, updatedAt = :updatedAt WHERE id = :messageId")
    suspend fun restoreSoftDeletedMessage(messageId: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun purgeDeletedMessage(messageId: Long)

    @Query("DELETE FROM chat_messages WHERE isDeleted = 1 AND syncStatus = 'SYNCED'")
    suspend fun purgeAllSyncedDeletedMessages()

    // =========================================================================
    // CRUD & Last-Write-Wins Remote Upsert
    // =========================================================================

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessage(message: ChatMessage): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMessages(messages: List<ChatMessage>): List<Long>

    @Update
    suspend fun updateMessage(message: ChatMessage)

    @Delete
    suspend fun deleteMessage(message: ChatMessage)

    @Query("SELECT * FROM chat_sessions WHERE id = :id LIMIT 1")
    suspend fun getParentChatByIdSync(id: Long): ChatSession?

    @Query("UPDATE chat_sessions SET lastUpdatedAt = :timestamp, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END WHERE id = :chatId")
    suspend fun updateParentChatTimestamp(chatId: Long, timestamp: Long)

    @Query("UPDATE chat_sessions SET totalVoiceCommands = totalVoiceCommands + 1, lastUpdatedAt = :timestamp, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END WHERE id = :chatId")
    suspend fun incrementParentChatVoiceCommands(chatId: Long, timestamp: Long)

    @Query("UPDATE chat_sessions SET totalVoiceCommands = totalVoiceCommands + :delta, lastUpdatedAt = :timestamp, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END WHERE id = :chatId")
    suspend fun incrementParentChatVoiceCommandsBy(chatId: Long, delta: Int, timestamp: Long)

    @Transaction
    suspend fun insertMessageSafely(message: ChatMessage): Long {
        val sanitized = message.sanitizeForPersistence()
        if (!sanitized.hasPersistableContent) return -1L
        val parent = getParentChatByIdSync(sanitized.chatId) ?: return -1L
        val id = insertMessage(sanitized)
        if (id <= 0L) return -1L
        if (sanitized.triggeredByWakeWord) {
            incrementParentChatVoiceCommands(parent.id, sanitized.timestamp)
        } else {
            updateParentChatTimestamp(parent.id, sanitized.timestamp)
        }
        return id
    }

    @Transaction
    suspend fun insertMessagesSafely(messages: List<ChatMessage>): List<Long> {
        if (messages.isEmpty()) return emptyList()
        val chatId = messages.first().chatId
        val parent = getParentChatByIdSync(chatId) ?: return emptyList()
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
                incrementParentChatVoiceCommandsBy(parent.id, wakeCount, maxTimestamp)
            } else {
                updateParentChatTimestamp(parent.id, maxTimestamp)
            }
        }
        return ids
    }

    @Transaction
    suspend fun upsertRemoteMessage(remoteMessage: ChatMessage): Long {
        val sanitizedRemote = remoteMessage.sanitizeForPersistence()
        if (!sanitizedRemote.hasPersistableContent && !sanitizedRemote.isDeleted) return -1L
        val parent = getParentChatByIdSync(sanitizedRemote.chatId) ?: return -1L
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
                updateParentChatTimestamp(sanitizedRemote.chatId, sanitizedRemote.timestamp)
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

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun deleteMessageById(messageId: Long)

    @Query("DELETE FROM chat_messages WHERE chatId = :chatId")
    suspend fun deleteMessagesForChat(chatId: Long)
}
