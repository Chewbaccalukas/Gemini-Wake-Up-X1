package com.example.data.repository

import com.example.data.dao.ChatDao
import com.example.data.dao.WakeWordDao
import com.example.data.model.ChatSession
import com.example.data.model.SyncStatus
import com.example.data.model.TriggerType
import com.example.data.model.WakeWord
import com.example.voice.AudioCommandSanitizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Repository abstracting [WakeWordDao] operations, providing a clean reactive API for the ViewModel
 * to observe, add (with case-insensitive deduplication per [TriggerType]), update, and remove wake words.
 */
class WakeWordRepository(
    private val wakeWordDao: WakeWordDao,
    private val chatDao: ChatDao? = null
) {

    private fun sanitizeWakeWord(item: WakeWord): WakeWord {
        val fallbackPhrase = when (item.commandRole) {
            WakeWord.ROLE_DICTATE -> AudioCommandSanitizer.DEFAULT_DICTATE_TRIGGER
            WakeWord.ROLE_SEND -> AudioCommandSanitizer.DEFAULT_SEND_TRIGGER
            WakeWord.ROLE_HEARTBEAT -> AudioCommandSanitizer.DEFAULT_HEARTBEAT_TRIGGER
            else -> "Hey Gemini"
        }
        val cleanPhrase = item.phrase.trim().ifBlank { fallbackPhrase }
        val cleanSensitivity = item.sensitivity.coerceIn(0.50f, 0.98f)
        val cleanSensitivityInt = item.sensitivityInt.coerceIn(0, 100)
        val resolvedTargetChatId = item.effectiveTargetChatId
        return item.copy(
            phrase = cleanPhrase,
            chatId = resolvedTargetChatId,
            targetChatId = resolvedTargetChatId,
            sensitivity = cleanSensitivity,
            sensitivityInt = cleanSensitivityInt,
            chatTitle = item.chatTitle.trim().ifBlank {
                if (item.triggerType == TriggerType.CHAT) (item.metadata ?: "Gemini Chat") else item.commandRole
            }
        )
    }

    val allWakeWords: Flow<List<WakeWord>> = wakeWordDao.getAllWakeWords()
        .map { list -> list.map { sanitizeWakeWord(it) } }
        .catch { emit(emptyList()) }

    val chatWakeWords: Flow<List<WakeWord>> = wakeWordDao.getWakeWordsByTriggerType(TriggerType.CHAT)
        .map { list -> list.map { sanitizeWakeWord(it) } }
        .catch { emit(emptyList()) }

    val enabledChatWakeWords: Flow<List<WakeWord>> = wakeWordDao.getEnabledChatWakeWordsFlow()
        .map { list -> list.map { sanitizeWakeWord(it) } }
        .catch { emit(emptyList()) }

    val systemTriggers: Flow<List<WakeWord>> = wakeWordDao.getWakeWordsByTriggerType(TriggerType.HANDS_FREE)
        .map { list -> list.map { sanitizeWakeWord(it) } }
        .catch { emit(emptyList()) }

    val pendingSyncWakeWordCount: Flow<Int> = wakeWordDao.observePendingSyncWakeWordCount()
        .catch { emit(0) }

    fun observeAllWakeWords(): Flow<List<WakeWord>> = allWakeWords

    fun observeWakeWords(): Flow<List<WakeWord>> = allWakeWords

    fun observeChatWakeWords(): Flow<List<WakeWord>> = chatWakeWords

    fun observeHandsFreeWakeWords(): Flow<List<WakeWord>> = systemTriggers

    fun observeWakeWordsByType(triggerType: TriggerType): Flow<List<WakeWord>> =
        getWakeWordsByTriggerType(triggerType)

    fun getWakeWordsByTriggerType(triggerType: TriggerType): Flow<List<WakeWord>> =
        wakeWordDao.getWakeWordsByTriggerType(triggerType)
            .map { list -> list.map { sanitizeWakeWord(it) } }
            .catch { emit(emptyList()) }

    suspend fun getAllWakeWordsSync(): List<WakeWord> = withContext(Dispatchers.IO) {
        try {
            wakeWordDao.getAllWakeWordsSync().map { sanitizeWakeWord(it) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun getPendingSyncWakeWords(): List<WakeWord> = withContext(Dispatchers.IO) {
        try {
            wakeWordDao.getPendingSyncWakeWords().map { sanitizeWakeWord(it) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun getPendingDeletedWakeWords(): List<WakeWord> = withContext(Dispatchers.IO) {
        try {
            wakeWordDao.getPendingDeletedWakeWords().map { sanitizeWakeWord(it) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun softDeleteWakeWord(id: Long) = withContext(Dispatchers.IO) {
        try {
            wakeWordDao.markWakeWordSoftDeleted(id, System.currentTimeMillis())
        } catch (_: Throwable) {}
    }

    suspend fun restoreSoftDeletedWakeWord(id: Long) = withContext(Dispatchers.IO) {
        try {
            wakeWordDao.restoreSoftDeletedWakeWord(id, System.currentTimeMillis())
        } catch (_: Throwable) {}
    }

    suspend fun purgeDeletedWakeWord(id: Long) = withContext(Dispatchers.IO) {
        try {
            wakeWordDao.deleteWakeWordById(id)
        } catch (_: Throwable) {}
    }

    suspend fun purgeAllSyncedDeletedWakeWords() = withContext(Dispatchers.IO) {
        try {
            wakeWordDao.purgeAllSyncedDeletedWakeWords()
        } catch (_: Throwable) {}
    }

    suspend fun rebindLocalRecordsForUser(userId: String) = withContext(Dispatchers.IO) {
        if (userId.isBlank()) return@withContext
        try {
            wakeWordDao.rebindWakeWordsForUser(userId)
        } catch (_: Throwable) {}
    }

    suspend fun markWakeWordSynced(
        id: Long,
        remoteId: String = "wake_$id",
        userId: String?,
        syncedAt: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        try {
            wakeWordDao.updateWakeWordSyncState(
                id = id,
                remoteId = remoteId,
                userId = userId,
                syncStatus = SyncStatus.SYNCED,
                syncedAt = syncedAt
            )
        } catch (_: Throwable) {}
    }

    suspend fun markWakeWordSyncFailed(id: Long) = withContext(Dispatchers.IO) {
        try {
            wakeWordDao.markWakeWordSyncFailed(id)
        } catch (_: Throwable) {}
    }

    suspend fun upsertRemoteWakeWord(remoteWakeWord: WakeWord): Long = withContext(Dispatchers.IO) {
        try {
            wakeWordDao.upsertRemoteWakeWord(sanitizeWakeWord(remoteWakeWord))
        } catch (_: Throwable) {
            remoteWakeWord.id
        }
    }

    suspend fun getChatWakeWordsForChatSync(chatId: Long): List<WakeWord> = withContext(Dispatchers.IO) {
        try {
            wakeWordDao.getChatWakeWordsForChatSync(chatId).map { sanitizeWakeWord(it) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    suspend fun getSystemTriggerPhrase(role: String, defaultPhrase: String): String = withContext(Dispatchers.IO) {
        try {
            wakeWordDao.getWakeWordByRoleSync(role)?.phrase?.trim()?.takeIf { it.isNotBlank() } ?: defaultPhrase
        } catch (_: Throwable) {
            defaultPhrase
        }
    }

    suspend fun getSavedSensitivity(defaultSensitivity: Float = 0.78f): Float = withContext(Dispatchers.IO) {
        try {
            val all = wakeWordDao.getAllWakeWordsSync()
            all.firstOrNull()?.sensitivity?.coerceIn(0.50f, 0.98f) ?: defaultSensitivity.coerceIn(0.50f, 0.98f)
        } catch (_: Throwable) {
            defaultSensitivity.coerceIn(0.50f, 0.98f)
        }
    }

    /**
     * Ensures that system hands-free triggers (DICTATE, SEND, HEARTBEAT) and existing ChatSession
     * wake words are seeded into the Room table on startup so persisted records are always available.
     */
    suspend fun ensureSeededFromChatsAndDefaults(
        chats: List<ChatSession>,
        initialDictate: String = AudioCommandSanitizer.DEFAULT_DICTATE_TRIGGER,
        initialSend: String = AudioCommandSanitizer.DEFAULT_SEND_TRIGGER,
        initialHeartbeat: String = AudioCommandSanitizer.DEFAULT_HEARTBEAT_TRIGGER,
        initialSensitivity: Float = 0.78f
    ) = withContext(Dispatchers.IO) {
        try {
            val clampedSensitivity = initialSensitivity.coerceIn(0.50f, 0.98f)
            if (wakeWordDao.getWakeWordByRoleSync(WakeWord.ROLE_DICTATE) == null) {
                wakeWordDao.upsertSystemTrigger(
                    role = WakeWord.ROLE_DICTATE,
                    phrase = initialDictate.trim().ifBlank { AudioCommandSanitizer.DEFAULT_DICTATE_TRIGGER },
                    sensitivity = clampedSensitivity,
                    metadata = WakeWord.ROLE_DICTATE
                )
            }
            if (wakeWordDao.getWakeWordByRoleSync(WakeWord.ROLE_SEND) == null) {
                wakeWordDao.upsertSystemTrigger(
                    role = WakeWord.ROLE_SEND,
                    phrase = initialSend.trim().ifBlank { AudioCommandSanitizer.DEFAULT_SEND_TRIGGER },
                    sensitivity = clampedSensitivity,
                    metadata = WakeWord.ROLE_SEND
                )
            }
            if (wakeWordDao.getWakeWordByRoleSync(WakeWord.ROLE_HEARTBEAT) == null) {
                wakeWordDao.upsertSystemTrigger(
                    role = WakeWord.ROLE_HEARTBEAT,
                    phrase = initialHeartbeat.trim().ifBlank { AudioCommandSanitizer.DEFAULT_HEARTBEAT_TRIGGER },
                    sensitivity = clampedSensitivity,
                    metadata = WakeWord.ROLE_HEARTBEAT
                )
            }
            for (chat in chats) {
                if (chat.id > 0L) {
                    val existing = wakeWordDao.getChatWakeWordsForChatSync(chat.id)
                    if (existing.isEmpty()) {
                        wakeWordDao.syncPrimaryChatWakeWord(
                            chatId = chat.id,
                            chatTitle = chat.title,
                            phrase = chat.wakeWord.ifBlank { "Hey Gemini" },
                            sensitivity = clampedSensitivity,
                            metadata = chat.title
                        )
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Adds or updates a [WakeWord] in Room, deduplicating case-insensitively per [TriggerType].
     */
    suspend fun addWakeWord(wakeWord: WakeWord): Long = withContext(Dispatchers.IO) {
        val clean = sanitizeWakeWord(wakeWord)
        if (clean.phrase.isBlank()) return@withContext -1L
        try {
            val now = System.currentTimeMillis()
            val duplicate = if (clean.id > 0L) {
                wakeWordDao.getWakeWordByIdSync(clean.id)
            } else {
                wakeWordDao.getWakeWordByPhraseAndTypeSync(clean.phrase, clean.triggerType)
            }
            if (duplicate != null) {
                val resolvedTargetChatId = clean.effectiveTargetChatId ?: duplicate.effectiveTargetChatId
                val updated = duplicate.copy(
                    phrase = clean.phrase,
                    triggerType = clean.triggerType,
                    metadata = clean.metadata ?: duplicate.metadata,
                    commandRole = clean.commandRole,
                    chatId = resolvedTargetChatId,
                    targetChatId = resolvedTargetChatId,
                    chatTitle = clean.chatTitle.ifBlank { duplicate.chatTitle },
                    isEnabled = true,
                    sensitivity = clean.sensitivity,
                    sensitivityInt = clean.sensitivityInt,
                    updatedAt = now,
                    remoteId = clean.remoteId ?: duplicate.remoteId ?: "wake_${duplicate.id}",
                    userId = clean.userId ?: duplicate.userId,
                    syncStatus = when {
                        clean.syncStatus == SyncStatus.SYNCED -> SyncStatus.SYNCED
                        duplicate.syncStatus == SyncStatus.SYNCED -> SyncStatus.DIRTY
                        else -> duplicate.syncStatus
                    },
                    lastSyncedAt = if (clean.lastSyncedAt > 0L) clean.lastSyncedAt else duplicate.lastSyncedAt
                )
                wakeWordDao.updateWakeWord(updated)
                duplicate.id
            } else {
                val insertedId = wakeWordDao.insertWakeWord(clean.copy(updatedAt = now))
                if (insertedId > 0L && clean.remoteId.isNullOrBlank()) {
                    wakeWordDao.updateWakeWordSyncState(
                        id = insertedId,
                        remoteId = "wake_$insertedId",
                        userId = clean.userId,
                        syncStatus = clean.syncStatus,
                        syncedAt = clean.lastSyncedAt
                    )
                }
                insertedId
            }
        } catch (_: Throwable) {
            -1L
        }
    }

    /**
     * Convenience overload to add a wake word by phrase, [TriggerType], and optional metadata.
     * Deduplicates case-insensitively per [TriggerType].
     */
    suspend fun addWakeWord(
        phrase: String,
        triggerType: TriggerType = TriggerType.CHAT,
        metadata: String? = null,
        chatId: Long? = null,
        chatTitle: String = "",
        sensitivity: Float = 0.78f
    ): Long = withContext(Dispatchers.IO) {
        val cleanPhrase = phrase.trim()
        if (cleanPhrase.isBlank()) return@withContext -1L
        val now = System.currentTimeMillis()
        val resolvedTitle = chatTitle.trim().ifBlank {
            if (triggerType == TriggerType.CHAT) (metadata ?: "Gemini Chat") else (metadata ?: "HANDS_FREE")
        }
        val commandRole = if (triggerType == TriggerType.CHAT) {
            WakeWord.ROLE_CHAT_WAKE
        } else {
            metadata?.takeIf { it.isNotBlank() } ?: WakeWord.ROLE_DICTATE
        }
        addWakeWord(
            WakeWord(
                phrase = cleanPhrase,
                triggerType = triggerType,
                metadata = metadata,
                commandRole = commandRole,
                chatId = chatId,
                chatTitle = resolvedTitle,
                isEnabled = true,
                sensitivity = sensitivity.coerceIn(0.50f, 0.98f),
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun insertWakeWord(wakeWord: WakeWord): Long = addWakeWord(wakeWord)

    suspend fun addCustomChatWakeWord(
        chatId: Long,
        chatTitle: String,
        phrase: String,
        sensitivity: Float = 0.78f,
        metadata: String? = null
    ): Long = withContext(Dispatchers.IO) {
        val cleanPhrase = phrase.trim()
        if (cleanPhrase.isBlank()) return@withContext -1L
        try {
            val now = System.currentTimeMillis()
            val resolvedTitle = chatTitle.ifBlank { "Gemini Chat" }
            val existingForChat = wakeWordDao.getChatWakeWordsForChatSync(chatId)
            val id = addWakeWord(
                WakeWord(
                    phrase = cleanPhrase,
                    triggerType = TriggerType.CHAT,
                    metadata = metadata ?: resolvedTitle,
                    commandRole = WakeWord.ROLE_CHAT_WAKE,
                    chatId = chatId,
                    chatTitle = resolvedTitle,
                    isEnabled = true,
                    sensitivity = sensitivity.coerceIn(0.50f, 0.98f),
                    createdAt = now,
                    updatedAt = now
                )
            )
            // If this is the first wake word for the chat, also ensure ChatSession.wakeWord matches
            if (existingForChat.isEmpty() && id > 0L) {
                chatDao?.getChatByIdSync(chatId)?.let { chat ->
                    chatDao.updateChat(chat.copy(wakeWord = cleanPhrase, lastUpdatedAt = now))
                }
            }
            id
        } catch (_: Throwable) {
            -1L
        }
    }

    suspend fun updateWakeWordPhrase(id: Long, newPhrase: String): Boolean = withContext(Dispatchers.IO) {
        val clean = newPhrase.trim()
        if (clean.isBlank()) return@withContext false
        try {
            val existing = wakeWordDao.getWakeWordByIdSync(id) ?: return@withContext false
            val now = System.currentTimeMillis()
            wakeWordDao.updateWakeWordPhrase(id, clean, now)
            if (existing.triggerType == TriggerType.CHAT && existing.chatId != null) {
                val chatWords = wakeWordDao.getChatWakeWordsForChatSync(existing.chatId)
                // If the primary (first) wake word for this chat was updated, sync back to ChatSession
                if (chatWords.firstOrNull()?.id == id) {
                    chatDao?.getChatByIdSync(existing.chatId)?.let { chat ->
                        chatDao.updateChat(chat.copy(wakeWord = clean, lastUpdatedAt = now))
                    }
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun setWakeWordEnabled(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        try {
            wakeWordDao.setWakeWordEnabled(id, enabled, System.currentTimeMillis())
        } catch (_: Throwable) {}
    }

    /**
     * Removes a [WakeWord] entity from Room and promotes the next remaining wake word for its chat if needed.
     */
    suspend fun removeWakeWord(wakeWord: WakeWord) = deleteWakeWord(wakeWord)

    suspend fun deleteWakeWord(wakeWord: WakeWord) = withContext(Dispatchers.IO) {
        try {
            wakeWordDao.deleteWakeWord(wakeWord)
            if (wakeWord.triggerType == TriggerType.CHAT && wakeWord.chatId != null) {
                val remaining = wakeWordDao.getChatWakeWordsForChatSync(wakeWord.chatId)
                if (remaining.isNotEmpty()) {
                    val nextPrimary = remaining.first().phrase
                    chatDao?.getChatByIdSync(wakeWord.chatId)?.let { chat ->
                        chatDao.updateChat(chat.copy(wakeWord = nextPrimary, lastUpdatedAt = System.currentTimeMillis()))
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Removes a wake word by its primary key [id].
     */
    suspend fun removeWakeWordById(id: Long) = deleteWakeWordById(id)

    suspend fun deleteWakeWordById(id: Long) = withContext(Dispatchers.IO) {
        try {
            val existing = wakeWordDao.getWakeWordByIdSync(id)
            wakeWordDao.deleteWakeWordById(id)
            if (existing?.triggerType == TriggerType.CHAT && existing.chatId != null) {
                val remaining = wakeWordDao.getChatWakeWordsForChatSync(existing.chatId)
                if (remaining.isNotEmpty()) {
                    val nextPrimary = remaining.first().phrase
                    chatDao?.getChatByIdSync(existing.chatId)?.let { chat ->
                        chatDao.updateChat(chat.copy(wakeWord = nextPrimary, lastUpdatedAt = System.currentTimeMillis()))
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    /**
     * Removes wake word(s) matching [phrase] case-insensitively.
     */
    suspend fun removeWakeWordByPhrase(phrase: String) = deleteWakeWordByPhrase(phrase)

    suspend fun deleteWakeWordByPhrase(phrase: String) = withContext(Dispatchers.IO) {
        val clean = phrase.trim()
        if (clean.isBlank()) return@withContext
        try {
            val matchingChatWake = wakeWordDao.getWakeWordByPhraseAndTypeSync(clean, TriggerType.CHAT)
            wakeWordDao.deleteWakeWordByPhrase(clean)
            val affectedChatId = matchingChatWake?.chatId
            if (affectedChatId != null) {
                val remaining = wakeWordDao.getChatWakeWordsForChatSync(affectedChatId)
                if (remaining.isNotEmpty()) {
                    val nextPrimary = remaining.first().phrase
                    chatDao?.getChatByIdSync(affectedChatId)?.let { chat ->
                        chatDao.updateChat(chat.copy(wakeWord = nextPrimary, lastUpdatedAt = System.currentTimeMillis()))
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    suspend fun syncChatWakeWordFromSession(
        chatId: Long,
        chatTitle: String,
        wakeWord: String,
        sensitivity: Float = 0.78f
    ) = withContext(Dispatchers.IO) {
        try {
            val cleanTitle = chatTitle.ifBlank { "Gemini Chat" }
            wakeWordDao.syncPrimaryChatWakeWord(
                chatId = chatId,
                chatTitle = cleanTitle,
                phrase = wakeWord.trim().ifBlank { "Hey Gemini" },
                sensitivity = sensitivity.coerceIn(0.50f, 0.98f),
                metadata = cleanTitle
            )
        } catch (_: Throwable) {}
    }

    suspend fun deleteWakeWordsForChat(chatId: Long) = withContext(Dispatchers.IO) {
        try {
            wakeWordDao.deleteWakeWordsForChat(chatId)
        } catch (_: Throwable) {}
    }

    suspend fun upsertSystemTrigger(role: String, phrase: String, sensitivity: Float = 0.78f): Long =
        withContext(Dispatchers.IO) {
            try {
                val fallback = when (role) {
                    WakeWord.ROLE_DICTATE -> AudioCommandSanitizer.DEFAULT_DICTATE_TRIGGER
                    WakeWord.ROLE_SEND -> AudioCommandSanitizer.DEFAULT_SEND_TRIGGER
                    WakeWord.ROLE_HEARTBEAT -> AudioCommandSanitizer.DEFAULT_HEARTBEAT_TRIGGER
                    else -> "Trigger"
                }
                wakeWordDao.upsertSystemTrigger(
                    role = role,
                    phrase = phrase.trim().ifBlank { fallback },
                    sensitivity = sensitivity.coerceIn(0.50f, 0.98f),
                    metadata = role
                )
            } catch (_: Throwable) {
                -1L
            }
        }

    suspend fun resetSystemTriggersToDefaults(sensitivity: Float = 0.78f) = withContext(Dispatchers.IO) {
        val clamped = sensitivity.coerceIn(0.50f, 0.98f)
        upsertSystemTrigger(WakeWord.ROLE_DICTATE, AudioCommandSanitizer.DEFAULT_DICTATE_TRIGGER, clamped)
        upsertSystemTrigger(WakeWord.ROLE_SEND, AudioCommandSanitizer.DEFAULT_SEND_TRIGGER, clamped)
        upsertSystemTrigger(WakeWord.ROLE_HEARTBEAT, AudioCommandSanitizer.DEFAULT_HEARTBEAT_TRIGGER, clamped)
    }

    suspend fun updateSensitivity(sensitivity: Float) = withContext(Dispatchers.IO) {
        try {
            val clamped = sensitivity.coerceIn(0.50f, 0.98f)
            wakeWordDao.updateSensitivityForAll(clamped, System.currentTimeMillis())
        } catch (_: Throwable) {}
    }

    suspend fun updateSensitivity(sensitivityPercent: Int) = withContext(Dispatchers.IO) {
        try {
            wakeWordDao.updateSensitivityForAllInt(sensitivityPercent.coerceIn(0, 100), System.currentTimeMillis())
        } catch (_: Throwable) {}
    }

    suspend fun updateSensitivityLevel(level: com.example.voice.WakeSensitivityLevel) =
        updateSensitivity(level.sensitivityValue)

    /**
     * Updates the sensitivity threshold (`0.50f..0.98f`) for a single [WakeWord] entry by [id].
     */
    suspend fun updateWakeWordSensitivity(id: Long, sensitivity: Float): Boolean = withContext(Dispatchers.IO) {
        try {
            val existing = wakeWordDao.getWakeWordByIdSync(id) ?: return@withContext false
            if (existing.isDeleted) return@withContext false
            val clamped = sensitivity.coerceIn(0.50f, 0.98f)
            wakeWordDao.updateWakeWordSensitivity(id, clamped, System.currentTimeMillis())
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Updates the integer sensitivity (`0..100`) for a single [WakeWord] entry by [id].
     */
    suspend fun updateWakeWordSensitivity(id: Long, sensitivityPercent: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val existing = wakeWordDao.getWakeWordByIdSync(id) ?: return@withContext false
            if (existing.isDeleted) return@withContext false
            val clampedInt = sensitivityPercent.coerceIn(0, 100)
            wakeWordDao.updateWakeWordSensitivityInt(id, clampedInt, System.currentTimeMillis())
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Updates a single [WakeWord]'s sensitivity to a predefined [com.example.voice.WakeSensitivityLevel].
     */
    suspend fun updateWakeWordSensitivityLevel(
        id: Long,
        level: com.example.voice.WakeSensitivityLevel
    ): Boolean = updateWakeWordSensitivity(id, level.sensitivityValue)

    /**
     * Re-binds an existing [WakeWord] ([id]) to a specific [ChatSession] ([chatId]).
     */
    suspend fun updateWakeWordChatBinding(
        id: Long,
        chatId: Long,
        chatTitle: String = ""
    ): Boolean = withContext(Dispatchers.IO) {
        if (chatId <= 0L) return@withContext false
        try {
            val existing = wakeWordDao.getWakeWordByIdSync(id) ?: return@withContext false
            val targetChat = chatDao?.getChatByIdSync(chatId)
            val resolvedTitle = chatTitle.trim().ifBlank {
                targetChat?.title?.takeIf { it.isNotBlank() } ?: existing.chatTitle.ifBlank { "Gemini Chat" }
            }
            val now = System.currentTimeMillis()
            val existingForTarget = wakeWordDao.getChatWakeWordsForChatSync(chatId)
            wakeWordDao.updateWakeWordChatBinding(
                id = id,
                chatId = chatId,
                chatTitle = resolvedTitle,
                metadata = resolvedTitle,
                updatedAt = now
            )
            if (existingForTarget.isEmpty() && targetChat != null) {
                chatDao.updateChat(targetChat.copy(wakeWord = existing.phrase, lastUpdatedAt = now))
            }
            // If old chat lost its primary wake word, promote next remaining wake word if available
            val oldChatId = existing.effectiveTargetChatId
            if (oldChatId != null && oldChatId != chatId) {
                val remainingOld = wakeWordDao.getChatWakeWordsForChatSync(oldChatId)
                if (remainingOld.isNotEmpty()) {
                    chatDao?.getChatByIdSync(oldChatId)?.let { oldChat ->
                        chatDao.updateChat(oldChat.copy(wakeWord = remainingOld.first().phrase, lastUpdatedAt = now))
                    }
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Alias for [updateWakeWordChatBinding] updating `targetChatId` on a [WakeWord].
     */
    suspend fun updateWakeWordTargetChatId(
        id: Long,
        targetChatId: Long,
        chatTitle: String = ""
    ): Boolean = updateWakeWordChatBinding(id, targetChatId, chatTitle)

    /**
     * Creates a new [ChatSession] on the fly and binds a custom [WakeWord] phrase to it.
     * Returns `(chatId to wakeWordId)`.
     */
    suspend fun createChatAndBindWakeWord(
        chatTitle: String,
        phrase: String,
        sensitivity: Float = 0.78f,
        themeColorHex: String = "#38BDF8",
        iconName: String = "Sparkles",
        systemInstruction: String = "You are a helpful AI assistant."
    ): Pair<Long, Long> = withContext(Dispatchers.IO) {
        val cleanPhrase = phrase.trim()
        if (cleanPhrase.isBlank()) return@withContext (-1L to -1L)
        val resolvedTitle = chatTitle.trim().ifBlank { "$cleanPhrase Chat" }
        val now = System.currentTimeMillis()
        try {
            val createdChatId = chatDao?.insertChat(
                ChatSession(
                    title = resolvedTitle,
                    wakeWord = cleanPhrase,
                    systemInstruction = systemInstruction,
                    themeColorHex = themeColorHex,
                    iconName = iconName,
                    isDefaultWakeChat = false,
                    isPersistent = true,
                    createdAt = now,
                    lastUpdatedAt = now,
                    syncStatus = SyncStatus.PENDING,
                    isDeleted = false
                )
            ) ?: -1L
            if (createdChatId > 0L) {
                chatDao?.updateChatSyncState(
                    id = createdChatId,
                    remoteId = "chat_$createdChatId",
                    userId = null,
                    syncStatus = SyncStatus.PENDING,
                    syncedAt = 0L
                )
            }
            val targetChatId = if (createdChatId > 0L) createdChatId else null
            val wakeWordId = if (targetChatId != null) {
                addCustomChatWakeWord(
                    chatId = targetChatId,
                    chatTitle = resolvedTitle,
                    phrase = cleanPhrase,
                    sensitivity = sensitivity.coerceIn(0.50f, 0.98f),
                    metadata = resolvedTitle
                )
            } else {
                addWakeWord(
                    phrase = cleanPhrase,
                    triggerType = TriggerType.CHAT,
                    metadata = resolvedTitle,
                    chatId = null,
                    chatTitle = resolvedTitle,
                    sensitivity = sensitivity.coerceIn(0.50f, 0.98f)
                )
            }
            createdChatId to wakeWordId
        } catch (_: Throwable) {
            -1L to -1L
        }
    }

    /**
     * Creates a new [ChatSession] on the fly and binds a custom [WakeWord] phrase with an integer
     * sensitivity (`0..100`) to it. Returns `(chatId to wakeWordId)`.
     */
    suspend fun createChatAndBindWakeWord(
        chatTitle: String,
        phrase: String,
        sensitivity: Int,
        themeColorHex: String = "#38BDF8",
        iconName: String = "Sparkles",
        systemInstruction: String = "You are a helpful AI assistant."
    ): Pair<Long, Long> = withContext(Dispatchers.IO) {
        val clampedInt = sensitivity.coerceIn(0, 100)
        val floatSensitivity = WakeWord.fromSensitivityInt(clampedInt)
        val result = createChatAndBindWakeWord(
            chatTitle = chatTitle,
            phrase = phrase,
            sensitivity = floatSensitivity,
            themeColorHex = themeColorHex,
            iconName = iconName,
            systemInstruction = systemInstruction
        )
        if (result.second > 0L) {
            try {
                wakeWordDao.updateWakeWordSensitivityInt(result.second, clampedInt, System.currentTimeMillis())
            } catch (_: Throwable) {}
        }
        result
    }

    /**
     * Creates a new [ChatSession] on the fly and re-assigns an existing [WakeWord] ([wakeWordId]) to it.
     * Returns the newly created `chatId` (or `-1L` on failure).
     */
    suspend fun reassignWakeWordToNewChat(
        wakeWordId: Long,
        newChatTitle: String,
        themeColorHex: String = "#38BDF8",
        iconName: String = "Sparkles"
    ): Long = withContext(Dispatchers.IO) {
        try {
            val existing = wakeWordDao.getWakeWordByIdSync(wakeWordId) ?: return@withContext -1L
            val resolvedTitle = newChatTitle.trim().ifBlank { "${existing.phrase} Chat" }
            val now = System.currentTimeMillis()
            val newChatId = chatDao?.insertChat(
                ChatSession(
                    title = resolvedTitle,
                    wakeWord = existing.phrase,
                    systemInstruction = "You are a helpful AI assistant.",
                    themeColorHex = themeColorHex,
                    iconName = iconName,
                    isDefaultWakeChat = false,
                    isPersistent = true,
                    createdAt = now,
                    lastUpdatedAt = now,
                    syncStatus = SyncStatus.PENDING,
                    isDeleted = false
                )
            ) ?: return@withContext -1L
            if (newChatId > 0L) {
                chatDao.updateChatSyncState(
                    id = newChatId,
                    remoteId = "chat_$newChatId",
                    userId = existing.userId,
                    syncStatus = SyncStatus.PENDING,
                    syncedAt = 0L
                )
                updateWakeWordChatBinding(wakeWordId, newChatId, resolvedTitle)
            }
            newChatId
        } catch (_: Throwable) {
            -1L
        }
    }
}
