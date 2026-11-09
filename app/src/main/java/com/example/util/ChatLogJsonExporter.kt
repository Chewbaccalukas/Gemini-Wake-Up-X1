package com.example.util

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.repository.ChatRepository
import com.example.ui.SessionStatisticsUiState
import com.example.ui.components.DailyWakeSessionBucket
import com.example.ui.components.computeLast7DaysWakeSessionBuckets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

enum class BackupExportFormat(
    val extension: String,
    val mimeType: String,
    val displayLabel: String
) {
    JSON(
        extension = "json",
        mimeType = "application/json",
        displayLabel = "JSON Backup (.json)"
    ),
    CSV(
        extension = "csv",
        mimeType = "text/csv",
        displayLabel = "CSV Spreadsheet (.csv)"
    )
}

/**
 * Metadata result returned when a temporary export file and its corresponding
 * [FileProvider] `content://` [Uri] are generated for sharing to external applications.
 */
data class GeneratedSharedExportFile(
    val file: File,
    val contentUri: Uri,
    val format: BackupExportFormat,
    val mimeType: String
)

/**
 * Exports chat interaction logs and usage statistics stored in the local Room database
 * (`chat_sessions`, `chat_messages`, and `voice_command_history`) into structured
 * JSON or standardized RFC-4180 CSV files (via [ChatSessionCsvTransformer]), generating
 * temporary shared file [Uri]s via [FileProvider] (`${applicationId}.fileprovider`) for
 * direct sharing to external applications as well as supporting direct Save-to-Device
 * via Storage Access Framework (`CreateDocument`).
 */
object ChatLogJsonExporter {

    private const val EXPORT_SUBDIR = "exports"
    private const val MIME_TYPE_JSON = "application/json"
    private const val MIME_TYPE_CSV = "text/csv"
    private const val TEMP_EXPORT_MAX_AGE_MS = 24L * 60L * 60L * 1000L // 24 hours

    fun fileProviderAuthority(context: Context): String = "${context.packageName}.fileprovider"

    /**
     * Cleans up stale temporary export files older than [maxAgeMs] in [outputDir]
     * so shared temporary files do not accumulate indefinitely in the app cache.
     */
    fun pruneExpiredTempExportFiles(
        outputDir: File,
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long = TEMP_EXPORT_MAX_AGE_MS
    ): Int {
        if (!outputDir.exists() || !outputDir.isDirectory) return 0
        var deletedCount = 0
        outputDir.listFiles()?.forEach { file ->
            try {
                if (file.isFile && (nowMs - file.lastModified()) > maxAgeMs) {
                    if (file.delete()) deletedCount++
                }
            } catch (_: Throwable) {
            }
        }
        return deletedCount
    }

    /**
     * Generates a temporary `content://` [Uri] for [exportedFile] using [FileProvider]
     * (`${applicationId}.fileprovider`). Falls back to `Uri.fromFile` only in local unit test
     * environments where no Android `ContentProvider` is registered.
     */
    fun generateTemporarySharedFileUri(
        context: Context,
        exportedFile: File
    ): Uri {
        val authority = fileProviderAuthority(context)
        return try {
            FileProvider.getUriForFile(context, authority, exportedFile)
        } catch (_: Throwable) {
            Uri.fromFile(exportedFile)
        }
    }

    private fun formatIsoTimestamp(epochMs: Long): String {
        return ChatSessionCsvTransformer.formatIsoTimestamp(epochMs)
    }

