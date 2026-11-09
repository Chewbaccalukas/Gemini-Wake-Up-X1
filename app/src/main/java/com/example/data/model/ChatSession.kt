package com.example.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.example.data.db.AppDatabase

typealias Chat = ChatSession

/**
 * Room `@Entity` representing a conversation session (`Chat` / `ChatSession`)
 * in the hybrid offline-first Room + Cloud Firestore synchronization architecture.
 */
@Entity(
    tableName = "chat_sessions",
    indices = [
        Index(value = ["remoteId"]),
        Index(value = ["syncStatus"]),
        Index(value = ["userId"]),
        Index(value = ["isDeleted"])
    ]
)
@TypeConverters(AppDatabase.Converters::class)
data class ChatSession(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "wakeWord")
    val wakeWord: String, // e.g. "hey gemini", "code mode", "creative idea"

    @ColumnInfo(name = "isDefaultWakeChat")
    val isDefaultWakeChat: Boolean = false, // If true, opens when general wake word or fallback is spoken

    @ColumnInfo(name = "isPersistent")
    val isPersistent: Boolean = true, // If true, saved to SQLite disk; if false, ephemeral RAM buffer only

    @ColumnInfo(name = "systemInstruction")
    val systemInstruction: String = "You are Gemini, a helpful, brilliant, and proactive AI companion.",

    @ColumnInfo(name = "greetingMessage", defaultValue = "")
    val greetingMessage: String = "",

    @ColumnInfo(name = "iconName")
    val iconName: String = "Sparkles", // Sparkles, Code, Lightbulb, Chat, Fitness, Book

    @ColumnInfo(name = "themeColorHex")
    val themeColorHex: String = "#38BDF8",

    @ColumnInfo(name = "themeMode")
    val themeMode: com.example.ui.theme.AppTheme = com.example.ui.theme.AppTheme.SYSTEM,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "lastUpdatedAt")
    val lastUpdatedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "totalTrackedSeconds")
    val totalTrackedSeconds: Long = 0L, // Accumulated tracked stopwatch/timer duration in seconds

    @ColumnInfo(name = "totalVoiceCommands", defaultValue = "0")
    val totalVoiceCommands: Int = 0, // Total wake-word-triggered voice command turns committed in this session

    @ColumnInfo(name = "remoteId")
    val remoteId: String? = null, // Cloud Firestore document ID (e.g. "chat_{id}")

    @ColumnInfo(name = "userId")
    val userId: String? = null, // Authenticated Firebase user UID owning this record

    @ColumnInfo(name = "syncStatus")
    val syncStatus: String = SyncStatus.PENDING, // "PENDING", "SYNCED", "DIRTY", or "FAILED"

    @ColumnInfo(name = "lastSyncedAt")
    val lastSyncedAt: Long = 0L, // Epoch ms when last synchronized with Cloud Firestore

    @ColumnInfo(name = "isDeleted", defaultValue = "0")
    val isDeleted: Boolean = false, // Soft-delete tombstone flag for hybrid cloud sync

    @ColumnInfo(name = "isPinned", defaultValue = "0")
    val isPinned: Boolean = false, // If true, pinned to the top of the chat list regardless of recency

    @ColumnInfo(name = "colorPaletteId", defaultValue = "'OCEAN_CYAN'")
    val colorPaletteId: String = "OCEAN_CYAN", // Predefined chat color palette identifier

    @ColumnInfo(name = "backgroundStyle", defaultValue = "'DEFAULT'")
    val backgroundStyle: String = "DEFAULT" // Predefined background preset ID or "custom:<uri>"
) {
    val resolvedRemoteId: String
        get() = remoteId?.takeIf { it.isNotBlank() } ?: "chat_$id"

    val isSynced: Boolean
        get() = syncStatus == SyncStatus.SYNCED && !isDeleted

    val needsSync: Boolean
        get() = syncStatus != SyncStatus.SYNCED || isDeleted

    val syncStateEnum: SyncState
        get() = SyncState.fromStatus(syncStatus)
}

val ChatSession.pinned: Boolean
    get() = isPinned

