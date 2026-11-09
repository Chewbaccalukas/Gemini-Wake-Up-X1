package com.example.data.firebase

import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.SyncStatus
import com.example.data.model.TriggerType
import com.example.data.model.WakeWord
import com.example.ui.theme.AppTheme
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

@IgnoreExtraProperties
data class FirestoreChatDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val localChatId: Long = 0L,
    val title: String = "Gemini Chat",
    val wakeWord: String = "Hey Gemini",
    val systemInstruction: String = "You are Gemini, a helpful, brilliant, and proactive AI companion.",
    val themeColorHex: String = "#38BDF8",
    val iconName: String = "Sparkles",
    @field:PropertyName("isDefaultWakeChat")
    @get:PropertyName("isDefaultWakeChat")
    @set:PropertyName("isDefaultWakeChat")
    var isDefaultWakeChat: Boolean = false,
    @field:PropertyName("isPersistent")
    @get:PropertyName("isPersistent")
    @set:PropertyName("isPersistent")
    var isPersistent: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toChatSession(): ChatSession {
        val parsedFromDocId = id.removePrefix("chat_").toLongOrNull()
        val resolvedId = when {
            localChatId > 0L -> localChatId
            parsedFromDocId != null && parsedFromDocId > 0L -> parsedFromDocId
            id.isNotBlank() -> (id.hashCode().toLong() and 0x7fffffffL).coerceAtLeast(1L)
            else -> 0L
        }
        val now = System.currentTimeMillis()
        val resolvedCreated = createdAt?.toDate()?.time ?: now
        val resolvedUpdated = updatedAt?.toDate()?.time ?: resolvedCreated
        val resolvedRemoteId = id.ifBlank { docIdForChat(resolvedId) }
        return ChatSession(
            id = resolvedId,
            title = title.ifBlank { "Gemini Chat" },
            wakeWord = wakeWord.ifBlank { "Hey Gemini" },
            isDefaultWakeChat = isDefaultWakeChat,
            isPersistent = isPersistent,
            systemInstruction = systemInstruction.ifBlank { "You are Gemini, a helpful, brilliant, and proactive AI companion." },
            iconName = iconName.ifBlank { "Sparkles" },
            themeColorHex = themeColorHex.ifBlank { "#38BDF8" },
            themeMode = AppTheme.SYSTEM,
            createdAt = resolvedCreated,
            lastUpdatedAt = resolvedUpdated,
            totalTrackedSeconds = 0L,
            remoteId = resolvedRemoteId,
            userId = userId.takeIf { it.isNotBlank() },
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = now
        )
    }

    companion object {
        fun docIdForChat(localChatId: Long): String = "chat_$localChatId"

        fun fromChatSession(userId: String, chat: ChatSession): FirestoreChatDoc {
            val docId = chat.remoteId?.takeIf { it.isNotBlank() } ?: docIdForChat(chat.id)
            return FirestoreChatDoc(
                id = docId,
                userId = userId,
                localChatId = chat.id.coerceAtLeast(0L),
                title = chat.title.take(199).ifBlank { "Gemini Chat" },
                wakeWord = chat.wakeWord.take(119).ifBlank { "Hey Gemini" },
                systemInstruction = chat.systemInstruction.take(3999).ifBlank { "You are Gemini, a helpful, brilliant, and proactive AI companion." },
                themeColorHex = chat.themeColorHex.take(15).ifBlank { "#38BDF8" },
                iconName = chat.iconName.take(63).ifBlank { "Sparkles" },
                isDefaultWakeChat = chat.isDefaultWakeChat,
                isPersistent = chat.isPersistent
            )
        }
    }
}

