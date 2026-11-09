package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.components.VoiceWaveformVisualizer
import com.example.voice.SpeechRecognitionCoordinator.CoordinatorMutexSnapshot
import com.example.voice.WakeWordLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DiagnosticFilterCategory(val label: String) {
    ALL("All"),
    LIFECYCLE("Lifecycle"),
    CALLBACKS("Callbacks"),
    WAKE_EVENTS("Wake Events"),
    MUTEX_NAV("Mutex & Nav"),
    ERRORS("Errors & Warnings")
}

data class DiagnosticTraceItem(
    val id: String,
    val stage: String,
    val category: DiagnosticFilterCategory,
    val threadLabel: String,
    val isMainThread: Boolean,
    val mutexFlags: Map<String, String>,
    val details: String,
    val formattedTimestamp: String,
    val rmsDb: Float?,
    val rawLine: String
)

object DiagnosticTraceParser {

    fun categorizeStage(stage: String, rawLine: String): DiagnosticFilterCategory {
        val upper = stage.uppercase(Locale.US)
        return when {
            rawLine.contains("OFF-MAIN-THREAD", ignoreCase = true) ||
                upper.contains("ERROR") ||
                upper.contains("EXCEPTION") ||
                upper.contains("UNAVAILABLE") ||
                upper.contains("ABORTED") ||
                upper.contains("RATE_LIMITED") -> DiagnosticFilterCategory.ERRORS
            upper.startsWith("CB_ON_") || upper.contains("PARTIAL_SILENCE") -> DiagnosticFilterCategory.CALLBACKS
            upper.contains("WAKE") || !stage.startsWith("[SR-Trace]") && !upper.contains("_") -> DiagnosticFilterCategory.WAKE_EVENTS
            upper.contains("VOICE_NAV") ||
                upper.contains("MUTEX") ||
                upper.contains("PAUSE") ||
                upper.contains("RESUME") ||
                upper.contains("WINDOW_FOCUS") ||
                upper.contains("PROCESSING") ||
                upper.contains("SLEEP") -> DiagnosticFilterCategory.MUTEX_NAV
            else -> DiagnosticFilterCategory.LIFECYCLE
        }
    }

    fun parseSrTraceString(rawLine: String, index: Int): DiagnosticTraceItem {
        // Format: [SR-Trace] stage=STAGE | thread=main#2(main=true) | listening=false ... | details
        val cleaned = rawLine.removePrefix("[SR-Trace]").trim()
        val parts = cleaned.split("|").map { it.trim() }
        val stagePart = parts.getOrNull(0)?.removePrefix("stage=")?.trim().orEmpty().ifBlank { "SR_TRACE" }
        val threadPart = parts.getOrNull(1)?.removePrefix("thread=")?.trim().orEmpty().ifBlank { "main(main=true)" }
        val isMain = !threadPart.contains("main=false", ignoreCase = true) &&
            !rawLine.contains("OFF-MAIN-THREAD", ignoreCase = true)
        val mutexPart = parts.getOrNull(2).orEmpty()
        val detailsPart = if (parts.size >= 4) {
            parts.drop(3).joinToString(" | ")
        } else {
            parts.lastOrNull().orEmpty()
        }
        val mutexMap = parseMutexTokens(mutexPart)
        return DiagnosticTraceItem(
            id = "sr_${index}_${rawLine.hashCode()}",
            stage = stagePart,
            category = categorizeStage(stagePart, rawLine),
            threadLabel = threadPart,
            isMainThread = isMain,
            mutexFlags = mutexMap,
            details = detailsPart,
            formattedTimestamp = "Live #$index",
            rmsDb = null,
            rawLine = rawLine
        )
    }

