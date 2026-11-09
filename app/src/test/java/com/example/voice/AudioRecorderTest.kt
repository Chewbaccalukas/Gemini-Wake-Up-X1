package com.example.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.model.WakeWordSample
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioRecorderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testAudioRecorderInitialState() {
        val recorder = AudioRecorder(context)
        assertEquals(AudioRecorder.State.Idle, recorder.state.value)
        assertFalse(recorder.isRecording)
        assertEquals(0, recorder.getMaxAmplitude())

        recorder.cancel()
        assertEquals(AudioRecorder.State.Idle, recorder.state.value)

        recorder.release()
        assertEquals(AudioRecorder.State.Idle, recorder.state.value)
    }

    @Test
    fun testSnippetAudioPlayerInitialState() {
        val player = SnippetAudioPlayer()
        assertFalse(player.isPlaying)
        assertEquals(null, player.currentPlayingPath.value)

        player.stop()
        assertFalse(player.isPlaying)

        player.release()
        assertFalse(player.isPlaying)
    }

    @Test
    fun testWakeWordSampleIndexExtractionForM4a() {
        val sample = WakeWordSample(
            chatId = 42L,
            samplePath = "/data/user/0/com.aistudio.geminiwake/files/wake_samples/wake_word_42_3.m4a"
        )
        assertEquals(3, sample.sampleIndex)
        assertEquals(42L, sample.chatId)
        assertTrue(sample.samplePath.endsWith("wake_word_42_3.m4a"))
        assertEquals(sample.samplePath, sample.filePath)
    }

    @Test
    fun testSnippetAudioPlayerHandlesMissingFileGracefully() {
        val player = SnippetAudioPlayer()
        val nonExistentFile = File(context.filesDir, "does_not_exist.m4a")

        var completionCalled = false
        player.play(nonExistentFile) {
            completionCalled = true
        }

        // Should safely avoid crashing and remain in stopped/idle state
        assertFalse(player.isPlaying)
        assertFalse(completionCalled)
        player.release()
    }

    @Test
    fun testSnippetAudioPlayerPauseAndResumeWhenIdle() {
        val player = SnippetAudioPlayer()
        assertFalse(player.isPaused.value)
        assertFalse(player.isPlaying)

        // Pausing or resuming when idle must not crash
        player.pause()
        assertFalse(player.isPaused.value)

        player.resume()
        assertFalse(player.isPaused.value)
        player.release()
    }

    @Test
    fun testSnippetAudioPlayerPlaySequenceEmptyOrMissingFiles() {
        val player = SnippetAudioPlayer()
        var completed = false

        // Empty file list
        player.playSequence(emptyList()) {
            completed = true
        }
        assertFalse(player.isPlaying)
        assertFalse(completed)

        // Non-existent files
        val missingFiles = listOf(
            File(context.filesDir, "missing1.m4a"),
            File(context.filesDir, "missing2.m4a")
        )
        player.playSequence(missingFiles) {
            completed = true
        }
        assertFalse(player.isPlaying)
        assertFalse(completed)
        player.release()
    }

    @Test
    fun testSnippetAudioPlayerStopResetsAllStateFlows() {
        val player = SnippetAudioPlayer()
        assertEquals(0f, player.playbackProgress.value, 0.001f)
        assertEquals(0, player.playbackPositionMs.value)
        assertEquals(0, player.playbackDurationMs.value)
        assertFalse(player.isPaused.value)
        assertEquals(null, player.currentPlayingPath.value)

        player.stop()

        assertEquals(0f, player.playbackProgress.value, 0.001f)
        assertEquals(0, player.playbackPositionMs.value)
        assertEquals(0, player.playbackDurationMs.value)
        assertFalse(player.isPaused.value)
        assertEquals(null, player.currentPlayingPath.value)
        player.release()
    }

    @Test
    fun testAudioPipelineBufferRingBufferDropOldestAndConsumeAsFlow() = kotlinx.coroutines.test.runTest {
        val recorder = AudioRecorder(context)
        assertEquals(32, AudioRecorder.AUDIO_PIPELINE_BUFFER_CAPACITY)
        assertFalse(recorder.isIntentionallyPaused)

        // Push 40 PCM chunks non-blockingly into the 32-slot DROP_OLDEST ring buffer
        for (i in 0 until 40) {
            val chunk = byteArrayOf(i.toByte(), 0)
            val sent = recorder.pushPcmChunkToPipeline(chunk)
            assertTrue("Non-blocking trySend into ring buffer should succeed for chunk $i", sent)
        }

        // Collect 32 items from audioPipelineFlow (consumeAsFlow()): oldest 8 (0..7) should be dropped,
        // leaving chunks 8..39 in exact FIFO order.
        val collected = mutableListOf<Int>()
        val collectJob = backgroundScope.launch(
            kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
            start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED
        ) {
            recorder.audioPipelineFlow.collect { bytes ->
                collected.add(bytes[0].toInt())
            }
        }
        testScheduler.runCurrent()

        assertEquals(32, collected.size)
        assertEquals(8, collected.first())
        assertEquals(39, collected.last())

        collectJob.cancel()
        recorder.release()
    }

    @Test
    fun testAudioRecorderRespectsIsIntentionallyPausedGuard() {
        val recorder = AudioRecorder(context)
        assertFalse(recorder.isIntentionallyPaused)

        recorder.setIntentionallyPaused(true)
        assertTrue(recorder.isIntentionallyPaused)

        // While intentionally paused (sleep mode), pushing chunks and starting capture must abort cleanly
        assertFalse(recorder.pushPcmChunkToPipeline(byteArrayOf(1, 2, 3, 4)))
        val outFile = File(context.cacheDir, "paused_test.wav")
        val startResult = recorder.start(outFile)
        assertTrue(startResult.isFailure)
        assertFalse(recorder.isRecording)

        // Resuming clears the guard
        recorder.setIntentionallyPaused(false)
        assertFalse(recorder.isIntentionallyPaused)
        assertTrue(recorder.pushPcmChunkToPipeline(byteArrayOf(10, 20)))
        recorder.release()
    }
}
