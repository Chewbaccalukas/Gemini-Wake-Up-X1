package com.example.voice

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Encapsulates audio snippet metadata extracted from physical files and Room database.
 */
data class SnippetMetadata(
    val file: File,
    val sizeBytes: Long,
    val durationMs: Long,
    val mimeType: String = "audio/mp4"
) {
    val formattedDuration: String
        get() {
            if (durationMs <= 0) return "0.0s"
            val seconds = durationMs / 1000.0
            return String.format(Locale.US, "%.1fs", seconds)
        }

    val formattedSize: String
        get() {
            if (sizeBytes <= 0) return "0 B"
            if (sizeBytes < 1024) return "$sizeBytes B"
            val kb = sizeBytes / 1024.0
            return if (kb < 1024) {
                String.format(Locale.US, "%.1f KB", kb)
            } else {
                String.format(Locale.US, "%.1f MB", kb / 1024.0)
            }
        }
}

/**
 * Manages physical storage, naming conventions, directory lifecycles, and audio metadata
 * for wake word training snippets stored in the app's dedicated internal storage.
 */
class WakeWordStorageManager(private val context: Context) {

    companion object {
        private const val TAG = "WakeWordStorageManager"
        const val SUBDIR_NAME = "wake_samples"
        const val FILE_PREFIX = "wake"
        const val FILE_EXT = ".wav"

        // Structured slot format: wake_<chatId>_<slotIndex>.(wav|pcm|m4a)
        private val FILE_NAME_REGEX = Regex("""^wake_(\d+)_(\d+)\.(wav|pcm|m4a)$""", RegexOption.IGNORE_CASE)
    }

    val storageDir: File by lazy {
        File(context.filesDir, SUBDIR_NAME).apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    /**
     * Resolves the deterministic File destination for a chat's wake word slot.
     */
    fun getSnippetFile(chatId: Long, slotIndex: Int): File {
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        return File(storageDir, "${FILE_PREFIX}_${chatId}_${slotIndex}${FILE_EXT}")
    }

    /**
     * Parses the chatId from a structured snippet file name, or returns null if non-matching.
     */
    fun parseChatId(fileName: String): Long? {
        val match = FILE_NAME_REGEX.matchEntire(fileName) ?: return null
        return match.groupValues[1].toLongOrNull()
    }

    /**
     * Parses the slot index from a structured snippet file name, or returns null if non-matching.
     */
    fun parseSlotIndex(fileName: String): Int? {
        val match = FILE_NAME_REGEX.matchEntire(fileName) ?: return null
        return match.groupValues[2].toIntOrNull()
    }

    /**
     * Extracts physical file metadata including size in bytes and playback duration in milliseconds.
     */
    fun extractMetadata(file: File): SnippetMetadata {
        val sizeBytes = if (file.exists()) file.length() else 0L
        var durationMs = 0L
        val lowerName = file.name.lowercase(Locale.US)
        val mimeType = when {
            lowerName.endsWith(".wav") -> "audio/wav"
            lowerName.endsWith(".pcm") -> "audio/pcm"
            else -> "audio/mp4"
        }

        if (file.exists() && sizeBytes > 0) {
            if (lowerName.endsWith(".wav") || lowerName.endsWith(".pcm")) {
                val pcmBytes = if (lowerName.endsWith(".wav") && sizeBytes > 44L) {
                    sizeBytes - 44L
                } else {
                    sizeBytes
                }
                // 16kHz 16-bit mono = 32,000 bytes/sec = 32 bytes/ms
                durationMs = (pcmBytes / 32L).coerceAtLeast(1L)
            } else {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    durationMs = durationStr?.toLongOrNull() ?: 0L
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to extract duration for ${file.name}: ${e.message}")
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {}
                }
                if (durationMs <= 0L && sizeBytes > 44L) {
                    durationMs = ((sizeBytes - 44L) / 32L).coerceAtLeast(1L)
                }
            }
        }

        return SnippetMetadata(
            file = file,
            sizeBytes = sizeBytes,
            durationMs = durationMs,
            mimeType = mimeType
        )
    }

    /**
     * Deletes a specific snippet file by chatId and slotIndex.
     */
    fun deleteSnippet(chatId: Long, slotIndex: Int): Boolean {
        val file = getSnippetFile(chatId, slotIndex)
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }

    /**
     * Deletes an audio snippet by its absolute path.
     */
    fun deleteFile(filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (file.exists()) file.delete() else false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Deletes all physical snippet files belonging to a specific chat session.
     */
    fun deleteAllSnippetsForChat(chatId: Long): Int {
        var count = 0
        try {
            val files = storageDir.listFiles() ?: return 0
            for (f in files) {
                if (f.isFile && parseChatId(f.name) == chatId) {
                    if (f.delete()) count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting snippets for chat $chatId", e)
        }
        return count
    }

    /**
     * Prunes orphaned audio snippet files in the storage directory that are no longer
     * referenced by active records in Room.
     */
    fun pruneOrphanedFiles(activeSamplePaths: Set<String>): Int {
        var deletedCount = 0
        try {
            val files = storageDir.listFiles() ?: return 0
            for (f in files) {
                if (f.isFile && !activeSamplePaths.contains(f.absolutePath)) {
                    Log.d(TAG, "Pruning orphaned wake snippet: ${f.name}")
                    if (f.delete()) {
                        deletedCount++
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pruning orphaned files", e)
        }
        return deletedCount
    }

    /**
     * Computes the total disk space in bytes consumed by wake word snippet files.
     */
    fun getTotalStorageBytes(): Long {
        return try {
            val files = storageDir.listFiles() ?: return 0L
            files.filter { it.isFile }.sumOf { it.length() }
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Returns the total count of valid wake word snippet files stored on disk.
     */
    fun getSnippetCount(): Int {
        return try {
            val files = storageDir.listFiles() ?: return 0
            files.count { it.isFile && FILE_NAME_REGEX.matches(it.name) }
        } catch (_: Exception) {
            0
        }
    }
}
