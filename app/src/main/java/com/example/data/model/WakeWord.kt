package com.example.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlin.math.roundToInt

enum class TriggerType {
    CHAT,
    HANDS_FREE
}

/**
 * Room `@Entity` representing a custom or system wake word (`WakeWord`)
 * in the hybrid offline-first Room + Cloud Firestore synchronization architecture.
 *
 * Supports both normalized float sensitivity (`0.50f..0.98f`) and integer sensitivity (`0..100`),
 * as well as `targetChatId` / `chatId` for associating each wake word with a specific chat session.
 */
@Entity(
    tableName = "wake_words",
    indices = [
        Index(value = ["chatId"]),
        Index(value = ["targetChatId"]),
        Index(value = ["triggerType"]),
        Index(value = ["commandRole"]),
        Index(value = ["remoteId"]),
        Index(value = ["syncStatus"]),
        Index(value = ["userId"]),
        Index(value = ["isDeleted"])
    ]
)
data class WakeWord(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "phrase")
    val phrase: String,

    @ColumnInfo(name = "triggerType")
    val triggerType: TriggerType = TriggerType.CHAT,

    @ColumnInfo(name = "metadata")
    val metadata: String? = null,

    @ColumnInfo(name = "commandRole")
    val commandRole: String = ROLE_CHAT_WAKE,

    @ColumnInfo(name = "chatId")
    val chatId: Long? = null,

    @ColumnInfo(name = "targetChatId")
    val targetChatId: Long? = chatId,

    @ColumnInfo(name = "chatTitle")
    val chatTitle: String = "",

    @ColumnInfo(name = "isEnabled")
    val isEnabled: Boolean = true,

    @ColumnInfo(name = "sensitivity")
    val sensitivity: Float = 0.78f,

    @ColumnInfo(name = "sensitivityInt", defaultValue = "78")
    val sensitivityInt: Int = toSensitivityInt(sensitivity),

    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "remoteId")
    val remoteId: String? = null,

    @ColumnInfo(name = "userId")
    val userId: String? = null,

    @ColumnInfo(name = "syncStatus")
    val syncStatus: String = SyncStatus.PENDING,

    @ColumnInfo(name = "lastSyncedAt")
    val lastSyncedAt: Long = 0L,

    @ColumnInfo(name = "isDeleted", defaultValue = "0")
    val isDeleted: Boolean = false
) {
    /**
     * Secondary constructor allowing callers to specify `sensitivity` as an integer in `0..100`
     * and `targetChatId` directly.
     */
    constructor(
        id: Long = 0,
        phrase: String,
        triggerType: TriggerType = TriggerType.CHAT,
        metadata: String? = null,
        commandRole: String = ROLE_CHAT_WAKE,
        targetChatId: Long? = null,
        chatTitle: String = "",
        isEnabled: Boolean = true,
        sensitivity: Int,
        createdAt: Long = System.currentTimeMillis(),
        updatedAt: Long = System.currentTimeMillis(),
        remoteId: String? = null,
        userId: String? = null,
        syncStatus: String = SyncStatus.PENDING,
        lastSyncedAt: Long = 0L,
        isDeleted: Boolean = false
    ) : this(
        id = id,
        phrase = phrase,
        triggerType = triggerType,
        metadata = metadata,
        commandRole = commandRole,
        chatId = targetChatId,
        targetChatId = targetChatId,
        chatTitle = chatTitle,
        isEnabled = isEnabled,
        sensitivity = fromSensitivityInt(sensitivity),
        sensitivityInt = sensitivity.coerceIn(0, 100),
        createdAt = createdAt,
        updatedAt = updatedAt,
        remoteId = remoteId,
        userId = userId,
        syncStatus = syncStatus,
        lastSyncedAt = lastSyncedAt,
        isDeleted = isDeleted
    )

    /**
     * Returns the effective associated chat ID, resolving `targetChatId` or `chatId`.
     */
    val effectiveTargetChatId: Long?
        get() = targetChatId ?: chatId

    /**
     * Returns the sensitivity on a `0..100` integer scale.
     */
    val sensitivityPercentage: Int
        get() = sensitivityInt.coerceIn(0, 100)

    val resolvedRemoteId: String
        get() = remoteId?.takeIf { it.isNotBlank() } ?: "wake_$id"

    val isSynced: Boolean
        get() = syncStatus == SyncStatus.SYNCED && !isDeleted

    val needsSync: Boolean
        get() = syncStatus != SyncStatus.SYNCED || isDeleted

    val syncStateEnum: SyncState
        get() = SyncState.fromStatus(syncStatus)

    companion object {
        val TYPE_CHAT: TriggerType = TriggerType.CHAT
        val TYPE_HANDS_FREE: TriggerType = TriggerType.HANDS_FREE

        const val ROLE_CHAT_WAKE = "CHAT_WAKE"
        const val ROLE_DICTATE = "DICTATE"
        const val ROLE_SEND = "SEND"
        const val ROLE_HEARTBEAT = "HEARTBEAT"

        fun toSensitivityInt(value: Float): Int {
            return if (value > 1.0f) {
                value.roundToInt().coerceIn(0, 100)
            } else {
                (value * 100f).roundToInt().coerceIn(0, 100)
            }
        }

        fun fromSensitivityInt(percent: Int): Float {
            val clamped = percent.coerceIn(0, 100)
            return (clamped / 100f).coerceIn(0.50f, 0.98f)
        }
    }
}
