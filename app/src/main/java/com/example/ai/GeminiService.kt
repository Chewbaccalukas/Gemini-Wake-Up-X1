package com.example.ai

import com.example.data.model.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

open class GeminiService {

    private val apiService: GeminiApiService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GeminiApiService()
    }

    /**
     * Streams responses from Gemini with support for Orion neural speech, graceful text fallback, and 429 failover.
     */
    open fun streamChatResponse(
        context: android.content.Context? = null,
        systemInstruction: String,
        history: List<ChatMessage>,
        userPrompt: String,
        userAudioBytes: ByteArray? = null,
        attachmentBytes: ByteArray? = null,
        attachmentMimeType: String? = null,
        requestAudio: Boolean = false,
        onAudioChunk: ((ByteArray) -> Unit)? = null
    ): Flow<String> = flow {
        apiService.streamChatWithOrionVoice(
            context = context,
            systemInstruction = systemInstruction,
            history = history,
            userPrompt = userPrompt,
            userAudioBytes = userAudioBytes,
            attachmentBytes = attachmentBytes,
            attachmentMimeType = attachmentMimeType,
            requestAudio = requestAudio
        ).collect { chunk ->
            if (chunk.pcmAudioBytes != null) {
                onAudioChunk?.invoke(chunk.pcmAudioBytes)
            }
            if (!chunk.text.isNullOrEmpty()) {
                emit(chunk.text)
            }
        }
    }

    /**
     * Transcribes captured audio bytes using Gemini transcription model.
     */
    open suspend fun transcribeAudioSnippet(
        audioBytes: ByteArray,
        mimeType: String = "audio/pcm",
        context: android.content.Context? = null
    ): String {
        return apiService.transcribeAudio(audioBytes, mimeType, context)
    }
}
