package com.example.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.model.SyncStatus
import com.example.data.model.TriggerType
import com.example.data.model.WakeWord
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object (DAO) for [WakeWord] CRUD, soft-delete tombstones (`isDeleted`),
 * and hybrid Room + Cloud Firestore synchronization operations.
 */
@Dao
interface WakeWordDao {

    // =========================================================================
    // Reactive Flow & Lookup Queries
    // =========================================================================

    @Query("SELECT * FROM wake_words WHERE isDeleted = 0 ORDER BY CASE WHEN triggerType = 'CHAT' THEN 0 ELSE 1 END, createdAt ASC, id ASC")
    fun getAllWakeWords(): Flow<List<WakeWord>>

    @Query("SELECT * FROM wake_words WHERE isDeleted = 0 ORDER BY CASE WHEN triggerType = 'CHAT' THEN 0 ELSE 1 END, createdAt ASC, id ASC")
    fun observeAllWakeWords(): Flow<List<WakeWord>>

    @Query("SELECT * FROM wake_words WHERE isDeleted = 0 ORDER BY CASE WHEN triggerType = 'CHAT' THEN 0 ELSE 1 END, createdAt ASC, id ASC")
    fun getAllWakeWordsFlow(): Flow<List<WakeWord>>

    @Query("SELECT * FROM wake_words WHERE triggerType = 'CHAT' AND isDeleted = 0 ORDER BY createdAt ASC, id ASC")
    fun getChatWakeWordsFlow(): Flow<List<WakeWord>>

    @Query("SELECT * FROM wake_words WHERE triggerType = 'CHAT' AND isEnabled = 1 AND isDeleted = 0 ORDER BY createdAt ASC, id ASC")
    fun getEnabledChatWakeWordsFlow(): Flow<List<WakeWord>>

    @Query("SELECT * FROM wake_words WHERE triggerType = 'HANDS_FREE' AND isDeleted = 0 ORDER BY id ASC")
    fun getSystemTriggersFlow(): Flow<List<WakeWord>>

    @Query("SELECT * FROM wake_words WHERE triggerType = :triggerType AND isDeleted = 0 ORDER BY createdAt ASC, id ASC")
    fun getWakeWordsByTriggerType(triggerType: TriggerType): Flow<List<WakeWord>>

    @Query("SELECT * FROM wake_words WHERE triggerType = :triggerType AND isDeleted = 0 ORDER BY createdAt ASC, id ASC")
    fun getWakeWordsByTriggerTypeFlow(triggerType: TriggerType): Flow<List<WakeWord>>

    @Query("SELECT COUNT(*) FROM wake_words WHERE syncStatus != 'SYNCED' OR isDeleted = 1")
    fun observePendingSyncWakeWordCount(): Flow<Int>

    @Query("SELECT * FROM wake_words WHERE isDeleted = 0 ORDER BY createdAt ASC, id ASC")
    suspend fun getAllWakeWordsSync(): List<WakeWord>

    // =========================================================================
    // Sync Queue, State Transitions & Account Re-Binding
    // =========================================================================

    @Query("SELECT * FROM wake_words WHERE isDeleted = 0 AND syncStatus != 'SYNCED' ORDER BY updatedAt ASC")
    suspend fun getPendingSyncWakeWords(): List<WakeWord>

    @Query("SELECT * FROM wake_words WHERE isDeleted = 1 AND syncStatus != 'SYNCED' ORDER BY updatedAt ASC")
    suspend fun getPendingDeletedWakeWords(): List<WakeWord>

    @Query("UPDATE wake_words SET userId = :userId, syncStatus = CASE WHEN userId IS NULL OR userId != :userId THEN 'PENDING' ELSE syncStatus END WHERE isDeleted = 0 AND (userId IS NULL OR userId != :userId)")
    suspend fun rebindWakeWordsForUser(userId: String)

