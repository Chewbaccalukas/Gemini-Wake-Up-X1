package com.example.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

private const val TAG = "SnippetAudioPlayer"

/**
 * AudioTrack + MediaPlayer player for auditioning and previewing wake word snippets
 * (.wav, .pcm, and .m4a files or in-memory transition ring buffers) in the training screen,
 * providing playback progress, duration tracking, pause/resume, and sequential playback ("Audition All").
 */
class SnippetAudioPlayer(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
) {

    private var audioTrack: AudioTrack? = null
    private var audioTrackJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null
    private var sequenceIndex: Int = 0
    private var currentPlaylist: List<File> = emptyList()

    @Volatile
    var lastPlayedPcmBytes: ByteArray = ByteArray(0)
        private set

    private val _currentPlayingPath = MutableStateFlow<String?>(null)
    val currentPlayingPath: StateFlow<String?> = _currentPlayingPath.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0)
    val playbackPositionMs: StateFlow<Int> = _playbackPositionMs.asStateFlow()

    private val _playbackDurationMs = MutableStateFlow(0)
    val playbackDurationMs: StateFlow<Int> = _playbackDurationMs.asStateFlow()

    private var currentVolume: Float = 1.0f
    val volume: Float
        get() = currentVolume

    val isPlaying: Boolean
        get() = _currentPlayingPath.value != null && !_isPaused.value

    /**
     * Plays raw 16-bit Little-Endian mono PCM or RIFF/WAVE bytes directly through AudioTrack
     * at [sampleRate] Hz so recorded wake snippets and transition buffers are clearly audible.
     */
    @Synchronized
    fun playPcmOrWavBytes(
        bytes: ByteArray,
        sampleRate: Int = WakeWordManager.SAMPLE_RATE_HZ,
        label: String = "snippet_buffer",
        onComplete: () -> Unit = {}
    ) {
        stop()

        val rawPcm = WakeWordManager.extractPcmFromWavOrRaw(bytes)
        if (rawPcm.isEmpty()) {
            Log.w(TAG, "Cannot play PCM/WAV snippet; buffer is empty")
            return
        }

        lastPlayedPcmBytes = rawPcm
        val bytesPerMs = ((sampleRate * 2) / 1000).coerceAtLeast(1)
        val totalDurationMs = (rawPcm.size / bytesPerMs).coerceAtLeast(1)

        _currentPlayingPath.value = label
        _isPaused.value = false
        _playbackDurationMs.value = totalDurationMs
        _playbackPositionMs.value = 0
        _playbackProgress.value = 0f

        try {
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBuf, rawPcm.size.coerceAtMost(sampleRate * 2), 4096)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            track.setVolume(currentVolume)
            track.play()
            audioTrack = track
        } catch (e: Exception) {
            Log.w(TAG, "AudioTrack hardware init fallback during snippet playback: ${e.message}")
        }

        audioTrackJob = scope.launch {
            val chunkBytes = (sampleRate * 2 / 25).coerceAtLeast(640) // ~40ms chunks
            var offset = 0
            while (isActive && offset < rawPcm.size && _currentPlayingPath.value == label) {
                if (_isPaused.value) {
                    delay(40)
                    continue
                }
                val toWrite = (rawPcm.size - offset).coerceAtMost(chunkBytes)
                try {
                    audioTrack?.write(rawPcm, offset, toWrite)
                } catch (e: Exception) {
                    Log.w(TAG, "AudioTrack write warning: ${e.message}")
                }
                offset += toWrite
                val currentMs = (offset / bytesPerMs).coerceAtMost(totalDurationMs)
                _playbackPositionMs.value = currentMs
                _playbackProgress.value = (offset.toFloat() / rawPcm.size.toFloat()).coerceIn(0f, 1f)
                delay(35)
            }
            if (isActive && _currentPlayingPath.value == label) {
                _playbackPositionMs.value = totalDurationMs
                _playbackProgress.value = 1f
                stop()
                onComplete()
            }
        }
    }

    /**
     * Plays the audio snippet from [file]. Uses AudioTrack for .wav / .pcm / RIFF files
     * and falls back to MediaPlayer for compressed .m4a files.
     */
    @Synchronized
    fun play(file: File, onComplete: () -> Unit = {}) {
        stop()

        if (!file.exists() || file.length() == 0L) {
            Log.w(TAG, "Cannot play snippet; file missing or empty: ${file.absolutePath}")
            return
        }

        val lowerName = file.name.lowercase(Locale.US)
        val rawBytes = try {
            file.readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read snippet file: ${file.absolutePath}", e)
            ByteArray(0)
        }
        val isWavHeader = rawBytes.size >= 12 &&
            rawBytes[0] == 'R'.code.toByte() &&
            rawBytes[1] == 'I'.code.toByte() &&
            rawBytes[2] == 'F'.code.toByte() &&
            rawBytes[3] == 'F'.code.toByte()

        if (lowerName.endsWith(".wav") || lowerName.endsWith(".pcm") || isWavHeader) {
            playPcmOrWavBytes(
                bytes = rawBytes,
                sampleRate = WakeWordManager.SAMPLE_RATE_HZ,
                label = file.absolutePath,
                onComplete = onComplete
            )
            return
        }

        try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    stop()
                    onComplete()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    stop()
                    true
                }
                prepare()
                setVolume(currentVolume, currentVolume)
                start()
            }
            mediaPlayer = player
            _currentPlayingPath.value = file.absolutePath
            _isPaused.value = false
            _playbackDurationMs.value = player.duration.coerceAtLeast(0)
            _playbackPositionMs.value = 0
            _playbackProgress.value = 0f

            startProgressTracker()
            Log.d(TAG, "Started playback preview: ${file.name} (duration=${player.duration}ms)")
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer failed on ${file.name}, falling back to AudioTrack PCM playback: ${e.message}")
            if (rawBytes.isNotEmpty()) {
                playPcmOrWavBytes(
                    bytes = rawBytes,
                    sampleRate = WakeWordManager.SAMPLE_RATE_HZ,
                    label = file.absolutePath,
                    onComplete = onComplete
                )
            } else {
                stop()
            }
        }
    }

    /**
     * Plays a sequence of audio snippet files sequentially with a short pause between them.
     */
    @Synchronized
    fun playSequence(files: List<File>, onComplete: () -> Unit = {}) {
        stop()
        val validFiles = files.filter { it.exists() && it.length() > 0 }
        if (validFiles.isEmpty()) {
            return
        }

        currentPlaylist = validFiles
        sequenceIndex = 0

        fun playNext() {
            if (sequenceIndex < currentPlaylist.size) {
                val nextFile = currentPlaylist[sequenceIndex]
                sequenceIndex++
                play(nextFile) {
                    scope.launch {
                        delay(200) // Brief natural pause between wake word samples
                        playNext()
                    }
                }
            } else {
                stop()
                onComplete()
            }
        }

        playNext()
    }

    /**
     * Pauses current playback.
     */
    @Synchronized
    fun pause() {
        try {
            if (audioTrack != null || audioTrackJob != null) {
                _isPaused.value = true
                try {
                    audioTrack?.pause()
                } catch (_: Exception) {}
                return
            }
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.pause()
                    _isPaused.value = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing playback", e)
        }
    }

    /**
     * Resumes paused playback.
     */
    @Synchronized
    fun resume() {
        try {
            if (audioTrack != null || audioTrackJob != null) {
                if (_isPaused.value) {
                    _isPaused.value = false
                    try {
                        audioTrack?.play()
                    } catch (_: Exception) {}
                }
                return
            }
            mediaPlayer?.let { player ->
                if (_isPaused.value) {
                    player.start()
                    _isPaused.value = false
                    startProgressTracker()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error resuming playback", e)
        }
    }

    /**
     * Updates the playback volume (0.0 to 1.0).
     */
    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        currentVolume = clamped
        try {
            audioTrack?.setVolume(clamped)
            mediaPlayer?.setVolume(clamped, clamped)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting volume", e)
        }
    }

    /**
     * Stops any in-progress snippet playback.
     */
    @Synchronized
    fun stop() {
        progressJob?.cancel()
        progressJob = null
        audioTrackJob?.cancel()
        audioTrackJob = null
        try {
            audioTrack?.let { track ->
                try {
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING ||
                        track.playState == AudioTrack.PLAYSTATE_PAUSED
                    ) {
                        track.stop()
                    }
                } catch (_: Exception) {}
                try {
                    track.release()
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioTrack", e)
        } finally {
            audioTrack = null
        }

        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.reset()
                player.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping MediaPlayer", e)
        } finally {
            mediaPlayer = null
            _currentPlayingPath.value = null
            _isPaused.value = false
            _playbackProgress.value = 0f
            _playbackPositionMs.value = 0
            _playbackDurationMs.value = 0
            currentPlaylist = emptyList()
            sequenceIndex = 0
        }
    }

    /**
     * Releases AudioTrack and MediaPlayer resources.
     */
    @Synchronized
    fun release() {
        stop()
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && mediaPlayer != null && _currentPlayingPath.value != null && !_isPaused.value) {
                val player = mediaPlayer ?: break
                try {
                    if (player.isPlaying) {
                        val current = player.currentPosition
                        val total = player.duration.coerceAtLeast(1)
                        _playbackPositionMs.value = current
                        _playbackDurationMs.value = total
                        _playbackProgress.value = (current.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    }
                } catch (_: Exception) {
                    break
                }
                delay(40)
            }
        }
    }
}
