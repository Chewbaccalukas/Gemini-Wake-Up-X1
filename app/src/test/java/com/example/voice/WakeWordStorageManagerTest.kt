package com.example.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WakeWordStorageManagerTest {

    private lateinit var context: Context
    private lateinit var storageManager: WakeWordStorageManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        storageManager = WakeWordStorageManager(context)
        // Clean out any leftover files
        storageManager.storageDir.listFiles()?.forEach { it.delete() }
    }

    @Test
    fun testFileNamingConventionAndParsing() {
        val snippetFile = storageManager.getSnippetFile(chatId = 105L, slotIndex = 3)
        assertEquals("wake_105_3.wav", snippetFile.name)
        assertTrue(snippetFile.parentFile!!.absolutePath.endsWith("wake_samples"))

        assertEquals(105L, storageManager.parseChatId("wake_105_3.wav"))
        assertEquals(3, storageManager.parseSlotIndex("wake_105_3.wav"))
        assertEquals(105L, storageManager.parseChatId("wake_105_3.pcm"))
        assertEquals(105L, storageManager.parseChatId("wake_105_3.m4a"))

        assertNull(storageManager.parseChatId("invalid_name.wav"))
        assertNull(storageManager.parseSlotIndex("invalid_name.wav"))
        assertNull(storageManager.parseChatId("wake_abc_1.wav"))
    }

    @Test
    fun testDirectoryCreation() {
        assertTrue(storageManager.storageDir.exists())
        assertTrue(storageManager.storageDir.isDirectory)
    }

    @Test
    fun testExtractMetadataForExistingFile() {
        val testFile = storageManager.getSnippetFile(chatId = 77L, slotIndex = 0)
        testFile.writeBytes(ByteArray(2048) { 0x1A })

        val meta = storageManager.extractMetadata(testFile)
        assertEquals(2048L, meta.sizeBytes)
        assertEquals("audio/wav", meta.mimeType)
        assertEquals("2.0 KB", meta.formattedSize)

        testFile.delete()
    }

    @Test
    fun testExtractMetadataForNonExistentFile() {
        val missingFile = File(storageManager.storageDir, "wake_999_9.m4a")
        val meta = storageManager.extractMetadata(missingFile)
        assertEquals(0L, meta.sizeBytes)
        assertEquals(0L, meta.durationMs)
        assertEquals("0 B", meta.formattedSize)
        assertEquals("0.0s", meta.formattedDuration)
    }

    @Test
    fun testDeleteSnippetAndAllSnippetsForChat() {
        val chat10Slot0 = storageManager.getSnippetFile(10L, 0)
        val chat10Slot1 = storageManager.getSnippetFile(10L, 1)
        val chat20Slot0 = storageManager.getSnippetFile(20L, 0)

        chat10Slot0.writeText("sample 10-0")
        chat10Slot1.writeText("sample 10-1")
        chat20Slot0.writeText("sample 20-0")

        assertEquals(3, storageManager.getSnippetCount())

        // Delete single snippet
        val deletedSingle = storageManager.deleteSnippet(10L, 0)
        assertTrue(deletedSingle)
        assertFalse(chat10Slot0.exists())
        assertTrue(chat10Slot1.exists())

        // Delete all for chat 10
        val deletedCount = storageManager.deleteAllSnippetsForChat(10L)
        assertEquals(1, deletedCount)
        assertFalse(chat10Slot1.exists())
        assertTrue(chat20Slot0.exists())

        chat20Slot0.delete()
    }

    @Test
    fun testPruneOrphanedFiles() {
        val active1 = storageManager.getSnippetFile(50L, 0)
        val active2 = storageManager.getSnippetFile(50L, 1)
        val orphan = storageManager.getSnippetFile(99L, 4)

        active1.writeText("active 1")
        active2.writeText("active 2")
        orphan.writeText("abandoned orphan")

        val activePaths = setOf(active1.absolutePath, active2.absolutePath)
        val pruned = storageManager.pruneOrphanedFiles(activePaths)

        assertEquals(1, pruned)
        assertFalse(orphan.exists())
        assertTrue(active1.exists())
        assertTrue(active2.exists())

        active1.delete()
        active2.delete()
    }

    @Test
    fun testTotalStorageBytesComputation() {
        val file1 = storageManager.getSnippetFile(1L, 0)
        val file2 = storageManager.getSnippetFile(1L, 1)

        file1.writeBytes(ByteArray(1000))
        file2.writeBytes(ByteArray(1500))

        assertEquals(2500L, storageManager.getTotalStorageBytes())

        file1.delete()
        file2.delete()
    }
}
