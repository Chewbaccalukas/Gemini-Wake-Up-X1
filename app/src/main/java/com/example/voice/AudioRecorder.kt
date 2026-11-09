package com.example.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs

private const val TAG = "AudioRecorder"

/**
 * Lifecycle-safe audio recorder that captures 16 kHz 16-bit mono PCM audio from
 * MediaRecorder.AudioSource.MIC (with fallback to UNPROCESSED / VOICE_RECOGNITION).
 *
 * Uses a non-blocking, thread-safe ring buffer ([audioPipelineBuffer]) backed by a
 * `Channel<ByteArray>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)`
 * exposed via `consumeAsFlow()` ([audioPipelineFlow]) so the real-time `AudioRecord.read()`
 * thread immediately pushes raw PCM chunks via `trySend` without blocking or dropping frames.
 */
class AudioRecorder(private val context: Context) {

    companion object {
        /** Ring buffer capacity (32 PCM chunks) with DROP_OLDEST overflow policy. */
        const val AUDIO_PIPELINE_BUFFER_CAPACITY = 32
    }

    sealed class State {
        object Idle : State()
        data class Recording(val outputFile: File, val startedAt: Long) : State()
        data class Error(val message: String, val cause: Throwable? = null) : State()
    }

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var mediaRecorder: MediaRecorder? = null
    private var currentOutputFile: File? = null

    private val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var accumulatorJob: Job? = null

    /**
     * Thread-safe ring buffer channel (`capacity = 32`, `BufferOverflow.DROP_OLDEST`) acting as
     * the primary `AudioPipelineBuffer` for raw PCM byte chunks read from [AudioRecord].
     */
    var audioPipelineBuffer: Channel<ByteArray> = createPipelineBufferChannel()
        private set

    /**
     * Exposes [audioPipelineBuffer] as a cold/consumable [Flow] via [consumeAsFlow] for
     * downstream audio pipeline components.
     */
    var audioPipelineFlow: Flow<ByteArray> = audioPipelineBuffer.consumeAsFlow()
        private set

