package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Launch
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SyncAlt
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.coupler.ChromeBridgeSocketManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.coupler.GeminiCouplerBridge
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.importer.ParsedGeminiChat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GeminiCouplerDialog(
    currentChat: ChatSession?,
    messages: List<ChatMessage>,
    initialPayload: String = "",
    initialTab: Int = 0,
    onDismiss: () -> Unit,
    onAppendToCurrentChat: (List<ChatMessage>) -> Unit,
    onCreateCoupledChat: (String, String, List<ChatMessage>) -> Unit,
    onRecordHandoff: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(initialTab) }

    val bridgeState by ChromeBridgeSocketManager.bridgeState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        ChromeBridgeSocketManager.start(context)
    }

    // Handoff state
    var taskPromptInput by remember { mutableStateOf("") }
    var showPreview by remember { mutableStateOf(false) }

    // Piggyback Load state
    var loadPayloadInput by remember { mutableStateOf(initialPayload) }
    var isParsing by remember { mutableStateOf(false) }
    var parsedResult by remember { mutableStateOf<ParsedGeminiChat?>(null) }
    var parseError by remember { mutableStateOf<String?>(null) }

    // Auto-parse if initial payload is provided (e.g. from share intent)
    LaunchedEffect(initialPayload) {
        if (initialPayload.isNotBlank()) {
            loadPayloadInput = initialPayload
            selectedTab = 1 // Switch to Piggyback Load tab
            isParsing = true
            parseError = null
            scope.launch {
                val parsed = GeminiCouplerBridge.parsePiggybackPayload(initialPayload)
                if (parsed != null && parsed.messages.isNotEmpty()) {
                    parsedResult = parsed
                } else {
                    parseError = "Could not automatically detect turns. Try raw JSON or share URL."
                }
                isParsing = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = "Gemini Coupler",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column {
                    Text(
                        text = "Gemini External Coupler",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Piggyback Save/Load & App Handoff",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Handoff (Push)") },
                        icon = { Icon(Icons.Default.Launch, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Piggyback (Pull)") },
                        icon = { Icon(Icons.Default.CallReceived, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Chrome Bridge") },
                        icon = { Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        text = { Text("Guide") },
                        icon = { Icon(Icons.Default.HelpOutline, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                when (selectedTab) {
                    0 -> {
                        // TAB 0: Handoff / Save to Gemini
                        if (currentChat == null) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(
                                        text = "No Active Chat Selected",
                                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Open a chat project first to hand off its context to the Gemini Android app or Gemini Web.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "Coupled Project: ${currentChat.title}",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "${messages.size} messages in buffer • Wake Word: \"${currentChat.wakeWord}\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = taskPromptInput,
                                onValueChange = { taskPromptInput = it },
                                label = { Text("Next Task / Prompt to Gemini (Optional)") },
                                placeholder = { Text("e.g. Please outline the next 3 steps for our architecture...") },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("coupler_task_prompt_input"),
                                minLines = 2,
                                maxLines = 4
                            )

                            Spacer(modifier = Modifier.height(12.dp))

                            // Main Coupler Handoff Action
                            Button(
                                onClick = {
                                    val success = GeminiCouplerBridge.launchGeminiAppHandoff(
                                        context = context,
                                        chat = currentChat,
                                        messages = messages,
                                        activeTaskPrompt = taskPromptInput.ifBlank { null }
                                    )
                                    if (success) {
                                        onRecordHandoff()
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("coupler_handoff_gemini_app_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Handoff to Gemini Android App")
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Reset & Reconnect Chrome Quick Button
                            FilledTonalButton(
                                onClick = {
                                    GeminiCouplerBridge.resetAndReconnectChrome(context, currentChat)
                                    onRecordHandoff()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("coupler_quick_reset_reconnect_chrome_button")
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Reset & Reconnect Chrome (Port: ${bridgeState.boundPort})", fontSize = 13.sp)
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        GeminiCouplerBridge.copyPromptPackageToClipboard(
                                            context = context,
                                            chat = currentChat,
                                            messages = messages,
                                            activeTaskPrompt = taskPromptInput.ifBlank { null }
                                        )
                                        onRecordHandoff()
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("coupler_copy_web_prompt_button")
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy Web Prompt", fontSize = 12.sp)
                                }

                                OutlinedButton(
                                    onClick = {
                                        GeminiCouplerBridge.copyJsonSnapshotToClipboard(
                                            context = context,
                                            chat = currentChat,
                                            messages = messages
                                        )
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("coupler_copy_json_button")
                                ) {
                                    Icon(Icons.Default.DataObject, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Copy JSON", fontSize = 12.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            TextButton(
                                onClick = { showPreview = !showPreview },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (showPreview) "Hide Prompt Payload Preview" else "View Formatted Prompt Preview")
                            }

                            if (showPreview) {
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(140.dp)
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    val previewText = GeminiCouplerBridge.formatGeminiPromptPackage(
                                        chat = currentChat,
                                        messages = messages,
                                        activeTaskPrompt = taskPromptInput.ifBlank { null }
                                    )
                                    Column(
                                        modifier = Modifier
                                            .padding(8.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        Text(
                                            text = previewText,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        // TAB 1: Piggyback Load (Pull / Import from Gemini)
                        Text(
                            text = "Piggyback Load from Gemini",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Paste text copied from gemini.google.com, Gemini app share text, or a Coupler JSON snapshot.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                                    if (clipText.isNotBlank()) {
                                        loadPayloadInput = clipText
                                        // Auto-parse on paste
                                        isParsing = true
                                        parseError = null
                                        scope.launch {
                                            val parsed = GeminiCouplerBridge.parsePiggybackPayload(clipText)
                                            if (parsed != null && parsed.messages.isNotEmpty()) {
                                                parsedResult = parsed
                                            } else {
                                                parseError = "Could not parse turns. Paste raw Gemini text or JSON snapshot."
                                            }
                                            isParsing = false
                                        }
                                    } else {
                                        Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("coupler_paste_clipboard_button")
                            ) {
                                Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Paste Clipboard")
                            }

                            FilledTonalButton(
                                onClick = {
                                    if (loadPayloadInput.isBlank()) return@FilledTonalButton
                                    isParsing = true
                                    parseError = null
                                    scope.launch {
                                        val parsed = GeminiCouplerBridge.parsePiggybackPayload(loadPayloadInput)
                                        if (parsed != null && parsed.messages.isNotEmpty()) {
                                            parsedResult = parsed
                                        } else {
                                            parseError = "Could not parse payload. Please verify content format."
                                        }
                                        isParsing = false
                                    }
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("coupler_parse_button"),
                                enabled = loadPayloadInput.isNotBlank() && !isParsing
                            ) {
                                if (isParsing) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Analyze")
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = loadPayloadInput,
                            onValueChange = {
                                loadPayloadInput = it
                                parsedResult = null
                                parseError = null
                            },
                            label = { Text("Gemini Response / Coupler Payload") },
                            placeholder = { Text("Paste response from gemini.google.com or shared transcript here...") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(130.dp)
                                .testTag("coupler_load_payload_input")
                        )

                        if (parseError != null) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "⚠️ $parseError",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        // Parse Result Card
                        parsedResult?.let { res ->
                            Spacer(modifier = Modifier.height(10.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Payload Ready: ${res.messages.size} Messages",
                                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                        )
                                    }

                                    val userTurns = res.messages.count { it.sender == "user" }
                                    val geminiTurns = res.messages.count { it.sender != "user" }
                                    Text(
                                        text = "$userTurns User queries • $geminiTurns Gemini responses",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    // Action Choice 1: Append to Current Chat (if available)
                                    if (currentChat != null) {
                                        Button(
                                            onClick = {
                                                onAppendToCurrentChat(res.messages)
                                                Toast.makeText(
                                                    context,
                                                    "Piggybacked ${res.messages.size} messages into \"${currentChat.title}\"!",
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                                onDismiss()
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .testTag("coupler_append_current_button")
                                        ) {
                                            Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Append to \"${currentChat.title}\"")
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                    }

                                    // Action Choice 2: Create New Coupled Chat
                                    OutlinedButton(
                                        onClick = {
                                            val title = if (res.title.isNotBlank()) res.title else "Coupled Gemini Project"
                                            val wakeWord = if (res.suggestedWakeWord.isNotBlank()) res.suggestedWakeWord else "Coupled Project"
                                            onCreateCoupledChat(title, wakeWord, res.messages)
                                            Toast.makeText(
                                                context,
                                                "Created new coupled session: $title",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            onDismiss()
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("coupler_create_new_button")
                                    ) {
                                        Icon(Icons.Default.Storage, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Create New Coupled Project")
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        // TAB 2: Chrome Bridge Socket Manager
                        Text(
                            text = "Chrome Loopback Bridge Manager",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Manages external browser connections with dynamic ephemeral port binding & cryptographic nonce security.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Bridge Status Card
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
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
                                                .background(if (bridgeState.isRunning) Color(0xFF22C55E) else Color(0xFFEF4444))
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (bridgeState.isRunning) "SOCKET ACTIVE (127.0.0.1)" else "SOCKET STOPPED",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = if (bridgeState.isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                        )
                                    }

                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = "Port: ${bridgeState.boundPort}",
                                            style = MaterialTheme.typography.labelMedium.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Dynamic Nonce Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Security,
                                        contentDescription = "Security Nonce",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Session Nonce: ",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Text(
                                        text = if (bridgeState.sessionNonce.length > 12) "${bridgeState.sessionNonce.take(12)}..." else bridgeState.sessionNonce,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                // Real-time stats row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Surface(
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text("Active Streams", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text("${bridgeState.activeClientsCount}", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                        }
                                    }
                                    Surface(
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text("Ring Buffer", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text("${bridgeState.bufferedPacketsCount} pkts", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                        }
                                    }
                                    Surface(
                                        modifier = Modifier.weight(1f),
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            Text("Bridge Cache", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Text("${bridgeState.cacheDirSizeBytes} B", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Primary Action: Reset & Reconnect Chrome
                        Button(
                            onClick = {
                                GeminiCouplerBridge.resetAndReconnectChrome(context, currentChat)
                                onRecordHandoff()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("coupler_reset_reconnect_chrome_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Reset & Reconnect Chrome (Port: ${bridgeState.boundPort})")
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Secondary actions row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    ChromeBridgeSocketManager.launchChromeBridge(context, currentChat)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("coupler_open_chrome_button")
                            ) {
                                Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Open Chrome", fontSize = 12.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Chrome Bridge URL", ChromeBridgeSocketManager.getBridgeUrl())
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Bridge URL copied!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("coupler_copy_chrome_url_button")
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy URL", fontSize = 12.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Live Ring Buffer Logs
                        val packets = remember(bridgeState.bufferedPacketsCount, bridgeState.lastResetTimestamp) {
                            ChromeBridgeSocketManager.getBufferedPackets().reversed()
                        }
                        Text(
                            text = "Recent Socket & Packet Events (${packets.size})",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(110.dp)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            if (packets.isEmpty()) {
                                Box(modifier = Modifier.padding(12.dp), contentAlignment = Alignment.Center) {
                                    Text("No packet events recorded yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            } else {
                                val logDateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                                Column(
                                    modifier = Modifier
                                        .padding(8.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    for (pkt in packets) {
                                        val timeStr = logDateFormat.format(Date(pkt.timestamp))
                                        Text(
                                            text = "[$timeStr] ${pkt.type} - ${pkt.summary}",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 10.sp
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    3 -> {
                        // TAB 3: Guide & Architecture Explanation
                        Text(
                            text = "How the Gemini Coupler Works",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Because Google isolates the consumer Gemini App and web histories from developer APIs, the Coupler acts as an external bridge between them:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        CouplerStepCard(
                            stepNumber = "1",
                            title = "Handoff to Gemini",
                            description = "Tap 'Handoff to Gemini Android App' or 'Copy Web Prompt'. The coupler packages your project title, wake word, memory context, and active task into a formatted package."
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        CouplerStepCard(
                            stepNumber = "2",
                            title = "Work in Gemini (Web or App)",
                            description = "Use Gemini's full capabilities (web search, Deep Research, tool use) on the task. Gemini understands the full context from the coupler package."
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        CouplerStepCard(
                            stepNumber = "3",
                            title = "Piggyback Load Back Here",
                            description = "Copy Gemini's output or share the chat link to this app. Use 'Piggyback Load' to append the response directly into your project's local buffer and speech triggers."
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("coupler_dismiss_button")
            ) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun CouplerStepCard(
    stepNumber: String,
    title: String,
    description: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stepNumber,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
