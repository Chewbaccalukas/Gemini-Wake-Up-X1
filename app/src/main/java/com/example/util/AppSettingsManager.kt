package com.example.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.bridge.LocalSyncRelayServer
import com.example.cache.ChatMemoryBufferManager
import com.example.coupler.ChromeBridgeSocketManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

private const val TAG = "AppSettingsManager"

/**
 * Result summary of a [AppSettingsManager.forceResetAndClear] system hygiene execution.
 */
data class HygieneResetResult(
    val relayServerStopped: Boolean,
    val bridgeSocketPortReset: Int,
    val audioBufferCleared: Boolean,
    val memoryBufferDetached: Boolean,
    val flushedCacheFilesCount: Int,
    val roomTablesPreserved: Boolean = true,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val summaryMessage: String
        get() = "System hygiene complete: relay socket stopped, audio & memory buffers cleared, $flushedCacheFilesCount cache file(s) flushed (Room tables preserved)."
}

/**
 * Central manager for application-wide settings, system hygiene (`forceResetAndClear`),
 * favorite chat sessions, contextual wake-word routing preferences, and 30-day backup
 * export timestamp tracking.
 */
object AppSettingsManager {

    const val PREFS_NAME = "gemini_wake_settings"
    const val KEY_LAST_BACKUP_EXPORT_MS = "last_backup_export_epoch_ms"
    const val KEY_LAST_BACKUP_REMINDER_SHOWN_MS = "last_backup_reminder_shown_ms"
    const val KEY_FAVORITE_CHAT_IDS = "favorite_chat_ids_csv"
    const val KEY_PINNED_CHAT_IDS = "pinned_chat_ids_csv"
    const val KEY_PINNED_TIMESTAMPS_MAP = "pinned_chat_timestamps_map_entries"
    const val KEY_CONTEXTUAL_ROUTING_ENABLED = "contextual_wake_routing_enabled"
    const val KEY_PROMPT_FAVORITES_ON_GENERAL_WAKE = "prompt_favorites_on_general_wake"
    const val KEY_FIRST_INSTALL_TIMESTAMP_MS = "first_install_timestamp_ms"
    const val KEY_CHAT_CATEGORY_MAP = "chat_category_map_entries"
    const val KEY_MESSAGE_CATEGORY_MAP = "message_category_map_entries"
    const val KEY_CHAT_TAGS_MAP = "chat_tags_map_entries"
    const val KEY_CUSTOM_CATEGORIES_CSV = "custom_chat_categories_csv"

    val PRIMARY_SIDE_FILTER_CATEGORIES = listOf("Work", "Personal", "Projects")
    val DEFAULT_CHAT_CATEGORIES = listOf("Work", "Personal", "Projects", "Research")
    val DEFAULT_SUGGESTED_TAGS = listOf("urgent", "daily", "ideas", "code", "notes", "voice")

    const val BACKUP_REMINDER_THRESHOLD_DAYS = 30L
    const val BACKUP_REMINDER_THRESHOLD_MS = BACKUP_REMINDER_THRESHOLD_DAYS * 24L * 60L * 60L * 1000L

    private val _favoriteChatIds = MutableStateFlow<Set<Long>>(emptySet())
    val favoriteChatIds: StateFlow<Set<Long>> = _favoriteChatIds.asStateFlow()

    private val _pinnedChatIds = MutableStateFlow<Set<Long>>(emptySet())
    val pinnedChatIds: StateFlow<Set<Long>> = _pinnedChatIds.asStateFlow()

    private val _pinnedTimestampsMap = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val pinnedTimestampsMap: StateFlow<Map<Long, Long>> = _pinnedTimestampsMap.asStateFlow()

    private val _contextualRoutingEnabled = MutableStateFlow(true)
    val contextualRoutingEnabled: StateFlow<Boolean> = _contextualRoutingEnabled.asStateFlow()

