package com.example.cache

import android.content.Context
import android.util.Log
import com.example.data.dao.ChatDao
import com.example.data.dao.MessageDao
import com.example.data.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "ChatMemoryBufferManager"

data class BufferMetrics(
    val hotMemoryCount: Int = 0,
    val totalDiskCount: Int = 0,
    val diskCacheSizeBytes: Long = 0L,
    val incomingDiskCount: Int = 0,
    val outgoingDiskCount: Int = 0,
    val incomingDiskSizeBytes: Long = 0L,
    val outgoingDiskSizeBytes: Long = 0L,
    val hotBufferSizeLimit: Int = ChatMemoryBufferManager.DEFAULT_HOT_WINDOW_LIMIT,
    val isPreloaded: Boolean = false,
    val preloadStatus: String = "Ready"
)

/**
 * Module 2 — The Anti-Freeze Two-Tier Memory Buffer:
 * 1. **Hot In-Memory Sliding Window (Tier 1)**:
 *    - Capped at [DEFAULT_HOT_WINDOW_LIMIT] (30 items) for initial load and live appends so Compose
 *      `LazyColumn` renders at 60fps without large allocations.
 *    - Preloads the initial recent batch on [Dispatchers.IO] via keyset query
 *      `getRecentMessages(chatId, limit = 30)` (`ORDER BY timestamp DESC`), reverses it in memory
 *      for chronological ascending display, and records [oldestLoadedTimestamp].
 * 2. **Keyset Prefetch & Disk Cache (Tier 2)**:
 *    - [loadOlderMessagesChunk] queries Room strictly before [oldestLoadedTimestamp]
 *      (`WHERE chatId = :chatId AND timestamp < :beforeTimestamp ORDER BY timestamp DESC LIMIT :limit`),
 *      reverses the older chunk into chronological order, and prepends it to [hotMessages] without
 *      wiping the active hot window.
 *    - Ensures every item in [hotMessages] carries a unique, stable `id` so `LazyColumn`
 *      `key = { message.id }` preserves scroll anchoring during history prepends.
 */
