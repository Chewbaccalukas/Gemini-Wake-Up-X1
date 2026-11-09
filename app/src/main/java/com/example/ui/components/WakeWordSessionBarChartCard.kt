package com.example.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.ui.SessionStatisticsUiState
import com.example.util.BackupExportFormat
import com.example.util.VoiceCommandHistoryEntry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Selectable date-range filter options for the Dashboard Bar Chart and [com.example.ui.SessionStatisticsViewModel].
 */
enum class StatisticsDateRange(
    val displayLabel: String,
    val chipLabel: String,
    val shortCode: String
) {
    LAST_7_DAYS(
        displayLabel = "Last 7 Days",
        chipLabel = "7 Days",
        shortCode = "7D"
    ),
    LAST_30_DAYS(
        displayLabel = "Last 30 Days",
        chipLabel = "30 Days",
        shortCode = "30D"
    ),
    ALL_TIME(
        displayLabel = "All-Time",
        chipLabel = "All-Time",
        shortCode = "ALL"
    )
}

/**
 * Time-slice bucket holding counts of wake-word-triggered sessions vs. total sessions,
 * plus individual wake-word trigger counts and message volume for that slice.
 * Used for 7-day daily bars, 30-day 5-day buckets, and All-Time weekly/monthly buckets.
 */
data class DailyWakeSessionBucket(
    val dayLabel: String,
    val dateLabel: String,
    val startOfDayMillis: Long,
    val endOfDayMillis: Long,
    val wakeTriggeredSessions: Int,
    val totalSessions: Int,
    val wakeTriggerCount: Int = 0,
    val totalMessagesCount: Int = 0,
    val isToday: Boolean = false
)

private const val ONE_DAY_MS = 86_400_000L

private fun buildBucketForTimeWindow(
    dayLabel: String,
    dateLabel: String,
    startMillis: Long,
    endMillis: Long,
    isCurrentSlice: Boolean,
    chats: List<ChatSession>,
    messages: List<ChatMessage>,
    voiceCommands: List<VoiceCommandHistoryEntry>,
    chatIdByTitle: Map<String, Long>
): DailyWakeSessionBucket {
    val activeSessionIds = mutableSetOf<Long>()
    val wakeSessionIds = mutableSetOf<Long>()
    var windowWakeMessageCount = 0
    var windowTotalMessagesCount = 0
    var windowSessionVoiceCountFallback = 0

    chats.forEach { chat ->
        val createdInWindow = chat.createdAt in startMillis..endMillis
        val activeInWindow = chat.lastUpdatedAt in startMillis..endMillis
        val fallbackCurrent = isCurrentSlice && chat.createdAt <= 0L && chat.lastUpdatedAt <= 0L

        if (createdInWindow || activeInWindow || fallbackCurrent) {
            activeSessionIds.add(chat.id)
            if (chat.totalVoiceCommands > 0) {
                wakeSessionIds.add(chat.id)
                windowSessionVoiceCountFallback += chat.totalVoiceCommands
            }
        }
    }

    messages.forEach { msg ->
        if (msg.timestamp in startMillis..endMillis) {
            windowTotalMessagesCount++
            activeSessionIds.add(msg.chatId)
            if (msg.triggeredByWakeWord) {
                wakeSessionIds.add(msg.chatId)
                windowWakeMessageCount++
            }
        }
    }

    var standaloneVoiceSessionCount = 0
    var windowVoiceCommandEntries = 0
    voiceCommands.forEach { cmd ->
        if (cmd.timestamp in startMillis..endMillis) {
            windowVoiceCommandEntries++
            val matchedId = cmd.chatTitle
                ?.trim()
                ?.lowercase(Locale.ROOT)
                ?.let { chatIdByTitle[it] }
            if (matchedId != null && matchedId > 0L) {
                activeSessionIds.add(matchedId)
                wakeSessionIds.add(matchedId)
            } else {
                standaloneVoiceSessionCount++
            }
        }
    }

    val totalCount = activeSessionIds.size + standaloneVoiceSessionCount
    val wakeCount = (wakeSessionIds.size + standaloneVoiceSessionCount).coerceAtMost(totalCount)
    val resolvedWakeTriggers = maxOf(
        windowWakeMessageCount,
        windowVoiceCommandEntries,
        if (messages.isEmpty()) windowSessionVoiceCountFallback else 0,
        wakeCount
    )

    return DailyWakeSessionBucket(
        dayLabel = dayLabel,
        dateLabel = dateLabel,
        startOfDayMillis = startMillis,
        endOfDayMillis = endMillis,
        wakeTriggeredSessions = wakeCount,
        totalSessions = totalCount,
        wakeTriggerCount = resolvedWakeTriggers,
        totalMessagesCount = windowTotalMessagesCount,
        isToday = isCurrentSlice
    )
}