    /**
     * Secondary non-blocking internal channel used to drain PCM chunks into [pcmByteStream]
     * on a background I/O coroutine without competing with downstream consumers of [audioPipelineFlow].
     */
    private val internalSnippetChannel: Channel<ByteArray> = Channel(
        capacity = AUDIO_PIPELINE_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val pcmBufferLock = Any()
    private val pcmByteStream = ByteArrayOutputStream()

    /**
     * Guard flag indicating whether microphone capture is intentionally paused (e.g., when the app
     * enters Sleep Mode). When `true`, the [AudioRecord.read] loop exits immediately and releases
     * the hardware microphone cleanly.
     */
    @Volatile
    var isIntentionallyPaused: Boolean = false
        private set

    @Volatile
    private var isCapturingPcm = false

    @Volatile
    private var lastMaxAmplitude = 0

    @Volatile
    var lastRecordedPcmBytes: ByteArray = ByteArray(0)
        private set

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    val isRecording: Boolean
        get() = _state.value is State.Recording

    init {
        startInternalSnippetDrainer()
    }

    private fun createPipelineBufferChannel(): Channel<ByteArray> = Channel(
        capacity = AUDIO_PIPELINE_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Resets and recreates [audioPipelineBuffer] and [audioPipelineFlow] if a previous downstream
     * collector cancelled the channel.
     */
    @Synchronized
    fun resetAudioPipelineBuffer(): Flow<ByteArray> {
        audioPipelineBuffer.close()
        audioPipelineBuffer = createPipelineBufferChannel()
        audioPipelineFlow = audioPipelineBuffer.consumeAsFlow()
        return audioPipelineFlow
    }

    /**
     * Updates [isIntentionallyPaused]. When `paused == true`, immediately stops the recording
     * loop and releases the microphone hardware cleanly.
     */
    fun setIntentionallyPaused(paused: Boolean) {
        isIntentionallyPaused = paused
        if (paused) {
            Log.d(TAG, "isIntentionallyPaused=true -> releasing microphone hardware cleanly")
            stopAndReleaseHardware()
        }
    }

    private fun startInternalSnippetDrainer() {
        accumulatorJob?.cancel()
        accumulatorJob = recorderScope.launch {
            for (chunk in internalSnippetChannel) {
                synchronized(pcmBufferLock) {
                    pcmByteStream.write(chunk, 0, chunk.size)
                    lastRecordedPcmBytes = pcmByteStream.toByteArray()
                }
            }
        }
    }

    /**
     * Non-blockingly pushes a raw 16-bit Little-Endian PCM chunk into [audioPipelineBuffer]
     * via `trySend` (ring buffer with `capacity = 32`, `DROP_OLDEST`).
     */
    fun pushPcmChunkToPipeline(pcmChunk: ByteArray, bytesRead: Int = pcmChunk.size): Boolean {
        if (isIntentionallyPaused || bytesRead <= 0 || pcmChunk.isEmpty()) return false
        val limit = bytesRead.coerceAtMost(pcmChunk.size)
        val exactChunk = if (limit == pcmChunk.size) pcmChunk else pcmChunk.copyOf(limit)

        // Update peak amplitude metric without blocking
        var maxSample = 0
        var i = 0
        while (i + 1 < exactChunk.size) {
            val low = exactChunk[i].toInt() and 0xFF
            val high = exactChunk[i + 1].toInt()
            val sample = abs(((high shl 8) or low).toShort().toInt())
            if (sample > maxSample) maxSample = sample
            i += 2
        }
        if (maxSample > lastMaxAmplitude) {
            lastMaxAmplitude = maxSample
        }

        // 1. Immediately push into the primary AudioPipelineBuffer (Channel<ByteArray>, capacity=32, DROP_OLDEST)
        val sendResult = audioPipelineBuffer.trySend(exactChunk)

        // 2. Also offer to internal snippet writer channel
        internalSnippetChannel.trySend(exactChunk)

        return sendResult.isSuccess
    }

    /**
     * Appends incoming 16-bit Little-Endian PCM bytes into both the ring buffer pipeline
     * and the active snippet buffer.
     */
    fun appendPcmBytes(pcmBytes: ByteArray, bytesRead: Int = pcmBytes.size) {
        if (bytesRead <= 0 || pcmBytes.isEmpty()) return
        val limit = bytesRead.coerceAtMost(pcmBytes.size)
        val exactChunk = if (limit == pcmBytes.size) pcmBytes else pcmBytes.copyOf(limit)
        synchronized(pcmBufferLock) {
            pcmByteStream.write(exactChunk, 0, exactChunk.size)
            lastRecordedPcmBytes = pcmByteStream.toByteArray()
        }
        pushPcmChunkToPipeline(exactChunk, exactChunk.size)
    }

    /**
     * Starts recording a voice snippet to the designated [outputFile] using 16 kHz 16-bit mono
     * PCM from MediaRecorder.AudioSource.MIC (or MediaRecorder fallback).
     */
    @Synchronized
    fun start(outputFile: File): Result<Unit> {
        if (isIntentionallyPaused) {
            val pausedErr = IllegalStateException("AudioRecorder is intentionally paused (sleep mode)")
            Log.d(TAG, "Aborting start(): isIntentionallyPaused=true")
            stopAndReleaseHardware()
            return Result.failure(pausedErr)
        }

        return try {
            val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasPermission) {
                val err = SecurityException("RECORD_AUDIO permission not granted")
                _state.value = State.Error("Microphone permission required", err)
                return Result.failure(err)
            }

            if (isRecording) {
                cancel()
            }

            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) {
                outputFile.delete()
            }

            synchronized(pcmBufferLock) {
                pcmByteStream.reset()
            }
            lastMaxAmplitude = 0
            currentOutputFile = outputFile

            val minBuf = AudioRecord.getMinBufferSize(
                WakeWordManager.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = WakeWordManager.calculateBufferSize(minBuf)
            val initializedRecord = createMicAudioRecord(bufferSize)

            if (initializedRecord != null && initializedRecord.state == AudioRecord.STATE_INITIALIZED) {
                val startedSuccessfully = try {
                    if (initializedRecord.state == AudioRecord.STATE_INITIALIZED) {
                        initializedRecord.startRecording()
                    }
                    initializedRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "IllegalStateException starting AudioRecord in AudioRecorder", e)
                    false
                } catch (t: Throwable) {
                    Log.e(TAG, "Native failure starting AudioRecord in AudioRecorder", t)
                    false
                }

                if (startedSuccessfully) {
                    audioRecord = initializedRecord
                    WakeWordVoiceService.registerOwnAudioSessionId(initializedRecord.audioSessionId)
                    WakeWordVoiceService.isAppRecordingActive = true
                    isCapturingPcm = true
                    _state.value = State.Recording(outputFile, System.currentTimeMillis())

                    captureThread = thread(start = true, name = "Snippet-PCM-Recorder") {
                        val byteBuf = ByteArray(bufferSize)
                        try {
                            while (isCapturingPcm && !isIntentionallyPaused) {
                                if (!hasRecordAudioPermission()) {
                                    Log.w(TAG, "RECORD_AUDIO permission revoked during capture — stopping")
                                    break
                                }
                                val rec = audioRecord ?: break
                                if (rec.state != AudioRecord.STATE_INITIALIZED ||
                                    rec.recordingState != AudioRecord.RECORDSTATE_RECORDING
                                ) {
                                    break
                                }
                                val bytesRead = try {
                                    if (rec.state == AudioRecord.STATE_INITIALIZED) {
                                        rec.read(byteBuf, 0, byteBuf.size)
                                    } else {
                                        AudioRecord.ERROR_INVALID_OPERATION
                                    }
                                } catch (e: IllegalStateException) {
                                    Log.w(TAG, "IllegalStateException reading AudioRecord: ${e.message}")
                                    break
                                } catch (_: Throwable) {
                                    break
                                }
                                if (bytesRead < 0) {
                                    // Abort on native read error (-20 / NO_INIT / ERROR_BAD_VALUE / ERROR_DEAD_OBJECT)
                                    Log.w(TAG, "AudioRecorder read returned error code $bytesRead — aborting loop")
                                    break
                                }
                                if (bytesRead > 0) {
                                    val chunk = byteBuf.copyOf(bytesRead)
                                    // Immediately push raw PCM byte chunk into the non-blocking ring buffer channel
                                    pushPcmChunkToPipeline(chunk, bytesRead)
                                }
                            }
                        } finally {
                            if (isIntentionallyPaused) {
                                releaseRecorder()
                                if (_state.value is State.Recording) {
                                    _state.value = State.Idle
                                }
                            }
                        }
                    }
                    Log.d(TAG, "AudioRecorder started 16kHz PCM capture (MIC): ${outputFile.name}")
                    return Result.success(Unit)
                } else {
                    try {
                        initializedRecord.release()
                    } catch (_: Throwable) {}
                }
            }

            // Fallback to MediaRecorder if AudioRecord was unavailable
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(64_000)
                setAudioSamplingRate(16_000)
                setAudioChannels(1)
                setOutputFile(outputFile.absolutePath)
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaRecorder error: what=$what, extra=$extra")
                    _state.value = State.Error("MediaRecorder runtime error ($what, $extra)")
                }
                prepare()
                start()
            }

