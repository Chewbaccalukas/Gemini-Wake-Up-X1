package com.example.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.ChatSession
import com.example.data.model.WakeWord
import com.example.data.model.WakeWordSample
import com.example.voice.VoiceEngineMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

private fun parseSafeWakeColor(hex: String?): Color {
    if (hex.isNullOrBlank()) return Color(0xFF38BDF8)
    return try {
        Color(android.graphics.Color.parseColor(hex.trim()))
    } catch (_: Throwable) {
        Color(0xFF38BDF8)
    }
}

@Composable
fun WakeWordSettingsDialog(
    chats: List<ChatSession>,
    isListening: Boolean,
    audioRmsDb: Float,
    statusMessage: String,
    lastHeardText: String,
    dictateTrigger: String = "Dictate",
    sendTrigger: String = "Send",
    heartbeatTrigger: String = "Heartbeat",
    isHandsFreeActive: Boolean = false,
    voiceEngineMode: VoiceEngineMode = VoiceEngineMode.GEMINI_ORION,
    isOrionVoicePlaying: Boolean = false,
    selectedVoice: String = "Fenrir",
    masterVolume: Float = 1.0f,
    autoStopLimitSec: Int = 3,
    wakeWordSensitivity: Float = 0.78f,
    activeRecordingSlot: Int? = null,
    recordingDurationMs: Long = 0L,
    currentlyPlayingPath: String? = null,
    isSnippetPaused: Boolean = false,
    notifyActiveChatReplies: Boolean = true,
    permissionsResetCount: Int = 0,
    wakeWordLogs: List<com.example.voice.WakeWordLogEntry> = emptyList(),
    configuredWakeWords: List<WakeWord> = emptyList(),
    onAddCustomWakeWord: (Long, String) -> Unit = { _, _ -> },
    onAddCustomWakeWordWithSensitivity: (Long, String, Float) -> Unit = { chatId, phrase, _ ->
        onAddCustomWakeWord(chatId, phrase)
    },
    onAddCustomWakeWordWithNewChat: (String, String, Float) -> Unit = { _, _, _ -> },
    onUpdateConfiguredWakeWord: (Long, String) -> Unit = { _, _ -> },
    onToggleConfiguredWakeWord: (Long, Boolean) -> Unit = { _, _ -> },
    onDeleteConfiguredWakeWord: (Long) -> Unit = {},
    onSetIndividualWakeWordSensitivity: (Long, Float) -> Unit = { _, _ -> },
    onReassignWakeWordToChat: (Long, Long) -> Unit = { _, _ -> },
    onReassignWakeWordToNewChat: (Long, String) -> Unit = { _, _ -> },
    onOpenChat: (Long) -> Unit = {},
    getSamplesFlow: (Long) -> Flow<List<WakeWordSample>> = { flowOf(emptyList()) },
    onStartSnippetRecording: (Long, Int) -> Unit = { _, _ -> },
    onStopSnippetRecording: (Long, Int) -> Unit = { _, _ -> },
    onPlaySnippetPreview: (String) -> Unit = {},
    onPauseSnippetPreview: () -> Unit = {},
    onResumeSnippetPreview: () -> Unit = {},
    onStopSnippetPreview: () -> Unit = {},
    onDeleteSampleById: (Long, Long, String) -> Unit = { _, _, _ -> },
    onDeleteSamples: (Long) -> Unit = {},
    onSetWakeWordSensitivity: (Float) -> Unit = {},
    onSetVoiceEngineMode: (VoiceEngineMode) -> Unit = {},
    onSelectVoice: (String) -> Unit = {},
    onPlayOrionTestSample: () -> Unit = {},
    onSetMasterVolume: (Float) -> Unit = {},
    onSetAutoStopLimit: (Int) -> Unit = {},
    onToggleNotifyActiveChatReplies: (Boolean) -> Unit = {},
    onResetPermissions: () -> Unit = {},
    onRequestRequiredPermissions: () -> Unit = {},
    onUpdateDictateTrigger: (String) -> Unit = {},
    onUpdateSendTrigger: (String) -> Unit = {},
    onUpdateHeartbeatTrigger: (String) -> Unit = {},
    onToggleHandsFree: (Boolean) -> Unit = {},
    onResetTriggers: () -> Unit = {},
    onManualHeartbeatPing: () -> Unit = {},
    onOpenWakeWordStudio: () -> Unit = {},
    onToggleListening: () -> Unit,
    onDismiss: () -> Unit
) {
    var dictateInput by remember(dictateTrigger) { mutableStateOf(dictateTrigger) }
    var sendInput by remember(sendTrigger) { mutableStateOf(sendTrigger) }
    var heartbeatInput by remember(heartbeatTrigger) { mutableStateOf(heartbeatTrigger) }
    var newWakePhraseInput by remember { mutableStateOf("") }
    var newWakeChatTitleInput by remember { mutableStateOf("") }
    var createNewChatForWakeWord by remember(chats.isEmpty()) { mutableStateOf(chats.isEmpty()) }
    var newWakeSensitivity by remember(wakeWordSensitivity) {
        mutableStateOf(wakeWordSensitivity.coerceIn(0.50f, 0.98f))
    }
    var editingWakeWordId by remember { mutableStateOf<Long?>(null) }
    var editingWakeWordDraft by remember { mutableStateOf("") }
    var reassignNewChatTitleDraft by remember { mutableStateOf("") }

    var showSelectTargetChatDialog by remember { mutableStateOf(false) }
    var rebindTargetDialogWakeWord by remember { mutableStateOf<WakeWord?>(null) }

    val defaultTargetChat = remember(chats) {
        chats.firstOrNull { it.isDefaultWakeChat } ?: chats.firstOrNull()
    }
    var selectedEnrollmentChatId by remember(defaultTargetChat?.id) {
        mutableStateOf(defaultTargetChat?.id ?: 0L)
    }
    val enrollmentChat = remember(chats, selectedEnrollmentChatId) {
        chats.firstOrNull { it.id == selectedEnrollmentChatId } ?: defaultTargetChat
    }
    val enrolledSamples by remember(enrollmentChat?.id) {
        if (enrollmentChat != null && enrollmentChat.id > 0L) {
            getSamplesFlow(enrollmentChat.id)
        } else {
            flowOf(emptyList())
        }
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    if (showSelectTargetChatDialog || rebindTargetDialogWakeWord != null) {
        val activeRebindItem = rebindTargetDialogWakeWord
        SelectTargetChatDialog(
            chats = chats,
            wakePhrase = activeRebindItem?.phrase ?: newWakePhraseInput.ifBlank { "Wake Word" },
            initialSelectedChatId = activeRebindItem?.effectiveTargetChatId ?: enrollmentChat?.id,
            onSelectExistingChat = { chosenChatId ->
                if (activeRebindItem != null) {
                    onReassignWakeWordToChat(activeRebindItem.id, chosenChatId)
                    rebindTargetDialogWakeWord = null
                } else {
                    createNewChatForWakeWord = false
                    selectedEnrollmentChatId = chosenChatId
                    showSelectTargetChatDialog = false
                }
            },
            onCreateNewChat = { newTitle ->
                if (activeRebindItem != null) {
                    onReassignWakeWordToNewChat(activeRebindItem.id, newTitle)
                    rebindTargetDialogWakeWord = null
                    editingWakeWordId = null
                } else {
                    createNewChatForWakeWord = true
                    newWakeChatTitleInput = newTitle
                    val cleanPhrase = newWakePhraseInput.trim()
                    if (cleanPhrase.isNotBlank()) {
                        onAddCustomWakeWordWithNewChat(
                            cleanPhrase,
                            newTitle.ifBlank { "$cleanPhrase Chat" },
                            newWakeSensitivity.coerceIn(0.50f, 0.98f)
                        )
                        newWakePhraseInput = ""
                        newWakeChatTitleInput = ""
                        createNewChatForWakeWord = false
                    }
                    showSelectTargetChatDialog = false
                }
            },
            onDismiss = {
                showSelectTargetChatDialog = false
                rebindTargetDialogWakeWord = null
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Hearing,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Wake Word Hub", style = MaterialTheme.typography.titleLarge)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Live Test Section
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(16.dp)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = if (isListening) "Mic Active - Say a Wake Word" else "Mic Inactive",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        VoiceWaveformVisualizer(
                            isListening = isListening,
                            audioRmsDb = audioRmsDb,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            text = statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (lastHeardText.isNotBlank()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Heard: ",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "\"$lastHeardText\"",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        Button(
                            onClick = onToggleListening,
                            colors = if (isListening) {
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            } else {
                                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("toggle_listening_dialog_button")
                        ) {
                            Icon(
                                imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = null
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isListening) "Pause Listening" else "Start Listening")
                        }
                    }
                }

                // 3-Step Custom Audio Wake-Word Enrollment & Sensitivity Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f))
                        .padding(14.dp)
                        .testTag("custom_audio_wake_enrollment_card")
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Audio Wake-Word Enrollment (3 Samples)",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            if (enrollmentChat != null && enrolledSamples.isNotEmpty()) {
                                TextButton(
                                    onClick = { onDeleteSamples(enrollmentChat.id) },
                                    modifier = Modifier.testTag("wake_hub_clear_samples_btn")
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Reset", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        Text(
                            text = if (enrollmentChat != null) {
                                "Record 3 voice samples (16 kHz, 16-bit mono PCM) for \"${enrollmentChat.title}\" (wake phrase: \"${enrollmentChat.wakeWord}\"). Acoustic templates are matched directly against live mic audio."
                            } else {
                                "Create a chat session first to enroll 3 custom voice wake-word audio templates."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (chats.size > 1) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                chats.take(4).forEach { chatItem ->
                                    val isSelected = enrollmentChat?.id == chatItem.id
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (isSelected) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.surfaceVariant
                                            )
                                            .clickable { selectedEnrollmentChatId = chatItem.id }
                                            .padding(horizontal = 8.dp, vertical = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = chatItem.title.take(12),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        if (enrollmentChat != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                (0 until 3).forEach { slotIndex ->
                                    val sample = enrolledSamples.firstOrNull { it.sampleIndex == slotIndex }
                                    val hasSample = sample != null
                                    val isThisRecording = activeRecordingSlot == slotIndex
                                    val isThisPlaying = sample != null && currentlyPlayingPath == sample.samplePath

                                    Card(
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("wake_hub_sample_slot_$slotIndex"),
                                        colors = CardDefaults.cardColors(
                                            containerColor = when {
                                                isThisRecording -> MaterialTheme.colorScheme.errorContainer
                                                isThisPlaying -> MaterialTheme.colorScheme.secondaryContainer
                                                hasSample -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                                                else -> MaterialTheme.colorScheme.surface
                                            }
                                        ),
                                        border = BorderStroke(
                                            width = if (isThisRecording || isThisPlaying) 2.dp else 1.dp,
                                            color = when {
                                                isThisRecording -> MaterialTheme.colorScheme.error
                                                isThisPlaying -> MaterialTheme.colorScheme.secondary
                                                hasSample -> MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                                            }
                                        ),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 8.dp, horizontal = 4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = "Sample ${slotIndex + 1}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                color = if (hasSample) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                            )

                                            if (isThisRecording) {
                                                FilledTonalButton(
                                                    onClick = { onStopSnippetRecording(enrollmentChat.id, slotIndex) },
                                                    colors = ButtonDefaults.filledTonalButtonColors(
                                                        containerColor = MaterialTheme.colorScheme.error,
                                                        contentColor = MaterialTheme.colorScheme.onError
                                                    ),
                                                    modifier = Modifier
                                                        .height(32.dp)
                                                        .testTag("wake_hub_stop_sample_$slotIndex")
                                                ) {
                                                    Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = String.format(java.util.Locale.US, "%.1fs", recordingDurationMs / 1000.0),
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }
                                            } else if (sample != null) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = "Recorded",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(2.dp))
                                                    Text(
                                                        text = sample.formattedDuration.ifBlank { "16kHz PCM" },
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                                Row(
                                                    horizontalArrangement = Arrangement.Center,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    IconButton(
                                                        onClick = {
                                                            if (isThisPlaying) {
                                                                if (isSnippetPaused) onResumeSnippetPreview() else onPauseSnippetPreview()
                                                            } else {
                                                                onPlaySnippetPreview(sample.samplePath)
                                                            }
                                                        },
                                                        modifier = Modifier
                                                            .size(28.dp)
                                                            .testTag("wake_hub_play_sample_$slotIndex")
                                                    ) {
                                                        Icon(
                                                            imageVector = if (isThisPlaying && !isSnippetPaused) Icons.Default.Pause else Icons.Default.PlayArrow,
                                                            contentDescription = "Play Sample ${slotIndex + 1}",
                                                            tint = MaterialTheme.colorScheme.primary,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                    IconButton(
                                                        onClick = {
                                                            onDeleteSampleById(enrollmentChat.id, sample.id, sample.samplePath)
                                                        },
                                                        modifier = Modifier
                                                            .size(28.dp)
                                                            .testTag("wake_hub_delete_sample_$slotIndex")
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Close,
                                                            contentDescription = "Delete Sample ${slotIndex + 1}",
                                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                            modifier = Modifier.size(15.dp)
                                                        )
                                                    }
                                                }
                                            } else {
                                                OutlinedButton(
                                                    onClick = { onStartSnippetRecording(enrollmentChat.id, slotIndex) },
                                                    enabled = activeRecordingSlot == null,
                                                    modifier = Modifier
                                                        .height(32.dp)
                                                        .testTag("wake_hub_record_sample_$slotIndex")
                                                ) {
                                                    Icon(Icons.Default.Mic, contentDescription = "Record", modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Rec", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Acoustic Sensitivity Threshold Slider + Predefined Levels (Low, Medium, High)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val activePreset = com.example.voice.WakeSensitivityLevel.fromSensitivity(wakeWordSensitivity)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Acoustic Match Sensitivity (${activePreset.label})",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${(wakeWordSensitivity * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                com.example.voice.WakeSensitivityLevel.entries.forEach { preset ->
                                    val selected = activePreset == preset
                                    OutlinedButton(
                                        onClick = { onSetWakeWordSensitivity(preset.sensitivityValue) },
                                        colors = if (selected) {
                                            ButtonDefaults.outlinedButtonColors(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        } else {
                                            ButtonDefaults.outlinedButtonColors()
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("dialog_sensitivity_preset_${preset.name.lowercase(java.util.Locale.US)}")
                                    ) {
                                        Text(
                                            text = "${preset.label} (${(preset.sensitivityValue * 100).toInt()}%)",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                            Slider(
                                value = wakeWordSensitivity.coerceIn(0.50f, 0.98f),
                                onValueChange = { onSetWakeWordSensitivity(it.coerceIn(0.50f, 0.98f)) },
                                valueRange = 0.50f..0.98f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("wake_word_sensitivity_slider")
                            )
                            Text(
                                text = activePreset.description,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilledTonalButton(
                                onClick = {
                                    onDismiss()
                                    onOpenWakeWordStudio()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("dialog_open_wake_studio_button")
                            ) {
                                Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Open Full-Screen Custom Wake Word Studio")
                            }
                        }
                    }
                }

                // Custom Hands-Free Trigger Words Section
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Hands-Free Voice Triggers",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                            Switch(
                                checked = isHandsFreeActive,
                                onCheckedChange = onToggleHandsFree,
                                modifier = Modifier.testTag("dialog_hands_free_switch")
                            )
                        }

                        Text(
                            text = "Configure custom spoken words to control dictation, sending, and low-power heartbeat pings hands-free.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // 1. Dictate Wake Word
                        OutlinedTextField(
                            value = dictateInput,
                            onValueChange = {
                                dictateInput = it
                                onUpdateDictateTrigger(it)
                            },
                            label = { Text("Dictate Wake Word") },
                            placeholder = { Text("e.g. Dictate, Take Note") },
                            singleLine = true,
                            supportingText = {
                                Text("Starts streaming voice transcription into active chat buffer")
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("dictate_trigger_input")
                        )

                        // 2. Send Trigger Word
                        OutlinedTextField(
                            value = sendInput,
                            onValueChange = {
                                sendInput = it
                                onUpdateSendTrigger(it)
                            },
                            label = { Text("Send Trigger Word") },
                            placeholder = { Text("e.g. Send, Over") },
                            singleLine = true,
                            supportingText = {
                                Text("Scrubs trigger word and immediately dispatches message")
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("send_trigger_input")
                        )

                        // 3. Heartbeat Trigger Word
                        OutlinedTextField(
                            value = heartbeatInput,
                            onValueChange = {
                                heartbeatInput = it
                                onUpdateHeartbeatTrigger(it)
                            },
                            label = { Text("Heartbeat Trigger Word") },
                            placeholder = { Text("e.g. Heartbeat, Pulse") },
                            singleLine = true,
                            supportingText = {
                                Text("Intercepts phrase, fires heartbeat ping & plays tone")
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("heartbeat_trigger_input")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onManualHeartbeatPing,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("test_heartbeat_ping_btn")
                            ) {
                                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Test Ping", style = MaterialTheme.typography.labelSmall)
                            }

                            FilledTonalButton(
                                onClick = {
                                    dictateInput = "Dictate"
                                    sendInput = "Send"
                                    heartbeatInput = "Heartbeat"
                                    onResetTriggers()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("reset_triggers_btn")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reset Defaults", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                VoiceOutputSettingsCard(
                    voiceEngineMode = voiceEngineMode,
                    isOrionVoicePlaying = isOrionVoicePlaying,
                    selectedVoice = selectedVoice,
                    masterVolume = masterVolume,
                    onSelectVoiceEngineMode = onSetVoiceEngineMode,
                    onSelectVoice = onSelectVoice,
                    onPlayOrionTestSample = onPlayOrionTestSample,
                    onSetMasterVolume = onSetMasterVolume
                )

                RecordingSettingsCard(
                    autoStopLimitSec = autoStopLimitSec,
                    onSetAutoStopLimit = onSetAutoStopLimit
                )

                PermissionsAndNotificationSettingsCard(
                    notifyActiveChatReplies = notifyActiveChatReplies,
                    onToggleNotifyActiveChatReplies = onToggleNotifyActiveChatReplies,
                    permissionsResetCount = permissionsResetCount,
                    onResetPermissions = onResetPermissions,
                    onRequestRequiredPermissions = onRequestRequiredPermissions
                )

                // Recent Wake Word Detections Log Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Hearing,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Wake Word Detection Log (Last 5)",
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                        Text(
                            text = "Verify microphone sensitivity & tuning with recent detections",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (wakeWordLogs.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No wake words detected yet. Try saying your wake word!",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            wakeWordLogs.forEach { log ->
                                val timeStr = try {
                                    val dateFormat = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                                    dateFormat.format(java.util.Date(log.timestamp.coerceAtLeast(0L)))
                                } catch (_: Throwable) {
                                    ""
                                }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "\"${log.wakeWord}\"",
                                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "(${log.chatTitle})",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "Time: $timeStr | Confidence RMS: ${String.format(java.util.Locale.getDefault(), "%.1f", log.rmsDb)} dB",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Text(
                    text = "Active Wake Word Triggers (Room Persisted)",
                    style = MaterialTheme.typography.titleMedium
                )

                // Inline Add Custom Wake Word Card (supports binding to any existing chat OR creating a new chat on the fly)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(12.dp)
                        .testTag("wake_word_add_card")
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (createNewChatForWakeWord || enrollmentChat == null) {
                                    "Add Wake Phrase & Create New Chat"
                                } else {
                                    "Add Wake Phrase for \"${enrollmentChat.title}\""
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { showSelectTargetChatDialog = true },
                                modifier = Modifier.testTag("open_target_chat_selection_dialog_button")
                            ) {
                                Text("Choose Chat...", style = MaterialTheme.typography.labelSmall)
                            }
                        }

                        // Target Chat Selector: Existing Chats + "+ New Chat" on the fly
                        Text(
                            text = "Target Conversation:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            chats.take(3).forEach { chatOption ->
                                val isSelected = !createNewChatForWakeWord && enrollmentChat?.id == chatOption.id
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.surface
                                        )
                                        .clickable {
                                            createNewChatForWakeWord = false
                                            selectedEnrollmentChatId = chatOption.id
                                        }
                                        .padding(horizontal = 8.dp, vertical = 5.dp)
                                        .testTag("wake_word_target_chat_chip_${chatOption.id}"),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = chatOption.title.take(12),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (createNewChatForWakeWord || enrollmentChat == null) MaterialTheme.colorScheme.tertiary
                                        else MaterialTheme.colorScheme.surface
                                    )
                                    .clickable { createNewChatForWakeWord = true }
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                                    .testTag("wake_word_target_new_chat_chip"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "+ New Chat",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (createNewChatForWakeWord || enrollmentChat == null) MaterialTheme.colorScheme.onTertiary
                                    else MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        if (createNewChatForWakeWord || enrollmentChat == null) {
                            OutlinedTextField(
                                value = newWakeChatTitleInput,
                                onValueChange = { newWakeChatTitleInput = it },
                                label = { Text("New Chat Title (optional)") },
                                placeholder = { Text("e.g. Jarvis Coding Assistant") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("wake_word_new_chat_title_input")
                            )
                        }

                        // Initial Sensitivity for New Wake Word (Preset Buttons + Slider)
                        val newWakePreset = com.example.voice.WakeSensitivityLevel.fromSensitivity(newWakeSensitivity)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Wake Word Sensitivity: ${newWakePreset.label}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "${(newWakeSensitivity * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            com.example.voice.WakeSensitivityLevel.entries.forEach { preset ->
                                val selected = newWakePreset == preset
                                OutlinedButton(
                                    onClick = { newWakeSensitivity = preset.sensitivityValue },
                                    colors = if (selected) {
                                        ButtonDefaults.outlinedButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    } else {
                                        ButtonDefaults.outlinedButtonColors()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("wake_word_new_preset_${preset.name.lowercase(java.util.Locale.US)}")
                                ) {
                                    Text(
                                        text = preset.label,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                        Slider(
                            value = newWakeSensitivity.coerceIn(0.50f, 0.98f),
                            onValueChange = { newWakeSensitivity = it.coerceIn(0.50f, 0.98f) },
                            valueRange = 0.50f..0.98f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("wake_word_new_sensitivity_slider")
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = newWakePhraseInput,
                                onValueChange = { newWakePhraseInput = it },
                                label = { Text("New Wake Word") },
                                placeholder = { Text("e.g. Hey Jarvis, Quick Note") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("wake_word_add_input")
                            )
                            FilledTonalButton(
                                onClick = {
                                    val clean = newWakePhraseInput.trim()
                                    if (clean.isNotBlank()) {
                                        val clampedSens = newWakeSensitivity.coerceIn(0.50f, 0.98f)
                                        if (createNewChatForWakeWord || enrollmentChat == null) {
                                            val chatTitle = newWakeChatTitleInput.trim().ifBlank { "$clean Chat" }
                                            onAddCustomWakeWordWithNewChat(clean, chatTitle, clampedSens)
                                            newWakeChatTitleInput = ""
                                            createNewChatForWakeWord = false
                                        } else {
                                            onAddCustomWakeWordWithSensitivity(enrollmentChat.id, clean, clampedSens)
                                        }
                                        newWakePhraseInput = ""
                                    }
                                },
                                enabled = newWakePhraseInput.trim().isNotBlank(),
                                modifier = Modifier.testTag("wake_word_add_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Add Wake Word", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Add")
                            }
                        }
                    }
                }

                val displayWakeWords = remember(configuredWakeWords, chats) {
                    if (configuredWakeWords.isNotEmpty()) {
                        configuredWakeWords
                    } else {
                        chats.map { chat ->
                            WakeWord(
                                id = chat.id,
                                phrase = chat.wakeWord,
                                triggerType = WakeWord.TYPE_CHAT,
                                commandRole = WakeWord.ROLE_CHAT_WAKE,
                                chatId = chat.id,
                                targetChatId = chat.id,
                                chatTitle = chat.title,
                                isEnabled = true,
                                sensitivity = wakeWordSensitivity
                            )
                        }
                    }
                }

                displayWakeWords.forEach { item ->
                    val resolvedItemChatId = item.effectiveTargetChatId
                    val boundChat = chats.firstOrNull { it.id == resolvedItemChatId }
                    val safeChatColor = parseSafeWakeColor(boundChat?.themeColorHex)
                    val isEditingThis = editingWakeWordId == item.id
                    val itemPreset = com.example.voice.WakeSensitivityLevel.fromSensitivity(item.sensitivity)
                    val targetChatId = boundChat?.id ?: resolvedItemChatId ?: 0L

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .padding(12.dp)
                            .testTag("wake_word_item_${item.id}"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(safeChatColor.copy(alpha = 0.2f))
                                    .clickable(enabled = targetChatId > 0L) {
                                        if (targetChatId > 0L) {
                                            onOpenChat(targetChatId)
                                            onDismiss()
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = getIconForName(boundChat?.iconName ?: "Sparkles"),
                                    contentDescription = "Open Linked Chat",
                                    tint = safeChatColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = boundChat?.title ?: item.chatTitle.ifBlank { "Gemini Chat" },
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    if (boundChat?.isDefaultWakeChat == true) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(MaterialTheme.colorScheme.primaryContainer)
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "DEFAULT",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "Wake trigger: \"${item.phrase}\"",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (item.isEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "Sensitivity: ${itemPreset.label} (${item.sensitivityInt}%)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    TextButton(
                                        onClick = { rebindTargetDialogWakeWord = item },
                                        modifier = Modifier
                                            .height(28.dp)
                                            .testTag("rebind_target_chat_dialog_button_${item.id}")
                                    ) {
                                        Text("Assign Chat", style = MaterialTheme.typography.labelSmall)
                                    }
                                    if (targetChatId > 0L) {
                                        TextButton(
                                            onClick = {
                                                onOpenChat(targetChatId)
                                                onDismiss()
                                            },
                                            modifier = Modifier
                                                .height(28.dp)
                                                .testTag("wake_word_open_chat_${item.id}")
                                        ) {
                                            Text("Open Chat", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                            Switch(
                                checked = item.isEnabled,
                                onCheckedChange = { enabled ->
                                    onToggleConfiguredWakeWord(item.id, enabled)
                                },
                                modifier = Modifier.testTag("wake_word_toggle_${item.id}")
                            )
                            IconButton(
                                onClick = {
                                    if (isEditingThis) {
                                        editingWakeWordId = null
                                        editingWakeWordDraft = ""
                                        reassignNewChatTitleDraft = ""
                                    } else {
                                        editingWakeWordId = item.id
                                        editingWakeWordDraft = item.phrase
                                        reassignNewChatTitleDraft = ""
                                    }
                                },
                                modifier = Modifier.testTag("wake_word_edit_${item.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit Wake Word",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    onDeleteConfiguredWakeWord(item.id)
                                },
                                modifier = Modifier.testTag("wake_word_delete_${item.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete Wake Word",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        // Per-Wake-Word Sensitivity Controls (Preset Levels + Fine-Tuning Slider)
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                com.example.voice.WakeSensitivityLevel.entries.forEach { preset ->
                                    val selected = itemPreset == preset
                                    OutlinedButton(
                                        onClick = { onSetIndividualWakeWordSensitivity(item.id, preset.sensitivityValue) },
                                        colors = if (selected) {
                                            ButtonDefaults.outlinedButtonColors(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        } else {
                                            ButtonDefaults.outlinedButtonColors()
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(30.dp)
                                            .testTag("wake_word_preset_${item.id}_${preset.name.lowercase(java.util.Locale.US)}")
                                    ) {
                                        Text(
                                            text = "${preset.label} (${(preset.sensitivityValue * 100).toInt()}%)",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                            Slider(
                                value = item.sensitivity.coerceIn(0.50f, 0.98f),
                                onValueChange = { onSetIndividualWakeWordSensitivity(item.id, it.coerceIn(0.50f, 0.98f)) },
                                valueRange = 0.50f..0.98f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("wake_word_sensitivity_slider_${item.id}")
                            )
                        }

                        if (isEditingThis) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = editingWakeWordDraft,
                                        onValueChange = { editingWakeWordDraft = it },
                                        label = { Text("Edit Wake Phrase") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("wake_word_edit_input_${item.id}")
                                    )
                                    IconButton(
                                        onClick = {
                                            val clean = editingWakeWordDraft.trim()
                                            if (clean.isNotBlank()) {
                                                onUpdateConfiguredWakeWord(item.id, clean)
                                                editingWakeWordId = null
                                                editingWakeWordDraft = ""
                                                reassignNewChatTitleDraft = ""
                                            }
                                        },
                                        modifier = Modifier.testTag("wake_word_save_${item.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = "Save Wake Word",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    IconButton(
                                        onClick = {
                                            editingWakeWordId = null
                                            editingWakeWordDraft = ""
                                            reassignNewChatTitleDraft = ""
                                        },
                                        modifier = Modifier.testTag("wake_word_cancel_${item.id}")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Cancel Edit",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                // Re-bind this wake word to another existing chat or create a new chat on the fly
                                Text(
                                    text = "Associated Chat Conversation:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    chats.take(4).forEach { chatOption ->
                                        val isCurrentBound = resolvedItemChatId == chatOption.id
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (isCurrentBound) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.surface
                                                )
                                                .clickable {
                                                    onReassignWakeWordToChat(item.id, chatOption.id)
                                                }
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                                .testTag("wake_word_bind_chat_${item.id}_${chatOption.id}"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = chatOption.title.take(12),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isCurrentBound) MaterialTheme.colorScheme.onPrimary
                                                else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = reassignNewChatTitleDraft,
                                        onValueChange = { reassignNewChatTitleDraft = it },
                                        label = { Text("Or Create & Bind New Chat") },
                                        placeholder = { Text("${item.phrase} Chat") },
                                        singleLine = true,
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("wake_word_reassign_new_chat_input_${item.id}")
                                    )
                                    FilledTonalButton(
                                        onClick = {
                                            val newTitle = reassignNewChatTitleDraft.trim().ifBlank { "${item.phrase} Chat" }
                                            onReassignWakeWordToNewChat(item.id, newTitle)
                                            reassignNewChatTitleDraft = ""
                                            editingWakeWordId = null
                                        },
                                        modifier = Modifier.testTag("wake_word_reassign_new_chat_${item.id}")
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("New Chat", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Pro-tip: Enroll 3 voice samples above so background wake detection matches your voice acoustics without injecting the wake word text into your Gemini prompt!",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("close_wake_settings_button")
            ) {
                Text("Done")
            }
        }
    )
}

/**
 * Selection dialog allowing users to pick an existing chat conversation or trigger a "Create New Chat"
 * flow when assigning a wake word (`targetChatId`).
 */
@Composable
fun SelectTargetChatDialog(
    chats: List<ChatSession>,
    wakePhrase: String,
    initialSelectedChatId: Long? = null,
    onSelectExistingChat: (Long) -> Unit,
    onCreateNewChat: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var createNewMode by remember(chats.isEmpty()) { mutableStateOf(chats.isEmpty()) }
    var selectedChatId by remember(initialSelectedChatId, chats) {
        mutableStateOf(initialSelectedChatId ?: chats.firstOrNull()?.id ?: 0L)
    }
    var newChatTitle by remember(wakePhrase) {
        mutableStateOf(if (wakePhrase.isNotBlank() && wakePhrase != "Wake Word") "$wakePhrase Chat" else "")
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("select_target_chat_dialog"),
        title = {
            Text(
                text = "Assign Target Chat for \"$wakePhrase\"",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Pick an existing chat conversation or create a new chat session for this wake word:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { createNewMode = false },
                        enabled = chats.isNotEmpty(),
                        colors = if (!createNewMode && chats.isNotEmpty()) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("target_dialog_mode_existing")
                    ) {
                        Text("Existing Chat", style = MaterialTheme.typography.labelMedium)
                    }
                    OutlinedButton(
                        onClick = { createNewMode = true },
                        colors = if (createNewMode) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        } else {
                            ButtonDefaults.outlinedButtonColors()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("target_dialog_mode_create_new")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Create New Chat", style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (!createNewMode && chats.isNotEmpty()) {
                    chats.forEach { chat ->
                        val isChosen = selectedChatId == chat.id
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedChatId = chat.id }
                                .testTag("target_dialog_chat_option_${chat.id}"),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isChosen) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                }
                            ),
                            border = BorderStroke(
                                width = if (isChosen) 2.dp else 1.dp,
                                color = if (isChosen) MaterialTheme.colorScheme.primary else Color.Transparent
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = chat.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Primary wake word: \"${chat.wakeWord}\"",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isChosen) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = newChatTitle,
                        onValueChange = { newChatTitle = it },
                        label = { Text("New Chat Session Title") },
                        placeholder = { Text("e.g. $wakePhrase Assistant") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("target_dialog_new_chat_title_input")
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (createNewMode || chats.isEmpty()) {
                        val resolvedTitle = newChatTitle.trim().ifBlank { "$wakePhrase Chat" }
                        onCreateNewChat(resolvedTitle)
                    } else if (selectedChatId > 0L) {
                        onSelectExistingChat(selectedChatId)
                    }
                },
                modifier = Modifier.testTag("target_dialog_confirm_button")
            ) {
                Text(if (createNewMode || chats.isEmpty()) "Create & Assign" else "Assign to Chat")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("target_dialog_cancel_button")
            ) {
                Text("Cancel")
            }
        }
    )
}

