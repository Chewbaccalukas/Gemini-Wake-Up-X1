package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.ChatSession
import com.example.data.model.WakeWord
import com.example.ui.components.VoiceWaveformVisualizer
import com.example.voice.WakeSensitivityLevel
import java.util.Locale

/**
 * Full-screen Settings screen allowing users to:
 * 1. Adjust the sensitivity of the speech recognition & wake-word trigger (global slider + presets + per-wake-word sliders).
 * 2. Customize their wake words (add new custom wake words, edit existing phrases inline, toggle active state, bind to chats, or delete).
 * 3. Customize hands-free voice command triggers (Dictate, Send, Heartbeat) and test live microphone recognition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    chats: List<ChatSession>,
    configuredWakeWords: List<WakeWord>,
    wakeWordSensitivity: Float,
    isListening: Boolean,
    isHandsFreeActive: Boolean,
    isSleepMode: Boolean = false,
    sleepModeReason: String = "",
    audioRmsDb: Float = 0f,
    statusMessage: String = "",
    lastHeardText: String = "",
    dictateTrigger: String = "Dictate",
    sendTrigger: String = "Send",
    heartbeatTrigger: String = "Heartbeat",
    onBack: () -> Unit,
    onSetWakeWordSensitivity: (Float) -> Unit,
    onSetWakeWordSensitivityLevel: (WakeSensitivityLevel) -> Unit = { level ->
        onSetWakeWordSensitivity(level.sensitivityValue)
    },
    onToggleListening: () -> Unit = {},
    onToggleHandsFree: (Boolean) -> Unit = {},
    onExitSleepMode: () -> Unit = {},
    onAddCustomWakeWord: (chatId: Long, phrase: String, sensitivity: Float) -> Unit = { _, _, _ -> },
    onAddCustomWakeWordWithNewChat: (phrase: String, newChatTitle: String, sensitivity: Float) -> Unit = { _, _, _ -> },
    onUpdateWakeWordPhrase: (id: Long, newPhrase: String) -> Unit = { _, _ -> },
    onToggleWakeWordEnabled: (id: Long, enabled: Boolean) -> Unit = { _, _ -> },
    onDeleteWakeWord: (id: Long) -> Unit = {},
    onSetIndividualWakeWordSensitivity: (id: Long, sensitivity: Float) -> Unit = { _, _ -> },
    onReassignWakeWordToChat: (wakeWordId: Long, targetChatId: Long) -> Unit = { _, _ -> },
    onUpdateChatSessionWakeWord: (ChatSession, String) -> Unit = { _, _ -> },
    onUpdateDictateTrigger: (String) -> Unit = {},
    onUpdateSendTrigger: (String) -> Unit = {},
    onUpdateHeartbeatTrigger: (String) -> Unit = {},
    onResetTriggersToDefaults: () -> Unit = {},
    onOpenWakeWordStudio: () -> Unit = {},
    onOpenDiagnosticLog: () -> Unit = {},
    onOpenChat: (Long) -> Unit = {},
    onSetDefaultWakeChat: (Long) -> Unit = {},
    onCreateNewWakeTargetChat: (title: String, wakePhrase: String) -> Unit = { _, _ -> },
    sessionStatisticsState: com.example.ui.SessionStatisticsUiState? = null,
    onSaveBackupToDevice: (com.example.util.BackupExportFormat) -> Unit = {},
    onShareBackupFile: (com.example.util.BackupExportFormat) -> Unit = {},
    relayServerState: com.example.bridge.RelayServerState? = null,
    onToggleRelayServer: (Boolean) -> Unit = {},
    onRotateRelaySessionToken: () -> Unit = {},
    onCopyRelaySessionToken: (String) -> Unit = {},
    onForceResetAndClearHygiene: () -> Unit = {},
    lastHygieneResetMessage: String? = null,
    favoriteChatIds: Set<Long> = emptySet(),
    onToggleFavoriteChat: (Long) -> Unit = {},
    contextualRoutingEnabled: Boolean = true,
    onToggleContextualRouting: (Boolean) -> Unit = {},
    promptFavoritesOnWake: Boolean = false,
    onTogglePromptFavoritesOnWake: (Boolean) -> Unit = {},
    allSamples: List<com.example.data.model.WakeWordSample> = emptyList(),
    activeRecordingSlot: Int? = null,
    recordingDurationMs: Long = 0L,
    currentlyPlayingPath: String? = null,
    onStartSnippetRecording: (chatId: Long, slotIndex: Int) -> Unit = { _, _ -> },
    onStopSnippetRecording: (chatId: Long, slotIndex: Int) -> Unit = { _, _ -> },
    onPlaySnippetPreview: (String) -> Unit = {},
    onStopSnippetPreview: () -> Unit = {},
    onDeleteSampleById: (chatId: Long, sampleId: Long, filePath: String) -> Unit = { _, _, _ -> },
    voiceEngineMode: com.example.voice.VoiceEngineMode = com.example.voice.VoiceEngineMode.GEMINI_ORION,
    onSetVoiceEngineMode: (com.example.voice.VoiceEngineMode) -> Unit = {},
    masterVolume: Float = 1.0f,
    onSetMasterVolume: (Float) -> Unit = {},
    autoStopLimitSec: Int = 3,
    onSetAutoStopLimit: (Int) -> Unit = {},
    defaultPersistenceEnabled: Boolean = true,
    onToggleDefaultPersistence: (Boolean) -> Unit = {},
    preserveCacheAcrossSessions: Boolean = true,
    onTogglePreserveCacheAcrossSessions: (Boolean) -> Unit = {},
    bufferMetrics: com.example.cache.BufferMetrics = com.example.cache.BufferMetrics(),
    onClearActiveInMemoryBuffer: () -> Unit = {},
    onPurgeStagedDiskCache: () -> Unit = onForceResetAndClearHygiene,
    onCreateGeneralLogFile: (() -> String)? = null
) {
    BackHandler { onBack() }

    val context = androidx.compose.ui.platform.LocalContext.current
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    var generalLogPreviewText by remember { mutableStateOf<String?>(null) }

    val handleCreateGeneralLogFile: () -> Unit = {
        val generatedLog = onCreateGeneralLogFile?.invoke()?.takeIf { it.isNotBlank() } ?: buildString {
            val nowFormatted = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
                .format(java.util.Date(System.currentTimeMillis()))
            val audioPermGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val cameraPermGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            appendLine("=== GEMINI WAKE GENERAL SESSION DIAGNOSTIC LOG FILE ===")
            appendLine("Log Generated: $nowFormatted")
            appendLine("Active Chats: ${chats.size} | Configured Wake Words: ${configuredWakeWords.size}")
            appendLine("Voice Engine: ${voiceEngineMode.displayName} | Sensitivity: ${(wakeWordSensitivity * 100).toInt()}%")
            appendLine("Memory Buffer: Hot RAM=${bufferMetrics.hotMemoryCount}/${bufferMetrics.hotBufferSizeLimit} | Total Disk=${bufferMetrics.totalDiskCount}")
            appendLine()
            appendLine("--- 1. NETWORK & CONNECTIVITY STATE ---")
            appendLine("Local Sync Relay: ${relayServerState?.bindHost ?: "127.0.0.1"}:${relayServerState?.port ?: 8765} | Running=${relayServerState?.isRunning == true}")
            appendLine("Relay Status: ${relayServerState?.statusText ?: "Standby"}")
            appendLine()
            appendLine("--- 2. PERMISSION & AUDIO FOCUS STATES ---")
            appendLine("RECORD_AUDIO Permission: ${if (audioPermGranted) "GRANTED" else "DENIED"}")
            appendLine("CAMERA Permission: ${if (cameraPermGranted) "GRANTED" else "DENIED"}")
            appendLine("Hands-Free Radar Active: $isHandsFreeActive | Mic Listening: $isListening | Sleep Mode: $isSleepMode")
            appendLine()
            appendLine("--- 3. RECENT ERROR LOGS & WARNINGS ---")
            appendLine("Status Message: ${statusMessage.ifBlank { "Normal" }}")
            appendLine("Last Recognized Transcript: \"${lastHeardText.ifBlank { "None" }}\"")
            appendLine("Haptic Confirmations Triggered: ${com.example.voice.AudioCommandSanitizer.hapticTriggerCount} | Last Pattern: ${com.example.voice.AudioCommandSanitizer.lastHapticPattern?.name ?: "NONE"}")
        }
        try {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Gemini Wake General Session Log", generatedLog)
            clipboard?.setPrimaryClip(clip)
            android.widget.Toast.makeText(
                context,
                "General session log copied to clipboard!",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        } catch (_: Exception) {}
        generalLogPreviewText = generatedLog
    }

    if (generalLogPreviewText != null) {
        val logText = generalLogPreviewText!!
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { generalLogPreviewText = null },
            modifier = Modifier.testTag("settings_general_log_preview_dialog"),
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "General Session Diagnostic Log",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Copied to clipboard • Network, permissions & session error telemetry",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            },
            text = {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_general_log_copy_box")
                ) {
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .padding(12.dp)
                        ) {
                            item {
                                Text(
                                    text = logText,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Gemini Wake General Session Log", logText)
                            clipboard?.setPrimaryClip(clip)
                            android.widget.Toast.makeText(
                                context,
                                "General session log copied to clipboard!",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } catch (_: Exception) {}
                    },
                    modifier = Modifier.testTag("settings_general_log_copy_button")
                ) {
                    Text("Copy to Clipboard")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { generalLogPreviewText = null },
                    modifier = Modifier.testTag("settings_general_log_close_button")
                ) {
                    Text("Close")
                }
            }
        )
    }

    val clampedGlobalSensitivity = wakeWordSensitivity.coerceIn(0.50f, 0.98f)
    var localGlobalSensitivity by remember(clampedGlobalSensitivity) {
        mutableFloatStateOf(clampedGlobalSensitivity)
    }
    val activePreset = remember(localGlobalSensitivity) {
        WakeSensitivityLevel.fromSensitivity(localGlobalSensitivity)
    }

    // State for adding a new custom wake word
    var newPhraseInput by rememberSaveable { mutableStateOf("") }
    var newChatTitleInput by rememberSaveable { mutableStateOf("") }
    var createNewChatForWakeWord by rememberSaveable(chats.isEmpty()) { mutableStateOf(chats.isEmpty()) }
    val defaultChatId = remember(chats) {
        chats.firstOrNull { it.isDefaultWakeChat }?.id ?: chats.firstOrNull()?.id ?: 0L
    }
    var selectedTargetChatId by rememberSaveable(defaultChatId) { mutableStateOf(defaultChatId) }
    var newWakeSensitivity by rememberSaveable { mutableFloatStateOf(clampedGlobalSensitivity) }
    var validationMessage by remember { mutableStateOf<String?>(null) }

    // Voice-recorded phrase dictation target ("new_wake", "wake_<id>", or "chat_<id>")
    var voiceDictationTarget by remember { mutableStateOf<String?>(null) }

    // State for inline editing existing custom wake words
    var editingWakeWordId by remember { mutableStateOf<Long?>(null) }
    var editingWakePhraseDraft by remember { mutableStateOf("") }

    // State for inline editing primary chat wake word when no custom WakeWord row exists yet
    var editingChatSessionId by remember { mutableStateOf<Long?>(null) }
    var editingChatWakeDraft by remember { mutableStateOf("") }

    // Automatically populate the active wake word input when voice recognition captures speech
    LaunchedEffect(lastHeardText, voiceDictationTarget) {
        val spoken = lastHeardText.trim()
        val target = voiceDictationTarget
        if (spoken.isNotBlank() && target != null) {
            when {
                target == "new_wake" -> {
                    newPhraseInput = spoken
                    validationMessage = null
                    voiceDictationTarget = null
                }
                target.startsWith("wake_") -> {
                    editingWakePhraseDraft = spoken
                    voiceDictationTarget = null
                }
                target.startsWith("chat_") -> {
                    editingChatWakeDraft = spoken
                    voiceDictationTarget = null
                }
            }
        }
    }

    // State for hands-free command trigger words
    var dictateDraft by remember(dictateTrigger) { mutableStateOf(dictateTrigger) }
    var sendDraft by remember(sendTrigger) { mutableStateOf(sendTrigger) }
    var heartbeatDraft by remember(heartbeatTrigger) { mutableStateOf(heartbeatTrigger) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("settings_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Wake Word & Speech Settings",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Customize wake phrases & trigger sensitivity",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = onOpenDiagnosticLog,
                        modifier = Modifier.testTag("settings_open_diagnostics_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.BugReport,
                            contentDescription = "Open Diagnostic Trace Log",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    TextButton(
                        onClick = onOpenWakeWordStudio,
                        modifier = Modifier.testTag("settings_open_studio_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = "Open Voice Studio",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Voice Studio", style = MaterialTheme.typography.labelMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .testTag("settings_lazy_column"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (isSleepMode) {
                item {
                    SleepModeVisualBanner(
                        isSleepMode = true,
                        sleepReason = sleepModeReason,
                        onWakeUpTap = onExitSleepMode
                    )
                }
            }

            // 1. Speech Recognition Trigger Sensitivity & Live Mic Monitor Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_sensitivity_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "Speech Recognition Trigger Sensitivity",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "Controls how strictly wake phrases & acoustic triggers match",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    text = "${(clampedGlobalSensitivity * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                        .testTag("settings_global_sensitivity_value_text")
                                )
                            }
                        }

                        // Preset Buttons (Low 65%, Medium 78%, High 90%)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            WakeSensitivityLevel.entries.forEach { preset ->
                                val isSelected = activePreset == preset
                                OutlinedButton(
                                    onClick = { onSetWakeWordSensitivityLevel(preset) },
                                    colors = if (isSelected) {
                                        ButtonDefaults.outlinedButtonColors(
                                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    } else {
                                        ButtonDefaults.outlinedButtonColors()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_sensitivity_preset_${preset.name.lowercase(Locale.US)}")
                                ) {
                                    Text(
                                        text = "${preset.label} (${(preset.sensitivityValue * 100).toInt()}%)",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    )
                                }
                            }
                        }

                        // Continuous Fine-Tuning Sensitivity Slider
                        Column {
                            Slider(
                                value = localGlobalSensitivity,
                                onValueChange = { newValue ->
                                    val coerced = newValue.coerceIn(0.50f, 0.98f)
                                    localGlobalSensitivity = coerced
                                    onSetWakeWordSensitivity(coerced)
                                },
                                valueRange = 0.50f..0.98f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_global_sensitivity_slider")
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "50% (More Permissive)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = activePreset.label,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "98% (Strict Match)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Text(
                            text = activePreset.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        // Live Microphone & Trigger Test Bar
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isListening) "Live Trigger Monitor: Listening" else "Live Trigger Monitor: Paused",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                if (statusMessage.isNotBlank()) {
                                    Text(
                                        text = statusMessage,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            FilledTonalButton(
                                onClick = {
                                    val willActivate = !isListening
                                    runCatching {
                                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    }
                                    com.example.voice.AudioCommandSanitizer.playRadarToggleHaptic(
                                        context = context,
                                        isActivating = willActivate,
                                        reason = if (willActivate) "Settings Live Trigger Mic Activated" else "Settings Live Trigger Mic Paused"
                                    )
                                    onToggleListening()
                                },
                                colors = if (isListening) {
                                    ButtonDefaults.filledTonalButtonColors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                } else {
                                    ButtonDefaults.filledTonalButtonColors()
                                },
                                modifier = Modifier.testTag("settings_toggle_listening_button")
                            ) {
                                Icon(
                                    imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = if (isListening) "Pause Listening" else "Test Trigger",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (isListening) "Pause Mic" else "Test Mic")
                            }
                        }

                        VoiceWaveformVisualizer(
                            isListening = isListening,
                            audioRmsDb = audioRmsDb,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (lastHeardText.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Hearing,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Last recognized: \"$lastHeardText\"",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = onOpenDiagnosticLog,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_open_diagnostic_log_card_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("View SpeechRecognizer Thread & Mutex Trace Log")
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { handleCreateGeneralLogFile() }
                                .testTag("settings_create_general_log_file_link")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.BugReport,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Create a general log file",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                                            ),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "Collects session network, permissions & error diagnostics to clipboard",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = "Copy general session log to clipboard",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 2. Add New Custom Wake Word Card
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_add_wake_word_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Add Custom Wake Word",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Assign a custom wake phrase and trigger sensitivity to any chat",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = newPhraseInput,
                                onValueChange = {
                                    newPhraseInput = it
                                    validationMessage = null
                                },
                                label = { Text("Custom Wake Word Phrase") },
                                placeholder = { Text("e.g. Hey Atlas, Code Master, Morning Brief") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_new_wake_word_input")
                            )
                            FilledTonalButton(
                                onClick = {
                                    if (lastHeardText.isNotBlank() && voiceDictationTarget != "new_wake") {
                                        newPhraseInput = lastHeardText.trim()
                                        validationMessage = null
                                    } else {
                                        voiceDictationTarget = if (voiceDictationTarget == "new_wake") null else "new_wake"
                                        if (!isListening) {
                                            onToggleListening()
                                        }
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                modifier = Modifier.testTag("settings_voice_record_new_wake_phrase_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Voice Record Custom Wake Word Phrase",
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (voiceDictationTarget == "new_wake") "Listening…" else "Voice Input",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        // Optional acoustic voice sample recording for the new wake word target chat
                        val targetChatForRecording = selectedTargetChatId.takeIf { it > 0L } ?: defaultChatId
                        if (targetChatForRecording > 0L) {
                            val isRecordingNewSample = activeRecordingSlot == 90
                            val targetChatSamples = remember(allSamples, targetChatForRecording) {
                                allSamples.filter { it.chatId == targetChatForRecording }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Recorded voice samples for target chat: ${targetChatSamples.size}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                OutlinedButton(
                                    onClick = {
                                        if (isRecordingNewSample) {
                                            onStopSnippetRecording(targetChatForRecording, 90)
                                        } else {
                                            onStartSnippetRecording(targetChatForRecording, 90)
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("settings_record_new_wake_sample_button")
                                ) {
                                    Icon(
                                        imageVector = if (isRecordingNewSample) Icons.Default.Stop else Icons.Default.Mic,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = if (isRecordingNewSample) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isRecordingNewSample) {
                                            "Stop (${recordingDurationMs / 1000}s)"
                                        } else {
                                            "Record Voice Sample"
                                        },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }

                        if (chats.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = !createNewChatForWakeWord,
                                    onClick = { createNewChatForWakeWord = false },
                                    label = { Text("Link to Existing Chat") },
                                    modifier = Modifier.testTag("settings_link_existing_chat_chip")
                                )
                                FilterChip(
                                    selected = createNewChatForWakeWord,
                                    onClick = { createNewChatForWakeWord = true },
                                    label = { Text("Create New Chat") },
                                    modifier = Modifier.testTag("settings_create_new_chat_chip")
                                )
                            }
                        }

                        if (createNewChatForWakeWord || chats.isEmpty()) {
                            OutlinedTextField(
                                value = newChatTitleInput,
                                onValueChange = { newChatTitleInput = it },
                                label = { Text("New Chat Title") },
                                placeholder = { Text("e.g. Research Assistant") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_new_wake_chat_title_input")
                            )
                        } else {
                            Text(
                                text = "Select Target Chat:",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                chats.take(4).forEach { chatItem ->
                                    val selected = selectedTargetChatId == chatItem.id
                                    FilterChip(
                                        selected = selected,
                                        onClick = { selectedTargetChatId = chatItem.id },
                                        label = {
                                            Text(
                                                text = chatItem.title,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        },
                                        modifier = Modifier.testTag("settings_target_chat_chip_${chatItem.id}")
                                    )
                                }
                            }
                        }

                        // Per-wake-word initial sensitivity slider
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Wake Phrase Trigger Sensitivity",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                Text(
                                    text = "${(newWakeSensitivity * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = newWakeSensitivity.coerceIn(0.50f, 0.98f),
                                onValueChange = { newWakeSensitivity = it.coerceIn(0.50f, 0.98f) },
                                valueRange = 0.50f..0.98f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_new_wake_sensitivity_slider")
                            )
                        }

                        if (!validationMessage.isNullOrBlank()) {
                            Text(
                                text = validationMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.testTag("settings_wake_word_validation_text")
                            )
                        }

                        Button(
                            onClick = {
                                val cleanedPhrase = newPhraseInput.trim()
                                if (cleanedPhrase.isBlank()) {
                                    validationMessage = "Please enter a wake word phrase."
                                    return@Button
                                }
                                val sens = newWakeSensitivity.coerceIn(0.50f, 0.98f)
                                if (createNewChatForWakeWord || chats.isEmpty()) {
                                    val resolvedChatTitle = newChatTitleInput.trim()
                                        .ifBlank { "$cleanedPhrase Chat" }
                                    onAddCustomWakeWordWithNewChat(cleanedPhrase, resolvedChatTitle, sens)
                                } else {
                                    val targetId = selectedTargetChatId.takeIf { it > 0L } ?: defaultChatId
                                    onAddCustomWakeWord(targetId, cleanedPhrase, sens)
                                }
                                newPhraseInput = ""
                                newChatTitleInput = ""
                                validationMessage = null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_add_wake_word_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save Custom Wake Word")
                        }
                    }
                }
            }

            // 3. Configured Custom Wake Words Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Hearing,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Configured Custom Wake Words (${configuredWakeWords.size})",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }

            if (configuredWakeWords.isEmpty() && chats.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No custom wake words configured yet. Add one above to get started.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // List each configured WakeWord with inline phrase editing, toggle, and individual sensitivity slider
            items(
                items = configuredWakeWords,
                key = { item -> "settings_wake_${item.id}" }
            ) { wakeItem ->
                val linkedChat = chats.firstOrNull { it.id == wakeItem.effectiveTargetChatId }
                val isEditing = editingWakeWordId == wakeItem.id
                val itemSensitivity = wakeItem.sensitivity.coerceIn(0.50f, 0.98f)

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_wake_word_item_${wakeItem.id}"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (wakeItem.isEnabled) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                        }
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
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "\"${wakeItem.phrase}\"",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = if (wakeItem.isEnabled) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                                Text(
                                    text = "Target Chat: ${linkedChat?.title ?: wakeItem.chatTitle.ifBlank { "Default Chat" }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        if (isEditing) {
                                            editingWakeWordId = null
                                        } else {
                                            editingWakeWordId = wakeItem.id
                                            editingWakePhraseDraft = wakeItem.phrase
                                        }
                                    },
                                    modifier = Modifier.testTag("settings_edit_wake_word_${wakeItem.id}")
                                ) {
                                    Icon(
                                        imageVector = if (isEditing) Icons.Default.Close else Icons.Default.Edit,
                                        contentDescription = "Edit Wake Word Phrase",
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteWakeWord(wakeItem.id) },
                                    modifier = Modifier.testTag("settings_delete_wake_word_${wakeItem.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete Wake Word",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Switch(
                                    checked = wakeItem.isEnabled,
                                    onCheckedChange = { enabled ->
                                        onToggleWakeWordEnabled(wakeItem.id, enabled)
                                    },
                                    modifier = Modifier.testTag("settings_wake_word_switch_${wakeItem.id}")
                                )
                            }
                        }

                        AnimatedVisibility(visible = isEditing) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                OutlinedTextField(
                                    value = editingWakePhraseDraft,
                                    onValueChange = { editingWakePhraseDraft = it },
                                    label = { Text("Wake Word Phrase") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_edit_wake_word_input_${wakeItem.id}")
                                )
                                FilledTonalButton(
                                    onClick = {
                                        val targetKey = "wake_${wakeItem.id}"
                                        if (lastHeardText.isNotBlank() && voiceDictationTarget != targetKey) {
                                            editingWakePhraseDraft = lastHeardText.trim()
                                        } else {
                                            voiceDictationTarget = if (voiceDictationTarget == targetKey) null else targetKey
                                            if (!isListening) {
                                                onToggleListening()
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier.testTag("settings_voice_dictate_wake_phrase_${wakeItem.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = "Speak Wake Phrase",
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = if (voiceDictationTarget == "wake_${wakeItem.id}") "Speak…" else "Speak",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Button(
                                    onClick = {
                                        val trimmed = editingWakePhraseDraft.trim()
                                        if (trimmed.isNotBlank()) {
                                            onUpdateWakeWordPhrase(wakeItem.id, trimmed)
                                            editingWakeWordId = null
                                        }
                                    },
                                    modifier = Modifier.testTag("settings_save_wake_word_phrase_${wakeItem.id}")
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = "Save", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Save")
                                }
                            }
                        }

                        // Compact Individual Recorded Wake-Word Detection Sensitivity Slider
                        var localWakeItemSensitivity by remember(wakeItem.id, itemSensitivity) {
                            mutableFloatStateOf(itemSensitivity)
                        }
                        val wakePresetLabel = remember(localWakeItemSensitivity) {
                            when {
                                localWakeItemSensitivity >= 0.85f -> "Strict Guard (Low False Triggers)"
                                localWakeItemSensitivity >= 0.72f -> "Balanced Threshold"
                                else -> "Permissive (Quiet Environment)"
                            }
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Recorded Wake-Word Sensitivity • $wakePresetLabel",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium)
                                )
                                Text(
                                    text = "${(localWakeItemSensitivity * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = localWakeItemSensitivity,
                                onValueChange = { updated ->
                                    val coerced = updated.coerceIn(0.50f, 0.98f)
                                    localWakeItemSensitivity = coerced
                                    onSetIndividualWakeWordSensitivity(wakeItem.id, coerced)
                                },
                                valueRange = 0.50f..0.98f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(32.dp)
                                    .testTag("settings_wake_word_sensitivity_slider_${wakeItem.id}")
                            )
                        }

                        // Voice-Recorded Input & Acoustic Snippet Controls for this Custom Wake Word
                        val effectiveChatIdForSample = wakeItem.effectiveTargetChatId ?: defaultChatId
                        val wakeWordSamples = remember(allSamples, wakeItem.id, wakeItem.phrase, effectiveChatIdForSample) {
                            allSamples.filter { sample ->
                                sample.wakeWordId == wakeItem.id ||
                                    sample.wakePhrase.equals(wakeItem.phrase, ignoreCase = true) ||
                                    (sample.chatId == effectiveChatIdForSample && effectiveChatIdForSample > 0L)
                            }
                        }
                        val slotForWakeItem = ((wakeItem.id % 50L).toInt()).coerceAtLeast(1) + 10
                        val isRecordingThisWakeItem = activeRecordingSlot == slotForWakeItem
                        val latestSample = wakeWordSamples.maxByOrNull { it.recordedAt }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_wake_word_voice_samples_row_${wakeItem.id}"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Voice Recorded Input: ${wakeWordSamples.size} sample(s)",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (wakeWordSamples.isNotEmpty()) {
                                        "Acoustic voice fingerprint active to prevent accidental triggers"
                                    } else {
                                        "Record your voice saying \"${wakeItem.phrase}\" to reduce false triggers"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (latestSample != null) {
                                    val isPlayingSample = currentlyPlayingPath == latestSample.samplePath
                                    OutlinedButton(
                                        onClick = {
                                            if (isPlayingSample) {
                                                onStopSnippetPreview()
                                            } else {
                                                onPlaySnippetPreview(latestSample.samplePath)
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                        modifier = Modifier.testTag("settings_play_wake_sample_${wakeItem.id}")
                                    ) {
                                        Icon(
                                            imageVector = if (isPlayingSample) Icons.Default.Stop else Icons.Default.PlayArrow,
                                            contentDescription = "Preview Recorded Wake Word Sample",
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text(
                                            text = if (isPlayingSample) "Stop" else "Play",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                                FilledTonalButton(
                                    onClick = {
                                        val targetId = effectiveChatIdForSample.takeIf { it > 0L } ?: 1L
                                        if (isRecordingThisWakeItem) {
                                            onStopSnippetRecording(targetId, slotForWakeItem)
                                        } else {
                                            onStartSnippetRecording(targetId, slotForWakeItem)
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("settings_record_wake_sample_${wakeItem.id}")
                                ) {
                                    Icon(
                                        imageVector = if (isRecordingThisWakeItem) Icons.Default.Stop else Icons.Default.Mic,
                                        contentDescription = "Record Voice Input for ${wakeItem.phrase}",
                                        modifier = Modifier.size(14.dp),
                                        tint = if (isRecordingThisWakeItem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isRecordingThisWakeItem) {
                                            "Stop (${recordingDurationMs / 1000}s)"
                                        } else {
                                            "Record Voice"
                                        },
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }

                        if (chats.size > 1) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    chats.take(3).forEach { chatOption ->
                                        val isAssigned = wakeItem.effectiveTargetChatId == chatOption.id
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isAssigned) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant
                                            },
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    onReassignWakeWordToChat(wakeItem.id, chatOption.id)
                                                }
                                        ) {
                                            Text(
                                                text = chatOption.title.take(12),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (isAssigned) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                                val targetChatId = wakeItem.effectiveTargetChatId
                                if (targetChatId != null && targetChatId > 0L) {
                                    TextButton(
                                        onClick = { onOpenChat(targetChatId) },
                                        modifier = Modifier.testTag("settings_open_wake_chat_${wakeItem.id}")
                                    ) {
                                        Text("Open Chat", style = MaterialTheme.typography.labelSmall)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. Primary Chat Session Wake Words (Quick inline editor per chat)
            if (chats.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_chat_wake_words_card"),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Chat Session Primary Wake Words",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            chats.forEach { chatSession ->
                                val isEditingChat = editingChatSessionId == chatSession.id
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = chatSession.title,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                                )
                                                Text(
                                                    text = "Wake Word: \"${chatSession.wakeWord}\"",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    if (isEditingChat) {
                                                        editingChatSessionId = null
                                                    } else {
                                                        editingChatSessionId = chatSession.id
                                                        editingChatWakeDraft = chatSession.wakeWord
                                                    }
                                                },
                                                modifier = Modifier.testTag("settings_edit_chat_wake_${chatSession.id}")
                                            ) {
                                                Icon(
                                                    imageVector = if (isEditingChat) Icons.Default.Close else Icons.Default.Edit,
                                                    contentDescription = "Edit Chat Wake Word",
                                                    modifier = Modifier.size(18.dp)
                                                )
                                            }
                                        }
                                        AnimatedVisibility(visible = isEditingChat) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                OutlinedTextField(
                                                    value = editingChatWakeDraft,
                                                    onValueChange = { editingChatWakeDraft = it },
                                                    label = { Text("Wake Word for ${chatSession.title}") },
                                                    singleLine = true,
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .testTag("settings_chat_wake_input_${chatSession.id}")
                                                )
                                                FilledTonalButton(
                                                    onClick = {
                                                        val targetKey = "chat_${chatSession.id}"
                                                        if (lastHeardText.isNotBlank() && voiceDictationTarget != targetKey) {
                                                            editingChatWakeDraft = lastHeardText.trim()
                                                        } else {
                                                            voiceDictationTarget = if (voiceDictationTarget == targetKey) null else targetKey
                                                            if (!isListening) {
                                                                onToggleListening()
                                                            }
                                                        }
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                                    modifier = Modifier.testTag("settings_voice_dictate_chat_wake_${chatSession.id}")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Mic,
                                                        contentDescription = "Speak Chat Wake Word",
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(3.dp))
                                                    Text(
                                                        text = if (voiceDictationTarget == "chat_${chatSession.id}") "Speak…" else "Speak",
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }
                                                Button(
                                                    onClick = {
                                                        val trimmed = editingChatWakeDraft.trim()
                                                        if (trimmed.isNotBlank()) {
                                                            onUpdateChatSessionWakeWord(chatSession, trimmed)
                                                            editingChatSessionId = null
                                                        }
                                                    },
                                                    modifier = Modifier.testTag("settings_save_chat_wake_${chatSession.id}")
                                                ) {
                                                    Text("Save")
                                                }
                                            }
                                        }

                                        // Per-Chat Primary Wake Word Sensitivity Slider & Voice Sample Recording
                                        val linkedWakeRow = configuredWakeWords.firstOrNull {
                                            it.effectiveTargetChatId == chatSession.id &&
                                                it.phrase.equals(chatSession.wakeWord, ignoreCase = true)
                                        } ?: configuredWakeWords.firstOrNull { it.effectiveTargetChatId == chatSession.id }
                                        val chatInitialSens = (linkedWakeRow?.sensitivity ?: clampedGlobalSensitivity).coerceIn(0.50f, 0.98f)
                                        var localChatSens by remember(chatSession.id, chatInitialSens) {
                                            mutableFloatStateOf(chatInitialSens)
                                        }
                                        val chatSampleSlot = ((chatSession.id % 30L).toInt()).coerceAtLeast(1) + 60
                                        val isRecordingChatSample = activeRecordingSlot == chatSampleSlot
                                        val chatSamplesCount = remember(allSamples, chatSession.id) {
                                            allSamples.count { it.chatId == chatSession.id }
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "Detection Threshold: ${(localChatSens * 100).toInt()}% • $chatSamplesCount voice sample(s)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            OutlinedButton(
                                                onClick = {
                                                    if (isRecordingChatSample) {
                                                        onStopSnippetRecording(chatSession.id, chatSampleSlot)
                                                    } else {
                                                        onStartSnippetRecording(chatSession.id, chatSampleSlot)
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.testTag("settings_record_chat_wake_sample_${chatSession.id}")
                                            ) {
                                                Icon(
                                                    imageVector = if (isRecordingChatSample) Icons.Default.Stop else Icons.Default.Mic,
                                                    contentDescription = "Record Voice Sample for ${chatSession.title}",
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = if (isRecordingChatSample) "Stop" else "Record Voice",
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                            }
                                        }
                                        Slider(
                                            value = localChatSens,
                                            onValueChange = { updated ->
                                                val coerced = updated.coerceIn(0.50f, 0.98f)
                                                localChatSens = coerced
                                                if (linkedWakeRow != null) {
                                                    onSetIndividualWakeWordSensitivity(linkedWakeRow.id, coerced)
                                                } else {
                                                    onAddCustomWakeWord(chatSession.id, chatSession.wakeWord, coerced)
                                                }
                                            },
                                            valueRange = 0.50f..0.98f,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(30.dp)
                                                .testTag("settings_chat_wake_sensitivity_slider_${chatSession.id}")
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 5. Hands-Free Voice Command Triggers Card (Dictate, Send, Heartbeat)
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_hands_free_triggers_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Hands-Free Action Wake Words",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = "Spoken commands for dictation, sending, and heartbeat",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = isHandsFreeActive,
                                onCheckedChange = { enabled ->
                                    runCatching {
                                        hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                    }
                                    com.example.voice.AudioCommandSanitizer.playRadarToggleHaptic(
                                        context = context,
                                        isActivating = enabled,
                                        reason = if (enabled) "Settings Hands-Free Switch ON" else "Settings Hands-Free Switch OFF"
                                    )
                                    onToggleHandsFree(enabled)
                                },
                                modifier = Modifier.testTag("settings_hands_free_switch")
                            )
                        }

                        OutlinedTextField(
                            value = dictateDraft,
                            onValueChange = {
                                dictateDraft = it
                                onUpdateDictateTrigger(it)
                            },
                            label = { Text("Dictate Wake Word") },
                            placeholder = { Text("e.g. Dictate") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_dictate_trigger_input")
                        )

                        OutlinedTextField(
                            value = sendDraft,
                            onValueChange = {
                                sendDraft = it
                                onUpdateSendTrigger(it)
                            },
                            label = { Text("Send Trigger Word") },
                            placeholder = { Text("e.g. Send") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_send_trigger_input")
                        )

                        OutlinedTextField(
                            value = heartbeatDraft,
                            onValueChange = {
                                heartbeatDraft = it
                                onUpdateHeartbeatTrigger(it)
                            },
                            label = { Text("Heartbeat Trigger Word") },
                            placeholder = { Text("e.g. Heartbeat") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_heartbeat_trigger_input")
                        )

                        OutlinedButton(
                            onClick = {
                                dictateDraft = "Dictate"
                                sendDraft = "Send"
                                heartbeatDraft = "Heartbeat"
                                onResetTriggersToDefaults()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_reset_triggers_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reset Action Triggers to Defaults")
                        }
                    }
                }
            }

            // =========================================================================
            // 5. Target Chat to Open on Wake Word Detection (Existing or New Chat)
            // =========================================================================
            item {
                var createNewWakeTargetMode by rememberSaveable { mutableStateOf(false) }
                var newWakeTargetChatTitle by rememberSaveable { mutableStateOf("") }
                var newWakeTargetPhrase by rememberSaveable { mutableStateOf("Hey Gemini") }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_wake_target_chat_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Hearing,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Target Chat on Wake Word Detection",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Select an existing chat to open when a wake word is detected, or specify a new one",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = !createNewWakeTargetMode,
                                onClick = { createNewWakeTargetMode = false },
                                label = { Text("Choose Existing Chat") },
                                modifier = Modifier.testTag("settings_choose_existing_wake_chat_chip")
                            )
                            FilterChip(
                                selected = createNewWakeTargetMode,
                                onClick = { createNewWakeTargetMode = true },
                                label = { Text("Specify New Chat") },
                                modifier = Modifier.testTag("settings_specify_new_wake_chat_chip")
                            )
                        }

                        if (!createNewWakeTargetMode) {
                            chats.forEach { chat ->
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onSetDefaultWakeChat(chat.id) }
                                        .testTag("settings_wake_target_option_${chat.id}"),
                                    color = if (chat.isDefaultWakeChat) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = chat.title,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = if (chat.isDefaultWakeChat) FontWeight.Bold else FontWeight.Medium
                                                )
                                            )
                                            Text(
                                                text = "Wake word: \"${chat.wakeWord}\" • ${chat.totalVoiceCommands} voice turns",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        if (chat.isDefaultWakeChat) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Active default wake target chat",
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = newWakeTargetChatTitle,
                                onValueChange = { newWakeTargetChatTitle = it },
                                label = { Text("New Target Chat Title") },
                                placeholder = { Text("e.g. Hands-Free Assistant") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_new_wake_target_title_input")
                            )
                            OutlinedTextField(
                                value = newWakeTargetPhrase,
                                onValueChange = { newWakeTargetPhrase = it },
                                label = { Text("Wake Word Phrase") },
                                placeholder = { Text("e.g. Hey Gemini") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_new_wake_target_phrase_input")
                            )
                            Button(
                                onClick = {
                                    val cleanTitle = newWakeTargetChatTitle.trim().ifBlank { "Hands-Free Assistant" }
                                    val cleanPhrase = newWakeTargetPhrase.trim().ifBlank { "Hey Gemini" }
                                    onCreateNewWakeTargetChat(cleanTitle, cleanPhrase)
                                    newWakeTargetChatTitle = ""
                                    createNewWakeTargetMode = false
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("settings_create_new_wake_target_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Create Chat & Set as Wake Target")
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // 6. Backup & Usage Statistics Export (JSON / CSV)
            // =========================================================================
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_backup_export_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Backup Chat History & Usage Statistics",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Export all chat turns, voice commands, and 7-day wake-word telemetry as JSON or CSV",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        if (sessionStatisticsState != null) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "7-Day Wake Sessions: ${sessionStatisticsState.wakeTriggeredSessions7d} / ${sessionStatisticsState.totalSessions7d} (${sessionStatisticsState.wakeSessionPercentage7d}%)",
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            text = "All-Time: ${sessionStatisticsState.allTimeSessionsCount} chats • ${sessionStatisticsState.allTimeMessagesCount} turns • ${sessionStatisticsState.allTimeWakeTriggerCount} wake triggers",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onSaveBackupToDevice(com.example.util.BackupExportFormat.JSON) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_save_json_backup_button")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Save JSON")
                            }
                            Button(
                                onClick = { onSaveBackupToDevice(com.example.util.BackupExportFormat.CSV) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_save_csv_backup_button")
                            ) {
                                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Save CSV")
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { onShareBackupFile(com.example.util.BackupExportFormat.JSON) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_share_json_backup_button")
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share JSON")
                            }
                            OutlinedButton(
                                onClick = { onShareBackupFile(com.example.util.BackupExportFormat.CSV) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_share_csv_backup_button")
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Share CSV")
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // 7. Smart Contextual Wake Routing, Favorite Chats & Fallback Chat
            // =========================================================================
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_smart_routing_and_favorites_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Contextual Auto-Selection, Favorites & Fallback Chat",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Priority: 1) Specific custom wake word → 2) Contextual schedule/activity or Favorite Chats prompt → 3) Fallback default chat.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Smart Contextual Chat Routing",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "Auto-selects relevant chat using time of day, workday schedule & recent activity",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = contextualRoutingEnabled,
                                onCheckedChange = onToggleContextualRouting,
                                modifier = Modifier.testTag("settings_contextual_routing_switch")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Prompt Favorite Chats on General Wake Word",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "Shows a quick picker of your starred favorite chats when a general wake word is detected",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = promptFavoritesOnWake,
                                onCheckedChange = onTogglePromptFavoritesOnWake,
                                modifier = Modifier.testTag("settings_prompt_favorites_switch")
                            )
                        }

                        if (chats.isNotEmpty()) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            Text(
                                text = "Select Favorite Chats (${favoriteChatIds.size} starred):",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            chats.forEach { chat ->
                                val isFav = favoriteChatIds.contains(chat.id)
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { onToggleFavoriteChat(chat.id) }
                                        .testTag("settings_favorite_chat_chip_${chat.id}"),
                                    color = if (isFav) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                    shape = RoundedCornerShape(10.dp)
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
                                                text = chat.title,
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = if (isFav) FontWeight.Bold else FontWeight.Medium
                                                )
                                            )
                                            Text(
                                                text = buildString {
                                                    append("Wake: \"${chat.wakeWord}\"")
                                                    if (chat.isDefaultWakeChat) append(" • Fallback Default")
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        FilterChip(
                                            selected = isFav,
                                            onClick = { onToggleFavoriteChat(chat.id) },
                                            label = { Text(if (isFav) "★ Favorite" else "Star") }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =========================================================================
            // 8. Pillar 3: Local Loopback Sync Relay (127.0.0.1:8765) & System Hygiene
            // =========================================================================
            item {
                val activeToken = relayServerState?.sessionToken?.ifBlank { "Generating..." } ?: "Generating..."
                val bindEndpoint = "${relayServerState?.bindHost ?: "127.0.0.1"}:${relayServerState?.port ?: 8765}"
                val isRelayRunning = relayServerState?.isRunning == true

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_local_sync_relay_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Local Sync Relay ($bindEndpoint)",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = relayServerState?.statusText ?: "Loopback HTTP/SSE Relay (127.0.0.1:8765)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isRelayRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = isRelayRunning,
                                onCheckedChange = onToggleRelayServer,
                                modifier = Modifier.testTag("settings_relay_server_switch")
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "Ephemeral Session Token (X-Session-Token / ?token=)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = activeToken,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.testTag("settings_relay_session_token_text")
                                )
                                Text(
                                    text = "Deduplicated Turns: ${relayServerState?.deduplicatedTurnCount ?: 0} • Connected Clients: ${relayServerState?.connectedClientsCount ?: 0}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilledTonalButton(
                                onClick = { onCopyRelaySessionToken(activeToken) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_copy_relay_token_button")
                            ) {
                                Text("Copy Session Token")
                            }
                            OutlinedButton(
                                onClick = onRotateRelaySessionToken,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("settings_rotate_relay_token_button")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Rotate Token")
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Text(
                            text = "System Hygiene (forceResetAndClear)",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Stops the relay socket, clears the audio & hot memory buffers, and flushes ephemeral cache files without wiping Room database tables.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (!lastHygieneResetMessage.isNullOrBlank()) {
                            Text(
                                text = lastHygieneResetMessage,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("settings_hygiene_reset_status_text")
                            )
                        }

                        OutlinedButton(
                            onClick = onForceResetAndClearHygiene,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_force_reset_and_clear_button")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Force Reset Relay, Audio Buffer & Ephemeral Cache")
                        }
                    }
                }
            }

            // =========================================================================
            // 9. Consolidated Voice Output, Recording Duration & Session Persistence
            // =========================================================================
            item {
                var localMasterVolume by remember(masterVolume) {
                    mutableFloatStateOf(masterVolume.coerceIn(0f, 1f))
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_consolidated_voice_and_persistence_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Unified Voice Output & Session Persistence",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Consolidated audio synthesis, snippet recording auto-stop, and session storage",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        // Voice Engine Mode Selector
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            com.example.voice.VoiceEngineMode.entries.forEach { mode ->
                                val selected = voiceEngineMode == mode
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSetVoiceEngineMode(mode) },
                                    label = { Text(mode.displayName) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("settings_voice_engine_chip_${mode.name.lowercase(Locale.US)}")
                                )
                            }
                        }

                        // Master Volume Slider
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Voice Playback Master Volume",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    text = "${(localMasterVolume * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value = localMasterVolume,
                                onValueChange = { v ->
                                    localMasterVolume = v.coerceIn(0f, 1f)
                                    onSetMasterVolume(localMasterVolume)
                                },
                                valueRange = 0f..1f,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(32.dp)
                                    .testTag("settings_master_volume_slider")
                            )
                        }

                        // Voice Snippet Auto-Stop Duration
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Wake Snippet Recording Limit",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(2, 3, 5).forEach { sec ->
                                    FilterChip(
                                        selected = autoStopLimitSec == sec,
                                        onClick = { onSetAutoStopLimit(sec) },
                                        label = { Text("${sec}s") },
                                        modifier = Modifier.testTag("settings_auto_stop_${sec}s_chip")
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Persist New Chats by Default",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "Automatically save new chat sessions & category mappings to Room & Cloud",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = defaultPersistenceEnabled,
                                onCheckedChange = onToggleDefaultPersistence,
                                modifier = Modifier.testTag("settings_default_persistence_switch")
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Preserve Disk Cache Across Sessions",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "Retain paged message history on disk when switching categories or chats",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = preserveCacheAcrossSessions,
                                onCheckedChange = onTogglePreserveCacheAcrossSessions,
                                modifier = Modifier.testTag("settings_preserve_cache_switch")
                            )
                        }
                    }
                }
            }

            // =========================================================================
            // 10. Two-Tier Memory Buffer Settings (Chat Memory & Buffer Settings)
            // =========================================================================
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("settings_memory_buffer_card"),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Two-Tier Memory Buffer Settings",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "Chat Memory & Buffer Settings • Hot RAM Window & Staged Ephemeral Disk Cache",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "Hot Window Limit: ${bufferMetrics.hotBufferSizeLimit} messages • Active Resident Count: ${bufferMetrics.hotMemoryCount} messages",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.testTag("settings_buffer_telemetry_text")
                                )
                                Text(
                                    text = "Total Persisted Messages: ${bufferMetrics.totalDiskCount} • Incoming AI Turns: ${bufferMetrics.incomingDiskCount} • Outgoing User Turns: ${bufferMetrics.outgoingDiskCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        FilledTonalButton(
                            onClick = onClearActiveInMemoryBuffer,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_clear_active_ram_window_button")
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Clear Active RAM Window (Clear Active In-Memory Buffer)")
                        }

                        OutlinedButton(
                            onClick = onPurgeStagedDiskCache,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_purge_ephemeral_disk_cache_button")
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Purge Ephemeral Disk Cache (Purge Staged Disk Cache)")
                        }
                    }
                }
            }
        }
    }
}
