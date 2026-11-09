package com.example.util

import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.ui.SessionStatisticsUiState
import com.example.ui.components.DailyWakeSessionBucket
import com.example.ui.components.StatisticsDateRange
import com.example.ui.components.WeeklyHeatmapCell
import com.example.ui.components.computeAllTimeWakeSessionBuckets
import com.example.ui.components.computeLast30DaysWakeSessionBuckets
import com.example.ui.components.computeLast7DaysWakeSessionBuckets
import com.example.ui.components.computeWeeklyActivityHeatmap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Standardized CSV data utility that transforms chat history ([ChatSession], [ChatMessage],
 * [VoiceCommandHistoryEntry]) and [SessionStatisticsUiState] metrics from `SessionStatisticsViewModel`
 * (including **7-Day**, **30-Day**, and **All-Time** summaries plus trend buckets for the active
 * date-range filter) into an RFC-4180 compliant single normalized table with a UTF-8 Byte Order Mark
 * (BOM) for seamless viewing in Microsoft Excel, Google Sheets, and Apple Numbers.
 */
object ChatSessionCsvTransformer {

    /**
     * UTF-8 Byte Order Mark (BOM) so Excel and spreadsheet readers automatically detect UTF-8.
     */
    const val UTF8_BOM = "\uFEFF"

    /**
     * Standard RFC-4180 record delimiter (`CRLF`).
     */
    const val CRLF = "\r\n"

    /**
     * Normalized 17-column header schema for unified spreadsheet filtering and pivot analysis.
     */
    val HEADER_COLUMNS: List<String> = listOf(
        "row_category",
        "record_id",
        "chat_id",
        "chat_title",
        "wake_word",
        "sender_or_metric",
        "triggered_by_wake_word",
        "local_datetime",
        "timestamp_iso",
        "timestamp_ms",
        "content_or_value",
        "wake_triggered_sessions",
        "total_sessions",
        "wake_trigger_count",
        "wake_share_percent",
        "total_messages_count",
        "total_tracked_seconds"
    )

    object RowCategory {
        const val SUMMARY_7D = "SUMMARY_7D"
        const val SUMMARY_30D = "SUMMARY_30D"
        const val SUMMARY_ALL_TIME = "SUMMARY_ALL_TIME"
        const val DAILY_TREND_7D = "DAILY_TREND_7D"
        const val BUCKET_TREND_30D = "BUCKET_TREND_30D"
        const val BUCKET_TREND_ALL_TIME = "BUCKET_TREND_ALL_TIME"
        const val HEATMAP_SLOT_7D = "HEATMAP_SLOT_7D"
        const val CHAT_SESSION = "CHAT_SESSION"
        const val CHAT_MESSAGE = "CHAT_MESSAGE"
        const val VOICE_COMMAND = "VOICE_COMMAND"
    }

