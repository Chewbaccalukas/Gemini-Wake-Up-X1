package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.importer.ChunkedChatImporter
import com.example.importer.GeminiChatParser
import com.example.importer.ImportPacing
import com.example.importer.ImportProgress
import com.example.importer.ParsedGeminiChat
import com.example.util.CompressionStats
import com.example.util.GzipUtil
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
fun ImportGeminiChatDialog(
    initialInput: String = "",
    defaultPersistence: Boolean = true,
    onDismiss: () -> Unit,
    onImportComplete: (ChatSession, List<ChatMessage>) -> Unit,
    onChunkedImportStart: ((ChatSession, List<ChatMessage>, ImportPacing, (ImportProgress) -> Unit, (Long) -> Unit) -> Unit)? = null,
    onBrowseImportFile: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val parser = remember { GeminiChatParser() }
    val chunkedImporter = remember { ChunkedChatImporter() }

    var inputText by remember { mutableStateOf(initialInput) }
    var isParsing by remember { mutableStateOf(false) }
    var parsedChat by remember { mutableStateOf<ParsedGeminiChat?>(null) }
    var compressionStats by remember { mutableStateOf<CompressionStats?>(null) }

    var title by remember { mutableStateOf("") }
    var wakeWord by remember { mutableStateOf("") }
    var isPersistent by remember { mutableStateOf(defaultPersistence) }
    var selectedPacing by remember { mutableStateOf(ImportPacing.SAFE_SLOW) }

    // Progressive Chunked Import State
    var isImportingInProgress by remember { mutableStateOf(false) }
    var currentImportProgress by remember { mutableStateOf<ImportProgress?>(null) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    var parseError by remember { mutableStateOf<String?>(null) }

    fun runParse(input: String) {
        if (input.isBlank()) return
        isParsing = true
        parseError = null
        scope.launch {
            val result = parser.parseGeminiInput(input)
            isParsing = false
            if (result != null && result.messages.isNotEmpty()) {
                parsedChat = result
                title = result.title
                wakeWord = result.suggestedWakeWord

                // Calculate compression statistics
                val combinedText = result.messages.joinToString("\n") { it.content }
                compressionStats = GzipUtil.calculateStats(combinedText)
            } else {
                parseError = "Could not parse Gemini conversation. Make sure it's a valid Gemini share link, transcript, or JSON export."
            }
        }
    }

    LaunchedEffect(initialInput) {
        if (initialInput.isNotBlank()) {
            runParse(initialInput)
        }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isImportingInProgress) onDismiss()
        },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text("Load Existing Gemini Chat", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Anti-Choke Throttling & Compression",
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
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (!isImportingInProgress) {
                    Text(
                        text = "Paste a Gemini share link, copied conversation transcript, or Google Takeout export below:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = {
                            inputText = it
                            if (parsedChat != null) {
                                parsedChat = null
                                compressionStats = null
                            }
                        },
                        placeholder = { Text("https://gemini.google.com/share/... or paste transcript") },
                        minLines = 3,
                        maxLines = 5,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("import_gemini_input_textfield")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                                if (clip.isNotBlank()) {
                                    inputText = clip
                                    runParse(clip)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Paste")
                        }

                        Button(
                            onClick = { runParse(inputText) },
                            enabled = inputText.isNotBlank() && !isParsing,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("analyze_gemini_chat_button")
                        ) {
                            if (isParsing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Parse Chat")
                            }
                        }
                    }

                    if (onBrowseImportFile != null) {
                        FilledTonalButton(
                            onClick = {
                                onDismiss()
                                onBrowseImportFile()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("upload_import_file_button")
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Upload File (.md, .txt, .pdf, .doc, .html, .json)")
                        }
                    }

                    if (parseError != null) {
                        Text(
                            text = parseError!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    // Parsed Chat Configuration Card
                    AnimatedVisibility(
                        visible = parsedChat != null,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        parsedChat?.let { chat ->
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = "${chat.messages.size} Messages Found",
                                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        // Compression Badge
                                        compressionStats?.let { stats ->
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(MaterialTheme.colorScheme.surface)
                                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                            ) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        Icons.Default.Compress,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(12.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(
                                                        text = "-${stats.reductionPercentage.toInt()}% GZIP",
                                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // Compression Details
                                    compressionStats?.let { stats ->
                                        Text(
                                            text = "Compressed in-flight payload: ${GzipUtil.formatBytes(stats.compressedSizeBytes)} (from ${GzipUtil.formatBytes(stats.originalSizeBytes)})",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    OutlinedTextField(
                                        value = title,
                                        onValueChange = { title = it },
                                        label = { Text("Chat Title") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    OutlinedTextField(
                                        value = wakeWord,
                                        onValueChange = { wakeWord = it },
                                        label = { Text("Assigned Wake Trigger") },
                                        leadingIcon = {
                                            Icon(Icons.Default.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    // Persistence Toggle
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f))
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Storage,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Column {
                                                Text(
                                                    text = if (isPersistent) "Disk Persistent Storage" else "Ephemeral RAM Only",
                                                    style = MaterialTheme.typography.titleSmall
                                                )
                                                Text(
                                                    text = if (isPersistent) "Saves permanently to device SQLite" else "Purges from RAM when chat is closed",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                        Switch(
                                            checked = isPersistent,
                                            onCheckedChange = { isPersistent = it },
                                            modifier = Modifier.testTag("import_persistence_switch")
                                        )
                                    }

                                    // Pacing Selection (Anti-Choke)
                                    Text(
                                        text = "Import Pacing (Anti-Choke)",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        ImportPacing.values().forEach { pacing ->
                                            val isSelected = selectedPacing == pacing
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        if (isSelected) MaterialTheme.colorScheme.primary
                                                        else MaterialTheme.colorScheme.surface
                                                    )
                                                    .border(
                                                        width = if (isSelected) 1.5.dp else 1.dp,
                                                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                                                        shape = RoundedCornerShape(8.dp)
                                                    )
                                                    .clickable { selectedPacing = pacing }
                                                    .padding(vertical = 6.dp, horizontal = 4.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = when (pacing) {
                                                        ImportPacing.SAFE_SLOW -> "Safe (150ms)"
                                                        ImportPacing.BALANCED -> "Balanced"
                                                        ImportPacing.TURBO -> "Turbo"
                                                    },
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Ingesting Progress Screen
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                        Text(
                            text = "Chunked Ingestion in Progress...",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Pacing batches to prevent memory choking on long threads.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        currentImportProgress?.let { prog ->
                            LinearProgressIndicator(
                                progress = { prog.percentage },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .testTag("import_chunk_progress"),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Chunk ${prog.currentChunkIndex} of ${prog.totalChunks}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = "${(prog.percentage * 100).toInt()}% (${prog.importedMessageCount}/${prog.totalMessageCount})",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = prog.currentPreviewText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2
                                )
                            }
                        } ?: run {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (!isImportingInProgress) {
                Button(
                    onClick = {
                        parsedChat?.let { parsed ->
                            val now = System.currentTimeMillis()
                            val newSession = ChatSession(
                                title = title.ifBlank { parsed.title },
                                wakeWord = wakeWord.ifBlank { parsed.suggestedWakeWord },
                                isDefaultWakeChat = false,
                                isPersistent = isPersistent,
                                systemInstruction = parsed.systemInstruction,
                                iconName = "Sparkles",
                                themeColorHex = "#38BDF8",
                                createdAt = now,
                                lastUpdatedAt = now
                            )

                            if (onChunkedImportStart != null) {
                                isImportingInProgress = true
                                onChunkedImportStart.invoke(
                                    newSession,
                                    parsed.messages,
                                    selectedPacing,
                                    { progress -> currentImportProgress = progress },
                                    { _ -> isImportingInProgress = false }
                                )
                            } else {
                                // Fallback chunked import inside dialog
                                isImportingInProgress = true
                                importJob = scope.launch {
                                    val totalMsgs = parsed.messages
                                    val chunks = totalMsgs.chunked(selectedPacing.chunkSize)
                                    for ((idx, _) in chunks.withIndex()) {
                                        currentImportProgress = ImportProgress(
                                            currentChunkIndex = idx + 1,
                                            totalChunks = chunks.size,
                                            importedMessageCount = (idx + 1) * selectedPacing.chunkSize,
                                            totalMessageCount = totalMsgs.size,
                                            percentage = (idx + 1).toFloat() / chunks.size,
                                            currentPreviewText = "Processing batch ${idx + 1}...",
                                            isComplete = (idx == chunks.size - 1)
                                        )
                                        kotlinx.coroutines.delay(selectedPacing.delayMs)
                                    }
                                    isImportingInProgress = false
                                    onImportComplete(newSession, parsed.messages)
                                }
                            }
                        }
                    },
                    enabled = parsedChat != null,
                    modifier = Modifier.testTag("confirm_import_chat_button")
                ) {
                    Text("Start Chunked Import")
                }
            } else {
                TextButton(
                    onClick = {
                        importJob?.cancel()
                        isImportingInProgress = false
                    }
                ) {
                    Text("Cancel Import")
                }
            }
        },
        dismissButton = {
            if (!isImportingInProgress) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    )
}
