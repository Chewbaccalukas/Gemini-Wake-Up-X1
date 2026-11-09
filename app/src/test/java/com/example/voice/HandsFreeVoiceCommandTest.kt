package com.example.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HandsFreeVoiceCommandTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.Shadows.shadowOf(context as android.app.Application)
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun testDictateTriggerDetectionAndExtraction() {
        val test1 = "Dictate what is quantum computing?"
        assertTrue(AudioCommandSanitizer.containsDictateTrigger(test1, "Dictate"))
        val extracted1 = AudioCommandSanitizer.extractContentAfterDictate(test1, "Dictate")
        assertEquals("what is quantum computing?", extracted1)

        val test2 = "Take note organize task list for tomorrow"
        assertTrue(AudioCommandSanitizer.containsDictateTrigger(test2, "Dictate"))
        val extracted2 = AudioCommandSanitizer.extractContentAfterDictate(test2, "Dictate")
        assertEquals("organize task list for tomorrow", extracted2)

        val test3 = "Start dictation summarize meeting notes"
        assertTrue(AudioCommandSanitizer.containsDictateTrigger(test3, "Dictate"))
        val extracted3 = AudioCommandSanitizer.extractContentAfterDictate(test3, "Dictate")
        assertEquals("summarize meeting notes", extracted3)

        // Custom trigger
        val testCustom = "Record query what is the speed of sound?"
        assertTrue(AudioCommandSanitizer.containsDictateTrigger(testCustom, "Record query"))
        val extractedCustom = AudioCommandSanitizer.extractContentAfterDictate(testCustom, "Record query")
        assertEquals("what is the speed of sound?", extractedCustom)
    }

    @Test
    fun testSendTriggerDetection() {
        assertTrue(AudioCommandSanitizer.containsSendTrigger("Please compile this code send", "Send"))
        assertTrue(AudioCommandSanitizer.containsSendTrigger("How does photosynthesis work? Over", "Send"))
        assertTrue(AudioCommandSanitizer.containsSendTrigger("Send message", "Send"))
        assertTrue(AudioCommandSanitizer.containsSendTrigger("Dispatch this query", "Send"))
        assertTrue(AudioCommandSanitizer.containsSendTrigger("Transmit now", "Transmit"))

        assertFalse(AudioCommandSanitizer.containsSendTrigger("This is a regular question without trigger", "Send"))
    }

    @Test
    fun testHeartbeatTriggerDetection() {
        assertTrue(AudioCommandSanitizer.containsHeartbeatTrigger("heartbeat", "Heartbeat"))
        assertTrue(AudioCommandSanitizer.containsHeartbeatTrigger("Pulse", "Heartbeat"))
        assertTrue(AudioCommandSanitizer.containsHeartbeatTrigger("Ping", "Heartbeat"))
        assertTrue(AudioCommandSanitizer.containsHeartbeatTrigger("Heartbeat ping", "Heartbeat"))
        assertTrue(AudioCommandSanitizer.containsHeartbeatTrigger("Keep alive check", "Keep alive"))

        assertFalse(AudioCommandSanitizer.containsHeartbeatTrigger("Hello Gemini how are you?", "Heartbeat"))
    }

    @Test
    fun testAudioCommandSanitization_RemovesTrailingAndLeadingCommands() {
        // Trailing send trigger should be scrubbed
        val prompt1 = "Explain the difference between TCP and UDP send"
        val scrubbed1 = AudioCommandSanitizer.sanitizeSpokenText(prompt1, sendTrigger = "Send", heartbeatTrigger = "Heartbeat")
        assertEquals("Explain the difference between TCP and UDP", scrubbed1)

        // Trailing over trigger should be scrubbed
        val prompt2 = "Review this Kotlin function over"
        val scrubbed2 = AudioCommandSanitizer.sanitizeSpokenText(prompt2, sendTrigger = "Send", heartbeatTrigger = "Heartbeat")
        assertEquals("Review this Kotlin function", scrubbed2)

        // Trailing heartbeat trigger should be scrubbed
        val prompt3 = "Checking system state heartbeat"
        val scrubbed3 = AudioCommandSanitizer.sanitizeSpokenText(prompt3, sendTrigger = "Send", heartbeatTrigger = "Heartbeat")
        assertEquals("Checking system state", scrubbed3)

        // Trailing custom trigger
        val promptCustom = "Generate a unit test for Room database dispatch"
        val scrubbedCustom = AudioCommandSanitizer.sanitizeSpokenText(promptCustom, sendTrigger = "dispatch", heartbeatTrigger = "Heartbeat")
        assertEquals("Generate a unit test for Room database", scrubbedCustom)

        // Internal words shouldn't be damaged unless they are isolated command triggers
        val promptClean = "Send me a recipe for chocolate cake"
        // Leading trigger "Send" alone as command scrubbed, remaining prompt preserved
        val scrubbedClean = AudioCommandSanitizer.sanitizeSpokenText(promptClean, sendTrigger = "Send", heartbeatTrigger = "Heartbeat")
        assertEquals("me a recipe for chocolate cake", scrubbedClean)
    }

    @Test
    fun testPlayHeartbeatConfirmationDoesNotCrash() {
        // Runs without throwing exception under Robolectric JVM environment
        AudioCommandSanitizer.playHeartbeatConfirmation(context)
    }

    @Test
    fun testHandsFreeVoiceStateValues() {
        assertEquals("Idle", HandsFreeVoiceState.IDLE.displayName)
        assertEquals("Listening", HandsFreeVoiceState.LISTENING.displayName)
        assertEquals("Transcribing", HandsFreeVoiceState.TRANSCRIBING.displayName)
        assertEquals("Heartbeat Ping", HandsFreeVoiceState.HEARTBEAT_PING.displayName)
    }

    @Test
    fun testWakeWordManagerSilenceDetection_3dbThresholdAnd1200msCutoff() {
        val manager = WakeWordManager(context)
        manager.transitionToActiveStreaming()
        var speechStartedCount = 0
        var silenceCutoffCount = 0

        manager.onSpeechStarted = { speechStartedCount++ }
        manager.onSilenceCutoffDetected = { silenceCutoffCount++ }

        assertEquals(0.0f, WakeWordManager.SPEECH_DB_THRESHOLD, 0.001f)
        assertEquals(1200L, WakeWordManager.SILENCE_CUTOFF_MS)

        // Pure silence (0.0f dB <= 0.0f) should NOT start speech
        assertFalse(manager.processRmsFrame(0.0f, nowMs = 1000L))
        assertEquals(0, speechStartedCount)
        assertEquals(0, silenceCutoffCount)

        // Above-threshold speech (> 0 dB, e.g. 0.5f or 3.5f dB) starts speech without dropping low-volume human speech
        assertFalse(manager.processRmsFrame(0.5f, nowMs = 1100L))
        assertEquals(1, speechStartedCount)
        assertEquals(0, silenceCutoffCount)

        // Drop to silence (0.0f dB) at t=2000ms
        assertFalse(manager.processRmsFrame(0.0f, nowMs = 2000L))
        // Still silent at t=2800ms (800ms elapsed < 1200ms) -> should NOT trigger yet
        assertFalse(manager.processRmsFrame(0.0f, nowMs = 2800L))
        assertEquals(0, silenceCutoffCount)

        // Continuous silence reaches 1200ms at t=3200ms -> triggers onSilenceCutoffDetected & transitions to passive listening
        assertTrue(manager.processRmsFrame(0.0f, nowMs = 3200L))
        assertEquals(1, silenceCutoffCount)
        assertEquals(HandsFreeVoiceState.LISTENING, manager.handsFreeState.value)

        manager.destroy()
    }

    @Test
    fun testWakeWordManagerPassiveListening_silenceCutoffDecoupled() {
        val manager = WakeWordManager(context)
        manager.transitionToPassiveListening()
        var silenceCutoffCount = 0
        manager.onSilenceCutoffDetected = { silenceCutoffCount++ }

        // In PASSIVE_LISTENING / Standby, low RMS and prolonged silence should NEVER trigger silence cutoff
        assertFalse(manager.processRmsFrame(0.1f, nowMs = 1000L))
        assertFalse(manager.processRmsFrame(0.2f, nowMs = 3000L))
        assertFalse(manager.processRmsFrame(0.1f, nowMs = 6000L))
        assertEquals(0, silenceCutoffCount)
        assertEquals(HandsFreeVoiceState.LISTENING, manager.handsFreeState.value)

        // Keyword hit transitions to active streaming
        manager.onKeywordHit("jimmy")
        assertEquals(HandsFreeVoiceState.TRANSCRIBING, manager.handsFreeState.value)

        manager.destroy()
    }

    @Test
    fun testMicrophoneMutexAndWakePreRollAudioHandoffToChatSession() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        val manager = viewModel.wakeWordManager

        // Simulate passive wake-word listening with non-silent wake speech frames in pre-roll buffer
        manager.setHandsFreeMode(true)
        assertTrue(manager.activeAudioRecordCount <= 1)

        val wakePcmSample = ByteArray(640) { idx -> if (idx % 2 == 0) 0x24 else 0x08 }
        manager.appendPreRollPcm(wakePcmSample, wakePcmSample.size)

        val handoffBefore = manager.wakeHandoffCount
        manager.onKeywordHit("Gemini", initialQuery = null)

        // Verify wake AudioRecord was released before chat-turn stream initialized (single-instance mutex)
        assertEquals(handoffBefore + 1, manager.wakeHandoffCount)
        assertTrue(manager.activeAudioRecordCount <= 1)
        assertEquals(WakeWordManagerState.ACTIVE_STREAMING, manager.engineState.value)
        assertEquals(HandsFreeVoiceState.TRANSCRIBING, manager.handsFreeState.value)

        // Verify captured pre-roll wake audio was forwarded directly into MainViewModel's active chat turn buffer
        val bufferedTurnBytes = viewModel.getBufferedVoiceTurnBytes()
        assertTrue(bufferedTurnBytes.isNotEmpty())
        assertEquals(wakePcmSample.size, bufferedTurnBytes.size)

        // Cancelling or completing the voice turn releases the turn AudioRecord and re-arms passive wake listening
        viewModel.cancelVoiceRecording()
        assertTrue(manager.activeAudioRecordCount <= 1)
        assertEquals(WakeWordManagerState.PASSIVE_LISTENING, manager.engineState.value)
        assertEquals(HandsFreeVoiceState.LISTENING, manager.handsFreeState.value)

        manager.destroy()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun testAudioFocusGainTransientExclusiveAndGboardTextFocusCompatibility() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        val manager = viewModel.wakeWordManager

        // Verify AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE configuration
        assertEquals(
            android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            manager.audioFocusGainMode
        )
        assertEquals(
            android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            WakeWordVoiceService.AUDIO_FOCUS_GAIN_MODE
        )

        manager.setHandsFreeMode(true)

        // When an on-screen text input gains focus (e.g. user taps chat box or Gboard opens),
        // background AudioRecord must immediately pause and release
        viewModel.onTextInputFocusChanged(true)
        assertTrue(viewModel.isTextInputFocused.value)
        assertFalse(manager.isListening.value)
        assertEquals(0, manager.activeAudioRecordCount)

        // When text focus clears, hands-free mode automatically resumes passive wake listening
        viewModel.onTextInputFocusChanged(false)
        assertFalse(viewModel.isTextInputFocused.value)
        assertEquals(WakeWordManagerState.PASSIVE_LISTENING, manager.engineState.value)

        // When external package (such as Gboard voice typing) requests microphone capture or audio focus
        manager.handleExternalMicContention(true)
        assertTrue(manager.isExternalMicActive.value)
        assertFalse(manager.isListening.value)
        assertEquals(0, manager.activeAudioRecordCount)

        manager.handleExternalMicContention(false)
        assertFalse(manager.isExternalMicActive.value)

        // AudioFocus loss and gain cycle
        manager.handleAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(manager.hasAudioFocus.value)
        assertFalse(manager.isListening.value)
        assertEquals(0, manager.activeAudioRecordCount)

        manager.handleAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(manager.hasAudioFocus.value)

        manager.destroy()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun testSystemAndCommandStatusStringsBlockedFromAudioPlayback() {
        assertTrue(AudioCommandSanitizer.isSystemOrCommandStatusString("Listening"))
        assertTrue(AudioCommandSanitizer.isSystemOrCommandStatusString("Processing"))
        assertTrue(AudioCommandSanitizer.isSystemOrCommandStatusString("Heartbeat"))
        assertTrue(AudioCommandSanitizer.isSystemOrCommandStatusString("Transcribing"))
        assertTrue(AudioCommandSanitizer.isSystemOrCommandStatusString("Idle"))
        assertFalse(AudioCommandSanitizer.isSystemOrCommandStatusString("Here is the analysis of your Kotlin code."))

        val player = StreamingVoicePlayer()
        player.feedBase64Chunk("Listening")
        assertFalse(player.isPlaying.value)
        player.feedBase64Chunk("Processing")
        assertFalse(player.isPlaying.value)
        player.feedBase64Chunk("Heartbeat")
        assertFalse(player.isPlaying.value)
        player.release()
    }

    @Test
    fun testSelfInitiatedAudioRecordingCallbackIgnored_NoMicCyclingLoop() {
        val manager = WakeWordManager(context)
        val myUid = android.os.Process.myUid()
        val ownSessionId = 7742
        WakeWordVoiceService.registerOwnAudioSessionId(ownSessionId)

        // 1. Config matching our own app UID (Process.myUid()) MUST be recognized as own capture and ignored
        assertTrue(
            WakeWordVoiceService.isOwnRecordingConfig(
                clientUid = myUid,
                clientAudioSessionId = 9999,
                currentAudioSessionId = ownSessionId
            )
        )

        // 2. Config matching our AudioRecord.audioSessionId MUST be recognized as own capture and ignored
        assertTrue(
            WakeWordVoiceService.isOwnRecordingConfig(
                clientUid = null,
                clientAudioSessionId = ownSessionId,
                currentAudioSessionId = ownSessionId
            )
        )

        // 3. Config from a different UID and different session ID is recognized as external
        assertFalse(
            WakeWordVoiceService.isOwnRecordingConfig(
                clientUid = myUid + 1000,
                clientAudioSessionId = 8888,
                currentAudioSessionId = ownSessionId
            )
        )

        // 4. Empty or self-initiated callback invocation does not flip isExternalMicActive or cycle the mic
        manager.handleRecordingConfigsChanged(emptyList())
        assertFalse(manager.isExternalMicActive.value)

        manager.destroy()
    }

    @Test
    fun testIsTransitioningAudioStateGuardAndIsUserVoiceTurnSingleActiveState() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        val manager = viewModel.wakeWordManager

        manager.setHandsFreeMode(true)
        assertFalse(viewModel.isUserVoiceTurn.value)

        // 1. Manual mic button tap sets isUserVoiceTurn = true
        viewModel.startVoiceRecording()
        assertTrue(viewModel.isUserVoiceTurn.value)
        assertTrue(manager.isUserVoiceTurn.value)
        assertEquals(WakeWordManagerState.ACTIVE_STREAMING, manager.engineState.value)

        // While isUserVoiceTurn == true, passive wake listener transitions/restarts are suppressed
        manager.transitionToPassiveListening()
        assertEquals(WakeWordManagerState.ACTIVE_STREAMING, manager.engineState.value)
        manager.restartWakeListeningAfterTurn()
        assertEquals(WakeWordManagerState.ACTIVE_STREAMING, manager.engineState.value)

        // Cancelling the voice turn resets isUserVoiceTurn = false and resumes passive wake mode
        viewModel.cancelVoiceRecording()
        assertFalse(viewModel.isUserVoiceTurn.value)
        assertFalse(manager.isUserVoiceTurn.value)
        assertEquals(WakeWordManagerState.PASSIVE_LISTENING, manager.engineState.value)

        // 2. Hands-free keyword hit also sets isUserVoiceTurn = true
        manager.onKeywordHit("Gemini", initialQuery = null)
        assertTrue(viewModel.isUserVoiceTurn.value)
        assertEquals(WakeWordManagerState.ACTIVE_STREAMING, manager.engineState.value)

        // Completing the turn resets isUserVoiceTurn = false
        manager.completeChatTurnAndReleaseMic()
        assertFalse(viewModel.isUserVoiceTurn.value)

        // 3. Verify isTransitioningAudioState re-entrancy guard blocks re-entrant contention/focus triggers
        manager.setTransitioningAudioStateForTest(true)
        assertTrue(manager.isTransitioningAudioState)
        manager.handleExternalMicContention(true)
        assertFalse(manager.isExternalMicActive.value)
        manager.setTransitioningAudioStateForTest(false)
        assertFalse(manager.isTransitioningAudioState)

        manager.destroy()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun testMainActivityManifestWindowSoftInputModeAdjustResize() {
        val pm = context.packageManager
        val componentName = android.content.ComponentName(context, com.example.MainActivity::class.java)
        val activityInfo = pm.getActivityInfo(componentName, 0)
        @Suppress("DEPRECATION")
        val adjustResizeMask = android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        assertTrue(
            "MainActivity must specify android:windowSoftInputMode=\"adjustResize\"",
            (activityInfo.softInputMode and adjustResizeMask) == adjustResizeMask
        )
    }

    @Test
    fun testAudioSourceNormalizationAndZeroAmplitudeCycleToMic() {
        val manager = WakeWordManager(context)

        // 1. Default AudioSource is MediaRecorder.AudioSource.MIC (with UNPROCESSED and VOICE_RECOGNITION fallbacks)
        assertEquals(android.media.MediaRecorder.AudioSource.MIC, WakeWordManager.AUDIO_SOURCE)
        assertEquals(android.media.MediaRecorder.AudioSource.UNPROCESSED, WakeWordManager.UNPROCESSED_AUDIO_SOURCE)
        assertEquals(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeWordManager.FALLBACK_AUDIO_SOURCE)
        assertEquals(android.media.MediaRecorder.AudioSource.MIC, manager.audioSource)

        // 2. Simulate Samsung One UI zeroed buffer on VOICE_RECOGNITION: inspectAndLogAmplitude must warn & cycle to MIC
        manager.setAudioSourceForTest(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION)
        assertEquals(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, manager.audioSource)

        val zeroShorts = ShortArray(320) { 0 }
        val warningsBefore = manager.zeroAmplitudeWarningCount
        val zeroRms = manager.inspectAndLogAmplitude(zeroShorts, zeroShorts.size)
        assertEquals(0f, zeroRms, 0.0001f)
        assertEquals(warningsBefore + 1, manager.zeroAmplitudeWarningCount)
        assertEquals(android.media.MediaRecorder.AudioSource.MIC, manager.audioSource)

        // 3. Non-zero PCM buffer produces > 0 RMS and stays on MIC
        val speechShorts = ShortArray(320) { idx -> if (idx % 2 == 0) 900 else -900 }
        val activeRms = manager.inspectAndLogAmplitude(speechShorts, speechShorts.size)
        assertTrue(activeRms > 0f)
        assertEquals(android.media.MediaRecorder.AudioSource.MIC, manager.audioSource)

        manager.destroy()
    }

    @Test
    fun testWakeSnippetAndTransitionRingBufferRecordingAndPlayback() {
        val manager = WakeWordManager(context)
        manager.clearCapturedSnippetPcm()
        assertTrue(manager.getCapturedSnippetPcm().isEmpty())

        // 1. Incoming PCM bytes in pre-roll or snippet capture populate the local test buffer
        val samplePcm = ByteArray(1600) { idx -> if (idx % 2 == 0) 0x34 else 0x12 }
        manager.appendPreRollPcm(samplePcm, samplePcm.size)

        val capturedPcm = manager.getCapturedSnippetPcm()
        assertEquals(samplePcm.size, capturedPcm.size)
        assertTrue(samplePcm.contentEquals(capturedPcm))

        val capturedWav = manager.getCapturedSnippetWav()
        assertEquals(44 + samplePcm.size, capturedWav.size)
        assertEquals('R'.code.toByte(), capturedWav[0])
        assertEquals('I'.code.toByte(), capturedWav[1])
        assertEquals('F'.code.toByte(), capturedWav[2])
        assertEquals('F'.code.toByte(), capturedWav[3])

        // 2. SnippetAudioPlayer plays both WAV and raw PCM buffers via AudioTrack and extracts exact PCM payload
        val snippetPlayer = SnippetAudioPlayer()
        val tempWavFile = java.io.File(context.cacheDir, "wake_test_1_0.wav")
        tempWavFile.writeBytes(capturedWav)

        snippetPlayer.play(tempWavFile)
        assertEquals(samplePcm.size, snippetPlayer.lastPlayedPcmBytes.size)
        assertTrue(samplePcm.contentEquals(snippetPlayer.lastPlayedPcmBytes))
        assertTrue(snippetPlayer.playbackDurationMs.value > 0)

        snippetPlayer.stop()
        tempWavFile.delete()
        snippetPlayer.release()
        manager.destroy()
    }

    @Test
    fun testTrueLiveDuplexStreamingModeContinuesWithoutWalkieTalkieDrop() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        val manager = viewModel.wakeWordManager

        // Enabling Hands-Free enables persistent Live Duplex mode
        viewModel.toggleHandsFreeMode(true)
        assertTrue(viewModel.isHandsFreeActive.value)
        assertTrue(viewModel.isLiveDuplexMode.value)
        assertTrue(manager.isLiveDuplexMode.value)

        // Simulate user voice turn followed by turn completion in Live Duplex mode
        manager.onKeywordHit("Gemini", initialQuery = null)
        assertTrue(manager.isUserVoiceTurn.value)
        assertEquals(WakeWordManagerState.ACTIVE_STREAMING, manager.engineState.value)

        // Completing the turn in Live Duplex mode keeps the session in continuous listening state (never drops to IDLE)
        manager.completeTurnKeepLiveDuplexMic()
        assertFalse(manager.isUserVoiceTurn.value)
        assertEquals(WakeWordManagerState.PASSIVE_LISTENING, manager.engineState.value)
        assertEquals(HandsFreeVoiceState.LISTENING, manager.handsFreeState.value)

        manager.destroy()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun testWakeWordTriggerIsolation_NeverInjectsOpenIntoPromptOrLastHeardText() {
        // 1. Standalone wake words ("open", "gemini", custom wake word) scrub to empty string
        assertEquals("", AudioCommandSanitizer.stripWakeWordTrigger("open"))
        assertEquals("", AudioCommandSanitizer.stripWakeWordTrigger("Open!"))
        assertEquals("", AudioCommandSanitizer.stripWakeWordTrigger("Hey Gemini"))
        assertEquals("", AudioCommandSanitizer.stripWakeWordTrigger("Code Mode", listOf("Code Mode")))
        assertTrue(AudioCommandSanitizer.isPureWakeWordTrigger("open"))
        assertTrue(AudioCommandSanitizer.isSystemOrCommandStatusString("open"))

        // 2. Wake word followed by spoken query strips only the wake word prefix
        assertEquals(
            "what is the weather today",
            AudioCommandSanitizer.stripWakeWordTrigger("open what is the weather today")
        )
        assertEquals(
            "explain recursion",
            AudioCommandSanitizer.stripWakeWordTrigger("Code Mode explain recursion", listOf("Code Mode"))
        )

        // 3. WakeWordManager.onKeywordHit("open") never populates lastHeardText with "open"
        val manager = WakeWordManager(context)
        manager.onKeywordHit("open", initialQuery = "open")
        assertEquals("", manager.lastHeardText.value)

        manager.onKeywordHit("open", initialQuery = "open tell me a joke")
        assertEquals("tell me a joke", manager.lastHeardText.value)

        manager.clearLastHeardText()
        assertEquals("", manager.lastHeardText.value)
        manager.destroy()
    }

    @Test
    fun testCorruptedHistoryRecovery_HandlesMalformedJsonWithoutCrashing() {
        val prefs = context.getSharedPreferences("web_link_history_prefs", Context.MODE_PRIVATE)
        // Inject corrupted JSON and duplicate/malformed entries into SharedPreferences
        prefs.edit()
            .putString("web_link_history_entries_v1", "{corrupted_json_not_an_array")
            .putString("voice_command_history_entries_v1", "[{\"id\":\"\",\"recognizedTranscript\":\"\"}, {\"corrupt\":true}]")
            .commit()

        // Initializing WebLinkHistoryManager must not crash
        val historyManager = com.example.util.WebLinkHistoryManager(context)
        assertTrue(historyManager.historyEntries.value.isEmpty())

        // Repairing corrupted history purges invalid keys and keeps app functional
        val repairedCount = historyManager.repairOrClearCorruptedHistory()
        assertTrue(repairedCount >= 0)

        val validEntry = historyManager.recordVoiceCommand(
            commandType = com.example.util.VoiceCommandType.VOICE_TURN,
            triggerPhrase = "Gemini",
            recognizedTranscript = "What is Kotlin Multiplatform?",
            chatTitle = "Android Dev"
        )
        assertTrue(validEntry != null)
        assertEquals(1, historyManager.voiceCommandEntries.value.size)
    }

    @Test
    fun testCustomAudioWakeWordAcousticTemplateMatchingAndSensitivity() {
        val manager = WakeWordManager(context)
        val chat = com.example.data.model.ChatSession(
            id = 42L,
            title = "Voice Lab",
            wakeWord = "Phoenix",
            systemInstruction = "Helpful assistant",
            themeColorHex = "#38BDF8",
            iconName = "Sparkles",
            isDefaultWakeChat = true
        )
        manager.updateActiveChats(listOf(chat))

        // Create 3 enrolled 16 kHz PCM samples with a distinctive 2-syllable energy envelope
        val totalSamples = 16000 // 1.0 second at 16 kHz
        val templatePcm = ShortArray(totalSamples) { i ->
            val inSyllable1 = i in 1600..5600
            val inSyllable2 = i in 8000..13600
            val amp = when {
                inSyllable1 -> 6000
                inSyllable2 -> 8500
                else -> 150
            }
            if (i % 2 == 0) amp.toShort() else (-amp).toShort()
        }

        manager.updateEnrollmentSamples(42L, listOf(templatePcm, templatePcm.clone(), templatePcm.clone()))
        manager.wakeWordSensitivity = 0.72f

        val liveRms = manager.computeRmsProfile(templatePcm)
        val liveZcr = manager.computeZcrProfile(templatePcm)

        val match = manager.matchAcousticWakeTemplates(liveRms, liveZcr)
        assertTrue("Expected acoustic template match for enrolled 16 kHz PCM phrase", match != null)
        assertEquals(42L, match!!.first.id)
        assertTrue(match.second >= 0.72f)

        manager.destroy()
    }

    @Test
    fun testViewModelSpeechRecognizerPermissionRequestAndGrantFlow() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)

        // Simulate missing RECORD_AUDIO permission
        viewModel.setPermissionGrantedOverrideForTest(false)
        assertFalse(viewModel.checkRecordAudioPermission())
        assertFalse(viewModel.hasRecordAudioPermission.value)

        // Attempting to start continuous wake-word listening without permission emits requiresAudioPermission = true
        viewModel.startContinuousWakeWordListening()
        assertTrue(viewModel.requiresAudioPermission.value)
        assertFalse(viewModel.isSpeechRecognizerListening.value)

        // UI consumes the permission request event when launching RequestPermission()
        viewModel.consumeAudioPermissionRequest()
        assertFalse(viewModel.requiresAudioPermission.value)

        // Simulate SpeechRecognizer reporting ERROR_INSUFFICIENT_PERMISSIONS
        viewModel.wakeRecognitionListener.onError(android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
        assertTrue(viewModel.requiresAudioPermission.value)
        assertFalse(viewModel.hasRecordAudioPermission.value)

        // Simulate user granting RECORD_AUDIO permission
        viewModel.setPermissionGrantedOverrideForTest(true)
        viewModel.onAudioPermissionResult(true)
        assertTrue(viewModel.hasRecordAudioPermission.value)
        assertFalse(viewModel.requiresAudioPermission.value)

        viewModel.cleanShutdown(null)
    }

    @Test
    fun testViewModelSpeechRecognizerWakeWordAndFollowUpQueryMatching() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)

        val customChat = com.example.data.model.ChatSession(
            id = 99L,
            title = "Quantum Research",
            wakeWord = "Hey Atlas",
            systemInstruction = "Research assistant",
            themeColorHex = "#38BDF8",
            iconName = "Sparkles",
            isDefaultWakeChat = true
        )
        viewModel.wakeWordManager.updateActiveChats(listOf(customChat))

        // Verify direct wake phrase + follow-up query matching in WakeWordManager
        val matchWithFollowUp = viewModel.wakeWordManager.matchWakePhraseInTranscript(
            "Hey Atlas explain quantum entanglement in simple terms"
        )
        assertTrue(matchWithFollowUp != null)
        assertEquals(99L, matchWithFollowUp!!.matchedChat?.id)
        assertEquals("Hey Atlas", matchWithFollowUp.matchedWakeWord)
        assertEquals("explain quantum entanglement in simple terms", matchWithFollowUp.followUpQuery)

        // Verify standalone wake phrase matching (no follow-up query yet -> followUpQuery is null)
        val standaloneMatch = viewModel.wakeWordManager.matchWakePhraseInTranscript("Hey Atlas")
        assertTrue(standaloneMatch != null)
        assertEquals(99L, standaloneMatch!!.matchedChat?.id)
        assertEquals(null, standaloneMatch.followUpQuery)

        // Verify SpeechRecognizer RecognitionListener RMS & timeout handling in MainViewModel
        viewModel.wakeRecognitionListener.onReadyForSpeech(android.os.Bundle())
        viewModel.wakeRecognitionListener.onRmsChanged(6.5f)
        assertEquals(6.5f, viewModel.wakeWordManager.audioRmsDb.value, 0.01f)

        // 1. Standalone wake phrase triggers user voice turn and does NOT inject wake word into lastHeardText
        val standaloneBundle = android.os.Bundle().apply {
            putStringArrayList(
                android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("Hey Atlas")
            )
        }
        viewModel.wakeRecognitionListener.onResults(standaloneBundle)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("", viewModel.wakeWordManager.lastHeardText.value)
        assertTrue(viewModel.isUserVoiceTurn.value)

        // 2. Wake phrase + follow-up query extracts only the scrubbed follow-up query into lastHeardText
        // and transitions into active user voice turn
        viewModel.wakeWordManager.completeChatTurnAndReleaseMic()
        assertFalse(viewModel.isUserVoiceTurn.value)
        viewModel.wakeWordManager.updateActiveChats(listOf(customChat))
        val followUpBundle = android.os.Bundle().apply {
            putStringArrayList(
                android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("Hey Atlas what is superfluidity")
            )
        }
        viewModel.wakeRecognitionListener.onResults(followUpBundle)
        assertEquals("what is superfluidity", viewModel.wakeWordManager.lastHeardText.value)
        assertTrue(viewModel.isUserVoiceTurn.value)

        // Simulate benign silence timeout (ERROR_SPEECH_TIMEOUT / ERROR_NO_MATCH) without crashing
        viewModel.wakeRecognitionListener.onError(android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
        viewModel.wakeRecognitionListener.onError(android.speech.SpeechRecognizer.ERROR_NO_MATCH)

        viewModel.cleanShutdown(null)
    }

    @Test
    fun testSpeechRecognizerIntentSilenceTimeoutOverrides() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        val intent = viewModel.createWakeRecognizerIntent()

        assertEquals(
            android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            intent.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL)
        )
        assertEquals(
            3000L,
            intent.getLongExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 0L)
        )
        assertEquals(
            3000L,
            intent.getLongExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 0L)
        )
        assertEquals(
            5000L,
            intent.getLongExtra(android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 0L)
        )

        viewModel.cleanShutdown(null)
    }

    @Test
    fun testWakeWordReTriggerForcesStateResetWhenAlreadyInChatOrHoldingStaleDraft() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)

        val customChat = com.example.data.model.ChatSession(
            id = 99L,
            title = "Quantum Research",
            wakeWord = "Hey Atlas",
            systemInstruction = "Research assistant",
            themeColorHex = "#38BDF8",
            iconName = "Sparkles",
            isDefaultWakeChat = true
        )
        viewModel.wakeWordManager.updateActiveChats(listOf(customChat))
        viewModel.selectChat(99L)

        // Simulate user in ChatDetailScreen with active in-chat dictation holding an unsent partial draft
        viewModel.toggleInChatDictation()
        assertTrue(viewModel.isInChatDictationActive.value)
        val partialBundle = android.os.Bundle().apply {
            putStringArrayList(
                android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("unfinished stale draft")
            )
        }
        viewModel.wakeRecognitionListener.onPartialResults(partialBundle)
        assertEquals("", viewModel.currentDraftText.value)
        assertEquals("unfinished stale draft", viewModel.wakeWordManager.lastHeardText.value)
        val retriggerIdBefore = viewModel.wakeRetriggerEventId.value

        // Re-trigger wake word while already in ChatDetailScreen: must force state reset and clear stale draft
        viewModel.wakeWordManager.onKeywordHit("Hey Atlas", initialQuery = null)
        assertFalse(viewModel.isInChatDictationActive.value)
        assertEquals("", viewModel.currentDraftText.value)
        assertEquals("", viewModel.wakeWordManager.lastHeardText.value)
        assertEquals(retriggerIdBefore + 1L, viewModel.wakeRetriggerEventId.value)
        assertTrue(viewModel.isUserVoiceTurn.value)
        assertEquals(HandsFreeVoiceState.TRANSCRIBING, viewModel.wakeWordManager.handsFreeState.value)

        viewModel.cleanShutdown(null)
    }

    @Test
    fun testSpeechRecognizerAutoDispatchOnResultsAndManualSendBufferFlush() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)

        val customChat = com.example.data.model.ChatSession(
            id = 99L,
            title = "Quantum Research",
            wakeWord = "Hey Atlas",
            systemInstruction = "Research assistant",
            themeColorHex = "#38BDF8",
            iconName = "Sparkles",
            isDefaultWakeChat = true
        )
        viewModel.wakeWordManager.updateActiveChats(listOf(customChat))
        viewModel.selectChat(99L)

        // 0. When in-chat dictation is NOT active, partial speech transcripts must NOT leak into currentDraftText
        val strayPartialBundle = android.os.Bundle().apply {
            putStringArrayList(
                android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("unrelated background speech")
            )
        }
        viewModel.wakeRecognitionListener.onPartialResults(strayPartialBundle)
        assertEquals("", viewModel.currentDraftText.value)

        // 1. When in-chat dictation IS active, SpeechRecognizer onResults() automatically dispatches finalized spoken text to Gemini
        viewModel.toggleInChatDictation()
        assertTrue(viewModel.isInChatDictationActive.value)
        val finalBundle = android.os.Bundle().apply {
            putStringArrayList(
                android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("Explain quantum tunneling")
            )
        }
        viewModel.wakeRecognitionListener.onResults(finalBundle)
        assertFalse(viewModel.isInChatDictationActive.value)
        assertEquals("Explain quantum tunneling", viewModel.lastDispatchedVoicePrompt.value)
        assertEquals("", viewModel.currentDraftText.value)
        assertEquals("", viewModel.wakeWordManager.lastHeardText.value)

        // Reset generation state so next manual send can proceed immediately in unit test
        viewModel.selectChat(null)
        viewModel.selectChat(99L)

        // 2. Manual Send button grabs partial transcription from buffer during active in-chat dictation, stops SpeechRecognizer, and dispatches immediately
        viewModel.toggleInChatDictation()
        val partialBundle = android.os.Bundle().apply {
            putStringArrayList(
                android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("Summarize the Schrödinger equation")
            )
        }
        viewModel.wakeRecognitionListener.onPartialResults(partialBundle)
        assertEquals("", viewModel.currentDraftText.value)
        assertEquals("Summarize the Schrödinger equation", viewModel.wakeWordManager.lastHeardText.value)

        // Tapping manual Send with empty inputText argument still grabs the buffered partial transcription
        viewModel.sendMessage(chatId = 99L, text = "")
        assertFalse(viewModel.isSpeechRecognizerListening.value)
        assertEquals("Summarize the Schrödinger equation", viewModel.lastDispatchedVoicePrompt.value)
        assertEquals("", viewModel.currentDraftText.value)
        assertEquals("", viewModel.wakeWordManager.lastHeardText.value)

        viewModel.cleanShutdown(null)
    }

    @Test
    fun testAudioRecordError20MutualExclusionAndExponentialBackoff() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        val manager = viewModel.wakeWordManager

        // 1. Verify exponential backoff progression (500ms -> 1000ms -> 2000ms -> 4000ms cap)
        assertEquals(3, WakeWordManager.MAX_AUDIO_RECORD_INIT_RETRIES)
        assertEquals(500L, WakeWordManager.calculateExponentialBackoffMs(0))
        assertEquals(1000L, WakeWordManager.calculateExponentialBackoffMs(1))
        assertEquals(2000L, WakeWordManager.calculateExponentialBackoffMs(2))
        assertEquals(4000L, WakeWordManager.calculateExponentialBackoffMs(3))
        assertEquals(4000L, WakeWordManager.calculateExponentialBackoffMs(10))

        // 2. Verify mutual exclusion: when SpeechRecognizer is active, AudioRecord is released and never created concurrently
        manager.isExternalSpeechRecognizerActive = false
        manager.startContinuousListening(forceActiveTurn = false)
        assertTrue(manager.activeAudioRecordCount <= 1)

        manager.isExternalSpeechRecognizerActive = true
        viewModel.releaseAllNativeAudioRecordTracksForSpeechRecognizer()
        assertEquals(0, manager.activeAudioRecordCount)

        // Attempting to start continuous PCM listening while SpeechRecognizer is active must NOT allocate an AudioRecord
        manager.startContinuousListening(forceActiveTurn = true)
        assertEquals(0, manager.activeAudioRecordCount)

        // 3. Verify permission guard: revoking RECORD_AUDIO prevents AudioRecord initialization
        manager.isExternalSpeechRecognizerActive = false
        org.robolectric.Shadows.shadowOf(app)
            .denyPermissions(android.Manifest.permission.RECORD_AUDIO)
        assertFalse(manager.hasRecordAudioPermission())
        manager.startContinuousListening(forceActiveTurn = false)
        assertEquals(0, manager.activeAudioRecordCount)
        assertEquals(
            "Microphone permission required for wake-word listening",
            manager.statusMessage.value
        )

        // Restore permission for cleanup
        org.robolectric.Shadows.shadowOf(app)
            .grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        viewModel.cleanShutdown(null)
    }

    @Test
    fun testLegacyAudioPlaceholdersBlockedAndTranscribedTextPersisted() = kotlinx.coroutines.runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            app,
            com.example.data.db.AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val repo = com.example.data.repository.ChatRepository(db.chatDao(), db.messageDao())

        val chatId = repo.saveChat(
            com.example.data.model.ChatSession(
                title = "Voice Transcript Chat",
                wakeWord = "Hey Gemini",
                systemInstruction = "Helpful assistant"
            )
        )
        assertTrue(chatId > 0L)

        // 1. Legacy audio placeholder or empty audio-only message must be rejected (-1L)
        val placeholderMsg = com.example.data.model.ChatMessage(
            chatId = chatId,
            sender = "user",
            content = "[Audio Turn]",
            attachmentName = "turn_capture.pcm",
            attachmentMimeType = "audio/pcm"
        )
        assertFalse(placeholderMsg.hasTranscribedText)
        assertFalse(placeholderMsg.sanitizeForPersistence().hasPersistableContent)
        val rejectedId = repo.insertMessage(placeholderMsg)
        assertEquals(-1L, rejectedId)

        // 2. Spoken message with actual transcribed text strips legacy audio attachment metadata and persists words
        val transcribedMsg = com.example.data.model.ChatMessage(
            chatId = chatId,
            sender = "user",
            content = "What is the weather in Tokyo tomorrow?",
            triggeredByWakeWord = true,
            attachmentName = "turn_capture.pcm",
            attachmentMimeType = "audio/pcm"
        )
        val savedId = repo.insertMessage(transcribedMsg)
        assertTrue(savedId > 0L)

        val storedMessages = repo.getMessagesForChatSync(chatId)
        assertEquals(1, storedMessages.size)
        assertEquals("What is the weather in Tokyo tomorrow?", storedMessages.first().content)
        assertEquals(null, storedMessages.first().attachmentName)
        assertTrue(storedMessages.first().hasTranscribedText)

        db.close()
    }

    @Test
    fun testDuplicateMessageInsertIgnoredAndTotalVoiceCommandsIncremented() = kotlinx.coroutines.runBlocking {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            app,
            com.example.data.db.AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val messageDao = db.messageDao()
        val chatDao = db.chatDao()

        val chatId = chatDao.insertChat(
            com.example.data.model.ChatSession(
                title = "Voice Counter Chat",
                wakeWord = "Hey Gemini",
                systemInstruction = "Helpful assistant"
            )
        )
        assertTrue(chatId > 0L)

        // 1. Verify "Hey Jimmy" and "Hey Gemini" prefixes are cleanly stripped
        val (jimmyWake, jimmyStripped) = SpeechRecognitionCoordinator.extractWakeTriggeredTranscript(
            rawTranscript = "Hey Jimmy, explain coroutine structured concurrency",
            customWakeWords = listOf("Hey Gemini")
        )
        assertTrue(jimmyWake)
        assertEquals("explain coroutine structured concurrency", jimmyStripped)

        val (geminiWake, geminiStripped) = SpeechRecognitionCoordinator.extractWakeTriggeredTranscript(
            rawTranscript = "Hey Gemini: summarize the meeting notes",
            customWakeWords = listOf("Hey Gemini")
        )
        assertTrue(geminiWake)
        assertEquals("summarize the meeting notes", geminiStripped)

        // 2. Insert wake-word-triggered message and verify totalVoiceCommands increments to 1
        val fixedTs = 1730000000000L
        val firstWakeMsg = com.example.data.model.ChatMessage(
            chatId = chatId,
            sender = "user",
            content = "Hey Jimmy, explain coroutine structured concurrency",
            timestamp = fixedTs,
            triggeredByWakeWord = true
        )
        val firstId = messageDao.insertMessageSafely(firstWakeMsg)
        assertTrue(firstId > 0L)

        val updatedChatAfterFirst = chatDao.getChatByIdSync(chatId)!!
        assertEquals(1, updatedChatAfterFirst.totalVoiceCommands)

        // Verify stored message has stripped wake prefix and triggeredByWakeWord = true
        val savedMsg = messageDao.getMessageByIdSync(firstId)!!
        assertEquals("explain coroutine structured concurrency", savedMsg.content)
        assertTrue(savedMsg.triggeredByWakeWord)

        // 3. Attempt duplicate insert with identical [chatId, timestamp, sender] -> must be ignored (-1L)
        //    and totalVoiceCommands must NOT increment a second time
        val duplicateWakeMsg = com.example.data.model.ChatMessage(
            chatId = chatId,
            sender = "user",
            content = "explain coroutine structured concurrency",
            timestamp = fixedTs,
            triggeredByWakeWord = true
        )
        val duplicateId = messageDao.insertMessageSafely(duplicateWakeMsg)
        assertEquals(-1L, duplicateId)

        val updatedChatAfterDuplicate = chatDao.getChatByIdSync(chatId)!!
        assertEquals(1, updatedChatAfterDuplicate.totalVoiceCommands)
        assertEquals(1, messageDao.getMessagesForChatSync(chatId).size)

        db.close()
    }

    @Test
    fun testVoiceHistoryNavigationCommandAudioMutexAndStateConsumption() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)

        // 1. Verify "open history", "show history", and "voice history" (with or without leading wake words) match
        assertTrue(AudioCommandSanitizer.isVoiceHistoryNavigationCommand("open history"))
        assertTrue(AudioCommandSanitizer.isVoiceHistoryNavigationCommand("Show History!"))
        assertTrue(AudioCommandSanitizer.isVoiceHistoryNavigationCommand("voice history"))
        assertTrue(AudioCommandSanitizer.isVoiceHistoryNavigationCommand("Hey Gemini, open history"))
        assertTrue(AudioCommandSanitizer.isVoiceHistoryNavigationCommand("Hey Jimmy, show history"))
        assertFalse(AudioCommandSanitizer.isVoiceHistoryNavigationCommand("tell me about ancient history"))

        // 2. Verify SpeechRecognitionCoordinator.extractMatchedWakeWordAndPrompt returns null for history commands
        //    so "open history" is never mis-routed as a chat wake word ("open") + prompt ("history")
        org.junit.Assert.assertNull(SpeechRecognitionCoordinator.extractMatchedWakeWordAndPrompt("open history"))
        org.junit.Assert.assertNull(SpeechRecognitionCoordinator.extractMatchedWakeWordAndPrompt("Hey Gemini, show history"))

        // 3. Dispatching a voice history command immediately enters transient isProcessingCommand=true
        //    (without setting isIntentionallyPaused=true) and emits pendingVoiceNavigationRoute = "history"
        val handled = viewModel.processSpeechRecognizerResults(listOf("open history"), isFinal = true)
        assertTrue(handled)
        assertEquals("history", viewModel.pendingVoiceNavigationRoute.value)
        assertTrue(viewModel.isNavigatingViaVoiceCommand.value)
        assertFalse(viewModel.speechRecognitionCoordinator.isIntentionallyPaused)
        assertTrue(viewModel.speechRecognitionCoordinator.isProcessing)
        assertTrue(viewModel.speechRecognitionCoordinator.isProcessingCommand)
        assertFalse(WakeWordVoiceService.isIntentionallyPaused)
        assertTrue(WakeWordVoiceService.isProcessingCommand)
        org.junit.Assert.assertNull(viewModel.pendingWakeQuery.value)

        // 4. Consuming the pending route immediately resets pendingVoiceNavigationRoute to null so it cannot loop on resume
        viewModel.consumePendingVoiceNavigationRoute()
        org.junit.Assert.assertNull(viewModel.pendingVoiceNavigationRoute.value)

        // 5. Verify heavy AI and Cloud Sync subsystems remain lazily uninitialized during voice history navigation
        assertFalse(viewModel.isGeminiServiceInitialized)
        assertFalse(viewModel.isFirebaseSyncInitialized)

        // 6. Verify SpeechRecognizer trace logs are recorded in both coordinator traceHistory and in-app wakeWordLogs
        val coordinatorTraces = viewModel.speechRecognitionCoordinator.traceHistory.value
        assertTrue(coordinatorTraces.any { it.contains("stage=PAUSE_FOR_COMMAND_DISPATCH") && it.contains("thread=") })
        val inAppLogs = viewModel.wakeWordLogs.value
        assertTrue(inAppLogs.any { it.wakeWord.contains("VOICE_NAV_DISPATCH_START") })
        assertTrue(inAppLogs.any { it.wakeWord.contains("VOICE_NAV_ROUTE_CONSUMED") })

        // 7. Simulate NavController destination commit ("history") + settle handshake and verify mutex unlocks cleanly
        viewModel.onVoiceNavigationDestinationCommitted("history", settleDelayMs = 100L)
        assertTrue(viewModel.wakeWordLogs.value.any { it.wakeWord.contains("VOICE_NAV_DESTINATION_COMMITTED") })

        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(250))

        assertFalse(viewModel.isNavigatingViaVoiceCommand.value)
        assertFalse(viewModel.speechRecognitionCoordinator.isProcessingCommand)
        assertFalse(WakeWordVoiceService.isIntentionallyPaused)
        assertFalse(WakeWordVoiceService.isProcessingCommand)
        assertTrue(viewModel.wakeWordLogs.value.any { it.wakeWord.contains("VOICE_NAV_MUTEX_RESUMED") })

        viewModel.cleanShutdown(null)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun testWindowFocusGuardAndVoicePayloadSanitization() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)
        if (viewModel.isHandsFreeActive.value) {
            viewModel.toggleHandsFreeVoice(false)
        }

        // 1. Verify Window Focus Guard blocks startListening() and AudioRecord.startRecording() when unfocused and hands-free is OFF
        viewModel.onWindowFocusChanged(false)
        assertFalse(viewModel.hasWindowFocus.value)
        assertFalse(viewModel.speechRecognitionCoordinator.isWindowFocused)
        assertFalse(viewModel.wakeWordManager.hasActiveWindowFocus)
        assertTrue(viewModel.speechRecognitionCoordinator.shouldAbortListening())
        assertFalse(viewModel.speechRecognitionCoordinator.startListening(userInitiated = true))
        assertFalse(viewModel.toggleInChatDictation())
        assertFalse(viewModel.isInChatDictationActive.value)
        viewModel.wakeWordManager.startContinuousListening(forceActiveTurn = true)
        assertFalse(viewModel.wakeWordManager.isListening.value)

        // Restore window focus and verify ChatScreen mic button stops background service listener before starting interactive dictation
        viewModel.onWindowFocusChanged(true)
        assertTrue(viewModel.hasWindowFocus.value)
        assertTrue(viewModel.speechRecognitionCoordinator.isWindowFocused)
        assertTrue(viewModel.wakeWordManager.hasActiveWindowFocus)
        assertTrue(viewModel.toggleInChatDictation())
        assertTrue(viewModel.isInChatDictationActive.value)
        assertTrue(WakeWordVoiceService.isIntentionallyPaused)
        assertFalse(viewModel.toggleInChatDictation())
        assertFalse(viewModel.isInChatDictationActive.value)

        // 2. Verify empty, blank, or < 2 character voice payloads are discarded completely
        org.junit.Assert.assertNull(viewModel.sanitizeValidWakeQuery(null))
        org.junit.Assert.assertNull(viewModel.sanitizeValidWakeQuery(""))
        org.junit.Assert.assertNull(viewModel.sanitizeValidWakeQuery("   "))
        org.junit.Assert.assertNull(viewModel.sanitizeValidWakeQuery("a"))
        org.junit.Assert.assertNull(viewModel.sanitizeValidWakeQuery(" ? "))
        assertEquals("hi", viewModel.sanitizeValidWakeQuery("  hi "))

        viewModel.setPendingWakeQuery("x")
        org.junit.Assert.assertNull(viewModel.pendingWakeQuery.value)
        org.junit.Assert.assertNull(viewModel.consumePendingWakeQuery())

        // 3. Verify _pendingWakeQuery is cleared immediately when entering ChatScreen (selectChat / onEnterChatScreen)
        viewModel.setPendingWakeQuery("Summarize our roadmap")
        assertEquals("Summarize our roadmap", viewModel.pendingWakeQuery.value)
        viewModel.selectChat(1L)
        org.junit.Assert.assertNull(viewModel.pendingWakeQuery.value)

        viewModel.setPendingWakeQuery("Check deployment status")
        assertEquals("Check deployment status", viewModel.pendingWakeQuery.value)
        viewModel.onEnterChatScreen(1L)
        org.junit.Assert.assertNull(viewModel.pendingWakeQuery.value)
        org.junit.Assert.assertNull(viewModel.wakeConversationSummary.value)

        viewModel.cleanShutdown(null)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun testDistinctHapticPatternsForRadarToggleWakeWordsAndVoiceCommands() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        AudioCommandSanitizer.resetHapticDiagnosticsForTest()

        // 1. Verify each HapticPatternType has distinct waveform timings and amplitudes
        val patterns = HapticPatternType.entries
        assertEquals(5, patterns.size)
        for (pattern in patterns) {
            assertEquals(pattern.timingsMs.size, pattern.amplitudes.size)
            assertTrue(pattern.timingsMs.isNotEmpty())
        }
        assertFalse(
            HapticPatternType.RADAR_TOGGLE_ON.timingsMs.contentEquals(
                HapticPatternType.RADAR_TOGGLE_OFF.timingsMs
            )
        )
        assertFalse(
            HapticPatternType.RADAR_TOGGLE_ON.timingsMs.contentEquals(
                HapticPatternType.WAKE_WORD_DETECTED.timingsMs
            )
        )
        assertFalse(
            HapticPatternType.WAKE_WORD_DETECTED.timingsMs.contentEquals(
                HapticPatternType.VOICE_COMMAND_TRIGGERED.timingsMs
            )
        )

        // 2. Verify Hands-Free Radar toggle ON and OFF trigger distinct haptic patterns
        AudioCommandSanitizer.playRadarToggleHaptic(app, isActivating = true, reason = "TestRadarToggleOn")
        assertEquals(HapticPatternType.RADAR_TOGGLE_ON, AudioCommandSanitizer.lastHapticPattern)
        assertEquals(1, AudioCommandSanitizer.hapticTriggerCount)

        AudioCommandSanitizer.playRadarToggleHaptic(app, isActivating = false, reason = "TestRadarToggleOff")
        assertEquals(HapticPatternType.RADAR_TOGGLE_OFF, AudioCommandSanitizer.lastHapticPattern)
        assertEquals(2, AudioCommandSanitizer.hapticTriggerCount)

        // 3. Verify Wake Word detection triggers WAKE_WORD_DETECTED pattern
        AudioCommandSanitizer.resetHapticDiagnosticsForTest()
        val manager = WakeWordManager(app)
        manager.onKeywordHit("Hey Gemini", initialQuery = "what is the time")
        assertEquals(HapticPatternType.WAKE_WORD_DETECTED, AudioCommandSanitizer.lastHapticPattern)
        assertTrue(AudioCommandSanitizer.hapticTriggerCount >= 1)

        // 4. Verify Voice Command triggers VOICE_COMMAND_TRIGGERED pattern
        AudioCommandSanitizer.resetHapticDiagnosticsForTest()
        AudioCommandSanitizer.playVoiceCommandHaptic(app, commandLabel = "TestVoiceCommand:open_history")
        assertEquals(HapticPatternType.VOICE_COMMAND_TRIGGERED, AudioCommandSanitizer.lastHapticPattern)
        assertEquals(1, AudioCommandSanitizer.hapticTriggerCount)
        assertTrue(
            AudioCommandSanitizer.getRecentHapticEvents().any {
                it.contains("VOICE_COMMAND_TRIGGERED") && it.contains("TestVoiceCommand:open_history")
            }
        )

        manager.destroy()
    }

    @Test
    fun testCreateGeneralLogFileCollectsSessionDiagnosticsAndCopiesToClipboard() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        AudioCommandSanitizer.resetHapticDiagnosticsForTest()
        val viewModel = com.example.ui.MainViewModel(app)

        // Trigger a Radar haptic event and a warning/diagnostic event in the session
        AudioCommandSanitizer.playRadarToggleHaptic(app, isActivating = true, reason = "HandsFreeRadarPill")
        viewModel.wakeRecognitionListener.onError(android.speech.SpeechRecognizer.ERROR_NETWORK)

        // Execute "Create a general log file" action which builds per-use session diagnostics and copies to clipboard
        val logOutput = viewModel.createAndCopyGeneralLogFileToClipboard(app)

        // 1. Verify key diagnostic sections are present (Network, Permissions, Recent Error Logs, Haptics)
        assertTrue(logOutput.contains("=== GEMINI WAKE GENERAL SESSION DIAGNOSTIC LOG FILE ==="))
        assertTrue(logOutput.contains("--- 1. NETWORK & CONNECTIVITY STATE ---"))
        assertTrue(logOutput.contains("Network Connection State:"))
        assertTrue(logOutput.contains("--- 2. PERMISSION & AUDIO FOCUS STATES ---"))
        assertTrue(logOutput.contains("RECORD_AUDIO Permission:"))
        assertTrue(logOutput.contains("CAMERA Permission:"))
        assertTrue(logOutput.contains("POST_NOTIFICATIONS Permission:"))
        assertTrue(logOutput.contains("Hands-Free Radar Active:"))
        assertTrue(logOutput.contains("--- 3. RECENT ERROR LOGS & WARNINGS ---"))
        assertTrue(logOutput.contains("--- 4. HAPTIC FEEDBACK & VOICE ACTIVATION TELEMETRY ---"))
        assertTrue(logOutput.contains("Last Pattern: RADAR_TOGGLE_ON"))
        assertTrue(logOutput.contains("RADAR_TOGGLE_ON: HandsFreeRadarPill"))

        // 2. Verify the generated general log was copied directly to the Android ClipboardManager
        val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertTrue(clipboard.hasPrimaryClip())
        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertEquals(logOutput, clipText)

        viewModel.cleanShutdown(null)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun testHandsFreeRadarToggleDebounce400ms_andTransientIsListeningDoesNotForceHandsFreeOff() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)

        var simulatedClockMs = 10_000L
        viewModel.handsFreeToggleClockMs = { simulatedClockMs }
        viewModel.resetHandsFreeToggleDebounceForTest()

        // Ensure starting from OFF
        if (viewModel.isHandsFreeActive.value) {
            viewModel.toggleHandsFreeVoice(false)
            simulatedClockMs += 500L
        }
        assertFalse(viewModel.isHandsFreeActive.value)

        // 1. Toggle ON at t = 10_500ms -> succeeds
        viewModel.toggleHandsFreeVoice()
        assertTrue(viewModel.isHandsFreeActive.value)
        assertFalse(viewModel.speechRecognitionCoordinator.isIntentionallyPaused)

        // 2. Rapid duplicate click 150ms later (< 400ms debounce) -> ignored, stays ON
        simulatedClockMs += 150L
        viewModel.toggleHandsFreeVoice()
        assertTrue(
            "Rapid click within 400ms debounce window must not bounce handsFreeMode OFF",
            viewModel.isHandsFreeActive.value
        )

        // 3. Simulate a transient isListening == false while WakeWordManager is active ->
        //    toggling after 500ms must still use _isHandsFreeActive as source of truth
        simulatedClockMs += 500L
        viewModel.speechRecognitionCoordinator.stopListening(markIntentionallyPaused = false)
        assertFalse(viewModel.isSpeechRecognizerListening.value)
        assertTrue(
            "Transient isListening == false must never force isHandsFreeActive to OFF",
            viewModel.isHandsFreeActive.value
        )

        // 4. Stale pause recovery on window focus: if a background glitch set isIntentionallyPaused = true,
        //    onWindowFocusChanged(true) forces isIntentionallyPaused = false when hands-free is ON
        viewModel.speechRecognitionCoordinator.setIntentionallyPaused(true)
        assertTrue(viewModel.speechRecognitionCoordinator.isIntentionallyPaused)
        viewModel.onWindowFocusChanged(true)
        assertFalse(
            "onWindowFocusChanged(true) must clear isIntentionallyPaused when hands-free is ON and not processing",
            viewModel.speechRecognitionCoordinator.isIntentionallyPaused
        )

        // 5. Explicit user toggle OFF after > 400ms -> sets isHandsFreeActive = false and isIntentionallyPaused = true
        simulatedClockMs += 500L
        viewModel.toggleHandsFreeVoice()
        assertFalse(viewModel.isHandsFreeActive.value)
        assertTrue(viewModel.speechRecognitionCoordinator.isIntentionallyPaused)

        viewModel.cleanShutdown(null)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun testIsolateHandsFreeFromFocusLoss_VoiceCommandDispatcher_andAutoRearm() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)
        AudioCommandSanitizer.resetHapticDiagnosticsForTest()

        // Enable Hands-Free Radar
        viewModel.toggleHandsFreeVoice(true)
        assertTrue(viewModel.isHandsFreeActive.value)
        assertFalse(viewModel.speechRecognitionCoordinator.isIntentionallyPaused)

        // 1. Focus loss / pause / background transitions must NEVER set handsFreeMode = false,
        //    emit RADAR_TOGGLE_OFF, or set isIntentionallyPaused = true
        AudioCommandSanitizer.resetHapticDiagnosticsForTest()
        viewModel.onWindowFocusChanged(false)
        viewModel.onAppPaused()
        viewModel.onAppBackgrounded()
        assertTrue("Hands-Free Radar must remain TRUE across focus loss & backgrounding", viewModel.isHandsFreeActive.value)
        assertFalse("isIntentionallyPaused must remain FALSE when handsFreeMode is active", viewModel.speechRecognitionCoordinator.isIntentionallyPaused)
        assertFalse(
            "RADAR_TOGGLE_OFF must never be emitted on focus loss or backgrounding",
            AudioCommandSanitizer.getRecentHapticEvents().any { it.contains("RADAR_TOGGLE_OFF") }
        )

        viewModel.onAppForegrounded()
        viewModel.onWindowFocusChanged(true)
        assertTrue(viewModel.isHandsFreeActive.value)
        assertFalse(viewModel.speechRecognitionCoordinator.isIntentionallyPaused)

        // 2. VoiceCommandDispatcher internal screen navigation ("Hey Gemini, open settings")
        //    must route internally without setting isIntentionallyPaused = true or disabling handsFreeMode
        val navSettingsHandled = viewModel.processSpeechRecognizerResults(
            listOf("Hey Gemini, open settings"),
            isFinal = true
        )
        assertTrue(navSettingsHandled)
        assertEquals("settings", viewModel.pendingVoiceNavigationRoute.value)
        assertTrue(viewModel.isHandsFreeActive.value)
        assertFalse(viewModel.speechRecognitionCoordinator.isIntentionallyPaused)
        assertTrue(viewModel.speechRecognitionCoordinator.isProcessingCommand)
        assertFalse(viewModel.isUserVoiceTurn.value)

        viewModel.consumePendingVoiceNavigationRoute()
        viewModel.onVoiceNavigationDestinationCommitted("settings", settleDelayMs = 100L)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(250))

        // Verify re-armed after command completion
        assertFalse(viewModel.speechRecognitionCoordinator.isProcessingCommand)
        assertFalse(viewModel.speechRecognitionCoordinator.isIntentionallyPaused)
        assertTrue(viewModel.isHandsFreeActive.value)

        // 3. VoiceCommandDispatcher category filter switching ("filter by Work" and "show all categories")
        val catHandled = viewModel.processSpeechRecognizerResults(
            listOf("filter by Work"),
            isFinal = true
        )
        assertTrue(catHandled)
        assertEquals("Work", viewModel.selectedCategoryFilter.value)
        assertEquals("home", viewModel.pendingVoiceNavigationRoute.value)
        assertFalse(viewModel.speechRecognitionCoordinator.isProcessingCommand)
        assertFalse(viewModel.speechRecognitionCoordinator.isIntentionallyPaused)
        assertTrue(viewModel.isHandsFreeActive.value)

        val clearCatHandled = viewModel.processSpeechRecognizerResults(
            listOf("show all categories"),
            isFinal = true
        )
        assertTrue(clearCatHandled)
        org.junit.Assert.assertNull(viewModel.selectedCategoryFilter.value)
        assertFalse(viewModel.speechRecognitionCoordinator.isProcessingCommand)
        assertFalse(viewModel.speechRecognitionCoordinator.isIntentionallyPaused)

        // 4. External URL opening passively stages URL in transient isProcessingCommand state and re-arms without disabling radar or starting an external Activity
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        shadowApp.clearNextStartedActivities()
        viewModel.openExternalUrlWithTransientGuard(app, "https://example.com")
        assertTrue(viewModel.isHandsFreeActive.value)
        assertFalse(viewModel.speechRecognitionCoordinator.isIntentionallyPaused)
        assertFalse(viewModel.speechRecognitionCoordinator.isProcessingCommand)
        org.junit.Assert.assertNull(
            "openExternalUrlWithTransientGuard must passively stage URL and never call startActivity()",
            shadowApp.nextStartedActivity
        )

        // 5. "open chat" voice command navigates internally via NavController route ("chat") without launching Chrome
        val openChatHandled = viewModel.processSpeechRecognizerResults(
            listOf("Hey Gemini, open chat"),
            isFinal = true
        )
        assertTrue(openChatHandled)
        assertEquals("chat", viewModel.pendingVoiceNavigationRoute.value)
        assertTrue(viewModel.isHandsFreeActive.value)
        org.junit.Assert.assertNull(
            "Voice command 'open chat' must never launch an external Activity",
            shadowApp.nextStartedActivity
        )
        viewModel.consumePendingVoiceNavigationRoute()
        viewModel.onVoiceNavigationDestinationCommitted("chat", settleDelayMs = 100L)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(200))
        assertFalse(viewModel.isNavigatingViaVoiceCommand.value)
        assertTrue(viewModel.isHandsFreeActive.value)

        // 6. Audio Focus Handling:
        //    - Do NOT yield on AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
        //    - Yield on AUDIOFOCUS_LOSS and AUDIOFOCUS_LOSS_TRANSIENT
        //    - Use a 500ms debounce before re-arming SpeechRecognizer on AUDIOFOCUS_GAIN
        assertEquals(500L, SpeechRecognitionCoordinator.AUDIOFOCUS_GAIN_REARM_DEBOUNCE_MS)
        assertEquals(500L, WakeWordManager.AUDIOFOCUS_GAIN_DEBOUNCE_MS)

        viewModel.wakeWordManager.handleAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(viewModel.wakeWordManager.hasAudioFocus.value)

        // CAN_DUCK must NOT yield audio focus
        viewModel.wakeWordManager.handleAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertTrue(
            "Audio focus must NOT be yielded on AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK",
            viewModel.wakeWordManager.hasAudioFocus.value
        )
        assertTrue(
            viewModel.speechRecognitionCoordinator.traceHistory.value.any {
                it.contains("stage=AUDIOFOCUS_CAN_DUCK_IGNORED")
            }
        )

        // AUDIOFOCUS_LOSS_TRANSIENT must yield audio focus
        viewModel.wakeWordManager.handleAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertFalse(viewModel.wakeWordManager.hasAudioFocus.value)
        assertTrue(
            viewModel.speechRecognitionCoordinator.traceHistory.value.any {
                it.contains("stage=AUDIOFOCUS_YIELD")
            }
        )

        // AUDIOFOCUS_GAIN must queue a 500ms debounced re-arm of SpeechRecognizer
        viewModel.wakeWordManager.handleAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_GAIN)
        assertTrue(viewModel.wakeWordManager.hasAudioFocus.value)
        assertTrue(viewModel.speechRecognitionCoordinator.hasPendingRestart)
        assertTrue(
            viewModel.speechRecognitionCoordinator.traceHistory.value.any {
                it.contains("stage=AUDIOFOCUS_GAIN_REARM_QUEUED") && it.contains("debounceMs=500")
            }
        )

        viewModel.cleanShutdown(null)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test
    fun testCloudBackupVaultRestoreAndLocalOnlyDeletionDecoupling() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            app,
            com.example.data.db.AppDatabase::class.java
        ).allowMainThreadQueries().build()
        val chatRepo = com.example.data.repository.ChatRepository(db.chatDao(), db.messageDao())
        val messageRepo = com.example.data.repository.MessageRepository(db.messageDao(), db.chatDao())
        val wakeRepo = com.example.data.repository.WakeWordRepository(db.wakeWordDao())
        val viewModel = com.example.ui.MainViewModel(app)

        // 1. Verify FirestoreChatDoc with localChatId == 0L and non-numeric id resolves a deterministic positive ID
        val cloudDoc = com.example.data.firebase.FirestoreChatDoc(
            id = "cloud_backup_alpha_99",
            userId = "user_123",
            localChatId = 0L,
            title = "Restored Cloud Architecture Chat",
            wakeWord = "Hey Cloud",
            systemInstruction = "Cloud backup assistant"
        )
        val mappedSession = cloudDoc.toChatSession()
        assertTrue("Mapped ChatSession ID must be positive even when localChatId is 0", mappedSession.id > 0L)

        val cloudMsgDoc = com.example.data.firebase.FirestoreMessageDoc(
            id = "msg_cloud_1",
            userId = "user_123",
            localMessageId = 501L,
            localChatId = mappedSession.id,
            sender = "user",
            content = "Restore my cloud architecture notes",
            messageTimestamp = 1730000010000L
        )
        viewModel.seedCloudBackupStateForTest(
            chats = listOf(cloudDoc),
            messages = listOf(cloudMsgDoc)
        )
        assertEquals(1, viewModel.cloudBackupChats.value.size)

        kotlinx.coroutines.runBlocking {
            // 2. Restore the cloud backup to the device and verify it is upserted into local Room DB and selected
            val restoredId = viewModel.restoreChatFromCloudSuspend(
                chatDoc = cloudDoc,
                chatRepoOverride = chatRepo,
                messageRepoOverride = messageRepo,
                wakeRepoOverride = wakeRepo
            )
            assertTrue("Restored chat ID must be > 0", restoredId > 0L)
            val localRoomChat = chatRepo.getChatByIdSync(restoredId)
            org.junit.Assert.assertNotNull("Restored cloud chat must exist in local Room database", localRoomChat)
            assertEquals("Restored Cloud Architecture Chat", localRoomChat?.title)
            assertEquals(restoredId, viewModel.currentChatId.value)

            val restoredMessages = chatRepo.getMessagesForChatSync(restoredId)
            assertEquals(1, restoredMessages.size)
            assertEquals("Restore my cloud architecture notes", restoredMessages.first().content)

            // 3. Delete the chat locally -> must remove from local Room DB while cloudBackupChats remains intact
            chatRepo.deleteChat(restoredId)
            val afterLocalDelete = chatRepo.getChatByIdSync(restoredId)
            org.junit.Assert.assertNull("Local deleteChat must remove the chat from local Room DB", afterLocalDelete)
            assertEquals(
                "Local delete must NOT remove the backup from Cloud Backup Vault",
                1,
                viewModel.cloudBackupChats.value.size
            )

            // 4. Verify deleteChatFromCloudSuspend removes the backup from Cloud Backup Vault
            val cloudDeleted = viewModel.deleteChatFromCloudSuspend(cloudDoc)
            assertTrue(cloudDeleted)
            assertTrue("Cloud backup must be removed after deleteChatFromCloudSuspend", viewModel.cloudBackupChats.value.isEmpty())
        }

        db.close()
        viewModel.cleanShutdown(null)
    }

    @Test
    fun testInAppManualMicAutoSendAndSingleTapPermissionCallback() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        shadowApp.clearNextStartedActivities()

        val viewModel = com.example.ui.MainViewModel(app)
        var simulatedClockMs = 50_000L
        viewModel.handsFreeToggleClockMs = { simulatedClockMs }
        viewModel.resetHandsFreeToggleDebounceForTest()

        // 1. Start with microphone permission DENIED and hands-free OFF
        if (viewModel.isHandsFreeActive.value) {
            viewModel.toggleHandsFreeVoice(false)
            simulatedClockMs += 500L
        }
        viewModel.setPermissionGrantedOverrideForTest(false)
        assertFalse(viewModel.hasRecordAudioPermission.value)

        // User taps the Hands-Free Radar toggle once -> requests permission and sets pendingWakeStartAfterPermission
        viewModel.toggleHandsFreeVoice(true)
        assertTrue(viewModel.requiresAudioPermission.value)

        // Simulate system permission dialog causing temporary window focus loss, then user taps "Allow"
        viewModel.onWindowFocusChanged(false)
        viewModel.setPermissionGrantedOverrideForTest(true)
        viewModel.onAudioPermissionResult(true)

        // Verify that granting permission on the FIRST tap immediately enables handsFreeActive and restores window focus
        assertTrue("Window focus must be restored upon permission grant", viewModel.hasWindowFocus.value)
        assertTrue("Hands-Free Radar must activate on the first tap once permission is granted", viewModel.isHandsFreeActive.value)
        assertFalse(viewModel.speechRecognitionCoordinator.isIntentionallyPaused)

        // 2. Verify Manual In-Chat Mic Button uses internal speechRecognitionCoordinator (no external Activity intent)
        //    updates live partial transcript, and auto-sends on final speech result
        val startedDictation = viewModel.toggleInChatDictation()
        assertTrue("Manual mic button must start in-chat dictation in-app", startedDictation)
        assertTrue(viewModel.isInChatDictationActive.value)
        org.junit.Assert.assertNull(
            "Manual mic button must never launch an external RecognizerIntent Activity",
            shadowApp.nextStartedActivity
        )

        // Partial results update wakeWordManager.lastHeardText in real time for the composer text field
        val partialBundle = android.os.Bundle().apply {
            putStringArrayList(
                android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("Drafting architecture")
            )
        }
        viewModel.wakeRecognitionListener.onPartialResults(partialBundle)
        assertEquals("Drafting architecture", viewModel.wakeWordManager.lastHeardText.value)
        assertEquals("", viewModel.currentDraftText.value)

        // Final results auto-send the recognized utterance and end in-chat dictation
        val finalBundle = android.os.Bundle().apply {
            putStringArrayList(
                android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf("Drafting architecture summary now")
            )
        }
        viewModel.wakeRecognitionListener.onResults(finalBundle)
        assertFalse("In-chat dictation must finish and auto-send when utterance completes", viewModel.isInChatDictationActive.value)
        assertEquals("Drafting architecture summary now", viewModel.lastDispatchedVoicePrompt.value)

        simulatedClockMs += 500L
        viewModel.toggleHandsFreeVoice(false)
        WakeWordVoiceService.resumeMicrophoneAfterCommandNavigation(app)
        viewModel.selectChat(null)
        viewModel.cleanShutdown(null)
    }

    @Test
    fun testWindowFocus1500msDebounce_toggleInternalDictation_andSendMessageErrorRecovery() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadowApp = org.robolectric.Shadows.shadowOf(app)
        shadowApp.clearNextStartedActivities()

        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)
        viewModel.onWindowFocusChanged(true)

        // 1. Verify 1500ms windowFocus debounce constant and transient focus loss behavior
        assertEquals(1500L, SpeechRecognitionCoordinator.WINDOW_FOCUS_DEBOUNCE_MS)
        assertEquals(1500L, WakeWordManager.WINDOW_FOCUS_DEBOUNCE_MS)

        val initialRecreations = viewModel.speechRecognitionCoordinator.recognizerRecreationCount
        viewModel.onWindowFocusChanged(false)
        // Simulate transient notification shade pull-down (< 1500ms) followed by focus restoration
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            .idleFor(java.time.Duration.ofMillis(400))
        viewModel.onWindowFocusChanged(true)
        assertEquals(
            "Transient windowFocus loss (< 1500ms) must NEVER recreate SpeechRecognizer",
            initialRecreations,
            viewModel.speechRecognitionCoordinator.recognizerRecreationCount
        )

        // 2. Verify toggleInternalDictation() uses internal coordinator and never launches an external Activity or Service Intent
        shadowApp.clearNextStartedActivities()
        val started = viewModel.toggleInternalDictation()
        assertTrue(started)
        assertTrue(viewModel.isInChatDictationActive.value)
        org.junit.Assert.assertNull(
            "toggleInternalDictation() must never launch an external Activity",
            shadowApp.nextStartedActivity
        )
        val stopped = viewModel.toggleInternalDictation()
        assertFalse(stopped)
        assertFalse(viewModel.isInChatDictationActive.value)

        // 3. Verify sendMessage() catches Gemini API errors, prints error message into chat, and immediately sets isGenerating = false
        viewModel.geminiServiceOverrideForTest = object : com.example.ai.GeminiService() {
            override fun streamChatResponse(
                context: android.content.Context?,
                systemInstruction: String,
                history: List<com.example.data.model.ChatMessage>,
                userPrompt: String,
                userAudioBytes: ByteArray?,
                attachmentBytes: ByteArray?,
                attachmentMimeType: String?,
                requestAudio: Boolean,
                onAudioChunk: ((ByteArray) -> Unit)?
            ): kotlinx.coroutines.flow.Flow<String> = kotlinx.coroutines.flow.flow {
                throw IllegalStateException("Simulated Gemini API network failure")
            }
        }

        val chatId = kotlinx.coroutines.runBlocking {
            viewModel.repository.saveChat(
                com.example.data.model.ChatSession(
                    title = "Spinner Recovery Test Chat",
                    wakeWord = "SpinnerTest",
                    systemInstruction = "Test assistant"
                )
            )
        }
        viewModel.selectChat(chatId)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        viewModel.sendMessage(chatId = chatId, text = "Hello typed message", triggeredByWakeWord = false)

        val deadline = System.currentTimeMillis() + 3000L
        while (viewModel.isGenerating.value && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse(
            "sendMessage() must immediately reset isGenerating = false when API throws",
            viewModel.isGenerating.value
        )
        assertTrue(
            "sendMessage() must populate errorMessage on API failure",
            viewModel.errorMessage.value?.contains("Simulated Gemini API network failure") == true
        )
        val chatMessages = kotlinx.coroutines.runBlocking {
            viewModel.messageRepository.getMessagesForChatSync(chatId)
        }
        assertTrue(
            "sendMessage() must print the caught error directly into the chat thread",
            chatMessages.any { it.sender == "gemini" && it.content.contains("⚠️ Error: Simulated Gemini API network failure") }
        )

        viewModel.geminiServiceOverrideForTest = null
        viewModel.selectChat(null)
        viewModel.cleanShutdown(null)
    }

    @Test
    fun testToggleHandsFreeVoiceSynchronizesWakeWordVoiceServiceIntentionallyPausedFlag() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)
        viewModel.setPermissionGrantedOverrideForTest(true)

        // Simulate service previously paused in memory (e.g. via internal dictation or manual pause)
        WakeWordVoiceService.pauseForInternalDictationInMemory()
        WakeWordVoiceService.setIntentionallyPaused(true)
        assertTrue(WakeWordVoiceService.isIntentionallyPaused)
        assertTrue(WakeWordVoiceService.isInputFocusPaused.value)

        // Toggling Hands-Free ON must clear WakeWordVoiceService.isIntentionallyPaused and dictation pause
        viewModel.resetHandsFreeToggleDebounceForTest()
        viewModel.toggleHandsFreeVoice(enabled = true)
        assertFalse(
            "toggleHandsFreeVoice(true) must set WakeWordVoiceService.isIntentionallyPaused to false",
            WakeWordVoiceService.isIntentionallyPaused
        )
        assertFalse(
            "toggleHandsFreeVoice(true) must clear WakeWordVoiceService.isInputFocusPaused",
            WakeWordVoiceService.isInputFocusPaused.value
        )

        // Toggling Hands-Free OFF must set WakeWordVoiceService.isIntentionallyPaused to true
        viewModel.resetHandsFreeToggleDebounceForTest()
        viewModel.toggleHandsFreeVoice(enabled = false)
        assertTrue(
            "toggleHandsFreeVoice(false) must set WakeWordVoiceService.isIntentionallyPaused to true",
            WakeWordVoiceService.isIntentionallyPaused
        )

        // Re-enable Hands-Free, then test window focus regain & diagnostic mutex reset unpausing
        viewModel.resetHandsFreeToggleDebounceForTest()
        viewModel.toggleHandsFreeVoice(enabled = true)
        WakeWordVoiceService.setIntentionallyPaused(true)
        viewModel.onWindowFocusChanged(true)
        assertFalse(
            "onWindowFocusChanged(true) while Hands-Free is active and not processing must unpause WakeWordVoiceService",
            WakeWordVoiceService.isIntentionallyPaused
        )

        WakeWordVoiceService.setIntentionallyPaused(true)
        viewModel.resetDiagnosticAudioMutexLocks()
        assertFalse(
            "resetDiagnosticAudioMutexLocks() must unpause WakeWordVoiceService",
            WakeWordVoiceService.isIntentionallyPaused
        )

        viewModel.cleanShutdown(null)
    }
}