    fun parseWakeWordLogEntry(entry: WakeWordLogEntry, index: Int): DiagnosticTraceItem {
        val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        val timeStr = sdf.format(Date(entry.timestamp))
        val isSrTrace = entry.wakeWord.startsWith("[SR-Trace]")
        val stage = if (isSrTrace) {
            entry.wakeWord.removePrefix("[SR-Trace]").trim()
        } else {
            "WAKE_MATCH: ${entry.wakeWord}"
        }
        val rawDetails = entry.chatTitle
        val threadLabel = if (rawDetails.startsWith("[")) {
            rawDetails.substringAfter("[").substringBefore("]")
        } else {
            "main (WakeEngine)"
        }
        val isMain = !threadLabel.contains("main=false", ignoreCase = true)
        val bodyAfterThread = if (rawDetails.contains("]")) {
            rawDetails.substringAfter("]").trim()
        } else {
            rawDetails
        }
        val subParts = bodyAfterThread.split("|").map { it.trim() }
        val mutexMap = if (subParts.size >= 2 && subParts[0].contains("listening=")) {
            parseMutexTokens(subParts[0])
        } else {
            emptyMap()
        }
        val details = if (subParts.size >= 2 && subParts[0].contains("listening=")) {
            subParts.drop(1).joinToString(" | ")
        } else {
            bodyAfterThread
        }
        val rawLine = "[$timeStr] ${entry.wakeWord} | rms=${String.format(Locale.US, "%.1f", entry.rmsDb)}dB | $rawDetails"
        val category = if (!isSrTrace) {
            DiagnosticFilterCategory.WAKE_EVENTS
        } else {
            categorizeStage(stage, rawLine)
        }
        return DiagnosticTraceItem(
            id = "wake_${entry.timestamp}_${index}_${rawLine.hashCode()}",
            stage = stage,
            category = category,
            threadLabel = threadLabel,
            isMainThread = isMain,
            mutexFlags = mutexMap,
            details = details,
            formattedTimestamp = timeStr,
            rmsDb = entry.rmsDb,
            rawLine = rawLine
        )
    }

