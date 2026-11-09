package com.example

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.importer.FileProcessingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilePickerAndProcessingServiceTest {

    @Test
    fun testCreateFilePickerIntentFiltersSupportedMimeTypes() {
        val intent = MainActivity.createSupportedFilePickerIntent()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals("*/*", intent.type)

        val mimeTypes = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)
        assertNotNull(mimeTypes)
        val mimeList = mimeTypes!!.toList()
        assertTrue(mimeList.contains("text/markdown"))
        assertTrue(mimeList.contains("text/plain"))
        assertTrue(mimeList.contains("application/pdf"))
        assertTrue(mimeList.contains("application/msword"))
        assertTrue(mimeList.contains("text/html"))
        assertTrue(mimeList.contains("application/json"))
    }

    @Test
    fun testIsSupportedFileRecognizesMdTxtPdfDocHtmlJson() {
        assertTrue(FileProcessingService.isSupportedFile("notes.md", null))
        assertTrue(FileProcessingService.isSupportedFile("transcript.txt", "text/plain"))
        assertTrue(FileProcessingService.isSupportedFile("report.pdf", "application/pdf"))
        assertTrue(FileProcessingService.isSupportedFile("spec.doc", "application/msword"))
        assertTrue(FileProcessingService.isSupportedFile("spec.docx", null))
        assertTrue(FileProcessingService.isSupportedFile("page.html", "text/html"))
        assertTrue(FileProcessingService.isSupportedFile("export.json", "application/json"))

        assertFalse(FileProcessingService.isSupportedFile("malware.exe", "application/x-msdownload"))
        assertFalse(FileProcessingService.isSupportedFile("video.mp4", "video/mp4"))
    }

    @Test
    fun testMainActivityHandlePickedFileUriDispatchesToBackgroundService() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowApp = shadowOf(app)
        shadowApp.clearStartedServices()

        val tempFile = File(app.cacheDir, "sample_chat.md").apply {
            writeText("You: Hello\nGemini: Hi there!")
        }
        val uri = Uri.fromFile(tempFile)

        val handled = MainActivity.handlePickedFileUri(
            context = app,
            uri = uri,
            targetChatId = 42L,
            action = FileProcessingService.ACTION_PROCESS_FILE_IMPORT
        )
        assertTrue(handled)

        val startedServiceIntent = shadowApp.nextStartedService
        assertNotNull("Expected FileProcessingService to be started", startedServiceIntent)
        assertEquals(
            FileProcessingService::class.java.name,
            startedServiceIntent.component?.className
        )
        assertEquals(uri, FileProcessingService.extractFileUri(startedServiceIntent))
        assertEquals(42L, startedServiceIntent.getLongExtra(FileProcessingService.EXTRA_TARGET_CHAT_ID, -1L))
        assertEquals(FileProcessingService.ACTION_PROCESS_FILE_IMPORT, startedServiceIntent.action)

        tempFile.delete()
    }

    @Test
    fun testMainActivityHandlePickedFileUriRejectsUnsupportedFile() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val shadowApp = shadowOf(app)
        shadowApp.clearStartedServices()

        val badFile = File(app.cacheDir, "archive.zip").apply {
            writeText("fake zip")
        }
        val uri = Uri.fromFile(badFile)

        val handled = MainActivity.handlePickedFileUri(
            context = app,
            uri = uri,
            targetChatId = 10L
        )
        assertFalse(handled)
        assertNull(shadowApp.nextStartedService)

        badFile.delete()
    }
}
