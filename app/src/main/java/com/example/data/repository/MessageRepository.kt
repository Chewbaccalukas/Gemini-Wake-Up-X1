package com.example.data.repository

import com.example.data.dao.ChatDao
import com.example.data.dao.MessageDao
import com.example.data.model.ChatMessage
import com.example.data.model.Message
import com.example.data.model.SyncStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Repository abstracting [MessageDao] operations for the [Message] ([ChatMessage]) entity,
 * providing a clean reactive and suspend API for ViewModels to perform CRUD, soft-delete
 * tombstone handling, and hybrid Room + Cloud Firestore synchronization.
 */
class MessageRepository(
    private val messageDao: MessageDao,
    private val chatDao: ChatDao? = null
) {

    internal fun sanitizeMessage(msg: ChatMessage, fallbackIndex: Int = 0): ChatMessage {
        val now = System.currentTimeMillis().coerceAtLeast(1L)
        val safeTs = if (msg.timestamp > 0L) msg.timestamp else (now + fallbackIndex)
        val safeUpdatedAt = if (msg.updatedAt > 0L) msg.updatedAt else safeTs
        val sanitized = msg.sanitizeForPersistence()
        return sanitized.copy(
            sender = sanitized.sender.ifBlank { "gemini" },
            timestamp = safeTs,
            updatedAt = safeUpdatedAt
        )
    }

    // =========================================================================
    // Reactive Flows
    // =========================================================================

    @get:JvmName("allMessagesFlow")
    val allMessages: Flow<List<Message>> = messageDao.getAllMessages()
        .map { list ->
            list.mapIndexed { idx, msg -> sanitizeMessage(msg, idx) }
                .filter { it.hasPersistableContent }
                .take(100)
        }
        .flowOn(Dispatchers.IO)
        .catch { emit(emptyList()) }

    val pendingSyncMessageCount: Flow<Int> = messageDao.observePendingSyncMessageCount()
        .flowOn(Dispatchers.IO)
        .catch { emit(0) }

    fun getAllMessages(): Flow<List<Message>> = allMessages

    fun observeAllMessages(): Flow<List<Message>> = allMessages

    fun getRecentMessages(limit: Int = 100): Flow<List<Message>> =
        messageDao.getRecentMessages(limit.coerceIn(1, 100))
            .map { list ->
                list.mapIndexed { idx, msg -> sanitizeMessage(msg, idx) }
                    .filter { it.hasPersistableContent }
            }
            .flowOn(Dispatchers.IO)
            .catch { emit(emptyList()) }

    fun getMessagesForChat(chatId: Long): Flow<List<Message>> =
        messageDao.getMessagesForChat(chatId)
            .map { list ->
                list.mapIndexed { idx, msg -> sanitizeMessage(msg, idx) }
                    .filter { it.hasPersistableContent }
                    .sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
            }
            .flowOn(Dispatchers.IO)
            .catch { emit(emptyList()) }

    fun observeMessagesForChat(chatId: Long): Flow<List<Message>> = getMessagesForChat(chatId)

    fun getLastMessageForChat(chatId: Long): Flow<Message?> =
        messageDao.getLastMessageForChat(chatId)
            .map { it?.let { msg -> sanitizeMessage(msg).takeIf { m -> m.hasPersistableContent } } }
            .flowOn(Dispatchers.IO)
            .catch { emit(null) }

    // =========================================================================
    // Synchronous / Suspend Lookups
    // =========================================================================

    suspend fun getMessagesForChatSync(chatId: Long): List<Message> = withContext(Dispatchers.IO) {
        try {
            messageDao.getMessagesForChatSync(chatId)
                .mapIndexed { idx, msg -> sanitizeMessage(msg, idx) }
                .filter { it.hasPersistableContent }
                .sortedWith(compareBy<Message> { it.timestamp }.thenBy { it.id })
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun getMessageByIdSync(id: Long): Message? = withContext(Dispatchers.IO) {
        try {
            messageDao.getMessageByIdSync(id)?.let { sanitizeMessage(it) }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun getMessageByRemoteIdSync(remoteId: String): Message? = withContext(Dispatchers.IO) {
        try {
            messageDao.getMessageByRemoteIdSync(remoteId)?.let { sanitizeMessage(it) }
        } catch (_: Throwable) {
            null
        }
    }

    // =========================================================================
    // CRUD Operations
    // =========================================================================

    suspend fun insertMessage(message: Message): Long = withContext(Dispatchers.IO) {
        try {
            val sanitized = sanitizeMessage(message)
            if (!sanitized.hasPersistableContent) {
                return@withContext -1L
            }
            val id = messageDao.insertMessageSafely(sanitized)
            if (id > 0L && sanitized.remoteId.isNullOrBlank()) {
                messageDao.updateMessageSyncState(
                    id = id,
                    remoteId = "msg_$id",
                    userId = sanitized.userId,
                    syncStatus = sanitized.syncStatus,
                    syncedAt = sanitized.lastSyncedAt
                )
            }
            id
        } catch (_: Throwable) {
            -1L
        }
    }

    suspend fun insertMessageSafely(message: Message): Long = insertMessage(message)

    suspend fun insertMessages(messages: List<Message>): List<Long> = withContext(Dispatchers.IO) {
        try {
            val sanitizedList = messages.mapIndexed { idx, m -> sanitizeMessage(m, idx) }
            val ids = messageDao.insertMessagesSafely(sanitizedList)
            ids.forEachIndexed { index, id ->
                val msg = sanitizedList.getOrNull(index)
                if (id > 0L && msg != null && msg.remoteId.isNullOrBlank()) {
                    messageDao.updateMessageSyncState(
                        id = id,
                        remoteId = "msg_$id",
                        userId = msg.userId,
                        syncStatus = msg.syncStatus,
                        syncedAt = msg.lastSyncedAt
                    )
                }
            }
            ids
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun updateMessage(message: Message) = withContext(Dispatchers.IO) {
        try {
            val clean = sanitizeMessage(message)
            val dirty = clean.copy(
                remoteId = clean.remoteId?.takeIf { it.isNotBlank() } ?: "msg_${clean.id}",
                syncStatus = if (clean.syncStatus == SyncStatus.SYNCED) SyncStatus.DIRTY else clean.syncStatus,
                updatedAt = maxOf(clean.updatedAt, System.currentTimeMillis())
            )
            messageDao.updateMessage(dirty)
        } catch (_: Throwable) {}
    }

    suspend fun deleteMessage(messageId: Long) = withContext(Dispatchers.IO) {
        try {
            messageDao.deleteMessageById(messageId)
        } catch (_: Throwable) {}
    }

    suspend fun clearChatMessages(chatId: Long) = withContext(Dispatchers.IO) {
        try {
            messageDao.deleteMessagesForChat(chatId)
        } catch (_: Throwable) {}
    }

    // =========================================================================
    // Soft-Delete Tombstones, Restore & Purge Operations
    // =========================================================================

    suspend fun softDeleteMessage(messageId: Long) = withContext(Dispatchers.IO) {
        try {
            messageDao.markMessageSoftDeleted(messageId, System.currentTimeMillis())
        } catch (_: Throwable) {}
    }

    suspend fun softDeleteMessagesForChat(chatId: Long) = withContext(Dispatchers.IO) {
        try {
            messageDao.markMessagesForChatSoftDeleted(chatId, System.currentTimeMillis())
        } catch (_: Throwable) {}
    }

    suspend fun restoreSoftDeletedMessage(messageId: Long) = withContext(Dispatchers.IO) {
        try {
            messageDao.restoreSoftDeletedMessage(messageId, System.currentTimeMillis())
        } catch (_: Throwable) {}
    }

    suspend fun purgeDeletedMessage(messageId: Long) = withContext(Dispatchers.IO) {
        try {
            messageDao.purgeDeletedMessage(messageId)
        } catch (_: Throwable) {}
    }

    suspend fun purgeAllSyncedDeletedMessages() = withContext(Dispatchers.IO) {
        try {
            messageDao.purgeAllSyncedDeletedMessages()
        } catch (_: Throwable) {}
    }

    // =========================================================================
    // Hybrid Cloud Sync & Account Re-Binding
    // =========================================================================

    suspend fun getPendingSyncMessages(limit: Int = 100): List<Message> = withContext(Dispatchers.IO) {
        try {
            messageDao.getPendingSyncMessages(limit).mapIndexed { idx, msg -> sanitizeMessage(msg, idx) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun getPendingDeletedMessages(limit: Int = 100): List<Message> = withContext(Dispatchers.IO) {
        try {
            messageDao.getPendingDeletedMessages(limit).mapIndexed { idx, msg -> sanitizeMessage(msg, idx) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun rebindLocalRecordsForUser(userId: String) = withContext(Dispatchers.IO) {
        if (userId.isBlank()) return@withContext
        try {
            messageDao.rebindMessagesForUser(userId)
        } catch (_: Throwable) {}
    }

    suspend fun markMessageSynced(
        id: Long,
        remoteId: String = "msg_$id",
        userId: String?,
        syncedAt: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        try {
            messageDao.updateMessageSyncState(
                id = id,
                remoteId = remoteId,
                userId = userId,
                syncStatus = SyncStatus.SYNCED,
                syncedAt = syncedAt
            )
        } catch (_: Throwable) {}
    }

    suspend fun markMessageSyncFailed(id: Long) = withContext(Dispatchers.IO) {
        try {
            messageDao.markMessageSyncFailed(id)
        } catch (_: Throwable) {}
    }

    suspend fun upsertRemoteMessage(remoteMessage: Message): Long = withContext(Dispatchers.IO) {
        try {
            messageDao.upsertRemoteMessage(sanitizeMessage(remoteMessage))
        } catch (_: Throwable) {
            -1L
        }
    }
}
