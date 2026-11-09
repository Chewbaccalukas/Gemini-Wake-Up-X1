package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.util.VoiceCommandHistoryEntry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class HeatmapTimeBlock(
    val id: String,
    val shortLabel: String,
    val rangeLabel: String,
    val startHour: Int,
    val endHourExclusive: Int
) {
    MORNING("MORNING", "Morning", "6a–12p", 6, 12),
    AFTERNOON("AFTERNOON", "Afternoon", "12p–6p", 12, 18),
    EVENING("EVENING", "Evening", "6p–12a", 18, 24),
    NIGHT("NIGHT", "Night", "12a–6a", 0, 6);

    companion object {
        fun fromHourOfDay(hour: Int): HeatmapTimeBlock {
            return when (hour) {
                in 6..11 -> MORNING
                in 12..17 -> AFTERNOON
                in 18..23 -> EVENING
                else -> NIGHT
            }
        }
    }
}

data class WeeklyHeatmapCell(
    val dayIndex: Int, // 0 = 6 days ago ... 6 = today
    val dayShortLabel: String, // e.g., "Mon"
    val dateLabel: String, // e.g., "Nov 6"
    val timeBlock: HeatmapTimeBlock,
    val wakeTriggerCount: Int,
    val messageCount: Int,
    val activeSessionCount: Int,
    val topWakePhrase: String?,
    val intensityLevel: Int // 0..4
) {
    val totalActivityScore: Int
        get() = wakeTriggerCount * 2 + messageCount + activeSessionCount
}

fun computeWeeklyActivityHeatmap(
    chats: List<ChatSession>,
    messages: List<ChatMessage>,
    voiceCommands: List<VoiceCommandHistoryEntry> = emptyList(),
    nowMs: Long = System.currentTimeMillis()
): List<WeeklyHeatmapCell> {
    val dayFmt = SimpleDateFormat("EEE", Locale.US)
    val dateFmt = SimpleDateFormat("MMM d", Locale.US)

    val cal = Calendar.getInstance().apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val todayStartMs = cal.timeInMillis
    val dayDurationMs = 24L * 60L * 60L * 1000L
    val sevenDaysStartMs = todayStartMs - 6L * dayDurationMs

    val chatById = chats.associateBy { it.id }
    val rawCells = mutableListOf<WeeklyHeatmapCell>()
    var maxScore = 0

    for (dayOffset in 0..6) {
        val dayStart = sevenDaysStartMs + dayOffset * dayDurationMs
        val dayEnd = dayStart + dayDurationMs
        val dayDate = Date(dayStart)
        val dayShort = dayFmt.format(dayDate)
        val dateStr = dateFmt.format(dayDate)

        for (block in HeatmapTimeBlock.entries) {
            val blockCommands = voiceCommands.filter { cmd ->
                cmd.timestamp in dayStart until dayEnd &&
                    HeatmapTimeBlock.fromHourOfDay(getHourOfDay(cmd.timestamp)) == block
            }
            val blockMessages = messages.filter { msg ->
                !msg.isDeleted &&
                    msg.timestamp in dayStart until dayEnd &&
                    HeatmapTimeBlock.fromHourOfDay(getHourOfDay(msg.timestamp)) == block
            }
            val wakeMessages = blockMessages.filter { it.triggeredByWakeWord }
            val wakeTriggers = maxOf(blockCommands.size, wakeMessages.size)

            val activeChatIds = blockMessages.map { it.chatId }.toSet()
            val newlyCreatedChats = chats.count { chat ->
                !chat.isDeleted &&
                    chat.createdAt in dayStart until dayEnd &&
                    HeatmapTimeBlock.fromHourOfDay(getHourOfDay(chat.createdAt)) == block &&
                    chat.id !in activeChatIds
            }
            val sessionCount = activeChatIds.size + newlyCreatedChats

            val phraseCandidates = buildList {
                blockCommands.forEach { if (it.triggerPhrase.isNotBlank()) add(it.triggerPhrase) }
                wakeMessages.forEach { msg ->
                    val ww = chatById[msg.chatId]?.wakeWord
                    if (!ww.isNullOrBlank()) add(ww)
                }
            }
            val topPhrase = phraseCandidates
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }
                ?.key

            val cell = WeeklyHeatmapCell(
                dayIndex = dayOffset,
                dayShortLabel = dayShort,
                dateLabel = dateStr,
                timeBlock = block,
                wakeTriggerCount = wakeTriggers,
                messageCount = blockMessages.size,
                activeSessionCount = sessionCount,
                topWakePhrase = topPhrase,
                intensityLevel = 0
            )
            if (cell.totalActivityScore > maxScore) {
                maxScore = cell.totalActivityScore
            }
            rawCells.add(cell)
        }
    }

    return rawCells.map { cell ->
        val score = cell.totalActivityScore
        val level = when {
            score <= 0 || maxScore <= 0 -> 0
            score >= (maxScore * 0.75) -> 4
            score >= (maxScore * 0.50) -> 3
            score >= (maxScore * 0.25) -> 2
            else -> 1
        }
        cell.copy(intensityLevel = level)
    }
}

