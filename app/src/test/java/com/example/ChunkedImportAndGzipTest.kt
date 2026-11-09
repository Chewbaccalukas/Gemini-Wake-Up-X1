package com.example

import com.example.data.model.ChatMessage
import com.example.importer.ChunkedChatImporter
import com.example.importer.ImportPacing
import com.example.util.GzipUtil
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkedImportAndGzipTest {

    @Test
    fun testGzipCompressionAndDecompression() {
        val testString = "This is a long chat transcript with repeating Gemini phrases and instructions. ".repeat(20)
        val compressed = GzipUtil.compress(testString)
        val decompressed = GzipUtil.decompress(compressed)

        assertEquals(testString, decompressed)
        val stats = GzipUtil.calculateStats(testString)
        assertTrue(stats.reductionPercentage > 40f)
        assertTrue(stats.compressedSizeBytes < stats.originalSizeBytes)
    }

    @Test
    fun testChunkedChatImporterBatching() = runBlocking {
        val importer = ChunkedChatImporter()
        val dummyMessages = (1..25).map { i ->
            ChatMessage(
                chatId = 1,
                sender = if (i % 2 == 0) "user" else "gemini",
                content = "Message turn #$i with some sample content",
                timestamp = 1000L * i
            )
        }

        val importedChunks = mutableListOf<List<ChatMessage>>()
        val progressEvents = importer.importInChunks(
            messages = dummyMessages,
            pacing = ImportPacing.TURBO, // Turbo (chunkSize = 12, delay = 20ms)
            onChunkImported = { chunk ->
                importedChunks.add(chunk)
            }
        ).toList()

        assertEquals(3, importedChunks.size) // 12 + 12 + 1 = 25
        assertEquals(25, importedChunks.flatten().size)
        assertTrue(progressEvents.last().isComplete)
        assertEquals(25, progressEvents.last().importedMessageCount)
        assertEquals(1f, progressEvents.last().percentage, 0.01f)
    }
}
