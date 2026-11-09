package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.VoiceCommandHistoryEntry
import com.example.util.VoiceCommandType
import com.example.util.WebLinkHistoryEntry
import com.example.util.WebLinkSourceType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun formatSafeTimestamp(timestamp: Long, pattern: String): String {
    return try {
        val safeTs = if (timestamp > 0L) timestamp else System.currentTimeMillis()
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(safeTs))
    } catch (_: Throwable) {
        "Just now"
    }
}

/**
 * Home Screen Web Search Bar with a swipe-down / dropdown History Menu featuring two dedicated tabs:
 * - Tab 0: Web & Links (Web Search + Gemini Chat Link history)
 * - Tab 1: Voice Commands (Recent voice commands and their recognized text transcripts)
 */
@Composable
fun WebSearchAndHistoryBar(
    historyEntries: List<WebLinkHistoryEntry>,
    onSubmitSearchOrUrl: (String) -> Unit,
    onOpenHistoryEntry: (WebLinkHistoryEntry) -> Unit,
    onDeleteHistoryEntry: (String) -> Unit,
    onClearAllHistory: () -> Unit,
    modifier: Modifier = Modifier,
    voiceCommandEntries: List<VoiceCommandHistoryEntry> = emptyList(),
    onUseVoiceCommandTranscript: (VoiceCommandHistoryEntry) -> Unit = {},
    onDeleteVoiceCommandEntry: (String) -> Unit = {},
    onClearAllVoiceCommands: () -> Unit = {},
    onClearCorruptedHistory: () -> Unit = {},
    showSearchInput: Boolean = true,
    isHistoryExpandedExternal: Boolean? = null,
    onSetHistoryExpandedExternal: ((Boolean) -> Unit)? = null,
    onTextInputFocusChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var internalExpanded by rememberSaveable { mutableStateOf(false) }
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val isHistoryExpanded = isHistoryExpandedExternal ?: internalExpanded
    val setHistoryExpanded: (Boolean) -> Unit = { newValue ->
        internalExpanded = newValue
        onSetHistoryExpandedExternal?.invoke(newValue)
    }

    val validVoiceCommandEntries = remember(voiceCommandEntries) {
        voiceCommandEntries.filter { entry ->
            val transcript = entry.recognizedTranscript.trim()
            val trigger = entry.triggerPhrase.trim()
            (transcript.isNotBlank() && !com.example.data.model.ChatMessage.isLegacyAudioPlaceholder(transcript)) ||
                (trigger.isNotBlank() && !com.example.data.model.ChatMessage.isLegacyAudioPlaceholder(trigger))
        }
    }
    var cumulativeDragY by remember { mutableFloatStateOf(0f) }
    val totalHistoryCount = historyEntries.size + validVoiceCommandEntries.size

    Column(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(isHistoryExpanded) {
                detectVerticalDragGestures(
                    onDragStart = { cumulativeDragY = 0f },
                    onVerticalDrag = { _, dragAmount ->
                        cumulativeDragY += dragAmount
                        if (cumulativeDragY > 28f && !isHistoryExpanded) {
                            setHistoryExpanded(true)
                        } else if (cumulativeDragY < -28f && isHistoryExpanded) {
                            setHistoryExpanded(false)
                        }
                    }
                )
            }
            .testTag("home_web_search_bar_container")
    ) {
        if (showSearchInput) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                border = BorderStroke(
                    1.dp,
                    if (isHistoryExpanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                    else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // History Swipe-Down / Dropdown Trigger Button
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = if (isHistoryExpanded) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .clickable { setHistoryExpanded(!isHistoryExpanded) }
                                .testTag("toggle_web_history_dropdown_button")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = "Web Link & Voice Command History Menu",
                                    tint = if (isHistoryExpanded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(16.dp)
                                )
                                if (totalHistoryCount > 0) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = totalHistoryCount.toString(),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (isHistoryExpanded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                                Spacer(modifier = Modifier.width(2.dp))
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = if (isHistoryExpanded) "Collapse History" else "Expand History",
                                    tint = if (isHistoryExpanded) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .rotate(if (isHistoryExpanded) 180f else 0f)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text(
                                    text = "Search web or enter URL (swipe down for history)...",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = {
                                    val trimmed = searchQuery.trim()
                                    if (trimmed.isNotBlank()) {
                                        onSubmitSearchOrUrl(trimmed)
                                        searchQuery = ""
                                    }
                                }
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { onTextInputFocusChanged(it.isFocused) }
                                .testTag("home_web_search_textfield")
                        )

                        if (searchQuery.isNotBlank()) {
                            IconButton(
                                onClick = { searchQuery = "" },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("clear_web_search_input_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Clear search text",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                val trimmed = searchQuery.trim()
                                if (trimmed.isNotBlank()) {
                                    onSubmitSearchOrUrl(trimmed)
                                    searchQuery = ""
                                } else {
                                    setHistoryExpanded(!isHistoryExpanded)
                                }
                            },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(
                                    if (searchQuery.isNotBlank()) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surface
                                )
                                .testTag("home_web_search_submit_button")
                        ) {
                            Icon(
                                imageVector = if (searchQuery.isNotBlank()) Icons.Default.OpenInBrowser else Icons.Default.Search,
                                contentDescription = "Search or open in default browser",
                                tint = if (searchQuery.isNotBlank()) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Subtle swipe-down handle indicator
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { setHistoryExpanded(!isHistoryExpanded) }
                            .padding(bottom = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(32.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                        )
                    }
                }
            }
        }

        // Swipe-down / Dropdown History Menu with Dedicated Tabs
        AnimatedVisibility(
            visible = isHistoryExpanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .testTag("web_link_history_dropdown_menu"),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    // Dedicated Tab Selector: Web & Links vs Voice Commands
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            HistoryMenuTabPill(
                                selected = selectedTabIndex == 0,
                                icon = Icons.Default.Public,
                                label = "Web & Links",
                                count = historyEntries.size,
                                onClick = { selectedTabIndex = 0 },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("history_tab_web_links")
                            )
                            HistoryMenuTabPill(
                                selected = selectedTabIndex == 1,
                                icon = Icons.Default.RecordVoiceOver,
                                label = "Voice Commands",
                                count = validVoiceCommandEntries.size,
                                onClick = { selectedTabIndex = 1 },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("history_tab_voice_commands")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (selectedTabIndex == 0) {
                        // TAB 0: WEB LINK & SEARCH HISTORY
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Public,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Web Link & Search History",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "Tap any search or Gemini chat link to open in default browser",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (historyEntries.isNotEmpty()) {
                                    TextButton(
                                        onClick = onClearAllHistory,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.testTag("clear_web_history_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteSweep,
                                            contentDescription = "Clear All Web History",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Clear",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { setHistoryExpanded(false) },
                                    modifier = Modifier
                                        .size(28.dp)
                                        .testTag("close_web_history_dropdown_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close history menu",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        if (historyEntries.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 18.dp)
                                    .testTag("web_history_empty_state"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No web searches or chat links opened yet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                                    .testTag("web_link_history_list"),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                itemsIndexed(
                                    items = historyEntries,
                                    key = { index, item -> "${item.id}_$index" }
                                ) { _, entry ->
                                    WebLinkHistoryRow(
                                        entry = entry,
                                        onClick = { onOpenHistoryEntry(entry) },
                                        onDelete = { onDeleteHistoryEntry(entry.id) }
                                    )
                                }
                            }
                        }
                    } else {
                        // TAB 1: VOICE COMMANDS & RECOGNIZED TRANSCRIPTS
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Voice Commands & Transcripts",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "Recent wake words, voice commands, and recognized text transcripts",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = {
                                        onClearCorruptedHistory()
                                        Toast.makeText(context, "Corrupted history records repaired & cleared", Toast.LENGTH_SHORT).show()
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("repair_voice_history_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CleaningServices,
                                        contentDescription = "Clear Corrupted History",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Repair",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (validVoiceCommandEntries.isNotEmpty()) {
                                    TextButton(
                                        onClick = onClearAllVoiceCommands,
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.testTag("clear_voice_history_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteSweep,
                                            contentDescription = "Clear All Voice Command History",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "Clear",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { setHistoryExpanded(false) },
                                    modifier = Modifier
                                        .size(28.dp)
                                        .testTag("close_web_history_dropdown_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Close history menu",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        if (validVoiceCommandEntries.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 18.dp)
                                    .testTag("voice_history_empty_state"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No voice commands or speech transcripts recorded yet.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp)
                                    .testTag("voice_command_history_list"),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                itemsIndexed(
                                    items = validVoiceCommandEntries,
                                    key = { index, item -> "${item.id}_$index" }
                                ) { _, entry ->
                                    VoiceCommandHistoryRow(
                                        entry = entry,
                                        onClick = {
                                            searchQuery = entry.recognizedTranscript
                                            onUseVoiceCommandTranscript(entry)
                                        },
                                        onCopy = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            val clip = ClipData.newPlainText("Voice Transcript", entry.recognizedTranscript)
                                            clipboard.setPrimaryClip(clip)
                                            Toast.makeText(context, "Voice transcript copied to clipboard", Toast.LENGTH_SHORT).show()
                                        },
                                        onDelete = { onDeleteVoiceCommandEntry(entry.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryMenuTabPill(
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
                ),
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (count > 0) {
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.22f)
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                ) {
                    Text(
                        text = count.toString(),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceCommandHistoryRow(
    entry: VoiceCommandHistoryEntry,
    onClick: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val timeFormatted = remember(entry.timestamp) {
        formatSafeTimestamp(entry.timestamp, "MMM d, h:mm:ss a")
    }

    val (icon, badgeColor, badgeTextColor) = when (entry.commandType) {
        VoiceCommandType.WAKE_WORD -> Triple(
            Icons.Default.Hearing,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        VoiceCommandType.DICTATE -> Triple(
            Icons.Default.RecordVoiceOver,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        VoiceCommandType.VOICE_SEND -> Triple(
            Icons.Default.Mic,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        VoiceCommandType.VOICE_TURN -> Triple(
            Icons.Default.GraphicEq,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        VoiceCommandType.HEARTBEAT -> Triple(
            Icons.Default.Favorite,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
        VoiceCommandType.SLEEP -> Triple(
            Icons.Default.Hearing,
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer
        )
        VoiceCommandType.WAKE_UP -> Triple(
            Icons.Default.Mic,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("voice_history_item_${entry.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(badgeColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = entry.commandType.displayLabel,
                    tint = badgeTextColor,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeColor.copy(alpha = 0.8f)
                    ) {
                        Text(
                            text = entry.commandType.displayLabel,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = badgeTextColor,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                    Text(
                        text = "\"${entry.triggerPhrase}\"",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!entry.chatTitle.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Text(
                                text = "Chat: ${entry.chatTitle}",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = entry.recognizedTranscript,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = timeFormatted,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onCopy,
                modifier = Modifier
                    .size(28.dp)
                    .testTag("copy_voice_history_item_${entry.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = "Copy voice transcript",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp)
                )
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(26.dp)
                    .testTag("delete_voice_history_item_${entry.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove voice command from history",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun WebLinkHistoryRow(
    entry: WebLinkHistoryEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val timeFormatted = remember(entry.timestamp) {
        formatSafeTimestamp(entry.timestamp, "MMM d, h:mm a")
    }

    val (icon, badgeColor, badgeTextColor) = when (entry.sourceType) {
        WebLinkSourceType.WEB_SEARCH -> Triple(
            Icons.Default.Search,
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer
        )
        WebLinkSourceType.CHAT_LINK -> Triple(
            Icons.Default.AutoAwesome,
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer
        )
        WebLinkSourceType.EXTERNAL_LINK -> Triple(
            Icons.Default.Language,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer
        )
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .testTag("web_history_item_${entry.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(badgeColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = entry.sourceType.displayLabel,
                    tint = badgeTextColor,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.titleOrQuery,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeColor.copy(alpha = 0.75f)
                    ) {
                        Text(
                            text = if (!entry.chatTitle.isNullOrBlank()) "Chat: ${entry.chatTitle}" else entry.sourceType.displayLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = badgeTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                Text(
                    text = "${entry.url} • $timeFormatted",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Icon(
                imageVector = Icons.Default.OpenInNew,
                contentDescription = "Open in default browser",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(16.dp)
            )

            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(26.dp)
                    .testTag("delete_web_history_item_${entry.id}")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove from history",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
