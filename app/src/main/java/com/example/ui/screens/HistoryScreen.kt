package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.ui.components.WakeWordSessionBarChartCard
import com.example.ui.components.WeeklyActivityHeatmapCard
import com.example.ui.components.computeWeeklyActivityHeatmap
import com.example.ui.components.getIconForName
import com.example.util.VoiceCommandHistoryEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val MAX_HISTORY_RENDER_ITEMS = 100

@Suppress("USELESS_ELVIS")
private fun safeText(value: String?, fallback: String = ""): String {
    return try {
        (value ?: fallback).trim().ifBlank { fallback }
    } catch (_: Throwable) {
        fallback
    }
}

private fun parseSafeAccentColor(hex: String?): Color {
    if (hex.isNullOrBlank()) return Color(0xFF38BDF8)
    return try {
        Color(android.graphics.Color.parseColor(hex.trim()))
    } catch (_: Throwable) {
        Color(0xFF38BDF8)
    }
}

private fun formatSafeHistoryTime(timestamp: Long?): String {
    return try {
        val raw = timestamp ?: 0L
        val safeTs = if (raw > 0L) raw else System.currentTimeMillis()
        val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
        sdf.format(Date(safeTs))
    } catch (_: Throwable) {
        "Just now"
    }
}

private sealed interface SafeHistoryState {
    data class Ready(
        val messages: List<ChatMessage>,
        val voiceCommands: List<VoiceCommandHistoryEntry>,
        val chatMap: Map<Long, ChatSession>
    ) : SafeHistoryState

