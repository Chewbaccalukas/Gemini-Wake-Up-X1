package com.example.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.example.data.db.AppDatabase
import java.util.Locale
import java.util.UUID

@Entity(
    tableName = "wake_word_samples",
    indices = [
        Index(value = ["chatId"]),
        Index(value = ["wakeWordId"]),
        Index(value = ["sampleUuid"])
    ]
)
@TypeConverters(AppDatabase.Converters::class)
data class WakeWordSample(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val chatId: Long,
    @ColumnInfo(name = "wakeWordId")
    val wakeWordId: Long? = null,
    @ColumnInfo(name = "sampleUuid")
    val sampleUuid: String = UUID.randomUUID().toString(),
    @ColumnInfo(name = "wakePhrase")
    val wakePhrase: String = "",
    val samplePath: String,
    val recordedAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val mimeType: String = "audio/mp4"
) {
    val filePath: String
        get() = samplePath

    val sampleIndex: Int
        get() = samplePath.substringAfterLast("_").substringBefore(".").toIntOrNull() ?: 0

    val shortUniqueId: String
        get() = sampleUuid.take(8).uppercase(Locale.US)

    val formattedDuration: String
        get() {
            if (durationMs <= 0) return ""
            val seconds = durationMs / 1000.0
            return String.format(Locale.US, "%.1fs", seconds)
        }

    val formattedSize: String
        get() {
            if (sizeBytes <= 0) return ""
            val kb = sizeBytes / 1024.0
            return String.format(Locale.US, "%.1f KB", kb)
        }
}
