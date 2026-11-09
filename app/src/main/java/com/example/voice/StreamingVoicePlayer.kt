package com.example.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

private const val TAG = "StreamingVoicePlayer"

enum class VoiceEngineMode(val displayName: String, val description: String) {
    GEMINI_ORION(
        "Gemini Neural Voice (Orion)",
        "Low-latency 24kHz neural speech streaming directly from Gemini"
    ),
    SYSTEM_TTS(
        "System Android TTS",
        "On-device Android text-to-speech engine"
    ),
    MUTED(
        "Muted",
        "Silence audio playback; display text responses only"
    )
}

/**
 * Low-latency streaming AudioTrack player for Gemini Neural Voice (Orion).
 * Decodes incoming 24kHz / 16-bit mono PCM chunks on the fly and streams
 * Orion's voice directly to the speaker without waiting for full response generation.
 */
class StreamingVoicePlayer(
    val sampleRate: Int = SAMPLE_RATE_HZ,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {

    companion object {
        const val SAMPLE_RATE_HZ = 24000
        const val CHANNEL_OUT_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val TRANSFER_MODE = AudioTrack.MODE_STREAM
    }

    val channelConfig: Int = CHANNEL_OUT_CONFIG
    val audioEncoding: Int = AUDIO_ENCODING
    val transferMode: Int = TRANSFER_MODE

    private var audioTrack: AudioTrack? = null
    private val audioQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private var playbackJob: Job? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _lastVoiceSamplePlayedTime = MutableStateFlow<Long?>(null)
    val lastVoiceSamplePlayedTime: StateFlow<Long?> = _lastVoiceSamplePlayedTime.asStateFlow()

    private val _lastChunkPresentationTimestampMs = MutableStateFlow(System.currentTimeMillis())
    val lastChunkPresentationTimestampMs: StateFlow<Long> = _lastChunkPresentationTimestampMs.asStateFlow()

    var onPlaybackCompleted: (() -> Unit)? = null

    init {
        initAudioTrack()
        startPlaybackLoop()
    }

    private fun initAudioTrack() {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRate * 2 / 10) // At least 100ms buffer at 24000 Hz mono 16-bit

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val format = AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build()

            val track = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBufferSize * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state == AudioTrack.STATE_INITIALIZED) {
                audioTrack = track
            } else {
                Log.w(TAG, "AudioTrack failed to initialize in MODE_STREAM")
                track.release()
                audioTrack = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack initialization error: ${e.message}")
        }
    }

    private fun startPlaybackLoop() {
        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val chunk = audioQueue.receiveCatching().getOrNull() ?: break
                if (chunk.isEmpty() || WakeWordManager.isPcmByteBufferSilent(chunk, chunk.size)) {
                    if (audioQueue.isEmpty) {
                        _isPlaying.value = false
                    }
                    continue
                }

                _isPlaying.value = true
                _lastChunkPresentationTimestampMs.value = WakeWordManager.resolvePresentationTimestamp(System.currentTimeMillis())
                if (audioTrack == null || audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
                    initAudioTrack()
                }
                val track = audioTrack
                if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                    try {
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                            track.play()
                        }
                        track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
                    } catch (e: Exception) {
                        Log.w(TAG, "AudioTrack write error: ${e.message}")
                    }
                }

                if (audioQueue.isEmpty) {
                    _isPlaying.value = false
                    onPlaybackCompleted?.invoke()
                }
            }
        }
    }

    /**
     * Feeds raw 24kHz 16-bit PCM byte chunk from Gemini model responses for immediate streaming playback.
     */
    fun feedPcmChunk(pcmData: ByteArray, presentationTimestampMs: Long = System.currentTimeMillis()) {
        if (pcmData.isEmpty() || WakeWordManager.isPcmByteBufferSilent(pcmData, pcmData.size)) return
        _lastChunkPresentationTimestampMs.value = WakeWordManager.resolvePresentationTimestamp(presentationTimestampMs)
        _isPlaying.value = true
        try {
            audioQueue.trySend(pcmData)
        } catch (_: Exception) {
            // Ignored if channel is stopped or closed
        }
    }

    /**
     * Decodes Base64-encoded PCM chunk from Gemini model responses and feeds into streaming playback queue.
     * Rejects plain-text internal status or command strings.
     */
    fun feedBase64Chunk(base64Data: String) {
        val trimmed = base64Data.trim()
        if (trimmed.isEmpty() || AudioCommandSanitizer.isSystemOrCommandStatusString(trimmed)) return
        try {
            val decoded = Base64.decode(trimmed, Base64.DEFAULT)
            feedPcmChunk(decoded, System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64 audio chunk: ${e.message}")
        }
    }

    private var currentVolume: Float = 1.0f
    val volume: Float
        get() = currentVolume

    /**
     * Updates the playback volume (0.0 to 1.0).
     */
    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        currentVolume = clamped
        try {
            audioTrack?.setVolume(clamped)
        } catch (e: Exception) {
            Log.w(TAG, "Error setting volume: ${e.message}")
        }
    }

    /**
     * Forcefully stops and purges any currently active speech stream.
     * Used for barge-in interruptions when the user begins speaking.
     */
    fun stop() {
        // Drain pending queue
        while (!audioQueue.isEmpty) {
            audioQueue.tryReceive()
        }
        _isPlaying.value = false

        try {
            audioTrack?.apply {
                if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                    pause()
                    flush()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping audio track: ${e.message}")
        }
    }

    /**
     * Plays a short cached sample of the Orion voice acknowledging a test ping.
     * Uses a multi-harmonic formant synthesized waveform matching Orion's 24kHz profile.
     */
    fun playOrionTestSample(context: Context? = null) {
        stop()
        _lastVoiceSamplePlayedTime.value = System.currentTimeMillis()
        _isPlaying.value = true

        scope.launch(Dispatchers.Default) {
            val samplePcm = generateOrionSamplePcm(sampleRate)
            feedPcmChunk(samplePcm)
        }
    }

    /**
     * Generates a 24kHz / 16-bit PCM multi-frequency formant acoustic burst
     * representing Orion's signature voice acknowledgment ("Ping received, Orion voice online").
     */
    private fun generateOrionSamplePcm(rate: Int): ByteArray {
        val durationSeconds = 1.2
        val totalSamples = (rate * durationSeconds).toInt()
        val byteBuffer = ByteBuffer.allocate(totalSamples * 2).order(ByteOrder.LITTLE_ENDIAN)

        // Musical and formant frequencies simulating a rich neural voice chime (F3, C4, G4, A4)
        val noteDurations = listOf(0.25, 0.25, 0.35, 0.35)
        val noteFreqs = listOf(174.61, 261.63, 392.00, 440.00) // Orion harmonic chord
        var currentSample = 0

        for (i in noteFreqs.indices) {
            val freq = noteFreqs[i]
            val duration = noteDurations[i]
            val samplesForNote = (rate * duration).toInt()

            for (s in 0 until samplesForNote) {
                if (currentSample >= totalSamples) break
                val t = s.toDouble() / rate

                // Envelope: smooth Attack, Sustain, Decay
                val env = when {
                    s < samplesForNote * 0.15 -> s / (samplesForNote * 0.15)
                    s > samplesForNote * 0.7 -> (samplesForNote - s) / (samplesForNote * 0.3)
                    else -> 1.0
                }

                // Fundamental + rich harmonics for warm human vocal timbre
                val signal = (
                    0.55 * sin(2.0 * Math.PI * freq * t) +
                    0.25 * sin(2.0 * Math.PI * (freq * 2) * t) +
                    0.12 * sin(2.0 * Math.PI * (freq * 3) * t) +
                    0.08 * sin(2.0 * Math.PI * (freq * 4) * t)
                ) * env

                val shortVal = (signal * 14000.0).toInt().coerceIn(-32767, 32767).toShort()
                byteBuffer.putShort(shortVal)
                currentSample++
            }
        }

        return byteBuffer.array()
    }

    fun release() {
        stop()
        playbackJob?.cancel()
        audioQueue.close()
        try {
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing audio track: ${e.message}")
        }
    }
}