    data class Unavailable(val reason: String = "History unavailable") : SafeHistoryState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    messages: List<ChatMessage>?,
    chats: List<ChatSession>?,
    onBack: () -> Unit,
    onDeleteMessage: (Long) -> Unit,
    onNavigateToChat: (Long) -> Unit,
    onClearCorruptedHistory: () -> Unit = {},
    onShareLogsAsJson: () -> Unit = {},
    onShareLogsAsCsv: () -> Unit = {},
    onOpenBackupExportDialog: (() -> Unit)? = null,
    sessionStatisticsState: com.example.ui.SessionStatisticsUiState? = null,
    onSelectStatisticsDateRange: ((com.example.ui.components.StatisticsDateRange) -> Unit)? = null,
    voiceCommands: List<VoiceCommandHistoryEntry>? = emptyList(),
    onDeleteVoiceCommand: (String) -> Unit = {},
    hasHistoryError: Boolean = false
) {
    BackHandler { onBack() }

    var searchQuery by remember { mutableStateOf("") }

    val safeState: SafeHistoryState = remember(messages, chats, voiceCommands, searchQuery, hasHistoryError) {
        if (hasHistoryError || messages == null) {
            SafeHistoryState.Unavailable("History unavailable")
        } else {
            try {
                val query = safeText(searchQuery)
                val boundedMessages = messages
                    .asSequence()
                    .filterNotNull()
                    .filter { msg ->
                        val content = safeText(msg.content)
                        val hasValidText = content.isNotBlank() && !ChatMessage.isLegacyAudioPlaceholder(content)
                        val hasNonAudioAttachment = !safeText(msg.attachmentName).isBlank() && !msg.isAudioPlaceholderAttachment
                        if (!hasValidText && !hasNonAudioAttachment) {
                            false
                        } else if (query.isBlank()) {
                            true
                        } else {
                            content.contains(query, ignoreCase = true) ||
                                safeText(msg.attachmentName).contains(query, ignoreCase = true)
                        }
                    }
                    .take(MAX_HISTORY_RENDER_ITEMS)
                    .toList()

                val boundedVoiceCommands = (voiceCommands ?: emptyList())
                    .asSequence()
                    .filterNotNull()
                    .filter { cmd ->
                        val transcript = safeText(cmd.recognizedTranscript)
                        val trigger = safeText(cmd.triggerPhrase)
                        val hasValidTranscript = transcript.isNotBlank() && !ChatMessage.isLegacyAudioPlaceholder(transcript)
                        val hasValidTrigger = trigger.isNotBlank() && !ChatMessage.isLegacyAudioPlaceholder(trigger)
                        if (!hasValidTranscript && !hasValidTrigger) {
                            false
                        } else if (query.isBlank()) {
                            true
                        } else {
                            transcript.contains(query, ignoreCase = true) ||
                                trigger.contains(query, ignoreCase = true) ||
                                safeText(cmd.chatTitle).contains(query, ignoreCase = true)
                        }
                    }
                    .take(MAX_HISTORY_RENDER_ITEMS)
                    .toList()

                val safeChatMap = (chats ?: emptyList())
                    .filterNotNull()
                    .associateBy { it.id }

                SafeHistoryState.Ready(
                    messages = boundedMessages,
                    voiceCommands = boundedVoiceCommands,
                    chatMap = safeChatMap
                )
            } catch (_: Throwable) {
                SafeHistoryState.Unavailable("History unavailable")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("history_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Conversation History",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (onOpenBackupExportDialog != null) {
                                onOpenBackupExportDialog()
                            } else {
                                onShareLogsAsJson()
                            }
                        },
                        modifier = Modifier.testTag("export_share_logs_json_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Export JSON or CSV Backup",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Export JSON/CSV",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                    TextButton(
                        onClick = onClearCorruptedHistory,
                        modifier = Modifier.testTag("clear_corrupted_history_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CleaningServices,
                            contentDescription = "Clear Corrupted History",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Clear Corrupted History",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onShareLogsAsCsv,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.testTag("history_fab_share_csv_statistics")
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "Share Session Statistics CSV via FileProvider"
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search Bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search through past turns...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("history_search_field"),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    singleLine = true
                )
            }

            when (val current = safeState) {
                is SafeHistoryState.Unavailable -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = "History unavailable",
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = current.reason,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("history_unavailable_text")
                            )
                        }
                    }
                }

                is SafeHistoryState.Ready -> {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("history_lazy_column")
                    ) {
                        item(key = "history_wake_session_bar_chart") {
                            WakeWordSessionBarChartCard(
                                chats = current.chatMap.values.toList(),
                                messages = current.messages,
                                voiceCommands = current.voiceCommands,
                                statisticsState = sessionStatisticsState,
                                onSelectDateRange = onSelectStatisticsDateRange,
                                onOpenExportDialog = onOpenBackupExportDialog,
                                onQuickShareCsv = onShareLogsAsCsv
                            )
                        }

                        item(key = "history_weekly_activity_heatmap") {
                            val heatmapCells = remember(sessionStatisticsState, current) {
                                sessionStatisticsState?.weeklyHeatmapCells?.takeIf { it.isNotEmpty() }
                                    ?: computeWeeklyActivityHeatmap(
                                        chats = current.chatMap.values.toList(),
                                        messages = current.messages,
                                        voiceCommands = current.voiceCommands
                                    )
                            }
                            val peakLabel = sessionStatisticsState?.peakHeatmapSlotLabel ?: "Peak Activity Slot"
                            WeeklyActivityHeatmapCard(
                                heatmapCells = heatmapCells,
                                peakSlotSummary = peakLabel,
                                onShareCsvClick = onShareLogsAsCsv
                            )
                        }

                        if (current.messages.isEmpty() && current.voiceCommands.isEmpty()) {
                            item(key = "history_empty_state") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 48.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            Icons.Default.History,
                                            contentDescription = null,
                                            modifier = Modifier.size(64.dp),
                                            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                        )
                                        Spacer(modifier = Modifier.height(16.dp))
                                        Text(
                                            text = if (searchQuery.isBlank()) "No history recorded yet" else "No matches found for \"$searchQuery\"",
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        } else {
                            if (current.voiceCommands.isNotEmpty()) {
                                itemsIndexed(
                                    items = current.voiceCommands,
                                    key = { index, cmd -> "voice_cmd_${safeText(cmd.id, index.toString())}_$index" }
                                ) { _, cmd ->
                                    VoiceCommandHistoryCard(
                                        entry = cmd,
                                        onDelete = { onDeleteVoiceCommand(safeText(cmd.id)) }
                                    )
                                }
                            }

                            itemsIndexed(
                                items = current.messages,
                                key = { index, item -> "msg_${item.id}_${item.chatId}_$index" }
                            ) { _, message ->
                                val chat = current.chatMap[message.chatId]
                                HistoryMessageItem(
                                    message = message,
                                    chat = chat,
                                    onDelete = { onDeleteMessage(message.id) },
                                    onClick = { onNavigateToChat(message.chatId) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VoiceCommandHistoryCard(
    entry: VoiceCommandHistoryEntry,
    onDelete: () -> Unit
) {
    val safeTranscript = remember(entry) {
        val rawTranscript = safeText(entry.recognizedTranscript)
        val cleanTranscript = if (!ChatMessage.isLegacyAudioPlaceholder(rawTranscript)) rawTranscript else ""
        val rawTrigger = safeText(entry.triggerPhrase)
        val cleanTrigger = if (!ChatMessage.isLegacyAudioPlaceholder(rawTrigger)) rawTrigger else ""
        cleanTranscript.ifBlank { cleanTrigger }
    }
    if (safeTranscript.isBlank()) {
        return
    }
    val safeTrigger = remember(entry) {
        val rawTrigger = safeText(entry.triggerPhrase)
        if (!ChatMessage.isLegacyAudioPlaceholder(rawTrigger)) rawTrigger else "Voice Trigger"
    }
    val safeChatTitle = remember(entry) {
        safeText(entry.chatTitle, "Hands-Free Command")
    }
    val timeFormatted = remember(entry) {
        formatSafeHistoryTime(entry.timestamp)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("voice_command_history_item_${safeText(entry.id)}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Spoken command",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "$safeChatTitle • \"$safeTrigger\"",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = timeFormatted,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(24.dp)
                            .testTag("delete_voice_command_${safeText(entry.id)}")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete voice command",
                            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = safeTranscript,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag("voice_command_transcript_text_${safeText(entry.id)}")
            )
        }
    }
}

@Composable
fun HistoryMessageItem(
    message: ChatMessage,
    chat: ChatSession?,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    val timeFormatted = remember(message.timestamp) {
        formatSafeHistoryTime(message.timestamp)
    }

    val accentColor = remember(chat?.themeColorHex) {
        if (chat != null) parseSafeAccentColor(chat.themeColorHex)
        else Color.Gray
    }

    val safeContent = remember(message.content, message.attachmentName) {
        val raw = message.sanitizedTextContent
        if (raw.isNotBlank()) {
            raw
        } else {
            val att = safeText(message.attachmentName)
            if (att.isNotBlank() && !message.isAudioPlaceholderAttachment) "Attached: $att" else ""
        }
    }

    if (safeContent.isBlank()) {
        return
    }

    val safeSender = remember(message.sender) {
        safeText(message.sender, "user")
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .testTag("history_item_${message.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.1f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(accentColor.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = getIconForName(safeText(chat?.iconName, "Sparkles")),
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = safeText(chat?.title, "Deleted Chat"),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = accentColor
                    )
                }
                Text(
                    text = timeFormatted,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.Top) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            if (safeSender == "user") MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.secondaryContainer
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (safeSender == "user") "U" else "G",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = if (safeSender == "user") MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    if (safeContent.isNotBlank()) {
                        Text(
                            text = safeContent,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("history_item_text_${message.id}")
                        )
                    }
                    if (message.triggeredByWakeWord && safeContent.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Mic,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Hands-Free Trigger",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(24.dp)
                        .testTag("delete_history_item_${message.id}")
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete from history",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