    @Query("SELECT * FROM wake_words WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getWakeWordByRemoteIdSync(remoteId: String): WakeWord?

    @Query("UPDATE wake_words SET remoteId = :remoteId, userId = :userId, syncStatus = :syncStatus, lastSyncedAt = :syncedAt WHERE id = :id")
    suspend fun updateWakeWordSyncState(
        id: Long,
        remoteId: String,
        userId: String?,
        syncStatus: String = SyncStatus.SYNCED,
        syncedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE wake_words SET syncStatus = 'FAILED' WHERE id = :id")
    suspend fun markWakeWordSyncFailed(id: Long)

    // =========================================================================
    // Soft-Delete Tombstones, Restore & Purge Operations
    // =========================================================================

    @Query("UPDATE wake_words SET isDeleted = 1, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END, updatedAt = :updatedAt WHERE id = :id")
    suspend fun markWakeWordSoftDeleted(id: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE wake_words SET isDeleted = 1, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END, updatedAt = :updatedAt WHERE chatId = :chatId AND triggerType = 'CHAT'")
    suspend fun markWakeWordsForChatSoftDeleted(chatId: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE wake_words SET isDeleted = 0, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END, updatedAt = :updatedAt WHERE id = :id")
    suspend fun restoreSoftDeletedWakeWord(id: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM wake_words WHERE id = :id")
    suspend fun purgeDeletedWakeWord(id: Long)

    @Query("DELETE FROM wake_words WHERE isDeleted = 1 AND syncStatus = 'SYNCED'")
    suspend fun purgeAllSyncedDeletedWakeWords()

    // =========================================================================
    // Synchronous Lookups, CRUD & Last-Write-Wins Remote Upsert
    // =========================================================================

    @Query("SELECT * FROM wake_words WHERE triggerType = :triggerType AND isDeleted = 0 ORDER BY createdAt ASC, id ASC")
    suspend fun getWakeWordsByTriggerTypeSync(triggerType: TriggerType): List<WakeWord>

    @Query("SELECT * FROM wake_words WHERE triggerType = 'CHAT' AND (chatId = :chatId OR targetChatId = :chatId) AND isDeleted = 0 ORDER BY createdAt ASC, id ASC")
    suspend fun getChatWakeWordsForChatSync(chatId: Long): List<WakeWord>

    @Query("SELECT * FROM wake_words WHERE triggerType = :triggerType AND phrase = :phrase COLLATE NOCASE AND isDeleted = 0 LIMIT 1")
    suspend fun getWakeWordByPhraseAndTypeSync(phrase: String, triggerType: TriggerType): WakeWord?

    @Query("SELECT * FROM wake_words WHERE commandRole = :role AND isDeleted = 0 LIMIT 1")
    suspend fun getWakeWordByRoleSync(role: String): WakeWord?

    @Query("SELECT * FROM wake_words WHERE id = :id LIMIT 1")
    suspend fun getWakeWordByIdSync(id: Long): WakeWord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWakeWord(wakeWord: WakeWord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(wakeWord: WakeWord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWakeWords(wakeWords: List<WakeWord>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(wakeWords: List<WakeWord>): List<Long>

    @Update
    suspend fun updateWakeWord(wakeWord: WakeWord)

    /**
     * Merges an incoming remote Cloud Firestore wake word document into the local Room cache
     * using Last-Write-Wins conflict resolution on [WakeWord.updatedAt].
     */
    @Transaction
    suspend fun upsertRemoteWakeWord(remoteWakeWord: WakeWord): Long {
        val resolvedRemoteId = remoteWakeWord.resolvedRemoteId
        val existing = (if (remoteWakeWord.id > 0L) getWakeWordByIdSync(remoteWakeWord.id) else null)
            ?: getWakeWordByRemoteIdSync(resolvedRemoteId)
            ?: getWakeWordByPhraseAndTypeSync(remoteWakeWord.phrase, remoteWakeWord.triggerType)

        val now = System.currentTimeMillis()
        val resolvedTargetChatId = remoteWakeWord.effectiveTargetChatId
        if (existing == null) {
            val toInsert = remoteWakeWord.copy(
                chatId = resolvedTargetChatId,
                targetChatId = resolvedTargetChatId,
                sensitivityInt = WakeWord.toSensitivityInt(remoteWakeWord.sensitivity),
                remoteId = resolvedRemoteId,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = if (remoteWakeWord.lastSyncedAt > 0L) remoteWakeWord.lastSyncedAt else now,
                isDeleted = false
            )
            return insertWakeWord(toInsert)
        }

        if (remoteWakeWord.updatedAt >= existing.updatedAt || existing.syncStatus == SyncStatus.SYNCED) {
            val mergedChatId = resolvedTargetChatId ?: existing.effectiveTargetChatId
            val merged = existing.copy(
                phrase = remoteWakeWord.phrase,
                triggerType = remoteWakeWord.triggerType,
                metadata = remoteWakeWord.metadata ?: existing.metadata,
                commandRole = remoteWakeWord.commandRole,
                chatId = mergedChatId,
                targetChatId = mergedChatId,
                chatTitle = remoteWakeWord.chatTitle.ifBlank { existing.chatTitle },
                isEnabled = remoteWakeWord.isEnabled,
                sensitivity = remoteWakeWord.sensitivity,
                sensitivityInt = WakeWord.toSensitivityInt(remoteWakeWord.sensitivity),
                updatedAt = maxOf(remoteWakeWord.updatedAt, existing.updatedAt),
                remoteId = resolvedRemoteId,
                userId = remoteWakeWord.userId ?: existing.userId,
                syncStatus = SyncStatus.SYNCED,
                lastSyncedAt = if (remoteWakeWord.lastSyncedAt > 0L) remoteWakeWord.lastSyncedAt else now,
                isDeleted = remoteWakeWord.isDeleted
            )
            updateWakeWord(merged)
        }
        return existing.id
    }

    @Query("UPDATE wake_words SET phrase = :phrase, updatedAt = :updatedAt, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END WHERE id = :id")
    suspend fun updateWakeWordPhrase(id: Long, phrase: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE wake_words SET metadata = :metadata, updatedAt = :updatedAt, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END WHERE id = :id")
    suspend fun updateWakeWordMetadata(id: Long, metadata: String?, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE wake_words SET isEnabled = :enabled, updatedAt = :updatedAt, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END WHERE id = :id")
    suspend fun setWakeWordEnabled(id: Long, enabled: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE wake_words SET sensitivity = :sensitivity, sensitivityInt = :sensitivityInt, updatedAt = :updatedAt, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END WHERE id = :id")
    suspend fun updateWakeWordSensitivity(
        id: Long,
        sensitivity: Float,
        updatedAt: Long = System.currentTimeMillis(),
        sensitivityInt: Int = WakeWord.toSensitivityInt(sensitivity)
    )

    suspend fun updateWakeWordSensitivityInt(
        id: Long,
        sensitivity: Int,
        updatedAt: Long = System.currentTimeMillis()
    ) {
        val clampedInt = sensitivity.coerceIn(0, 100)
        val floatVal = WakeWord.fromSensitivityInt(clampedInt)
        updateWakeWordSensitivity(id = id, sensitivity = floatVal, updatedAt = updatedAt, sensitivityInt = clampedInt)
    }

    @Query("UPDATE wake_words SET sensitivity = :sensitivity, sensitivityInt = :sensitivityInt, updatedAt = :updatedAt, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END")
    suspend fun updateSensitivityForAll(
        sensitivity: Float,
        updatedAt: Long = System.currentTimeMillis(),
        sensitivityInt: Int = WakeWord.toSensitivityInt(sensitivity)
    )

    suspend fun updateSensitivityForAllInt(
        sensitivity: Int,
        updatedAt: Long = System.currentTimeMillis()
    ) {
        val clampedInt = sensitivity.coerceIn(0, 100)
        val floatVal = WakeWord.fromSensitivityInt(clampedInt)
        updateSensitivityForAll(sensitivity = floatVal, updatedAt = updatedAt, sensitivityInt = clampedInt)
    }

    @Query("UPDATE wake_words SET chatId = :chatId, targetChatId = :chatId, chatTitle = :chatTitle, metadata = :metadata, updatedAt = :updatedAt, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END WHERE id = :id")
    suspend fun updateWakeWordChatBinding(
        id: Long,
        chatId: Long,
        chatTitle: String,
        metadata: String? = chatTitle,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE wake_words SET chatId = :targetChatId, targetChatId = :targetChatId, chatTitle = :chatTitle, metadata = :metadata, updatedAt = :updatedAt, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END WHERE id = :id")
    suspend fun updateWakeWordTargetChat(
        id: Long,
        targetChatId: Long?,
        chatTitle: String = "",
        metadata: String? = chatTitle.ifBlank { null },
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE wake_words SET chatTitle = :chatTitle, updatedAt = :updatedAt, syncStatus = CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END WHERE chatId = :chatId OR targetChatId = :chatId")
    suspend fun updateChatTitleForWakeWords(chatId: Long, chatTitle: String, updatedAt: Long = System.currentTimeMillis())

    @Delete
    suspend fun deleteWakeWord(wakeWord: WakeWord)

    @Delete
    suspend fun delete(wakeWord: WakeWord)

    @Query("DELETE FROM wake_words WHERE id = :id")
    suspend fun deleteWakeWordById(id: Long)

    @Query("DELETE FROM wake_words WHERE phrase = :phrase COLLATE NOCASE")
    suspend fun deleteWakeWordByPhrase(phrase: String)

    @Query("DELETE FROM wake_words WHERE (chatId = :chatId OR targetChatId = :chatId) AND triggerType = 'CHAT'")
    suspend fun deleteWakeWordsForChat(chatId: Long)

    @Query("DELETE FROM wake_words")
    suspend fun deleteAllWakeWords()

    @Transaction
    suspend fun upsertSystemTrigger(
        role: String,
        phrase: String,
        sensitivity: Float = 0.78f,
        metadata: String? = role
    ): Long {
        val now = System.currentTimeMillis()
        val existing = getWakeWordByRoleSync(role)
        return if (existing != null) {
            updateWakeWord(
                existing.copy(
                    phrase = phrase,
                    triggerType = TriggerType.HANDS_FREE,
                    metadata = metadata,
                    commandRole = role,
                    isEnabled = true,
                    sensitivity = sensitivity,
                    sensitivityInt = WakeWord.toSensitivityInt(sensitivity),
                    updatedAt = now,
                    syncStatus = if (existing.syncStatus == SyncStatus.SYNCED) SyncStatus.DIRTY else existing.syncStatus,
                    isDeleted = false
                )
            )
            existing.id
        } else {
            insertWakeWord(
                WakeWord(
                    phrase = phrase,
                    triggerType = TriggerType.HANDS_FREE,
                    metadata = metadata,
                    commandRole = role,
                    chatId = null,
                    targetChatId = null,
                    chatTitle = role,
                    isEnabled = true,
                    sensitivity = sensitivity,
                    sensitivityInt = WakeWord.toSensitivityInt(sensitivity),
                    createdAt = now,
                    updatedAt = now,
                    syncStatus = SyncStatus.PENDING,
                    isDeleted = false
                )
            )
        }
    }

    @Transaction
    suspend fun syncPrimaryChatWakeWord(
        chatId: Long,
        chatTitle: String,
        phrase: String,
        sensitivity: Float = 0.78f,
        metadata: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val existingForChat = getChatWakeWordsForChatSync(chatId)
        return if (existingForChat.isEmpty()) {
            insertWakeWord(
                WakeWord(
                    phrase = phrase,
                    triggerType = TriggerType.CHAT,
                    metadata = metadata ?: chatTitle,
                    commandRole = WakeWord.ROLE_CHAT_WAKE,
                    chatId = chatId,
                    targetChatId = chatId,
                    chatTitle = chatTitle,
                    isEnabled = true,
                    sensitivity = sensitivity,
                    sensitivityInt = WakeWord.toSensitivityInt(sensitivity),
                    createdAt = now,
                    updatedAt = now,
                    syncStatus = SyncStatus.PENDING,
                    isDeleted = false
                )
            )
        } else {
            val primary = existingForChat.first()
            updateWakeWord(
                primary.copy(
                    phrase = phrase,
                    triggerType = TriggerType.CHAT,
                    metadata = metadata ?: primary.metadata ?: chatTitle,
                    commandRole = WakeWord.ROLE_CHAT_WAKE,
                    chatId = chatId,
                    targetChatId = chatId,
                    chatTitle = chatTitle,
                    updatedAt = now,
                    syncStatus = if (primary.syncStatus == SyncStatus.SYNCED) SyncStatus.DIRTY else primary.syncStatus,
                    isDeleted = false
                )
            )
            if (existingForChat.size > 1) {
                updateChatTitleForWakeWords(chatId, chatTitle, now)
            }
            primary.id
        }
    }
}
