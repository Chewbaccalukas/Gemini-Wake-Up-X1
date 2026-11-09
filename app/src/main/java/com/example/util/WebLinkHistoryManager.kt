package com.example.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.UUID

enum class WebLinkSourceType(val displayLabel: String) {
    WEB_SEARCH("Web Search"),
    CHAT_LINK("Gemini Chat Link"),
    EXTERNAL_LINK("Opened Link")
}

enum class VoiceCommandType(val displayLabel: String) {
    WAKE_WORD("Wake Word"),
    DICTATE("Voice Dictation"),
    VOICE_SEND("Voice Send"),
    VOICE_TURN("Voice Turn"),
    HEARTBEAT("Voice Heartbeat"),
    SLEEP("Sleep Mode"),
    WAKE_UP("Wake Up")
}

data class WebLinkHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val titleOrQuery: String,
    val url: String,
    val sourceType: WebLinkSourceType,
    val chatTitle: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class VoiceCommandHistoryEntry(
    val id: String = UUID.randomUUID().toString(),
    val commandType: VoiceCommandType,
    val triggerPhrase: String,
    val recognizedTranscript: String,
    val chatTitle: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

data class ExtractedChatLink(
    val label: String,
    val url: String,
    val domain: String,
    val startIndex: Int,
    val endIndex: Int
)

/**
 * Manages persistent Web Search, Chat Link History, and Voice Command Transcripts,
 * and handles opening URLs in the device's external default browser without triggering self-intent loops.
 */
class WebLinkHistoryManager(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val ioScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    private val _historyEntries = MutableStateFlow<List<WebLinkHistoryEntry>>(loadFromPrefs())
    val historyEntries: StateFlow<List<WebLinkHistoryEntry>> = _historyEntries.asStateFlow()

    private val _voiceCommandEntries = MutableStateFlow<List<VoiceCommandHistoryEntry>>(loadVoiceCommandsFromPrefs())
    val voiceCommandEntries: StateFlow<List<VoiceCommandHistoryEntry>> = _voiceCommandEntries.asStateFlow()

    init {
        ioScope.launch {
            try {
                val db = com.example.data.db.AppDatabase.getDatabase(appContext)
                val initialPrefs = _voiceCommandEntries.value
                if (initialPrefs.isNotEmpty()) {
                    db.chatDao().insertVoiceCommands(
                        initialPrefs.take(MAX_HISTORY_ITEMS).map {
                            com.example.data.model.VoiceCommandEntity.fromHistoryEntry(it)
                        }
                    )
                }
                db.chatDao().getRecentVoiceCommands().collect { roomEntities ->
                    val currentPrefs = loadVoiceCommandsFromPrefs()
                    if (currentPrefs.isEmpty() && _voiceCommandEntries.value.isEmpty()) {
                        // History was explicitly cleared; do not repopulate from stale async DB rows
                        return@collect
                    }
                    if (roomEntities.isNotEmpty() && _voiceCommandEntries.value.isEmpty()) {
                        val mapped = roomEntities
                            .filter { it.hasValidTranscript || !com.example.data.model.ChatMessage.isLegacyAudioPlaceholder(it.triggerPhrase) }
                            .take(MAX_HISTORY_ITEMS)
                            .map { it.toHistoryEntry() }
                        _voiceCommandEntries.value = mapped
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    fun recordSearchOrUrl(rawInput: String): WebLinkHistoryEntry? {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return null

        val isUrl = isDirectUrlOrDomain(trimmed)
        val finalUrl = normalizeInputToUrl(trimmed)
        val entry = WebLinkHistoryEntry(
            titleOrQuery = trimmed,
            url = finalUrl,
            sourceType = if (isUrl) WebLinkSourceType.EXTERNAL_LINK else WebLinkSourceType.WEB_SEARCH,
            chatTitle = null,
            timestamp = System.currentTimeMillis()
        )
        upsertEntry(entry)
        return entry
    }

    fun recordChatLink(
        rawUrl: String,
        label: String? = null,
        chatTitle: String? = null
    ): WebLinkHistoryEntry? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return null

        val normalizedUrl = normalizeDirectUrl(trimmed)
        val displayTitle = when {
            !label.isNullOrBlank() && label.trim() != normalizedUrl -> label.trim()
            else -> extractDomain(normalizedUrl).ifBlank { normalizedUrl }
        }
        val entry = WebLinkHistoryEntry(
            titleOrQuery = displayTitle,
            url = normalizedUrl,
            sourceType = WebLinkSourceType.CHAT_LINK,
            chatTitle = chatTitle,
            timestamp = System.currentTimeMillis()
        )
        upsertEntry(entry)
        return entry
    }

    fun recordIncomingExternalLink(rawUrl: String): WebLinkHistoryEntry? {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return null
        val normalizedUrl = normalizeDirectUrl(trimmed)
        val entry = WebLinkHistoryEntry(
            titleOrQuery = extractDomain(normalizedUrl).ifBlank { normalizedUrl },
            url = normalizedUrl,
            sourceType = WebLinkSourceType.EXTERNAL_LINK,
            chatTitle = null,
            timestamp = System.currentTimeMillis()
        )
        upsertEntry(entry)
        return entry
    }

    fun deleteEntry(id: String) {
        try {
            val updated = _historyEntries.value.filterNot { it.id == id }
            _historyEntries.value = updated
            saveToPrefs(updated)
        } catch (_: Throwable) {}
    }

    fun clearAllHistory() {
        try {
            _historyEntries.value = emptyList()
            prefs.edit().remove(KEY_HISTORY_JSON).apply()
        } catch (_: Throwable) {}
    }

    fun recordVoiceCommand(
        commandType: VoiceCommandType,
        triggerPhrase: String,
        recognizedTranscript: String,
        chatTitle: String? = null
    ): VoiceCommandHistoryEntry? {
        return try {
            val cleanTrigger = triggerPhrase.trim()
                .takeUnless { com.example.data.model.ChatMessage.isLegacyAudioPlaceholder(it) }
                .orEmpty()
            val cleanTranscript = recognizedTranscript.trim()
                .takeUnless { com.example.data.model.ChatMessage.isLegacyAudioPlaceholder(it) }
                .orEmpty()
            if (cleanTrigger.isBlank() && cleanTranscript.isBlank()) return null

            val resolvedTrigger = cleanTrigger.ifBlank { commandType.displayLabel }
            val resolvedTranscript = cleanTranscript.ifBlank {
                "Wake phrase \"$resolvedTrigger\" recognized"
            }
            val latestTs = _voiceCommandEntries.value.firstOrNull()?.timestamp ?: 0L
            val now = maxOf(System.currentTimeMillis().coerceAtLeast(1L), latestTs + 1L)

            val entry = VoiceCommandHistoryEntry(
                id = UUID.randomUUID().toString(),
                commandType = commandType,
                triggerPhrase = resolvedTrigger,
                recognizedTranscript = resolvedTranscript,
                chatTitle = chatTitle?.takeIf { it.isNotBlank() },
                timestamp = now
            )
            upsertVoiceCommandEntry(entry)
            ioScope.launch {
                try {
                    val db = com.example.data.db.AppDatabase.getDatabase(appContext)
                    db.chatDao().insertVoiceCommand(
                        com.example.data.model.VoiceCommandEntity.fromHistoryEntry(entry)
                    )
                } catch (_: Throwable) {}
            }
            entry
        } catch (_: Throwable) {
            null
        }
    }

    fun deleteVoiceCommandEntry(id: String) {
        try {
            val updated = _voiceCommandEntries.value.filterNot { it.id == id }
            _voiceCommandEntries.value = updated
            saveVoiceCommandsToPrefs(updated)
            ioScope.launch {
                try {
                    val db = com.example.data.db.AppDatabase.getDatabase(appContext)
                    db.chatDao().deleteVoiceCommandById(id)
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    fun clearAllVoiceCommandHistory() {
        try {
            _voiceCommandEntries.value = emptyList()
            prefs.edit().remove(KEY_VOICE_COMMAND_HISTORY_JSON).apply()
            ioScope.launch {
                try {
                    val db = com.example.data.db.AppDatabase.getDatabase(appContext)
                    db.chatDao().clearAllVoiceCommands()
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    /**
     * Recovery action to purge any unparseable or corrupted history entries from SharedPreferences
     * and reload sanitized lists in memory. Returns the number of corrupted items removed or repaired.
     */
    fun repairOrClearCorruptedHistory(): Int {
        var repairedCount = 0
        try {
            val rawWeb = prefs.getString(KEY_HISTORY_JSON, null)
            if (rawWeb != null) {
                val validWeb = loadFromPrefs()
                if (validWeb.isEmpty() && rawWeb.isNotBlank() && rawWeb != "[]") {
                    repairedCount++
                    prefs.edit().remove(KEY_HISTORY_JSON).apply()
                } else {
                    saveToPrefs(validWeb)
                }
                _historyEntries.value = validWeb
            }
        } catch (_: Throwable) {
            repairedCount++
            try {
                prefs.edit().remove(KEY_HISTORY_JSON).apply()
            } catch (_: Throwable) {}
            _historyEntries.value = emptyList()
        }

        try {
            val rawVoice = prefs.getString(KEY_VOICE_COMMAND_HISTORY_JSON, null)
            if (rawVoice != null) {
                val validVoice = loadVoiceCommandsFromPrefs()
                if (validVoice.isEmpty() && rawVoice.isNotBlank() && rawVoice != "[]") {
                    repairedCount++
                    prefs.edit().remove(KEY_VOICE_COMMAND_HISTORY_JSON).apply()
                } else {
                    saveVoiceCommandsToPrefs(validVoice)
                }
                _voiceCommandEntries.value = validVoice
            }
        } catch (_: Throwable) {
            repairedCount++
            try {
                prefs.edit().remove(KEY_VOICE_COMMAND_HISTORY_JSON).apply()
            } catch (_: Throwable) {}
            _voiceCommandEntries.value = emptyList()
        }
        return repairedCount
    }

    private fun upsertVoiceCommandEntry(newEntry: VoiceCommandHistoryEntry) {
        try {
            val current = _voiceCommandEntries.value.toMutableList()
            current.removeAll { it.id == newEntry.id }
            current.add(0, newEntry)
            val capped = current.take(MAX_HISTORY_ITEMS)
            _voiceCommandEntries.value = capped
            saveVoiceCommandsToPrefs(capped)
        } catch (_: Throwable) {}
    }

    private fun loadVoiceCommandsFromPrefs(): List<VoiceCommandHistoryEntry> {
        val raw = try {
            prefs.getString(KEY_VOICE_COMMAND_HISTORY_JSON, null)
        } catch (_: Throwable) {
            null
        } ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<VoiceCommandHistoryEntry>()
            val seenIds = HashSet<String>()
            val fallbackNow = System.currentTimeMillis().coerceAtLeast(1L)
            for (i in 0 until array.length()) {
                try {
                    val obj = array.optJSONObject(i) ?: continue
                    val rawId = obj.optString("id", "").trim()
                    val id = if (rawId.isNotBlank() && seenIds.add(rawId)) {
                        rawId
                    } else {
                        val generated = "${UUID.randomUUID()}_$i"
                        seenIds.add(generated)
                        generated
                    }
                    val typeStr = obj.optString("commandType", VoiceCommandType.WAKE_WORD.name)
                    val commandType = try {
                        VoiceCommandType.valueOf(typeStr)
                    } catch (_: Throwable) {
                        VoiceCommandType.WAKE_WORD
                    }
                    val rawTrigger = obj.optString("triggerPhrase", commandType.displayLabel).trim()
                    val triggerPhrase = rawTrigger
                        .takeUnless { com.example.data.model.ChatMessage.isLegacyAudioPlaceholder(it) }
                        ?.ifBlank { commandType.displayLabel }
                        ?: commandType.displayLabel
                    val rawTranscript = obj.optString("recognizedTranscript", "").trim()
                    val recognizedTranscript = rawTranscript
                        .takeUnless { com.example.data.model.ChatMessage.isLegacyAudioPlaceholder(it) }
                        .orEmpty()
                    val chatTitle = obj.optString("chatTitle", "").trim().takeIf { it.isNotBlank() }
                    val rawTimestamp = obj.optLong("timestamp", fallbackNow)
                    val timestamp = if (rawTimestamp > 0L) rawTimestamp else fallbackNow
                    if (recognizedTranscript.isNotBlank() || !com.example.data.model.ChatMessage.isLegacyAudioPlaceholder(rawTrigger)) {
                        list.add(
                            VoiceCommandHistoryEntry(
                                id = id,
                                commandType = commandType,
                                triggerPhrase = triggerPhrase,
                                recognizedTranscript = recognizedTranscript.ifBlank { triggerPhrase },
                                chatTitle = chatTitle,
                                timestamp = timestamp
                            )
                        )
                    }
                } catch (_: Throwable) {
                    // Ignore individual corrupted entry and continue loading valid entries
                }
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun saveVoiceCommandsToPrefs(entries: List<VoiceCommandHistoryEntry>) {
        try {
            val array = JSONArray()
            val fallbackNow = System.currentTimeMillis().coerceAtLeast(1L)
            entries.forEachIndexed { index, entry ->
                try {
                    val obj = JSONObject().apply {
                        put("id", entry.id.ifBlank { "${UUID.randomUUID()}_$index" })
                        put("commandType", entry.commandType.name)
                        put("triggerPhrase", entry.triggerPhrase.ifBlank { entry.commandType.displayLabel })
                        put("recognizedTranscript", entry.recognizedTranscript.ifBlank { entry.triggerPhrase })
                        if (!entry.chatTitle.isNullOrBlank()) {
                            put("chatTitle", entry.chatTitle)
                        }
                        put("timestamp", if (entry.timestamp > 0L) entry.timestamp else fallbackNow)
                    }
                    array.put(obj)
                } catch (_: Throwable) {}
            }
            prefs.edit().putString(KEY_VOICE_COMMAND_HISTORY_JSON, array.toString()).apply()
        } catch (_: Throwable) {
        }
    }

    private fun upsertEntry(newEntry: WebLinkHistoryEntry) {
        try {
            val current = _historyEntries.value.toMutableList()
            // Remove duplicate URL + sourceType or duplicate ID to bubble most recent to top
            current.removeAll {
                it.id == newEntry.id ||
                    (it.url.equals(newEntry.url, ignoreCase = true) &&
                        it.titleOrQuery.equals(newEntry.titleOrQuery, ignoreCase = true))
            }
            current.add(0, newEntry)
            val capped = current.take(MAX_HISTORY_ITEMS)
            _historyEntries.value = capped
            saveToPrefs(capped)
        } catch (_: Throwable) {}
    }

    private fun loadFromPrefs(): List<WebLinkHistoryEntry> {
        val raw = try {
            prefs.getString(KEY_HISTORY_JSON, null)
        } catch (_: Throwable) {
            null
        } ?: return emptyList()
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            val list = mutableListOf<WebLinkHistoryEntry>()
            val seenIds = HashSet<String>()
            val fallbackNow = System.currentTimeMillis().coerceAtLeast(1L)
            for (i in 0 until array.length()) {
                try {
                    val obj = array.optJSONObject(i) ?: continue
                    val rawId = obj.optString("id", "").trim()
                    val id = if (rawId.isNotBlank() && seenIds.add(rawId)) {
                        rawId
                    } else {
                        val generated = "${UUID.randomUUID()}_$i"
                        seenIds.add(generated)
                        generated
                    }
                    val titleOrQuery = obj.optString("titleOrQuery", "").trim()
                    val url = obj.optString("url", "").trim()
                    val sourceTypeStr = obj.optString("sourceType", WebLinkSourceType.WEB_SEARCH.name)
                    val sourceType = try {
                        WebLinkSourceType.valueOf(sourceTypeStr)
                    } catch (_: Throwable) {
                        WebLinkSourceType.WEB_SEARCH
                    }
                    val chatTitle = obj.optString("chatTitle", "").trim().takeIf { it.isNotBlank() }
                    val rawTimestamp = obj.optLong("timestamp", fallbackNow)
                    val timestamp = if (rawTimestamp > 0L) rawTimestamp else fallbackNow
                    if (url.isNotBlank()) {
                        list.add(
                            WebLinkHistoryEntry(
                                id = id,
                                titleOrQuery = titleOrQuery.ifBlank { url },
                                url = url,
                                sourceType = sourceType,
                                chatTitle = chatTitle,
                                timestamp = timestamp
                            )
                        )
                    }
                } catch (_: Throwable) {
                    // Ignore individual corrupted entry
                }
            }
            list
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun saveToPrefs(entries: List<WebLinkHistoryEntry>) {
        try {
            val array = JSONArray()
            val fallbackNow = System.currentTimeMillis().coerceAtLeast(1L)
            entries.forEachIndexed { index, entry ->
                try {
                    val obj = JSONObject().apply {
                        put("id", entry.id.ifBlank { "${UUID.randomUUID()}_$index" })
                        put("titleOrQuery", entry.titleOrQuery.ifBlank { entry.url })
                        put("url", entry.url)
                        put("sourceType", entry.sourceType.name)
                        if (!entry.chatTitle.isNullOrBlank()) {
                            put("chatTitle", entry.chatTitle)
                        }
                        put("timestamp", if (entry.timestamp > 0L) entry.timestamp else fallbackNow)
                    }
                    array.put(obj)
                } catch (_: Throwable) {}
            }
            prefs.edit().putString(KEY_HISTORY_JSON, array.toString()).apply()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val PREFS_NAME = "gemini_web_link_history_prefs"
        private const val KEY_HISTORY_JSON = "web_link_history_json"
        private const val KEY_VOICE_COMMAND_HISTORY_JSON = "voice_command_history_json"
        private const val MAX_HISTORY_ITEMS = 100

        private val MARKDOWN_LINK_REGEX = Regex("""\[([^\]]+)]\((https?://[^\s)]+|www\.[^\s)]+)\)""")
        private val RAW_URL_REGEX = Regex("""(?i)\b((?:https?://|www\.)[^\s<>()"'`]+)""")
        private val DOMAIN_PATTERN = Regex("""^(?:https?://)?(?:www\.)?[a-zA-Z0-9-]+(?:\.[a-zA-Z]{2,})+(?:/[^\s]*)?$""")

        fun isDirectUrlOrDomain(input: String): Boolean {
            val trimmed = input.trim()
            if (trimmed.contains(' ')) return false
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("www.", ignoreCase = true)
            ) {
                return true
            }
            return DOMAIN_PATTERN.matches(trimmed)
        }

        fun normalizeDirectUrl(rawUrl: String): String {
            val trimmed = rawUrl.trim().trimEnd('.', ',', ';', ')', ']', '>')
            return when {
                trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                else -> "https://$trimmed"
            }
        }

        fun normalizeInputToUrl(input: String): String {
            val trimmed = input.trim()
            if (isDirectUrlOrDomain(trimmed)) {
                return normalizeDirectUrl(trimmed)
            }
            val encoded = try {
                URLEncoder.encode(trimmed, "UTF-8")
            } catch (_: Exception) {
                trimmed.replace(" ", "+")
            }
            return "https://www.google.com/search?q=$encoded"
        }

        fun extractDomain(url: String): String {
            return try {
                val normalized = normalizeDirectUrl(url)
                val uri = Uri.parse(normalized)
                uri.host?.removePrefix("www.") ?: normalized
            } catch (_: Exception) {
                url
            }
        }

        /**
         * Extracts both Markdown [title](url) links and standalone http/https/www URLs
         * from a Gemini chat message turn.
         */
        fun extractLinksFromText(text: String): List<ExtractedChatLink> {
            if (text.isBlank()) return emptyList()
            val results = mutableListOf<ExtractedChatLink>()
            val occupiedRanges = mutableListOf<IntRange>()

            MARKDOWN_LINK_REGEX.findAll(text).forEach { match ->
                val label = match.groupValues[1].trim()
                val rawUrl = match.groupValues[2].trim()
                val normalizedUrl = normalizeDirectUrl(rawUrl)
                val range = match.range
                occupiedRanges.add(range)
                results.add(
                    ExtractedChatLink(
                        label = label.ifBlank { extractDomain(normalizedUrl) },
                        url = normalizedUrl,
                        domain = extractDomain(normalizedUrl),
                        startIndex = range.first,
                        endIndex = range.last + 1
                    )
                )
            }

            RAW_URL_REGEX.findAll(text).forEach { match ->
                val range = match.range
                val overlapsMarkdown = occupiedRanges.any {
                    range.first <= it.last && range.last >= it.first
                }
                if (!overlapsMarkdown) {
                    val rawUrl = match.value.trimEnd('.', ',', ';', ')', ']', '>')
                    val normalizedUrl = normalizeDirectUrl(rawUrl)
                    results.add(
                        ExtractedChatLink(
                            label = extractDomain(normalizedUrl),
                            url = normalizedUrl,
                            domain = extractDomain(normalizedUrl),
                            startIndex = range.first,
                            endIndex = range.first + rawUrl.length
                        )
                    )
                }
            }

            return results.sortedBy { it.startIndex }.distinctBy { it.url }
        }

        /**
         * Passively stages a normalized URL payload in [com.example.coupler.ChromeBridgeSocketManager]
         * without ever creating an external browser `Intent(Intent.ACTION_VIEW)` or calling `startActivity()`.
         */
        fun openUrlInDefaultBrowser(context: Context, rawUrlOrQuery: String): Boolean {
            val normalizedUrl = normalizeInputToUrl(rawUrlOrQuery)
            if (normalizedUrl.isBlank()) return false
            com.example.coupler.ChromeBridgeSocketManager.stageUrlPayload(context, normalizedUrl)
            return true
        }
    }
}
