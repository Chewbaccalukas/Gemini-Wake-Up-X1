package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.ChatSession
import com.example.data.model.WakeWord
import com.example.data.model.WakeWordSample
import com.example.ui.WakeReadyCueState
import com.example.ui.components.VoiceWaveformVisualizer
import com.example.voice.WakeSensitivityLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.util.Locale

/**
 * Prominent visual feedback banner shown when the wake word listener is in Sleep Mode
 * (e.g., after saying "Gemini, sleep"), with a single-tap button to re-enable listening.
 */
@Composable
fun SleepModeVisualBanner(
    isSleepMode: Boolean,
    sleepReason: String = "",
    onWakeUpTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible = isSleepMode, modifier = modifier) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("sleep_mode_visual_banner"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bedtime,
                            contentDescription = "Sleep Mode Active",
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Sleep Mode Active • Listener Paused",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = sleepReason.ifBlank {
                                "Say \"Gemini, wake up\" or tap Wake Up to resume wake-word detection"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onWakeUpTap,
                    modifier = Modifier.testTag("wake_up_from_sleep_button"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.WbSunny,
                        contentDescription = "Wake Up",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Wake Up", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/**
 * Non-intrusive visual cue banner confirming that a wake word was detected and the app is ready
 * to receive voice commands, with a quick action to jump to the associated chat conversation.
 */
@Composable
fun WakeReadyVisualCueBanner(
    cueState: WakeReadyCueState?,
    onOpenChat: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible = cueState != null, modifier = modifier) {
        val cue = cueState ?: return@AnimatedVisibility
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .testTag("wake_ready_visual_cue_banner"),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            tonalElevation = 4.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.GraphicEq,
                        contentDescription = "Wake Word Detected Cue",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Ready for Commands • \"${cue.wakePhrase}\"",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = if (!cue.followUpQuery.isNullOrBlank()) {
                                "Routed to ${cue.chatTitle}: \"${cue.followUpQuery}\""
                            } else {
                                "Associated Chat: ${cue.chatTitle} — listening for your command"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (cue.chatId > 0L) {
                        TextButton(
                            onClick = { onOpenChat(cue.chatId) },
                            modifier = Modifier.testTag("wake_cue_open_chat_button")
                        ) {
                            Text("Open Chat", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(32.dp)
                            .testTag("wake_cue_dismiss_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss Cue",
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dedicated screen allowing users to record and save custom wake words, store audio samples in
 * the local Room database with unique IDs (`sampleUuid` and `wakeWordId`) for the keyword detection loop,
 * customize wake word sensitivity (Low / Medium / High presets + slider), control Sleep Mode
 * ("Gemini, sleep" / "Gemini, wake up"), and associate multiple wake words with specific chat conversations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WakeWordRecordingScreen(
    chats: List<ChatSession>,
    configuredWakeWords: List<WakeWord>,
    allSamples: List<WakeWordSample>,
    isListening: Boolean,
    isSleepMode: Boolean,
    sleepModeReason: String,
    backgroundCueOnlyMode: Boolean,
    wakeReadyVisualCue: WakeReadyCueState?,
    audioRmsDb: Float,
    statusMessage: String,
    wakeWordSensitivity: Float,
    activeRecordingSlot: Int?,
    recordingDurationMs: Long,
    currentlyPlayingPath: String?,
    isSnippetPaused: Boolean,
    getSamplesFlow: (Long) -> Flow<List<WakeWordSample>> = { flowOf(emptyList()) },
    onBack: () -> Unit,
    onToggleListening: () -> Unit,
    onEnterSleepMode: () -> Unit,
    onExitSleepMode: () -> Unit,
    onToggleBackgroundCueOnlyMode: (Boolean) -> Unit,
    onDismissWakeReadyCue: () -> Unit,
    onSetWakeWordSensitivity: (Float) -> Unit,
    onSetWakeWordSensitivityLevel: (WakeSensitivityLevel) -> Unit,
    onStartSnippetRecording: (Long, Int) -> Unit,
    onStopSnippetRecording: (Long, Int) -> Unit,
    onPlaySnippetPreview: (String) -> Unit,
    onPauseSnippetPreview: () -> Unit,
    onResumeSnippetPreview: () -> Unit,
    onDeleteSampleById: (Long, Long, String) -> Unit,
    onDeleteAllSamplesForChat: (Long) -> Unit,
    onSaveCustomWakeWordWithSamples: (String, Long, Float) -> Unit,
    onSaveCustomWakeWordWithNewChat: (String, String, Float) -> Unit = { _, _, _ -> },
    onSetIndividualWakeWordSensitivity: (Long, Float) -> Unit = { _, _ -> },
    onReassignWakeWordToChat: (Long, Long) -> Unit = { _, _ -> },
    onToggleWakeWordEnabled: (Long, Boolean) -> Unit,
    onDeleteWakeWord: (Long) -> Unit,
    onTriggerWakeWordAndOpenChat: (WakeWord) -> Unit,
    onOpenChat: (Long) -> Unit
) {
    BackHandler(onBack = onBack)

    val defaultChat = remember(chats) {
        chats.firstOrNull { it.isDefaultWakeChat } ?: chats.firstOrNull()
    }
    var selectedChatId by remember(defaultChat?.id) {
        mutableStateOf(defaultChat?.id ?: 0L)
    }
    var createNewChatMode by remember { mutableStateOf(false) }
    var newChatTitleInput by remember { mutableStateOf("") }
    var newWakeWordSensitivity by remember(wakeWordSensitivity) {
        mutableStateOf(wakeWordSensitivity.coerceIn(0.50f, 0.98f))
    }
    val selectedChat = remember(chats, selectedChatId) {
        chats.firstOrNull { it.id == selectedChatId } ?: defaultChat
    }

    var customPhraseInput by remember(selectedChat?.id) {
        mutableStateOf(selectedChat?.wakeWord ?: "Hey Gemini")
    }
    var statusFeedbackBanner by remember { mutableStateOf<String?>(null) }

    val selectedChatSamples by remember(selectedChat?.id) {
        if (selectedChat != null && selectedChat.id > 0L) {
            getSamplesFlow(selectedChat.id)
        } else {
            flowOf(emptyList())
        }
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    val currentSensitivityLevel = remember(wakeWordSensitivity) {
        WakeSensitivityLevel.fromSensitivity(wakeWordSensitivity)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("wake_word_recording_screen"),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Custom Wake Word Studio",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Record Audio Samples • Multi-Chat Routing • Sensitivity",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("wake_studio_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Home"
                        )
                    }
                },
                actions = {
                    FilledTonalButton(
                        onClick = {
                            if (isSleepMode) onExitSleepMode() else onEnterSleepMode()
                        },
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("studio_toggle_sleep_button")
                    ) {
                        Icon(
                            imageVector = if (isSleepMode) Icons.Default.WbSunny else Icons.Default.Bedtime,
                            contentDescription = if (isSleepMode) "Wake Up Listener" else "Enter Sleep Mode",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isSleepMode) "Wake Up" else "Sleep Mode",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Sleep Mode & Non-Intrusive Wake-Ready Cue Banners
            item {
                Column {
                    SleepModeVisualBanner(
                        isSleepMode = isSleepMode,
                        sleepReason = sleepModeReason,
                        onWakeUpTap = onExitSleepMode
                    )
                    WakeReadyVisualCueBanner(
                        cueState = wakeReadyVisualCue,
                        onOpenChat = onOpenChat,
                        onDismiss = onDismissWakeReadyCue
                    )
                }
            }

            // 2. Background Listener & Voice Sleep Command Status Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("studio_listener_status_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = when {
                                        isSleepMode -> Icons.Default.Bedtime
                                        isListening -> Icons.Default.Mic
                                        else -> Icons.Default.MicOff
                                    },
                                    contentDescription = null,
                                    tint = when {
                                        isSleepMode -> MaterialTheme.colorScheme.secondary
                                        isListening -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = when {
                                            isSleepMode -> "Sleep Mode (Say \"Gemini, wake up\" or Tap Wake Up)"
                                            isListening -> "Background Wake Listener Active"
                                            else -> "Wake Listener Paused"
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = statusMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Button(
                                onClick = {
                                    if (isSleepMode) onExitSleepMode() else onToggleListening()
                                },
                                modifier = Modifier.testTag("studio_toggle_listening_button")
                            ) {
                                Text(
                                    when {
                                        isSleepMode -> "Resume"
                                        isListening -> "Pause"
                                        else -> "Listen"
                                    }
                                )
                            }
                        }

                        VoiceWaveformVisualizer(
                            isListening = isListening && !isSleepMode,
                            audioRmsDb = audioRmsDb,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.NotificationsActive,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Background Non-Intrusive Cue",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Show notification/visual cue when wake word is detected in background without interrupting active apps",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = backgroundCueOnlyMode,
                                onCheckedChange = onToggleBackgroundCueOnlyMode,
                                modifier = Modifier.testTag("background_cue_mode_switch")
                            )
                        }
                    }
                }
            }

            // 3. Wake Word Sensitivity Customization Card (Predefined Levels + Fine-Tune Slider)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("studio_sensitivity_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Wake Word Detection Sensitivity",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "${currentSensitivityLevel.label} (${(wakeWordSensitivity * 100).toInt()}%)",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Predefined Sensitivity Level Chips (Low, Medium, High)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            WakeSensitivityLevel.entries.forEach { level ->
                                val isSelected = currentSensitivityLevel == level
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSetWakeWordSensitivityLevel(level) },
                                    label = {
                                        Text(
                                            text = "${level.label} (${(level.sensitivityValue * 100).toInt()}%)",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    },
                                    leadingIcon = if (isSelected) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else null,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("sensitivity_preset_${level.name.lowercase(Locale.US)}")
                                )
                            }
                        }

                        Slider(
                            value = wakeWordSensitivity.coerceIn(0.50f, 0.98f),
                            onValueChange = { onSetWakeWordSensitivity(it.coerceIn(0.50f, 0.98f)) },
                            valueRange = 0.50f..0.98f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("studio_sensitivity_slider")
                        )

                        Text(
                            text = currentSensitivityLevel.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 4. Record & Save Custom Wake Word Studio Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("record_custom_wake_word_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Record & Save Custom Wake Word",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (selectedChat != null && selectedChatSamples.isNotEmpty()) {
                                TextButton(
                                    onClick = { onDeleteAllSamplesForChat(selectedChat.id) },
                                    modifier = Modifier.testTag("studio_clear_samples_button")
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Clear Samples", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }

                        Text(
                            text = "1. Choose the target chat conversation • 2. Enter your custom wake phrase • 3. Record voice samples and save to the local database with a unique ID for the keyword detection loop.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Target Chat Conversation Selector + On-the-Fly New Chat
                        Text(
                            text = "Associated Chat Conversation:",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = createNewChatMode,
                                onClick = { createNewChatMode = true },
                                label = { Text("+ New Chat") },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (createNewChatMode) Icons.Default.Check else Icons.Default.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                },
                                modifier = Modifier.testTag("studio_chat_selector_new_chat")
                            )
                            chats.forEach { chatItem ->
                                val isSelected = !createNewChatMode && selectedChat?.id == chatItem.id
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        createNewChatMode = false
                                        selectedChatId = chatItem.id
                                        customPhraseInput = chatItem.wakeWord
                                    },
                                    label = { Text(chatItem.title) },
                                    leadingIcon = if (isSelected) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    } else null,
                                    modifier = Modifier.testTag("studio_chat_selector_${chatItem.id}")
                                )
                            }
                        }

                        if (createNewChatMode) {
                            OutlinedTextField(
                                value = newChatTitleInput,
                                onValueChange = { newChatTitleInput = it },
                                label = { Text("New Chat Title (Created on Save)") },
                                placeholder = { Text("e.g. Morning Standup, Research Workspace") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("studio_new_chat_title_input")
                            )
                        }

                        // Custom Wake Phrase Input
                        OutlinedTextField(
                            value = customPhraseInput,
                            onValueChange = { customPhraseInput = it },
                            label = { Text("Custom Wake Word Phrase") },
                            placeholder = { Text("e.g. Hey Jarvis, Code Mode, Morning Brief") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("custom_wake_word_phrase_input")
                        )

                        // Per-Wake-Word Sensitivity Controls when saving
                        val newPhraseLevel = WakeSensitivityLevel.fromSensitivity(newWakeWordSensitivity)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Phrase Sensitivity Override:",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${newPhraseLevel.label} (${(newWakeWordSensitivity * 100).toInt()}%)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                WakeSensitivityLevel.entries.forEach { level ->
                                    val isLevelSelected = newPhraseLevel == level
                                    FilterChip(
                                        selected = isLevelSelected,
                                        onClick = { newWakeWordSensitivity = level.sensitivityValue },
                                        label = {
                                            Text(
                                                text = "${level.label} (${(level.sensitivityValue * 100).toInt()}%)",
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("studio_new_phrase_sensitivity_${level.name.lowercase(Locale.US)}")
                                    )
                                }
                            }
                            Slider(
                                value = newWakeWordSensitivity.coerceIn(0.50f, 0.98f),
                                onValueChange = { newWakeWordSensitivity = it.coerceIn(0.50f, 0.98f) },
                                valueRange = 0.50f..0.98f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("studio_new_phrase_sensitivity_slider")
                            )
                        }

                        // 3 Audio Sample Recording Slots with Unique Database ID display
                        if (!createNewChatMode && selectedChat != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                (0 until 3).forEach { slotIndex ->
                                    val sample = selectedChatSamples.firstOrNull { it.sampleIndex == slotIndex }
                                    val hasSample = sample != null
                                    val isThisRecording = activeRecordingSlot == slotIndex
                                    val isThisPlaying = sample != null && currentlyPlayingPath == sample.samplePath

                                    Card(
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("studio_sample_slot_$slotIndex"),
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
                                                .padding(vertical = 8.dp, horizontal = 6.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Text(
                                                text = "Sample ${slotIndex + 1}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold
                                            )

                                            if (isThisRecording) {
                                                FilledTonalButton(
                                                    onClick = { onStopSnippetRecording(selectedChat.id, slotIndex) },
                                                    colors = ButtonDefaults.filledTonalButtonColors(
                                                        containerColor = MaterialTheme.colorScheme.error,
                                                        contentColor = MaterialTheme.colorScheme.onError
                                                    ),
                                                    modifier = Modifier
                                                        .height(32.dp)
                                                        .testTag("studio_stop_sample_$slotIndex")
                                                ) {
                                                    Icon(Icons.Default.Stop, contentDescription = "Stop", modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = String.format(Locale.US, "%.1fs", recordingDurationMs / 1000.0),
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }
                                            } else if (sample != null) {
                                                Text(
                                                    text = "ID: ${sample.shortUniqueId}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    maxLines = 1
                                                )
                                                Text(
                                                    text = sample.formattedDuration.ifBlank { "16kHz PCM" },
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
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
                                                            .testTag("studio_play_sample_$slotIndex")
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
                                                            onDeleteSampleById(selectedChat.id, sample.id, sample.samplePath)
                                                        },
                                                        modifier = Modifier
                                                            .size(28.dp)
                                                            .testTag("studio_delete_sample_$slotIndex")
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
                                                    onClick = { onStartSnippetRecording(selectedChat.id, slotIndex) },
                                                    enabled = activeRecordingSlot == null,
                                                    modifier = Modifier
                                                        .height(32.dp)
                                                        .testTag("studio_record_sample_$slotIndex")
                                                ) {
                                                    Icon(Icons.Default.Mic, contentDescription = "Record", modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("Record", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Button(
                            onClick = {
                                val cleanPhrase = customPhraseInput.trim()
                                if (cleanPhrase.isNotBlank()) {
                                    if (createNewChatMode) {
                                        val title = newChatTitleInput.trim().ifBlank { "$cleanPhrase Chat" }
                                        onSaveCustomWakeWordWithNewChat(cleanPhrase, title, newWakeWordSensitivity)
                                        statusFeedbackBanner = "Created chat \"$title\" & bound wake word \"$cleanPhrase\" (${(newWakeWordSensitivity * 100).toInt()}% sensitivity)!"
                                        createNewChatMode = false
                                        newChatTitleInput = ""
                                    } else {
                                        val targetId = selectedChat?.id ?: 0L
                                        onSaveCustomWakeWordWithSamples(cleanPhrase, targetId, newWakeWordSensitivity)
                                        statusFeedbackBanner = "Saved custom wake word \"$cleanPhrase\" (${(newWakeWordSensitivity * 100).toInt()}%) & linked ${selectedChatSamples.size} audio sample(s) to ${selectedChat?.title ?: "Chat"}!"
                                    }
                                }
                            },
                            enabled = customPhraseInput.trim().isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("save_custom_wake_word_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (createNewChatMode) "Create Chat & Save Wake Word" else "Save Custom Wake Word & Link Samples")
                        }

                        if (!statusFeedbackBanner.isNullOrBlank()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("studio_save_confirmation_banner")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = statusFeedbackBanner.orEmpty(),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(
                                        onClick = { statusFeedbackBanner = null },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(14.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. Multiple Wake Words -> Chat Conversation Routing Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "Active Wake Words & Chat Associations (${configuredWakeWords.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Each wake word opens directly to its corresponding chat conversation when detected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 6. List of Configured Wake Words and Linked Audio Samples
            items(
                items = configuredWakeWords,
                key = { "studio_wake_${it.id}_${it.phrase}" }
            ) { wakeItem ->
                val linkedSamples = remember(allSamples, wakeItem.id, wakeItem.chatId) {
                    allSamples.filter {
                        (it.wakeWordId != null && it.wakeWordId == wakeItem.id) ||
                            (it.wakeWordId == null && wakeItem.chatId != null && it.chatId == wakeItem.chatId)
                    }
                }
                val associatedChatTitle = remember(chats, wakeItem.chatId, wakeItem.chatTitle) {
                    chats.firstOrNull { it.id == wakeItem.chatId }?.title
                        ?: wakeItem.chatTitle.ifBlank { "General Gemini" }
                }
                val itemSensitivity = wakeItem.sensitivity.coerceIn(0.50f, 0.98f)
                val itemSensitivityLevel = WakeSensitivityLevel.fromSensitivity(itemSensitivity)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("studio_wake_word_item_${wakeItem.id}"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Hearing,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "\"${wakeItem.phrase}\"",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Text(
                                            text = "ID #${wakeItem.id}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Opens Chat: $associatedChatTitle • ${linkedSamples.size} Audio Sample(s) • Sensitivity ${itemSensitivityLevel.label} (${(itemSensitivity * 100).toInt()}%)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (linkedSamples.isNotEmpty()) {
                                    Text(
                                        text = "Sample UUIDs: ${linkedSamples.joinToString(", ") { it.shortUniqueId }}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Switch(
                                checked = wakeItem.isEnabled,
                                onCheckedChange = { enabled -> onToggleWakeWordEnabled(wakeItem.id, enabled) },
                                modifier = Modifier.testTag("studio_toggle_wake_word_${wakeItem.id}")
                            )
                        }

                        // Per-item sensitivity preset chips + slider
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            WakeSensitivityLevel.entries.forEach { level ->
                                val isSelected = itemSensitivityLevel == level
                                FilterChip(
                                    selected = isSelected,
                                    onClick = { onSetIndividualWakeWordSensitivity(wakeItem.id, level.sensitivityValue) },
                                    label = {
                                        Text(
                                            text = "${level.label} (${(level.sensitivityValue * 100).toInt()}%)",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("studio_item_sensitivity_${wakeItem.id}_${level.name.lowercase(Locale.US)}")
                                )
                            }
                        }

                        Slider(
                            value = itemSensitivity,
                            onValueChange = { onSetIndividualWakeWordSensitivity(wakeItem.id, it.coerceIn(0.50f, 0.98f)) },
                            valueRange = 0.50f..0.98f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("studio_item_sensitivity_slider_${wakeItem.id}")
                        )

                        // Per-item chat binding chips
                        if (chats.size > 1) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Target Chat:",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                chats.forEach { chatOption ->
                                    val isBoundChat = wakeItem.chatId == chatOption.id
                                    FilterChip(
                                        selected = isBoundChat,
                                        onClick = { onReassignWakeWordToChat(wakeItem.id, chatOption.id) },
                                        label = {
                                            Text(
                                                text = chatOption.title,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        },
                                        modifier = Modifier.testTag("studio_item_rebind_chat_${wakeItem.id}_${chatOption.id}")
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = { onTriggerWakeWordAndOpenChat(wakeItem) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("trigger_wake_word_open_chat_${wakeItem.id}")
                            ) {
                                Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Simulate Wake & Open Chat", style = MaterialTheme.typography.labelMedium)
                            }
                            if (wakeItem.chatId != null && wakeItem.chatId > 0L) {
                                OutlinedButton(
                                    onClick = { onOpenChat(wakeItem.chatId) },
                                    modifier = Modifier.testTag("studio_open_associated_chat_${wakeItem.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                        contentDescription = "Open Chat",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = { onDeleteWakeWord(wakeItem.id) },
                                modifier = Modifier.testTag("studio_delete_wake_word_${wakeItem.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete Wake Word",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