    /**
     * Formats an epoch millisecond timestamp as a human-readable local date/time string
     * (`yyyy-MM-dd HH:mm:ss`) for spreadsheet columns.
     */
    fun formatLocalDateTime(
        epochMs: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                this.timeZone = timeZone
            }
            sdf.format(Date(epochMs.coerceAtLeast(0L)))
        } catch (_: Throwable) {
            epochMs.toString()
        }
    }

    /**
     * Formats an epoch millisecond timestamp as a canonical UTC ISO-8601 string
     * (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`).
     */
    fun formatIsoTimestamp(epochMs: Long): String {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            sdf.format(Date(epochMs.coerceAtLeast(0L)))
        } catch (_: Throwable) {
            epochMs.toString()
        }
    }

    /**
     * Escapes a single CSV cell value according to RFC-4180:
     * - Preserves multiline text (`\n` / `\r\n`) inside double-quoted fields.
     * - Doubles any internal double quotes (`"` -> `""`).
     * - Wraps the field in double quotes whenever commas, double quotes, CR/LF newlines,
     *   leading/trailing whitespace, or leading spreadsheet formula characters (`=`, `+`, `-`, `@`)
     *   are present.
     */
    fun escapeCsvCell(value: String?): String {
        val raw = value ?: ""
        if (raw.isEmpty()) return ""

        val hasFormulaPrefix = raw.firstOrNull()?.let { it == '=' || it == '+' || it == '@' } == true
        val sanitized = if (hasFormulaPrefix) "'$raw" else raw

        val needsQuotes = sanitized.contains(',') ||
            sanitized.contains('"') ||
            sanitized.contains('\n') ||
            sanitized.contains('\r') ||
            sanitized.first().isWhitespace() ||
            sanitized.last().isWhitespace()

        return if (needsQuotes) {
            "\"${sanitized.replace("\"", "\"\"")}\""
        } else {
            sanitized
        }
    }

    /**
     * Joins a list of 17 column values into a single RFC-4180 CSV line.
     */
    fun buildCsvRow(fields: List<String?>): String {
        return fields.joinToString(",") { escapeCsvCell(it) }
    }

    /**
     * Transforms chat history ([chats], [messages], [voiceCommands]) and optional
     * [statisticsState] from `SessionStatisticsViewModel` into a standardized CSV string
     * prefixed with a UTF-8 BOM (`\uFEFF`) for spreadsheet viewing.
     */
    fun transformToCsv(
        chats: List<ChatSession>,
        messages: List<ChatMessage>,
        voiceCommands: List<VoiceCommandHistoryEntry> = emptyList(),
        statisticsState: SessionStatisticsUiState? = null,
        filterChatId: Long? = null,
        exportedAtMs: Long = System.currentTimeMillis(),
        includeUtf8Bom: Boolean = true,
        localTimeZone: TimeZone = TimeZone.getDefault()
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

        val activeDateRange = statisticsState?.selectedDateRange ?: StatisticsDateRange.LAST_7_DAYS

        // Resolve 7-day, 30-day, and all-time buckets
        val buckets7d: List<DailyWakeSessionBucket> = if (
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

        val buckets30d: List<DailyWakeSessionBucket> = if (
            filterChatId == null &&
            statisticsState != null &&
            statisticsState.buckets30d.isNotEmpty()
        ) {
            statisticsState.buckets30d
        } else {
            computeLast30DaysWakeSessionBuckets(
                chats = targetChats,
                messages = targetMessages,
                voiceCommands = targetVoiceCommands,
                nowMillis = exportedAtMs
            )
        }

        val bucketsAllTime: List<DailyWakeSessionBucket> = if (
            filterChatId == null &&
            statisticsState != null &&
            statisticsState.bucketsAllTime.isNotEmpty()
        ) {
            statisticsState.bucketsAllTime
        } else {
            computeAllTimeWakeSessionBuckets(
                chats = targetChats,
                messages = targetMessages,
                voiceCommands = targetVoiceCommands,
                nowMillis = exportedAtMs
            )
        }

        val totalSessions7d = if (filterChatId == null && statisticsState != null && !statisticsState.isLoading) {
            statisticsState.totalSessions7d
        } else {
            buckets7d.sumOf { it.totalSessions }
        }
        val wakeSessions7d = if (filterChatId == null && statisticsState != null && !statisticsState.isLoading) {
            statisticsState.wakeTriggeredSessions7d
        } else {
            buckets7d.sumOf { it.wakeTriggeredSessions }
        }
        val wakeTriggers7d = if (filterChatId == null && statisticsState != null && !statisticsState.isLoading) {
            statisticsState.wakeTriggerCount7d
        } else {
            buckets7d.sumOf { it.wakeTriggerCount }
        }
        val wakeSharePercent7d = if (filterChatId == null && statisticsState != null && !statisticsState.isLoading) {
            statisticsState.wakeSessionPercentage7d
        } else if (totalSessions7d > 0) {
            ((wakeSessions7d.toFloat() / totalSessions7d.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        } else {
            0
        }

        val totalSessions30d = if (filterChatId == null && statisticsState != null && !statisticsState.isLoading && statisticsState.totalSessions30d > 0) {
            statisticsState.totalSessions30d
        } else {
            buckets30d.sumOf { it.totalSessions }
        }
        val wakeSessions30d = if (filterChatId == null && statisticsState != null && !statisticsState.isLoading && statisticsState.wakeTriggeredSessions30d > 0) {
            statisticsState.wakeTriggeredSessions30d
        } else {
            buckets30d.sumOf { it.wakeTriggeredSessions }
        }
        val wakeTriggers30d = if (filterChatId == null && statisticsState != null && !statisticsState.isLoading && statisticsState.wakeTriggerCount30d > 0) {
            statisticsState.wakeTriggerCount30d
        } else {
            buckets30d.sumOf { it.wakeTriggerCount }
        }
        val wakeSharePercent30d = if (totalSessions30d > 0) {
            ((wakeSessions30d.toFloat() / totalSessions30d.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        } else {
            0
        }

        val allTimeChatsCount = if (filterChatId == null && statisticsState != null && !statisticsState.isLoading) {
            maxOf(statisticsState.allTimeSessionsCount, targetChats.size)
        } else {
            targetChats.size
        }

        val allTimeMessagesCount = if (filterChatId == null && statisticsState != null && !statisticsState.isLoading) {
            maxOf(statisticsState.allTimeMessagesCount, targetMessages.size)
        } else {
            targetMessages.size
        }

        val allTimeWakeMessagesCount = targetMessages.count { it.triggeredByWakeWord }
        val allTimeSessionVoiceSum = targetChats.sumOf { it.totalVoiceCommands }
        val allTimeWakeTriggers = if (filterChatId == null && statisticsState != null && !statisticsState.isLoading) {
            maxOf(
                statisticsState.allTimeWakeTriggerCount,
                allTimeWakeMessagesCount,
                allTimeSessionVoiceSum,
                targetVoiceCommands.size
            )
        } else {
            maxOf(allTimeWakeMessagesCount, allTimeSessionVoiceSum, targetVoiceCommands.size)
        }

        val allTimeTrackedSec = if (filterChatId == null && statisticsState != null && !statisticsState.isLoading) {
            maxOf(statisticsState.allTimeTrackedSeconds, targetChats.sumOf { it.totalTrackedSeconds })
        } else {
            targetChats.sumOf { it.totalTrackedSeconds }
        }

        val sb = StringBuilder()
        if (includeUtf8Bom) {
            sb.append(UTF8_BOM)
        }

        // Row 0: Normalized 17-column header
        sb.append(buildCsvRow(HEADER_COLUMNS)).append(CRLF)

        val scopeChatIdLabel = filterChatId?.toString() ?: "ALL"
        val exportLocalTime = formatLocalDateTime(exportedAtMs, localTimeZone)
        val exportIsoTime = formatIsoTimestamp(exportedAtMs)

        // 1. 7-Day Summary KPI Row
        val sevenDayMessagesSum = buckets7d.sumOf { it.totalMessagesCount }
        sb.append(
            buildCsvRow(
                listOf(
                    RowCategory.SUMMARY_7D,
                    "summary_7d",
                    scopeChatIdLabel,
                    "Last 7 Days Usage Summary",
                    "",
                    "7_DAY_METRICS",
                    (wakeSessions7d > 0).toString(),
                    exportLocalTime,
                    exportIsoTime,
                    exportedAtMs.toString(),
                    "7-Day Wake Sessions: $wakeSessions7d / $totalSessions7d ($wakeSharePercent7d%) | Wake Triggers: $wakeTriggers7d",
                    wakeSessions7d.toString(),
                    totalSessions7d.toString(),
                    wakeTriggers7d.toString(),
                    wakeSharePercent7d.toString(),
                    sevenDayMessagesSum.toString(),
                    allTimeTrackedSec.toString()
                )
            )
        ).append(CRLF)

        // 2. 30-Day Summary KPI Row
        val thirtyDayMessagesSum = buckets30d.sumOf { it.totalMessagesCount }
        sb.append(
            buildCsvRow(
                listOf(
                    RowCategory.SUMMARY_30D,
                    "summary_30d",
                    scopeChatIdLabel,
                    "Last 30 Days Usage Summary",
                    "",
                    "30_DAY_METRICS",
                    (wakeSessions30d > 0).toString(),
                    exportLocalTime,
                    exportIsoTime,
                    exportedAtMs.toString(),
                    "30-Day Wake Sessions: $wakeSessions30d / $totalSessions30d ($wakeSharePercent30d%) | Wake Triggers: $wakeTriggers30d",
                    wakeSessions30d.toString(),
                    totalSessions30d.toString(),
                    wakeTriggers30d.toString(),
                    wakeSharePercent30d.toString(),
                    thirtyDayMessagesSum.toString(),
                    allTimeTrackedSec.toString()
                )
            )
        ).append(CRLF)

        // 3. All-Time Summary KPI Row
        val allTimeWakeChats = targetChats.count { chat ->
            chat.totalVoiceCommands > 0 || targetMessages.any { it.chatId == chat.id && it.triggeredByWakeWord }
        }
        val allTimeSharePct = if (allTimeChatsCount > 0) {
            ((allTimeWakeChats.toFloat() / allTimeChatsCount.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        } else {
            0
        }
        sb.append(
            buildCsvRow(
                listOf(
                    RowCategory.SUMMARY_ALL_TIME,
                    "summary_all_time",
                    scopeChatIdLabel,
                    "All-Time Usage Summary",
                    "",
                    "ALL_TIME_METRICS",
                    (allTimeWakeTriggers > 0).toString(),
                    exportLocalTime,
                    exportIsoTime,
                    exportedAtMs.toString(),
                    "Total Chats: $allTimeChatsCount | Total Turns: $allTimeMessagesCount | Total Wake Triggers: $allTimeWakeTriggers | Active Filter: ${activeDateRange.displayLabel}",
                    allTimeWakeChats.toString(),
                    allTimeChatsCount.toString(),
                    allTimeWakeTriggers.toString(),
                    allTimeSharePct.toString(),
                    allTimeMessagesCount.toString(),
                    allTimeTrackedSec.toString()
                )
            )
        ).append(CRLF)

        // 4. Trend Bucket Rows for the Active Date Range (7D, 30D, or All-Time)
        val (activeTrendCategory, activeTrendBuckets) = when (activeDateRange) {
            StatisticsDateRange.LAST_7_DAYS -> RowCategory.DAILY_TREND_7D to buckets7d
            StatisticsDateRange.LAST_30_DAYS -> RowCategory.BUCKET_TREND_30D to buckets30d
            StatisticsDateRange.ALL_TIME -> RowCategory.BUCKET_TREND_ALL_TIME to bucketsAllTime
        }
        for ((index, bucket) in activeTrendBuckets.withIndex()) {
            val daySharePct = if (bucket.totalSessions > 0) {
                ((bucket.wakeTriggeredSessions.toFloat() / bucket.totalSessions.toFloat()) * 100f)
                    .roundToInt()
                    .coerceIn(0, 100)
            } else {
                0
            }
            val safeId = bucket.dateLabel.replace('/', '-').replace('–', '_')
            sb.append(
                buildCsvRow(
                    listOf(
                        activeTrendCategory,
                        "bucket_${activeDateRange.shortCode.lowercase(Locale.ROOT)}_${index}_$safeId",
                        scopeChatIdLabel,
                        "${bucket.dayLabel} (${bucket.dateLabel})",
                        "",
                        if (bucket.isToday) "CURRENT_BUCKET" else "TREND_BUCKET_${activeDateRange.shortCode}",
                        (bucket.wakeTriggeredSessions > 0).toString(),
                        formatLocalDateTime(bucket.startOfDayMillis, localTimeZone),
                        formatIsoTimestamp(bucket.startOfDayMillis),
                        bucket.startOfDayMillis.toString(),
                        "Range=${activeDateRange.displayLabel}; Slice=${bucket.dateLabel}; WakeSessions=${bucket.wakeTriggeredSessions}; TotalSessions=${bucket.totalSessions}; WakeTriggers=${bucket.wakeTriggerCount}; Share=$daySharePct%",
                        bucket.wakeTriggeredSessions.toString(),
                        bucket.totalSessions.toString(),
                        bucket.wakeTriggerCount.toString(),
                        daySharePct.toString(),
                        bucket.totalMessagesCount.toString(),
                        "0"
                    )
                )
            ).append(CRLF)
        }

        // 4b. Weekly Activity Heatmap Rows (7 days × 4 time blocks = 28 slots)
        val heatmapCells: List<WeeklyHeatmapCell> = if (
            filterChatId == null &&
            statisticsState != null &&
            statisticsState.weeklyHeatmapCells.isNotEmpty()
        ) {
            statisticsState.weeklyHeatmapCells
        } else {
            computeWeeklyActivityHeatmap(
                chats = targetChats,
                messages = targetMessages,
                voiceCommands = targetVoiceCommands,
                nowMs = exportedAtMs
            )
        }
        for (cell in heatmapCells) {
            sb.append(
                buildCsvRow(
                    listOf(
                        RowCategory.HEATMAP_SLOT_7D,
                        "heatmap_${cell.dayIndex}_${cell.timeBlock.id.lowercase(Locale.ROOT)}",
                        scopeChatIdLabel,
                        "${cell.dayShortLabel} (${cell.dateLabel}) - ${cell.timeBlock.shortLabel} (${cell.timeBlock.rangeLabel})",
                        cell.topWakePhrase ?: "",
                        cell.timeBlock.id,
                        (cell.wakeTriggerCount > 0).toString(),
                        exportLocalTime,
                        exportIsoTime,
                        exportedAtMs.toString(),
                        "Day=${cell.dayShortLabel} (${cell.dateLabel}); Block=${cell.timeBlock.shortLabel} (${cell.timeBlock.rangeLabel}); IntensityLevel=${cell.intensityLevel}/4; WakeTriggers=${cell.wakeTriggerCount}; Messages=${cell.messageCount}; ActiveChats=${cell.activeSessionCount}",
                        cell.activeSessionCount.toString(),
                        cell.activeSessionCount.toString(),
                        cell.wakeTriggerCount.toString(),
                        (cell.intensityLevel * 25).toString(),
                        cell.messageCount.toString(),
                        "0"
                    )
                )
            ).append(CRLF)
        }

        // 5. Chat Session Metadata Rows
        for (chat in targetChats) {
            val chatMessages = targetMessages.filter { it.chatId == chat.id }
            val chatWakeMsgCount = chatMessages.count { it.triggeredByWakeWord }
            val resolvedVoiceTriggers = maxOf(chat.totalVoiceCommands, chatWakeMsgCount)
            val isWakeSession = resolvedVoiceTriggers > 0
            val sessionSharePct = if (chatMessages.isNotEmpty()) {
                ((chatWakeMsgCount.toFloat() / chatMessages.size.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
            } else if (isWakeSession) {
                100
            } else {
                0
            }
            sb.append(
                buildCsvRow(
                    listOf(
                        RowCategory.CHAT_SESSION,
                        chat.id.toString(),
                        chat.id.toString(),
                        chat.title,
                        chat.wakeWord,
                        if (chat.isDefaultWakeChat) "DEFAULT_WAKE_CHAT" else "CHAT_SESSION",
                        isWakeSession.toString(),
                        formatLocalDateTime(chat.lastUpdatedAt, localTimeZone),
                        formatIsoTimestamp(chat.lastUpdatedAt),
                        chat.lastUpdatedAt.toString(),
                        chat.systemInstruction,
                        if (isWakeSession) "1" else "0",
                        "1",
                        resolvedVoiceTriggers.toString(),
                        sessionSharePct.toString(),
                        chatMessages.size.toString(),
                        chat.totalTrackedSeconds.toString()
                    )
                )
            ).append(CRLF)
        }

        // 6. Chronological Chat Message Rows (RFC-4180 multiline quoted content)
        for (msg in targetMessages) {
            val parent = chatById[msg.chatId]
            val normalizedSender = if (msg.sender.equals("user", ignoreCase = true)) "user" else "gemini"
            sb.append(
                buildCsvRow(
                    listOf(
                        RowCategory.CHAT_MESSAGE,
                        msg.id.toString(),
                        msg.chatId.toString(),
                        parent?.title ?: "",
                        parent?.wakeWord ?: "",
                        normalizedSender,
                        msg.triggeredByWakeWord.toString(),
                        formatLocalDateTime(msg.timestamp, localTimeZone),
                        formatIsoTimestamp(msg.timestamp),
                        msg.timestamp.toString(),
                        msg.content,
                        if (msg.triggeredByWakeWord) "1" else "0",
                        "1",
                        if (msg.triggeredByWakeWord) "1" else "0",
                        if (msg.triggeredByWakeWord) "100" else "0",
                        "1",
                        (parent?.totalTrackedSeconds ?: 0L).toString()
                    )
                )
            ).append(CRLF)
        }

        // 7. Voice Command Log Rows
        for (cmd in targetVoiceCommands) {
            sb.append(
                buildCsvRow(
                    listOf(
                        RowCategory.VOICE_COMMAND,
                        cmd.id,
                        "",
                        cmd.chatTitle ?: "",
                        cmd.triggerPhrase,
                        cmd.commandType.name,
                        "true",
                        formatLocalDateTime(cmd.timestamp, localTimeZone),
                        formatIsoTimestamp(cmd.timestamp),
                        cmd.timestamp.toString(),
                        cmd.recognizedTranscript,
                        "1",
                        "1",
                        "1",
                        "100",
                        "1",
                        "0"
                    )
                )
            ).append(CRLF)
        }

        return sb.toString()
    }
}
