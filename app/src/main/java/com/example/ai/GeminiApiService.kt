package com.example.ai

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.data.model.ChatMessage
import com.example.voice.WakeWordManager
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.InlineDataPart
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

private const val TAG = "GeminiApiService"

data class GeminiStreamChunk(
    val text: String? = null,
    val pcmAudioBytes: ByteArray? = null,
    val isAudio: Boolean = false,
    val presentationTimestampMs: Long = WakeWordManager.resolvePresentationTimestamp(System.currentTimeMillis())
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as GeminiStreamChunk
        if (text != other.text) return false
        if (pcmAudioBytes != null) {
            if (other.pcmAudioBytes == null) return false
            if (!pcmAudioBytes.contentEquals(other.pcmAudioBytes)) return false
        } else if (other.pcmAudioBytes != null) return false
        if (isAudio != other.isAudio) return false
        return presentationTimestampMs == other.presentationTimestampMs
    }

    override fun hashCode(): Int {
        var result = text?.hashCode() ?: 0
        result = 31 * result + (pcmAudioBytes?.contentHashCode() ?: 0)
        result = 31 * result + isAudio.hashCode()
        result = 31 * result + presentationTimestampMs.hashCode()
        return result
    }
}

/**
 * GeminiApiService handles communication with Gemini models supporting audio modalities,
 * Orion voice speech_config, and automatic debug bypass of Firebase App Check token enforcement
 * via direct Google Generative Language REST/SSE transport when running on physical debug devices.
 */
class GeminiApiService {

    companion object {
        const val ORION_VOICE_NAME = "Fenrir"
        const val AUDIO_MODALITY = "AUDIO"
        const val TEXT_MODALITY = "TEXT"
        private const val DIRECT_REST_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val DIRECT_REST_DEFAULT_MODEL = "gemini-2.5-flash"

        /**
         * Generates the standard generationConfig for Gemini Neural Voice (Orion).
         */
        fun buildOrionVoiceGenerationConfig(includeText: Boolean = true): JSONObject {
            val modalities = JSONArray().apply {
                put(AUDIO_MODALITY)
                if (includeText) {
                    put(TEXT_MODALITY)
                }
            }

            val speechConfig = JSONObject().apply {
                put("voice_config", JSONObject().apply {
                    put("prebuilt_voice_config", JSONObject().apply {
                        put("voice_name", ORION_VOICE_NAME)
                    })
                })
            }

            return JSONObject().apply {
                put("response_modalities", modalities)
                put("speech_config", speechConfig)
            }
        }

        /**
         * Sanitizes outgoing user prompt payload to remove unspaced duplicate concatenation artifacts.
         */
        fun sanitizePayload(text: String): String {
            val trimmed = text.trim()
            val half = trimmed.length / 2
            return if (trimmed.length >= 8 && trimmed.length % 2 == 0 && trimmed.take(half) == trimmed.substring(half)) {
                Log.w("PayloadSanitizer", "De-duplicated unspaced payload: $trimmed")
                trimmed.take(half)
            } else {
                trimmed
            }
        }

        /**
         * Checks whether a Throwable indicates a Firebase App Check rejection (e.g.,
         * `"Firebase App Check token is invalid."`) or authorization failure that should bypass
         * App Check enforcement in debug builds.
         */
        fun isAppCheckError(error: Throwable?): Boolean {
            if (error == null) return false
            val combined = buildString {
                append(error.message.orEmpty())
                append(" ")
                append(error.cause?.message.orEmpty())
            }.lowercase()
            return combined.contains("app check") ||
                combined.contains("appcheck") ||
                combined.contains("token is invalid") ||
                combined.contains("unauthenticated") ||
                combined.contains("permission_denied") ||
                combined.contains("403")
        }

        /**
         * Resolves the active Gemini API key for direct REST calls (bypassing Firebase App Check)
         * from:
         * 1. [ApiKeyManager] active key (Primary / Backup configured in Settings)
         * 2. `BuildConfig.GEMINI_API_KEY` (if present in `.env`)
         * 3. `google_api_key` string resource generated from `google-services.json`
         */
        fun resolveDirectApiKey(context: Context?): String {
            val fromManager = context?.let { ApiKeyManager.getInstance(it).getActiveApiKey().trim() }.orEmpty()
            if (fromManager.isNotBlank()) return fromManager

            val fromBuildConfig = runCatching {
                val field = BuildConfig::class.java.getDeclaredField("GEMINI_API_KEY")
                (field.get(null) as? String)?.trim().orEmpty()
            }.getOrDefault("")
            if (fromBuildConfig.isNotBlank() && !fromBuildConfig.equals("null", ignoreCase = true)) {
                return fromBuildConfig
            }

            if (context != null) {
                val resId = context.resources.getIdentifier("google_api_key", "string", context.packageName)
                if (resId != 0) {
                    val googleKey = runCatching { context.getString(resId).trim() }.getOrDefault("")
                    if (googleKey.isNotBlank()) return googleKey
                }
            }
            return ""
        }
    }