    private fun parseMutexTokens(mutexPart: String): Map<String, String> {
        if (mutexPart.isBlank()) return emptyMap()
        return mutexPart.split(" ")
            .mapNotNull { token ->
                val eqIdx = token.indexOf('=')
                if (eqIdx > 0 && eqIdx < token.length - 1) {
                    token.substring(0, eqIdx).trim() to token.substring(eqIdx + 1).trim()
                } else {
                    null
                }
            }
            .toMap()
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DiagnosticLogScreen(
    speechTraceHistory: List<String>,
    wakeWordLogs: List<WakeWordLogEntry>,
    mutexSnapshot: CoordinatorMutexSnapshot,
    isSpeechRecognizerListening: Boolean,
    isWakeManagerListening: Boolean,
    hasWindowFocus: Boolean,
    isSleepMode: Boolean,
    isNavigatingViaVoiceCommand: Boolean,
    hasExclusiveAudioFocus: Boolean,
    isHandsFreeActive: Boolean,
    audioRmsDb: Float,
    statusMessage: String,
    lastHeardText: String,
    activeWakePhrase: String = "Hey Gemini",
    onBack: () -> Unit,
    onToggleRecognizer: () -> Unit,
    onSimulateWakeEvent: (String) -> Unit,
    onResetMutexLocks: () -> Unit,
    onClearLogs: () -> Unit,
    onBuildDiagnosticReport: () -> String
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    var selectedCategory by rememberSaveable { mutableStateOf(DiagnosticFilterCategory.ALL) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var expandedTraceId by rememberSaveable { mutableStateOf<String?>(null) }

    val unifiedTraceItems = remember(speechTraceHistory, wakeWordLogs) {
        val wakeItems = wakeWordLogs
            .filter { !it.wakeWord.startsWith("[SR-Trace]") }
            .mapIndexed { idx, entry ->
                DiagnosticTraceParser.parseWakeWordLogEntry(entry, idx)
            }
        val srItems = speechTraceHistory.mapIndexed { idx, raw ->
            DiagnosticTraceParser.parseSrTraceString(raw, idx + 1)
        }
        val fallbackLogItems = if (srItems.isEmpty()) {
            wakeWordLogs.mapIndexed { idx, entry ->
                DiagnosticTraceParser.parseWakeWordLogEntry(entry, idx + 1)
            }
        } else {
            emptyList()
        }
        wakeItems + srItems + fallbackLogItems
    }

    val filteredTraceItems = remember(unifiedTraceItems, selectedCategory, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.US)
        unifiedTraceItems.filter { item ->
            val matchesCat = selectedCategory == DiagnosticFilterCategory.ALL || item.category == selectedCategory
            val matchesQuery = q.isEmpty() ||
                item.stage.lowercase(Locale.US).contains(q) ||
                item.threadLabel.lowercase(Locale.US).contains(q) ||
                item.details.lowercase(Locale.US).contains(q) ||
                item.rawLine.lowercase(Locale.US).contains(q)
            matchesCat && matchesQuery
        }
    }

    val effectiveListening = isSpeechRecognizerListening || isWakeManagerListening || mutexSnapshot.isListening
    val effectiveSleep = isSleepMode || mutexSnapshot.isAsleep

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("diagnostic_back_button")
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
                            imageVector = Icons.Default.BugReport,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Speech & Mutex Diagnostics",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${filteredTraceItems.size} / ${unifiedTraceItems.size} trace events",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val report = onBuildDiagnosticReport()
                            copyTextToClipboard(context, "SpeechRecognizer Diagnostic Log", report)
                            Toast.makeText(context, "Diagnostic trace report copied to clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("diagnostic_copy_all_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "Copy All Logs"
                        )
                    }
                    IconButton(
                        onClick = {
                            val report = onBuildDiagnosticReport()
                            shareDiagnosticReport(context, report)
                        },
                        modifier = Modifier.testTag("diagnostic_share_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share Diagnostic Report"
                        )
                    }
                    IconButton(
                        onClick = {
                            onClearLogs()
                            Toast.makeText(context, "Diagnostic trace logs cleared", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.testTag("diagnostic_clear_logs_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Clear Diagnostic Logs"
                        )
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
                .testTag("diagnostic_trace_lazy_column"),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. Live Engine & Mutex Telemetry Header Card
            item(key = "telemetry_header") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("diagnostic_telemetry_header_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    border = BorderStroke(
                        1.dp,
                        if (effectiveListening && !effectiveSleep) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                effectiveSleep -> MaterialTheme.colorScheme.error
                                                effectiveListening -> MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.outline
                                            }
                                        )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = when {
                                        effectiveSleep -> "SLEEP MODE (PAUSED)"
                                        isNavigatingViaVoiceCommand -> "VOICE NAV MUTEX LOCKED"
                                        isSpeechRecognizerListening -> "SPEECHRECOGNIZER ACTIVE"
                                        isWakeManagerListening -> "PCM WAKE RADAR ACTIVE"
                                        mutexSnapshot.isIntentionallyPaused -> "INTENTIONALLY PAUSED"
                                        else -> "STANDBY / IDLE"
                                    },
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                    color = if (effectiveListening && !effectiveSleep) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurface
                                    },
                                    modifier = Modifier.testTag("diagnostic_engine_status_badge")
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer
                            ) {
                                Text(
                                    text = String.format(Locale.US, "RMS %.1f dB", audioRmsDb),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        if (statusMessage.isNotBlank()) {
                            Text(
                                text = statusMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        VoiceWaveformVisualizer(
                            isListening = effectiveListening && !effectiveSleep,
                            audioRmsDb = audioRmsDb,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (lastHeardText.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Hearing,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Last Heard: \"$lastHeardText\"",
                                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.testTag("diagnostic_last_heard_text")
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                        Text(
                            text = "LIVE THREAD & MUTEX GUARD SNAPSHOT",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("diagnostic_mutex_flags_grid"),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            MutexStatePill("listening", effectiveListening, highlightWhenTrue = true)
                            MutexStatePill("windowFocus", mutexSnapshot.isWindowFocused && hasWindowFocus, highlightWhenTrue = true)
                            MutexStatePill("paused", mutexSnapshot.isIntentionallyPaused, isWarningWhenTrue = true)
                            MutexStatePill("processing", mutexSnapshot.isProcessing, isWarningWhenTrue = true)
                            MutexStatePill("asleep", effectiveSleep, isWarningWhenTrue = true)
                            MutexStatePill("dictating", mutexSnapshot.isInChatDictationMode, highlightWhenTrue = true)
                            MutexStatePill("navMutex", isNavigatingViaVoiceCommand, isWarningWhenTrue = true)
                            MutexStatePill("audioFocus", hasExclusiveAudioFocus, highlightWhenTrue = true)
                            MutexStatePill("handsFree", isHandsFreeActive, highlightWhenTrue = true)
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Idle Timeouts: ${mutexSnapshot.consecutiveIdleTimeouts}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Transient Err: ${mutexSnapshot.consecutiveTransientErrors}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Total Err: ${mutexSnapshot.totalErrors}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = if (mutexSnapshot.totalErrors > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 2. Interactive Verification & Test Controls Bar
            item(key = "interactive_controls") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("diagnostic_test_controls_card"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Interactive SpeechRecognizer & Wake-Word Verification",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onToggleRecognizer,
                                colors = if (effectiveListening) {
                                    ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error,
                                        contentColor = MaterialTheme.colorScheme.onError
                                    )
                                } else {
                                    ButtonDefaults.buttonColors()
                                },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("diagnostic_toggle_recognizer_button")
                            ) {
                                Icon(
                                    imageVector = if (effectiveListening) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (effectiveListening) "Stop Recognizer" else "Start Recognizer",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }

                            FilledTonalButton(
                                onClick = { onSimulateWakeEvent(activeWakePhrase) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("diagnostic_simulate_wake_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Simulate Wake Event",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                onResetMutexLocks()
                                Toast.makeText(context, "Audio & navigation mutex locks reset", Toast.LENGTH_SHORT).show()
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("diagnostic_reset_mutex_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Force Unlock & Reset Mutex Guards",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
            }

            // 3. Search & Category Filter Chips
            item(key = "search_and_filters") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        placeholder = { Text("Search stage, thread (main=true), wake phrase, or mutex…") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("diagnostic_search_input")
                    )

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("diagnostic_category_filter_row")
                    ) {
                        items(DiagnosticFilterCategory.entries) { category ->
                            FilterChip(
                                selected = selectedCategory == category,
                                onClick = { selectedCategory = category },
                                label = { Text(category.label) },
                                modifier = Modifier.testTag("diagnostic_filter_chip_${category.name.lowercase(Locale.US)}")
                            )
                        }
                    }
                }
            }

            // 4. Trace Entries List or Empty State
            if (filteredTraceItems.isEmpty()) {
                item(key = "empty_trace_state") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("diagnostic_empty_state_card"),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(32.dp)
                            )
                            Text(
                                text = "No matching trace logs yet",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Tap \"Start Recognizer\" or \"Simulate Wake Event\" above to verify SpeechRecognizer thread & mutex callbacks in real time.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(
                    items = filteredTraceItems,
                    key = { it.id }
                ) { item ->
                    val isExpanded = expandedTraceId == item.id
                    DiagnosticTraceCard(
                        item = item,
                        isExpanded = isExpanded,
                        onToggleExpand = {
                            expandedTraceId = if (isExpanded) null else item.id
                        },
                        onCopyEntry = {
                            copyTextToClipboard(context, "SR-Trace Entry", item.rawLine)
                            Toast.makeText(context, "Copied trace entry to clipboard", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagnosticTraceCard(
    item: DiagnosticTraceItem,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onCopyEntry: () -> Unit
) {
    val isErrorOrOffMain = !item.isMainThread || item.category == DiagnosticFilterCategory.ERRORS
    val isWakeEvent = item.category == DiagnosticFilterCategory.WAKE_EVENTS

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleExpand() }
            .testTag("diagnostic_trace_item_card"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isErrorOrOffMain -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                isWakeEvent -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else -> MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        border = BorderStroke(
            width = 1.dp,
            color = when {
                isErrorOrOffMain -> MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                isWakeEvent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = when {
                            isErrorOrOffMain -> MaterialTheme.colorScheme.error
                            isWakeEvent -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.secondaryContainer
                        }
                    ) {
                        Text(
                            text = item.stage,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            color = when {
                                isErrorOrOffMain -> MaterialTheme.colorScheme.onError
                                isWakeEvent -> MaterialTheme.colorScheme.onPrimary
                                else -> MaterialTheme.colorScheme.onSecondaryContainer
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (item.isMainThread) {
                            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.errorContainer
                        }
                    ) {
                        Text(
                            text = item.threadLabel,
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = if (item.isMainThread) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onErrorContainer
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onCopyEntry,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "Copy trace line",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (item.details.isNotBlank()) {
                Text(
                    text = item.details,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (item.mutexFlags.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item.mutexFlags.forEach { (k, v) ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = "$k=$v",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    Text(
                        text = item.rawLine,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MutexStatePill(
    name: String,
    value: Boolean,
    highlightWhenTrue: Boolean = false,
    isWarningWhenTrue: Boolean = false
) {
    val containerColor = when {
        value && isWarningWhenTrue -> MaterialTheme.colorScheme.errorContainer
        value && highlightWhenTrue -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        value && isWarningWhenTrue -> MaterialTheme.colorScheme.onErrorContainer
        value && highlightWhenTrue -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = containerColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Text(
            text = "$name=$value",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = if (value) FontWeight.Bold else FontWeight.Normal
            ),
            color = contentColor,
            modifier = Modifier
                .padding(horizontal = 7.dp, vertical = 3.dp)
                .testTag("diagnostic_mutex_pill_$name")
        )
    }
}

private fun copyTextToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun shareDiagnosticReport(context: Context, reportText: String) {
    runCatching {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Gemini Wake SpeechRecognizer & Mutex Diagnostic Report")
            putExtra(Intent.EXTRA_TEXT, reportText)
        }
        val chooser = Intent.createChooser(sendIntent, "Share Diagnostic Trace Report").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