            this.mediaRecorder = recorder
            _state.value = State.Recording(outputFile, System.currentTimeMillis())
            Log.d(TAG, "AudioRecorder started MediaRecorder fallback: ${outputFile.name}")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecorder", e)
            releaseRecorder()
            _state.value = State.Error("Failed to start recording: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun hasRecordAudioPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun createMicAudioRecord(bufferSize: Int): AudioRecord? {
        if (!hasRecordAudioPermission() || bufferSize <= 0 || isIntentionallyPaused) return null

        try {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                WakeWordManager.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (rec.state == AudioRecord.STATE_INITIALIZED) {
                return rec
            }
            try {
                rec.release()
            } catch (_: Throwable) {}
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "IllegalArgumentException creating AudioRecord: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.w(TAG, "IllegalStateException creating AudioRecord: ${e.message}")
        } catch (e: UnsupportedOperationException) {
            Log.w(TAG, "UnsupportedOperationException creating AudioRecord: ${e.message}")
        } catch (t: Throwable) {
            Log.w(TAG, "Native error creating AudioRecord: ${t.message}")
        }
        return null
    }

    /**
     * Drains any remaining chunks queued in [internalSnippetChannel] into [pcmByteStream]
     * before finalizing the output file.
     */
    private fun drainPendingSnippetChunks() {
        while (true) {
            val next = internalSnippetChannel.tryReceive().getOrNull() ?: break
            synchronized(pcmBufferLock) {
                pcmByteStream.write(next, 0, next.size)
                lastRecordedPcmBytes = pcmByteStream.toByteArray()
            }
        }
    }