/**
 * Computes 7 daily buckets (oldest to today) comparing wake-word-triggered sessions
 * versus total active sessions per day from local Room database records.
 */
fun computeLast7DaysWakeSessionBuckets(
    chats: List<ChatSession>,
    messages: List<ChatMessage> = emptyList(),
    voiceCommands: List<VoiceCommandHistoryEntry> = emptyList(),
    nowMillis: Long = System.currentTimeMillis()
): List<DailyWakeSessionBucket> {
    val dayFormat = SimpleDateFormat("EEE", Locale.getDefault())
    val dateFormat = SimpleDateFormat("M/d", Locale.getDefault())

    val calendar = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }

    val todayStartMillis = calendar.timeInMillis
    val chatIdByTitle = chats.associate { it.title.trim().lowercase(Locale.ROOT) to it.id }

    return (6 downTo 0).map { daysAgo ->
        val dayCal = Calendar.getInstance().apply {
            timeInMillis = todayStartMillis
            add(Calendar.DAY_OF_YEAR, -daysAgo)
        }
        val start = dayCal.timeInMillis
        val end = start + ONE_DAY_MS - 1L
        val isToday = daysAgo == 0
        buildBucketForTimeWindow(
            dayLabel = if (isToday) "Today" else dayFormat.format(Date(start)),
            dateLabel = dateFormat.format(Date(start)),
            startMillis = start,
            endMillis = end,
            isCurrentSlice = isToday,
            chats = chats,
            messages = messages,
            voiceCommands = voiceCommands,
            chatIdByTitle = chatIdByTitle
        )
    }
}

/**
 * Computes 6 five-day buckets across the Last 30 Days (oldest 5-day slice to most recent 5-day slice).
 */
fun computeLast30DaysWakeSessionBuckets(
    chats: List<ChatSession>,
    messages: List<ChatMessage> = emptyList(),
    voiceCommands: List<VoiceCommandHistoryEntry> = emptyList(),
    nowMillis: Long = System.currentTimeMillis()
): List<DailyWakeSessionBucket> {
    val shortDateFormat = SimpleDateFormat("M/d", Locale.getDefault())

    val calendar = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val todayEndMillis = calendar.timeInMillis + ONE_DAY_MS - 1L
    val chatIdByTitle = chats.associate { it.title.trim().lowercase(Locale.ROOT) to it.id }

    // 6 five-day slices covering 30 days ending at todayEndMillis
    return (5 downTo 0).map { bucketOffset ->
        val sliceEnd = todayEndMillis - (bucketOffset * 5L * ONE_DAY_MS)
        val sliceStart = sliceEnd - (5L * ONE_DAY_MS) + 1L
        val isLatestSlice = bucketOffset == 0
        val startLabel = shortDateFormat.format(Date(sliceStart))
        val endLabel = shortDateFormat.format(Date(sliceEnd))
        buildBucketForTimeWindow(
            dayLabel = if (isLatestSlice) "Now" else startLabel,
            dateLabel = "$startLabel–$endLabel",
            startMillis = sliceStart,
            endMillis = sliceEnd,
            isCurrentSlice = isLatestSlice,
            chats = chats,
            messages = messages,
            voiceCommands = voiceCommands,
            chatIdByTitle = chatIdByTitle
        )
    }
}