    /**
     * Serializes the provided Room [chats], [messages], [voiceCommands], and 7-day usage statistics
     * into a formatted JSON string. If [filterChatId] is non-null, only that chat session and its
     * associated interaction logs are included.
     */
    fun buildInteractionLogsJson(
        chats: List<ChatSession>,
        messages: List<ChatMessage>,
        voiceCommands: List<VoiceCommandHistoryEntry> = emptyList(),
        filterChatId: Long? = null,
        exportedAtMs: Long = System.currentTimeMillis(),
        statisticsState: SessionStatisticsUiState? = null
    ): String {
        val targetChats = if (filterChatId != null) {
            chats.filter { it.id == filterChatId && !it.isDeleted }
        } else {
            chats.filter { !it.isDeleted }
        }

        val chatById = targetChats.associateBy { it.id }

        val targetMessages = messages
            .filter { msg ->
                !msg.isDeleted && (filterChatId == null || msg.chatId == filterChatId)
            }
            .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })

        val targetChatTitles = targetChats.map { it.title.trim().lowercase(Locale.ROOT) }.toSet()
        val targetVoiceCommands = voiceCommands
            .filter { cmd ->
                filterChatId == null || (
                    !cmd.chatTitle.isNullOrBlank() &&
                        cmd.chatTitle.trim().lowercase(Locale.ROOT) in targetChatTitles
                    )
            }
            .sortedBy { it.timestamp }

        // Compute 7-day daily buckets & usage statistics
        val dailyBuckets: List<DailyWakeSessionBucket> = if (
            filterChatId == null &&
            statisticsState != null &&
            statisticsState.dailyBuckets.isNotEmpty()
        ) {
            statisticsState.dailyBuckets
        } else {
            computeLast7DaysWakeSessionBuckets(
                chats = targetChats,
                messages = targetMessages,
                voiceCommands = targetVoiceCommands,
                nowMillis = exportedAtMs
            )
        }
        val totalSessions7d = dailyBuckets.sumOf { it.totalSessions }
        val wakeSessions7d = dailyBuckets.sumOf { it.wakeTriggeredSessions }
        val wakeTriggerCount7d = dailyBuckets.sumOf { it.wakeTriggerCount }
        val wakeSharePercent7d = if (totalSessions7d > 0) {
            ((wakeSessions7d.toFloat() / totalSessions7d.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        } else {
            0
        }
        val allTimeWakeMessages = targetMessages.count { it.triggeredByWakeWord }
        val allTimeSessionVoiceCommands = targetChats.sumOf { it.totalVoiceCommands }
        val allTimeWakeTriggers = maxOf(
            allTimeWakeMessages,
            allTimeSessionVoiceCommands,
            targetVoiceCommands.size,
            statisticsState?.allTimeWakeTriggerCount ?: 0
        )
        val allTimeTrackedSeconds = targetChats.sumOf { it.totalTrackedSeconds }

        val dailyBucketsJsonArray = JSONArray()
        for (bucket in dailyBuckets) {
            val bObj = JSONObject().apply {
                put("dayLabel", bucket.dayLabel)
                put("dateLabel", bucket.dateLabel)
                put("startOfDayMillis", bucket.startOfDayMillis)
                put("endOfDayMillis", bucket.endOfDayMillis)
                put("wakeTriggeredSessions", bucket.wakeTriggeredSessions)
                put("totalSessions", bucket.totalSessions)
                put("wakeTriggerCount", bucket.wakeTriggerCount)
                put("totalMessagesCount", bucket.totalMessagesCount)
                put("isToday", bucket.isToday)
            }
            dailyBucketsJsonArray.put(bObj)
        }

        val usageStatisticsJson = JSONObject().apply {
            put("totalSessionsLast7Days", totalSessions7d)
            put("wakeTriggeredSessionsLast7Days", wakeSessions7d)
            put("wakeTriggerCountLast7Days", wakeTriggerCount7d)
            put("wakeSessionSharePercentLast7Days", wakeSharePercent7d)
            put("allTimeChatsCount", targetChats.size)
            put("allTimeMessagesCount", targetMessages.size)
            put("allTimeWakeWordMessagesCount", allTimeWakeMessages)
            put("allTimeWakeTriggerCount", allTimeWakeTriggers)
            put("allTimeTrackedSeconds", allTimeTrackedSeconds)
            put("dailyTrend7Days", dailyBucketsJsonArray)
        }

        val chatsJsonArray = JSONArray()
        for (chat in targetChats) {
            val chatMessages = targetMessages.filter { it.chatId == chat.id }
            val chatInteractionsArray = JSONArray()
            for (msg in chatMessages) {
                chatInteractionsArray.put(messageToJsonObject(msg, chat))
            }

            val chatObj = JSONObject().apply {
                put("chatId", chat.id)
                put("title", chat.title)
                put("wakeWord", chat.wakeWord)
                put("greetingMessage", chat.greetingMessage)
                put("systemInstruction", chat.systemInstruction)
                put("isDefaultWakeChat", chat.isDefaultWakeChat)
                put("isPersistent", chat.isPersistent)
                put("createdAt", chat.createdAt)
                put("createdAtIso", formatIsoTimestamp(chat.createdAt))
                put("lastUpdatedAt", chat.lastUpdatedAt)
                put("lastUpdatedAtIso", formatIsoTimestamp(chat.lastUpdatedAt))
                put("totalTrackedSeconds", chat.totalTrackedSeconds)
                put("totalVoiceCommands", chat.totalVoiceCommands)
                put("interactionCount", chatMessages.size)
                put("interactions", chatInteractionsArray)
                put("messages", chatInteractionsArray)
            }
            chatsJsonArray.put(chatObj)
        }

        val flatInteractionsArray = JSONArray()
        for (msg in targetMessages) {
            val parentChat = chatById[msg.chatId]
            flatInteractionsArray.put(messageToJsonObject(msg, parentChat))
        }

        val voiceCommandsArray = JSONArray()
        for (cmd in targetVoiceCommands) {
            val cmdObj = JSONObject().apply {
                put("id", cmd.id)
                put("timestamp", cmd.timestamp)
                put("timestampIso", formatIsoTimestamp(cmd.timestamp))
                put("commandType", cmd.commandType.name)
                put("triggerPhrase", cmd.triggerPhrase)
                put("recognizedTranscript", cmd.recognizedTranscript)
                put("chatTitle", cmd.chatTitle ?: JSONObject.NULL)
            }
            voiceCommandsArray.put(cmdObj)
        }

        val root = JSONObject().apply {
            put("schemaVersion", 2)
            put("exportedAt", exportedAtMs)
            put("exportedAtIso", formatIsoTimestamp(exportedAtMs))
            put("filterChatId", filterChatId ?: JSONObject.NULL)
            put("totalChats", targetChats.size)
            put("totalMessages", targetMessages.size)
            put("totalVoiceCommands", allTimeWakeTriggers)
            put("usageStatistics", usageStatisticsJson)
            put("chats", chatsJsonArray)
            put("interactions", flatInteractionsArray)
            put("messages", flatInteractionsArray)
            put("voiceCommands", voiceCommandsArray)
        }

        return root.toString(2)
    }

    /**
     * Delegates to [ChatSessionCsvTransformer.transformToCsv] to transform chat history
     * ([chats], [messages], [voiceCommands]) and [statisticsState] metrics from
     * `SessionStatisticsViewModel` into a standardized RFC-4180 single normalized CSV table
     * prefixed with a UTF-8 BOM (`\uFEFF`) for Excel and Google Sheets viewing.
     */
    fun buildInteractionLogsCsv(
        chats: List<ChatSession>,
        messages: List<ChatMessage>,
        voiceCommands: List<VoiceCommandHistoryEntry> = emptyList(),
        filterChatId: Long? = null,
        exportedAtMs: Long = System.currentTimeMillis(),
        statisticsState: SessionStatisticsUiState? = null,
        includeUtf8Bom: Boolean = true
    ): String {
        return ChatSessionCsvTransformer.transformToCsv(
            chats = chats,
            messages = messages,
            voiceCommands = voiceCommands,
            statisticsState = statisticsState,
            filterChatId = filterChatId,
            exportedAtMs = exportedAtMs,
            includeUtf8Bom = includeUtf8Bom
        )
    }

    private fun messageToJsonObject(msg: ChatMessage, parentChat: ChatSession?): JSONObject {
        val normalizedRole = if (msg.sender.equals("user", ignoreCase = true)) "user" else "model"
        return JSONObject().apply {
            put("id", msg.id)
            put("chatId", msg.chatId)
            put("chatTitle", parentChat?.title ?: "")
            put("wakeWord", parentChat?.wakeWord ?: "")
            put("sender", msg.sender)
            put("role", normalizedRole)
            put("content", msg.content)
            put("text", msg.content)
            put("timestamp", msg.timestamp)
            put("timestampIso", formatIsoTimestamp(msg.timestamp))
            put("labelLocalTime", ChatSessionCsvTransformer.formatLocalDateTime(msg.timestamp))
            put("triggeredByWakeWord", msg.triggeredByWakeWord)
            put("attachmentName", msg.attachmentName ?: JSONObject.NULL)
            put("attachmentMimeType", msg.attachmentMimeType ?: JSONObject.NULL)
        }
    }

    /**
     * Generates a timestamped suggested filename for SAF `CreateDocument` or temporary shared files.
     */
    fun buildSuggestedBackupFileName(
        format: BackupExportFormat,
        prefix: String = "gemini_wake_backup",
        timestampMs: Long = System.currentTimeMillis()
    ): String {
        val sanitizedPrefix = prefix
            .trim()
            .replace("[^a-zA-Z0-9_-]".toRegex(), "_")
            .trim('_')
            .ifBlank { "gemini_wake_backup" }
        val dateStamp = try {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestampMs))
        } catch (_: Throwable) {
            timestampMs.toString()
        }
        return "${sanitizedPrefix}_${dateStamp}.${format.extension}"
    }

    /**
     * Writes [jsonContent] to a temporary `.json` file inside [outputDir] (defaults to `cacheDir/exports`
     * which is mapped in `res/xml/file_paths.xml`).
     */
    fun writeLogsJsonToFile(
        context: Context,
        jsonContent: String,
        chatTitleOrPrefix: String = "chat_interaction_logs",
        outputDir: File = File(context.cacheDir, EXPORT_SUBDIR),
        timestampMs: Long = System.currentTimeMillis()
    ): File {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        pruneExpiredTempExportFiles(outputDir, nowMs = timestampMs)
        val fileName = buildSuggestedBackupFileName(
            format = BackupExportFormat.JSON,
            prefix = chatTitleOrPrefix,
            timestampMs = timestampMs
        )
        val jsonFile = File(outputDir, fileName)
        jsonFile.writeText(jsonContent, Charsets.UTF_8)
        return jsonFile
    }

    /**
     * Writes [csvContent] to a temporary `.csv` file inside [outputDir] (defaults to `cacheDir/exports`).
     */
    fun writeLogsCsvToFile(
        context: Context,
        csvContent: String,
        chatTitleOrPrefix: String = "chat_usage_backup",
        outputDir: File = File(context.cacheDir, EXPORT_SUBDIR),
        timestampMs: Long = System.currentTimeMillis()
    ): File {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }
        pruneExpiredTempExportFiles(outputDir, nowMs = timestampMs)
        val fileName = buildSuggestedBackupFileName(
            format = BackupExportFormat.CSV,
            prefix = chatTitleOrPrefix,
            timestampMs = timestampMs
        )
        val csvFile = File(outputDir, fileName)
        csvFile.writeText(csvContent, Charsets.UTF_8)
        return csvFile
    }

    /**
     * Queries the Room database via [chatRepository] and exports the chat interaction logs
     * and usage statistics to a `.json` file on disk.
     */
    suspend fun exportLogsToJsonFile(
        context: Context,
        chatRepository: ChatRepository,
        chatId: Long? = null,
        outputDir: File = File(context.cacheDir, EXPORT_SUBDIR),
        statisticsState: SessionStatisticsUiState? = null
    ): File = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val chats = if (chatId != null) {
            listOfNotNull(chatRepository.getChatByIdSync(chatId))
        } else {
            chatRepository.getAllChatsSync()
        }
        val messages = if (chatId != null) {
            chatRepository.getMessagesForChatSync(chatId)
        } else {
            chatRepository.getAllMessagesChronologicalSync()
        }
        val voiceCommands = chatRepository.getRecentVoiceCommandsSync(limit = 500)

        val jsonString = buildInteractionLogsJson(
            chats = chats,
            messages = messages,
            voiceCommands = voiceCommands,
            filterChatId = chatId,
            exportedAtMs = now,
            statisticsState = statisticsState
        )

        val prefix = if (chatId != null) {
            val title = chats.firstOrNull()?.title ?: "chat_$chatId"
            "${title}_interaction_logs"
        } else {
            "chat_interaction_logs"
        }

        writeLogsJsonToFile(
            context = context,
            jsonContent = jsonString,
            chatTitleOrPrefix = prefix,
            outputDir = outputDir,
            timestampMs = now
        )
    }

    /**
     * Queries the Room database via [chatRepository] and exports the chat history
     * and [SessionStatisticsUiState] metrics to a standardized `.csv` file on disk
     * via [ChatSessionCsvTransformer].
     */
    suspend fun exportLogsToCsvFile(
        context: Context,
        chatRepository: ChatRepository,
        chatId: Long? = null,
        outputDir: File = File(context.cacheDir, EXPORT_SUBDIR),
        statisticsState: SessionStatisticsUiState? = null
    ): File = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val chats = if (chatId != null) {
            listOfNotNull(chatRepository.getChatByIdSync(chatId))
        } else {
            chatRepository.getAllChatsSync()
        }
        val messages = if (chatId != null) {
            chatRepository.getMessagesForChatSync(chatId)
        } else {
            chatRepository.getAllMessagesChronologicalSync()
        }
        val voiceCommands = chatRepository.getRecentVoiceCommandsSync(limit = 500)

        val csvString = buildInteractionLogsCsv(
            chats = chats,
            messages = messages,
            voiceCommands = voiceCommands,
            filterChatId = chatId,
            exportedAtMs = now,
            statisticsState = statisticsState
        )

        val prefix = if (chatId != null) {
            val title = chats.firstOrNull()?.title ?: "chat_$chatId"
            "${title}_usage_backup"
        } else {
            "chat_usage_backup"
        }

        writeLogsCsvToFile(
            context = context,
            csvContent = csvString,
            chatTitleOrPrefix = prefix,
            outputDir = outputDir,
            timestampMs = now
        )
    }

    /**
     * Generates a temporary export file (`.json` or `.csv`) in `cacheDir/exports` and returns
     * a [GeneratedSharedExportFile] containing both the [File] and its temporary [FileProvider]
     * `content://` [Uri].
     */
    suspend fun generateTemporarySharedExport(
        context: Context,
        format: BackupExportFormat,
        chatRepository: ChatRepository,
        chatId: Long? = null,
        outputDir: File = File(context.cacheDir, EXPORT_SUBDIR),
        statisticsState: SessionStatisticsUiState? = null
    ): GeneratedSharedExportFile = withContext(Dispatchers.IO) {
        val exportedFile = when (format) {
            BackupExportFormat.JSON -> exportLogsToJsonFile(
                context = context,
                chatRepository = chatRepository,
                chatId = chatId,
                outputDir = outputDir,
                statisticsState = statisticsState
            )
            BackupExportFormat.CSV -> exportLogsToCsvFile(
                context = context,
                chatRepository = chatRepository,
                chatId = chatId,
                outputDir = outputDir,
                statisticsState = statisticsState
            )
        }
        val contentUri = generateTemporarySharedFileUri(context, exportedFile)
        GeneratedSharedExportFile(
            file = exportedFile,
            contentUri = contentUri,
            format = format,
            mimeType = format.mimeType
        )
    }

    /**
     * Writes the full JSON or standardized CSV backup payload directly to a user-selected SAF [Uri].
     */
    suspend fun writeBackupToDocumentUri(
        context: Context,
        targetUri: Uri,
        format: BackupExportFormat,
        chatRepository: ChatRepository,
        chatId: Long? = null,
        statisticsState: SessionStatisticsUiState? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val chats = if (chatId != null) {
            listOfNotNull(chatRepository.getChatByIdSync(chatId))
        } else {
            chatRepository.getAllChatsSync()
        }
        val messages = if (chatId != null) {
            chatRepository.getMessagesForChatSync(chatId)
        } else {
            chatRepository.getAllMessagesChronologicalSync()
        }
        val voiceCommands = chatRepository.getRecentVoiceCommandsSync(limit = 500)

        val payload = when (format) {
            BackupExportFormat.JSON -> buildInteractionLogsJson(
                chats = chats,
                messages = messages,
                voiceCommands = voiceCommands,
                filterChatId = chatId,
                exportedAtMs = now,
                statisticsState = statisticsState
            )
            BackupExportFormat.CSV -> buildInteractionLogsCsv(
                chats = chats,
                messages = messages,
                voiceCommands = voiceCommands,
                filterChatId = chatId,
                exportedAtMs = now,
                statisticsState = statisticsState
            )
        }

        context.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
            outputStream.write(payload.toByteArray(Charsets.UTF_8))
            outputStream.flush()
            true
        } ?: false
    }

    /**
     * Resolves a shareable [Uri] for [jsonFile] using [FileProvider] and constructs a standard
     * Android `Intent.ACTION_SEND` chooser intent with `FLAG_GRANT_READ_URI_PERMISSION`.
     */
    fun createShareJsonFileIntent(
        context: Context,
        jsonFile: File,
        chooserTitle: String = "Share Chat Interaction Logs (JSON)"
    ): Intent {
        return createShareBackupFileIntent(
            context = context,
            backupFile = jsonFile,
            mimeType = MIME_TYPE_JSON,
            chooserTitle = chooserTitle
        )
    }

    /**
     * Resolves a shareable [Uri] for [csvFile] using [FileProvider] and constructs a standard
     * Android `Intent.ACTION_SEND` chooser intent with `FLAG_GRANT_READ_URI_PERMISSION`.
     */
    fun createShareCsvFileIntent(
        context: Context,
        csvFile: File,
        chooserTitle: String = "Share Chat & Usage Backup (CSV)"
    ): Intent {
        return createShareBackupFileIntent(
            context = context,
            backupFile = csvFile,
            mimeType = MIME_TYPE_CSV,
            chooserTitle = chooserTitle
        )
    }

    private fun createShareBackupFileIntent(
        context: Context,
        backupFile: File,
        mimeType: String,
        chooserTitle: String
    ): Intent {
        val contentUri: Uri = generateTemporarySharedFileUri(context, backupFile)

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "Gemini Wake Backup & Usage Statistics (${backupFile.name})")
            putExtra(
                Intent.EXTRA_TEXT,
                "Exported Gemini Wake chat history and usage statistics (${backupFile.name})."
            )
            clipData = ClipData.newRawUri(backupFile.name, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Proactively grant temporary read URI permission to all candidate receiver activities
        try {
            val resInfoList = context.packageManager.queryIntentActivities(
                sendIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
            for (resolveInfo in resInfoList) {
                val packageName = resolveInfo.activityInfo?.packageName ?: continue
                context.grantUriPermission(
                    packageName,
                    contentUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        } catch (_: Throwable) {
        }

        return Intent.createChooser(sendIntent, chooserTitle).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    /**
     * Exports the chat interaction logs and usage statistics from Room into a temporary JSON file,
     * generates a [FileProvider] URI, and immediately launches the Android share sheet.
     */
    suspend fun exportAndShareChatInteractionLogs(
        context: Context,
        chatRepository: ChatRepository,
        chatId: Long? = null
    ): Intent {
        val jsonFile = exportLogsToJsonFile(
            context = context,
            chatRepository = chatRepository,
            chatId = chatId
        )
        val chooserIntent = createShareJsonFileIntent(context, jsonFile)
        withContext(Dispatchers.Main) {
            context.startActivity(chooserIntent)
        }
        return chooserIntent
    }

    /**
     * Exports the chat history and usage statistics from Room into a temporary CSV file,
     * generates a [FileProvider] URI, and immediately launches the Android share sheet.
     */
    suspend fun exportAndShareChatLogsCsv(
        context: Context,
        chatRepository: ChatRepository,
        chatId: Long? = null
    ): Intent {
        val csvFile = exportLogsToCsvFile(
            context = context,
            chatRepository = chatRepository,
            chatId = chatId
        )
        val chooserIntent = createShareCsvFileIntent(context, csvFile)
        withContext(Dispatchers.Main) {
            context.startActivity(chooserIntent)
        }
        return chooserIntent
    }
}
