package com.example.importer

import com.example.data.model.ChatMessage
import com.example.util.CompressionStats
import com.example.util.GzipUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class ImportProgress(
    val currentChunkIndex: Int,
    val totalChunks: Int,
    val importedMessageCount: Int,
    val totalMessageCount: Int,
    val percentage: Float,
    val currentPreviewText: String,
    val isComplete: Boolean = false,
    val compressionStats: CompressionStats? = null
)

enum class ImportPacing(val label: String, val delayMs: Long, val chunkSize: Int) {
    SAFE_SLOW("Safe Anti-Choke (150ms)", 150L, 5),
    BALANCED("Balanced (75ms)", 75L, 8),
    TURBO("Turbo (20ms)", 20L, 12)
}

class ChunkedChatImporter {

    /**
     * Splits messages into compressed/chunked batches and yields progressive import events.
     * Pacing delay avoids choking the thread or SQLite buffer on large threads (100+ turns).
     */
    fun importInChunks(
        messages: List<ChatMessage>,
        pacing: ImportPacing = ImportPacing.SAFE_SLOW,
        onChunkImported: suspend (List<ChatMessage>) -> Unit
    ): Flow<ImportProgress> = flow {
        val totalMessages = messages.size
        if (totalMessages == 0) {
            emit(
                ImportProgress(
                    currentChunkIndex = 0,
                    totalChunks = 0,
                    importedMessageCount = 0,
                    totalMessageCount = 0,
                    percentage = 1f,
                    currentPreviewText = "No messages to import.",
                    isComplete = true
                )
            )
            return@flow
        }

        // Calculate combined raw content for compression stats
        val totalRawText = messages.joinToString("\n") { it.content }
        val compressionStats = GzipUtil.calculateStats(totalRawText)

        val chunks = messages.chunked(pacing.chunkSize)
        val totalChunks = chunks.size
        var importedSoFar = 0

        for (index in chunks.indices) {
            val chunk = chunks[index]
            val chunkPreview = chunk.firstOrNull()?.content?.take(40)?.replace("\n", " ") ?: ""

            // Persist this chunk into database via callback
            onChunkImported(chunk)
            importedSoFar += chunk.size

            val progressRatio = (index + 1).toFloat() / totalChunks
            emit(
                ImportProgress(
                    currentChunkIndex = index + 1,
                    totalChunks = totalChunks,
                    importedMessageCount = importedSoFar,
                    totalMessageCount = totalMessages,
                    percentage = progressRatio,
                    currentPreviewText = "Chunk ${index + 1}/$totalChunks: \"$chunkPreview...\"",
                    isComplete = (index == totalChunks - 1),
                    compressionStats = compressionStats
                )
            )

            // Safe throttling pacing to yield thread & prevent freezing
            if (pacing.delayMs > 0 && index < totalChunks - 1) {
                delay(pacing.delayMs)
            }
        }
    }
}
