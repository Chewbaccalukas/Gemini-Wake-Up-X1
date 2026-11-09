package com.example.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

typealias Message = ChatMessage

/**
 * Room `@Entity` representing a chat message turn (`Message` / `ChatMessage`)
 * in the hybrid offline-first Room + Cloud Firestore synchronization architecture.
 *
 * Stores the user's actual spoken/transcribed or typed words in [content].
 */
@Entity(
    tableName = "chat_messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSession::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["chatId"]),
        Index(value = ["chatId", "timestamp", "sender"], unique = true),
        Index(value = ["remoteId"]),
        Index(value = ["syncStatus"]),
        Index(value = ["userId"]),
        Index(value = ["isDeleted"])
    ]
)
data class ChatMessage(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "chatId")
    val chatId: Long,

    @ColumnInfo(name = "sender")
    val sender: String, // "user" or "gemini"

    @ColumnInfo(name = "content")
    val content: String = "",

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "triggeredByWakeWord")
    val triggeredByWakeWord: Boolean = false,

    @ColumnInfo(name = "attachmentUri")
    val attachmentUri: String? = null,

    @ColumnInfo(name = "attachmentName")
    val attachmentName: String? = null,

    @ColumnInfo(name = "attachmentMimeType")
    val attachmentMimeType: String? = null,

    @ColumnInfo(name = "attachmentSizeBytes")
    val attachmentSizeBytes: Long? = null,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long = timestamp,

    @ColumnInfo(name = "remoteId")
    val remoteId: String? = null, // Cloud Firestore document ID (e.g. "msg_{id}")

    @ColumnInfo(name = "userId")
    val userId: String? = null, // Authenticated Firebase user UID owning this record

    @ColumnInfo(name = "syncStatus")
    val syncStatus: String = SyncStatus.PENDING, // "PENDING", "SYNCED", "DIRTY", or "FAILED"

    @ColumnInfo(name = "lastSyncedAt")
    val lastSyncedAt: Long = 0L, // Epoch ms when last synchronized with Cloud Firestore

    @ColumnInfo(name = "isDeleted", defaultValue = "0")
    val isDeleted: Boolean = false // Soft-delete tombstone flag for hybrid cloud sync
) {
    val resolvedRemoteId: String
        get() = remoteId?.takeIf { it.isNotBlank() } ?: "msg_$id"

    val isSynced: Boolean
        get() = syncStatus == SyncStatus.SYNCED && !isDeleted

    val needsSync: Boolean
        get() = syncStatus != SyncStatus.SYNCED || isDeleted

    val syncStateEnum: SyncState
        get() = SyncState.fromStatus(syncStatus)

    /**
     * Returns true when this message holds actual user/model transcribed or typed text
     * rather than a blank string or a legacy audio placeholder.
     */
    val hasTranscribedText: Boolean
        get() = sanitizedTextContent.isNotBlank()

    /**
     * Returns the trimmed message text (with any leading wake phrase stripped when [triggeredByWakeWord] is true)
     * if valid, or an empty string if [content] is blank or a legacy audio placeholder.
     */
    val sanitizedTextContent: String
        get() {
            if (content.isBlank() || isLegacyAudioPlaceholder(content)) return ""
            val raw = content.trim()
            val cleaned = if (triggeredByWakeWord && sender.equals("user", ignoreCase = true)) {
                com.example.voice.AudioCommandSanitizer.stripWakeWordTrigger(raw)
            } else {
                raw
            }
            return if (cleaned.isNotBlank() && !isLegacyAudioPlaceholder(cleaned)) cleaned else ""
        }

    /**
     * Returns true if the attachment is a legacy raw audio placeholder file/MIME type.
     */
    val isAudioPlaceholderAttachment: Boolean
        get() {
            val mime = attachmentMimeType?.lowercase().orEmpty()
            val name = attachmentName?.lowercase().orEmpty()
            val uri = attachmentUri?.lowercase().orEmpty()
            return mime.startsWith("audio/") ||
                name.endsWith(".pcm") ||
                name.endsWith(".wav") ||
                name.endsWith(".m4a") ||
                name.endsWith(".ogg") ||
                uri.endsWith(".pcm") ||
                uri.endsWith(".wav") ||
                uri.endsWith(".m4a")
        }

    /**
     * Returns true when a real non-audio attachment (such as an image or document) is attached.
     */
    val hasVisibleNonAudioAttachment: Boolean
        get() = (!attachmentName.isNullOrBlank() || !attachmentUri.isNullOrBlank()) &&
            !isAudioPlaceholderAttachment

    /**
     * Returns true when this message contains actual transcribed/typed text or a valid
     * non-audio attachment worth persisting in Room and rendering in the UI.
     */
    val hasPersistableContent: Boolean
        get() = hasTranscribedText || hasVisibleNonAudioAttachment

    /**
     * Returns a sanitized copy with legacy audio placeholder strings and audio-only attachment
     * metadata stripped so Room only persists clean text and non-audio attachments.
     */
    fun sanitizeForPersistence(): ChatMessage {
        val cleanContent = sanitizedTextContent
        return if (isAudioPlaceholderAttachment) {
            copy(
                content = cleanContent,
                attachmentUri = null,
                attachmentName = null,
                attachmentMimeType = null,
                attachmentSizeBytes = null
            )
        } else {
            copy(content = cleanContent)
        }
    }

    companion object {
        private val LEGACY_AUDIO_PLACEHOLDERS = setOf(
            "🎙️ [voice input audio turn]",
            "[voice input audio turn]",
            "[audio placeholder]",
            "[voice message]",
            "[audio turn]",
            "[audio]",
            "[voice turn]",
            "audio message",
            "voice message",
            "voice input audio turn"
        )

        fun isLegacyAudioPlaceholder(text: String?): Boolean {
            if (text.isNullOrBlank()) return true
            val normalized = text.trim().lowercase()
            if (normalized in LEGACY_AUDIO_PLACEHOLDERS) return true
            if (normalized.endsWith(".pcm") || normalized.endsWith(".wav") || normalized.endsWith(".m4a")) {
                if (!normalized.contains(" ")) return true
            }
            return false
        }
    }
}