class ChatMemoryBufferManager(
    private val context: Context,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao? = null
) {
    companion object {
        const val DEFAULT_HOT_WINDOW_LIMIT = 30
    }

    private val cacheDir = File(context.cacheDir, "chat_disk_buffers").apply {
        if (!exists()) mkdirs()
    }

    private val _bufferMetrics = MutableStateFlow(BufferMetrics())
    val bufferMetrics: StateFlow<BufferMetrics> = _bufferMetrics.asStateFlow()

    private val _hotMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val hotMessages: StateFlow<List<ChatMessage>> = _hotMessages.asStateFlow()

    private val _isLoadingOlder = MutableStateFlow(false)
    val isLoadingOlder: StateFlow<Boolean> = _isLoadingOlder.asStateFlow()

    private val _hasMoreOlderMessages = MutableStateFlow(false)
    val hasMoreOlderMessages: StateFlow<Boolean> = _hasMoreOlderMessages.asStateFlow()

    private val _oldestLoadedTimestampFlow = MutableStateFlow<Long?>(null)
    val oldestLoadedTimestampFlow: StateFlow<Long?> = _oldestLoadedTimestampFlow.asStateFlow()

    @Volatile
    var oldestLoadedTimestamp: Long? = null
        private set

    private var currentChatId: Long? = null
    private var hotBufferSizeLimit: Int = DEFAULT_HOT_WINDOW_LIMIT

    /**
     * Tracks whether the user has explicitly paged older history chunks in the current session.
     * When `false`, live appends keep the hot window capped at [hotBufferSizeLimit] (30 items).
     * When `true`, prepended historical chunks remain visible in [hotMessages].
     */
    private var hasExpandedOlderHistory: Boolean = false

    /**
     * Full chronological backup list used for disk-cache mirroring and fallback when Room is empty.
     */
    private var allCachedMessagesForCurrentChat: MutableList<ChatMessage> = mutableListOf()

    private fun updateOldestTimestamp(timestamp: Long?) {
        oldestLoadedTimestamp = timestamp
        _oldestLoadedTimestampFlow.value = timestamp
    }

    /**
     * Ensures every message in a list has a unique, stable non-zero [ChatMessage.id]
     * so `LazyColumn(key = { message.id })` never encounters duplicate keys.
     */
    private fun ensureStableUniqueIds(messages: List<ChatMessage>): List<ChatMessage> {
        val seenIds = HashSet<Long>(messages.size * 2)
        return messages.mapIndexed { index, msg ->
            var candidateId = if (msg.id > 0L) msg.id else (msg.timestamp * 31L + index + 1L).coerceAtLeast(1L)
            while (!seenIds.add(candidateId)) {
                candidateId++
            }
            if (candidateId == msg.id) msg else msg.copy(id = candidateId)
        }
    }

    fun setBufferSizeLimit(limit: Int) {
        hotBufferSizeLimit = limit.coerceIn(10, 100)
        hasExpandedOlderHistory = false
        refreshHotWindowFromCache()
    }

    /**
     * Batch preloads the initial chunk (up to [hotBufferSizeLimit] = 30 items) on [Dispatchers.IO]
     * using keyset pagination (`getRecentMessages(chatId, limit)`), reverses it in memory for
     * chronological display, and records [oldestLoadedTimestamp].
     */
    suspend fun preloadAndAttachChat(chatId: Long) = withContext(Dispatchers.IO) {
        currentChatId = chatId
        hasExpandedOlderHistory = false
        _bufferMetrics.value = _bufferMetrics.value.copy(
            preloadStatus = "Warming disk cache...",
            isPreloaded = false
        )

        try {
            // 1. Keyset initial load: query top N newest messages in DESC order on Dispatchers.IO
            val recentDesc = try {
                messageDao?.getRecentMessages(chatId, hotBufferSizeLimit)
                    ?: chatDao.getRecentMessages(chatId, hotBufferSizeLimit)
            } catch (_: Throwable) {
                emptyList()
            }

            // 2. Reverse in memory for chronological ascending display (oldest -> newest within the 30-item window)
            val initialChronological = if (recentDesc.isNotEmpty()) {
                recentDesc
                    .filter { it.chatId == chatId && !it.isDeleted && it.hasPersistableContent }
                    .asReversed()
                    .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
            } else {
                // Fallback to disk cache file if Room has no rows yet
                (readDiskCacheFile(chatId) ?: emptyList())
                    .filter { it.chatId == chatId && !it.isDeleted && it.hasPersistableContent }
                    .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
                    .takeLast(hotBufferSizeLimit)
            }

            val stabilizedHot = ensureStableUniqueIds(initialChronological.takeLast(hotBufferSizeLimit))
            val oldestTs = stabilizedHot.firstOrNull()?.timestamp
            updateOldestTimestamp(oldestTs)

            // 3. Check if older messages exist before oldestLoadedTimestamp via keyset count/probe
            val olderCountInDb = if (oldestTs != null) {
                try {
                    messageDao?.countMessagesBefore(chatId, oldestTs)
                        ?: chatDao.countMessagesBefore(chatId, oldestTs)
                } catch (_: Throwable) {
                    0
                }
            } else {
                0
            }

            // Also keep background disk cache synchronized
            val fullDbSnapshot = try {
                (messageDao?.getMessagesForChatSync(chatId) ?: chatDao.getMessagesForChatSync(chatId))
                    .filter { it.chatId == chatId && !it.isDeleted && it.hasPersistableContent }
                    .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
            } catch (_: Throwable) {
                stabilizedHot
            }

            synchronized(allCachedMessagesForCurrentChat) {
                allCachedMessagesForCurrentChat.clear()
                allCachedMessagesForCurrentChat.addAll(ensureStableUniqueIds(fullDbSnapshot))
            }

            if (fullDbSnapshot.isNotEmpty()) {
                writeDiskCacheFileAsync(chatId, fullDbSnapshot)
            }

            val totalCount = maxOf(fullDbSnapshot.size, stabilizedHot.size + olderCountInDb)
            val hasMoreOlder = olderCountInDb > 0 || totalCount > stabilizedHot.size

            _hotMessages.value = stabilizedHot
            _hasMoreOlderMessages.value = hasMoreOlder
            updateMetrics(
                hotList = stabilizedHot,
                totalDiskCount = totalCount,
                statusOverride = if (hasMoreOlder) {
                    "Preloaded & Warm (${stabilizedHot.size} in RAM, ${totalCount - stabilizedHot.size} on Disk)"
                } else {
                    "Preloaded & Warm"
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Preload failed for chat $chatId", e)
            _bufferMetrics.value = _bufferMetrics.value.copy(
                preloadStatus = "Ready (Fallback mode)"
            )
        }
    }

    /**
     * Detaches the active chat session from RAM, immediately purging in-memory message
     * buffers and resetting keyset pagination state to prevent cross-session memory bleeding.
     */
    fun detachChat() {
        currentChatId = null
        hasExpandedOlderHistory = false
        updateOldestTimestamp(null)
        synchronized(allCachedMessagesForCurrentChat) {
            allCachedMessagesForCurrentChat.clear()
        }
        _hotMessages.value = emptyList()
        _hasMoreOlderMessages.value = false
        _isLoadingOlder.value = false
        _bufferMetrics.value = BufferMetrics(
            hotMemoryCount = 0,
            totalDiskCount = 0,
            diskCacheSizeBytes = 0L,
            incomingDiskCount = 0,
            outgoingDiskCount = 0,
            incomingDiskSizeBytes = 0L,
            outgoingDiskSizeBytes = 0L,
            hotBufferSizeLimit = hotBufferSizeLimit,
            isPreloaded = false,
            preloadStatus = "Detached"
        )
    }

    /**
     * Updates the full cache and slides the active 30-item hot buffer window (unless the user
     * has expanded older history chunks), strictly isolating updates to the currently attached
     * [chatId] and ordering messages chronologically.
     */
    fun updateFullMessageList(chatId: Long, messages: List<ChatMessage>) {
        if (currentChatId != null && currentChatId != chatId) {
            // Stale repository emission for a previous chat; drop to prevent bleed
            return
        }
        currentChatId = chatId
        val chronological = ensureStableUniqueIds(
            messages
                .filter { it.chatId == chatId && !it.isDeleted && it.hasPersistableContent }
                .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
        )
        synchronized(allCachedMessagesForCurrentChat) {
            allCachedMessagesForCurrentChat = chronological.toMutableList()
        }

        // Asynchronously persist disk backup for instantaneous next-session preload
        writeDiskCacheFileAsync(chatId, chronological)

        refreshHotWindowFromCache()
    }

    /**
     * Appends a newly sent or received message to the hot window and disk buffer if belonging
     * to the current chat, preserving strict chronological order and capping the sliding window
     * at [hotBufferSizeLimit] (30 items) when older history is not expanded.
     */
    fun appendMessage(message: ChatMessage) {
        if (currentChatId != null && currentChatId != message.chatId) {
            return
        }
        if (!message.hasPersistableContent) {
            return
        }
        synchronized(allCachedMessagesForCurrentChat) {
            if (message.id > 0L) {
                allCachedMessagesForCurrentChat.removeAll { it.id == message.id }
            }
            allCachedMessagesForCurrentChat.removeAll {
                it.chatId == message.chatId &&
                    it.timestamp == message.timestamp &&
                    it.sender.equals(message.sender, ignoreCase = true)
            }
            allCachedMessagesForCurrentChat.add(message)
            allCachedMessagesForCurrentChat.sortWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
        }

        val currentHot = _hotMessages.value.toMutableList()
        if (message.id > 0L) {
            currentHot.removeAll { it.id == message.id }
        }
        currentHot.removeAll {
            it.chatId == message.chatId &&
                it.timestamp == message.timestamp &&
                it.sender.equals(message.sender, ignoreCase = true)
        }
        currentHot.add(message)
        currentHot.sortWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })

        val effectiveWindowCap = if (hasExpandedOlderHistory) {
            maxOf(currentHot.size, hotBufferSizeLimit)
        } else {
            hotBufferSizeLimit
        }
        val updatedHot = ensureStableUniqueIds(currentHot.takeLast(effectiveWindowCap))
        _hotMessages.value = updatedHot
        updateOldestTimestamp(updatedHot.firstOrNull()?.timestamp)

        val totalCached = synchronized(allCachedMessagesForCurrentChat) { allCachedMessagesForCurrentChat.size }
        _hasMoreOlderMessages.value = totalCached > updatedHot.size
        updateMetrics(hotList = updatedHot, totalDiskCount = maxOf(totalCached, updatedHot.size))
        currentChatId?.let { id ->
            val snapshot = synchronized(allCachedMessagesForCurrentChat) { allCachedMessagesForCurrentChat.toList() }
            writeDiskCacheFileAsync(id, snapshot)
        }
    }

    /**
     * Keyset backward prefetch: queries records strictly before [oldestLoadedTimestamp]
     * (`timestamp < :beforeTimestamp ORDER BY timestamp DESC LIMIT :limit`) on [Dispatchers.IO],
     * reverses the older chunk into chronological order, and prepends it to [hotMessages]
     * without wiping the current hot window.
     */
    suspend fun loadOlderMessagesChunk() = withContext(Dispatchers.IO) {
        if (_isLoadingOlder.value || !_hasMoreOlderMessages.value) return@withContext
        val chatId = currentChatId ?: return@withContext
        val currentHot = _hotMessages.value
        val beforeTs = oldestLoadedTimestamp ?: currentHot.firstOrNull()?.timestamp ?: Long.MAX_VALUE

        _isLoadingOlder.value = true
        try {
            // 1. Query Room via keyset pagination strictly before oldestLoadedTimestamp
            val dbOlderDesc = try {
                messageDao?.getMessagesBefore(
                    chatId = chatId,
                    beforeTimestamp = beforeTs,
                    limit = hotBufferSizeLimit
                ) ?: chatDao.getMessagesBefore(
                    chatId = chatId,
                    beforeTimestamp = beforeTs,
                    limit = hotBufferSizeLimit
                )
            } catch (_: Throwable) {
                emptyList()
            }

            // 2. Reverse DESC chunk in memory for chronological ascending order; fall back to cached list if needed
            val olderChronological = if (dbOlderDesc.isNotEmpty()) {
                dbOlderDesc
                    .filter { it.chatId == chatId && !it.isDeleted && it.hasPersistableContent }
                    .asReversed()
                    .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
            } else {
                synchronized(allCachedMessagesForCurrentChat) {
                    allCachedMessagesForCurrentChat
                        .filter { it.timestamp < beforeTs }
                        .takeLast(hotBufferSizeLimit)
                }
            }

            if (olderChronological.isEmpty()) {
                _hasMoreOlderMessages.value = false
                return@withContext
            }

            // 3. Prepend older chunk ahead of existing hot window without wiping current hotMessages
            val existingIds = currentHot.map { it.id }.toHashSet()
            val deduplicatedOlder = olderChronological.filter { older ->
                (older.id <= 0L || older.id !in existingIds) &&
                    currentHot.none { existing ->
                        existing.timestamp == older.timestamp &&
                            existing.sender.equals(older.sender, ignoreCase = true)
                    }
            }

            val combined = ensureStableUniqueIds(
                (deduplicatedOlder + currentHot)
                    .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
            )
            val newOldestTs = combined.firstOrNull()?.timestamp
            updateOldestTimestamp(newOldestTs)
            hasExpandedOlderHistory = true

            // 4. Determine whether even older records exist before newOldestTs
            val remainingOlderInDb = if (newOldestTs != null) {
                try {
                    messageDao?.countMessagesBefore(chatId, newOldestTs)
                        ?: chatDao.countMessagesBefore(chatId, newOldestTs)
                } catch (_: Throwable) {
                    0
                }
            } else {
                0
            }
            val remainingOlderInCache = synchronized(allCachedMessagesForCurrentChat) {
                if (newOldestTs != null) {
                    allCachedMessagesForCurrentChat.count { it.timestamp < newOldestTs }
                } else {
                    0
                }
            }
            val hasMore = remainingOlderInDb > 0 || remainingOlderInCache > 0

            val totalKnown = maxOf(
                synchronized(allCachedMessagesForCurrentChat) { allCachedMessagesForCurrentChat.size },
                combined.size + maxOf(remainingOlderInDb, remainingOlderInCache)
            )

            _hotMessages.value = combined
            _hasMoreOlderMessages.value = hasMore
            updateMetrics(
                hotList = combined,
                totalDiskCount = totalKnown,
                statusOverride = "Paged (${combined.size}/$totalKnown in RAM)"
            )
        } finally {
            _isLoadingOlder.value = false
        }
    }

    private fun refreshHotWindowFromCache() {
        val snapshot = synchronized(allCachedMessagesForCurrentChat) {
            allCachedMessagesForCurrentChat.toList()
        }
        val total = snapshot.size
        val targetWindowSize = if (hasExpandedOlderHistory) {
            maxOf(_hotMessages.value.size, hotBufferSizeLimit).coerceAtMost(total.coerceAtLeast(hotBufferSizeLimit))
        } else {
            hotBufferSizeLimit
        }
        val hotList = ensureStableUniqueIds(
            if (total <= targetWindowSize) {
                snapshot
            } else {
                snapshot.takeLast(targetWindowSize)
            }
        )

        _hotMessages.value = hotList
        updateOldestTimestamp(hotList.firstOrNull()?.timestamp)
        _hasMoreOlderMessages.value = total > hotList.size
        updateMetrics(hotList = hotList, totalDiskCount = total)
    }

    private fun updateMetrics(
        hotList: List<ChatMessage>,
        totalDiskCount: Int,
        statusOverride: String? = null
    ) {
        val incomingFile = currentChatId?.let { getIncomingCacheFile(it) }
        val outgoingFile = currentChatId?.let { getOutgoingCacheFile(it) }
        val incomingSize = if (incomingFile != null && incomingFile.exists()) incomingFile.length() else 0L
        val outgoingSize = if (outgoingFile != null && outgoingFile.exists()) outgoingFile.length() else 0L
        val totalFileSize = incomingSize + outgoingSize

        val snapshot = synchronized(allCachedMessagesForCurrentChat) {
            if (allCachedMessagesForCurrentChat.isNotEmpty()) allCachedMessagesForCurrentChat.toList() else hotList
        }
        val incomingCount = snapshot.count { it.sender != "user" }
        val outgoingCount = snapshot.count { it.sender == "user" }

        val resolvedStatus = statusOverride ?: if (totalDiskCount > hotList.size) {
            "Buffered (${hotList.size} in RAM, ${totalDiskCount - hotList.size} on Disk)"
        } else {
            "Optimal (All in RAM)"
        }

        _bufferMetrics.value = _bufferMetrics.value.copy(
            hotMemoryCount = hotList.size,
            totalDiskCount = totalDiskCount,
            diskCacheSizeBytes = totalFileSize,
            incomingDiskCount = incomingCount,
            outgoingDiskCount = outgoingCount,
            incomingDiskSizeBytes = incomingSize,
            outgoingDiskSizeBytes = outgoingSize,
            hotBufferSizeLimit = hotBufferSizeLimit,
            isPreloaded = true,
            preloadStatus = resolvedStatus
        )
    }

    private fun getIncomingCacheFile(chatId: Long): File {
        return File(cacheDir, "chat_cache_incoming_$chatId.json")
    }

    private fun getOutgoingCacheFile(chatId: Long): File {
        return File(cacheDir, "chat_cache_outgoing_$chatId.json")
    }

    private fun writeDiskCacheFileAsync(chatId: Long, messages: List<ChatMessage>) {
        try {
            val incomingMessages = messages.filter { it.sender != "user" }
            val outgoingMessages = messages.filter { it.sender == "user" }

            writeMessagesToFile(getIncomingCacheFile(chatId), incomingMessages)
            writeMessagesToFile(getOutgoingCacheFile(chatId), outgoingMessages)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write disk cache files", e)
        }
    }

    private fun writeMessagesToFile(file: File, messages: List<ChatMessage>) {
        try {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            val jsonArray = JSONArray()
            val fallbackNow = System.currentTimeMillis().coerceAtLeast(1L)
            messages.forEachIndexed { index, msg ->
                try {
                    val validTimestamp = if (msg.timestamp > 0L) msg.timestamp else fallbackNow + index
                    val obj = JSONObject().apply {
                        put("id", if (msg.id > 0L) msg.id else (fallbackNow + index))
                        put("chatId", msg.chatId)
                        put("sender", msg.sender.ifBlank { "gemini" })
                        put("content", msg.content)
                        put("timestamp", validTimestamp)
                        put("triggeredByWakeWord", msg.triggeredByWakeWord)
                    }
                    jsonArray.put(obj)
                } catch (_: Throwable) {}
            }
            file.writeText(jsonArray.toString())
        } catch (e: Throwable) {
            Log.w(TAG, "Failed writing cache file ${file.name}", e)
        }
    }

    private fun readDiskCacheFile(chatId: Long): List<ChatMessage>? {
        return try {
            val incomingFile = getIncomingCacheFile(chatId)
            val outgoingFile = getOutgoingCacheFile(chatId)
            val legacyFile = File(cacheDir, "chat_cache_$chatId.json")

            val list = mutableListOf<ChatMessage>()
            if (incomingFile.exists()) {
                readMessagesFromFile(incomingFile)?.let { list.addAll(it) }
            }
            if (outgoingFile.exists()) {
                readMessagesFromFile(outgoingFile)?.let { list.addAll(it) }
            }

            // Backward compatibility with legacy combined cache file
            if (list.isEmpty() && legacyFile.exists()) {
                readMessagesFromFile(legacyFile)?.let { list.addAll(it) }
            }

            if (list.isNotEmpty()) list.sortedBy { it.timestamp } else null
        } catch (e: Throwable) {
            Log.w(TAG, "Failed reading disk cache for chat $chatId", e)
            null
        }
    }

    private fun readMessagesFromFile(file: File): List<ChatMessage>? {
        if (!file.exists()) return null
        return try {
            val jsonString = file.readText()
            if (jsonString.isBlank()) return emptyList()
            val jsonArray = JSONArray(jsonString)
            val list = mutableListOf<ChatMessage>()
            val fallbackNow = System.currentTimeMillis().coerceAtLeast(1L)
            for (i in 0 until jsonArray.length()) {
                try {
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val rawTimestamp = obj.optLong("timestamp", 0L)
                    val resolvedTimestamp = if (rawTimestamp > 0L) rawTimestamp else (fallbackNow + i)
                    val rawId = obj.optLong("id", 0L)
                    val resolvedId = if (rawId > 0L) rawId else (resolvedTimestamp + i)
                    val sender = obj.optString("sender", "gemini").ifBlank { "gemini" }
                    val content = obj.optString("content", "")
                    list.add(
                        ChatMessage(
                            id = resolvedId,
                            chatId = obj.optLong("chatId", 0L),
                            sender = sender,
                            content = content,
                            timestamp = resolvedTimestamp,
                            triggeredByWakeWord = obj.optBoolean("triggeredByWakeWord", false)
                        )
                    )
                } catch (_: Throwable) {
                    // Skip corrupted individual message object
                }
            }
            list
        } catch (e: Throwable) {
            Log.w(TAG, "Corrupted cache file ${file.name}, deleting to recover", e)
            try {
                file.delete()
            } catch (_: Throwable) {}
            null
        }
    }

    /**
     * Scans and removes any corrupted disk cache JSON files across all chats.
     */
    fun repairOrClearCorruptedDiskCaches(): Int {
        var removedCount = 0
        try {
            val files = cacheDir.listFiles() ?: return 0
            for (f in files) {
                if (!f.isFile || !f.name.endsWith(".json")) continue
                try {
                    val text = f.readText()
                    if (text.isNotBlank()) {
                        JSONArray(text)
                    }
                } catch (_: Throwable) {
                    try {
                        if (f.delete()) removedCount++
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
        return removedCount
    }

    /**
     * Clears incoming (AI / Gemini) messages disk cache buffer.
     */
    fun clearIncomingDiskCache(chatId: Long) {
        try {
            val file = getIncomingCacheFile(chatId)
            if (file.exists()) file.delete()
            synchronized(allCachedMessagesForCurrentChat) {
                allCachedMessagesForCurrentChat.removeAll { it.sender != "user" }
            }
            refreshHotWindowFromCache()
        } catch (_: Throwable) {}
    }

    /**
     * Clears outgoing (User queries) messages disk cache buffer.
     */
    fun clearOutgoingDiskCache(chatId: Long) {
        try {
            val file = getOutgoingCacheFile(chatId)
            if (file.exists()) file.delete()
            synchronized(allCachedMessagesForCurrentChat) {
                allCachedMessagesForCurrentChat.removeAll { it.sender == "user" }
            }
            refreshHotWindowFromCache()
        } catch (_: Throwable) {}
    }

    /**
     * Clears all disk buffers (both incoming and outgoing).
     */
    fun clearDiskCache(chatId: Long) {
        try {
            val inc = getIncomingCacheFile(chatId)
            if (inc.exists()) inc.delete()
            val out = getOutgoingCacheFile(chatId)
            if (out.exists()) out.delete()
            val legacy = File(cacheDir, "chat_cache_$chatId.json")
            if (legacy.exists()) legacy.delete()
        } catch (_: Throwable) {}

        hasExpandedOlderHistory = false
        updateOldestTimestamp(null)
        synchronized(allCachedMessagesForCurrentChat) {
            allCachedMessagesForCurrentChat.clear()
        }
        _hotMessages.value = emptyList()
        _hasMoreOlderMessages.value = false
        _bufferMetrics.value = BufferMetrics(
            hotMemoryCount = 0,
            totalDiskCount = 0,
            diskCacheSizeBytes = 0L,
            incomingDiskCount = 0,
            outgoingDiskCount = 0,
            incomingDiskSizeBytes = 0L,
            outgoingDiskSizeBytes = 0L,
            hotBufferSizeLimit = hotBufferSizeLimit,
            preloadStatus = "All Disk Caches Purged"
        )
    }

    /**
     * Clears the active in-memory hot RAM window ([_hotMessages]) while preserving staged disk caches
     * and persistent Room database history.
     */
    fun clear() {
        hasExpandedOlderHistory = false
        updateOldestTimestamp(null)
        _hotMessages.value = emptyList()
        _isLoadingOlder.value = false
        val totalDisk = synchronized(allCachedMessagesForCurrentChat) {
            allCachedMessagesForCurrentChat.size
        }
        _hasMoreOlderMessages.value = totalDisk > 0
        _bufferMetrics.value = _bufferMetrics.value.copy(
            hotMemoryCount = 0,
            hotBufferSizeLimit = hotBufferSizeLimit,
            preloadStatus = "Active RAM Window Cleared (0/$hotBufferSizeLimit in RAM)"
        )
    }

    /**
     * Purges all staged ephemeral disk cache JSON files across all chats and clears the active RAM window.
     */
    fun forceResetAndClear(): Int {
        var deletedFiles = 0
        try {
            val files = cacheDir.listFiles().orEmpty()
            for (f in files) {
                if (f.isFile && f.delete()) {
                    deletedFiles++
                }
            }
        } catch (_: Throwable) {}
        hasExpandedOlderHistory = false
        updateOldestTimestamp(null)
        synchronized(allCachedMessagesForCurrentChat) {
            allCachedMessagesForCurrentChat.clear()
        }
        _hotMessages.value = emptyList()
        _hasMoreOlderMessages.value = false
        _isLoadingOlder.value = false
        _bufferMetrics.value = BufferMetrics(
            hotMemoryCount = 0,
            totalDiskCount = 0,
            diskCacheSizeBytes = 0L,
            incomingDiskCount = 0,
            outgoingDiskCount = 0,
            incomingDiskSizeBytes = 0L,
            outgoingDiskSizeBytes = 0L,
            hotBufferSizeLimit = hotBufferSizeLimit,
            isPreloaded = false,
            preloadStatus = "Ephemeral Disk Cache Purged ($deletedFiles file(s) removed)"
        )
        return deletedFiles
    }
}
