package com.example.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.ai.GeminiApiService
import com.example.coupler.GeminiCouplerBridge
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamingVoicePlayerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testVoiceEngineModeDefaultsAndEnumValues() {
        assertEquals(3, VoiceEngineMode.values().size)
        assertEquals(VoiceEngineMode.GEMINI_ORION, VoiceEngineMode.valueOf("GEMINI_ORION"))
        assertEquals(VoiceEngineMode.SYSTEM_TTS, VoiceEngineMode.valueOf("SYSTEM_TTS"))
        assertEquals(VoiceEngineMode.MUTED, VoiceEngineMode.valueOf("MUTED"))
    }

    @Test
    fun testOrionVoiceGenerationConfigStructure() {
        val config = GeminiApiService.buildOrionVoiceGenerationConfig()
        assertNotNull(config)

        // Verify response_modalities contains AUDIO and TEXT
        assertTrue(config.has("response_modalities"))
        val modalities = config.getJSONArray("response_modalities")
        assertEquals(2, modalities.length())
        assertEquals("AUDIO", modalities.getString(0))
        assertEquals("TEXT", modalities.getString(1))

        // Verify speech_config with prebuilt Fenrir voice
        assertTrue(config.has("speech_config"))
        val speechConfig = config.getJSONObject("speech_config")
        val voiceConfig = speechConfig.getJSONObject("voice_config")
        val prebuilt = voiceConfig.getJSONObject("prebuilt_voice_config")
        assertEquals("Fenrir", prebuilt.getString("voice_name"))
    }

    @Test
    fun testCouplerBridgeOrionSpeechConfig() {
        val speechJson = GeminiCouplerBridge.getOrionSpeechConfigJson()
        val parsed = JSONObject(speechJson)

        assertTrue(parsed.has("speech_config"))
        val speechConfig = parsed.getJSONObject("speech_config")
        assertTrue(speechConfig.has("voice_config"))
        val prebuilt = speechConfig.getJSONObject("voice_config").getJSONObject("prebuilt_voice_config")
        assertEquals("Orion", prebuilt.getString("voice_name"))
    }

    @Test
    fun testStreamingVoicePlayerFeedAndBargeInStop() = runTest {
        val player = StreamingVoicePlayer(scope = this)

        // Initially not playing
        assertFalse(player.isPlaying.value)

        // Feed synthesized 24kHz PCM chunk
        val sampleChunk = ByteArray(480) { (it % 127).toByte() }
        player.feedPcmChunk(sampleChunk)

        // Playing flag becomes true when audio is fed
        assertTrue(player.isPlaying.value)

        // Barge-In Interruption: user speaks wake/dictate word
        // player.stop() must immediately halt and flush
        player.stop()
        assertFalse(player.isPlaying.value)

        player.release()
    }

    @Test
    fun testStreamingVoicePlayerBase64Feed() = runTest {
        val player = StreamingVoicePlayer(scope = this)

        // Encode 24kHz PCM byte array to Base64
        val rawPcm = ByteArray(240) { 42 }
        val base64Chunk = Base64.getEncoder().encodeToString(rawPcm)

        player.feedBase64Chunk(base64Chunk)
        assertTrue(player.isPlaying.value)

        player.stop()
        assertFalse(player.isPlaying.value)

        player.release()
    }

    @Test
    fun testPlayOrionTestSampleDoesNotCrash() = runTest {
        val player = StreamingVoicePlayer(scope = this)

        // Plays synthesized 24kHz acoustic ping acknowledging test
        player.playOrionTestSample(context)
        assertTrue(player.isPlaying.value)

        player.stop()
        assertFalse(player.isPlaying.value)

        player.release()
    }

    @Test
    fun testBargeInInterruptionHook() = runTest {
        val player = StreamingVoicePlayer(scope = this)
        var bargeInTriggered = false

        val wakeWordManager = WakeWordManager(context)
        wakeWordManager.onBargeInDetected = {
            bargeInTriggered = true
            player.stop()
        }

        // Simulate voice playback active
        player.feedPcmChunk(ByteArray(100) { 1 })
        assertTrue(player.isPlaying.value)

        // Simulate user speaking trigger: barge in fires
        wakeWordManager.onBargeInDetected?.invoke()
        assertTrue(bargeInTriggered)
        assertFalse(player.isPlaying.value)

        player.release()
        wakeWordManager.destroy()
    }

    @Test
    fun testVolumeControlAndClamping() = runTest {
        val voicePlayer = StreamingVoicePlayer(scope = this)
        val snippetPlayer = SnippetAudioPlayer(scope = this)

        assertEquals(1.0f, voicePlayer.volume, 0.001f)
        assertEquals(1.0f, snippetPlayer.volume, 0.001f)

        voicePlayer.setVolume(0.45f)
        snippetPlayer.setVolume(0.45f)
        assertEquals(0.45f, voicePlayer.volume, 0.001f)
        assertEquals(0.45f, snippetPlayer.volume, 0.001f)

        // Verify clamping outside [0.0, 1.0]
        voicePlayer.setVolume(1.5f)
        snippetPlayer.setVolume(-0.2f)
        assertEquals(1.0f, voicePlayer.volume, 0.001f)
        assertEquals(0.0f, snippetPlayer.volume, 0.001f)

        voicePlayer.release()
        snippetPlayer.release()
    }

    @Test
    fun testAudioRecordExplicitConfigurationAndReadLoopGuards() {
        val wakeWordManager = WakeWordManager(context)
        assertEquals(16000, WakeWordManager.SAMPLE_RATE_HZ)
        assertEquals(android.media.AudioFormat.CHANNEL_IN_MONO, WakeWordManager.CHANNEL_CONFIG)
        assertEquals(android.media.AudioFormat.ENCODING_PCM_16BIT, WakeWordManager.AUDIO_ENCODING)
        assertEquals(android.media.MediaRecorder.AudioSource.MIC, WakeWordManager.AUDIO_SOURCE)
        assertEquals(android.media.MediaRecorder.AudioSource.UNPROCESSED, WakeWordManager.UNPROCESSED_AUDIO_SOURCE)
        assertEquals(android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeWordManager.FALLBACK_AUDIO_SOURCE)

        assertEquals(16000, wakeWordManager.sampleRate)
        assertEquals(android.media.AudioFormat.CHANNEL_IN_MONO, wakeWordManager.channelConfig)
        assertEquals(android.media.AudioFormat.ENCODING_PCM_16BIT, wakeWordManager.audioFormat)
        assertEquals(android.media.MediaRecorder.AudioSource.MIC, wakeWordManager.audioSource)

        // Verify dynamic buffer calculation: maxOf(minBuf * 2, 4096)
        assertEquals(4096, WakeWordManager.calculateBufferSize(1280))
        assertEquals(6400, WakeWordManager.calculateBufferSize(3200))
        val minBuf = android.media.AudioRecord.getMinBufferSize(
            16000,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )
        assertEquals(maxOf(minBuf * 2, 4096), WakeWordManager.calculateBufferSize(minBuf))

        // Uninitialized / null AudioRecord must not be read
        assertFalse(WakeWordManager.canReadFromAudioRecord(null))

        // Silence and bytesRead <= 0 guards
        val silentShorts = ShortArray(160) { 0 }
        assertTrue(WakeWordManager.isShortBufferSilent(silentShorts, 160))
        assertTrue(WakeWordManager.isShortBufferSilent(silentShorts, 0))
        assertTrue(WakeWordManager.isShortBufferSilent(silentShorts, -1))
        assertEquals(0f, WakeWordManager.computeRmsDb(silentShorts, 160), 0.0001f)

        val silentBytes = ByteArray(3200) { 0 }
        assertFalse(WakeWordManager.shouldTransmitAudioPacket(0, silentBytes))
        assertFalse(WakeWordManager.shouldTransmitAudioPacket(-1, silentBytes))
        assertFalse(WakeWordManager.shouldTransmitAudioPacket(3200, silentBytes))
        assertFalse(WakeWordManager.shouldTransmitAudioPacket(0, ByteArray(0)))

        // Low-volume non-zero 16-bit PCM sample (even amplitude = 1) must NOT be dropped as silent (> 0 dB)
        val quietSpeechShorts = ShortArray(160) { index -> if (index == 10) 1 else 0 }
        assertFalse(WakeWordManager.isShortBufferSilent(quietSpeechShorts, 160))
        assertTrue(WakeWordManager.computeRmsDb(quietSpeechShorts, 160) > WakeWordManager.SPEECH_DB_THRESHOLD)

        // Non-silent speech buffer passes guard and emits AudioPacket with dynamic timestamp
        val activeShorts = ShortArray(160) { index -> if (index % 2 == 0) 1200 else -1200 }
        assertFalse(WakeWordManager.isShortBufferSilent(activeShorts, 160))

        var capturedPacket: AudioPacket? = null
        wakeWordManager.onAudioPacketCaptured = { pkt -> capturedPacket = pkt }

        // Silent frame must NOT emit a packet
        val dropped = wakeWordManager.processCapturedShortFrame(silentShorts, 160)
        assertEquals(null, dropped)
        assertEquals(null, capturedPacket)

        // Quiet non-zero speech frame emits packet with System.currentTimeMillis() presentation timestamp
        val beforeMs = System.currentTimeMillis()
        val emitted = wakeWordManager.processCapturedShortFrame(quietSpeechShorts, 160)
        val afterMs = System.currentTimeMillis()

        assertNotNull(emitted)
        assertNotNull(capturedPacket)
        assertEquals(320, emitted!!.bytesRead)
        assertTrue(emitted.presentationTimestampMs in beforeMs..afterMs)
        assertTrue(emitted.presentationTimestampMs > 0L)

        wakeWordManager.destroy()
    }

    @Test
    fun testPresentationTimestampResolutionNeverReturnsZero() {
        val before = System.currentTimeMillis()
        val resolvedFromZero = WakeWordManager.resolvePresentationTimestamp(0L)
        val resolvedFromNegative = WakeWordManager.resolvePresentationTimestamp(-500L)
        val after = System.currentTimeMillis()

        assertTrue(resolvedFromZero in before..after)
        assertTrue(resolvedFromNegative in before..after)
        assertTrue(resolvedFromZero > 0L)

        val explicitTime = 1762250000000L
        assertEquals(explicitTime, WakeWordManager.resolvePresentationTimestamp(explicitTime))
    }

    @Test
    fun testStreamingVoicePlayerExplicit24kHzConfigurationAndSilentChunkRejection() = runTest {
        val player = StreamingVoicePlayer(scope = this)
        assertEquals(24000, StreamingVoicePlayer.SAMPLE_RATE_HZ)
        assertEquals(android.media.AudioFormat.CHANNEL_OUT_MONO, StreamingVoicePlayer.CHANNEL_OUT_CONFIG)
        assertEquals(android.media.AudioFormat.ENCODING_PCM_16BIT, StreamingVoicePlayer.AUDIO_ENCODING)
        assertEquals(android.media.AudioTrack.MODE_STREAM, StreamingVoicePlayer.TRANSFER_MODE)

        assertEquals(24000, player.sampleRate)
        assertEquals(android.media.AudioFormat.CHANNEL_OUT_MONO, player.channelConfig)
        assertEquals(android.media.AudioFormat.ENCODING_PCM_16BIT, player.audioEncoding)
        assertEquals(android.media.AudioTrack.MODE_STREAM, player.transferMode)

        // Feeding an all-zero silent buffer should be rejected and not start playback
        player.feedPcmChunk(ByteArray(480) { 0 })
        assertFalse(player.isPlaying.value)

        // Feeding a non-silent 16-bit PCM chunk activates playback and stamps dynamic timestamp
        val nonSilentPcm = ByteArray(480) { idx -> if (idx % 2 == 0) 0x40 else 0x10 }
        val before = System.currentTimeMillis()
        player.feedPcmChunk(nonSilentPcm, 0L) // 0L should resolve to System.currentTimeMillis()
        val after = System.currentTimeMillis()

        assertTrue(player.isPlaying.value)
        assertTrue(player.lastChunkPresentationTimestampMs.value in before..after)

        player.stop()
        player.release()
    }

    @Test
    fun testEmptyVoiceTurnAbortsWithBriefNoticeInMainViewModel() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val viewModel = com.example.ui.MainViewModel(app)

        assertEquals(
            android.media.MediaRecorder.AudioSource.MIC,
            viewModel.recordingAudioSource
        )
        assertTrue(viewModel.calculateRecordingBufferSize() >= 4096)

        // When finishVoiceRecordingAndSend is called with an empty/silent buffer and blank prompt,
        // it must abort the turn and surface the brief notice instead of fabricating 3200 silent bytes.
        viewModel.finishVoiceRecordingAndSend("")
        assertEquals(
            "No speech detected — voice turn cancelled",
            viewModel.wakeWordManager.statusMessage.value
        )
        assertEquals(
            "No speech detected — voice turn cancelled",
            viewModel.errorMessage.value
        )
        viewModel.wakeWordManager.destroy()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}