    private val _promptFavoritesOnWake = MutableStateFlow(false)
    val promptFavoritesOnWake: StateFlow<Boolean> = _promptFavoritesOnWake.asStateFlow()

    private val _chatCategoryMap = MutableStateFlow<Map<Long, String>>(emptyMap())
    val chatCategoryMap: StateFlow<Map<Long, String>> = _chatCategoryMap.asStateFlow()

    private val _messageCategoryMap = MutableStateFlow<Map<Long, String>>(emptyMap())
    val messageCategoryMap: StateFlow<Map<Long, String>> = _messageCategoryMap.asStateFlow()

    private val _chatTagsMap = MutableStateFlow<Map<Long, Set<String>>>(emptyMap())
    val chatTagsMap: StateFlow<Map<Long, Set<String>>> = _chatTagsMap.asStateFlow()

    private val _availableCategories = MutableStateFlow<List<String>>(DEFAULT_CHAT_CATEGORIES)
    val availableCategories: StateFlow<List<String>> = _availableCategories.asStateFlow()

    private val _lastBackupExportTimestampMs = MutableStateFlow(0L)
    val lastBackupExportTimestampMs: StateFlow<Long> = _lastBackupExportTimestampMs.asStateFlow()

    private val _lastHygieneResetResult = MutableStateFlow<HygieneResetResult?>(null)
    val lastHygieneResetResult: StateFlow<HygieneResetResult?> = _lastHygieneResetResult.asStateFlow()

    private val _lastHygieneResetMessage = MutableStateFlow<String?>(null)
    val lastHygieneResetMessage: StateFlow<String?> = _lastHygieneResetMessage.asStateFlow()

    @Volatile
    private var isInitialized = false

