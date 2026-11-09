package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.WakeWordSample

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.model.ChatSession

val AvailableIcons = listOf(
    "Sparkles" to Icons.Default.AutoAwesome,
    "Code" to Icons.Default.Code,
    "Lightbulb" to Icons.Default.Lightbulb,
    "Chat" to Icons.Default.Chat,
    "Book" to Icons.Default.Book,
    "Fitness" to Icons.Default.FitnessCenter
)

val AvailableColorHexes = listOf(
    "#38BDF8", // Cyan
    "#818CF8", // Indigo
    "#A855F7", // Purple
    "#10B981", // Emerald
    "#F59E0B", // Amber
    "#EC4899"  // Pink
)

fun getIconForName(name: String): ImageVector {
    return AvailableIcons.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
        ?: Icons.Default.AutoAwesome
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateEditChatDialog(
    initialChat: ChatSession? = null,
    existingChats: List<ChatSession> = emptyList(),
    defaultPersistence: Boolean = true,
    isEnrollmentRecording: Boolean = false,
    activeRecordingSlot: Int? = null,
    currentlyPlayingPath: String? = null,
    recordingDurationMs: Long = 0L,
    playbackProgress: Float = 0f,
    playbackPositionMs: Int = 0,
    playbackDurationMs: Int = 0,
    isSnippetPaused: Boolean = false,
    autoStopLimitSec: Int = 3,
    onSetAutoStopLimit: (Int) -> Unit = {},
    onStartSnippetRecording: (Long, Int) -> Unit = { _, _ -> },
    onStopSnippetRecording: (Long, Int) -> Unit = { _, _ -> },
    onPlaySnippetPreview: (String) -> Unit = {},
    onPauseSnippetPreview: () -> Unit = {},
    onResumeSnippetPreview: () -> Unit = {},
    onStopSnippetPreview: () -> Unit = {},
    onPlayAllSnippets: () -> Unit = {},
    onDeleteSampleById: (Long, Long, String) -> Unit = { _, _, _ -> },
    onStartEnrollment: ((ShortArray) -> Unit) -> Unit = {},
    onStopEnrollment: () -> Unit = {},
    onSaveSample: (Long, Int, ShortArray) -> Unit = { _, _, _ -> },
    onDeleteSamples: (Long) -> Unit = {},
    getSamplesFlow: (Long) -> kotlinx.coroutines.flow.Flow<List<com.example.data.model.WakeWordSample>> = { kotlinx.coroutines.flow.flowOf(emptyList()) },
    initialCategory: String = "Work",
    initialTags: Set<String> = emptySet(),
    availableCategories: List<String> = com.example.util.AppSettingsManager.DEFAULT_CHAT_CATEGORIES,
    onSaveCategoryAndTags: (category: String, tags: Set<String>) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
    onSave: (ChatSession) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    
    val chatSamples by (if (initialChat != null) getSamplesFlow(initialChat.id) else kotlinx.coroutines.flow.flowOf(emptyList()))
        .collectAsStateWithLifecycle(initialValue = emptyList())

    var title by remember { mutableStateOf(initialChat?.title ?: "") }
    var wakeWord by remember { mutableStateOf(initialChat?.wakeWord ?: "") }
    var systemInstruction by remember {
        mutableStateOf(
            initialChat?.systemInstruction ?: "You are Gemini, a helpful and brilliant AI companion."
        )
    }
    var greetingMessage by remember { mutableStateOf(initialChat?.greetingMessage ?: "") }
    var isDefaultWakeChat by remember { mutableStateOf(initialChat?.isDefaultWakeChat ?: false) }
    var isPersistent by remember { mutableStateOf(initialChat?.isPersistent ?: defaultPersistence) }
    var selectedIcon by remember { mutableStateOf(initialChat?.iconName ?: "Sparkles") }
    var selectedColor by remember { mutableStateOf(initialChat?.themeColorHex ?: "#38BDF8") }
    var selectedThemeMode by remember { mutableStateOf(initialChat?.themeMode ?: com.example.ui.theme.AppTheme.SYSTEM) }
    var selectedCategory by remember(initialChat?.id, initialCategory) {
        mutableStateOf(initialCategory.ifBlank { "Work" })
    }
    var customCategoryInput by remember { mutableStateOf("") }
    var selectedTags by remember(initialChat?.id, initialTags) {
        mutableStateOf(initialTags)
    }
    var customTagInput by remember { mutableStateOf("") }

    var titleError by remember { mutableStateOf(false) }
    var wakeWordError by remember { mutableStateOf(false) }
    var duplicateWakeWordConflictTitle by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialChat == null) "New Gemini Chat" else "Edit Chat & Wake Word",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        titleError = false
                    },
                    label = { Text("Chat Title") },
                    placeholder = { Text("e.g. Travel Planner, Code Mode") },
                    isError = titleError,
                    supportingText = if (titleError) {
                        { Text("Title cannot be empty") }
                    } else null,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chat_title_input")
                )

                OutlinedTextField(
                    value = wakeWord,
                    onValueChange = {
                        wakeWord = it
                        wakeWordError = false
                        duplicateWakeWordConflictTitle = null
                    },
                    label = { Text("Unique Wake Word / Phrase") },
                    placeholder = { Text("e.g. \"Hey Gemini\", \"Code Mode\"") },
                    leadingIcon = {
                        Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    isError = wakeWordError || duplicateWakeWordConflictTitle != null,
                    supportingText = {
                        Text(
                            when {
                                wakeWordError -> "Wake word cannot be empty"
                                duplicateWakeWordConflictTitle != null ->
                                    "Wake word \"${wakeWord.trim()}\" is already used by \"$duplicateWakeWordConflictTitle\". Each chat session must have a unique wake word."
                                else -> "Each chat session has its own unique wake word that opens it directly"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chat_wakeword_input")
                )

                if (duplicateWakeWordConflictTitle != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("duplicate_wake_word_error_banner"),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "Duplicate Wake Word Blocked: \"${wakeWord.trim()}\" is already assigned to session \"$duplicateWakeWordConflictTitle\". Please enter a unique wake word for this chat session.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Default Wake Target",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Opens this chat when generic \"Hey Gemini\" is spoken",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = isDefaultWakeChat,
                        onCheckedChange = { isDefaultWakeChat = it },
                        modifier = Modifier.testTag("default_wake_switch")
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isPersistent) "Persistent Disk Storage" else "Ephemeral RAM Only",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (isPersistent) "History saved permanently in device database" else "Kept in temporary RAM buffer during active use",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = isPersistent,
                        onCheckedChange = { isPersistent = it },
                        modifier = Modifier.testTag("persistence_switch")
                    )
                }

                // Category / Folder & Multi-Tag Organization Section
                Text(
                    text = "Category / Folder (for Wake Word & Browse Filtering)",
                    style = MaterialTheme.typography.labelLarge
                )
                val allCategoryOptions = remember(availableCategories, selectedCategory) {
                    (com.example.util.AppSettingsManager.DEFAULT_CHAT_CATEGORIES + availableCategories + listOfNotNull(selectedCategory.takeIf { it.isNotBlank() }))
                        .map { it.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    allCategoryOptions.forEach { cat ->
                        val isSelected = selectedCategory.equals(cat, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .clickable { selectedCategory = cat }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .testTag("dialog_category_chip_${cat.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "📁 $cat",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customCategoryInput,
                        onValueChange = { customCategoryInput = it },
                        label = { Text("Custom Category / Folder") },
                        placeholder = { Text("e.g. Finance, Travel") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_custom_category_input")
                    )
                    FilledTonalButton(
                        onClick = {
                            val trimmed = customCategoryInput.trim()
                            if (trimmed.isNotBlank()) {
                                selectedCategory = trimmed
                                customCategoryInput = ""
                            }
                        },
                        modifier = Modifier.testTag("add_custom_category_button")
                    ) {
                        Text("Use")
                    }
                }

                Text(
                    text = "Tags (Multiple Allowed)",
                    style = MaterialTheme.typography.labelLarge
                )
                val allTagOptions = remember(selectedTags) {
                    (com.example.util.AppSettingsManager.DEFAULT_SUGGESTED_TAGS + selectedTags)
                        .map { it.trim().removePrefix("#").lowercase() }
                        .filter { it.isNotBlank() }
                        .distinct()
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    allTagOptions.forEach { tag ->
                        val isTagged = selectedTags.any { it.equals(tag, ignoreCase = true) }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isTagged) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                )
                                .border(
                                    width = if (isTagged) 1.5.dp else 0.dp,
                                    color = if (isTagged) MaterialTheme.colorScheme.secondary else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable {
                                    selectedTags = if (isTagged) {
                                        selectedTags.filterNot { it.equals(tag, ignoreCase = true) }.toSet()
                                    } else {
                                        selectedTags + tag
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                .testTag("dialog_tag_chip_$tag"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "#$tag",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isTagged) FontWeight.Bold else FontWeight.Normal,
                                color = if (isTagged) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = customTagInput,
                        onValueChange = { customTagInput = it },
                        label = { Text("Add Custom Tag") },
                        placeholder = { Text("e.g. sprint, important") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("chat_custom_tag_input")
                    )
                    FilledTonalButton(
                        onClick = {
                            val cleanTag = customTagInput.trim().removePrefix("#").lowercase()
                            if (cleanTag.isNotBlank()) {
                                selectedTags = selectedTags + cleanTag
                                customTagInput = ""
                            }
                        },
                        modifier = Modifier.testTag("add_custom_tag_button")
                    ) {
                        Text("+ Tag")
                    }
                }

                Text(
                    text = "Select Icon",
                    style = MaterialTheme.typography.labelLarge
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AvailableIcons.forEach { (name, iconVector) ->
                        val isSelected = selectedIcon.equals(name, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .border(
                                    width = if (isSelected) 2.dp else 0.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedIcon = name },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = iconVector,
                                contentDescription = name,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Text(
                    text = "Theme Accent",
                    style = MaterialTheme.typography.labelLarge
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AvailableColorHexes.forEach { hex ->
                        val color = try {
                            Color(android.graphics.Color.parseColor(hex))
                        } catch (_: Throwable) {
                            Color(0xFF38BDF8)
                        }
                        val isSelected = selectedColor.equals(hex, ignoreCase = true)
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (isSelected) 3.dp else 1.dp,
                                    color = if (isSelected) Color.White else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = hex }
                        )
                    }
                }

                OutlinedTextField(
                    value = greetingMessage,
                    onValueChange = { greetingMessage = it },
                    label = { Text("Custom Greeting Message") },
                    placeholder = { Text("e.g. Good morning, team! Here's what's on the agenda today.") },
                    supportingText = {
                        Text("Displayed at the top when this chat is opened (and read aloud on voice wake)")
                    },
                    trailingIcon = if (greetingMessage.isNotEmpty()) {
                        {
                            IconButton(
                                onClick = { greetingMessage = "" },
                                modifier = Modifier.testTag("clear_chat_greeting_button")
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Clear greeting")
                            }
                        }
                    } else null,
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chat_greeting_input")
                )

                OutlinedTextField(
                    value = systemInstruction,
                    onValueChange = { systemInstruction = it },
                    label = { Text("Gemini Persona & Instructions") },
                    placeholder = { Text("Tell Gemini how to behave in this chat...") },
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chat_instructions_input")
                )

                if (initialChat != null) {
                    Text(
                        text = "Wake Word Voice Enrollment (16 kHz PCM Snippets)",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        text = "Record at least 3 clear voice samples of \"${wakeWord.ifBlank { "wake phrase" }}\" (16 kHz, 16-bit Mono PCM .wav).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Optional Auto-Stop Duration Selector
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Auto-Stop Limit:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf(0, 2, 3, 5, 10).forEach { sec ->
                                val isSelected = autoStopLimitSec == sec
                                val label = if (sec == 0) "Off" else "${sec}s"
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surfaceVariant
                                        )
                                        .clickable { onSetAutoStopLimit(sec) }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                        .testTag("dialog_auto_stop_chip_$sec"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        (0 until 5).forEach { index ->
                            val sample = chatSamples.firstOrNull { it.sampleIndex == index }
                            val hasSample = sample != null
                            val isThisRecording = (activeRecordingSlot == index) || (isEnrollmentRecording && activeRecordingSlot == null)
                            val isThisPlaying = sample != null && currentlyPlayingPath == sample.samplePath

                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("wake_sample_slot_$index"),
                                colors = CardDefaults.cardColors(
                                    containerColor = when {
                                        isThisRecording -> MaterialTheme.colorScheme.errorContainer
                                        isThisPlaying -> MaterialTheme.colorScheme.secondaryContainer
                                        hasSample -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    }
                                ),
                                border = BorderStroke(
                                    width = if (isThisRecording || isThisPlaying) 2.dp else 1.dp,
                                    color = when {
                                        isThisRecording -> MaterialTheme.colorScheme.error
                                        isThisPlaying -> MaterialTheme.colorScheme.secondary
                                        hasSample -> MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
                                        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                                    }
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp, horizontal = 2.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    if (isThisRecording) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onStopSnippetRecording(initialChat.id, index) }
                                                .padding(vertical = 4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Stop,
                                                contentDescription = "Stop recording snippet",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Text(
                                                text = "STOP",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                            Text(
                                                text = String.format(java.util.Locale.US, "%.1fs", recordingDurationMs / 1000.0),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                                            )
                                        }
                                    } else if (sample != null) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Sample recorded",
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(2.dp))
                                            Text(
                                                text = if (sample.formattedDuration.isBlank()) "#${index + 1}" else "#${index + 1} • ${sample.formattedDuration}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            // Play preview button
                                            IconButton(
                                                onClick = {
                                                    if (isThisPlaying) {
                                                        if (isSnippetPaused) onResumeSnippetPreview() else onPauseSnippetPreview()
                                                    } else {
                                                        onPlaySnippetPreview(sample.samplePath)
                                                    }
                                                },
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .testTag("play_sample_button_$index")
                                            ) {
                                                Icon(
                                                    imageVector = when {
                                                        isThisPlaying && !isSnippetPaused -> Icons.Default.Pause
                                                        isThisPlaying && isSnippetPaused -> Icons.Default.PlayArrow
                                                        else -> Icons.Default.PlayArrow
                                                    },
                                                    contentDescription = if (isThisPlaying) "Pause/Resume preview" else "Play preview",
                                                    tint = if (isThisPlaying) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                            // Retake / Delete button
                                            IconButton(
                                                onClick = {
                                                    onDeleteSampleById(initialChat.id, sample.id, sample.samplePath)
                                                },
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .testTag("delete_sample_button_$index")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "Delete sample snippet",
                                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        }
                                    } else {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable(enabled = activeRecordingSlot == null) {
                                                    onStartSnippetRecording(initialChat.id, index)
                                                }
                                                .padding(vertical = 4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Mic,
                                                contentDescription = "Record snippet #$index",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "${index + 1}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Audio Preview & Audition Deck
                    if (currentlyPlayingPath != null || chatSamples.isNotEmpty()) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("snippet_player_deck"),
                            colors = CardDefaults.cardColors(
                                containerColor = if (currentlyPlayingPath != null)
                                    MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                else
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(
                                            imageVector = if (currentlyPlayingPath != null) Icons.Default.VolumeUp else Icons.Default.PlaylistPlay,
                                            contentDescription = null,
                                            tint = if (currentlyPlayingPath != null) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (currentlyPlayingPath != null) {
                                                val activeSample = chatSamples.firstOrNull { it.samplePath == currentlyPlayingPath }
                                                val slotLabel = activeSample?.let { "Sample #${it.sampleIndex + 1}" } ?: "Snippet"
                                                if (isSnippetPaused) "Paused: $slotLabel" else "Playing: $slotLabel"
                                            } else {
                                                "Audition Deck (${chatSamples.size} saved)"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }

                                    if (chatSamples.isNotEmpty()) {
                                        FilledTonalButton(
                                            onClick = {
                                                if (currentlyPlayingPath != null) {
                                                    onStopSnippetPreview()
                                                } else {
                                                    onPlayAllSnippets()
                                                }
                                            },
                                            modifier = Modifier
                                                .height(30.dp)
                                                .testTag("play_all_snippets_button"),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (currentlyPlayingPath != null) Icons.Default.Stop else Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = if (currentlyPlayingPath != null) "Stop" else "Audition All",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }

                                if (currentlyPlayingPath != null) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { playbackProgress },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(CircleShape),
                                        color = MaterialTheme.colorScheme.secondary,
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val posSec = playbackPositionMs / 1000.0
                                        val durSec = playbackDurationMs / 1000.0
                                        Text(
                                            text = String.format(java.util.Locale.US, "%.1fs / %.1fs", posSec, durSec),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            IconButton(
                                                onClick = {
                                                    if (isSnippetPaused) onResumeSnippetPreview() else onPauseSnippetPreview()
                                                },
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .testTag("preview_pause_resume_button")
                                            ) {
                                                Icon(
                                                    imageVector = if (isSnippetPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                                    contentDescription = if (isSnippetPaused) "Resume" else "Pause",
                                                    tint = MaterialTheme.colorScheme.secondary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            IconButton(
                                                onClick = onStopSnippetPreview,
                                                modifier = Modifier
                                                    .size(26.dp)
                                                    .testTag("preview_stop_deck_button")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Stop,
                                                    contentDescription = "Stop preview",
                                                    tint = MaterialTheme.colorScheme.error,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = when {
                                activeRecordingSlot != null -> {
                                    val timeLabel = String.format(java.util.Locale.US, "(%.1fs)", recordingDurationMs / 1000.0)
                                    "🔴 Recording snippet #${activeRecordingSlot + 1} $timeLabel... Tap STOP when finished"
                                }
                                currentlyPlayingPath != null -> "▶ Auditioning saved wake snippet with MediaPlayer..."
                                chatSamples.isNotEmpty() -> {
                                    val totalBytes = chatSamples.sumOf { it.sizeBytes }
                                    val sizeLabel = if (totalBytes > 0) " (${String.format(java.util.Locale.US, "%.1f KB", totalBytes / 1024.0)})" else ""
                                    "${chatSamples.size}/5 snippets recorded$sizeLabel"
                                }
                                else -> "Tap a slot to record a 1-2s snippet of your wake phrase"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                activeRecordingSlot != null -> MaterialTheme.colorScheme.error
                                currentlyPlayingPath != null -> MaterialTheme.colorScheme.secondary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.weight(1f)
                        )

                        if (chatSamples.isNotEmpty()) {
                            TextButton(
                                onClick = { onDeleteSamples(initialChat.id) },
                                modifier = Modifier.testTag("reset_all_samples_button")
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Reset All", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "Note: Wake word training is available after creating the chat.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }

                Text(
                    text = "Chat Appearance",
                    style = MaterialTheme.typography.labelLarge
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    com.example.ui.theme.AppTheme.values().forEach { theme ->
                        val isSelected = selectedThemeMode == theme
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .border(
                                    width = if (isSelected) 2.dp else 0.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedThemeMode = theme }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = theme.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isBlank()) {
                        titleError = true
                        return@Button
                    }
                    val trimmedWake = wakeWord.trim()
                    if (trimmedWake.isBlank()) {
                        wakeWordError = true
                        return@Button
                    }
                    val conflictingChat = existingChats.firstOrNull { chat ->
                        !chat.isDeleted &&
                            chat.id != (initialChat?.id ?: -1L) &&
                            chat.wakeWord.trim().equals(trimmedWake, ignoreCase = true)
                    }
                    if (conflictingChat != null) {
                        duplicateWakeWordConflictTitle = conflictingChat.title
                        return@Button
                    }

                    // Find selected theme mode from the UI state
                    // Handled by selectedThemeMode state

                    val chatToSave = initialChat?.copy(
                        title = title.trim(),
                        wakeWord = trimmedWake,
                        systemInstruction = systemInstruction.trim(),
                        greetingMessage = greetingMessage.trim(),
                        isDefaultWakeChat = isDefaultWakeChat,
                        isPersistent = isPersistent,
                        iconName = selectedIcon,
                        themeColorHex = selectedColor,
                        themeMode = selectedThemeMode,
                        lastUpdatedAt = System.currentTimeMillis()
                    ) ?: ChatSession(
                        title = title.trim(),
                        wakeWord = trimmedWake,
                        systemInstruction = systemInstruction.trim(),
                        greetingMessage = greetingMessage.trim(),
                        isDefaultWakeChat = isDefaultWakeChat,
                        isPersistent = isPersistent,
                        iconName = selectedIcon,
                        themeColorHex = selectedColor,
                        themeMode = selectedThemeMode
                    )

                    onSaveCategoryAndTags(selectedCategory.trim().ifBlank { "Work" }, selectedTags)
                    onSave(chatToSave)
                },
                modifier = Modifier.testTag("save_chat_button")
            ) {
                Text(if (initialChat == null) "Create Chat" else "Save Changes")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("cancel_chat_button")
            ) {
                Text("Cancel")
            }
        }
    )
}
