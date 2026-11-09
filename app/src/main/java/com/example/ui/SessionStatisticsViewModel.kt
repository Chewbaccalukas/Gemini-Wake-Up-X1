package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.repository.ChatRepository
import com.example.ui.components.DailyWakeSessionBucket
import com.example.ui.components.StatisticsDateRange
import com.example.ui.components.WeeklyHeatmapCell
import com.example.ui.components.computeAllTimeWakeSessionBuckets
import com.example.ui.components.computeLast30DaysWakeSessionBuckets
import com.example.ui.components.computeLast7DaysWakeSessionBuckets
import com.example.ui.components.computeWeeklyActivityHeatmap
import com.example.util.AppSettingsManager
import com.example.util.BackupExportFormat
import com.example.util.ChatLogJsonExporter
import com.example.util.VoiceCommandHistoryEntry
import com.example.util.WebLinkHistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Immutable UI state emitted by [SessionStatisticsViewModel] representing the 7-day,
 * 30-day, and all-time trends of wake-word-triggered sessions vs. total sessions,
 * weekly activity heatmap (7 days × 4 time blocks), and cumulative usage telemetry.
 */
data class SessionStatisticsUiState(
    val selectedDateRange: StatisticsDateRange = StatisticsDateRange.LAST_7_DAYS,
    val activeBuckets: List<DailyWakeSessionBucket> = emptyList(),
    val dailyBuckets: List<DailyWakeSessionBucket> = emptyList(),
    val buckets30d: List<DailyWakeSessionBucket> = emptyList(),
    val bucketsAllTime: List<DailyWakeSessionBucket> = emptyList(),
    val weeklyHeatmapCells: List<WeeklyHeatmapCell> = emptyList(),
    val peakHeatmapSlotLabel: String = "No peak slot yet",
    val totalSessions7d: Int = 0,
    val wakeTriggeredSessions7d: Int = 0,
    val wakeTriggerCount7d: Int = 0,
    val wakeSessionPercentage7d: Int = 0,
    val totalSessions30d: Int = 0,
    val wakeTriggeredSessions30d: Int = 0,
    val wakeTriggerCount30d: Int = 0,
    val wakeSessionPercentage30d: Int = 0,
    val allTimeSessionsCount: Int = 0,
    val allTimeWakeTriggeredSessionsCount: Int = 0,
    val allTimeWakeSessionPercentage: Int = 0,
    val allTimeMessagesCount: Int = 0,
    val allTimeWakeTriggerCount: Int = 0,
    val allTimeTrackedSeconds: Long = 0L,
    val activeDefaultWakeChat: ChatSession? = null,
    val availableChats: List<ChatSession> = emptyList(),
    val isLoading: Boolean = true,
    val isExporting: Boolean = false,
    val lastExportMessage: String? = null,
    val lastSharedFileUri: String? = null
)

/**
 * Dedicated ViewModel that aggregates wake-word trigger counts and wake-word-triggered sessions
 * versus total session counts across **Last 7 Days**, **Last 30 Days**, and **All-Time** from
 * the local Room database (`chat_sessions` and `chat_messages`), providing reactive data to the
 * Recharts-styled trend bar chart dashboard component, the 7×4 Weekly Activity Heatmap, and
 * orchestrating JSON / CSV backups and temporary [androidx.core.content.FileProvider] URIs.
 */
class SessionStatisticsViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val chatDao = database.chatDao()
    private val messageDao = database.messageDao()
    private val repository = ChatRepository(chatDao = chatDao, messageDao = messageDao)
    private val webLinkHistoryManager = WebLinkHistoryManager(application)

    private val _selectedDateRange = MutableStateFlow(StatisticsDateRange.LAST_7_DAYS)
    val selectedDateRange: StateFlow<StatisticsDateRange> = _selectedDateRange.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _lastExportMessage = MutableStateFlow<String?>(null)
    val lastExportMessage: StateFlow<String?> = _lastExportMessage.asStateFlow()

    private val _lastSharedFileUri = MutableStateFlow<String?>(null)
    val lastSharedFileUri: StateFlow<String?> = _lastSharedFileUri.asStateFlow()

    private val _pendingExportFormat = MutableStateFlow(BackupExportFormat.CSV)
    val pendingExportFormat: StateFlow<BackupExportFormat> = _pendingExportFormat.asStateFlow()

    val uiState: StateFlow<SessionStatisticsUiState> = combine(
        chatDao.observeAllChats(),
        messageDao.observeActiveMessagesSince(0L),
        messageDao.observeTotalActiveMessageCount(),
        messageDao.observeTotalWakeWordMessageCount(),
        webLinkHistoryManager.voiceCommandEntries
    ) { chats, allActiveMessages, totalMsgCount, totalWakeMsgCount, voiceCommands ->
        buildStatisticsState(
            chats = chats,
            allMessages = allActiveMessages,
            totalMsgCount = totalMsgCount,
            totalWakeMsgCount = totalWakeMsgCount,
            voiceCommands = voiceCommands,
            selectedRange = _selectedDateRange.value,
            isExporting = _isExporting.value,
            lastExportMessage = _lastExportMessage.value,
            lastSharedFileUri = _lastSharedFileUri.value
        )
    }
        .combine(_selectedDateRange) { state, selectedRange ->
            val resolvedActive = when (selectedRange) {
                StatisticsDateRange.LAST_7_DAYS -> state.dailyBuckets
                StatisticsDateRange.LAST_30_DAYS -> state.buckets30d
                StatisticsDateRange.ALL_TIME -> state.bucketsAllTime
            }
            state.copy(
                selectedDateRange = selectedRange,
                activeBuckets = resolvedActive
            )
        }
        .combine(_isExporting) { state, exporting ->
            state.copy(isExporting = exporting)
        }
        .combine(_lastExportMessage) { state, exportMsg ->
            state.copy(lastExportMessage = exportMsg)
        }
        .combine(_lastSharedFileUri) { state, sharedUri ->
            state.copy(lastSharedFileUri = sharedUri)
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SessionStatisticsUiState(
                selectedDateRange = StatisticsDateRange.LAST_7_DAYS,
                activeBuckets = computeLast7DaysWakeSessionBuckets(emptyList()),
                dailyBuckets = computeLast7DaysWakeSessionBuckets(emptyList()),
                buckets30d = computeLast30DaysWakeSessionBuckets(emptyList()),
                bucketsAllTime = computeAllTimeWakeSessionBuckets(emptyList()),
                weeklyHeatmapCells = computeWeeklyActivityHeatmap(emptyList(), emptyList()),
                isLoading = true
            )
        )

    fun selectDateRange(range: StatisticsDateRange) {
        _selectedDateRange.value = range
    }

    private fun buildStatisticsState(
        chats: List<ChatSession>,
        allMessages: List<ChatMessage>,
        totalMsgCount: Int,
        totalWakeMsgCount: Int,
        voiceCommands: List<VoiceCommandHistoryEntry>,
        selectedRange: StatisticsDateRange,
        isExporting: Boolean,
        lastExportMessage: String?,
        lastSharedFileUri: String?
    ): SessionStatisticsUiState {
        val activeChats = chats.filter { !it.isDeleted }
        val validMessages = allMessages.filter { !it.isDeleted }

        val buckets7d = computeLast7DaysWakeSessionBuckets(
            chats = activeChats,
            messages = validMessages,
            voiceCommands = voiceCommands
        )
        val buckets30d = computeLast30DaysWakeSessionBuckets(
            chats = activeChats,
            messages = validMessages,
            voiceCommands = voiceCommands
        )
        val bucketsAllTime = computeAllTimeWakeSessionBuckets(
            chats = activeChats,
            messages = validMessages,
            voiceCommands = voiceCommands
        )
        val heatmapCells = computeWeeklyActivityHeatmap(
            chats = activeChats,
            messages = validMessages,
            voiceCommands = voiceCommands
        )
        val peakCell = heatmapCells.maxByOrNull { it.totalActivityScore }
        val peakSlotLabel = if (peakCell != null && peakCell.totalActivityScore > 0) {
            "Peak: ${peakCell.dayShortLabel} ${peakCell.timeBlock.shortLabel} (${peakCell.wakeTriggerCount} wake, ${peakCell.messageCount} turns)"
        } else {
            "Peak: No activity recorded yet"
        }

        val totalSessions7d = buckets7d.sumOf { it.totalSessions }
        val wakeTriggeredSessions7d = buckets7d.sumOf { it.wakeTriggeredSessions }
        val wakeTriggerCount7d = buckets7d.sumOf { it.wakeTriggerCount }
        val wakeSharePct7d = if (totalSessions7d > 0) {
            ((wakeTriggeredSessions7d.toFloat() / totalSessions7d.toFloat()) * 100f)
                .roundToInt()
                .coerceIn(0, 100)
        } else {
            0
        }

        val totalSessions30d = buckets30d.sumOf { it.totalSessions }
        val wakeTriggeredSessions30d = buckets30d.sumOf { it.wakeTriggeredSessions }
        val wakeTriggerCount30d = buckets30d.sumOf { it.wakeTriggerCount }
        val wakeSharePct30d = if (totalSessions30d > 0) {
            ((wakeTriggeredSessions30d.toFloat() / totalSessions30d.toFloat()) * 100f)
                .roundToInt()
                .coerceIn(0, 100)
        } else {
            0
        }

        val sessionVoiceSum = activeChats.sumOf { it.totalVoiceCommands }
        val allTimeWakeCount = maxOf(
            totalWakeMsgCount,
            sessionVoiceSum,
            voiceCommands.size,
            wakeTriggerCount30d,
            wakeTriggerCount7d
        )

        val allTimeWakeChatsCount = activeChats.count { chat ->
            chat.totalVoiceCommands > 0 || validMessages.any { it.chatId == chat.id && it.triggeredByWakeWord }
        }
        val allTimeSharePct = if (activeChats.isNotEmpty()) {
            ((allTimeWakeChatsCount.toFloat() / activeChats.size.toFloat()) * 100f)
                .roundToInt()
                .coerceIn(0, 100)
        } else {
            0
        }

        val allTimeTrackedSec = activeChats.sumOf { it.totalTrackedSeconds }
        val defaultWakeChat = activeChats.firstOrNull { it.isDefaultWakeChat } ?: activeChats.firstOrNull()

        val activeBuckets = when (selectedRange) {
            StatisticsDateRange.LAST_7_DAYS -> buckets7d
            StatisticsDateRange.LAST_30_DAYS -> buckets30d
            StatisticsDateRange.ALL_TIME -> bucketsAllTime
        }

        return SessionStatisticsUiState(
            selectedDateRange = selectedRange,
            activeBuckets = activeBuckets,
            dailyBuckets = buckets7d,
            buckets30d = buckets30d,
            bucketsAllTime = bucketsAllTime,
            weeklyHeatmapCells = heatmapCells,
            peakHeatmapSlotLabel = peakSlotLabel,
            totalSessions7d = totalSessions7d,
            wakeTriggeredSessions7d = wakeTriggeredSessions7d,
            wakeTriggerCount7d = wakeTriggerCount7d,
            wakeSessionPercentage7d = wakeSharePct7d,
            totalSessions30d = totalSessions30d,
            wakeTriggeredSessions30d = wakeTriggeredSessions30d,
            wakeTriggerCount30d = wakeTriggerCount30d,
            wakeSessionPercentage30d = wakeSharePct30d,
            allTimeSessionsCount = activeChats.size,
            allTimeWakeTriggeredSessionsCount = allTimeWakeChatsCount,
            allTimeWakeSessionPercentage = allTimeSharePct,
            allTimeMessagesCount = maxOf(totalMsgCount, validMessages.size),
            allTimeWakeTriggerCount = allTimeWakeCount,
            allTimeTrackedSeconds = allTimeTrackedSec,
            activeDefaultWakeChat = defaultWakeChat,
            availableChats = activeChats,
            isLoading = false,
            isExporting = isExporting,
            lastExportMessage = lastExportMessage,
            lastSharedFileUri = lastSharedFileUri
        )
    }

    fun setPendingExportFormat(format: BackupExportFormat) {
        _pendingExportFormat.value = format
    }

    fun clearExportMessage() {
        _lastExportMessage.value = null
    }

    /**
     * Transforms the current Room chat history and [uiState] metrics into a standardized
     * RFC-4180 CSV string with UTF-8 BOM via [com.example.util.ChatSessionCsvTransformer].
     */
    suspend fun exportStandardizedCsvString(chatId: Long? = null): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val chats = if (chatId != null) {
            listOfNotNull(repository.getChatByIdSync(chatId))
        } else {
            repository.getAllChatsSync()
        }
        val messages = if (chatId != null) {
            repository.getMessagesForChatSync(chatId)
        } else {
            repository.getAllMessagesChronologicalSync()
        }
        val voiceCommands = repository.getRecentVoiceCommandsSync(limit = 500)
        com.example.util.ChatSessionCsvTransformer.transformToCsv(
            chats = chats,
            messages = messages,
            voiceCommands = voiceCommands,
            statisticsState = uiState.value,
            filterChatId = chatId,
            exportedAtMs = now
        )
    }

    /**
     * Generates a temporary export file and `content://` [Uri] via [ChatLogJsonExporter]
     * (`FileProvider`), records the backup timestamp for the 30-day reminder check, and launches
     * the Android `Intent.ACTION_SEND` Share Sheet so the user can export their data to other apps.
     */
    fun shareBackup(
        context: Context,
        format: BackupExportFormat,
        chatId: Long? = null,
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            _isExporting.value = true
            try {
                val generated = ChatLogJsonExporter.generateTemporarySharedExport(
                    context = context,
                    format = format,
                    chatRepository = repository,
                    chatId = chatId,
                    statisticsState = uiState.value
                )
                AppSettingsManager.recordBackupExportTimestamp(context)
                _lastSharedFileUri.value = generated.contentUri.toString()
                val chooserIntent = when (format) {
                    BackupExportFormat.JSON -> ChatLogJsonExporter.createShareJsonFileIntent(
                        context = context,
                        jsonFile = generated.file
                    )
                    BackupExportFormat.CSV -> ChatLogJsonExporter.createShareCsvFileIntent(
                        context = context,
                        csvFile = generated.file
                    )
                }
                withContext(Dispatchers.Main) {
                    context.startActivity(chooserIntent)
                }
                _lastExportMessage.value = "Shared ${generated.file.name} via FileProvider (${generated.contentUri})"
            } catch (e: Exception) {
                Log.e("SessionStatisticsVM", "Failed sharing ${format.name} backup", e)
                val msg = e.localizedMessage ?: "Failed to share ${format.name} backup"
                _lastExportMessage.value = msg
                onError(msg)
            } finally {
                _isExporting.value = false
            }
        }
    }

    /**
     * Writes the complete JSON or standardized CSV backup (chat history + usage statistics)
     * directly to a user-selected SAF document [targetUri] and updates the 30-day backup timestamp.
     */
    fun saveBackupToDocumentUri(
        context: Context,
        targetUri: Uri,
        format: BackupExportFormat = _pendingExportFormat.value,
        chatId: Long? = null,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            _isExporting.value = true
            try {
                val success = ChatLogJsonExporter.writeBackupToDocumentUri(
                    context = context,
                    targetUri = targetUri,
                    format = format,
                    chatRepository = repository,
                    chatId = chatId,
                    statisticsState = uiState.value
                )
                if (success) {
                    AppSettingsManager.recordBackupExportTimestamp(context)
                }
                val message = if (success) {
                    "Saved ${format.displayLabel} to device!"
                } else {
                    "Could not write ${format.displayLabel} to selected file."
                }
                _lastExportMessage.value = message
                withContext(Dispatchers.Main) {
                    onComplete(success, message)
                }
            } catch (e: Exception) {
                Log.e("SessionStatisticsVM", "Failed writing ${format.name} backup to URI", e)
                val message = "Backup save failed: ${e.localizedMessage ?: "Unknown error"}"
                _lastExportMessage.value = message
                withContext(Dispatchers.Main) {
                    onComplete(false, message)
                }
            } finally {
                _isExporting.value = false
            }
        }
    }
}