/**
 * Computes adaptive weekly or monthly buckets across the user's All-Time chat history.
 * - If total history spans > 45 days, groups into 6 equal monthly/multi-week slices.
 * - Otherwise groups into 6 weekly slices ending Today.
 */
fun computeAllTimeWakeSessionBuckets(
    chats: List<ChatSession>,
    messages: List<ChatMessage> = emptyList(),
    voiceCommands: List<VoiceCommandHistoryEntry> = emptyList(),
    nowMillis: Long = System.currentTimeMillis()
): List<DailyWakeSessionBucket> {
    val shortDateFormat = SimpleDateFormat("M/d", Locale.getDefault())
    val monthFormat = SimpleDateFormat("MMM d", Locale.getDefault())

    val calendar = Calendar.getInstance().apply {
        timeInMillis = nowMillis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val todayEndMillis = calendar.timeInMillis + ONE_DAY_MS - 1L
    val chatIdByTitle = chats.associate { it.title.trim().lowercase(Locale.ROOT) to it.id }

    val earliestChatTs = chats.flatMap { listOf(it.createdAt, it.lastUpdatedAt) }.filter { it > 0L }.minOrNull()
    val earliestMsgTs = messages.map { it.timestamp }.filter { it > 0L }.minOrNull()
    val earliestVoiceTs = voiceCommands.map { it.timestamp }.filter { it > 0L }.minOrNull()
    val earliestOverall = listOfNotNull(earliestChatTs, earliestMsgTs, earliestVoiceTs).minOrNull()
        ?: (todayEndMillis - 42L * ONE_DAY_MS + 1L)

    val rawSpanMs = (todayEndMillis - earliestOverall).coerceAtLeast(42L * ONE_DAY_MS)
    val bucketCount = 6
    val sliceDurationMs = (rawSpanMs / bucketCount).coerceAtLeast(7L * ONE_DAY_MS)
    val isMultiMonthSpan = rawSpanMs > 45L * ONE_DAY_MS

    return (bucketCount - 1 downTo 0).map { bucketOffset ->
        val sliceEnd = todayEndMillis - (bucketOffset * sliceDurationMs)
        val sliceStart = if (bucketOffset == bucketCount - 1) {
            minOf(earliestOverall, sliceEnd - sliceDurationMs + 1L)
        } else {
            sliceEnd - sliceDurationMs + 1L
        }
        val isLatestSlice = bucketOffset == 0
        val barLabel = if (isLatestSlice) {
            "Current"
        } else if (isMultiMonthSpan) {
            monthFormat.format(Date(sliceStart))
        } else {
            shortDateFormat.format(Date(sliceStart))
        }
        val rangeLabel = "${shortDateFormat.format(Date(sliceStart))}–${shortDateFormat.format(Date(sliceEnd))}"

        buildBucketForTimeWindow(
            dayLabel = barLabel,
            dateLabel = rangeLabel,
            startMillis = sliceStart,
            endMillis = sliceEnd,
            isCurrentSlice = isLatestSlice,
            chats = chats,
            messages = messages,
            voiceCommands = voiceCommands,
            chatIdByTitle = chatIdByTitle
        )
    }
}

/**
 * Dispatches bucket computation according to [dateRange] (`LAST_7_DAYS`, `LAST_30_DAYS`, `ALL_TIME`).
 */
fun computeBucketsForDateRange(
    dateRange: StatisticsDateRange,
    chats: List<ChatSession>,
    messages: List<ChatMessage> = emptyList(),
    voiceCommands: List<VoiceCommandHistoryEntry> = emptyList(),
    nowMillis: Long = System.currentTimeMillis()
): List<DailyWakeSessionBucket> {
    return when (dateRange) {
        StatisticsDateRange.LAST_7_DAYS -> computeLast7DaysWakeSessionBuckets(
            chats = chats,
            messages = messages,
            voiceCommands = voiceCommands,
            nowMillis = nowMillis
        )
        StatisticsDateRange.LAST_30_DAYS -> computeLast30DaysWakeSessionBuckets(
            chats = chats,
            messages = messages,
            voiceCommands = voiceCommands,
            nowMillis = nowMillis
        )
        StatisticsDateRange.ALL_TIME -> computeAllTimeWakeSessionBuckets(
            chats = chats,
            messages = messages,
            voiceCommands = voiceCommands,
            nowMillis = nowMillis
        )
    }
}

/**
 * Recharts-styled Trend Bar Chart Card displaying counts of wake-word-triggered
 * sessions versus total sessions across a selectable date-range filter
 * (**Last 7 Days**, **Last 30 Days**, or **All-Time**), integrated with [SessionStatisticsUiState]
 * from `SessionStatisticsViewModel`, plus inline Wake-Word Target Chat selection and
 * one-tap CSV `FileProvider` Share & JSON/CSV Backup Export controls.
 */
@Composable
fun WakeWordSessionBarChartCard(
    chats: List<ChatSession>,
    messages: List<ChatMessage> = emptyList(),
    voiceCommands: List<VoiceCommandHistoryEntry> = emptyList(),
    statisticsState: SessionStatisticsUiState? = null,
    onSelectDateRange: ((StatisticsDateRange) -> Unit)? = null,
    onSelectDefaultWakeChat: ((Long) -> Unit)? = null,
    onCreateNewWakeTargetChat: ((title: String, wakePhrase: String) -> Unit)? = null,
    onOpenExportDialog: (() -> Unit)? = null,
    onQuickShareJson: (() -> Unit)? = null,
    onQuickShareCsv: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var localSelectedRange by rememberSaveable {
        mutableStateOf(statisticsState?.selectedDateRange ?: StatisticsDateRange.LAST_7_DAYS)
    }
    val activeRange = statisticsState?.selectedDateRange ?: localSelectedRange

    val buckets = remember(activeRange, statisticsState, chats, messages, voiceCommands) {
        val stateBuckets = when (activeRange) {
            StatisticsDateRange.LAST_7_DAYS -> statisticsState?.dailyBuckets
            StatisticsDateRange.LAST_30_DAYS -> statisticsState?.buckets30d
            StatisticsDateRange.ALL_TIME -> statisticsState?.bucketsAllTime
        }
        stateBuckets?.takeIf { it.isNotEmpty() }
            ?: computeBucketsForDateRange(
                dateRange = activeRange,
                chats = chats,
                messages = messages,
                voiceCommands = voiceCommands
            )
    }

    val maxBucketCount = remember(buckets) {
        max(4, buckets.maxOfOrNull { max(it.totalSessions, it.wakeTriggeredSessions) } ?: 4)
    }

    val activeTotalSessions = remember(activeRange, statisticsState, buckets, chats) {
        when (activeRange) {
            StatisticsDateRange.LAST_7_DAYS -> statisticsState?.totalSessions7d ?: buckets.sumOf { it.totalSessions }
            StatisticsDateRange.LAST_30_DAYS -> statisticsState?.totalSessions30d ?: buckets.sumOf { it.totalSessions }
            StatisticsDateRange.ALL_TIME -> maxOf(
                statisticsState?.allTimeSessionsCount ?: chats.size,
                buckets.sumOf { it.totalSessions }
            )
        }
    }

    val activeWakeSessions = remember(activeRange, statisticsState, buckets, chats) {
        when (activeRange) {
            StatisticsDateRange.LAST_7_DAYS -> statisticsState?.wakeTriggeredSessions7d ?: buckets.sumOf { it.wakeTriggeredSessions }
            StatisticsDateRange.LAST_30_DAYS -> statisticsState?.wakeTriggeredSessions30d ?: buckets.sumOf { it.wakeTriggeredSessions }
            StatisticsDateRange.ALL_TIME -> maxOf(
                statisticsState?.allTimeWakeTriggeredSessionsCount ?: chats.count { it.totalVoiceCommands > 0 },
                buckets.sumOf { it.wakeTriggeredSessions }
            ).coerceAtMost(activeTotalSessions.coerceAtLeast(1))
        }
    }

    val activeWakeTriggers = remember(activeRange, statisticsState, buckets, chats) {
        when (activeRange) {
            StatisticsDateRange.LAST_7_DAYS -> statisticsState?.wakeTriggerCount7d ?: buckets.sumOf { it.wakeTriggerCount }
            StatisticsDateRange.LAST_30_DAYS -> statisticsState?.wakeTriggerCount30d ?: buckets.sumOf { it.wakeTriggerCount }
            StatisticsDateRange.ALL_TIME -> maxOf(
                statisticsState?.allTimeWakeTriggerCount ?: chats.sumOf { it.totalVoiceCommands },
                buckets.sumOf { it.wakeTriggerCount }
            )
        }
    }

    val wakePercentage = remember(activeTotalSessions, activeWakeSessions) {
        if (activeTotalSessions > 0) {
            ((activeWakeSessions.toFloat() / activeTotalSessions.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
        } else {
            0
        }
    }

    var selectedDayIndex by rememberSaveable(activeRange) {
        mutableIntStateOf(buckets.lastIndex.coerceAtLeast(0))
    }
    val safeSelectedIndex = selectedDayIndex.coerceIn(0, (buckets.size - 1).coerceAtLeast(0))
    val selectedBucket = buckets.getOrNull(safeSelectedIndex)

    // State for choosing or creating the target chat that opens on wake-word detection
    var wakeChatMenuExpanded by remember { mutableStateOf(false) }
    var showNewWakeChatDialog by rememberSaveable { mutableStateOf(false) }
    val activeWakeTargetChat = remember(statisticsState, chats) {
        statisticsState?.activeDefaultWakeChat
            ?: chats.firstOrNull { it.isDefaultWakeChat }
            ?: chats.firstOrNull()
    }

    val wakeBarColor = MaterialTheme.colorScheme.primary
    val totalBarColor = MaterialTheme.colorScheme.secondary
    val gridLineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    val cursorHighlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)

    if (showNewWakeChatDialog && onCreateNewWakeTargetChat != null) {
        CreateNewWakeTargetChatDialog(
            defaultWakePhrase = activeWakeTargetChat?.wakeWord ?: "Hey Gemini",
            onDismiss = { showNewWakeChatDialog = false },
            onConfirm = { newTitle, wakePhrase ->
                onCreateNewWakeTargetChat(newTitle, wakePhrase)
                showNewWakeChatDialog = false
            }
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("wake_word_session_bar_chart_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.BarChart,
                            contentDescription = "Recharts Wake-Word vs Total Sessions Trend",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "${activeRange.displayLabel}: Wake vs. Total Sessions",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = when (activeRange) {
                                StatisticsDateRange.LAST_7_DAYS -> "7 daily bars • Tap any bar to inspect session telemetry"
                                StatisticsDateRange.LAST_30_DAYS -> "6 five-day buckets • 30-day session frequency"
                                StatisticsDateRange.ALL_TIME -> "All-time trend buckets • Cumulative session telemetry"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$activeWakeSessions / $activeTotalSessions ($wakePercentage%)",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.testTag("wake_chart_summary_badge")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Date-Range Filter Bar (Last 7 Days | Last 30 Days | All-Time)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("dashboard_date_range_filter_row"),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.DateRange,
                    contentDescription = "Filter date range",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp)
                )
                StatisticsDateRange.entries.forEach { rangeOption ->
                    val isSelected = activeRange == rangeOption
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            localSelectedRange = rangeOption
                            onSelectDateRange?.invoke(rangeOption)
                        },
                        label = {
                            Text(
                                text = rangeOption.chipLabel,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            )
                        },
                        leadingIcon = if (isSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        } else null,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("stats_range_chip_${rangeOption.name}")
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Interactive Recharts-style Tooltip Banner for the selected bucket
            if (selectedBucket != null) {
                val dayShare = if (selectedBucket.totalSessions > 0) {
                    ((selectedBucket.wakeTriggeredSessions.toFloat() / selectedBucket.totalSessions.toFloat()) * 100f)
                        .roundToInt()
                } else {
                    0
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("recharts_active_tooltip_card"),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${selectedBucket.dayLabel} (${selectedBucket.dateLabel})",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Wake Triggers: ${selectedBucket.wakeTriggerCount} • Share: $dayShare%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Wake Sessions: ${selectedBucket.wakeTriggeredSessions}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = wakeBarColor
                                )
                                Text(
                                    text = "Total Sessions: ${selectedBucket.totalSessions}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                    color = totalBarColor
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Recharts Cartesian Grid + Y-Axis + Paired Bar Chart Plot
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(148.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                    .padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 6.dp)
                    .testTag("wake_word_bar_chart_plot"),
                verticalAlignment = Alignment.Bottom
            ) {
                // YAxis tick labels column (Max, Mid, 0)
                Column(
                    modifier = Modifier
                        .width(24.dp)
                        .fillMaxHeight()
                        .padding(bottom = 18.dp, top = 12.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = maxBucketCount.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = (maxBucketCount / 2).toString(),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "0",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Plot area with Cartesian dashed grid lines behind interactive bucket columns
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    // CartesianGrid background canvas
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 14.dp, bottom = 18.dp)
                    ) {
                        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                        val rows = 4
                        for (i in 0..rows) {
                            val y = (size.height / rows) * i
                            drawLine(
                                color = gridLineColor,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1.dp.toPx(),
                                pathEffect = if (i == rows) null else dashEffect
                            )
                        }
                    }

                    // Interactive bucket columns
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        buckets.forEachIndexed { index, bucket ->
                            val isSelected = index == safeSelectedIndex
                            val targetTotalFraction = (bucket.totalSessions.toFloat() / maxBucketCount.toFloat())
                                .coerceIn(0f, 1f)
                            val targetWakeFraction = (bucket.wakeTriggeredSessions.toFloat() / maxBucketCount.toFloat())
                                .coerceIn(0f, 1f)

                            val animatedTotalFraction by animateFloatAsState(
                                targetValue = targetTotalFraction,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessLow
                                ),
                                label = "totalSessionBar_$index"
                            )
                            val animatedWakeFraction by animateFloatAsState(
                                targetValue = targetWakeFraction,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                ),
                                label = "wakeSessionBar_$index"
                            )

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) cursorHighlightColor else Color.Transparent)
                                    .clickable { selectedDayIndex = index }
                                    .padding(vertical = 2.dp)
                                    .testTag("wake_bar_day_$index"),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Bottom
                            ) {
                                // Top count badge
                                Text(
                                    text = if (bucket.totalSessions > 0 || bucket.wakeTriggeredSessions > 0) {
                                        "${bucket.wakeTriggeredSessions}/${bucket.totalSessions}"
                                    } else {
                                        "0"
                                    },
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 9.sp,
                                        fontWeight = if (bucket.isToday || isSelected) FontWeight.Bold else FontWeight.Medium
                                    ),
                                    color = if (bucket.wakeTriggeredSessions > 0) {
                                        wakeBarColor
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    },
                                    textAlign = TextAlign.Center
                                )

                                Spacer(modifier = Modifier.height(3.dp))

                                // Paired Recharts Bar columns
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    // Series 1: Wake-Word-Triggered Sessions Bar
                                    Box(
                                        modifier = Modifier
                                            .width(10.dp)
                                            .fillMaxHeight(
                                                if (bucket.wakeTriggeredSessions > 0) {
                                                    animatedWakeFraction.coerceAtLeast(0.12f)
                                                } else {
                                                    0.04f
                                                }
                                            )
                                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                            .background(
                                                brush = Brush.verticalGradient(
                                                    colors = if (bucket.wakeTriggeredSessions > 0) {
                                                        listOf(wakeBarColor, wakeBarColor.copy(alpha = 0.72f))
                                                    } else {
                                                        listOf(
                                                            wakeBarColor.copy(alpha = 0.18f),
                                                            wakeBarColor.copy(alpha = 0.10f)
                                                        )
                                                    }
                                                )
                                            )
                                    )

                                    Spacer(modifier = Modifier.width(3.dp))

                                    // Series 2: Total Sessions Bar
                                    Box(
                                        modifier = Modifier
                                            .width(10.dp)
                                            .fillMaxHeight(
                                                if (bucket.totalSessions > 0) {
                                                    animatedTotalFraction.coerceAtLeast(0.12f)
                                                } else {
                                                    0.04f
                                                }
                                            )
                                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                            .background(
                                                color = if (bucket.totalSessions > 0) {
                                                    totalBarColor.copy(alpha = 0.85f)
                                                } else {
                                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                                                }
                                            )
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                // XAxis bucket label
                                Text(
                                    text = bucket.dayLabel,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 10.sp,
                                        fontWeight = if (bucket.isToday || isSelected) FontWeight.Bold else FontWeight.Normal
                                    ),
                                    color = if (bucket.isToday || isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Recharts Legend & Voice Turns footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(wakeBarColor)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Wake Sessions ($activeWakeSessions)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(9.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(totalBarColor.copy(alpha = 0.85f))
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Total Sessions ($activeTotalSessions)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text(
                    text = "$activeWakeTriggers wake triggers",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Target Wake Chat Selector + CSV Share & Backup Export Action Bar
            if (onSelectDefaultWakeChat != null ||
                onCreateNewWakeTargetChat != null ||
                onOpenExportDialog != null ||
                onQuickShareCsv != null
            ) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Wake-Word Target Chat Selector (Select existing or create new)
                    if (onSelectDefaultWakeChat != null || onCreateNewWakeTargetChat != null) {
                        Box(modifier = Modifier.weight(1f)) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { wakeChatMenuExpanded = true }
                                    .testTag("dashboard_wake_target_chat_selector"),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Hearing,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Column {
                                            Text(
                                                text = "Wake Target Chat",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = activeWakeTargetChat?.title ?: "Select or Create Chat",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.onSurface,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Icon(
                                        imageVector = Icons.Default.ExpandMore,
                                        contentDescription = "Choose wake target chat",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = wakeChatMenuExpanded,
                                onDismissRequest = { wakeChatMenuExpanded = false }
                            ) {
                                chats.forEach { chat ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(
                                                    text = chat.title,
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontWeight = if (chat.isDefaultWakeChat) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                )
                                                Text(
                                                    text = "Wake phrase: \"${chat.wakeWord}\"",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        },
                                        leadingIcon = {
                                            if (chat.id == activeWakeTargetChat?.id) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = "Active wake target",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        },
                                        onClick = {
                                            wakeChatMenuExpanded = false
                                            onSelectDefaultWakeChat?.invoke(chat.id)
                                        },
                                        modifier = Modifier.testTag("select_wake_target_chat_${chat.id}")
                                    )
                                }

                                if (onCreateNewWakeTargetChat != null) {
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                text = "+ Specify New Chat for Wake Word...",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        },
                                        onClick = {
                                            wakeChatMenuExpanded = false
                                            showNewWakeChatDialog = true
                                        },
                                        modifier = Modifier.testTag("create_new_wake_target_chat_menu_item")
                                    )
                                }
                            }
                        }
                    }

                    if (onQuickShareCsv != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        OutlinedButton(
                            onClick = onQuickShareCsv,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("dashboard_quick_share_csv_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Share CSV via FileProvider",
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Share CSV",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }

                    if (onOpenExportDialog != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        FilledTonalButton(
                            onClick = onOpenExportDialog,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.testTag("dashboard_open_backup_export_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = "Export JSON or CSV Backup",
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Export",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dialog allowing the user to specify a brand-new chat session to open automatically
 * when a wake word is detected.
 */
@Composable
fun CreateNewWakeTargetChatDialog(
    defaultWakePhrase: String = "Hey Gemini",
    onDismiss: () -> Unit,
    onConfirm: (title: String, wakePhrase: String) -> Unit
) {
    var chatTitle by rememberSaveable { mutableStateOf("") }
    var wakePhrase by rememberSaveable { mutableStateOf(defaultWakePhrase) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "New Wake-Word Target Chat",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Create a new chat session and set it as the target conversation that opens when your wake word is detected.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = chatTitle,
                    onValueChange = { chatTitle = it },
                    label = { Text("New Chat Title") },
                    placeholder = { Text("e.g. Daily Voice Assistant") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_wake_target_chat_title_input")
                )
                OutlinedTextField(
                    value = wakePhrase,
                    onValueChange = { wakePhrase = it },
                    label = { Text("Trigger Wake Word") },
                    placeholder = { Text("e.g. Hey Gemini") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_wake_target_chat_phrase_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanTitle = chatTitle.trim().ifBlank { "Voice Assistant Chat" }
                    val cleanPhrase = wakePhrase.trim().ifBlank { "Hey Gemini" }
                    onConfirm(cleanTitle, cleanPhrase)
                },
                modifier = Modifier.testTag("confirm_new_wake_target_chat_button")
            ) {
                Text("Create & Set Target")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Reusable Material 3 dialog allowing the user to export their chat history and 7-day / 30-day / all-time
 * usage statistics as either a JSON (`.json`) or CSV (`.csv`) file via **Save to Device**
 * (SAF `CreateDocument`) or **Share Sheet** (`FileProvider`).
 */
@Composable
fun BackupExportDialog(
    statisticsState: SessionStatisticsUiState? = null,
    onDismiss: () -> Unit,
    onSaveToDevice: (BackupExportFormat) -> Unit,
    onShareFile: (BackupExportFormat) -> Unit
) {
    var selectedFormat by rememberSaveable { mutableStateOf(BackupExportFormat.CSV) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Export Chat History & Usage Stats",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Back up your complete conversation history, voice command transcripts, and 7-day, 30-day, & All-Time wake-word session statistics.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (statisticsState != null) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "Chats: ${statisticsState.allTimeSessionsCount} • Turns: ${statisticsState.allTimeMessagesCount}",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "7D: ${statisticsState.wakeTriggeredSessions7d}/${statisticsState.totalSessions7d} (${statisticsState.wakeSessionPercentage7d}%) • 30D: ${statisticsState.wakeTriggeredSessions30d}/${statisticsState.totalSessions30d} (${statisticsState.wakeSessionPercentage30d}%)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                if (!statisticsState.lastSharedFileUri.isNullOrBlank()) {
                                    Text(
                                        text = "Temp Share URI: ${statisticsState.lastSharedFileUri}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("last_shared_fileprovider_uri_text")
                                    )
                                }
                            }
                            Text(
                                text = "${statisticsState.allTimeWakeTriggerCount} wake triggers",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Text(
                    text = "Select Export Format:",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BackupExportFormat.entries.forEach { format ->
                        val isSelected = selectedFormat == format
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedFormat = format },
                            label = {
                                Text(
                                    text = format.displayLabel,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (format == BackupExportFormat.JSON) {
                                        Icons.Default.BarChart
                                    } else {
                                        Icons.Default.TableChart
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_format_chip_${format.name.lowercase(Locale.ROOT)}")
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        onShareFile(selectedFormat)
                        onDismiss()
                    },
                    modifier = Modifier.testTag("backup_dialog_share_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share")
                }

                Button(
                    onClick = {
                        onSaveToDevice(selectedFormat)
                        onDismiss()
                    },
                    modifier = Modifier.testTag("backup_dialog_save_device_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save to Device")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