    private data class ModelCacheKey(
        val modelName: String,
        val systemInstruction: String,
        val includeSearchTool: Boolean
    )

    private val firebaseAi by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Firebase.ai
    }

    private val okHttpClient: OkHttpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Once a physical debug device encounters `"Firebase App Check token is invalid"`, remember it
     * for the session so subsequent turns go straight to the direct App-Check-free transport
     * without waiting for a failed roundtrip.
     */
    @Volatile
    private var bypassFirebaseAppCheckForSession: Boolean = false

    private val modelCache = java.util.concurrent.ConcurrentHashMap<ModelCacheKey, com.google.firebase.ai.GenerativeModel>()

    private fun getOrCreateGenerativeModel(
        modelName: String,
        systemInstruction: String = "",
        includeSearchTool: Boolean = true
    ): com.google.firebase.ai.GenerativeModel {
        val key = ModelCacheKey(
            modelName = modelName,
            systemInstruction = systemInstruction,
            includeSearchTool = includeSearchTool
        )
        return modelCache.getOrPut(key) {
            if (modelCache.size >= 8) {
                modelCache.clear()
            }
            if (systemInstruction.isNotBlank()) {
                firebaseAi.generativeModel(
                    modelName = modelName,
                    tools = if (includeSearchTool) listOf(Tool.googleSearch()) else emptyList(),
                    systemInstruction = content { text(systemInstruction) }
                )
            } else {
                firebaseAi.generativeModel(
                    modelName = modelName,
                    tools = if (includeSearchTool) listOf(Tool.googleSearch()) else emptyList()
                )
            }
        }
    }

    /**
     * Builds the JSON request body for the direct Google Generative Language REST API
     * (completely independent of Firebase App Check).
     */
    internal fun buildDirectRestPayload(
        systemInstruction: String,
        history: List<ChatMessage>,
        userPrompt: String,
        userAudioBytes: ByteArray?,
        attachmentBytes: ByteArray?,
        attachmentMimeType: String?
    ): JSONObject {
        val root = JSONObject()
        if (systemInstruction.isNotBlank()) {
            root.put(
                "system_instruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", systemInstruction))
                )
            )
        }

        val contentsArray = JSONArray()
        val chronologicalHistory = history
            .filter { !it.isDeleted && it.content.isNotBlank() && !it.content.startsWith("⚠️ Error:") }
            .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })

        for (msg in chronologicalHistory) {
            val role = if (msg.sender == "user") "user" else "model"
            val parts = JSONArray().put(JSONObject().put("text", msg.content))
            contentsArray.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", parts)
            )
        }

        val userParts = JSONArray()
        if (attachmentBytes != null && attachmentBytes.isNotEmpty() && !attachmentMimeType.isNullOrBlank()) {
            userParts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", attachmentMimeType)
                        .put("data", Base64.encodeToString(attachmentBytes, Base64.NO_WRAP))
                )
            )
        }
        if (userAudioBytes != null && WakeWordManager.shouldTransmitAudioPacket(userAudioBytes.size, userAudioBytes)) {
            val wavBytes = WakeWordManager.buildWavFromPcmBytes(userAudioBytes, WakeWordManager.SAMPLE_RATE_HZ)
            userParts.put(
                JSONObject().put(
                    "inline_data",
                    JSONObject()
                        .put("mime_type", "audio/wav")
                        .put("data", Base64.encodeToString(wavBytes, Base64.NO_WRAP))
                )
            )
        }
        val effectivePrompt = userPrompt.ifBlank { "Please respond to this input." }
        userParts.put(JSONObject().put("text", effectivePrompt))

        contentsArray.put(
            JSONObject()
                .put("role", "user")
                .put("parts", userParts)
        )
        root.put("contents", contentsArray)
        return root
    }

    /**
     * Executes a Gemini request directly via OkHttp against the Google Generative Language REST/SSE
     * endpoint, bypassing Firebase App Check token enforcement completely in debug environments.
     */
    private fun executeDirectRestBypass(
        apiKey: String,
        systemInstruction: String,
        history: List<ChatMessage>,
        userPrompt: String,
        userAudioBytes: ByteArray?,
        attachmentBytes: ByteArray?,
        attachmentMimeType: String?,
        onChunk: (GeminiStreamChunk) -> Unit
    ): Boolean {
        if (apiKey.isBlank()) return false
        val payloadJson = buildDirectRestPayload(
            systemInstruction = systemInstruction,
            history = history,
            userPrompt = userPrompt,
            userAudioBytes = userAudioBytes,
            attachmentBytes = attachmentBytes,
            attachmentMimeType = attachmentMimeType
        ).toString()

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val sseUrl = "$DIRECT_REST_BASE_URL/$DIRECT_REST_DEFAULT_MODEL:streamGenerateContent?alt=sse&key=$apiKey"
        val request = Request.Builder()
            .url(sseUrl)
            .post(payloadJson.toRequestBody(mediaType))
            .header("Accept", "text/event-stream")
            .build()

        var emittedAnyText = false
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errBody = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                throw IllegalStateException(
                    "Gemini REST API HTTP ${response.code}: ${errBody.take(240).ifBlank { response.message }}"
                )
            }
            val bodyStream = response.body?.byteStream() ?: return false
            BufferedReader(InputStreamReader(bodyStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val trimmed = line?.trim().orEmpty()
                    if (trimmed.startsWith("data:")) {
                        val jsonStr = trimmed.removePrefix("data:").trim()
                        if (jsonStr.isNotEmpty() && jsonStr != "[DONE]") {
                            val parsedText = extractTextFromCandidateJson(jsonStr)
                            if (parsedText.isNotEmpty()) {
                                emittedAnyText = true
                                onChunk(
                                    GeminiStreamChunk(
                                        text = parsedText,
                                        isAudio = false,
                                        presentationTimestampMs = WakeWordManager.resolvePresentationTimestamp(
                                            System.currentTimeMillis()
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
        return emittedAnyText
    }

    private fun extractTextFromCandidateJson(jsonStr: String): String {
        return try {
            val obj = JSONObject(jsonStr)
            val candidates = obj.optJSONArray("candidates") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until candidates.length()) {
                val candidate = candidates.optJSONObject(i) ?: continue
                val contentObj = candidate.optJSONObject("content") ?: continue
                val parts = contentObj.optJSONArray("parts") ?: continue
                for (j in 0 until parts.length()) {
                    val part = parts.optJSONObject(j) ?: continue
                    val text = part.optString("text", "")
                    if (text.isNotEmpty()) {
                        sb.append(text)
                    }
                }
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Streams responses requesting Orion neural voice and text chunks.
     * Automatically bypasses Firebase App Check token errors in debug environments via direct REST
     * transport and supports HTTP 429 / RESOURCE_EXHAUSTED dual-key failover.
     */
    fun streamChatWithOrionVoice(
        context: Context? = null,
        systemInstruction: String,
        history: List<ChatMessage>,
        userPrompt: String,
        userAudioBytes: ByteArray? = null,
        attachmentBytes: ByteArray? = null,
        attachmentMimeType: String? = null,
        requestAudio: Boolean = true
    ): Flow<GeminiStreamChunk> = flow {
        val apiKeyManager = context?.let { ApiKeyManager.getInstance(it) }
        val sanitizedUserPrompt = sanitizePayload(userPrompt)
        var retriedAfterFailover = false

        while (true) {
            var voiceStreamSuccess = false
            var caughtError: Exception? = null

            val hasAudio = userAudioBytes != null &&
                WakeWordManager.shouldTransmitAudioPacket(userAudioBytes.size, userAudioBytes)
            val hasAttachment = attachmentBytes != null && attachmentBytes.isNotEmpty() && !attachmentMimeType.isNullOrBlank()

            // If user explicitly configured a Gemini API key in Settings or if a previous call in this
            // debug session hit "Firebase App Check token is invalid", bypass Firebase App Check directly.
            val directApiKey = resolveDirectApiKey(context)
            val shouldPreferDirectBypass = BuildConfig.DEBUG &&
                directApiKey.isNotBlank() &&
                (bypassFirebaseAppCheckForSession || apiKeyManager?.getActiveApiKey()?.isNotBlank() == true)

            if (shouldPreferDirectBypass) {
                try {
                    val pendingChunks = mutableListOf<GeminiStreamChunk>()
                    val directSuccess = executeDirectRestBypass(
                        apiKey = directApiKey,
                        systemInstruction = systemInstruction,
                        history = history,
                        userPrompt = sanitizedUserPrompt,
                        userAudioBytes = if (hasAudio) userAudioBytes else null,
                        attachmentBytes = if (hasAttachment) attachmentBytes else null,
                        attachmentMimeType = attachmentMimeType,
                        onChunk = { pendingChunks.add(it) }
                    )
                    if (directSuccess && pendingChunks.isNotEmpty()) {
                        for (c in pendingChunks) {
                            emit(c)
                        }
                        break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Direct REST preferred bypass attempt failed, trying Firebase.ai: ${e.message}")
                    caughtError = e
                }
            }

            val userContent = content("user") {
                if (hasAttachment) {
                    inlineData(attachmentBytes!!, attachmentMimeType!!)
                }
                if (hasAudio) {
                    inlineData(userAudioBytes!!, "audio/pcm;rate=16000")
                }
                if (sanitizedUserPrompt.isNotEmpty()) {
                    text(sanitizedUserPrompt)
                }
            }

            if (requestAudio && !bypassFirebaseAppCheckForSession) {
                try {
                    val orionInstruction = "$systemInstruction (Voice model: Orion neural speech enabled)"
                    val model = getOrCreateGenerativeModel(
                        modelName = "gemini-2.5-flash",
                        systemInstruction = orionInstruction,
                        includeSearchTool = true
                    )

                    val chronologicalHistory = history
                        .filter { !it.isDeleted && it.content.isNotBlank() && !it.content.startsWith("⚠️ Error:") }
                        .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
                    val historyContent = chronologicalHistory.map { msg ->
                        val role = if (msg.sender == "user") "user" else "model"
                        content(role) { text(msg.content) }
                    }

                    val chatSession = model.startChat(history = historyContent)

                    withTimeout(60_000L) {
                        val stream = if (hasAudio || hasAttachment) {
                            chatSession.sendMessageStream(userContent)
                        } else {
                            chatSession.sendMessageStream(sanitizedUserPrompt)
                        }
                        stream.collect { chunk ->
                            val chunkTimestamp = WakeWordManager.resolvePresentationTimestamp(System.currentTimeMillis())
                            chunk.candidates.forEach { candidate ->
                                candidate.content.parts.forEach { part ->
                                    if (part is InlineDataPart &&
                                        part.mimeType.lowercase().startsWith("audio") &&
                                        WakeWordManager.shouldTransmitAudioPacket(part.inlineData.size, part.inlineData)
                                    ) {
                                        voiceStreamSuccess = true
                                        emit(
                                            GeminiStreamChunk(
                                                pcmAudioBytes = part.inlineData,
                                                isAudio = true,
                                                presentationTimestampMs = chunkTimestamp
                                            )
                                        )
                                    }
                                }
                            }
                            val text = chunk.text.orEmpty()
                            if (text.isNotEmpty()) {
                                voiceStreamSuccess = true
                                emit(
                                    GeminiStreamChunk(
                                        text = text,
                                        isAudio = false,
                                        presentationTimestampMs = chunkTimestamp
                                    )
                                )
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    caughtError = e
                    if (isAppCheckError(e)) {
                        bypassFirebaseAppCheckForSession = true
                    }
                    Log.w(TAG, "Audio stream attempt failed: ${e.message}")
                }
            }

            // If audio wasn't requested or threw an exception, try Firebase.ai text stream (unless App Check already failed)
            if (!voiceStreamSuccess && !bypassFirebaseAppCheckForSession) {
                try {
                    val fallbackModel = getOrCreateGenerativeModel(
                        modelName = "gemini-2.5-flash",
                        systemInstruction = systemInstruction,
                        includeSearchTool = true
                    )
                    val chronologicalHistory = history
                        .filter { !it.isDeleted && it.content.isNotBlank() && !it.content.startsWith("⚠️ Error:") }
                        .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })
                    val historyContent = chronologicalHistory.map { msg ->
                        val role = if (msg.sender == "user") "user" else "model"
                        content(role) { text(msg.content) }
                    }
                    val chat = fallbackModel.startChat(history = historyContent)
                    withTimeout(60_000L) {
                        val stream = if (hasAudio || hasAttachment) {
                            chat.sendMessageStream(userContent)
                        } else {
                            chat.sendMessageStream(sanitizedUserPrompt)
                        }
                        stream.collect { chunk ->
                            val text = chunk.text.orEmpty()
                            if (text.isNotEmpty()) {
                                voiceStreamSuccess = true
                                emit(GeminiStreamChunk(text = text, isAudio = false))
                            }
                        }
                    }
                    caughtError = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    caughtError = e
                    if (isAppCheckError(e)) {
                        bypassFirebaseAppCheckForSession = true
                    }
                }
            }

            // If Firebase.ai failed (e.g. "Firebase App Check token is invalid"), bypass App Check via direct REST API
            if (!voiceStreamSuccess && directApiKey.isNotBlank()) {
                try {
                    Log.i(TAG, "Bypassing Firebase App Check via direct Gemini REST endpoint...")
                    val pendingChunks = mutableListOf<GeminiStreamChunk>()
                    val directSuccess = executeDirectRestBypass(
                        apiKey = directApiKey,
                        systemInstruction = systemInstruction,
                        history = history,
                        userPrompt = sanitizedUserPrompt,
                        userAudioBytes = if (hasAudio) userAudioBytes else null,
                        attachmentBytes = if (hasAttachment) attachmentBytes else null,
                        attachmentMimeType = attachmentMimeType,
                        onChunk = { pendingChunks.add(it) }
                    )
                    if (directSuccess && pendingChunks.isNotEmpty()) {
                        for (c in pendingChunks) {
                            emit(c)
                        }
                        caughtError = null
                        voiceStreamSuccess = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (directEx: Exception) {
                    Log.w(TAG, "Direct Gemini REST fallback failed: ${directEx.message}")
                    if (caughtError == null) {
                        caughtError = directEx
                    }
                }
            }

            if (caughtError != null && !voiceStreamSuccess) {
                if (!retriedAfterFailover && apiKeyManager != null && apiKeyManager.isQuotaOr429Error(caughtError)) {
                    val failoverActivated = apiKeyManager.handleQuotaExhaustionAndFailover()
                    if (failoverActivated) {
                        retriedAfterFailover = true
                        modelCache.clear()
                        Log.i(TAG, "HTTP 429 / RESOURCE_EXHAUSTED encountered. Retrying execution with Backup Key...")
                        continue
                    }
                }
                Log.e(TAG, "Streaming failed: ${caughtError.message}", caughtError)
                throw caughtError
            }

            break
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Transcribes audio bytes using Gemini model, falling back to direct REST (bypassing App Check) if needed.
     */
    suspend fun transcribeAudio(
        audioBytes: ByteArray,
        mimeType: String = "audio/wav",
        context: Context? = null
    ): String = withContext(Dispatchers.IO) {
        if (!bypassFirebaseAppCheckForSession) {
            try {
                val model = getOrCreateGenerativeModel(
                    modelName = "gemini-2.5-flash",
                    systemInstruction = "",
                    includeSearchTool = false
                )
                val response = withTimeout(30_000L) {
                    model.generateContent(
                        content {
                            inlineData(audioBytes, mimeType)
                            text("Transcribe this audio accurately.")
                        }
                    )
                }
                val text = response.text.orEmpty().trim()
                if (text.isNotEmpty()) return@withContext text
            } catch (e: Exception) {
                if (isAppCheckError(e)) {
                    bypassFirebaseAppCheckForSession = true
                }
                Log.w(TAG, "Firebase.ai transcription failed, attempting direct REST bypass: ${e.message}")
            }
        }

        val directApiKey = resolveDirectApiKey(context)
        if (directApiKey.isNotBlank()) {
            try {
                val sb = StringBuilder()
                executeDirectRestBypass(
                    apiKey = directApiKey,
                    systemInstruction = "",
                    history = emptyList(),
                    userPrompt = "Transcribe this audio accurately. Return only the spoken words.",
                    userAudioBytes = if (mimeType.contains("pcm")) audioBytes else null,
                    attachmentBytes = if (!mimeType.contains("pcm")) audioBytes else null,
                    attachmentMimeType = if (!mimeType.contains("pcm")) mimeType else null,
                    onChunk = { chunk ->
                        if (!chunk.text.isNullOrEmpty()) {
                            sb.append(chunk.text)
                        }
                    }
                )
                return@withContext sb.toString().trim()
            } catch (e: Exception) {
                Log.e(TAG, "Direct REST transcription failed: ${e.message}")
            }
        }
        ""
    }
}
