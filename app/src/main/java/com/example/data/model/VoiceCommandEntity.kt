package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.util.VoiceCommandHistoryEntry
import com.example.util.VoiceCommandType
import java.util.UUID

/**
 * Room entity persisting recognized voice commands and transcriptions for the Command History screen.
 * Queried with `ORDER BY timestamp DESC LIMIT 100` to prevent unbounded memory growth.
 */
@Entity(
    tableName = "voice_command_history",
    indices = [
        Index(value = ["timestamp"])
    ]
)
data class VoiceCommandEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val commandType: String = VoiceCommandType.WAKE_WORD.name,
    val triggerPhrase: String = "",
    val recognizedTranscript: String = "",
    val chatTitle: String? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Returns true when [recognizedTranscript] holds actual transcribed speech rather than a blank
     * string or a legacy audio placeholder.
     */
    val hasValidTranscript: Boolean
        get() = recognizedTranscript.isNotBlank() &&
            !ChatMessage.isLegacyAudioPlaceholder(recognizedTranscript)

    fun toHistoryEntry(): VoiceCommandHistoryEntry {
        val safeType = try {
            VoiceCommandType.valueOf(commandType.trim().uppercase())
        } catch (_: Throwable) {
            VoiceCommandType.WAKE_WORD
        }
        val safeTrigger = triggerPhrase.trim().ifBlank { safeType.displayLabel }
        val cleanTranscript = recognizedTranscript.trim()
            .takeUnless { ChatMessage.isLegacyAudioPlaceholder(it) }
            .orEmpty()
            .ifBlank { safeTrigger }
        val safeTimestamp = if (timestamp > 0L) timestamp else System.currentTimeMillis()
        return VoiceCommandHistoryEntry(
            id = id.ifBlank { UUID.randomUUID().toString() },
            commandType = safeType,
            triggerPhrase = safeTrigger,
            recognizedTranscript = cleanTranscript,
            chatTitle = chatTitle?.trim()?.takeIf { it.isNotBlank() },
            timestamp = safeTimestamp
        )
    }

    companion object {
        fun fromHistoryEntry(entry: VoiceCommandHistoryEntry): VoiceCommandEntity {
            val safeId = entry.id.trim().ifBlank { UUID.randomUUID().toString() }
            val safeTrigger = entry.triggerPhrase.trim().ifBlank { entry.commandType.displayLabel }
            val cleanTranscript = entry.recognizedTranscript.trim()
                .takeUnless { ChatMessage.isLegacyAudioPlaceholder(it) }
                .orEmpty()
                .ifBlank { safeTrigger }
            val safeTimestamp = if (entry.timestamp > 0L) entry.timestamp else System.currentTimeMillis()
            return VoiceCommandEntity(
                id = safeId,
                commandType = entry.commandType.name,
                triggerPhrase = safeTrigger,
                recognizedTranscript = cleanTranscript,
                chatTitle = entry.chatTitle?.trim()?.takeIf { it.isNotBlank() },
                timestamp = safeTimestamp
            )
        }
    }
}