    /**
     * Stops the active recording session, writes captured PCM/WAV bytes to [currentOutputFile],
     * and returns the recorded [File].
     */
    @Synchronized
    fun stop(): Result<File> {
        val activeFile = currentOutputFile
        return try {
            isCapturingPcm = false
            captureThread?.let { t ->
                if (Thread.currentThread() != t) {
                    t.interrupt()
                    try {
                        t.join(150)
                    } catch (_: InterruptedException) {}
                }
            }
            captureThread = null

            drainPendingSnippetChunks()

            val capturedPcm = synchronized(pcmBufferLock) {
                pcmByteStream.toByteArray()
            }
            if (capturedPcm.isNotEmpty()) {
                lastRecordedPcmBytes = capturedPcm
                if (activeFile != null) {
                    val bytesToWrite = if (activeFile.name.endsWith(".pcm", ignoreCase = true)) {
                        capturedPcm
                    } else {
                        WakeWordManager.buildWavFromPcmBytes(capturedPcm, WakeWordManager.SAMPLE_RATE_HZ)
                    }
                    activeFile.outputStream().use { it.write(bytesToWrite) }
                }
            }

            val recorder = mediaRecorder
            if (recorder != null) {
                try {
                    recorder.stop()
                } catch (e: RuntimeException) {
                    Log.w(TAG, "MediaRecorder stop failed (possibly too short): ${e.message}")
                    if (capturedPcm.isEmpty()) {
                        activeFile?.delete()
                        releaseRecorder()
                        _state.value = State.Error("Recording was too short")
                        return Result.failure(e)
                    }
                }
            }
            releaseRecorder()
            _state.value = State.Idle

            if (activeFile != null && activeFile.exists() && activeFile.length() > 0) {
                Log.d(TAG, "AudioRecorder stopped successfully: ${activeFile.name} (${activeFile.length()} bytes)")
                Result.success(activeFile)
            } else {
                val err = IllegalStateException("Recorded audio snippet was empty or missing")
                _state.value = State.Error(err.message ?: "Empty recording")
                Result.failure(err)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecorder", e)
            releaseRecorder()
            _state.value = State.Error("Error stopping recording: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Cancels any active recording and deletes the partial audio file.
     */
    @Synchronized
    fun cancel() {
        isCapturingPcm = false
        captureThread?.interrupt()
        captureThread = null
        try {
            mediaRecorder?.let {
                try {
                    it.stop()
                } catch (_: Exception) {}
            }
        } finally {
            releaseRecorder()
            currentOutputFile?.delete()
            currentOutputFile = null
            _state.value = State.Idle
        }
    }

    /**
     * Returns the maximum absolute amplitude recorded since the last call, or 0.
     * Useful for animating amplitude rings and live recording VU meters.
     */
    fun getMaxAmplitude(): Int {
        return try {
            if (isRecording) {
                val pcmPeak = lastMaxAmplitude
                lastMaxAmplitude = 0
                if (pcmPeak > 0) pcmPeak else (mediaRecorder?.maxAmplitude ?: 0)
            } else {
                0
            }
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Synchronously stops and releases any active AudioRecord or MediaRecorder instance
     * so SpeechRecognizer has uncontested access to the hardware microphone track.
     */
    @Synchronized
    fun stopAndReleaseHardware() {
        isCapturingPcm = false
        val threadToJoin = captureThread
        captureThread = null
        if (threadToJoin != null && Thread.currentThread() != threadToJoin) {
            threadToJoin.interrupt()
            try {
                threadToJoin.join(150)
            } catch (_: InterruptedException) {}
        }
        releaseRecorder()
        if (_state.value is State.Recording) {
            _state.value = State.Idle
        }
    }

    /**
     * Releases hardware recorder resources.
     */
    @Synchronized
    fun release() {
        cancel()
    }

    private fun releaseRecorder() {
        isCapturingPcm = false
        val rec = audioRecord
        audioRecord = null
        WakeWordVoiceService.isAppRecordingActive = false
        if (rec != null) {
            try {
                if (rec.state == AudioRecord.STATE_INITIALIZED) {
                    rec.stop()
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "IllegalStateException stopping AudioRecord in AudioRecorder: ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioRecord in AudioRecorder: ${e.message}")
            }
            try {
                rec.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing AudioRecord in AudioRecorder", e)
            }
        }
        try {
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing MediaRecorder", e)
        } finally {
            mediaRecorder = null
            currentOutputFile = null
        }
    }
}
