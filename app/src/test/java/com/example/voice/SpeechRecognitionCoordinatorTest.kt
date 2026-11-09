package com.example.voice

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeechRecognitionCoordinatorTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun onEndOfSpeech_doesNotTriggerImmediateStartListening() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        var prepareHardwareCalls = 0

        val coordinator = SpeechRecognitionCoordinator(
            context = context,
            scope = scope,
            mainDispatcher = testDispatcher,
            isContinuousModeEnabled = { true },
            onPrepareHardwareBeforeStart = { prepareHardwareCalls++ }
        )

        coordinator.recognitionListener.onReadyForSpeech(Bundle())
        assertTrue(coordinator.isListening.value)

        coordinator.recognitionListener.onEndOfSpeech()
        assertFalse(coordinator.isListening.value)

        advanceTimeBy(5_000L)
        runCurrent()
        assertEquals("onEndOfSpeech() must never call startListening()", 0, prepareHardwareCalls)
    }

    @Test
    fun onError_noMatchAndSpeechTimeout_debouncesAtLeast1500ms_andStopsAfterIdleThreshold() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        var idleThresholdHitCount = 0

        val coordinator = SpeechRecognitionCoordinator(
            context = context,
            scope = scope,
            mainDispatcher = testDispatcher,
            maxIdleTimeoutRestarts = 3,
            isContinuousModeEnabled = { true },
            onIdleThresholdReached = { idleThresholdHitCount++ }
        )

        // 1st idle timeout (ERROR_NO_MATCH = 7)
        coordinator.recognitionListener.onError(SpeechRecognizer.ERROR_NO_MATCH)
        assertEquals(1, coordinator.consecutiveIdleTimeouts)
        assertFalse(coordinator.isIntentionallyPaused)

        // 2nd idle timeout (ERROR_SPEECH_TIMEOUT = 6)
        coordinator.recognitionListener.onError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        assertEquals(2, coordinator.consecutiveIdleTimeouts)
        assertFalse(coordinator.isIntentionallyPaused)

        // 3rd idle timeout hits maxIdleTimeoutRestarts = 3 -> pauses and aborts infinite loop
        coordinator.recognitionListener.onError(SpeechRecognizer.ERROR_NO_MATCH)
        assertEquals(3, coordinator.consecutiveIdleTimeouts)
        assertTrue(
            "Coordinator must set isIntentionallyPaused=true when idle threshold is reached",
            coordinator.isIntentionallyPaused
        )
        assertEquals(1, idleThresholdHitCount)
    }

    @Test
    fun guards_isIntentionallyPaused_isAsleep_and_isProcessing_abortStartListening() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        val coordinator = SpeechRecognitionCoordinator(
            context = context,
            scope = scope,
            mainDispatcher = testDispatcher,
            isContinuousModeEnabled = { true }
        )

        // 1. isIntentionallyPaused guard
        coordinator.setIntentionallyPaused(true)
        assertTrue(coordinator.shouldAbortListening())
        assertFalse(coordinator.startListening(userInitiated = false))

        // 2. isAsleep guard
        coordinator.setIntentionallyPaused(false)
        coordinator.setSleeping(true)
        assertTrue(coordinator.shouldAbortListening())
        assertFalse(coordinator.startListening(userInitiated = true))

        // 3. isProcessing guard
        coordinator.setSleeping(false)
        coordinator.setProcessingState(true)
        assertTrue(coordinator.shouldAbortListening())
        assertFalse(coordinator.startListening(userInitiated = true))

        // Clear all guards
        coordinator.setProcessingState(false)
        assertFalse(coordinator.shouldAbortListening())
    }

    @Test
    fun streamSystemMute_mutesBeforeStart_andRestoresOnReadyForSpeechOrOnError() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 5, 0)

        val coordinator = SpeechRecognitionCoordinator(
            context = context,
            scope = scope,
            mainDispatcher = testDispatcher,
            muteSystemChimeOnStart = true,
            isContinuousModeEnabled = { true }
        )

        // Mute STREAM_SYSTEM around startListening()
        coordinator.muteSystemStreamForStartChime()
        assertTrue(coordinator.isSystemStreamCurrentlyMutedByCoordinator())

        // Restored on onReadyForSpeech()
        coordinator.recognitionListener.onReadyForSpeech(Bundle())
        assertFalse(coordinator.isSystemStreamCurrentlyMutedByCoordinator())
        assertFalse(audioManager.isStreamMute(AudioManager.STREAM_SYSTEM))

        // Mute again and verify onError() also restores STREAM_SYSTEM
        coordinator.muteSystemStreamForStartChime()
        assertTrue(coordinator.isSystemStreamCurrentlyMutedByCoordinator())
        coordinator.recognitionListener.onError(SpeechRecognizer.ERROR_NO_MATCH)
        assertFalse(coordinator.isSystemStreamCurrentlyMutedByCoordinator())
        assertFalse(audioManager.isStreamMute(AudioManager.STREAM_SYSTEM))
    }

    @Test
    fun diagnosticTraceAndMutexSnapshot_recordsThreadAndMutexFlags_andParsesCorrectly() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        val coordinator = SpeechRecognitionCoordinator(
            context = context,
            scope = scope,
            mainDispatcher = testDispatcher,
            isContinuousModeEnabled = { true }
        )

        coordinator.recognitionListener.onReadyForSpeech(Bundle())
        assertTrue(coordinator.mutexSnapshotFlow.value.isListening)
        assertTrue(coordinator.traceHistory.value.any { it.contains("CB_ON_READY_FOR_SPEECH") })

        val topRaw = coordinator.traceHistory.value.first()
        val parsed = com.example.ui.screens.DiagnosticTraceParser.parseSrTraceString(topRaw, 1)
        assertEquals("CB_ON_READY_FOR_SPEECH", parsed.stage)
        assertEquals(com.example.ui.screens.DiagnosticFilterCategory.CALLBACKS, parsed.category)
        assertEquals("true", parsed.mutexFlags["listening"])

        coordinator.pauseForCommandDispatch("test_nav")
        assertFalse(coordinator.mutexSnapshotFlow.value.isIntentionallyPaused)
        assertTrue(coordinator.mutexSnapshotFlow.value.isProcessing)
        assertTrue(coordinator.mutexSnapshotFlow.value.isProcessingCommand)

        coordinator.completeCommandProcessingAndRearm(delayMs = 1500L, reason = "test_nav_done")
        assertFalse(coordinator.mutexSnapshotFlow.value.isProcessing)
        assertFalse(coordinator.mutexSnapshotFlow.value.isProcessingCommand)
        assertFalse(coordinator.mutexSnapshotFlow.value.isIntentionallyPaused)

        coordinator.clearTraceHistory()
        assertTrue(coordinator.traceHistory.value.isEmpty())
    }

    @Test
    fun onError_errorClient_whenHandsFreeActive_doesNotSetIntentionallyPaused_recreatesRecognizer_andSchedulesDebouncedRestart() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        var handsFreeEnabled = true

        val coordinator = SpeechRecognitionCoordinator(
            context = context,
            scope = scope,
            mainDispatcher = testDispatcher,
            isContinuousModeEnabled = { handsFreeEnabled }
        )

        // Simulate a stale pause state prior to ERROR_CLIENT(5) callback while hands-free is ON
        coordinator.setIntentionallyPaused(true)
        assertTrue(coordinator.isIntentionallyPaused)
        val initialRecreations = coordinator.recognizerRecreationCount

        // Trigger ERROR_CLIENT (5) while handsFreeMode is active
        coordinator.recognitionListener.onError(SpeechRecognizer.ERROR_CLIENT)

        // 1. Must NOT leave isIntentionallyPaused = true when hands-free is active
        assertFalse(
            "ERROR_CLIENT(5) must clear isIntentionallyPaused when hands-free mode is active",
            coordinator.isIntentionallyPaused
        )
        // 2. Must recreate/reset SpeechRecognizer instance before debounced restart
        assertEquals(initialRecreations + 1, coordinator.recognizerRecreationCount)
        // 3. Must schedule a debounced restart
        assertTrue(
            "ERROR_CLIENT(5) must schedule a debounced restart when hands-free is active",
            coordinator.hasPendingRestart
        )
        assertTrue(
            coordinator.traceHistory.value.any { it.contains("CB_ON_ERROR_CLIENT_TRANSIENT_RESET") }
        )

        // When hands-free is OFF, ERROR_CLIENT should abort without scheduling a restart
        handsFreeEnabled = false
        coordinator.stopListening(markIntentionallyPaused = true)
        coordinator.recognitionListener.onError(SpeechRecognizer.ERROR_CLIENT)
        assertFalse(coordinator.hasPendingRestart)
    }

    @Test
    fun setWindowFocused_whenTrueAndHandsFreeEnabledAndNotProcessing_clearsIntentionallyPausedAndRearms() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        val coordinator = SpeechRecognitionCoordinator(
            context = context,
            scope = scope,
            mainDispatcher = testDispatcher,
            isContinuousModeEnabled = { true }
        )

        // When hands-free is active, losing window focus sets isTransientFocusLoss = true without setting isIntentionallyPaused
        coordinator.setWindowFocused(false)
        assertTrue(coordinator.isTransientFocusLoss)
        assertFalse(coordinator.isIntentionallyPaused)
        assertTrue(
            coordinator.traceHistory.value.any { it.contains("focused=false isTransientFocusLoss=true") }
        )

        // Simulate a stale background pause
        coordinator.setIntentionallyPaused(true)
        assertTrue(coordinator.isIntentionallyPaused)

        // Re-focus window while hands-free is enabled and not processing a command
        coordinator.setWindowFocused(true)
        testScheduler.advanceTimeBy(SpeechRecognitionCoordinator.WINDOW_FOCUS_DEBOUNCE_MS + 100L)

        assertFalse(coordinator.isTransientFocusLoss)
        assertFalse(
            "setWindowFocused(true) must force isIntentionallyPaused = false when hands-free is active and not processing",
            coordinator.isIntentionallyPaused
        )
        assertTrue(
            coordinator.traceHistory.value.any { it.contains("SET_WINDOW_FOCUSED_REARM") }
        )
    }

    @Test
    fun onErrorClient_whenIsTransientFocusLossTrue_returnsImmediatelyWithoutRecreatingOrQueueingRestart() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        val coordinator = SpeechRecognitionCoordinator(
            context = context,
            scope = scope,
            mainDispatcher = testDispatcher,
            isContinuousModeEnabled = { true }
        )

        // Queue a pending restart first, then lose window focus
        coordinator.scheduleDebouncedRestart(delayMs = 500L)
        assertTrue(coordinator.hasPendingRestart)

        coordinator.setWindowFocused(false)
        assertTrue(coordinator.isTransientFocusLoss)
        assertFalse(
            "Losing window focus must immediately cancel any pending restartJob",
            coordinator.hasPendingRestart
        )

        val initialRecreations = coordinator.recognizerRecreationCount

        // Fire 20 consecutive ERROR_CLIENT callbacks while isTransientFocusLoss == true
        repeat(20) {
            coordinator.recognitionListener.onError(SpeechRecognizer.ERROR_CLIENT)
        }

        assertEquals(
            "onError(ERROR_CLIENT) must NEVER recreate SpeechRecognizer while isTransientFocusLoss is true",
            initialRecreations,
            coordinator.recognizerRecreationCount
        )
        assertFalse(
            "onError(ERROR_CLIENT) must NEVER queue a restart while isTransientFocusLoss is true",
            coordinator.hasPendingRestart
        )

        // Also verify WakeWordManager hard-kills onError(ERROR_CLIENT) during transient focus loss
        val wakeManager = WakeWordManager(context)
        wakeManager.setWindowFocused(false)
        assertTrue(wakeManager.isTransientFocusLoss)
        wakeManager.onError(SpeechRecognizer.ERROR_CLIENT)
        assertTrue(wakeManager.isTransientFocusLoss)
        wakeManager.destroy()
    }

    @Test
    fun wakeWordDetection_stopsSpeechRecognitionCoordinatorAndReleasesExclusiveAudioFocusBeforeTts() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)

        val coordinator = viewModel.speechRecognitionCoordinator
        val wakeManager = viewModel.wakeWordManager

        // Trigger wake word with a spoken prompt routing to chat
        wakeManager.onKeywordHit("Gemini", initialQuery = "what is the weather today")

        assertFalse(
            "SpeechRecognitionCoordinator must NOT be listening after wake word handoff",
            coordinator.isListening.value
        )
        assertFalse(
            "SpeechRecognitionCoordinator must NOT have a pending restart during wake query processing",
            coordinator.hasPendingRestart
        )
        assertFalse(
            "WakeWordManager must release exclusive audio focus before Gemini / TTS begins",
            wakeManager.hasAudioFocus.value
        )
        assertEquals(0, wakeManager.activeAudioRecordCount)

        viewModel.cleanShutdown(null)
    }

    @Test
    fun audioFocusChangeListener_onLossTransient_setsFlagAndCancelsWithoutRestart_andOnGain_restartsAfter500ms() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)
        var startHardwareCalls = 0

        val coordinator = SpeechRecognitionCoordinator(
            context = context,
            scope = scope,
            mainDispatcher = testDispatcher,
            isContinuousModeEnabled = { true },
            onPrepareHardwareBeforeStart = { startHardwareCalls++ }
        )

        coordinator.recognitionListener.onReadyForSpeech(Bundle())
        assertTrue(coordinator.isListening.value)
        assertFalse(coordinator.isTransientAudioFocusLoss)

        // 1. AUDIOFOCUS_LOSS_TRANSIENT (-2): sets transient loss flag, cancels recognizer, and does NOT restart immediately
        coordinator.audioFocusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertTrue(
            "AUDIOFOCUS_LOSS_TRANSIENT (-2) must set isTransientAudioFocusLoss = true",
            coordinator.isTransientAudioFocusLoss
        )
        assertFalse(coordinator.isListening.value)
        assertFalse(
            "AUDIOFOCUS_LOSS_TRANSIENT (-2) must NOT queue an immediate restart",
            coordinator.hasPendingRestart
        )

        // Even if SpeechRecognizer emits an error after cancel(), it must NOT restart while isTransientAudioFocusLoss is true
        coordinator.recognitionListener.onError(SpeechRecognizer.ERROR_CLIENT)
        assertFalse(coordinator.hasPendingRestart)
        assertEquals(0, startHardwareCalls)

        // 2. AUDIOFOCUS_GAIN (1): checks transient loss flag, clears it, and schedules a 500ms debounced restart
        coordinator.audioFocusChangeListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertFalse(
            "AUDIOFOCUS_GAIN (1) must clear isTransientAudioFocusLoss",
            coordinator.isTransientAudioFocusLoss
        )
        assertTrue(
            "AUDIOFOCUS_GAIN (1) must schedule a 500ms debounced restart",
            coordinator.hasPendingRestart
        )
        // Before 500ms elapses, must not have restarted yet
        advanceTimeBy(300L)
        runCurrent()
        assertEquals(0, startHardwareCalls)

        // After 500ms elapses, restart fires
        advanceTimeBy(250L)
        runCurrent()
        assertTrue(
            coordinator.traceHistory.value.any { it.contains("AUDIOFOCUS_GAIN_REARM_FIRING") }
        )
    }

    @Test
    fun uiDictationMicButton_synchronouslyStopsAndReleasesWakeWordVoiceServiceAudioRecordBeforeStartListening() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val serviceController = org.robolectric.Robolectric.buildService(
            WakeWordVoiceService::class.java,
            android.content.Intent(app, WakeWordVoiceService::class.java)
        )
        val service = serviceController.create().startCommand(0, 1).get()
        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)

        try {
            val initialHandoffs = WakeWordVoiceService.synchronousDictationHandoffReleaseCount
            var servicePausedWhenRecognizerPrepared = false
            var serviceMicReleasedWhenRecognizerPrepared = false

            // Tap UI dictation mic button
            val started = viewModel.toggleInChatDictation()
            assertTrue(started)
            assertTrue(
                "Tapping UI mic button must synchronously invoke stopAndReleaseServiceAudioRecordSynchronously()",
                WakeWordVoiceService.synchronousDictationHandoffReleaseCount > initialHandoffs
            )
            servicePausedWhenRecognizerPrepared = WakeWordVoiceService.isIntentionallyPaused
            serviceMicReleasedWhenRecognizerPrepared = !WakeWordVoiceService.isMicrophoneInitialized.value
            assertTrue(
                "WakeWordVoiceService must be marked paused before SpeechRecognizer.startListening()",
                servicePausedWhenRecognizerPrepared
            )
            assertTrue(
                "WakeWordVoiceService AudioRecord must be synchronously released before SpeechRecognizer.startListening()",
                serviceMicReleasedWhenRecognizerPrepared
            )
        } finally {
            viewModel.cleanShutdown(null)
            serviceController.destroy()
        }
    }

    @Test
    fun featureTurnOffAndAppBackgrounded_callsSpeechRecognizerDestroyAndReleasesProcessingMutex() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)
        val coordinator = viewModel.speechRecognitionCoordinator

        // Enable Hands-Free and simulate an active processing mutex lock
        viewModel.resetHandsFreeToggleDebounceForTest()
        viewModel.toggleHandsFreeVoice(enabled = true)
        coordinator.setProcessingState(true)
        assertTrue(coordinator.isProcessing)
        val destroyCountBeforeOff = coordinator.destroyCount

        // 1. Turning feature OFF must call SpeechRecognizer.destroy() and release the Processing Mutex
        viewModel.resetHandsFreeToggleDebounceForTest()
        viewModel.toggleHandsFreeVoice(enabled = false)
        assertTrue(
            "Turning Hands-Free OFF must call SpeechRecognizer.destroy()",
            coordinator.destroyCount > destroyCountBeforeOff
        )
        assertFalse(
            "Turning Hands-Free OFF must release the Processing Mutex (isProcessing = false)",
            coordinator.isProcessing
        )
        assertFalse(coordinator.hasActiveRecognizerInstance)

        // 2. Backgrounding the app must also call SpeechRecognizer.destroy() and release the Processing Mutex
        viewModel.resetHandsFreeToggleDebounceForTest()
        viewModel.toggleHandsFreeVoice(enabled = true)
        coordinator.setProcessingState(true)
        assertTrue(coordinator.isProcessing)
        val destroyCountBeforeBg = coordinator.destroyCount

        viewModel.onAppBackgrounded()
        assertTrue(
            "Backgrounding the app must call SpeechRecognizer.destroy()",
            coordinator.destroyCount > destroyCountBeforeBg
        )
        assertFalse(
            "Backgrounding the app must release the Processing Mutex (isProcessing = false)",
            coordinator.isProcessing
        )
        assertFalse(coordinator.hasActiveRecognizerInstance)
        assertTrue(
            "Backgrounding must preserve isHandsFreeActive = true",
            viewModel.isHandsFreeActive.value
        )

        viewModel.cleanShutdown(null)
    }

    @Test
    fun ttsSpeak_synchronouslyReleasesWakeWordVoiceServiceAndRearmsAfter500msDebounce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertEquals(500L, TextToSpeechManager.ACOUSTIC_SETTLING_DELAY_MS)

        var settledCount = 0
        val ttsManager = TextToSpeechManager(
            context = app,
            scope = this,
            mainDispatcher = dispatcher,
            onPlaybackStarted = {
                WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("tts_speaking")
            },
            onPlaybackSettledAndReadyToListen = {
                settledCount++
            }
        )

        WakeWordVoiceService.setIntentionallyPaused(false)
        val releaseCountBefore = WakeWordVoiceService.synchronousDictationHandoffReleaseCount

        // 1. Yield Before Speech: speak() synchronously calls stopAndReleaseServiceAudioRecordSynchronously("tts_speaking")
        ttsManager.speak("Hello from Gemini chat greeting")
        assertTrue(
            "speak() must synchronously call stopAndReleaseServiceAudioRecordSynchronously(\"tts_speaking\")",
            WakeWordVoiceService.synchronousDictationHandoffReleaseCount > releaseCountBefore
        )
        assertEquals("tts_speaking", WakeWordVoiceService.lastSynchronousReleaseReason)
        assertTrue(WakeWordVoiceService.isIntentionallyPaused)
        assertFalse(WakeWordVoiceService.isMicrophoneInitialized.value)

        // 2. Simulate UtteranceProgressListener.onStart -> onDone and verify 500ms debounced delay
        ttsManager.utteranceProgressListener.onStart("utt_1")
        assertTrue(ttsManager.isSpeaking.value)
        WakeWordVoiceService.setIntentionallyPaused(true)

        ttsManager.utteranceProgressListener.onDone("utt_1")
        // At 300ms (< 500ms), must NOT have re-armed yet
        advanceTimeBy(300L)
        runCurrent()
        assertEquals(0, settledCount)
        assertTrue(WakeWordVoiceService.isIntentionallyPaused)

        // After 500ms total, WakeWordVoiceService is re-armed and onPlaybackSettledAndReadyToListen fires
        advanceTimeBy(250L)
        runCurrent()
        assertTrue(settledCount >= 1)
        assertFalse(
            "After 500ms onDone debounce, WakeWordVoiceService must be unpaused",
            WakeWordVoiceService.isIntentionallyPaused
        )

        // 3. Verify onStop also enforces the 500ms debounced re-arming
        val settledBeforeStop = settledCount
        ttsManager.utteranceProgressListener.onStart("utt_2")
        WakeWordVoiceService.setIntentionallyPaused(true)
        ttsManager.utteranceProgressListener.onStop("utt_2", true)
        advanceTimeBy(300L)
        runCurrent()
        assertEquals(settledBeforeStop, settledCount)
        advanceTimeBy(250L)
        runCurrent()
        assertEquals(settledBeforeStop + 1, settledCount)
        assertFalse(WakeWordVoiceService.isIntentionallyPaused)

        ttsManager.shutdown()
    }

    @Test
    fun wakeWordDetected_synchronouslyReleasesWakeWordVoiceServiceBeforeChatNavigationAndGuardsInit() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)

        try {
            WakeWordVoiceService.setIntentionallyPaused(false)
            val releaseCountBefore = WakeWordVoiceService.synchronousDictationHandoffReleaseCount

            // Detect wake word ("Hey Gemini") via SpeechRecognizer results
            val matched = viewModel.processSpeechRecognizerResults(
                candidates = listOf("Hey Gemini"),
                isFinal = true
            )
            assertTrue(matched)
            assertTrue(
                "Wake word detection must immediately invoke stopAndReleaseServiceAudioRecordSynchronously(\"wake_word_detected\")",
                WakeWordVoiceService.synchronousDictationHandoffReleaseCount > releaseCountBefore
            )
            assertEquals("wake_word_detected", WakeWordVoiceService.lastSynchronousReleaseReason)
            assertFalse(WakeWordVoiceService.isMicrophoneInitialized.value)

            // Verify that while TTS / processing mutex is active on ChatScreen load, non-user-initiated
            // startContinuousWakeWordListening(userInitiated = false) and onAppForegrounded() do not grab the mic
            viewModel.speechRecognitionCoordinator.setProcessingState(true)
            WakeWordVoiceService.setIntentionallyPaused(true)
            viewModel.startContinuousWakeWordListening(userInitiated = false)
            viewModel.onAppForegrounded()
            assertTrue(
                "Chat screen init/onResume must not unpause WakeWordVoiceService while handoff/processing mutex is active",
                WakeWordVoiceService.isIntentionallyPaused
            )
        } finally {
            viewModel.cleanShutdown(null)
        }
    }

    @Test
    fun errorInsufficientPermissions_whenOsRecordAudioPermissionGranted_isIgnoredAsFalsePositive() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var currentTime = 10_000L
        var permissionDeniedCallbackCount = 0

        val coordinator = SpeechRecognitionCoordinator(
            context = ApplicationProvider.getApplicationContext(),
            scope = this,
            mainDispatcher = dispatcher,
            clockMs = { currentTime },
            muteSystemChimeOnStart = false,
            hasRecordAudioPermission = { true },
            isContinuousModeEnabled = { true },
            onInsufficientPermissions = { permissionDeniedCallbackCount++ }
        )

        coordinator.recognitionListener.onError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertEquals(
            "When OS RECORD_AUDIO permission is granted, false-positive ERROR_INSUFFICIENT_PERMISSIONS(9) must be ignored",
            0,
            permissionDeniedCallbackCount
        )
        assertFalse(
            "False-positive ERROR_INSUFFICIENT_PERMISSIONS(9) must not set isIntentionallyPaused = true",
            coordinator.isIntentionallyPaused
        )
    }

    @Test
    fun v10_3_foregroundMicrophoneServiceType_singleTopManifest_andChatLaunchIntentFlags() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()

        // 1. Verify AndroidManifest.xml declarations
        val manifestFile = listOf(
            java.io.File("src/main/AndroidManifest.xml"),
            java.io.File("app/src/main/AndroidManifest.xml")
        ).firstOrNull { it.exists() }
        org.junit.Assert.assertNotNull("AndroidManifest.xml must exist", manifestFile)
        val manifestXml = manifestFile!!.readText()
        assertTrue(
            "AndroidManifest.xml must declare FOREGROUND_SERVICE_MICROPHONE permission",
            manifestXml.contains("android.permission.FOREGROUND_SERVICE_MICROPHONE")
        )
        assertTrue(
            "WakeWordVoiceService must declare android:foregroundServiceType=\"microphone\"",
            manifestXml.contains("android:foregroundServiceType=\"microphone\"")
        )
        assertTrue(
            "MainActivity must declare android:launchMode=\"singleTop\"",
            manifestXml.contains("android:launchMode=\"singleTop\"")
        )

        // 2. Verify WakeWordVoiceService startForeground() passes ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val serviceController = org.robolectric.Robolectric.buildService(
            WakeWordVoiceService::class.java,
            android.content.Intent(app, WakeWordVoiceService::class.java).apply {
                action = WakeWordVoiceService.ACTION_START_VOICE_SERVICE
            }
        )
        val service = serviceController.create().startCommand(0, 1).get()
        try {
            val shadowService = org.robolectric.Shadows.shadowOf(service)
            assertEquals(WakeWordVoiceService.NOTIFICATION_ID, shadowService.lastForegroundNotificationId)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                assertEquals(
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                    service.foregroundServiceType
                )
            }

            // 3. Verify Chat launch Intent includes FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_REORDER_TO_FRONT
            val detection = com.example.voice.WakeWordDetectionResult(
                wakeWord = com.example.data.model.WakeWord(
                    phrase = "Hey Gemini",
                    triggerType = com.example.data.model.TriggerType.CHAT,
                    chatId = 42L,
                    chatTitle = "Samsung Voice Chat"
                ),
                matchedPhrase = "Hey Gemini",
                followUpQuery = null,
                rawTranscript = "Hey Gemini"
            )
            val launchIntent = WakeWordVoiceService.createChatLaunchIntent(app, detection)
            val expectedFlags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            assertEquals(
                "Chat launch intent must include FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_REORDER_TO_FRONT",
                expectedFlags,
                launchIntent.flags and expectedFlags
            )
            assertEquals(42L, launchIntent.getLongExtra(WakeWordVoiceService.EXTRA_WAKE_TARGET_CHAT_ID, -1L))
        } finally {
            serviceController.destroy()
        }
    }

    @Test
    fun v10_4_samsungAndroid14_noSilentMinimizeOnForegroundSpeechRecognizerActivation() = runTest {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)

        // 1. Verify AndroidManifest.xml <queries> visibility for Android 14 / Samsung One UI speech services
        val manifestFile = listOf(
            java.io.File("src/main/AndroidManifest.xml"),
            java.io.File("app/src/main/AndroidManifest.xml")
        ).firstOrNull { it.exists() }
        org.junit.Assert.assertNotNull("AndroidManifest.xml must exist", manifestFile)
        val manifestXml = manifestFile!!.readText()
        assertTrue(manifestXml.contains("android.speech.RecognitionService"))
        assertTrue(manifestXml.contains("android.speech.action.RECOGNIZE_SPEECH"))
        assertTrue(manifestXml.contains("com.google.android.tts"))
        assertTrue(manifestXml.contains("com.samsung.android.bixby.agent"))

        // 2. Verify foreground SpeechRecognizer intent strips FLAG_ACTIVITY_NEW_TASK and task flags
        val dirtyIntent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
        }
        val sanitizedRecognizerIntent = WakeWordManager.configureRecognizerIntent(dirtyIntent, app.packageName)
        assertEquals(
            "Foreground RecognizerIntent must NEVER carry FLAG_ACTIVITY_NEW_TASK",
            0,
            sanitizedRecognizerIntent.flags and android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        )

        // 3. Verify WakeWordVoiceService does NOT fire startActivity(FLAG_ACTIVITY_NEW_TASK) when MainActivity is already in foreground
        val serviceController = org.robolectric.Robolectric.buildService(WakeWordVoiceService::class.java)
        val service = serviceController.create().get()
        try {
            service.setActiveWakeWordsForTesting(
                listOf(
                    com.example.data.model.WakeWord(
                        phrase = "Hey Gemini",
                        triggerType = com.example.data.model.TriggerType.CHAT,
                        chatId = 77L,
                        chatTitle = "Note 20 Chat"
                    )
                )
            )
            while (shadowApp.nextStartedActivity != null) {
                // drain any prior test intents
            }
            WakeWordVoiceService.setIntentionallyPaused(false)
            WakeWordVoiceService.setSleepMode(false)
            WakeWordVoiceService.isMainActivityInForeground = true
            val fgMatch = service.checkTranscriptAgainstDatabase("Hey Gemini")
            org.junit.Assert.assertNotNull("Wake word should match in foreground", fgMatch)
            assertEquals(
                "When MainActivity is already in the foreground, WakeWordVoiceService must NOT fire background startActivity(FLAG_ACTIVITY_NEW_TASK)",
                null,
                shadowApp.nextStartedActivity
            )

            // When app is backgrounded, WakeWordVoiceService MUST fire startActivity(FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_REORDER_TO_FRONT)
            WakeWordVoiceService.setIntentionallyPaused(false)
            WakeWordVoiceService.setSleepMode(false)
            WakeWordVoiceService.isMainActivityInForeground = false
            val bgMatch = service.checkTranscriptAgainstDatabase("Hey Gemini")
            org.junit.Assert.assertNotNull("Wake word should match in background", bgMatch)
            val startedFromBg = shadowApp.nextStartedActivity
            org.junit.Assert.assertNotNull(
                "When MainActivity is in the background, WakeWordVoiceService MUST launch MainActivity",
                startedFromBg
            )
            val expectedFlags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            assertEquals(expectedFlags, startedFromBg!!.flags and expectedFlags)
        } finally {
            WakeWordVoiceService.isMainActivityInForeground = true
            serviceController.destroy()
        }
    }

    @Test
    fun srTraceLogging_debounceWindow_audioFocusResponses_intentSilenceExtras_andServiceHandoffNullification() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val serviceController = org.robolectric.Robolectric.buildService(
            WakeWordVoiceService::class.java,
            android.content.Intent(app, WakeWordVoiceService::class.java)
        )
        val service = serviceController.create().get()
        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)
        viewModel.onWindowFocusChanged(true)

        try {
            viewModel.clearDiagnosticTraceLogs()

            // 1. Trigger manual dictation -> logs SERVICE_AUDIORECORD_NULLIFIED and UI_MIC_DEBOUNCE_START immediately
            val started = viewModel.toggleInternalDictation()
            assertTrue(started)

            val tracesBeforeDelay = viewModel.speechTraceHistory.value
            assertTrue(
                "Expected stage=UI_MIC_DEBOUNCE_START in [SR-Trace] before 300ms delay completes",
                tracesBeforeDelay.any { it.contains("stage=UI_MIC_DEBOUNCE_START") && it.contains("delayMs=300") }
            )
            assertTrue(
                "Expected stage=SERVICE_AUDIORECORD_NULLIFIED with exact millisecond in [SR-Trace]",
                tracesBeforeDelay.any { it.contains("stage=SERVICE_AUDIORECORD_NULLIFIED") && it.contains("serviceAudioRecord=null atEpochMs=") }
            )
            assertTrue(
                "lastAudioRecordNullifiedAtEpochMs must be > 0",
                WakeWordVoiceService.lastAudioRecordNullifiedAtEpochMs > 0L
            )

            // Advance the Robolectric paused main looper clock by 350ms so delay(300L) completes
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofMillis(350))

            val tracesAfterDelay = viewModel.speechTraceHistory.value
            assertTrue(
                "Expected stage=UI_MIC_DEBOUNCE_END in [SR-Trace] after 300ms delay",
                tracesAfterDelay.any { it.contains("stage=UI_MIC_DEBOUNCE_END") && it.contains("elapsedMs=") }
            )

            // 2. Verify AudioFocus request, abandon, and callback logs include exact OS response names
            service.requestExclusiveAudioFocus()
            service.abandonExclusiveAudioFocus()
            viewModel.speechRecognitionCoordinator.handleAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            viewModel.speechRecognitionCoordinator.handleAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN)

            val focusTraces = viewModel.speechTraceHistory.value
            assertTrue(
                "Expected SERVICE_AUDIOFOCUS_REQUEST with AUDIOFOCUS_REQUEST_GRANTED or FAILED in [SR-Trace]",
                focusTraces.any {
                    it.contains("stage=SERVICE_AUDIOFOCUS_REQUEST") &&
                        (it.contains("AUDIOFOCUS_REQUEST_GRANTED") || it.contains("AUDIOFOCUS_REQUEST_FAILED"))
                }
            )
            assertTrue(
                "Expected SERVICE_AUDIOFOCUS_ABANDON in [SR-Trace]",
                focusTraces.any { it.contains("stage=SERVICE_AUDIOFOCUS_ABANDON") }
            )
            assertTrue(
                "Expected AUDIOFOCUS_YIELD with response=AUDIOFOCUS_LOSS_TRANSIENT(-2) in [SR-Trace]",
                focusTraces.any {
                    it.contains("stage=AUDIOFOCUS_YIELD") &&
                        it.contains("response=AUDIOFOCUS_LOSS_TRANSIENT(-2)")
                }
            )

            // 3. Verify configureRecognizerIntent dumps exact 3000ms EOU silence extras to [SR-Trace]
            WakeWordManager.configureRecognizerIntent(
                android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH),
                app.packageName
            )
            val intentTraces = viewModel.speechTraceHistory.value
            assertTrue(
                "Expected stage=RECOGNIZER_INTENT_CONFIGURED with 3000ms silence values in [SR-Trace]",
                intentTraces.any {
                    it.contains("stage=RECOGNIZER_INTENT_CONFIGURED") &&
                        it.contains("EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS=3000ms") &&
                        it.contains("EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS=3000ms")
                }
            )
        } finally {
            viewModel.cleanShutdown(null)
            serviceController.destroy()
        }
    }
}