private fun getHourOfDay(timestampMs: Long): Int {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = timestampMs
    return calendar.get(Calendar.HOUR_OF_DAY)
}

@Composable
fun WeeklyActivityHeatmapCard(
    heatmapCells: List<WeeklyHeatmapCell>,
    peakSlotSummary: String,
    onShareCsvClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val defaultSelected = remember(heatmapCells) {
        heatmapCells.maxByOrNull { it.totalActivityScore } ?: heatmapCells.lastOrNull()
    }
    var selectedCell by remember(heatmapCells) { mutableStateOf(defaultSelected) }

    val daysByIndex = remember(heatmapCells) {
        heatmapCells.groupBy { it.dayIndex }.toSortedMap()
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("weekly_activity_heatmap_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.GridView,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Weekly Activity Heatmap",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "7 days × 4 time blocks • Tap any slot to inspect",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (onShareCsvClick != null) {
                    FilledTonalIconButton(
                        onClick = onShareCsvClick,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("heatmap_share_csv_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share Heatmap & Session CSV",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Column headers (7 days)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(68.dp))
                daysByIndex.forEach { (_, cellsForDay) ->
                    val dayHeader = cellsForDay.firstOrNull()?.dayShortLabel ?: ""
                    Text(
                        text = dayHeader,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 4 rows for Morning, Afternoon, Evening, Night
            HeatmapTimeBlock.entries.forEach { block ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.width(68.dp)) {
                        Text(
                            text = block.shortLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = block.rangeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    daysByIndex.forEach { (dayIdx, cellsForDay) ->
                        val cell = cellsForDay.find { it.timeBlock == block }
                        val isSelected = selectedCell?.dayIndex == dayIdx && selectedCell?.timeBlock == block
                        val cellColor = heatmapLevelColor(cell?.intensityLevel ?: 0)

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(30.dp)
                                .padding(horizontal = 2.dp)
                                .clip(RoundedCornerShape(7.dp))
                                .background(cellColor)
                                .then(
                                    if (isSelected) {
                                        Modifier.border(
                                            width = 2.dp,
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = RoundedCornerShape(7.dp)
                                        )
                                    } else {
                                        Modifier
                                    }
                                )
                                .clickable {
                                    if (cell != null) {
                                        selectedCell = cell
                                    }
                                }
                                .testTag("heatmap_cell_${dayIdx}_${block.id}"),
                            contentAlignment = Alignment.Center
                        ) {
                            val totalEvents = (cell?.wakeTriggerCount ?: 0) + (cell?.messageCount ?: 0)
                            if (totalEvents > 0) {
                                Text(
                                    text = totalEvents.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if ((cell?.intensityLevel ?: 0) >= 3) {
                                        MaterialTheme.colorScheme.onPrimary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Intensity legend + Peak Slot badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "Less",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    (0..4).forEach { lvl ->
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(heatmapLevelColor(lvl))
                        )
                    }
                    Text(
                        text = "More",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Timeline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = peakSlotSummary,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Selected slot inspection bar
            selectedCell?.let { cell ->
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("heatmap_selected_slot_detail")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${cell.dayShortLabel} (${cell.dateLabel}) • ${cell.timeBlock.shortLabel} (${cell.timeBlock.rangeLabel})",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            val phraseLabel = cell.topWakePhrase?.let { "Top wake: \"$it\"" } ?: "No wake triggers in slot"
                            Text(
                                text = "${cell.wakeTriggerCount} wake triggers • ${cell.messageCount} messages • ${cell.activeSessionCount} chats • $phraseLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun heatmapLevelColor(level: Int): Color {
    return when (level) {
        0 -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.55f)
        1 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        2 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.48f)
        3 -> MaterialTheme.colorScheme.primary.copy(alpha = 0.74f)
        else -> MaterialTheme.colorScheme.primary
    }
}
