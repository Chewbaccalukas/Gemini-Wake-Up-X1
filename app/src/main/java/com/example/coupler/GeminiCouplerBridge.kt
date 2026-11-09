package com.example.coupler

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.importer.GeminiChatParser
import com.example.importer.ParsedGeminiChat
import com.example.util.ImageScaler
import com.example.util.ScaledImageResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Metadata for a staged screenshot/image.
 */
data class StagedImageInfo(
    val uri: Uri,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val mimeType: String = "image/jpeg",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * GeminiCouplerBridge provides a two-way "piggyback" coupler system connecting
 * this application with both the official consumer Gemini Android App and the Gemini Web App.
 *
 * Capabilities:
 * 1. Intent-based handoff to the Gemini Android app (com.google.android.apps.bard or system chooser).
 * 2. Formatted prompt packaging for Gemini Web (gemini.google.com) to resume tasks with full context.
 * 3. Structured JSON snapshot export/import for cross-session state preservation.
 * 4. Piggyback loading to append incoming Gemini responses directly into local project memory.
 * 5. Isolated image/screenshot staging channel decoupled from text & voice streams.
 */
object GeminiCouplerBridge {

    const val GEMINI_APP_PACKAGE = "com.google.android.apps.bard"
    const val COUPLER_VERSION = "2.0"

    // Decoupled Image Staging Channel & Pipeline
    private val _stagedImageUri = MutableStateFlow<Uri?>(null)
    val stagedImageUri: StateFlow<Uri?> = _stagedImageUri.asStateFlow()

    private val _stagedImageInfo = MutableStateFlow<StagedImageInfo?>(null)
    val stagedImageInfo: StateFlow<StagedImageInfo?> = _stagedImageInfo.asStateFlow()

    val imagePayloadChannel = Channel<ByteArray>(Channel.CONFLATED)

    @Volatile
    var stagedScreenshotBytes: ByteArray? = null
        private set

    /**
     * Downscales and stages an image from content/file Uri into isolated cache staging.
     */
    fun stageImage(context: Context, sourceUri: Uri): ScaledImageResult? {
        val scaled = ImageScaler.processAndScaleImage(context, sourceUri) ?: return null
        stagedScreenshotBytes = scaled.bytes

        val shareableUri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", scaled.file)
        } catch (_: Exception) {
            scaled.uri
        }

        _stagedImageUri.value = shareableUri
        _stagedImageInfo.value = StagedImageInfo(
            uri = shareableUri,
            width = scaled.width,
            height = scaled.height,
            sizeBytes = scaled.sizeBytes
        )
        imagePayloadChannel.trySend(scaled.bytes)
        return scaled
    }

    /**
     * Stages raw binary bytes (e.g. from Chrome Socket binary frame) into isolated cache staging.
     */
    fun stageImageBytes(context: Context, rawBytes: ByteArray): ScaledImageResult? {
        val scaled = ImageScaler.processAndScaleBytes(context, rawBytes) ?: return null
        stagedScreenshotBytes = scaled.bytes

        val shareableUri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", scaled.file)
        } catch (_: Exception) {
            scaled.uri
        }

        _stagedImageUri.value = shareableUri
        _stagedImageInfo.value = StagedImageInfo(
            uri = shareableUri,
            width = scaled.width,
            height = scaled.height,
            sizeBytes = scaled.sizeBytes
        )
        imagePayloadChannel.trySend(scaled.bytes)
        return scaled
    }

    /**
     * Clears staged screenshot files, in-memory buffers, and resets StateFlows.
     */
    fun clearStagedImage(context: Context) {
        stagedScreenshotBytes = null
        _stagedImageUri.value = null
        _stagedImageInfo.value = null
        ImageScaler.clearStagingDirectory(context)
    }

    /**
     * Formats the current chat and active task into an optimized prompt package
     * suitable for pasting directly into the Gemini Web App (gemini.google.com)
     * or sending to the Gemini Android app.
     */
    fun formatGeminiPromptPackage(
        chat: ChatSession,
        messages: List<ChatMessage>,
        activeTaskPrompt: String? = null
    ): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val dateStr = dateFormat.format(Date())

        val builder = StringBuilder()
        builder.append("=== [PROJECT COUPLER: ${chat.title.uppercase()}] ===\n")
        builder.append("Coupler Source: Gemini Wake Android Bridge\n")
        builder.append("Project Timestamp: $dateStr\n")
        builder.append("Associated Wake Word: \"${chat.wakeWord}\"\n")
        builder.append("------------------------------------------\n")
        builder.append("CONTEXT & PREVIOUS CONVERSATION HISTORY:\n\n")

        // Include recent turns (up to 12 turns for optimal token balance in prompt)
        val recentTurns = if (messages.size > 12) messages.takeLast(12) else messages
        for (msg in recentTurns) {
            val role = if (msg.sender == "user") "USER" else "GEMINI"
            builder.append("[$role]: ${msg.content.trim()}\n\n")
        }

        builder.append("------------------------------------------\n")
        builder.append("CURRENT TASK / CONTINUATION REQUEST:\n")
        if (!activeTaskPrompt.isNullOrBlank()) {
            builder.append(activeTaskPrompt.trim())
        } else {
            builder.append("Please analyze the project context above and provide the next recommendations or continue the current task.")
        }
        builder.append("\n\n(Tip: When completed, you can share or copy this response back to the Gemini Wake app via the Piggyback Load coupler.)\n")

        return builder.toString()
    }

    /**
     * Serializes a chat session and its full message history into a standardized
     * Coupler JSON snapshot.
     */
    fun createCouplerJsonSnapshot(
        chat: ChatSession,
        messages: List<ChatMessage>
    ): String {
        val root = JSONObject()
        root.put("couplerVersion", COUPLER_VERSION)
        root.put("timestamp", System.currentTimeMillis())
        root.put("chatId", chat.id)
        root.put("title", chat.title)
        root.put("wakeWord", chat.wakeWord)
        root.put("systemInstruction", chat.systemInstruction)
        root.put("themeColorHex", chat.themeColorHex)
        root.put("isPersistent", chat.isPersistent)
        root.put("totalTrackedSeconds", chat.totalTrackedSeconds)

        val messagesArray = JSONArray()
        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("id", msg.id)
            msgObj.put("sender", msg.sender)
            msgObj.put("content", msg.content)
            msgObj.put("timestamp", msg.timestamp)
            msgObj.put("triggeredByWakeWord", msg.triggeredByWakeWord)
            messagesArray.put(msgObj)
        }
        root.put("messages", messagesArray)

        return root.toString(2)
    }

    /**
     * Launches an Android Intent to hand off project context directly to the Gemini Android app
     * (or system chooser if Gemini is not installed directly), attaching staged screenshot if provided.
     */
    fun launchGeminiAppHandoff(
        context: Context,
        chat: ChatSession,
        messages: List<ChatMessage>,
        activeTaskPrompt: String? = null,
        imageUri: Uri? = null
    ): Boolean {
        val payload = formatGeminiPromptPackage(chat, messages, activeTaskPrompt)

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            if (imageUri != null) {
                type = "image/*"
                putExtra(Intent.EXTRA_STREAM, imageUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
            putExtra(Intent.EXTRA_TEXT, payload)
            putExtra(Intent.EXTRA_SUBJECT, "Project Handoff: ${chat.title}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Also copy prompt to clipboard so user can immediately paste if app doesn't ingest EXTRA_TEXT with image
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Gemini Coupler Prompt", payload)
            clipboard.setPrimaryClip(clip)
        } catch (_: Exception) {}

        // Check if official Gemini package is installed
        val packageManager = context.packageManager
        val isGeminiInstalled = try {
            packageManager.getPackageInfo(GEMINI_APP_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }

        return try {
            if (isGeminiInstalled) {
                sendIntent.setPackage(GEMINI_APP_PACKAGE)
                context.startActivity(sendIntent)
                Toast.makeText(context, "Handoff sent to Gemini App!", Toast.LENGTH_SHORT).show()
            } else {
                val chooser = Intent.createChooser(sendIntent, "Handoff to Gemini / External AI").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }
            true
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open external app: ${e.message}", Toast.LENGTH_LONG).show()
            false
        }
    }

    /**
     * Copies the formatted prompt package to system clipboard for easy 1-click
     * pasting into gemini.google.com.
     */
    fun copyPromptPackageToClipboard(
        context: Context,
        chat: ChatSession,
        messages: List<ChatMessage>,
        activeTaskPrompt: String? = null
    ) {
        val payload = formatGeminiPromptPackage(chat, messages, activeTaskPrompt)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Gemini Coupler Prompt", payload)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Gemini Web prompt package copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    /**
     * Copies the JSON snapshot to system clipboard.
     */
    fun copyJsonSnapshotToClipboard(
        context: Context,
        chat: ChatSession,
        messages: List<ChatMessage>
    ) {
        val json = createCouplerJsonSnapshot(chat, messages)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Gemini Coupler JSON", json)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, "Coupler JSON snapshot copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    /**
     * Parses incoming piggyback text. Can handle:
     * 1. A Coupler JSON snapshot
     * 2. Gemini web share URL (https://gemini.google.com/share/...)
     * 3. Markdown / raw conversation transcript copied from Gemini web or app
     */
    suspend fun parsePiggybackPayload(rawInput: String): ParsedGeminiChat? {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return null

        // Try Coupler JSON format first
        if (trimmed.startsWith("{") && trimmed.contains("\"couplerVersion\"")) {
            try {
                val root = JSONObject(trimmed)
                val title = root.optString("title", "Coupled Project Chat")
                val wakeWord = root.optString("wakeWord", "Coupled Project")
                val systemInstruction = root.optString("systemInstruction", "You are Gemini, continuing this coupled project session.")
                val messagesArray = root.optJSONArray("messages") ?: JSONArray()

                val messages = mutableListOf<ChatMessage>()
                for (i in 0 until messagesArray.length()) {
                    val obj = messagesArray.getJSONObject(i)
                    messages.add(
                        ChatMessage(
                            chatId = 0,
                            sender = obj.optString("sender", "model"),
                            content = obj.optString("content", ""),
                            timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                            triggeredByWakeWord = obj.optBoolean("triggeredByWakeWord", false)
                        )
                    )
                }

                if (messages.isNotEmpty()) {
                    return ParsedGeminiChat(
                        title = title,
                        suggestedWakeWord = wakeWord,
                        systemInstruction = systemInstruction,
                        messages = messages
                    )
                }
            } catch (_: Exception) {
                // Fall back to standard parser
            }
        }

        // Delegate to GeminiChatParser for share URLs, standard JSON, and text transcripts
        val parser = GeminiChatParser()
        return parser.parseGeminiInput(trimmed)
    }

    /**
     * Executes a complete force reset & clear on the ChromeBridgeSocketManager,
     * flushing client streams, packet ring buffers, and local cache directories,
     * then re-binding to a fresh random ephemeral port and passively staging the bridge payload.
     */
    fun resetAndReconnectChrome(context: Context, chat: ChatSession? = null, preserveCache: Boolean = false): Int {
        val newPort = ChromeBridgeSocketManager.forceResetAndClear(context, preserveCache)
        ChromeBridgeSocketManager.launchChromeBridge(context, chat)
        val msg = if (preserveCache) {
            "Chrome Bridge reset (Cache Preserved)! Staged on port $newPort"
        } else {
            "Chrome Bridge reset & cache wiped! Staged on port $newPort"
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        return newPort
    }

    /**
     * Returns the currently active ephemeral port from ChromeBridgeSocketManager.
     */
    fun getActiveBridgePort(): Int {
        return ChromeBridgeSocketManager.bridgeState.value.boundPort
    }

    /**
     * Returns the current cryptographic session nonce.
     */
    fun getActiveBridgeNonce(): String {
        return ChromeBridgeSocketManager.bridgeState.value.sessionNonce
    }

    /**
     * Returns the full loopback URL for the active Chrome Bridge session.
     */
    fun getBridgeUrl(): String {
        return ChromeBridgeSocketManager.getBridgeUrl()
    }

    /**
     * Builds generationConfig specifying AUDIO response modality and Orion speech_config.
     */
    fun createOrionVoiceGenerationConfig(): JSONObject {
        val speechConfig = JSONObject().apply {
            put("voice_config", JSONObject().apply {
                put("prebuilt_voice_config", JSONObject().apply {
                    put("voice_name", "Orion")
                })
            })
        }
        return JSONObject().apply {
            put("response_modalities", JSONArray(listOf("AUDIO", "TEXT")))
            put("speech_config", speechConfig)
        }
    }

    /**
     * Formats speech_config JSON for prebuilt_voice_config with Orion.
     */
    fun getOrionSpeechConfigJson(): String {
        return createOrionVoiceGenerationConfig().toString(2)
    }
}

