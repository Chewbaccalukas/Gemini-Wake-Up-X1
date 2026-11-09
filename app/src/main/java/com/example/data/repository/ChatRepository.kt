package com.example.data.repository

import com.example.data.dao.ChatDao
import com.example.data.dao.MessageDao
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.SyncStatus
import com.example.data.model.VoiceCommandEntity
import com.example.data.model.WakeWordSample
import com.example.util.VoiceCommandHistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ChatRepository(
    private val chatDao: ChatDao,
    private val messageDao: MessageDao? = null
) {

    private fun sanitizeChat(chat: ChatSession): ChatSession {
        val safeHex = chat.themeColorHex.trim().let { hex ->
            try {
                android.graphics.Color.parseColor(hex)
                hex
            } catch (_: Throwable) {
                "#38BDF8"
            }
        }
        val safeCreatedAt = if (chat.createdAt > 0L) chat.createdAt else System.currentTimeMillis()
        val safeUpdatedAt = if (chat.lastUpdatedAt > 0L) chat.lastUpdatedAt else safeCreatedAt
        return chat.copy(
            title = chat.title.ifBlank { "Gemini Chat" },
            wakeWord = chat.wakeWord.ifBlank { "Gemini" },
            greetingMessage = chat.greetingMessage.trim(),
            themeColorHex = safeHex,
            iconName = chat.iconName.ifBlank { "Sparkles" },
            createdAt = safeCreatedAt,
            lastUpdatedAt = safeUpdatedAt
        )
    }

    private fun sanitizeMessage(msg: ChatMessage, fallbackIndex: Int = 0): ChatMessage {
        val now = System.currentTimeMillis().coerceAtLeast(1L)
        val safeTs = if (msg.timestamp > 0L) msg.timestamp else (now + fallbackIndex)
        val sanitized = msg.sanitizeForPersistence()
        return sanitized.copy(
            sender = sanitized.sender.ifBlank { "gemini" },
            timestamp = safeTs
        )
    }

    suspend fun pruneLegacyAudioPlaceholders(): Int = withContext(Dispatchers.IO) {
        try {
            val prunedMessages = chatDao.pruneLegacyAudioPlaceholderMessages()
            val prunedCommands = chatDao.pruneLegacyVoiceCommands()
            prunedMessages + prunedCommands
        } catch (_: Throwable) {
            0
        }
    }

    val allChats: Flow<List<ChatSession>> = chatDao.getAllChats()
        .map { list -> list.map { sanitizeChat(it) } }
        .flowOn(Dispatchers.IO)
        .catch { emit(emptyList()) }

    val pendingSyncChatCount: Flow<Int> = chatDao.observePendingSyncChatCount()
        .flowOn(Dispatchers.IO)
        .catch { emit(0) }

    fun observeAllChats(): Flow<List<ChatSession>> = allChats

    fun getChatById(id: Long): Flow<ChatSession?> = chatDao.getChatById(id)
        .map { it?.let { chat -> sanitizeChat(chat) } }
        .flowOn(Dispatchers.IO)
        .catch { emit(null) }

    suspend fun getChatByIdSync(id: Long): ChatSession? = withContext(Dispatchers.IO) {
        try {
            chatDao.getChatByIdSync(id)?.let { sanitizeChat(it) }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun getDefaultWakeChat(): ChatSession? = withContext(Dispatchers.IO) {
        try {
            chatDao.getDefaultWakeChat()?.let { sanitizeChat(it) }
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun getAllChatsSync(): List<ChatSession> = withContext(Dispatchers.IO) {
        try {
            chatDao.getAllChatsSync().map { sanitizeChat(it) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun getPendingSyncChats(): List<ChatSession> = withContext(Dispatchers.IO) {
        try {
            chatDao.getPendingSyncChats().map { sanitizeChat(it) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun getPendingDeletedChats(): List<ChatSession> = withContext(Dispatchers.IO) {
        try {
            chatDao.getPendingDeletedChats().map { sanitizeChat(it) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun rebindLocalRecordsForUser(userId: String) = withContext(Dispatchers.IO) {
        if (userId.isBlank()) return@withContext
        try {
            chatDao.rebindChatsForUser(userId)
            chatDao.rebindMessagesForUser(userId)
        } catch (_: Throwable) {}
    }

    suspend fun markChatSynced(
        id: Long,
        remoteId: String = "chat_$id",
        userId: String?,
        syncedAt: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        try {
            chatDao.updateChatSyncState(
                id = id,
                remoteId = remoteId,
                userId = userId,
                syncStatus = SyncStatus.SYNCED,
                syncedAt = syncedAt
            )
        } catch (_: Throwable) {}
    }

    suspend fun markChatSyncFailed(id: Long) = withContext(Dispatchers.IO) {
        try {
            chatDao.markChatSyncFailed(id)
        } catch (_: Throwable) {}
    }

    suspend fun upsertRemoteChat(remoteChat: ChatSession): Long = withContext(Dispatchers.IO) {
        try {
            chatDao.upsertRemoteChat(sanitizeChat(remoteChat))
        } catch (_: Throwable) {
            remoteChat.id
        }
    }

    suspend fun saveChat(chat: ChatSession): Long = withContext(Dispatchers.IO) {
        try {
            val clean = sanitizeChat(chat)
            if (clean.id == 0L) {
                val pending = clean.copy(
                    syncStatus = SyncStatus.PENDING,
                    lastUpdatedAt = System.currentTimeMillis(),
                    isDeleted = false
                )
                val id = chatDao.insertChat(pending)
                val remoteId = pending.remoteId?.takeIf { it.isNotBlank() } ?: "chat_$id"
                chatDao.updateChatSyncState(
                    id = id,
                    remoteId = remoteId,
                    userId = pending.userId,
                    syncStatus = SyncStatus.PENDING,
                    syncedAt = pending.lastSyncedAt
                )
                if (clean.isDefaultWakeChat) {
                    chatDao.setDefaultWakeChat(id)
                }
                id
            } else {
                val dirty = clean.copy(
                    remoteId = clean.remoteId?.takeIf { it.isNotBlank() } ?: "chat_${clean.id}",
                    syncStatus = if (clean.syncStatus == SyncStatus.SYNCED) SyncStatus.DIRTY else clean.syncStatus,
                    lastUpdatedAt = maxOf(clean.lastUpdatedAt, System.currentTimeMillis())
                )
                chatDao.updateChat(dirty)
                if (clean.isDefaultWakeChat) {
                    chatDao.setDefaultWakeChat(clean.id)
                }
                clean.id
            }
        } catch (_: Throwable) {
            chat.id
        }
    }

    suspend fun setDefaultWakeChat(chatId: Long) = withContext(Dispatchers.IO) {
        try {
            chatDao.setDefaultWakeChat(chatId)
        } catch (_: Throwable) {}
    }

    suspend fun softDeleteChat(chatId: Long) = withContext(Dispatchers.IO) {
        try {
            chatDao.softDeleteChatAndMessages(chatId, System.currentTimeMillis())
        } catch (_: Throwable) {}
    }

    suspend fun restoreSoftDeletedChat(chatId: Long) = withContext(Dispatchers.IO) {
        try {
            chatDao.restoreSoftDeletedChat(chatId, System.currentTimeMillis())
        } catch (_: Throwable) {}
    }

    suspend fun purgeDeletedChat(chatId: Long) = withContext(Dispatchers.IO) {
        try {
            chatDao.deleteChatAndMessages(chatId)
        } catch (_: Throwable) {}
    }

    suspend fun purgeAllSyncedDeletedChats() = withContext(Dispatchers.IO) {
        try {
            chatDao.purgeAllSyncedDeletedChats()
        } catch (_: Throwable) {}
    }

    suspend fun deleteChat(chatId: Long) = withContext(Dispatchers.IO) {
        try {
            chatDao.deleteChatAndMessages(chatId)
        } catch (_: Throwable) {}
    }

    fun getAllMessages(): Flow<List<ChatMessage>> = chatDao.getAllMessages()
        .map { list ->
            list.mapIndexed { idx, msg -> sanitizeMessage(msg, idx) }
                .filter { it.hasPersistableContent }
                .take(100)
        }
        .flowOn(Dispatchers.IO)
        .catch { emit(emptyList()) }

    fun getRecentMessages(limit: Int = 100): Flow<List<ChatMessage>> =
        chatDao.getRecentMessages(limit.coerceIn(1, 100))
            .map { list ->
                list.mapIndexed { idx, msg -> sanitizeMessage(msg, idx) }
                    .filter { it.hasPersistableContent }
            }
            .flowOn(Dispatchers.IO)
            .catch { emit(emptyList()) }

    /**
     * Checks if another active [ChatSession] (excluding [excludeChatId]) already uses [wakeWord]
     * (case-insensitive). Returns the conflicting [ChatSession] if found, or `null` if unique.
     */
    suspend fun findConflictingWakeWordSession(
        wakeWord: String,
        excludeChatId: Long = -1L
    ): ChatSession? = withContext(Dispatchers.IO) {
        val clean = wakeWord.trim()
        if (clean.isBlank()) return@withContext null
        try {
            chatDao.findChatByWakeWordSync(clean, excludeChatId)?.let { sanitizeChat(it) }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Returns `true` if [wakeWord] is not used by any other active [ChatSession] (excluding [excludeChatId]).
     */
    suspend fun isWakeWordUnique(
        wakeWord: String,
        excludeChatId: Long = -1L
    ): Boolean = findConflictingWakeWordSession(wakeWord, excludeChatId) == null

    fun getMessagesForChat(chatId: Long): Flow<List<ChatMessage>> =
        chatDao.getMessagesForChat(chatId)
            .map { list ->
                list.mapIndexed { idx, msg -> sanitizeMessage(msg, idx) }
                    .filter { it.hasPersistableContent }
                    .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
            }
            .flowOn(Dispatchers.IO)
            .catch { emit(emptyList()) }

    suspend fun getMessagesForChatSync(chatId: Long): List<ChatMessage> = withContext(Dispatchers.IO) {
        try {
            chatDao.getMessagesForChatSync(chatId)
                .mapIndexed { idx, msg -> sanitizeMessage(msg, idx) }
                .filter { it.hasPersistableContent }
                .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Loads the complete saved message history for [chatId] in chronological order (`timestamp ASC, id ASC`).
     */
    suspend fun loadChatHistoryChronological(chatId: Long): List<ChatMessage> =
        getMessagesForChatSync(chatId)

    /**
     * Saves a message into the specified [chatId]'s history with chronological timestamping.
     */
    suspend fun saveChatHistoryMessage(
        chatId: Long,
        sender: String,
        content: String,
        timestamp: Long = System.currentTimeMillis(),
        triggeredByWakeWord: Boolean = false
    ): Long = insertMessage(
        ChatMessage(
            chatId = chatId,
            sender = sender,
            content = content,
            timestamp = timestamp,
            triggeredByWakeWord = triggeredByWakeWord
        )
    )

    fun getLastMessageForChat(chatId: Long): Flow<ChatMessage?> =
        chatDao.getLastMessageForChat(chatId)
            .map { it?.let { msg -> sanitizeMessage(msg).takeIf { m -> m.hasPersistableContent } } }
            .flowOn(Dispatchers.IO)
            .catch { emit(null) }

    suspend fun insertMessage(message: ChatMessage): Long = withContext(Dispatchers.IO) {
        try {
            val sanitized = sanitizeMessage(message)
            if (!sanitized.hasPersistableContent) {
                return@withContext -1L
            }
            val id = chatDao.insertMessageSafely(sanitized)
            if (id > 0L && sanitized.remoteId.isNullOrBlank()) {
                chatDao.updateMessageSyncState(
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

    suspend fun insertMessageSafely(message: ChatMessage): Long = insertMessage(message)

    suspend fun getPendingSyncMessages(limit: Int = 100): List<ChatMessage> = withContext(Dispatchers.IO) {
        try {
            chatDao.getPendingSyncMessages(limit).mapIndexed { idx, msg -> sanitizeMessage(msg, idx) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun getPendingDeletedMessages(limit: Int = 100): List<ChatMessage> = withContext(Dispatchers.IO) {
        try {
            chatDao.getPendingDeletedMessages(limit).mapIndexed { idx, msg -> sanitizeMessage(msg, idx) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun softDeleteMessage(messageId: Long) = withContext(Dispatchers.IO) {
        try {
            chatDao.markMessageSoftDeleted(messageId, System.currentTimeMillis())
        } catch (_: Throwable) {}
    }

    suspend fun purgeDeletedMessage(messageId: Long) = withContext(Dispatchers.IO) {
        try {
            chatDao.deleteMessageById(messageId)
        } catch (_: Throwable) {}
    }

    suspend fun markMessageSynced(
        id: Long,
        remoteId: String = "msg_$id",
        userId: String?,
        syncedAt: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        try {
            chatDao.updateMessageSyncState(
                id = id,
                remoteId = remoteId,
                userId = userId,
                syncStatus = SyncStatus.SYNCED,
                syncedAt = syncedAt
            )
        } catch (_: Throwable) {}
    }

    suspend fun upsertRemoteMessage(remoteMessage: ChatMessage): Long = withContext(Dispatchers.IO) {
        try {
            chatDao.upsertRemoteMessage(sanitizeMessage(remoteMessage))
        } catch (_: Throwable) {
            -1L
        }
    }

    suspend fun insertMessages(messages: List<ChatMessage>): List<Long> = withContext(Dispatchers.IO) {
        try {
            chatDao.insertMessagesSafely(messages.mapIndexed { idx, m -> sanitizeMessage(m, idx) })
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun clearChatMessages(chatId: Long) = withContext(Dispatchers.IO) {
        try {
            chatDao.deleteMessagesForChat(chatId)
        } catch (_: Throwable) {}
    }

    suspend fun deleteMessage(messageId: Long) = withContext(Dispatchers.IO) {
        try {
            chatDao.deleteMessageById(messageId)
        } catch (_: Throwable) {}
    }

    suspend fun updateTrackedSeconds(chatId: Long, seconds: Long) = withContext(Dispatchers.IO) {
        try {
            chatDao.updateTrackedSeconds(chatId, seconds.coerceAtLeast(0L))
        } catch (_: Throwable) {}
    }

    suspend fun updateChatTheme(chatId: Long, themeMode: com.example.ui.theme.AppTheme) = withContext(Dispatchers.IO) {
        try {
            chatDao.updateChatTheme(chatId, themeMode)
        } catch (_: Throwable) {}
    }

    suspend fun updateChatPinned(chatId: Long, isPinned: Boolean) = withContext(Dispatchers.IO) {
        try {
            chatDao.updateChatPinned(chatId, isPinned)
        } catch (_: Throwable) {}
    }

    suspend fun updateChatAppearance(
        chatId: Long,
        colorPaletteId: String,
        backgroundStyle: String,
        themeColorHex: String
    ) = withContext(Dispatchers.IO) {
        try {
            chatDao.updateChatAppearance(
                chatId = chatId,
                colorPaletteId = colorPaletteId.ifBlank { "OCEAN_CYAN" },
                backgroundStyle = backgroundStyle.ifBlank { "DEFAULT" },
                themeColorHex = themeColorHex.ifBlank { "#38BDF8" }
            )
        } catch (_: Throwable) {}
    }

    suspend fun updateChatGreeting(chatId: Long, greetingMessage: String) = withContext(Dispatchers.IO) {
        try {
            chatDao.updateChatGreeting(chatId, greetingMessage.trim(), System.currentTimeMillis())
        } catch (_: Throwable) {}
    }

    // Voice Command History (Room-persisted, capped at 100 items on Dispatchers.IO)
    fun getRecentVoiceCommands(): Flow<List<VoiceCommandHistoryEntry>> =
        chatDao.getRecentVoiceCommands()
            .map { list ->
                list.filter { it.hasValidTranscript || !ChatMessage.isLegacyAudioPlaceholder(it.triggerPhrase) }
                    .take(100)
                    .map { it.toHistoryEntry() }
            }
            .flowOn(Dispatchers.IO)
            .catch { emit(emptyList()) }

    suspend fun getRecentVoiceCommandsSync(limit: Int = 500): List<VoiceCommandHistoryEntry> =
        withContext(Dispatchers.IO) {
            try {
                chatDao.getRecentVoiceCommandsSync(limit.coerceIn(1, 1000))
                    .filter { it.hasValidTranscript || !ChatMessage.isLegacyAudioPlaceholder(it.triggerPhrase) }
                    .map { it.toHistoryEntry() }
            } catch (_: Throwable) {
                emptyList()
            }
        }

    suspend fun getAllMessagesChronologicalSync(): List<ChatMessage> = withContext(Dispatchers.IO) {
        try {
            chatDao.getAllActiveMessagesChronologicalSync()
                .mapIndexed { idx, msg -> sanitizeMessage(msg, idx) }
                .filter { it.hasPersistableContent }
                .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Exports chat interaction logs stored in the Room database as a formatted JSON string.
     * If [chatId] is specified, exports only that chat session's logs; otherwise exports all active chats.
     */
    suspend fun exportChatInteractionLogsToJsonString(chatId: Long? = null): String =
        withContext(Dispatchers.IO) {
            val chats = if (chatId != null) {
                listOfNotNull(getChatByIdSync(chatId))
            } else {
                getAllChatsSync()
            }
            val messages = if (chatId != null) {
                getMessagesForChatSync(chatId)
            } else {
                getAllMessagesChronologicalSync()
            }
            val voiceCommands = getRecentVoiceCommandsSync(limit = 500)
            com.example.util.ChatLogJsonExporter.buildInteractionLogsJson(
                chats = chats,
                messages = messages,
                voiceCommands = voiceCommands,
                filterChatId = chatId
            )
        }

    /**
     * Exports chat interaction logs stored in Room to a `.json` file inside [outputDir].
     */
    suspend fun exportChatInteractionLogsToJsonFile(
        context: android.content.Context,
        chatId: Long? = null,
        outputDir: java.io.File = java.io.File(context.cacheDir, "exports")
    ): java.io.File = com.example.util.ChatLogJsonExporter.exportLogsToJsonFile(
        context = context,
        chatRepository = this,
        chatId = chatId,
        outputDir = outputDir
    )

    /**
     * Exports chat interaction logs stored in Room to a `.json` file and launches the standard
     * Android `Intent.ACTION_SEND` share chooser via [androidx.core.content.FileProvider].
     */
    suspend fun exportAndShareChatInteractionLogs(
        context: android.content.Context,
        chatId: Long? = null
    ): android.content.Intent = com.example.util.ChatLogJsonExporter.exportAndShareChatInteractionLogs(
        context = context,
        chatRepository = this,
        chatId = chatId
    )

    suspend fun insertVoiceCommand(entry: VoiceCommandHistoryEntry) = withContext(Dispatchers.IO) {
        try {
            val entity = VoiceCommandEntity.fromHistoryEntry(entry)
            if (!entity.hasValidTranscript && ChatMessage.isLegacyAudioPlaceholder(entity.triggerPhrase)) {
                return@withContext
            }
            chatDao.insertVoiceCommand(entity)
        } catch (_: Throwable) {}
    }

    suspend fun deleteVoiceCommand(id: String) = withContext(Dispatchers.IO) {
        try {
            chatDao.deleteVoiceCommandById(id)
        } catch (_: Throwable) {}
    }

    suspend fun clearAllVoiceCommands() = withContext(Dispatchers.IO) {
        try {
            chatDao.clearAllVoiceCommands()
        } catch (_: Throwable) {}
    }

    suspend fun insertWakeWordSample(sample: WakeWordSample): Long = withContext(Dispatchers.IO) {
        try {
            chatDao.insertWakeWordSample(sample)
        } catch (_: Throwable) {
            -1L
        }
    }

    suspend fun getAllWakeWordSamples(): List<WakeWordSample> = withContext(Dispatchers.IO) {
        try {
            chatDao.getAllWakeWordSamples()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun getAllWakeWordSamplesFlow(): Flow<List<WakeWordSample>> =
        chatDao.getAllWakeWordSamplesFlow()
            .flowOn(Dispatchers.IO)
            .catch { emit(emptyList()) }

    suspend fun getSamplesForChat(chatId: Long): List<WakeWordSample> = withContext(Dispatchers.IO) {
        try {
            chatDao.getSamplesForChat(chatId)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun getSamplesForChatFlow(chatId: Long): Flow<List<WakeWordSample>> =
        chatDao.getSamplesForChatFlow(chatId)
            .flowOn(Dispatchers.IO)
            .catch { emit(emptyList()) }

    suspend fun getSamplesForWakeWord(wakeWordId: Long): List<WakeWordSample> = withContext(Dispatchers.IO) {
        try {
            chatDao.getSamplesForWakeWord(wakeWordId)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun getSamplesForWakeWordFlow(wakeWordId: Long): Flow<List<WakeWordSample>> =
        chatDao.getSamplesForWakeWordFlow(wakeWordId)
            .flowOn(Dispatchers.IO)
            .catch { emit(emptyList()) }

    suspend fun associateSamplesWithWakeWord(chatId: Long, wakeWordId: Long, wakePhrase: String) = withContext(Dispatchers.IO) {
        try {
            chatDao.associateSamplesWithWakeWord(chatId, wakeWordId, wakePhrase)
        } catch (_: Throwable) {}
    }

    suspend fun deleteSamplesForChat(chatId: Long) = withContext(Dispatchers.IO) {
        try {
            chatDao.deleteSamplesForChat(chatId)
        } catch (_: Throwable) {}
    }

    suspend fun deleteSampleById(sampleId: Long) = withContext(Dispatchers.IO) {
        try {
            chatDao.deleteSampleById(sampleId)
        } catch (_: Throwable) {}
    }
}

