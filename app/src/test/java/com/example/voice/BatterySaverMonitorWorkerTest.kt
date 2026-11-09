package com.example.voice

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BatterySaverMonitorWorkerTest {

    private lateinit var context: Context
    private lateinit var coordinator: SpeechRecognitionCoordinator
    private lateinit var audioRecorder: AudioRecorder
    private var simulatedActiveVoiceUse = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        simulatedActiveVoiceUse = false
        BatterySaverMonitorWorker.powerSaveModeOverrideForTesting = null
        WakeWordVoiceService.setSleepMode(false)
        WakeWordVoiceService.setBatterySaverPaused(false)
        WakeWordVoiceService.setIntentionallyPaused(false)

        coordinator = SpeechRecognitionCoordinator(
            context = context,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            mainDispatcher = Dispatchers.Unconfined
        )
        audioRecorder = AudioRecorder(context)

        BatterySaverMonitorWorker.registerVoiceComponents(
            coordinator = coordinator,
            audioRecorder = audioRecorder,
            activeUseChecker = { simulatedActiveVoiceUse }
        )
    }

    @After
    fun tearDown() {
        BatterySaverMonitorWorker.powerSaveModeOverrideForTesting = null
        WakeWordVoiceService.setSleepMode(false)
        WakeWordVoiceService.setBatterySaverPaused(false)
        WakeWordVoiceService.setIntentionallyPaused(false)
        coordinator.destroy()
        audioRecorder.release()
    }

    @Test
    fun `worker pauses coordinator and audio recorder when battery saver is active and not in active use`() = runBlocking {
        assertFalse(coordinator.isIntentionallyPaused)
        assertFalse(audioRecorder.isIntentionallyPaused)

        BatterySaverMonitorWorker.powerSaveModeOverrideForTesting = true
        simulatedActiveVoiceUse = false

        val worker = TestListenableWorkerBuilder<BatterySaverMonitorWorker>(context).build()
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertTrue(coordinator.isIntentionallyPaused)
        assertTrue(audioRecorder.isIntentionallyPaused)
        assertTrue(WakeWordVoiceService.isIntentionallyPaused)
        assertTrue(WakeWordVoiceService.isBatterySaverPausedGlobal.value)
    }

    @Test
    fun `worker does not pause coordinator when battery saver is active but voice turn is in active use`() = runBlocking {
        BatterySaverMonitorWorker.powerSaveModeOverrideForTesting = true
        simulatedActiveVoiceUse = true

        val worker = TestListenableWorkerBuilder<BatterySaverMonitorWorker>(context).build()
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertFalse(coordinator.isIntentionallyPaused)
        assertFalse(audioRecorder.isIntentionallyPaused)
        assertFalse(WakeWordVoiceService.isBatterySaverPausedGlobal.value)
    }

    @Test
    fun `worker auto-resumes coordinator and audio recorder when battery saver turns off`() = runBlocking {
        // First: Battery Saver ON -> pauses
        BatterySaverMonitorWorker.powerSaveModeOverrideForTesting = true
        simulatedActiveVoiceUse = false
        val workerOn = TestListenableWorkerBuilder<BatterySaverMonitorWorker>(context).build()
        workerOn.doWork()
        assertTrue(coordinator.isIntentionallyPaused)
        assertTrue(audioRecorder.isIntentionallyPaused)
        assertTrue(WakeWordVoiceService.isBatterySaverPausedGlobal.value)

        // Second: Battery Saver OFF -> auto-resumes
        BatterySaverMonitorWorker.powerSaveModeOverrideForTesting = false
        val workerOff = TestListenableWorkerBuilder<BatterySaverMonitorWorker>(context).build()
        val result = workerOff.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertFalse(coordinator.isIntentionallyPaused)
        assertFalse(audioRecorder.isIntentionallyPaused)
        assertFalse(WakeWordVoiceService.isIntentionallyPaused)
        assertFalse(WakeWordVoiceService.isBatterySaverPausedGlobal.value)
    }

    @Test
    fun `service audioPipelineBuffer drops oldest beyond capacity 32 and exposes consumeAsFlow`() = runBlocking {
        val controller = Robolectric.buildService(WakeWordVoiceService::class.java, Intent(context, WakeWordVoiceService::class.java))
        val service = controller.create().get()
        try {
            val flow = service.resetAudioPipelineBuffer()
            // Push 40 chunks into 32-capacity ring buffer
            for (i in 1..40) {
                val chunk = byteArrayOf(i.toByte(), 0)
                val sent = service.audioPipelineBuffer.trySend(chunk)
                assertTrue(sent.isSuccess)
            }
            val collected = flow.take(32).toList()
            assertEquals(32, collected.size)
            // First 8 chunks (1..8) dropped; oldest retained is 9
            assertArrayEquals(byteArrayOf(9, 0), collected.first())
            assertArrayEquals(byteArrayOf(40, 0), collected.last())
        } finally {
            controller.destroy()
        }
    }
}