    private fun prefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Hydrates reactive state flows from SharedPreferences on app startup.
     */
    fun ensureInitialized(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            val sp = prefs(context)
            val now = System.currentTimeMillis()
            if (!sp.contains(KEY_FIRST_INSTALL_TIMESTAMP_MS)) {
                sp.edit().putLong(KEY_FIRST_INSTALL_TIMESTAMP_MS, now).apply()
            }
            _favoriteChatIds.value = parseFavoriteIds(sp.getString(KEY_FAVORITE_CHAT_IDS, ""))
            _pinnedChatIds.value = parseFavoriteIds(sp.getString(KEY_PINNED_CHAT_IDS, ""))
            _pinnedTimestampsMap.value = parseTimestampMap(sp.getString(KEY_PINNED_TIMESTAMPS_MAP, ""))
            _contextualRoutingEnabled.value = sp.getBoolean(KEY_CONTEXTUAL_ROUTING_ENABLED, true)
            _promptFavoritesOnWake.value = sp.getBoolean(KEY_PROMPT_FAVORITES_ON_GENERAL_WAKE, false)
            _lastBackupExportTimestampMs.value = sp.getLong(KEY_LAST_BACKUP_EXPORT_MS, 0L)
            val parsedCategoryMap = parseCategoryMap(sp.getString(KEY_CHAT_CATEGORY_MAP, ""))
            _chatCategoryMap.value = parsedCategoryMap
            _messageCategoryMap.value = parseCategoryMap(sp.getString(KEY_MESSAGE_CATEGORY_MAP, ""))
            _chatTagsMap.value = parseTagsMap(sp.getString(KEY_CHAT_TAGS_MAP, ""))
            val customCats = parseCustomCategories(sp.getString(KEY_CUSTOM_CATEGORIES_CSV, ""))
            _availableCategories.value = (DEFAULT_CHAT_CATEGORIES + customCats + parsedCategoryMap.values)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
            isInitialized = true
        }
    }

    private fun parseFavoriteIds(csv: String?): Set<Long> {
        if (csv.isNullOrBlank()) return emptySet()
        return csv.split(",")
            .mapNotNull { it.trim().toLongOrNull() }
            .filter { it > 0L }
            .toSet()
    }

    private fun parseTimestampMap(serialized: String?): Map<Long, Long> {
        if (serialized.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<Long, Long>()
        serialized.split(";;").forEach { entry ->
            val parts = entry.split("::", limit = 2)
            if (parts.size == 2) {
                val id = parts[0].trim().toLongOrNull()
                val ts = parts[1].trim().toLongOrNull()
                if (id != null && id > 0L && ts != null && ts > 0L) {
                    result[id] = ts
                }
            }
        }
        return result
    }

    private fun serializeTimestampMap(map: Map<Long, Long>): String {
        return map.entries
            .filter { it.key > 0L && it.value > 0L }
            .joinToString(";;") { "${it.key}::${it.value}" }
    }

    private fun parseCategoryMap(serialized: String?): Map<Long, String> {
        if (serialized.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<Long, String>()
        serialized.split(";;").forEach { entry ->
            val parts = entry.split("::", limit = 2)
            if (parts.size == 2) {
                val id = parts[0].trim().toLongOrNull()
                val category = parts[1].trim()
                if (id != null && id > 0L && category.isNotBlank()) {
                    result[id] = category
                }
            }
        }
        return result
    }

    private fun serializeCategoryMap(map: Map<Long, String>): String {
        return map.entries
            .filter { it.key > 0L && it.value.isNotBlank() }
            .joinToString(";;") { "${it.key}::${it.value.replace(";;", " ").replace("::", " ").trim()}" }
    }

    private fun parseTagsMap(serialized: String?): Map<Long, Set<String>> {
        if (serialized.isNullOrBlank()) return emptyMap()
        val result = mutableMapOf<Long, Set<String>>()
        serialized.split(";;").forEach { entry ->
            val parts = entry.split("::", limit = 2)
            if (parts.size == 2) {
                val id = parts[0].trim().toLongOrNull()
                val tags = parts[1].split("|")
                    .map { it.trim().removePrefix("#").trim() }
                    .filter { it.isNotBlank() }
                    .toSet()
                if (id != null && id > 0L && tags.isNotEmpty()) {
                    result[id] = tags
                }
            }
        }
        return result
    }

    private fun serializeTagsMap(map: Map<Long, Set<String>>): String {
        return map.entries
            .filter { it.key > 0L && it.value.isNotEmpty() }
            .joinToString(";;") { (id, tags) ->
                val cleanTags = tags.map { it.replace("|", "").replace(";;", "").replace("::", "").trim() }
                    .filter { it.isNotBlank() }
                "$id::${cleanTags.joinToString("|")}"
            }
    }

    private fun parseCustomCategories(csv: String?): List<String> {
        if (csv.isNullOrBlank()) return emptyList()
        return csv.split("|")
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    fun getChatCategory(context: Context, chatId: Long): String? {
        ensureInitialized(context)
        return _chatCategoryMap.value[chatId]
    }

    fun setChatCategory(context: Context, chatId: Long, category: String?) {
        if (chatId <= 0L) return
        ensureInitialized(context)
        val cleanCategory = category?.trim()?.takeIf { it.isNotBlank() }
        val updated = _chatCategoryMap.value.toMutableMap().apply {
            if (cleanCategory == null) remove(chatId) else put(chatId, cleanCategory)
        }.toMap()
        _chatCategoryMap.value = updated
        if (cleanCategory != null) {
            addCustomCategory(context, cleanCategory)
        }
        prefs(context).edit()
            .putString(KEY_CHAT_CATEGORY_MAP, serializeCategoryMap(updated))
            .apply()
    }

    /**
     * Automatically applies the conversation session's pre-assigned category to a message in that session.
     */
    fun applyCategoryToMessage(
        context: Context,
        messageId: Long,
        chatId: Long,
        explicitCategory: String? = null
    ): String? {
        if (messageId <= 0L && chatId <= 0L) return null
        ensureInitialized(context)
        val resolvedCategory = explicitCategory?.trim()?.takeIf { it.isNotBlank() }
            ?: _chatCategoryMap.value[chatId]?.trim()?.takeIf { it.isNotBlank() }
            ?: return null
        if (messageId > 0L) {
            val updatedMsgMap = _messageCategoryMap.value.toMutableMap().apply {
                put(messageId, resolvedCategory)
            }.toMap()
            _messageCategoryMap.value = updatedMsgMap
            prefs(context).edit()
                .putString(KEY_MESSAGE_CATEGORY_MAP, serializeCategoryMap(updatedMsgMap))
                .apply()
        }
        return resolvedCategory
    }

    /**
     * Updates the category for a chat session and propagates it to all message IDs belonging to that session.
     */
    fun applyCategoryToChatAndMessages(
        context: Context,
        chatId: Long,
        category: String?,
        messageIds: Collection<Long> = emptyList()
    ) {
        if (chatId <= 0L) return
        setChatCategory(context, chatId, category)
        val cleanCat = category?.trim()?.takeIf { it.isNotBlank() }
        if (messageIds.isNotEmpty()) {
            val updatedMsgMap = _messageCategoryMap.value.toMutableMap()
            for (msgId in messageIds) {
                if (msgId > 0L) {
                    if (cleanCat == null) updatedMsgMap.remove(msgId) else updatedMsgMap[msgId] = cleanCat
                }
            }
            val finalMap = updatedMsgMap.toMap()
            _messageCategoryMap.value = finalMap
            prefs(context).edit()
                .putString(KEY_MESSAGE_CATEGORY_MAP, serializeCategoryMap(finalMap))
                .apply()
        }
    }

    fun getEffectiveMessageCategory(messageId: Long, chatId: Long): String? {
        return _messageCategoryMap.value[messageId] ?: _chatCategoryMap.value[chatId]
    }

    fun addCustomCategory(context: Context, category: String) {
        val clean = category.trim()
        if (clean.isBlank()) return
        ensureInitialized(context)
        val merged = (_availableCategories.value + clean)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
        _availableCategories.value = merged
        val customOnly = merged.filter { cat ->
            DEFAULT_CHAT_CATEGORIES.none { it.equals(cat, ignoreCase = true) }
        }
        prefs(context).edit()
            .putString(KEY_CUSTOM_CATEGORIES_CSV, customOnly.joinToString("|"))
            .apply()
    }

    fun getChatTags(context: Context, chatId: Long): Set<String> {
        ensureInitialized(context)
        return _chatTagsMap.value[chatId] ?: emptySet()
    }

    fun setChatTags(context: Context, chatId: Long, tags: Set<String>) {
        if (chatId <= 0L) return
        ensureInitialized(context)
        val cleanTags = tags.map { it.trim().removePrefix("#").trim() }
            .filter { it.isNotBlank() }
            .toSet()
        val updated = _chatTagsMap.value.toMutableMap().apply {
            if (cleanTags.isEmpty()) remove(chatId) else put(chatId, cleanTags)
        }.toMap()
        _chatTagsMap.value = updated
        prefs(context).edit()
            .putString(KEY_CHAT_TAGS_MAP, serializeTagsMap(updated))
            .apply()
    }

    fun toggleChatTag(context: Context, chatId: Long, tag: String): Set<String> {
        ensureInitialized(context)
        val normalized = tag.trim().removePrefix("#").trim()
        if (normalized.isBlank()) return getChatTags(context, chatId)
        val current = getChatTags(context, chatId).toMutableSet()
        val existingMatch = current.firstOrNull { it.equals(normalized, ignoreCase = true) }
        if (existingMatch != null) {
            current.remove(existingMatch)
        } else {
            current.add(normalized)
        }
        setChatTags(context, chatId, current)
        return current
    }

    fun isFavoriteChat(context: Context, chatId: Long): Boolean {
        ensureInitialized(context)
        return _favoriteChatIds.value.contains(chatId)
    }

    fun toggleFavoriteChat(context: Context, chatId: Long): Set<Long> {
        ensureInitialized(context)
        val updated = _favoriteChatIds.value.toMutableSet().apply {
            if (contains(chatId)) remove(chatId) else add(chatId)
        }.toSet()
        _favoriteChatIds.value = updated
        prefs(context).edit()
            .putString(KEY_FAVORITE_CHAT_IDS, updated.joinToString(","))
            .apply()
        return updated
    }

    fun setFavoriteChats(context: Context, chatIds: Set<Long>) {
        ensureInitialized(context)
        val cleaned = chatIds.filter { it > 0L }.toSet()
        _favoriteChatIds.value = cleaned
        prefs(context).edit()
            .putString(KEY_FAVORITE_CHAT_IDS, cleaned.joinToString(","))
            .apply()
    }

    fun isPinnedChat(context: Context, chatId: Long): Boolean {
        ensureInitialized(context)
        return _pinnedChatIds.value.contains(chatId)
    }

    /**
     * Toggles whether [chatId] is pinned to the top of the chat list regardless of recency.
     * Returns the updated set of pinned chat IDs.
     */
    fun togglePinnedChat(context: Context, chatId: Long, nowMs: Long = System.currentTimeMillis()): Set<Long> {
        if (chatId <= 0L) return _pinnedChatIds.value
        ensureInitialized(context)
        val updatedTimestamps = _pinnedTimestampsMap.value.toMutableMap()
        val updatedPinned = _pinnedChatIds.value.toMutableSet().apply {
            if (contains(chatId)) {
                remove(chatId)
                updatedTimestamps.remove(chatId)
            } else {
                add(chatId)
                updatedTimestamps[chatId] = nowMs
            }
        }.toSet()
        val finalTimestamps = updatedTimestamps.toMap()
        _pinnedChatIds.value = updatedPinned
        _pinnedTimestampsMap.value = finalTimestamps
        prefs(context).edit()
            .putString(KEY_PINNED_CHAT_IDS, updatedPinned.joinToString(","))
            .putString(KEY_PINNED_TIMESTAMPS_MAP, serializeTimestampMap(finalTimestamps))
            .apply()
        return updatedPinned
    }

    fun unpinChat(context: Context, chatId: Long) {
        if (chatId <= 0L) return
        ensureInitialized(context)
        if (!_pinnedChatIds.value.contains(chatId)) return
        val updatedPinned = (_pinnedChatIds.value - chatId)
        val updatedTimestamps = (_pinnedTimestampsMap.value - chatId)
        _pinnedChatIds.value = updatedPinned
        _pinnedTimestampsMap.value = updatedTimestamps
        prefs(context).edit()
            .putString(KEY_PINNED_CHAT_IDS, updatedPinned.joinToString(","))
            .putString(KEY_PINNED_TIMESTAMPS_MAP, serializeTimestampMap(updatedTimestamps))
            .apply()
    }

    /**
     * Orders [chats] so that all pinned chats appear at the top of the list regardless of recency,
     * followed by unpinned chats ordered by recency (`lastUpdatedAt` descending).
     */
    fun sortChatsWithPinnedFirst(
        chats: List<com.example.data.model.ChatSession>,
        pinnedIds: Set<Long> = _pinnedChatIds.value,
        pinnedTimestamps: Map<Long, Long> = _pinnedTimestampsMap.value
    ): List<com.example.data.model.ChatSession> {
        if (chats.size <= 1) return chats
        return chats.sortedWith(
            Comparator { a, b ->
                val aPinned = a.isPinned || pinnedIds.contains(a.id)
                val bPinned = b.isPinned || pinnedIds.contains(b.id)
                when {
                    aPinned && !bPinned -> -1
                    !aPinned && bPinned -> 1
                    aPinned && bPinned -> {
                        val aPinTime = pinnedTimestamps[a.id] ?: 0L
                        val bPinTime = pinnedTimestamps[b.id] ?: 0L
                        if (aPinTime != bPinTime && aPinTime > 0L && bPinTime > 0L) {
                            bPinTime.compareTo(aPinTime)
                        } else {
                            b.lastUpdatedAt.compareTo(a.lastUpdatedAt)
                        }
                    }
                    else -> b.lastUpdatedAt.compareTo(a.lastUpdatedAt)
                }
            }
        )
    }

    fun setContextualRoutingEnabled(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        _contextualRoutingEnabled.value = enabled
        prefs(context).edit().putBoolean(KEY_CONTEXTUAL_ROUTING_ENABLED, enabled).apply()
    }

    fun setPromptFavoritesOnWake(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        _promptFavoritesOnWake.value = enabled
        prefs(context).edit().putBoolean(KEY_PROMPT_FAVORITES_ON_GENERAL_WAKE, enabled).apply()
    }

    /**
     * Records a successful JSON or CSV backup export timestamp so the 30-day backup reminder
     * resets its countdown window.
     */
    fun recordBackupExportTimestamp(
        context: Context,
        timestampMs: Long = System.currentTimeMillis()
    ) {
        ensureInitialized(context)
        _lastBackupExportTimestampMs.value = timestampMs
        prefs(context).edit()
            .putLong(KEY_LAST_BACKUP_EXPORT_MS, timestampMs)
            .apply()
    }

    fun getLastBackupExportTimestamp(context: Context): Long {
        ensureInitialized(context)
        return prefs(context).getLong(KEY_LAST_BACKUP_EXPORT_MS, 0L)
    }

    /**
     * Returns true if the user has not exported a backup in over [thresholdDays] (default 30 days).
     * Uses the earliest known chat creation timestamp or first install timestamp when no export
     * has ever been performed yet.
     */
    fun isBackupOverdue(
        context: Context,
        earliestChatCreatedAtMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
        thresholdDays: Long = BACKUP_REMINDER_THRESHOLD_DAYS
    ): Boolean {
        ensureInitialized(context)
        val sp = prefs(context)
        val lastExportMs = sp.getLong(KEY_LAST_BACKUP_EXPORT_MS, 0L)
        val referenceMs = when {
            lastExportMs > 0L -> lastExportMs
            earliestChatCreatedAtMs != null && earliestChatCreatedAtMs > 0L -> earliestChatCreatedAtMs
            else -> sp.getLong(KEY_FIRST_INSTALL_TIMESTAMP_MS, nowMs)
        }
        val thresholdMs = thresholdDays * 24L * 60L * 60L * 1000L
        return (nowMs - referenceMs) >= thresholdMs
    }

    fun daysSinceLastBackup(
        context: Context,
        earliestChatCreatedAtMs: Long? = null,
        nowMs: Long = System.currentTimeMillis()
    ): Long {
        ensureInitialized(context)
        val sp = prefs(context)
        val lastExportMs = sp.getLong(KEY_LAST_BACKUP_EXPORT_MS, 0L)
        val referenceMs = when {
            lastExportMs > 0L -> lastExportMs
            earliestChatCreatedAtMs != null && earliestChatCreatedAtMs > 0L -> earliestChatCreatedAtMs
            else -> sp.getLong(KEY_FIRST_INSTALL_TIMESTAMP_MS, nowMs)
        }
        return ((nowMs - referenceMs).coerceAtLeast(0L)) / (24L * 60L * 60L * 1000L)
    }

    /**
     * Pillar 3 — System Hygiene:
     * Stops the loopback relay socket (`LocalSyncRelayServer` and `ChromeBridgeSocketManager`),
     * clears the in-memory audio & message hot buffers, and flushes ephemeral cache files
     * (`exports/`, staged camera/screenshot files, and optional disk message cache buffers)
     * **without** wiping any Room database tables.
     */
    fun forceResetAndClear(
        context: Context,
        relayServer: LocalSyncRelayServer? = null,
        bufferManager: ChatMemoryBufferManager? = null,
        clearAudioBufferCallback: (() -> Unit)? = null,
        preserveDiskCache: Boolean = false
    ): HygieneResetResult {
        ensureInitialized(context)

        // 1. Stop LocalSyncRelayServer (127.0.0.1:8765) and clear deduplication cache
        var relayStopped = false
        try {
            if (relayServer != null) {
                relayServer.stop()
                relayServer.clearTurnDeduplicationCache()
                relayStopped = true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping LocalSyncRelayServer during forceResetAndClear", e)
        }

        // 2. Force reset ChromeBridgeSocketManager
        val newBridgePort = try {
            ChromeBridgeSocketManager.forceResetAndClear(
                context = context.applicationContext,
                preserveDiskCache = preserveDiskCache
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error resetting ChromeBridgeSocketManager", e)
            -1
        }

        // 3. Clear active PCM/voice audio buffer
        var audioCleared = false
        try {
            clearAudioBufferCallback?.invoke()
            audioCleared = true
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing audio buffer during forceResetAndClear", e)
        }

        // 4. Detach in-memory hot window in ChatMemoryBufferManager
        var memoryDetached = false
        try {
            bufferManager?.detachChat()
            memoryDetached = true
        } catch (e: Exception) {
            Log.w(TAG, "Error detaching ChatMemoryBufferManager", e)
        }

        // 5. Flush ephemeral cache files in cacheDir without touching Room SQLite database files
        val flushedCount = flushEphemeralCacheFiles(
            cacheDir = context.applicationContext.cacheDir,
            preserveChatDiskBuffers = preserveDiskCache
        )

        val result = HygieneResetResult(
            relayServerStopped = relayStopped,
            bridgeSocketPortReset = newBridgePort,
            audioBufferCleared = audioCleared,
            memoryBufferDetached = memoryDetached,
            flushedCacheFilesCount = flushedCount,
            roomTablesPreserved = true
        )
        _lastHygieneResetResult.value = result
        _lastHygieneResetMessage.value = result.summaryMessage
        Log.i(
            TAG,
            "forceResetAndClear complete: relayStopped=$relayStopped, flushedFiles=$flushedCount, roomTablesPreserved=true"
        )
        return result
    }

    /**
     * Deletes temporary export files (`cacheDir/exports`), staged camera/screenshot files,
     * and (unless [preserveChatDiskBuffers] is true) temporary disk message buffers.
     * Never touches the application's Room database directory.
     */
    fun flushEphemeralCacheFiles(
        cacheDir: File,
        preserveChatDiskBuffers: Boolean = false
    ): Int {
        if (!cacheDir.exists() || !cacheDir.isDirectory) return 0
        var deletedCount = 0

        // Flush cacheDir/exports
        val exportsDir = File(cacheDir, "exports")
        if (exportsDir.exists() && exportsDir.isDirectory) {
            exportsDir.listFiles()?.forEach { file ->
                try {
                    if (file.isFile && file.delete()) {
                        deletedCount++
                    }
                } catch (_: Throwable) {
                }
            }
        }

        // Flush cacheDir/chat_disk_buffers if not preserving across sessions
        if (!preserveChatDiskBuffers) {
            val diskBuffersDir = File(cacheDir, "chat_disk_buffers")
            if (diskBuffersDir.exists() && diskBuffersDir.isDirectory) {
                diskBuffersDir.listFiles()?.forEach { file ->
                    try {
                        if (file.isFile && file.delete()) {
                            deletedCount++
                        }
                    } catch (_: Throwable) {
                    }
                }
            }
        }

        // Flush top-level ephemeral staged files (staged_camera_*, temp_*, screenshot_*)
        cacheDir.listFiles()?.forEach { file ->
            try {
                if (file.isFile && (
                        file.name.startsWith("staged_camera_") ||
                            file.name.startsWith("temp_") ||
                            file.name.startsWith("shared_") ||
                            file.name.endsWith(".tmp")
                        )
                ) {
                    if (file.delete()) {
                        deletedCount++
                    }
                }
            } catch (_: Throwable) {
            }
        }

        return deletedCount
    }
}