@IgnoreExtraProperties
data class FirestoreMessageDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val localChatId: Long = 0L,
    val localMessageId: Long = 0L,
    val sender: String = "gemini",
    val content: String = "",
    val triggeredByWakeWord: Boolean = false,
    val messageTimestamp: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toChatMessage(): ChatMessage {
        val resolvedId = if (localMessageId > 0L) localMessageId else (id.removePrefix("msg_").toLongOrNull() ?: 0L)
        val now = System.currentTimeMillis()
        val resolvedTs = when {
            messageTimestamp > 0L -> messageTimestamp
            createdAt != null -> createdAt.toDate().time
            else -> now
        }
        val resolvedUpdated = updatedAt?.toDate()?.time ?: resolvedTs
        val resolvedRemoteId = id.ifBlank { docIdForMessage(resolvedId) }
        return ChatMessage(
            id = resolvedId,
            chatId = localChatId.coerceAtLeast(1L),
            sender = if (sender == "user") "user" else "gemini",
            content = content,
            timestamp = resolvedTs,
            triggeredByWakeWord = triggeredByWakeWord,
            updatedAt = resolvedUpdated,
            remoteId = resolvedRemoteId,
            userId = userId.takeIf { it.isNotBlank() },
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = now
        )
    }

    companion object {
        fun docIdForMessage(localMessageId: Long): String = "msg_$localMessageId"

        fun fromChatMessage(userId: String, msg: ChatMessage): FirestoreMessageDoc {
            val docId = msg.remoteId?.takeIf { it.isNotBlank() } ?: docIdForMessage(msg.id)
            return FirestoreMessageDoc(
                id = docId,
                userId = userId,
                localChatId = msg.chatId.coerceAtLeast(0L),
                localMessageId = msg.id.coerceAtLeast(0L),
                sender = if (msg.sender == "user") "user" else "gemini",
                content = msg.content.take(19999).ifBlank { "(empty)" },
                triggeredByWakeWord = msg.triggeredByWakeWord,
                messageTimestamp = msg.timestamp.coerceAtLeast(0L)
            )
        }
    }
}

@IgnoreExtraProperties
data class FirestoreWakeWordDoc(
    @DocumentId val id: String = "",
    val userId: String = "",
    val localWakeWordId: Long = 0L,
    val phrase: String = "Hey Gemini",
    val triggerType: String = TriggerType.CHAT.name,
    val chatId: Long = 0L,
    val chatTitle: String = "",
    @field:PropertyName("isEnabled")
    @get:PropertyName("isEnabled")
    @set:PropertyName("isEnabled")
    var isEnabled: Boolean = true,
    val sensitivity: Double = 0.78,
    val metadata: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toWakeWord(): WakeWord {
        val resolvedId = if (localWakeWordId > 0L) localWakeWordId else (id.removePrefix("wake_").toLongOrNull() ?: 0L)
        val resolvedTrigger = if (triggerType == TriggerType.HANDS_FREE.name) {
            TriggerType.HANDS_FREE
        } else {
            TriggerType.CHAT
        }
        val resolvedRole = if (resolvedTrigger == TriggerType.HANDS_FREE) {
            metadata?.takeIf { it.isNotBlank() } ?: WakeWord.ROLE_DICTATE
        } else {
            WakeWord.ROLE_CHAT_WAKE
        }
        val now = System.currentTimeMillis()
        val resolvedCreated = createdAt?.toDate()?.time ?: now
        val resolvedUpdated = updatedAt?.toDate()?.time ?: resolvedCreated
        val resolvedRemoteId = id.ifBlank { docIdForWakeWord(resolvedId) }
        return WakeWord(
            id = resolvedId,
            phrase = phrase.ifBlank { "Hey Gemini" },
            triggerType = resolvedTrigger,
            metadata = metadata,
            commandRole = resolvedRole,
            chatId = chatId.takeIf { it > 0L },
            chatTitle = chatTitle,
            isEnabled = isEnabled,
            sensitivity = sensitivity.toFloat().coerceIn(0.50f, 0.98f),
            createdAt = resolvedCreated,
            updatedAt = resolvedUpdated,
            remoteId = resolvedRemoteId,
            userId = userId.takeIf { it.isNotBlank() },
            syncStatus = SyncStatus.SYNCED,
            lastSyncedAt = now
        )
    }

    companion object {
        fun docIdForWakeWord(localWakeWordId: Long): String = "wake_$localWakeWordId"

        fun fromWakeWord(userId: String, wakeWord: WakeWord): FirestoreWakeWordDoc {
            val docId = wakeWord.remoteId?.takeIf { it.isNotBlank() } ?: docIdForWakeWord(wakeWord.id)
            return FirestoreWakeWordDoc(
                id = docId,
                userId = userId,
                localWakeWordId = wakeWord.id.coerceAtLeast(0L),
                phrase = wakeWord.phrase.take(119).ifBlank { "Hey Gemini" },
                triggerType = wakeWord.triggerType.name,
                chatId = (wakeWord.chatId ?: 0L).coerceAtLeast(0L),
                chatTitle = wakeWord.chatTitle.take(199),
                isEnabled = wakeWord.isEnabled,
                sensitivity = wakeWord.sensitivity.toDouble().coerceIn(0.50, 0.98),
                metadata = (wakeWord.metadata ?: wakeWord.commandRole).take(499)
            )
        }
    }
}
