package com.example.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import com.example.data.dao.WakeWordDao
import com.example.data.db.AppDatabase
import com.example.data.model.ChatSession
import com.example.data.model.SyncStatus
import com.example.data.model.TriggerType
import com.example.data.model.WakeWord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

private const val TAG = "WakeWordManager"

enum class WakeWordManagerState {
    IDLE,
    PASSIVE_LISTENING,
    ACTIVE_STREAMING
}

data class WakeWordTriggerEvent(
    val chatId: Long,
    val chatTitle: String,
    val matchedWord: String,
    val initialQuery: String?,
    val capturedAudioBytes: ByteArray = ByteArray(0)
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as WakeWordTriggerEvent
        if (chatId != other.chatId) return false
        if (chatTitle != other.chatTitle) return false
        if (matchedWord != other.matchedWord) return false
        if (initialQuery != other.initialQuery) return false
        return capturedAudioBytes.contentEquals(other.capturedAudioBytes)
    }

    override fun hashCode(): Int {
        var result = chatId.hashCode()
        result = 31 * result + chatTitle.hashCode()
        result = 31 * result + matchedWord.hashCode()
        result = 31 * result + (initialQuery?.hashCode() ?: 0)
        result = 31 * result + capturedAudioBytes.contentHashCode()
        return result
    }
}

data class WakeWordLogEntry(
    val wakeWord: String,
    val timestamp: Long = WakeWordManager.resolvePresentationTimestamp(),
    val rmsDb: Float,
    val chatTitle: String
)

data class WakePhraseMatchResult(
    val matchedChat: ChatSession?,
    val matchedWakeWord: String,
    val followUpQuery: String?
)

/**
 * Represents a captured 16kHz 16-bit mono PCM audio packet bound to a dynamic
 * system presentation timestamp (System.currentTimeMillis()).
 */
data class AudioPacket(
    val pcmBytes: ByteArray,
    val bytesRead: Int = pcmBytes.size,
    val presentationTimestampMs: Long = WakeWordManager.resolvePresentationTimestamp()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioPacket
        if (!pcmBytes.contentEquals(other.pcmBytes)) return false
        if (bytesRead != other.bytesRead) return false
        return presentationTimestampMs == other.presentationTimestampMs
    }

    override fun hashCode(): Int {
        var result = pcmBytes.contentHashCode()
        result = 31 * result + bytesRead
        result = 31 * result + presentationTimestampMs.hashCode()
        return result
    }
}

class WakeWordManager(
    private val context: Context,
    private val wakeWordDao: WakeWordDao? = null
) {

    private val resolvedWakeWordDao: WakeWordDao by lazy {
        wakeWordDao ?: AppDatabase.getDatabase(context.applicationContext).wakeWordDao()
    }

    private val audioRecordMutex = Any()
    private var audioRecord: AudioRecord? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var recordingThread: Thread? = null
    @Volatile
    private var isRecording = false

    private val audioManager: AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    val audioFocusGainMode: Int = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE

    private val _hasAudioFocus = MutableStateFlow(false)
    val hasAudioFocus: StateFlow<Boolean> = _hasAudioFocus.asStateFlow()

    private val _isTextInputFocused = MutableStateFlow(false)
    val isTextInputFocused: StateFlow<Boolean> = _isTextInputFocused.asStateFlow()

    private val _isExternalMicActive = MutableStateFlow(false)
    val isExternalMicActive: StateFlow<Boolean> = _isExternalMicActive.asStateFlow()

    @Volatile
    var isTransitioningAudioState: Boolean = false
        private set

    @Volatile
    private var lastStateTransitionMs: Long = 0L

    private val _isUserVoiceTurn = MutableStateFlow(false)
    val isUserVoiceTurn: StateFlow<Boolean> = _isUserVoiceTurn.asStateFlow()

    fun setUserVoiceTurn(active: Boolean) {
        _isUserVoiceTurn.value = active
        if (active) {
            _engineState.value = WakeWordManagerState.ACTIVE_STREAMING
        }
    }

    internal fun setTransitioningAudioStateForTest(transitioning: Boolean) {
        isTransitioningAudioState = transitioning
        WakeWordVoiceService.isTransitioningAudioState = transitioning
    }

    val currentAudioSessionId: Int
        get() = audioRecord?.audioSessionId ?: WakeWordVoiceService.activeAppAudioSessionId

    @Volatile
    private var shouldResumeAfterYield: Boolean = false

    @Volatile
    var isExternalSpeechRecognizerActive: Boolean = false

    fun hasRecordAudioPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Synchronously stops and releases any active AudioRecord instance and joins its reader thread
     * so SpeechRecognizer can acquire the hardware microphone track without AudioFlinger error -20.
     */
    fun stopAndReleaseAudioRecordSynchronously() {
        releaseAudioRecordHardware(stopForegroundService = false)
    }

    fun releaseHardwareForExternalRecognizer() {
        stopAndReleaseAudioRecordSynchronously()
    }

    private val _audioRecordInitFailureCount = AtomicInteger(0)
    val audioRecordInitFailureCount: Int
        get() = _audioRecordInitFailureCount.get()

    private val _activeAudioRecordCount = AtomicInteger(0)
    val activeAudioRecordCount: Int
        get() = _activeAudioRecordCount.get()

    private val _wakeHandoffCount = AtomicInteger(0)
    val wakeHandoffCount: Int
        get() = _wakeHandoffCount.get()

    private val _zeroAmplitudeWarningCount = AtomicInteger(0)
    val zeroAmplitudeWarningCount: Int
        get() = _zeroAmplitudeWarningCount.get()

    private val _consecutiveZeroFrames = AtomicInteger(0)

    private val wakePreRollLock = Any()
    private val wakePreRollStream = ByteArrayOutputStream()

    private val snippetBufferLock = Any()
    private val capturedSnippetPcmStream = ByteArrayOutputStream()

    private val _isLiveDuplexMode = MutableStateFlow(false)
    val isLiveDuplexMode: StateFlow<Boolean> = _isLiveDuplexMode.asStateFlow()

    fun setLiveDuplexMode(enabled: Boolean) {
        _isLiveDuplexMode.value = enabled
        Log.d(TAG, "Live Duplex Mode: ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    val sampleRate: Int = SAMPLE_RATE_HZ
    val channelConfig: Int = CHANNEL_CONFIG
    val audioFormat: Int = AUDIO_ENCODING
    var audioSource: Int = AUDIO_SOURCE
        private set
    var activeBufferSize: Int = MIN_FALLBACK_BUFFER_BYTES
        private set

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isEchoCancelerActive = MutableStateFlow(false)
    val isEchoCancelerActive: StateFlow<Boolean> = _isEchoCancelerActive.asStateFlow()

    private val _audioRmsDb = MutableStateFlow(0f)
    val audioRmsDb: StateFlow<Float> = _audioRmsDb.asStateFlow()

    private val _lastHeardText = MutableStateFlow("")
    val lastHeardText: StateFlow<String> = _lastHeardText.asStateFlow()

    fun clearLastHeardText() {
        _lastHeardText.value = ""
    }

    fun updateLastHeardText(text: String) {
        _lastHeardText.value = text
    }

    fun updateExternalRmsDb(rmsDb: Float) {
        _audioRmsDb.value = rmsDb.coerceIn(0f, 10f)
    }

    fun updateExternalListeningState(listening: Boolean, status: String? = null) {
        _isListening.value = listening
        if (listening && _engineState.value == WakeWordManagerState.IDLE && !_isUserVoiceTurn.value) {
            _engineState.value = WakeWordManagerState.PASSIVE_LISTENING
            _handsFreeState.value = HandsFreeVoiceState.LISTENING
        }
        if (status != null) {
            _statusMessage.value = status
        }
    }

    private val _statusMessage = MutableStateFlow("Tap to start listening")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    fun setStatusMessage(message: String) {
        _statusMessage.value = message
    }

    private val _isSleepMode = MutableStateFlow(false)
    val isSleepMode: StateFlow<Boolean> = _isSleepMode.asStateFlow()

    private val _sleepModeReason = MutableStateFlow("")
    val sleepModeReason: StateFlow<String> = _sleepModeReason.asStateFlow()

    /**
     * Guard flag indicating whether the microphone loop is intentionally paused (e.g., Sleep Mode).
     * When `true`, the [AudioRecord.read] loop exits immediately and releases microphone hardware.
     */
    @Volatile
    var isIntentionallyPaused: Boolean = false
        private set

    fun setIntentionallyPaused(paused: Boolean) {
        isIntentionallyPaused = paused
        if (paused) {
            isRecording = false
            releaseAudioRecordHardware(stopForegroundService = false)
        }
    }

    var onSleepModeChanged: ((Boolean, String) -> Unit)? = null

    /**
     * Enters Sleep Mode (e.g., via "Gemini, sleep" voice command or manual toggle),
     * temporarily disabling wake word triggers and providing visual status feedback.
     */
    fun enterSleepMode(reason: String = "Sleep mode enabled — say \"Gemini, wake up\" or tap Wake Up") {
        isIntentionallyPaused = true
        _isSleepMode.value = true
        _sleepModeReason.value = reason
        _handsFreeState.value = HandsFreeVoiceState.SLEEPING
        _statusMessage.value = "Sleep Mode active — say \"Gemini, wake up\" or tap Wake Up"
        releaseAudioRecordHardware(stopForegroundService = false)
        WakeWordVoiceService.setSleepMode(true)
        onSleepModeChanged?.invoke(true, reason)
    }

    /**
     * Exits Sleep Mode (e.g., via UI tap or "Gemini, wake up" voice command) and resumes listening.
     */
    fun exitSleepMode(reason: String = "Listening resumed") {
        isIntentionallyPaused = false
        _isSleepMode.value = false
        _sleepModeReason.value = ""
        WakeWordVoiceService.setSleepMode(false)
        if (_isHandsFreeMode.value) {
            _handsFreeState.value = HandsFreeVoiceState.LISTENING
        } else {
            _handsFreeState.value = HandsFreeVoiceState.IDLE
        }
        _statusMessage.value = "$reason — say a wake word"
        onSleepModeChanged?.invoke(false, reason)
    }

    /**
     * Evaluates a spoken transcript for "Gemini, sleep" or "Gemini, wake up" voice commands.
     * Returns `true` if a sleep or wake-up command was handled.
     */
    fun evaluateVoiceSleepOrWakeCommand(transcript: String): Boolean {
        val cleaned = transcript.trim()
        if (cleaned.isEmpty()) return false
        if (AudioCommandSanitizer.containsWakeUpCommand(cleaned)) {
            AudioCommandSanitizer.playVoiceCommandHaptic(context, "Wake Up (\"$cleaned\")")
            exitSleepMode("Voice command: \"$cleaned\"")
            return true
        }
        if (AudioCommandSanitizer.containsSleepCommand(cleaned)) {
            AudioCommandSanitizer.playVoiceCommandHaptic(context, "Sleep (\"$cleaned\")")
            enterSleepMode("Voice command: \"$cleaned\"")
            return true
        }
        return false
    }

    private val _lastOutgoingPacketTimestampMs = MutableStateFlow(System.currentTimeMillis())
    val lastOutgoingPacketTimestampMs: StateFlow<Long> = _lastOutgoingPacketTimestampMs.asStateFlow()

    private val _wakeWordLogs = MutableStateFlow<List<WakeWordLogEntry>>(emptyList())
    val wakeWordLogs: StateFlow<List<WakeWordLogEntry>> = _wakeWordLogs.asStateFlow()

    fun clearWakeWordLogs() {
        _wakeWordLogs.value = emptyList()
    }

    private fun recordLog(wakeWord: String, chatTitle: String) {
        val entry = WakeWordLogEntry(
            wakeWord = wakeWord,
            timestamp = resolvePresentationTimestamp(System.currentTimeMillis()),
            rmsDb = _audioRmsDb.value,
            chatTitle = chatTitle
        )
        _wakeWordLogs.update { current ->
            listOf(entry) + current.take(99)
        }
    }

    /**
     * Appends a structured SpeechRecognizer / voice navigation mutex diagnostic trace entry to both
     * Android Logcat and the in-app copyable diagnostic log (`wakeWordLogs`).
     */
    fun appendDiagnosticTraceLog(stage: String, details: String) {
        val thread = Thread.currentThread()
        val isMain = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        val formattedDetails = "[${thread.name}#${thread.id} main=$isMain] $details"
        Log.d(TAG, "[SR-Trace] $stage -> $formattedDetails")
        val entry = WakeWordLogEntry(
            wakeWord = "[SR-Trace] $stage",
            timestamp = resolvePresentationTimestamp(System.currentTimeMillis()),
            rmsDb = _audioRmsDb.value,
            chatTitle = formattedDetails
        )
        _wakeWordLogs.update { current ->
            listOf(entry) + current.take(99)
        }
    }

    private val _triggerEvents = MutableSharedFlow<WakeWordTriggerEvent>(extraBufferCapacity = 4)
    val triggerEvents: SharedFlow<WakeWordTriggerEvent> = _triggerEvents.asSharedFlow()

    /**
     * Thread-safe ring buffer (`Channel<ByteArray>` with `capacity = 32` and
     * `BufferOverflow.DROP_OLDEST`) acting as the `AudioPipelineBuffer` for raw PCM chunks.
     */
    var audioPipelineBuffer: Channel<ByteArray> = Channel(
        capacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
        private set

    /**
     * Exposes [audioPipelineBuffer] as a [Flow] via [consumeAsFlow] for downstream components.
     */
    var audioPipelineFlow: Flow<ByteArray> = audioPipelineBuffer.consumeAsFlow()
        private set

    @Synchronized
    fun resetAudioPipelineBuffer(): Flow<ByteArray> {
        audioPipelineBuffer.close()
        audioPipelineBuffer = Channel(
            capacity = 32,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        audioPipelineFlow = audioPipelineBuffer.consumeAsFlow()
        return audioPipelineFlow
    }

    private val _audioFrameFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 128)
    val audioFrameFlow: SharedFlow<ByteArray> = _audioFrameFlow.asSharedFlow()

    private val _audioPacketFlow = MutableSharedFlow<AudioPacket>(extraBufferCapacity = 128)
    val audioPacketFlow: SharedFlow<AudioPacket> = _audioPacketFlow.asSharedFlow()

    val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        handleAudioFocusChange(focusChange)
    }

    private val audioRecordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            super.onRecordingConfigChanged(configs)
            handleRecordingConfigsChanged(configs)
        }
    }

    /**
     * Evaluates AudioRecordingCallback events and ignores any recording configuration
     * belonging to our own app process (android.os.Process.myUid()) or matching our
     * AudioRecord.audioSessionId so our own capture never pauses or cycles the mic.
     */
    internal fun handleRecordingConfigsChanged(configs: List<AudioRecordingConfiguration>) {
        if (isTransitioningAudioState) {
            Log.d(TAG, "Ignoring onRecordingConfigChanged while isTransitioningAudioState=true")
            return
        }
        val activeSessionId = audioRecord?.audioSessionId ?: WakeWordVoiceService.activeAppAudioSessionId
        val externalConfigs = configs.filterNot { cfg ->
            WakeWordVoiceService.isOwnRecordingConfig(cfg, activeSessionId)
        }
        if (configs.isNotEmpty() && externalConfigs.isEmpty()) {
            Log.d(TAG, "Ignoring self-initiated AudioRecordingCallback (own UID / audioSessionId=$activeSessionId)")
            return
        }
        // Only treat as external capture if a config is verified to belong to a non-app UID
        val verifiedExternal = externalConfigs.any { cfg ->
            val uid = WakeWordVoiceService.extractClientUid(cfg)
            uid != null && uid != android.os.Process.myUid()
        }
        if (verifiedExternal != _isExternalMicActive.value) {
            handleExternalMicContention(verifiedExternal)
        }
    }

    init {
        try {
            audioManager?.registerAudioRecordingCallback(audioRecordingCallback, null)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register AudioRecordingCallback: ${e.message}")
        }
        WakeWordVoiceService.onAudioFocusLostCallback = { focusChange ->
            handleAudioFocusChange(focusChange)
        }
        WakeWordVoiceService.onAudioFocusGainedCallback = {
            handleAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        }
        WakeWordVoiceService.onExternalMicContentionChanged = { contended ->
            handleExternalMicContention(contended)
        }
        WakeWordVoiceService.onInputFocusPauseRequested = { paused ->
            onTextInputFocusChanged(paused)
        }
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
        const val UNPROCESSED_AUDIO_SOURCE = MediaRecorder.AudioSource.UNPROCESSED
        const val FALLBACK_AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
        const val MIN_FALLBACK_BUFFER_BYTES = 4096
        const val MAX_PRE_ROLL_BYTES = 16000 * 2 * 3 // Up to 3 seconds of 16kHz 16-bit mono pre-roll
        const val MAX_SNIPPET_BUFFER_BYTES = 16000 * 2 * 5 // Up to 5 seconds of 16kHz 16-bit mono snippet test buffer

        const val SPEECH_DB_THRESHOLD = 0.0f
        const val SILENCE_CUTOFF_MS = 1200L
        const val SPEECH_COMPLETE_SILENCE_MS = 3000L
        const val SPEECH_POSSIBLY_COMPLETE_SILENCE_MS = 3000L
        const val SPEECH_MINIMUM_LENGTH_MS = 5000L
        const val MIN_NON_SILENT_SAMPLE_ABS = 1

        const val MAX_AUDIO_RECORD_INIT_RETRIES = 3
        const val INITIAL_BACKOFF_MS = 500L
        const val MAX_BACKOFF_MS = 4000L

        /**
         * Computes exponential backoff delay (500ms -> 1000ms -> 2000ms -> 4000ms)
         * when AudioRecord fails to initialize (-20 / NO_INIT) so the reader thread
         * never spins in a tight loop.
         */
        fun calculateExponentialBackoffMs(attempt: Int): Long {
            val safeAttempt = attempt.coerceIn(0, 6)
            return (INITIAL_BACKOFF_MS * (1L shl safeAttempt)).coerceAtMost(MAX_BACKOFF_MS)
        }

        fun configureRecognizerIntent(intent: Intent, callingPackage: String? = null): Intent {
            // Strictly strip any activity task flags (such as FLAG_ACTIVITY_NEW_TASK) from the
            // foreground SpeechRecognizer intent so Android 14 / Samsung One UI never treats
            // in-process SpeechRecognizer binding as a background task transition.
            intent.removeFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            )
            intent.putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            intent.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            if (!callingPackage.isNullOrBlank()) {
                intent.putExtra(android.speech.RecognizerIntent.EXTRA_CALLING_PACKAGE, callingPackage)
            }
            intent.putExtra(
                android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                SPEECH_COMPLETE_SILENCE_MS
            )
            intent.putExtra(
                android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                SPEECH_POSSIBLY_COMPLETE_SILENCE_MS
            )
            intent.putExtra(
                android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                SPEECH_MINIMUM_LENGTH_MS
            )
            val completeSilenceMs = intent.getLongExtra(
                android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                -1L
            )
            val possiblyCompleteSilenceMs = intent.getLongExtra(
                android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                -1L
            )
            val minLengthMs = intent.getLongExtra(
                android.speech.RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                -1L
            )
            val intentTrace =
                "EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS=${completeSilenceMs}ms " +
                    "EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS=${possiblyCompleteSilenceMs}ms " +
                    "EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS=${minLengthMs}ms " +
                    "callingPackage=${callingPackage ?: "none"} flags=0x${Integer.toHexString(intent.flags)}"
            Log.d(TAG, "[SR-Trace] stage=RECOGNIZER_INTENT_CONFIGURED | $intentTrace")
            WakeWordVoiceService.dispatchDiagnosticTrace("RECOGNIZER_INTENT_CONFIGURED", intentTrace)
            return intent
        }

        /**
         * Wraps raw 16-bit Little-Endian mono PCM bytes into a standard 44-byte RIFF/WAVE header.
         */
        fun buildWavFromPcmBytes(pcmBytes: ByteArray, sampleRate: Int = SAMPLE_RATE_HZ): ByteArray {
            val channels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * channels * (bitsPerSample / 8)
            val blockAlign = channels * (bitsPerSample / 8)
            val dataSize = pcmBytes.size
            val chunkSize = 36 + dataSize

            val out = ByteArray(44 + dataSize)
            // "RIFF"
            out[0] = 'R'.code.toByte()
            out[1] = 'I'.code.toByte()
            out[2] = 'F'.code.toByte()
            out[3] = 'F'.code.toByte()
            writeIntLE(out, 4, chunkSize)
            // "WAVE"
            out[8] = 'W'.code.toByte()
            out[9] = 'A'.code.toByte()
            out[10] = 'V'.code.toByte()
            out[11] = 'E'.code.toByte()
            // "fmt "
            out[12] = 'f'.code.toByte()
            out[13] = 'm'.code.toByte()
            out[14] = 't'.code.toByte()
            out[15] = ' '.code.toByte()
            writeIntLE(out, 16, 16) // Subchunk1Size for PCM
            writeShortLE(out, 20, 1) // AudioFormat = 1 (PCM)
            writeShortLE(out, 22, channels)
            writeIntLE(out, 24, sampleRate)
            writeIntLE(out, 28, byteRate)
            writeShortLE(out, 32, blockAlign)
            writeShortLE(out, 34, bitsPerSample)
            // "data"
            out[36] = 'd'.code.toByte()
            out[37] = 'a'.code.toByte()
            out[38] = 't'.code.toByte()
            out[39] = 'a'.code.toByte()
            writeIntLE(out, 40, dataSize)
            if (dataSize > 0) {
                System.arraycopy(pcmBytes, 0, out, 44, dataSize)
            }
            return out
        }

        /**
         * Strips the 44-byte RIFF/WAVE header if present, returning raw 16-bit Little-Endian PCM bytes.
         */
        fun extractPcmFromWavOrRaw(bytes: ByteArray): ByteArray {
            if (bytes.size >= 44 &&
                bytes[0] == 'R'.code.toByte() &&
                bytes[1] == 'I'.code.toByte() &&
                bytes[2] == 'F'.code.toByte() &&
                bytes[3] == 'F'.code.toByte() &&
                bytes[8] == 'W'.code.toByte() &&
                bytes[9] == 'A'.code.toByte() &&
                bytes[10] == 'V'.code.toByte() &&
                bytes[11] == 'E'.code.toByte()
            ) {
                return bytes.copyOfRange(44, bytes.size)
            }
            return bytes
        }

        private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = (value and 0xFF).toByte()
            buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
            buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
            buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }

        private fun writeShortLE(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = (value and 0xFF).toByte()
            buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        }

        /**
         * Dynamically calculates the internal AudioRecord buffer size:
         * val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
         * val bufferSize = maxOf(minBuf * 2, 4096)
         */
        fun calculateBufferSize(
            minBuf: Int = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        ): Int {
            val safeMin = if (minBuf > 0) minBuf else 0
            return maxOf(safeMin * 2, MIN_FALLBACK_BUFFER_BYTES)
        }

        /**
         * Computes RMS amplitude in dB (> 0 dB whenever any non-zero 16-bit PCM sample exists).
         */
        fun computeRmsDb(buffer: ShortArray, readCount: Int = buffer.size): Float {
            if (readCount <= 0 || buffer.isEmpty()) return 0f
            val limit = readCount.coerceAtMost(buffer.size)
            var sum = 0.0
            var hasNonZero = false
            for (i in 0 until limit) {
                val sampleInt = buffer[i].toInt()
                if (sampleInt != 0) {
                    hasNonZero = true
                    val sample = sampleInt.toDouble()
                    sum += sample * sample
                }
            }
            if (!hasNonZero) return 0f
            val rms = sqrt(sum / limit)
            if (rms <= 0.0) return 0f
            // Map RMS >= 1.0 onto a positive dB scale (0.01f..10.0f) so any non-zero speech sample is > 0 dB
            val rawDb = (20.0 * log10(rms.coerceAtLeast(1.0))).toFloat() / 6.0f
            return rawDb.coerceIn(0.05f, 10.0f)
        }

        /**
         * Ensures presentation timestamps are always bound to dynamic system time
         * (System.currentTimeMillis()) and never default to 0L (12:00 AM).
         */
        fun resolvePresentationTimestamp(candidateTimestampMs: Long = 0L): Long {
            return if (candidateTimestampMs > 0L) candidateTimestampMs else System.currentTimeMillis()
        }

        /**
         * Verifies AudioRecord is both STATE_INITIALIZED and RECORDSTATE_RECORDING before reading.
         */
        fun canReadFromAudioRecord(record: AudioRecord?): Boolean {
            return record != null &&
                record.state == AudioRecord.STATE_INITIALIZED &&
                record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }

        /**
         * Returns true if the read short buffer has no valid samples (readCount <= 0)
         * or if all 16-bit PCM samples in [0, readCount) are zero.
         */
        fun isShortBufferSilent(buffer: ShortArray, readCount: Int): Boolean {
            if (readCount <= 0 || buffer.isEmpty()) return true
            val limit = readCount.coerceAtMost(buffer.size)
            for (i in 0 until limit) {
                if (buffer[i].toInt() != 0 && abs(buffer[i].toInt()) >= MIN_NON_SILENT_SAMPLE_ABS) {
                    return false
                }
            }
            return true
        }

        /**
         * Returns true if the 16-bit Little-Endian PCM byte buffer is empty (bytesRead <= 0)
         * or contains only zero samples.
         */
        fun isPcmByteBufferSilent(bytes: ByteArray, bytesRead: Int = bytes.size): Boolean {
            if (bytesRead <= 1 || bytes.isEmpty()) return true
            val limit = bytesRead.coerceAtMost(bytes.size)
            var i = 0
            while (i + 1 < limit) {
                val low = bytes[i].toInt() and 0xFF
                val high = bytes[i + 1].toInt()
                val sample = ((high shl 8) or low).toShort().toInt()
                if (sample != 0 && abs(sample) >= MIN_NON_SILENT_SAMPLE_ABS) {
                    return false
                }
                i += 2
            }
            return true
        }

        /**
         * Guards outgoing audio packet transmission: rejects packets when bytesRead <= 0
         * or when the PCM buffer is empty or contains zero non-silent 16-bit samples.
         */
        fun shouldTransmitAudioPacket(bytesRead: Int, pcmBytes: ByteArray): Boolean {
            if (bytesRead <= 0 || pcmBytes.isEmpty()) return false
            return !isPcmByteBufferSilent(pcmBytes, bytesRead)
        }

        /** 500ms debounce before re-arming listening on AUDIOFOCUS_GAIN. */
        const val AUDIOFOCUS_GAIN_DEBOUNCE_MS = 500L

        /** Strict 1500ms debounce for windowFocus changes to prevent rapid start/stop cycling on transient focus losses. */
        const val WINDOW_FOCUS_DEBOUNCE_MS = 1500L
    }

    @Volatile
    private var isSpeechActive = false
    @Volatile
    private var silenceStartTimestamp = 0L

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val windowFocusScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate
    )
    private var windowFocusDebounceJob: kotlinx.coroutines.Job? = null

    private val audioFocusGainRunnable = Runnable {
        if (_hasAudioFocus.value) {
            maybeResumeAfterYield()
        }
    }
    private val windowFocusDebounceRunnable = Runnable {
        if (!hasActiveWindowFocus && (!_isHandsFreeMode.value || _isSleepMode.value) && (audioRecord != null || isRecording)) {
            releaseAudioRecordHardware(stopForegroundService = false)
        }
    }
    var onAudioFocusChangeCallback: ((Int) -> Unit)? = null

    /**
     * Window focus guard: prevents `AudioRecord.startRecording()` from starting or looping
     * in the background when `MainActivity` does not have active window focus.
     */
    @Volatile
    var hasActiveWindowFocus: Boolean = true
        private set

    /**
     * Strict transient focus loss flag: set to `true` when window focus is lost and only cleared
     * after the 1500ms focus-gain debounce completes.
     */
    @Volatile
    var isTransientFocusLoss: Boolean = false
        internal set

    fun setWindowFocused(focused: Boolean) {
        hasActiveWindowFocus = focused
        windowFocusDebounceJob?.cancel()
        windowFocusDebounceJob = null
        mainHandler.removeCallbacks(windowFocusDebounceRunnable)
        if (!focused) {
            isTransientFocusLoss = true
        } else {
            windowFocusDebounceJob = windowFocusScope.launch {
                kotlinx.coroutines.delay(WINDOW_FOCUS_DEBOUNCE_MS)
                if (hasActiveWindowFocus) {
                    isTransientFocusLoss = false
                }
            }
        }
    }

    /**
     * Hard-kills `ERROR_CLIENT` during transient window focus losses so the recognizer is never
     * recreated or restarted in a loop.
     */
    fun onError(error: Int) {
        if (error == android.speech.SpeechRecognizer.ERROR_CLIENT && isTransientFocusLoss) {
            return
        }
    }

    var onAudioFrameCaptured: ((ByteArray) -> Unit)? = null
    var onAudioPacketCaptured: ((AudioPacket) -> Unit)? = null
    var onWakeAudioHandoff: ((ByteArray) -> Unit)? = null
    var onStopSpeechRecognizerForWakeHandoff: (() -> Unit)? = null
    var onWakeForceReset: ((chatId: Long, chatTitle: String, keyword: String, sanitizedQuery: String?) -> Unit)? = null
    var onSpeechStarted: (() -> Unit)? = null
    var onSilenceCutoffDetected: (() -> Unit)? = null
    var onKeywordProcessing: ((ShortArray) -> Unit)? = null

    // Hands-Free Voice Dictation & Action Engine
    private val _isHandsFreeMode = MutableStateFlow(false)
    val isHandsFreeMode: StateFlow<Boolean> = _isHandsFreeMode.asStateFlow()

    private val _handsFreeState = MutableStateFlow(HandsFreeVoiceState.IDLE)
    val handsFreeState: StateFlow<HandsFreeVoiceState> = _handsFreeState.asStateFlow()

    var dictateTrigger: String = AudioCommandSanitizer.DEFAULT_DICTATE_TRIGGER
    var sendTrigger: String = AudioCommandSanitizer.DEFAULT_SEND_TRIGGER
    var heartbeatTrigger: String = AudioCommandSanitizer.DEFAULT_HEARTBEAT_TRIGGER

    var onHandsFreePartialText: ((String) -> Unit)? = null
    var onHandsFreeSend: ((String) -> Unit)? = null
    var onHandsFreeHeartbeat: (() -> Unit)? = null
    var onBargeInDetected: (() -> Unit)? = null

    // Enrollment State
    private val _isEnrollmentRecording = MutableStateFlow(false)
    val isEnrollmentRecording: StateFlow<Boolean> = _isEnrollmentRecording.asStateFlow()

    private val enrollmentSamples = mutableMapOf<Long, List<ShortArray>>() // chatId -> List of PCM samples
    private val enrollmentRmsProfiles = mutableMapOf<Long, List<FloatArray>>() // chatId -> List of RMS envelopes
    private val enrollmentZcrProfiles = mutableMapOf<Long, List<FloatArray>>() // chatId -> List of ZCR spectral profiles

    // Chats cache for wake word matching
    private var activeChats: List<ChatSession> = emptyList()
    private var configuredWakeWords: List<WakeWord> = emptyList()

    /**
     * Appends non-silent 16-bit PCM bytes into the rolling wake pre-roll buffer
     * and local snippet test buffer so when a wake word triggers or snippet is tested,
     * the buffered wake audio is seamlessly available without voice cutoff.
     */
    fun appendPreRollPcm(pcmBytes: ByteArray, bytesRead: Int = pcmBytes.size) {
        if (bytesRead <= 0 || pcmBytes.isEmpty() || isPcmByteBufferSilent(pcmBytes, bytesRead)) return
        val limit = bytesRead.coerceAtMost(pcmBytes.size)
        synchronized(wakePreRollLock) {
            val current = wakePreRollStream.toByteArray()
            if (current.size + limit > MAX_PRE_ROLL_BYTES) {
                val keepCount = (MAX_PRE_ROLL_BYTES - limit).coerceAtLeast(0)
                wakePreRollStream.reset()
                if (keepCount > 0 && current.size >= keepCount) {
                    wakePreRollStream.write(current, current.size - keepCount, keepCount)
                }
            }
            wakePreRollStream.write(pcmBytes, 0, limit)
        }
        recordSnippetPcmBytes(pcmBytes, limit)
    }

    /**
     * Records incoming 16-bit Little-Endian PCM bytes into the local snippet/transition test buffer.
     */
    fun recordSnippetPcmBytes(pcmBytes: ByteArray, bytesRead: Int = pcmBytes.size) {
        if (bytesRead <= 0 || pcmBytes.isEmpty()) return
        val rawPcm = extractPcmFromWavOrRaw(
            if (bytesRead < pcmBytes.size) pcmBytes.copyOf(bytesRead) else pcmBytes
        )
        if (rawPcm.isEmpty() || isPcmByteBufferSilent(rawPcm, rawPcm.size)) return
        synchronized(snippetBufferLock) {
            val current = capturedSnippetPcmStream.toByteArray()
            if (current.size + rawPcm.size > MAX_SNIPPET_BUFFER_BYTES) {
                val keepCount = (MAX_SNIPPET_BUFFER_BYTES - rawPcm.size).coerceAtLeast(0)
                capturedSnippetPcmStream.reset()
                if (keepCount > 0 && current.size >= keepCount) {
                    capturedSnippetPcmStream.write(current, current.size - keepCount, keepCount)
                }
            }
            capturedSnippetPcmStream.write(rawPcm, 0, rawPcm.size)
        }
    }

    /**
     * Records incoming 16-bit PCM short samples into the local snippet/transition test buffer.
     */
    fun recordSnippetShorts(shorts: ShortArray, readCount: Int = shorts.size) {
        if (readCount <= 0 || shorts.isEmpty() || isShortBufferSilent(shorts, readCount)) return
        val limit = readCount.coerceAtMost(shorts.size)
        val pcmBytes = ByteArray(limit * 2)
        for (i in 0 until limit) {
            val s = shorts[i].toInt()
            pcmBytes[i * 2] = (s and 0xFF).toByte()
            pcmBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        recordSnippetPcmBytes(pcmBytes, pcmBytes.size)
    }

    /**
     * Returns a copy of the latest PCM bytes recorded in the local snippet/transition ring buffer.
     */
    fun getCapturedSnippetPcm(): ByteArray {
        synchronized(snippetBufferLock) {
            return capturedSnippetPcmStream.toByteArray()
        }
    }

    /**
     * Returns the recorded snippet/transition buffer wrapped as a playable 16kHz 16-bit mono WAV byte array.
     */
    fun getCapturedSnippetWav(): ByteArray {
        val pcm = getCapturedSnippetPcm()
        return if (pcm.isNotEmpty()) buildWavFromPcmBytes(pcm, SAMPLE_RATE_HZ) else ByteArray(0)
    }

    /**
     * Clears the local test snippet PCM buffer.
     */
    fun clearCapturedSnippetPcm() {
        synchronized(snippetBufferLock) {
            capturedSnippetPcmStream.reset()
        }
    }

    /**
     * Consumes and clears the rolling wake pre-roll buffer for handoff to the active chat turn.
     */
    fun consumeWakePreRollBuffer(): ByteArray {
        synchronized(wakePreRollLock) {
            val bytes = wakePreRollStream.toByteArray()
            wakePreRollStream.reset()
            return bytes
        }
    }

    /**
     * Requests AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE before acquiring AudioRecord.
     */
    fun requestExclusiveAudioFocus(): Boolean {
        val manager = audioManager ?: return false
        return try {
            val res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener)
                    .build()
                audioFocusRequest = req
                manager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
            val granted = res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            val responseLabel = WakeWordVoiceService.formatAudioFocusRequestResult(res)
            appendDiagnosticTraceLog(
                stage = "AUDIOFOCUS_REQUEST",
                details = "response=$responseLabel granted=$granted epochMs=${System.currentTimeMillis()}"
            )
            _hasAudioFocus.value = granted
            WakeWordVoiceService.setExclusiveAudioFocusState(granted)
            granted
        } catch (e: Exception) {
            appendDiagnosticTraceLog(
                stage = "AUDIOFOCUS_REQUEST",
                details = "response=AUDIOFOCUS_REQUEST_EXCEPTION error=${e.message}"
            )
            Log.w(TAG, "Error requesting AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE: ${e.message}")
            false
        }
    }

    /**
     * Abandons AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE when releasing AudioRecord.
     */
    fun abandonExclusiveAudioFocus() {
        val manager = audioManager ?: return
        try {
            val res = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { manager.abandonAudioFocusRequest(it) }
                    ?: AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(audioFocusChangeListener)
            }
            val responseLabel = WakeWordVoiceService.formatAudioFocusRequestResult(res)
            appendDiagnosticTraceLog(
                stage = "AUDIOFOCUS_ABANDON",
                details = "response=$responseLabel epochMs=${System.currentTimeMillis()}"
            )
        } catch (e: Exception) {
            appendDiagnosticTraceLog(
                stage = "AUDIOFOCUS_ABANDON",
                details = "response=AUDIOFOCUS_ABANDON_EXCEPTION error=${e.message}"
            )
            Log.w(TAG, "Error abandoning audio focus: ${e.message}")
        } finally {
            _hasAudioFocus.value = false
            WakeWordVoiceService.setExclusiveAudioFocusState(false)
        }
    }

    /**
     * Handles AudioManager audio focus transitions:
     * - Only yields audio focus on [AudioManager.AUDIOFOCUS_LOSS] and [AudioManager.AUDIOFOCUS_LOSS_TRANSIENT].
     * - Does NOT yield on [AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK].
     * - Uses a 500ms debounce ([AUDIOFOCUS_GAIN_DEBOUNCE_MS]) before re-arming on [AudioManager.AUDIOFOCUS_GAIN].
     */
    fun handleAudioFocusChange(focusChange: Int) {
        val responseLabel = WakeWordVoiceService.formatAudioFocusChange(focusChange)
        if (isTransitioningAudioState) {
            appendDiagnosticTraceLog(
                stage = "AUDIOFOCUS_CALLBACK",
                details = "response=$responseLabel ignored=true (isTransitioningAudioState=true)"
            )
            return
        }
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                appendDiagnosticTraceLog(
                    stage = "AUDIOFOCUS_CALLBACK",
                    details = "response=$responseLabel action=YIELD_AUDIORECORD"
                )
                mainHandler.removeCallbacks(audioFocusGainRunnable)
                _hasAudioFocus.value = false
                WakeWordVoiceService.setExclusiveAudioFocusState(false)
                if (_isListening.value || _isHandsFreeMode.value) {
                    shouldResumeAfterYield = true
                }
                if (_isListening.value) {
                    stopAndReleaseForYield("Paused for external audio focus")
                }
                onAudioFocusChangeCallback?.invoke(focusChange)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Do NOT yield audio focus on CAN_DUCK — keep handsFreeMode and listening active
                appendDiagnosticTraceLog(
                    stage = "AUDIOFOCUS_CALLBACK",
                    details = "response=$responseLabel action=IGNORE_CAN_DUCK"
                )
                onAudioFocusChangeCallback?.invoke(focusChange)
            }
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> {
                appendDiagnosticTraceLog(
                    stage = "AUDIOFOCUS_CALLBACK",
                    details = "response=$responseLabel action=GAIN_DEBOUNCE_${AUDIOFOCUS_GAIN_DEBOUNCE_MS}MS"
                )
                _hasAudioFocus.value = true
                WakeWordVoiceService.setExclusiveAudioFocusState(true)
                onAudioFocusChangeCallback?.invoke(focusChange)
                mainHandler.removeCallbacks(audioFocusGainRunnable)
                mainHandler.postDelayed(audioFocusGainRunnable, AUDIOFOCUS_GAIN_DEBOUNCE_MS)
            }
        }
    }

    /**
     * Pauses and releases the background AudioRecord loop whenever an on-screen text input
     * gains focus (so Gboard / IME voice typing has uncontested hardware mic access) and
     * automatically resumes listening when text focus clears.
     */
    fun onTextInputFocusChanged(focused: Boolean) {
        val previous = _isTextInputFocused.value
        if (focused == previous) return
        _isTextInputFocused.value = focused
        if (focused) {
            if (_isListening.value || _isHandsFreeMode.value) {
                shouldResumeAfterYield = true
            }
            // Only yield passive background listening; if user is actively in a dedicated voice turn,
            // do not let passive keyboard focus kill an explicit voice turn unless passive listening is active.
            if (_isListening.value && !_isUserVoiceTurn.value) {
                stopAndReleaseForYield("Paused for keyboard / Gboard input")
            }
            try {
                val pauseIntent = Intent(context, WakeWordVoiceService::class.java).apply {
                    action = WakeWordVoiceService.ACTION_PAUSE_FOR_INPUT_FOCUS
                }
                context.startService(pauseIntent)
            } catch (_: Exception) {}
        } else {
            try {
                val resumeIntent = Intent(context, WakeWordVoiceService::class.java).apply {
                    action = WakeWordVoiceService.ACTION_RESUME_AFTER_INPUT_FOCUS
                }
                context.startService(resumeIntent)
            } catch (_: Exception) {}
            maybeResumeAfterYield()
        }
    }

    /**
     * Handles external microphone contention detected via AudioManager.AudioRecordingCallback
     * or AudioFocus loss (such as Gboard IME voice typing).
     */
    fun handleExternalMicContention(contended: Boolean) {
        if (isTransitioningAudioState) {
            Log.d(TAG, "Ignoring handleExternalMicContention($contended) while isTransitioningAudioState=true")
            return
        }
        _isExternalMicActive.value = contended
        if (contended) {
            if (_isListening.value || _isHandsFreeMode.value) {
                shouldResumeAfterYield = true
            }
            if (_isListening.value) {
                stopAndReleaseForYield("Microphone yielded to Gboard / external app")
            }
        } else {
            maybeResumeAfterYield()
        }
    }

    private fun stopAndReleaseForYield(reasonStatus: String) {
        if (isTransitioningAudioState) return
        isTransitioningAudioState = true
        WakeWordVoiceService.isTransitioningAudioState = true
        try {
            releaseAudioRecordHardware(stopForegroundService = false)
            _statusMessage.value = reasonStatus
        } finally {
            lastStateTransitionMs = System.currentTimeMillis()
            isTransitioningAudioState = false
            WakeWordVoiceService.isTransitioningAudioState = false
        }
    }

    private fun maybeResumeAfterYield() {
        if (isTransitioningAudioState) return
        if (_isUserVoiceTurn.value) {
            Log.d(TAG, "Suppressing maybeResumeAfterYield while isUserVoiceTurn=true")
            return
        }
        if (_isTextInputFocused.value || _isExternalMicActive.value) return
        if (shouldResumeAfterYield || _isHandsFreeMode.value) {
            shouldResumeAfterYield = false
            if (_isHandsFreeMode.value) {
                transitionToPassiveListening()
            }
            if (!_isListening.value) {
                startContinuousListening(forceActiveTurn = false)
            }
        }
    }

    fun updateEnrollmentSamples(chatId: Long, samples: List<ShortArray>) {
        val nonSilentSamples = samples.filter { it.size >= 160 && !isShortBufferSilent(it, it.size) }
        enrollmentSamples[chatId] = nonSilentSamples
        // Pre-compute RMS energy profiles and Zero-Crossing Rate (ZCR) spectral profiles for acoustic matching
        enrollmentRmsProfiles[chatId] = nonSilentSamples.map { computeRmsProfile(it) }
        enrollmentZcrProfiles[chatId] = nonSilentSamples.map { computeZcrProfile(it) }
    }

    fun getEnrolledSampleCount(chatId: Long): Int {
        return enrollmentSamples[chatId]?.size ?: 0
    }

    internal fun computeRmsProfile(pcm: ShortArray, windowSize: Int = 160): FloatArray {
        if (pcm.size < windowSize || windowSize <= 0) return FloatArray(0)
        // Compute RMS for every 10ms (at 16kHz, 160 samples)
        val profile = FloatArray(pcm.size / windowSize)
        for (i in profile.indices) {
            var sum = 0.0
            for (j in 0 until windowSize) {
                val sample = pcm[i * windowSize + j].toDouble()
                sum += sample * sample
            }
            val rms = sqrt(sum / windowSize)
            profile[i] = if (rms > 0) ((20 * log10(rms)) - 20).toFloat().coerceIn(0f, 10f) else 0f
        }
        return profile
    }

    internal fun computeZcrProfile(pcm: ShortArray, windowSize: Int = 160): FloatArray {
        if (pcm.size < windowSize || windowSize <= 1) return FloatArray(0)
        val profile = FloatArray(pcm.size / windowSize)
        for (i in profile.indices) {
            var crossings = 0
            val base = i * windowSize
            for (j in 1 until windowSize) {
                val prev = pcm[base + j - 1].toInt()
                val curr = pcm[base + j].toInt()
                if ((prev >= 0 && curr < 0) || (prev < 0 && curr >= 0)) {
                    crossings++
                }
            }
            profile[i] = (crossings.toFloat() / windowSize.toFloat()) * 10f
        }
        return profile
    }

    internal fun compareProfiles(live: FloatArray, template: FloatArray): Float {
        if (template.isEmpty() || live.size < template.size) return 0f

        // 1. Sliding Normalized Cross-Correlation of envelopes
        var maxCorrelation = 0f
        val searchRange = live.size - template.size

        for (offset in 0..searchRange) {
            var dotProduct = 0.0
            var normLive = 0.0
            var normTemplate = 0.0

            for (i in template.indices) {
                val l = live[offset + i].toDouble()
                val t = template[i].toDouble()
                dotProduct += l * t
                normLive += l * l
                normTemplate += t * t
            }

            val correlation = if (normLive > 0 && normTemplate > 0) {
                (dotProduct / (sqrt(normLive) * sqrt(normTemplate))).toFloat()
            } else 0f

            if (correlation > maxCorrelation) maxCorrelation = correlation
        }

        // 2. Banded Dynamic Time Warping (DTW) similarity on the tail window to handle speaking rate variations
        val tailLen = (template.size * 13 / 10).coerceAtMost(live.size)
        val liveTail = live.copyOfRange(live.size - tailLen, live.size)
        val dtwScore = computeDtwSimilarity(liveTail, template)

        return maxOf(maxCorrelation, dtwScore).coerceIn(0f, 1f)
    }

    internal fun computeDtwSimilarity(seqA: FloatArray, seqB: FloatArray): Float {
        if (seqA.isEmpty() || seqB.isEmpty()) return 0f
        val n = seqA.size
        val m = seqB.size
        // Normalize both sequences to [0, 1] so DTW compares contour shape independent of absolute mic distance
        val maxA = (seqA.maxOrNull() ?: 0f).coerceAtLeast(0.001f)
        val maxB = (seqB.maxOrNull() ?: 0f).coerceAtLeast(0.001f)
        if (maxA <= 0.01f || maxB <= 0.01f) return 0f

        val band = maxOf(8, abs(n - m) + 6)
        val dp = Array(n + 1) { FloatArray(m + 1) { Float.POSITIVE_INFINITY } }
        dp[0][0] = 0f

        for (i in 1..n) {
            val jStart = maxOf(1, i - band)
            val jEnd = minOf(m, i + band)
            val aVal = seqA[i - 1] / maxA
            for (j in jStart..jEnd) {
                val bVal = seqB[j - 1] / maxB
                val cost = abs(aVal - bVal)
                val prevMin = minOf(dp[i - 1][j], minOf(dp[i][j - 1], dp[i - 1][j - 1]))
                if (prevMin.isFinite()) {
                    dp[i][j] = cost + prevMin
                }
            }
        }
        val finalDist = dp[n][m]
        if (!finalDist.isFinite()) return 0f
        val avgDist = finalDist / (n + m).toFloat()
        return (1f - avgDist * 1.6f).coerceIn(0f, 1f)
    }

    /**
     * Evaluates incoming 16kHz PCM audio against enrolled acoustic templates (Sample 1..N)
     * combining RMS energy envelope + ZCR spectral profile + DTW similarity against wakeWordSensitivity.
     * Returns the matched ChatSession if similarity >= wakeWordSensitivity, or null otherwise.
     */
    fun matchAcousticWakeTemplates(
        liveRmsProfile: FloatArray,
        liveZcrProfile: FloatArray? = null
    ): Pair<ChatSession, Float>? {
        var bestMatch: ChatSession? = null
        var bestScore = 0f
        val globalThreshold = wakeWordSensitivity.coerceIn(0.45f, 0.98f)

        for ((chatId, rmsTemplates) in enrollmentRmsProfiles) {
            if (rmsTemplates.isEmpty()) continue
            val chatThreshold = configuredWakeWords
                .filter { it.isEnabled && it.triggerType == WakeWord.TYPE_CHAT && it.chatId == chatId }
                .minOfOrNull { it.sensitivity.coerceIn(0.45f, 0.98f) }
                ?: globalThreshold
            val zcrTemplates = enrollmentZcrProfiles[chatId].orEmpty()
            for (idx in rmsTemplates.indices) {
                val rmsTemplate = rmsTemplates[idx]
                if (rmsTemplate.isEmpty()) continue
                val rmsScore = compareProfiles(liveRmsProfile, rmsTemplate)
                val combinedScore = if (liveZcrProfile != null && idx < zcrTemplates.size && zcrTemplates[idx].isNotEmpty()) {
                    val zcrScore = compareProfiles(liveZcrProfile, zcrTemplates[idx])
                    (rmsScore * 0.78f) + (zcrScore * 0.22f)
                } else {
                    rmsScore
                }
                if (combinedScore >= chatThreshold && combinedScore > bestScore) {
                    val chat = activeChats.firstOrNull { it.id == chatId }
                        ?: configuredWakeWords.firstOrNull { it.chatId == chatId }?.let { cw ->
                            ChatSession(
                                id = chatId,
                                title = cw.chatTitle.ifBlank { "Gemini Chat" },
                                wakeWord = cw.phrase
                            )
                        }
                    if (chat != null) {
                        bestScore = combinedScore
                        bestMatch = chat
                    }
                }
            }
        }
        return if (bestMatch != null) bestMatch to bestScore else null
    }

    private val _engineState = MutableStateFlow(WakeWordManagerState.IDLE)
    val engineState: StateFlow<WakeWordManagerState> = _engineState.asStateFlow()

    var wakeWordSensitivity: Float = 0.78f

    fun setWakeWordSensitivityLevel(level: WakeSensitivityLevel) {
        wakeWordSensitivity = level.sensitivityValue.coerceIn(0.50f, 0.98f)
    }

    fun setHandsFreeMode(enabled: Boolean) {
        _isHandsFreeMode.value = enabled
        _isLiveDuplexMode.value = enabled
        Log.d(TAG, "Hands-Free / Live Duplex Mode: ${if (enabled) "ENABLED" else "DISABLED"}")
        if (enabled) {
            if (_isUserVoiceTurn.value) {
                Log.d(TAG, "Hands-Free enabled while isUserVoiceTurn=true — keeping active user voice turn")
                return
            }
            transitionToPassiveListening()
            if (!_isListening.value && !_isTextInputFocused.value && !_isExternalMicActive.value) {
                startContinuousListening(forceActiveTurn = false)
            } else if (_isTextInputFocused.value || _isExternalMicActive.value) {
                shouldResumeAfterYield = true
            }
        } else {
            shouldResumeAfterYield = false
            if (!_isUserVoiceTurn.value) {
                _handsFreeState.value = HandsFreeVoiceState.IDLE
                _engineState.value = WakeWordManagerState.IDLE
                if (_isListening.value) {
                    stopContinuousListening()
                }
            }
        }
    }

    fun transitionToPassiveListening() {
        if (_isUserVoiceTurn.value) {
            Log.d(TAG, "Suppressing transitionToPassiveListening while isUserVoiceTurn=true")
            return
        }
        if (_engineState.value != WakeWordManagerState.PASSIVE_LISTENING) {
            Log.d(TAG, "STATE TRANSITION: -> PASSIVE_LISTENING (Standby / Waiting for Wake Word)")
            _engineState.value = WakeWordManagerState.PASSIVE_LISTENING
            _handsFreeState.value = HandsFreeVoiceState.LISTENING
            isSpeechActive = false
            silenceStartTimestamp = 0L
        } else if (_handsFreeState.value != HandsFreeVoiceState.LISTENING) {
            _handsFreeState.value = HandsFreeVoiceState.LISTENING
        }
    }

    fun transitionToActiveStreaming() {
        if (_engineState.value != WakeWordManagerState.ACTIVE_STREAMING) {
            Log.d(TAG, "STATE TRANSITION: -> ACTIVE_STREAMING (Capturing user speech)")
            _engineState.value = WakeWordManagerState.ACTIVE_STREAMING
            _handsFreeState.value = HandsFreeVoiceState.TRANSCRIBING
            isSpeechActive = false
            silenceStartTimestamp = 0L
        } else if (_handsFreeState.value != HandsFreeVoiceState.TRANSCRIBING) {
            _handsFreeState.value = HandsFreeVoiceState.TRANSCRIBING
        }
    }

    /**
     * Triggered when hands-free detects a wake word:
     * 1. Sets isUserVoiceTurn = true to suppress background wake listener restarts.
     * 2. Immediately stops and releases the passive wake-word AudioRecord instance (enforcing the single-AudioRecord mutex).
     * 3. Snapshots and forwards the captured pre-roll wake audio buffer into the active chat turn.
     * 4. Initializes the dedicated chat-turn AudioRecord recording stream.
     */
    fun onKeywordHit(
        keyword: String = "jimmy",
        initialQuery: String? = null,
        targetChatIdOverride: Long? = null
    ) {
        if (isTransitioningAudioState) {
            Log.d(TAG, "Ignoring re-entrant onKeywordHit while isTransitioningAudioState=true")
            return
        }
        val combinedCommandText = if (initialQuery.isNullOrBlank()) keyword else "$keyword $initialQuery"
        if (AudioCommandSanitizer.containsWakeUpCommand(combinedCommandText)) {
            AudioCommandSanitizer.playVoiceCommandHaptic(context, "Wake Up (\"$combinedCommandText\")")
            exitSleepMode("Voice command: \"$combinedCommandText\"")
            return
        }
        if (AudioCommandSanitizer.containsSleepCommand(combinedCommandText)) {
            AudioCommandSanitizer.playVoiceCommandHaptic(context, "Sleep (\"$combinedCommandText\")")
            enterSleepMode("Voice command: \"$combinedCommandText\"")
            return
        }
        if (_isSleepMode.value) {
            Log.d(TAG, "Ignoring wake word '$keyword' while in Sleep Mode")
            return
        }
        Log.d(TAG, "KEYWORD HIT DETECTED: '$keyword' (initialQuery: '$initialQuery', targetChatIdOverride: $targetChatIdOverride)")
        AudioCommandSanitizer.playWakeWordHaptic(context, keyword)
        val matchedConfigured = configuredWakeWords.firstOrNull {
            it.isEnabled &&
                it.triggerType == WakeWord.TYPE_CHAT &&
                (targetChatIdOverride == null || it.chatId == targetChatIdOverride) &&
                it.phrase.equals(keyword, ignoreCase = true)
        } ?: configuredWakeWords.firstOrNull {
            it.isEnabled &&
                it.triggerType == WakeWord.TYPE_CHAT &&
                it.phrase.equals(keyword, ignoreCase = true)
        }

        val resolvedOverrideId = targetChatIdOverride?.takeIf { it > 0L } ?: matchedConfigured?.chatId?.takeIf { it > 0L }
        val matchedChat = (resolvedOverrideId?.let { cid -> activeChats.firstOrNull { it.id == cid } })
            ?: activeChats.firstOrNull { it.wakeWord.equals(keyword, ignoreCase = true) }
            ?: activeChats.firstOrNull { it.isDefaultWakeChat }
            ?: activeChats.firstOrNull()

        val chatId = resolvedOverrideId ?: matchedChat?.id ?: matchedConfigured?.chatId ?: 0L
        val chatTitle = (resolvedOverrideId?.let { cid -> activeChats.firstOrNull { it.id == cid }?.title })
            ?: matchedConfigured?.chatTitle?.takeIf { it.isNotBlank() }
            ?: matchedChat?.title
            ?: "Gemini"
        val customWakeWords = listOfNotNull(keyword, matchedConfigured?.phrase, matchedChat?.wakeWord).distinct()
        val sanitizedQuery = initialQuery?.let {
            AudioCommandSanitizer.stripWakeWordTrigger(it, customWakeWords)
        }?.takeIf { it.isNotBlank() }

        recordLog(keyword, chatTitle)
        // Force a clean state reset even if already in ChatDetailScreen or holding a stale draft:
        // Do NOT inject the wake word itself into _lastHeardText if no spoken query followed it.
        isSpeechActive = false
        silenceStartTimestamp = 0L
        _lastHeardText.value = sanitizedQuery ?: ""
        _isUserVoiceTurn.value = true

        // 0. Synchronously stop and release WakeWordVoiceService's AudioRecord ("wake_word_detected")
        //    the exact moment the wake word is detected, BEFORE any navigation state or callback fires.
        WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("wake_word_detected")

        // Synchronously stop SpeechRecognitionCoordinator and release microphone hardware BEFORE routing to chat / TTS
        onStopSpeechRecognizerForWakeHandoff?.invoke()

        // Synchronously notify ViewModel to clear stale draft text, cancel hung recognizers, and reset UI state
        onWakeForceReset?.invoke(chatId, chatTitle, keyword, sanitizedQuery)

        // 1. Consume any buffered wake audio captured prior to handoff
        val capturedWakeBytes = consumeWakePreRollBuffer()

        // 2. Enforce Microphone & Audio Focus Release: stop and release any existing/hung AudioRecord
        // and abandon exclusive audio focus BEFORE Gemini model or TTS engine begins its response.
        isTransitioningAudioState = true
        WakeWordVoiceService.isTransitioningAudioState = true
        try {
            WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("wake_word_detected")
            releaseAudioRecordHardware(stopForegroundService = false)
            abandonExclusiveAudioFocus()
            _wakeHandoffCount.incrementAndGet()

            // 3. Transition state to ACTIVE_STREAMING / TRANSCRIBING for the chat turn
            transitionToActiveStreaming()
            _statusMessage.value = "Wake word '$keyword' detected!"
        } finally {
            lastStateTransitionMs = System.currentTimeMillis()
            isTransitioningAudioState = false
            WakeWordVoiceService.isTransitioningAudioState = false
        }

        // 4. Forward captured wake audio buffer directly into the active chat session (once, without duplication)
        if (capturedWakeBytes.isNotEmpty() && !isPcmByteBufferSilent(capturedWakeBytes, capturedWakeBytes.size)) {
            val handoffCallback = onWakeAudioHandoff
            if (handoffCallback != null) {
                handoffCallback.invoke(capturedWakeBytes)
            } else {
                val handoffPacket = AudioPacket(
                    pcmBytes = capturedWakeBytes,
                    bytesRead = capturedWakeBytes.size,
                    presentationTimestampMs = resolvePresentationTimestamp(System.currentTimeMillis())
                )
                onAudioPacketCaptured?.invoke(handoffPacket)
                onAudioFrameCaptured?.invoke(capturedWakeBytes)
            }
        }

        // 5. Do NOT re-open AudioRecord or re-request exclusive audio focus here when routing to chat;
        // the continuous mic and exclusive audio focus must stay released so Gemini TTS can claim audio focus cleanly.

        val event = WakeWordTriggerEvent(
            chatId = chatId,
            chatTitle = chatTitle,
            matchedWord = keyword,
            initialQuery = sanitizedQuery,
            capturedAudioBytes = capturedWakeBytes
        )
        _triggerEvents.tryEmit(event)
    }

    /**
     * Completes the current user utterance while keeping the bidirectional Live Duplex
     * microphone session open (without tearing down AudioRecord) so follow-up speech
     * and barge-in work continuously alongside incoming 24 kHz Orion audio.
     */
    fun completeTurnKeepLiveDuplexMic() {
        _isUserVoiceTurn.value = false
        isSpeechActive = false
        silenceStartTimestamp = 0L
        if (_isHandsFreeMode.value || _isLiveDuplexMode.value) {
            transitionToPassiveListening()
            if (!_isListening.value && !_isTextInputFocused.value && !_isExternalMicActive.value) {
                startContinuousListening(forceActiveTurn = false)
            }
        } else {
            completeChatTurnAndReleaseMic()
        }
    }

    /**
     * Completes or cancels the active chat voice turn by clearing isUserVoiceTurn and
     * immediately stopping and releasing the chat-turn AudioRecord instance.
     */
    fun completeChatTurnAndReleaseMic() {
        _isUserVoiceTurn.value = false
        isTransitioningAudioState = true
        WakeWordVoiceService.isTransitioningAudioState = true
        try {
            releaseAudioRecordHardware(stopForegroundService = !_isHandsFreeMode.value)
            if (_isHandsFreeMode.value) {
                transitionToPassiveListening()
            } else {
                _engineState.value = WakeWordManagerState.IDLE
                _handsFreeState.value = HandsFreeVoiceState.IDLE
            }
        } finally {
            lastStateTransitionMs = System.currentTimeMillis()
            isTransitioningAudioState = false
            WakeWordVoiceService.isTransitioningAudioState = false
        }
    }

    /**
     * Re-initializes and restarts the passive wake-word listening loop after a voice turn
     * completes or is cancelled, respecting isUserVoiceTurn, text input focus, and external mic contention.
     */
    fun restartWakeListeningAfterTurn() {
        if (!_isHandsFreeMode.value) return
        if (_isUserVoiceTurn.value) {
            Log.d(TAG, "Suppressing restartWakeListeningAfterTurn while isUserVoiceTurn=true")
            return
        }
        transitionToPassiveListening()
        if (_isTextInputFocused.value || _isExternalMicActive.value) {
            shouldResumeAfterYield = true
            return
        }
        startContinuousListening(forceActiveTurn = false)
    }

    fun updateActiveChats(chats: List<ChatSession>) {
        this.activeChats = chats
    }

    fun getActiveChats(): List<ChatSession> = activeChats

    private val _persistedWakeWords = MutableStateFlow<List<WakeWord>>(emptyList())
    val persistedWakeWords: StateFlow<List<WakeWord>> = _persistedWakeWords.asStateFlow()

    fun updateConfiguredWakeWords(words: List<WakeWord>) {
        this.configuredWakeWords = words
        this._persistedWakeWords.value = words
    }

    fun getConfiguredWakeWords(): List<WakeWord> = configuredWakeWords

    private fun sanitizeWakeWordConfig(item: WakeWord): WakeWord {
        val fallbackPhrase = when (item.commandRole) {
            WakeWord.ROLE_DICTATE -> AudioCommandSanitizer.DEFAULT_DICTATE_TRIGGER
            WakeWord.ROLE_SEND -> AudioCommandSanitizer.DEFAULT_SEND_TRIGGER
            WakeWord.ROLE_HEARTBEAT -> AudioCommandSanitizer.DEFAULT_HEARTBEAT_TRIGGER
            else -> "Hey Gemini"
        }
        val cleanPhrase = item.phrase.trim().ifBlank { fallbackPhrase }
        val cleanSensitivity = item.sensitivity.coerceIn(0.50f, 0.98f)
        return item.copy(
            phrase = cleanPhrase,
            sensitivity = cleanSensitivity,
            chatTitle = item.chatTitle.trim().ifBlank {
                if (item.triggerType == TriggerType.CHAT) (item.metadata ?: "Gemini Chat") else item.commandRole
            }
        )
    }

    private fun applyLoadedConfigurationsToEngine(allWords: List<WakeWord>) {
        val sanitized = allWords.filter { !it.isDeleted }.map { sanitizeWakeWordConfig(it) }
        val chatWords = sanitized.filter { it.triggerType == TriggerType.CHAT }
        this.configuredWakeWords = chatWords
        this._persistedWakeWords.value = sanitized

        sanitized.firstOrNull {
            it.triggerType == TriggerType.HANDS_FREE && it.commandRole == WakeWord.ROLE_DICTATE
        }?.phrase?.takeIf { it.isNotBlank() }?.let { dictateTrigger = it }

        sanitized.firstOrNull {
            it.triggerType == TriggerType.HANDS_FREE && it.commandRole == WakeWord.ROLE_SEND
        }?.phrase?.takeIf { it.isNotBlank() }?.let { sendTrigger = it }

        sanitized.firstOrNull {
            it.triggerType == TriggerType.HANDS_FREE && it.commandRole == WakeWord.ROLE_HEARTBEAT
        }?.phrase?.takeIf { it.isNotBlank() }?.let { heartbeatTrigger = it }

        sanitized.firstOrNull()?.sensitivity?.let { savedSensitivity ->
            wakeWordSensitivity = savedSensitivity.coerceIn(0.50f, 0.98f)
        }
    }

    /**
     * Reactive [Flow] observing all non-deleted custom and system [WakeWord] configurations
     * from [WakeWordDao] and automatically synchronizing the engine's in-memory wake word state.
     */
    fun observeWakeWordConfigurations(): Flow<List<WakeWord>> =
        resolvedWakeWordDao.observeAllWakeWords()
            .map { list ->
                val sanitized = list.map { sanitizeWakeWordConfig(it) }
                applyLoadedConfigurationsToEngine(sanitized)
                sanitized
            }
            .catch { emit(emptyList()) }

    /**
     * Reactive [Flow] observing custom chat-specific [WakeWord] configurations from [WakeWordDao].
     */
    fun observeCustomChatWakeWords(): Flow<List<WakeWord>> =
        resolvedWakeWordDao.getChatWakeWordsFlow()
            .map { list ->
                val sanitized = list.map { sanitizeWakeWordConfig(it) }
                this.configuredWakeWords = sanitized
                sanitized
            }
            .catch { emit(emptyList()) }

    /**
     * Retrieves all persisted [WakeWord] configurations from [WakeWordDao] and updates
     * the in-memory wake word engine state (`configuredWakeWords`, system triggers, sensitivity).
     */
    suspend fun retrieveWakeWordConfigurations(): List<WakeWord> = withContext(Dispatchers.IO) {
        try {
            val all = resolvedWakeWordDao.getAllWakeWordsSync().map { sanitizeWakeWordConfig(it) }
            applyLoadedConfigurationsToEngine(all)
            all
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to retrieve wake word configurations from WakeWordDao: ${e.message}")
            emptyList()
        }
    }

    /**
     * Retrieves all custom chat [WakeWord] configurations from [WakeWordDao].
     */
    suspend fun retrieveCustomWakeWords(): List<WakeWord> = withContext(Dispatchers.IO) {
        try {
            val all = retrieveWakeWordConfigurations()
            all.filter { it.triggerType == TriggerType.CHAT && !it.isDeleted }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Retrieves custom [WakeWord] configurations bound to a specific [chatId] from [WakeWordDao].
     */
    suspend fun retrieveCustomWakeWordsForChat(chatId: Long): List<WakeWord> = withContext(Dispatchers.IO) {
        try {
            resolvedWakeWordDao.getChatWakeWordsForChatSync(chatId).map { sanitizeWakeWordConfig(it) }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Persists a [WakeWord] configuration entity via [WakeWordDao] (deduplicating case-insensitively
     * by phrase and [TriggerType]) and refreshes the in-memory wake word cache.
     */
    suspend fun persistWakeWordEntity(wakeWord: WakeWord): Long = withContext(Dispatchers.IO) {
        val clean = sanitizeWakeWordConfig(wakeWord)
        if (clean.phrase.isBlank()) return@withContext -1L
        try {
            val now = System.currentTimeMillis()
            val existing = if (clean.id > 0L) {
                resolvedWakeWordDao.getWakeWordByIdSync(clean.id)
            } else {
                resolvedWakeWordDao.getWakeWordByPhraseAndTypeSync(clean.phrase, clean.triggerType)
            }
            val savedId = if (existing != null) {
                val updated = existing.copy(
                    phrase = clean.phrase,
                    triggerType = clean.triggerType,
                    metadata = clean.metadata ?: existing.metadata,
                    commandRole = clean.commandRole,
                    chatId = clean.chatId ?: existing.chatId,
                    chatTitle = clean.chatTitle.ifBlank { existing.chatTitle },
                    isEnabled = clean.isEnabled,
                    sensitivity = clean.sensitivity,
                    updatedAt = now,
                    remoteId = clean.remoteId ?: existing.remoteId ?: "wake_${existing.id}",
                    userId = clean.userId ?: existing.userId,
                    syncStatus = when {
                        clean.syncStatus == SyncStatus.SYNCED -> SyncStatus.SYNCED
                        existing.syncStatus == SyncStatus.SYNCED -> SyncStatus.DIRTY
                        else -> existing.syncStatus
                    },
                    lastSyncedAt = if (clean.lastSyncedAt > 0L) clean.lastSyncedAt else existing.lastSyncedAt,
                    isDeleted = false
                )
                resolvedWakeWordDao.updateWakeWord(updated)
                existing.id
            } else {
                val id = resolvedWakeWordDao.insertWakeWord(
                    clean.copy(
                        createdAt = if (clean.createdAt > 0L) clean.createdAt else now,
                        updatedAt = now,
                        isDeleted = false
                    )
                )
                if (id > 0L && clean.remoteId.isNullOrBlank()) {
                    resolvedWakeWordDao.updateWakeWordSyncState(
                        id = id,
                        remoteId = "wake_$id",
                        userId = clean.userId,
                        syncStatus = clean.syncStatus,
                        syncedAt = clean.lastSyncedAt
                    )
                }
                id
            }
            retrieveWakeWordConfigurations()
            savedId
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to persist wake word entity via WakeWordDao: ${e.message}")
            -1L
        }
    }

    /**
     * Persists a user's custom wake word configuration via [WakeWordDao] and updates the engine cache.
     */
    suspend fun persistCustomWakeWord(
        phrase: String,
        chatId: Long? = null,
        chatTitle: String = "",
        triggerType: TriggerType = TriggerType.CHAT,
        sensitivity: Float = wakeWordSensitivity,
        metadata: String? = null,
        userId: String? = null
    ): Long = withContext(Dispatchers.IO) {
        val cleanPhrase = phrase.trim()
        if (cleanPhrase.isBlank()) return@withContext -1L
        val now = System.currentTimeMillis()
        val resolvedTitle = chatTitle.trim().ifBlank {
            if (triggerType == TriggerType.CHAT) (metadata ?: "Gemini Chat") else (metadata ?: "HANDS_FREE")
        }
        val role = if (triggerType == TriggerType.CHAT) {
            WakeWord.ROLE_CHAT_WAKE
        } else {
            metadata?.takeIf { it.isNotBlank() } ?: WakeWord.ROLE_DICTATE
        }
        persistWakeWordEntity(
            WakeWord(
                phrase = cleanPhrase,
                triggerType = triggerType,
                metadata = metadata ?: resolvedTitle,
                commandRole = role,
                chatId = chatId,
                chatTitle = resolvedTitle,
                isEnabled = true,
                sensitivity = sensitivity.coerceIn(0.50f, 0.98f),
                createdAt = now,
                updatedAt = now,
                userId = userId,
                syncStatus = SyncStatus.PENDING,
                isDeleted = false
            )
        )
    }

    /**
     * Updates an existing custom wake word phrase in [WakeWordDao] and refreshes the engine cache.
     */
    suspend fun updateCustomWakeWordPhrase(id: Long, newPhrase: String): Boolean = withContext(Dispatchers.IO) {
        val clean = newPhrase.trim()
        if (clean.isBlank()) return@withContext false
        try {
            val existing = resolvedWakeWordDao.getWakeWordByIdSync(id) ?: return@withContext false
            if (existing.isDeleted) return@withContext false
            resolvedWakeWordDao.updateWakeWordPhrase(id, clean, System.currentTimeMillis())
            retrieveWakeWordConfigurations()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Persists the user's custom wake word sensitivity across all [WakeWord] configurations in [WakeWordDao].
     */
    suspend fun updateCustomWakeWordSensitivity(sensitivity: Float) = withContext(Dispatchers.IO) {
        val clamped = sensitivity.coerceIn(0.50f, 0.98f)
        wakeWordSensitivity = clamped
        try {
            resolvedWakeWordDao.updateSensitivityForAll(clamped, System.currentTimeMillis())
            retrieveWakeWordConfigurations()
        } catch (_: Throwable) {}
    }

    /**
     * Persists the user's custom wake word sensitivity (`0..100`) across all [WakeWord] configurations in [WakeWordDao].
     */
    suspend fun updateCustomWakeWordSensitivity(sensitivityPercent: Int) = withContext(Dispatchers.IO) {
        val clampedInt = sensitivityPercent.coerceIn(0, 100)
        val floatVal = WakeWord.fromSensitivityInt(clampedInt)
        wakeWordSensitivity = floatVal
        try {
            resolvedWakeWordDao.updateSensitivityForAllInt(clampedInt, System.currentTimeMillis())
            retrieveWakeWordConfigurations()
        } catch (_: Throwable) {}
    }

    /**
     * Persists a predefined [WakeSensitivityLevel] across all [WakeWord] configurations in [WakeWordDao].
     */
    suspend fun updateCustomWakeWordSensitivityLevel(level: WakeSensitivityLevel) =
        updateCustomWakeWordSensitivity(level.sensitivityValue)

    /**
     * Updates the sensitivity threshold (`0.50f..0.98f`) for a single [WakeWord] entry in [WakeWordDao]
     * and refreshes the in-memory wake word cache.
     */
    suspend fun updateWakeWordItemSensitivity(id: Long, sensitivity: Float): Boolean = withContext(Dispatchers.IO) {
        try {
            val existing = resolvedWakeWordDao.getWakeWordByIdSync(id) ?: return@withContext false
            if (existing.isDeleted) return@withContext false
            val clamped = sensitivity.coerceIn(0.50f, 0.98f)
            resolvedWakeWordDao.updateWakeWordSensitivity(id, clamped, System.currentTimeMillis())
            retrieveWakeWordConfigurations()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Updates the integer sensitivity (`0..100`) for a single [WakeWord] entry in [WakeWordDao]
     * and refreshes the in-memory wake word cache.
     */
    suspend fun updateWakeWordItemSensitivity(id: Long, sensitivityPercent: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            val existing = resolvedWakeWordDao.getWakeWordByIdSync(id) ?: return@withContext false
            if (existing.isDeleted) return@withContext false
            val clampedInt = sensitivityPercent.coerceIn(0, 100)
            resolvedWakeWordDao.updateWakeWordSensitivityInt(id, clampedInt, System.currentTimeMillis())
            retrieveWakeWordConfigurations()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Updates a single [WakeWord]'s sensitivity to a predefined [WakeSensitivityLevel] in [WakeWordDao].
     */
    suspend fun updateWakeWordItemSensitivityLevel(id: Long, level: WakeSensitivityLevel): Boolean =
        updateWakeWordItemSensitivity(id, level.sensitivityValue)

    /**
     * Creates a new [ChatSession] in Room and binds a custom [WakeWord] phrase with float sensitivity (`0.50f..0.98f`) to it.
     */
    suspend fun createChatAndBindWakeWord(
        chatTitle: String,
        phrase: String,
        sensitivity: Float = wakeWordSensitivity
    ): Pair<Long, Long> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(context.applicationContext)
            val repo = com.example.data.repository.WakeWordRepository(resolvedWakeWordDao, db.chatDao())
            val pair = repo.createChatAndBindWakeWord(
                chatTitle = chatTitle,
                phrase = phrase,
                sensitivity = sensitivity
            )
            retrieveWakeWordConfigurations()
            pair
        } catch (_: Throwable) {
            -1L to -1L
        }
    }

    /**
     * Creates a new [ChatSession] in Room and binds a custom [WakeWord] phrase with integer sensitivity (`0..100`) to it.
     */
    suspend fun createChatAndBindWakeWord(
        chatTitle: String,
        phrase: String,
        sensitivity: Int
    ): Pair<Long, Long> = withContext(Dispatchers.IO) {
        try {
            val db = AppDatabase.getDatabase(context.applicationContext)
            val repo = com.example.data.repository.WakeWordRepository(resolvedWakeWordDao, db.chatDao())
            val pair = repo.createChatAndBindWakeWord(
                chatTitle = chatTitle,
                phrase = phrase,
                sensitivity = sensitivity
            )
            retrieveWakeWordConfigurations()
            pair
        } catch (_: Throwable) {
            -1L to -1L
        }
    }

    /**
     * Associates an existing [WakeWord] entry ([id]) with a specific target [ChatSession] ([chatId] and [chatTitle]).
     */
    suspend fun updateWakeWordChatAssociation(
        id: Long,
        chatId: Long,
        chatTitle: String
    ): Boolean = withContext(Dispatchers.IO) {
        if (chatId <= 0L) return@withContext false
        try {
            val existing = resolvedWakeWordDao.getWakeWordByIdSync(id) ?: return@withContext false
            if (existing.isDeleted) return@withContext false
            val cleanTitle = chatTitle.trim().ifBlank { existing.chatTitle.ifBlank { "Gemini Chat" } }
            resolvedWakeWordDao.updateWakeWordChatBinding(
                id = id,
                chatId = chatId,
                chatTitle = cleanTitle,
                metadata = cleanTitle,
                updatedAt = System.currentTimeMillis()
            )
            retrieveWakeWordConfigurations()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Enables or disables a custom wake word configuration in [WakeWordDao] and refreshes the engine cache.
     */
    suspend fun setCustomWakeWordEnabled(id: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
        try {
            resolvedWakeWordDao.setWakeWordEnabled(id, enabled, System.currentTimeMillis())
            retrieveWakeWordConfigurations()
        } catch (_: Throwable) {}
    }

    /**
     * Soft-deletes (or permanently deletes if [softDelete] is false) a custom wake word configuration
     * in [WakeWordDao] and refreshes the in-memory wake word cache.
     */
    suspend fun deleteCustomWakeWord(id: Long, softDelete: Boolean = true) = withContext(Dispatchers.IO) {
        try {
            if (softDelete) {
                resolvedWakeWordDao.markWakeWordSoftDeleted(id, System.currentTimeMillis())
            } else {
                resolvedWakeWordDao.deleteWakeWordById(id)
            }
            retrieveWakeWordConfigurations()
        } catch (_: Throwable) {}
    }

    /**
     * Restores a previously soft-deleted custom wake word configuration in [WakeWordDao].
     */
    suspend fun restoreSoftDeletedWakeWord(id: Long) = withContext(Dispatchers.IO) {
        try {
            resolvedWakeWordDao.restoreSoftDeletedWakeWord(id, System.currentTimeMillis())
            retrieveWakeWordConfigurations()
        } catch (_: Throwable) {}
    }

    /**
     * Persists a system hands-free trigger phrase (`DICTATE`, `SEND`, `HEARTBEAT`) via [WakeWordDao].
     */
    suspend fun persistSystemTrigger(
        role: String,
        phrase: String,
        sensitivity: Float = wakeWordSensitivity
    ): Long = withContext(Dispatchers.IO) {
        try {
            val cleanPhrase = phrase.trim().ifBlank {
                when (role) {
                    WakeWord.ROLE_DICTATE -> AudioCommandSanitizer.DEFAULT_DICTATE_TRIGGER
                    WakeWord.ROLE_SEND -> AudioCommandSanitizer.DEFAULT_SEND_TRIGGER
                    WakeWord.ROLE_HEARTBEAT -> AudioCommandSanitizer.DEFAULT_HEARTBEAT_TRIGGER
                    else -> "Trigger"
                }
            }
            val id = resolvedWakeWordDao.upsertSystemTrigger(
                role = role,
                phrase = cleanPhrase,
                sensitivity = sensitivity.coerceIn(0.50f, 0.98f),
                metadata = role
            )
            retrieveWakeWordConfigurations()
            id
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun resolveTargetChatForWakeWord(configured: WakeWord): ChatSession? {
        val cid = configured.chatId
        if (cid != null && cid > 0L) {
            return activeChats.firstOrNull { it.id == cid }
                ?: ChatSession(
                    id = cid,
                    title = configured.chatTitle.ifBlank { "Gemini Chat" },
                    wakeWord = configured.phrase
                )
        }
        return activeChats.firstOrNull { it.isDefaultWakeChat } ?: activeChats.firstOrNull()
    }

    /**
     * Evaluates a recognized transcript from Android SpeechRecognizer against all active
     * Room-persisted WakeWord entries, ChatSession wake words, and default wake triggers.
     * Returns WakePhraseMatchResult with the matched ChatSession, wake word, and scrubbed follow-up query.
     */
    fun matchWakePhraseInTranscript(transcript: String): WakePhraseMatchResult? {
        val normalized = transcript.trim()
        if (normalized.isEmpty()) return null

        // Handle "Gemini, wake up" and "Gemini, sleep" commands before matching chat wake words
        if (evaluateVoiceSleepOrWakeCommand(normalized)) {
            return null
        }
        // Handle voice history navigation commands ("open history", "show history", "voice history")
        // before matching "open" or chat wake words so they never trigger a chat wake event
        val customWakeList = (
            configuredWakeWords.map { it.phrase } +
                activeChats.map { it.wakeWord }
            ).filter { it.isNotBlank() }
        if (AudioCommandSanitizer.isVoiceHistoryNavigationCommand(normalized, customWakeList)) {
            return null
        }
        if (_isSleepMode.value) {
            return null
        }

        val lower = normalized.lowercase(java.util.Locale.US)

        // 0. Check Room-persisted WakeWord entries (enabled CHAT phrases, longest first)
        val enabledConfigured = configuredWakeWords
            .filter { it.isEnabled && it.triggerType == WakeWord.TYPE_CHAT && it.phrase.isNotBlank() }
            .sortedByDescending { it.phrase.trim().length }

        for (configured in enabledConfigured) {
            val wake = configured.phrase.trim()
            val wakeLower = wake.lowercase(java.util.Locale.US)
            val regex = Regex("""^(?:hey\s+|ok\s+|okay\s+|hi\s+)?${Regex.escape(wakeLower)}\b[\s,.:;!?-]*""", RegexOption.IGNORE_CASE)
            val match = regex.find(normalized)
            if (match != null) {
                val targetChat = resolveTargetChatForWakeWord(configured)
                val remainder = normalized.substring(match.range.last + 1).trim()
                val scrubbedQuery = AudioCommandSanitizer.stripWakeWordTrigger(
                    rawText = remainder,
                    customWakeWords = listOfNotNull(wake, targetChat?.wakeWord).distinct()
                ).takeIf { it.isNotBlank() }
                return WakePhraseMatchResult(
                    matchedChat = targetChat,
                    matchedWakeWord = wake,
                    followUpQuery = scrubbedQuery
                )
            }
        }

        // 1. Check custom wake words configured on active chats (longest phrase first to avoid partial prefix shadowing)
        val disabledPhrases = configuredWakeWords
            .filter { !it.isEnabled && it.triggerType == WakeWord.TYPE_CHAT }
            .map { it.phrase.trim().lowercase(java.util.Locale.US) }
            .toSet()

        val sortedChats = activeChats
            .filter {
                it.wakeWord.isNotBlank() &&
                    !disabledPhrases.contains(it.wakeWord.trim().lowercase(java.util.Locale.US))
            }
            .sortedByDescending { it.wakeWord.trim().length }

        for (chat in sortedChats) {
            val wake = chat.wakeWord.trim()
            val wakeLower = wake.lowercase(java.util.Locale.US)
            val regex = Regex("""^(?:hey\s+|ok\s+|okay\s+|hi\s+)?${Regex.escape(wakeLower)}\b[\s,.:;!?-]*""", RegexOption.IGNORE_CASE)
            val match = regex.find(normalized)
            if (match != null) {
                val remainder = normalized.substring(match.range.last + 1).trim()
                val scrubbedQuery = AudioCommandSanitizer.stripWakeWordTrigger(
                    rawText = remainder,
                    customWakeWords = listOf(wake)
                ).takeIf { it.isNotBlank() }
                return WakePhraseMatchResult(
                    matchedChat = chat,
                    matchedWakeWord = wake,
                    followUpQuery = scrubbedQuery
                )
            }
        }

        // 2. Check default wake phrases ("hey gemini", "hey jimmy", "ok gemini", "gemini", "jimmy", "open")
        val defaultPhrases = listOf(
            "hey gemini",
            "ok gemini",
            "okay gemini",
            "hi gemini",
            "hey jimmy",
            "ok jimmy",
            "okay jimmy",
            "hi jimmy",
            "gemini",
            "jimmy",
            "open"
        )
        for (phrase in defaultPhrases) {
            val regex = Regex("""^${Regex.escape(phrase)}\b[\s,.:;!?-]*""", RegexOption.IGNORE_CASE)
            val match = regex.find(lower)
            if (match != null) {
                val targetChat = activeChats.firstOrNull { it.isDefaultWakeChat } ?: activeChats.firstOrNull()
                val remainder = normalized.substring(match.range.last + 1).trim()
                val scrubbedQuery = AudioCommandSanitizer.stripWakeWordTrigger(
                    rawText = remainder,
                    customWakeWords = listOfNotNull(phrase, targetChat?.wakeWord)
                ).takeIf { it.isNotBlank() }
                return WakePhraseMatchResult(
                    matchedChat = targetChat,
                    matchedWakeWord = targetChat?.wakeWord?.ifBlank { phrase } ?: phrase,
                    followUpQuery = scrubbedQuery
                )
            }
        }

        // 3. Sensitivity-scaled fuzzy prefix match on configured wake words (using each wake word's individual sensitivity) and active chats
        val strippedSalutation = normalized.replace(Regex("""^(?:hey|ok|okay|hi)\s+""", RegexOption.IGNORE_CASE), "").trim()
        val candidateTexts = listOf(normalized, strippedSalutation).distinct()

        for (candidate in candidateTexts) {
            val words = candidate.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.isEmpty()) continue
            val globalThreshold = wakeWordSensitivity.coerceIn(0.50f, 0.98f)

            for (configured in enabledConfigured) {
                val wake = configured.phrase.trim()
                val wakeWordsList = wake.split(Regex("\\s+")).filter { it.isNotBlank() }
                if (words.size >= wakeWordsList.size) {
                    val prefix = words.take(wakeWordsList.size).joinToString(" ")
                    val effectiveThreshold = configured.sensitivity.coerceIn(0.50f, 0.98f)
                    if (AudioCommandSanitizer.computePhraseSimilarity(prefix, wake) >= effectiveThreshold) {
                        val targetChat = resolveTargetChatForWakeWord(configured)
                        val remainder = words.drop(wakeWordsList.size).joinToString(" ").trim()
                        val scrubbedQuery = AudioCommandSanitizer.stripWakeWordTrigger(
                            rawText = remainder,
                            customWakeWords = listOfNotNull(wake, targetChat?.wakeWord).distinct()
                        ).takeIf { it.isNotBlank() }
                        return WakePhraseMatchResult(
                            matchedChat = targetChat,
                            matchedWakeWord = wake,
                            followUpQuery = scrubbedQuery
                        )
                    }
                }
            }

            val configuredPhrasesLower = enabledConfigured
                .map { it.phrase.trim().lowercase(java.util.Locale.US) }
                .toSet()

            for (chat in sortedChats) {
                val wake = chat.wakeWord.trim()
                if (configuredPhrasesLower.contains(wake.lowercase(java.util.Locale.US))) {
                    continue
                }
                val wakeWordsList = wake.split(Regex("\\s+")).filter { it.isNotBlank() }
                if (words.size >= wakeWordsList.size) {
                    val prefix = words.take(wakeWordsList.size).joinToString(" ")
                    if (AudioCommandSanitizer.computePhraseSimilarity(prefix, wake) >= globalThreshold) {
                        val remainder = words.drop(wakeWordsList.size).joinToString(" ").trim()
                        val scrubbedQuery = AudioCommandSanitizer.stripWakeWordTrigger(
                            rawText = remainder,
                            customWakeWords = listOf(wake)
                        ).takeIf { it.isNotBlank() }
                        return WakePhraseMatchResult(
                            matchedChat = chat,
                            matchedWakeWord = wake,
                            followUpQuery = scrubbedQuery
                        )
                    }
                }
            }
        }

        return null
    }

    fun isRecognitionAvailable(): Boolean {
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        return minBufferSize > 0 && minBufferSize != AudioRecord.ERROR_BAD_VALUE
    }

    /**
     * Amplitude logger & zero-buffer recovery for Samsung One UI / background capture:
     * Verifies read PCM buffers contain non-zero amplitude values (> 0 RMS).
     * If the buffer is pure zeroes, logs a clear warning and cycles AudioSource to MIC.
     */
    internal fun inspectAndLogAmplitude(buffer: ShortArray, readCount: Int = buffer.size): Float {
        if (readCount <= 0 || buffer.isEmpty()) return 0f
        val db = computeRmsDb(buffer, readCount)
        if (db <= 0f || isShortBufferSilent(buffer, readCount)) {
            val zeroStreak = _consecutiveZeroFrames.incrementAndGet()
            _zeroAmplitudeWarningCount.incrementAndGet()
            Log.w(
                TAG,
                "WARNING: Read PCM buffer is pure zeroes (RMS=0.0, readCount=$readCount, streak=$zeroStreak, source=$audioSource). Cycling AudioSource to MediaRecorder.AudioSource.MIC."
            )
            if (audioSource != MediaRecorder.AudioSource.MIC) {
                cycleAudioSourceToMic()
            }
            return 0f
        } else {
            val prevZeroStreak = _consecutiveZeroFrames.getAndSet(0)
            if (prevZeroStreak > 0) {
                Log.d(TAG, "Non-zero PCM amplitude restored: RMS=${db}dB (source=$audioSource)")
            }
            return db
        }
    }

    /**
     * Forces the active AudioSource to MediaRecorder.AudioSource.MIC when zeroed PCM buffers
     * are detected from another audio source (such as VOICE_RECOGNITION or VOICE_COMMUNICATION).
     */
    fun cycleAudioSourceToMic() {
        val previousSource = audioSource
        audioSource = MediaRecorder.AudioSource.MIC
        Log.w(TAG, "Cycling AudioSource from $previousSource to MediaRecorder.AudioSource.MIC")
    }

    internal fun setAudioSourceForTest(source: Int) {
        audioSource = source
    }

    /**
     * Evaluates a single RMS dB sample for dynamic speech activation (RMS dB > 0.0f)
     * and continuous sub-threshold silence cutoff (1,200ms).
     * ONLY applies silence cutoff logic during ACTIVE_STREAMING mode.
     * Returns true if silence cutoff was triggered, false otherwise.
     */
    internal fun processRmsFrame(db: Float, nowMs: Long = System.currentTimeMillis()): Boolean {
        _audioRmsDb.value = db

        // Decouple silence cutoff: ONLY active during ACTIVE_STREAMING mode
        if (_engineState.value != WakeWordManagerState.ACTIVE_STREAMING &&
            _handsFreeState.value != HandsFreeVoiceState.TRANSCRIBING
        ) {
            return false
        }

        if (db > SPEECH_DB_THRESHOLD) {
            if (!isSpeechActive) {
                isSpeechActive = true
                Log.d(TAG, "Speech detected (RMS: $db). Starting capture.")
                onSpeechStarted?.invoke()
            }
            silenceStartTimestamp = 0L
            return false
        } else if (isSpeechActive) {
            if (silenceStartTimestamp == 0L) {
                silenceStartTimestamp = nowMs
                return false
            } else if (nowMs - silenceStartTimestamp >= SILENCE_CUTOFF_MS) {
                Log.d(TAG, "Silence cutoff triggered after ${SILENCE_CUTOFF_MS}ms. Finalizing turn & returning to standby.")
                isSpeechActive = false
                silenceStartTimestamp = 0L
                _isUserVoiceTurn.value = false

                transitionToPassiveListening()
                onSilenceCutoffDetected?.invoke()
                return true
            }
        }
        return false
    }

    /**
     * Converts a non-empty, non-silent 16-bit PCM short buffer into a timestamped AudioPacket
     * and dispatches it to active listeners. Returns null if bytesRead <= 0 or buffer is silent.
     */
    internal fun processCapturedShortFrame(
        shortBuffer: ShortArray,
        shortsRead: Int,
        timestampMs: Long = System.currentTimeMillis()
    ): AudioPacket? {
        if (shortsRead <= 0 || isShortBufferSilent(shortBuffer, shortsRead)) {
            return null
        }
        val bytesRead = shortsRead * 2
        val pcmChunk = ByteArray(bytesRead)
        for (i in 0 until shortsRead) {
            val sample = shortBuffer[i].toInt()
            pcmChunk[i * 2] = (sample and 0xFF).toByte()
            pcmChunk[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        if (!shouldTransmitAudioPacket(bytesRead, pcmChunk)) {
            return null
        }
        recordSnippetPcmBytes(pcmChunk, bytesRead)
        val presentationTs = resolvePresentationTimestamp(timestampMs)
        _lastOutgoingPacketTimestampMs.value = presentationTs
        val packet = AudioPacket(
            pcmBytes = pcmChunk,
            bytesRead = bytesRead,
            presentationTimestampMs = presentationTs
        )
        onAudioPacketCaptured?.invoke(packet)
        onAudioFrameCaptured?.invoke(pcmChunk)
        _audioPacketFlow.tryEmit(packet)
        _audioFrameFlow.tryEmit(pcmChunk)
        return packet
    }

    private fun createInitializedAudioRecord(bufferSize: Int): Pair<AudioRecord?, Int> {
        if (!hasRecordAudioPermission() || bufferSize <= 0) {
            Log.w(TAG, "Skipping AudioRecord creation: RECORD_AUDIO permission not granted or invalid bufferSize=$bufferSize")
            return null to MediaRecorder.AudioSource.MIC
        }
        if (isExternalSpeechRecognizerActive) {
            Log.d(TAG, "Skipping AudioRecord creation: external SpeechRecognizer is active")
            return null to MediaRecorder.AudioSource.MIC
        }
        val preferredSource = audioSource.takeIf {
            it == MediaRecorder.AudioSource.MIC ||
                it == MediaRecorder.AudioSource.UNPROCESSED ||
                it == MediaRecorder.AudioSource.VOICE_RECOGNITION
        } ?: MediaRecorder.AudioSource.MIC

        // Perform a single guarded initialization attempt.
        // Never spin-loop across multiple sources when AudioFlinger returns status -1 / error -20.
        try {
            val candidate = AudioRecord(
                preferredSource,
                SAMPLE_RATE_HZ,
                CHANNEL_CONFIG,
                AUDIO_ENCODING,
                bufferSize
            )
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                _audioRecordInitFailureCount.set(0)
                return candidate to preferredSource
            }
            _audioRecordInitFailureCount.incrementAndGet()
            try {
                candidate.release()
            } catch (_: Throwable) {}
        } catch (e: IllegalArgumentException) {
            _audioRecordInitFailureCount.incrementAndGet()
            Log.w(TAG, "IllegalArgumentException initializing AudioRecord (source=$preferredSource): ${e.message}")
        } catch (e: IllegalStateException) {
            _audioRecordInitFailureCount.incrementAndGet()
            Log.w(TAG, "IllegalStateException initializing AudioRecord (source=$preferredSource): ${e.message}")
        } catch (e: UnsupportedOperationException) {
            _audioRecordInitFailureCount.incrementAndGet()
            Log.w(TAG, "UnsupportedOperationException initializing AudioRecord (source=$preferredSource): ${e.message}")
        } catch (t: Throwable) {
            _audioRecordInitFailureCount.incrementAndGet()
            Log.w(TAG, "Native failure initializing AudioRecord (source=$preferredSource): ${t.message}")
        }
        return null to MediaRecorder.AudioSource.MIC
    }

    /**
     * Releases the active AudioRecord instance inside the hardware mutex so that
     * two AudioRecord instances or SpeechRecognizer + AudioRecord can NEVER run concurrently.
     */
    private fun releaseAudioRecordHardware(stopForegroundService: Boolean) {
        val threadToJoin: Thread?
        synchronized(audioRecordMutex) {
            isRecording = false
            isSpeechActive = false
            silenceStartTimestamp = 0L
            threadToJoin = recordingThread
            recordingThread = null

            try {
                acousticEchoCanceler?.enabled = false
                acousticEchoCanceler?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AcousticEchoCanceler", e)
            }
            acousticEchoCanceler = null
            _isEchoCancelerActive.value = false

            try {
                noiseSuppressor?.enabled = false
                noiseSuppressor?.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing NoiseSuppressor", e)
            }
            noiseSuppressor = null

            val existingRecord = audioRecord
            audioRecord = null
            WakeWordVoiceService.isAppRecordingActive = false

            if (existingRecord != null) {
                WakeWordVoiceService.registerOwnAudioSessionId(existingRecord.audioSessionId)
                try {
                    if (existingRecord.state == AudioRecord.STATE_INITIALIZED) {
                        existingRecord.stop()
                    }
                } catch (e: IllegalStateException) {
                    Log.w(TAG, "IllegalStateException stopping AudioRecord: ${e.message}")
                } catch (e: Exception) {
                    Log.e(TAG, "Error stopping AudioRecord", e)
                }
                try {
                    existingRecord.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Error releasing AudioRecord", e)
                }
                _activeAudioRecordCount.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
            }
            _isListening.value = false
            _audioRmsDb.value = 0f
        }

        if (threadToJoin != null && Thread.currentThread() != threadToJoin) {
            threadToJoin.interrupt()
            try {
                threadToJoin.join(200)
            } catch (_: InterruptedException) {}
        }

        abandonExclusiveAudioFocus()

        if (stopForegroundService) {
            try {
                val stopIntent = Intent(context, WakeWordVoiceService::class.java).apply {
                    action = WakeWordVoiceService.ACTION_STOP_VOICE_SERVICE
                }
                context.startService(stopIntent)
            } catch (_: Exception) {}
        }
    }

    fun startContinuousListening(forceActiveTurn: Boolean = false) {
        if (!hasActiveWindowFocus) {
            Log.d(TAG, "Ignoring startContinuousListening: MainActivity does not have active window focus")
            if (audioRecord != null || isRecording) {
                releaseAudioRecordHardware(stopForegroundService = false)
            }
            return
        }
        if (isIntentionallyPaused || _isSleepMode.value) {
            Log.d(TAG, "Ignoring startContinuousListening: isIntentionallyPaused=$isIntentionallyPaused, isSleepMode=${_isSleepMode.value}")
            if (audioRecord != null || isRecording) {
                releaseAudioRecordHardware(stopForegroundService = false)
            }
            return
        }
        if (isTransitioningAudioState) {
            Log.d(TAG, "Ignoring startContinuousListening(forceActiveTurn=$forceActiveTurn) while isTransitioningAudioState=true")
            return
        }
        if (!hasRecordAudioPermission()) {
            if (audioRecord != null || isRecording) {
                releaseAudioRecordHardware(stopForegroundService = false)
            }
            _statusMessage.value = "Microphone permission required for wake-word listening"
            return
        }
        // Suppress background wake listener from starting or fighting when an active user voice turn is in progress
        if (_isUserVoiceTurn.value && !forceActiveTurn) {
            Log.d(TAG, "Suppressing background wake listener start because isUserVoiceTurn=true")
            return
        }
        if (forceActiveTurn) {
            _isUserVoiceTurn.value = true
        }
        if (_isTextInputFocused.value && !forceActiveTurn) {
            shouldResumeAfterYield = true
            _statusMessage.value = "Paused for keyboard / Gboard input"
            return
        }

        isTransitioningAudioState = true
        WakeWordVoiceService.isTransitioningAudioState = true
        try {
            if (forceActiveTurn || !_isHandsFreeMode.value) {
                transitionToActiveStreaming()
            } else if (_engineState.value != WakeWordManagerState.ACTIVE_STREAMING) {
                transitionToPassiveListening()
            }

            // Mutual Exclusion: if SpeechRecognizer is active, release any lingering AudioRecord and do not open a new one
            if (isExternalSpeechRecognizerActive) {
                if (audioRecord != null || isRecording) {
                    releaseAudioRecordHardware(stopForegroundService = false)
                }
                _statusMessage.value = "Listening for wake word (SpeechRecognizer)..."
                return
            }

            // Release any previous AudioRecord and join its thread BEFORE acquiring audioRecordMutex for creation
            if (audioRecord != null || _isListening.value || isRecording) {
                if (!forceActiveTurn && _isListening.value && audioRecord != null) return
                releaseAudioRecordHardware(stopForegroundService = false)
            }

            synchronized(audioRecordMutex) {
                try {
                    val minBuf = AudioRecord.getMinBufferSize(
                        16000,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                    if (minBuf <= 0) {
                        _statusMessage.value = "Hardware mic initialization failed"
                        return
                    }
                    val bufferSize = maxOf(minBuf * 2, 4096)
                    activeBufferSize = bufferSize

                    // Request AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                    requestExclusiveAudioFocus()

                    val (record, resolvedSource) = createInitializedAudioRecord(bufferSize)
                    audioSource = resolvedSource
                    audioRecord = record

                    if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                        _statusMessage.value = "Microphone failed to initialize"
                        try {
                            record?.release()
                        } catch (_: Throwable) {}
                        audioRecord = null
                        abandonExclusiveAudioFocus()
                        return
                    }

                    _activeAudioRecordCount.incrementAndGet()
                    WakeWordVoiceService.registerOwnAudioSessionId(record.audioSessionId)
                    WakeWordVoiceService.isAppRecordingActive = true

                    // Start microphone foreground service so Android does not mute capture
                    WakeWordVoiceService.startServiceSafely(
                        context = context,
                        action = WakeWordVoiceService.ACTION_START_VOICE_SERVICE
                    )

                    // Attach hardware AcousticEchoCanceler and NoiseSuppressor when available
                    try {
                        if (AcousticEchoCanceler.isAvailable()) {
                            acousticEchoCanceler?.release()
                            acousticEchoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply {
                                enabled = true
                                _isEchoCancelerActive.value = enabled
                            }
                        } else {
                            _isEchoCancelerActive.value = false
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "AcousticEchoCanceler unavailable: ${e.message}")
                        _isEchoCancelerActive.value = false
                    }

                    try {
                        if (NoiseSuppressor.isAvailable()) {
                            noiseSuppressor?.release()
                            noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.apply {
                                enabled = true
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "NoiseSuppressor unavailable: ${e.message}")
                    }

                    isSpeechActive = false
                    silenceStartTimestamp = 0L
                    val sourceLabel = when (resolvedSource) {
                        MediaRecorder.AudioSource.MIC -> "MIC"
                        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
                        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
                        else -> "SOURCE_$resolvedSource"
                    }
                    Log.d(TAG, "AudioRecord STARTING (16000Hz MONO PCM_16BIT $sourceLabel, bufferSize=$bufferSize)")

                    val startedSuccessfully = try {
                        if (!hasActiveWindowFocus) {
                            Log.d(TAG, "Aborting AudioRecord.startRecording(): MainActivity lost window focus")
                            false
                        } else {
                            if (record.state == AudioRecord.STATE_INITIALIZED) {
                                record.startRecording()
                            }
                            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
                        }
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "IllegalStateException calling AudioRecord.startRecording()", e)
                        false
                    } catch (t: Throwable) {
                        Log.e(TAG, "Native error calling AudioRecord.startRecording()", t)
                        false
                    }

                    if (!startedSuccessfully) {
                        _statusMessage.value = "Microphone failed to enter recording state"
                        releaseAudioRecordHardware(stopForegroundService = true)
                        return
                    }

                    isRecording = true
                    _isListening.value = true

                    if (forceActiveTurn || !_isHandsFreeMode.value) {
                        transitionToActiveStreaming()
                    } else if (_engineState.value != WakeWordManagerState.ACTIVE_STREAMING) {
                        transitionToPassiveListening()
                    }
                    _statusMessage.value = "Listening via hardware mic (AudioRecord)..."

                    recordingThread = thread(start = true, name = "PCM-AudioRecordThread") {
                        val shortBuffer = ShortArray(bufferSize / 2)
                        var lastBargeInTime = 0L

                        // Sliding window for RMS & ZCR profiles (last 2 seconds = 200 slots of 10ms)
                        val slidingRmsBuffer = FloatArray(200)
                        val slidingZcrBuffer = FloatArray(200)
                        var slidingPtr = 0
                        var framesProcessed = 0
                        var initRetryAttempt = 0

                        try {
                            while (isRecording && !isIntentionallyPaused && !_isSleepMode.value) {
                                try {
                                    if (isIntentionallyPaused || _isSleepMode.value || isExternalSpeechRecognizerActive || !hasRecordAudioPermission()) {
                                        Log.d(TAG, "AudioRecord loop yielding (paused=$isIntentionallyPaused, sleep=${_isSleepMode.value}, externalRecognizer=$isExternalSpeechRecognizerActive)")
                                        isRecording = false
                                        break
                                    }

                                    val activeRecord = audioRecord
                                    val readSize = if (canReadFromAudioRecord(activeRecord)) {
                                        try {
                                            if (activeRecord!!.state == AudioRecord.STATE_INITIALIZED) {
                                                activeRecord.read(shortBuffer, 0, shortBuffer.size)
                                            } else {
                                                AudioRecord.ERROR_INVALID_OPERATION
                                            }
                                        } catch (e: IllegalStateException) {
                                            Log.w(TAG, "IllegalStateException during AudioRecord.read: ${e.message}")
                                            AudioRecord.ERROR_INVALID_OPERATION
                                        } catch (readEx: Throwable) {
                                            Log.w(TAG, "Transient AudioRecord.read exception: ${readEx.message}")
                                            AudioRecord.ERROR_INVALID_OPERATION
                                        }
                                    } else {
                                        AudioRecord.ERROR_INVALID_OPERATION
                                    }

                                    if (readSize == 0) {
                                        try {
                                            Thread.sleep(20)
                                        } catch (_: InterruptedException) {
                                            break
                                        }
                                        continue
                                    }

                                    if (readSize < 0) {
                                        // Exponential backoff & circuit-breaker abort on initialization/read failure (-20 / NO_INIT)
                                        if (initRetryAttempt >= MAX_AUDIO_RECORD_INIT_RETRIES) {
                                            Log.e(
                                                TAG,
                                                "Aborting AudioRecord capture after $initRetryAttempt failed recovery attempts (readSize=$readSize)"
                                            )
                                            isRecording = false
                                            _isListening.value = false
                                            _statusMessage.value = "Microphone busy or unavailable — paused"
                                            break
                                        }

                                        val backoffMs = calculateExponentialBackoffMs(initRetryAttempt)
                                        initRetryAttempt++
                                        Log.w(
                                            TAG,
                                            "AudioRecord read/init error ($readSize). Backing off for ${backoffMs}ms (attempt $initRetryAttempt/$MAX_AUDIO_RECORD_INIT_RETRIES)"
                                        )
                                        try {
                                            Thread.sleep(backoffMs)
                                        } catch (_: InterruptedException) {
                                            break
                                        }

                                        if (!isRecording || isIntentionallyPaused || _isSleepMode.value || isExternalSpeechRecognizerActive || !hasRecordAudioPermission()) {
                                            break
                                        }

                                        synchronized(audioRecordMutex) {
                                            if (isRecording && !isIntentionallyPaused && !_isSleepMode.value && !isExternalSpeechRecognizerActive && hasRecordAudioPermission()) {
                                                val oldRecord = audioRecord
                                                audioRecord = null
                                                if (oldRecord != null) {
                                                    try {
                                                        if (oldRecord.state == AudioRecord.STATE_INITIALIZED) {
                                                            oldRecord.stop()
                                                        }
                                                    } catch (_: Throwable) {}
                                                    try {
                                                        oldRecord.release()
                                                    } catch (_: Throwable) {}
                                                }
                                                val (recreated, newSrc) = createInitializedAudioRecord(bufferSize)
                                                if (recreated != null && recreated.state == AudioRecord.STATE_INITIALIZED) {
                                                    val restarted = try {
                                                        if (recreated.state == AudioRecord.STATE_INITIALIZED) {
                                                            recreated.startRecording()
                                                        }
                                                        recreated.recordingState == AudioRecord.RECORDSTATE_RECORDING
                                                    } catch (e: IllegalStateException) {
                                                        Log.w(TAG, "IllegalStateException restarting AudioRecord: ${e.message}")
                                                        false
                                                    } catch (_: Throwable) {
                                                        false
                                                    }
                                                    if (restarted) {
                                                        audioSource = newSrc
                                                        audioRecord = recreated
                                                        WakeWordVoiceService.registerOwnAudioSessionId(recreated.audioSessionId)
                                                        initRetryAttempt = 0
                                                        Log.w(TAG, "Recovered background AudioRecord stream (source=$newSrc)")
                                                    } else {
                                                        try {
                                                            recreated.release()
                                                        } catch (_: Throwable) {}
                                                    }
                                                }
                                            }
                                        }
                                        continue
                                    }

                                    initRetryAttempt = 0
                                    val bytesRead = readSize * 2

                                    // Immediately push raw 16-bit PCM byte chunk into the non-blocking AudioPipelineBuffer (trySend)
                                    val pcmChunkBytes = ByteArray(bytesRead)
                                    for (i in 0 until readSize) {
                                        val s = shortBuffer[i].toInt()
                                        pcmChunkBytes[i * 2] = (s and 0xFF).toByte()
                                        pcmChunkBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                                    }
                                    audioPipelineBuffer.trySend(pcmChunkBytes)

                                    // 1. Feed Keyword Spotter / Wake Engine (Continuous in both modes)
                                    onKeywordProcessing?.invoke(shortBuffer.copyOf(readSize))

                                    // 2. Compute volume RMS dB and verify non-zero hardware amplitude (cycle to MIC if zeroed)
                                    val db = inspectAndLogAmplitude(shortBuffer, readSize)

                                    // Compute Zero-Crossing Rate (ZCR) for this frame
                                    var crossings = 0
                                    for (i in 1 until readSize) {
                                        val prev = shortBuffer[i - 1].toInt()
                                        val curr = shortBuffer[i].toInt()
                                        if ((prev >= 0 && curr < 0) || (prev < 0 && curr >= 0)) {
                                            crossings++
                                        }
                                    }
                                    val frameZcr = (crossings.toFloat() / readSize.toFloat()) * 10f

                                    // Buffer non-silent passive speech frames in rolling wake pre-roll & snippet buffer
                                    if (_engineState.value == WakeWordManagerState.PASSIVE_LISTENING &&
                                        !isShortBufferSilent(shortBuffer, readSize)
                                    ) {
                                        appendPreRollPcm(pcmChunkBytes, bytesRead)
                                    }

                                // Update sliding RMS & ZCR buffers for acoustic template matching
                                slidingRmsBuffer[slidingPtr] = db
                                slidingZcrBuffer[slidingPtr] = frameZcr
                                slidingPtr = (slidingPtr + 1) % slidingRmsBuffer.size
                                framesProcessed++

                                // Run acoustic template matching every 8 frames (~80ms) in passive mode when not in an active user voice turn
                                if (framesProcessed % 8 == 0 &&
                                    _engineState.value == WakeWordManagerState.PASSIVE_LISTENING &&
                                    !_isUserVoiceTurn.value
                                ) {
                                    val linearRmsProfile = FloatArray(slidingRmsBuffer.size)
                                    val linearZcrProfile = FloatArray(slidingZcrBuffer.size)
                                    for (i in linearRmsProfile.indices) {
                                        val idx = (slidingPtr + i) % slidingRmsBuffer.size
                                        linearRmsProfile[i] = slidingRmsBuffer[idx]
                                        linearZcrProfile[i] = slidingZcrBuffer[idx]
                                    }

                                    val matchResult = matchAcousticWakeTemplates(linearRmsProfile, linearZcrProfile)
                                    if (matchResult != null) {
                                        val (matchedChat, score) = matchResult
                                        Log.d(TAG, "ACOUSTIC TEMPLATE MATCH HIT! chatId=${matchedChat.id}, score=$score")
                                        onKeywordHit(keyword = matchedChat.wakeWord, initialQuery = null)
                                        break
                                    }
                                }

                                // 3. Handle Active Streaming (Piping non-silent PCM with dynamic timestamp to Gemini)
                                val shouldStreamFrames = _handsFreeState.value == HandsFreeVoiceState.TRANSCRIBING ||
                                    _engineState.value == WakeWordManagerState.ACTIVE_STREAMING ||
                                    !_isHandsFreeMode.value
                                if (shouldStreamFrames && !isShortBufferSilent(shortBuffer, readSize)) {
                                    processCapturedShortFrame(
                                        shortBuffer = shortBuffer,
                                        shortsRead = readSize,
                                        timestampMs = System.currentTimeMillis()
                                    )
                                }

                                if (db > SPEECH_DB_THRESHOLD) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastBargeInTime > 1000) {
                                        lastBargeInTime = now
                                        onBargeInDetected?.invoke()
                                    }
                                }

                                // 4. Auto-silence cutoff logic: in Live Duplex / Hands-Free mode, keep the mic stream alive
                                if (processRmsFrame(db, System.currentTimeMillis())) {
                                    if (!_isLiveDuplexMode.value && !_isHandsFreeMode.value) {
                                        break
                                    }
                                }

                                // 5. Handle Enrollment Sample Capture
                                if (_isEnrollmentRecording.value && onEnrollmentSampleCaptured != null) {
                                    if (db > SPEECH_DB_THRESHOLD && !isShortBufferSilent(shortBuffer, readSize)) {
                                        val enrollmentCaptureBuffer = ShortArray(24000)
                                        var capturedCount = 0

                                        val firstCopy = Math.min(readSize, enrollmentCaptureBuffer.size)
                                        System.arraycopy(shortBuffer, 0, enrollmentCaptureBuffer, 0, firstCopy)
                                        capturedCount += firstCopy

                                        while (capturedCount < enrollmentCaptureBuffer.size && isRecording) {
                                            val currentRec = audioRecord
                                            if (!canReadFromAudioRecord(currentRec)) break
                                            val more = try {
                                                if (currentRec!!.state == AudioRecord.STATE_INITIALIZED) {
                                                    currentRec.read(
                                                        enrollmentCaptureBuffer,
                                                        capturedCount,
                                                        enrollmentCaptureBuffer.size - capturedCount
                                                    )
                                                } else {
                                                    AudioRecord.ERROR_INVALID_OPERATION
                                                }
                                            } catch (e: IllegalStateException) {
                                                break
                                            } catch (_: Throwable) {
                                                break
                                            }
                                            if (more > 0) {
                                                capturedCount += more
                                            } else {
                                                break
                                            }
                                        }

                                        val finalSample = enrollmentCaptureBuffer.copyOf(capturedCount)
                                        if (!isShortBufferSilent(finalSample, finalSample.size)) {
                                            recordSnippetShorts(finalSample, finalSample.size)
                                            onEnrollmentSampleCaptured?.invoke(finalSample)
                                        }
                                        _isEnrollmentRecording.value = false
                                        onEnrollmentSampleCaptured = null
                                    }
                                }
                            } catch (loopEx: InterruptedException) {
                                break
                            } catch (loopEx: Throwable) {
                                Log.w(TAG, "Recovered from exception in background AudioRecord loop: ${loopEx.message}")
                                try {
                                    Thread.sleep(250)
                                } catch (_: InterruptedException) {
                                    break
                                }
                            }
                            }
                        } finally {
                            if (isIntentionallyPaused || _isSleepMode.value) {
                                releaseAudioRecordHardware(stopForegroundService = false)
                            }
                        }
                        Log.d(TAG, "AudioRecord THREAD EXITING")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start AudioRecord", e)
                    _statusMessage.value = "Error starting hardware mic stream"
                    releaseAudioRecordHardware(stopForegroundService = true)
                }
            }
        } finally {
            lastStateTransitionMs = System.currentTimeMillis()
            isTransitioningAudioState = false
            WakeWordVoiceService.isTransitioningAudioState = false
        }
    }

    fun stopContinuousListening() {
        if (isTransitioningAudioState) {
            Log.d(TAG, "Ignoring stopContinuousListening while isTransitioningAudioState=true")
            return
        }
        Log.d(TAG, "AudioRecord STOPPING (Tearing down hardware resources)")
        isTransitioningAudioState = true
        WakeWordVoiceService.isTransitioningAudioState = true
        try {
            _isUserVoiceTurn.value = false
            shouldResumeAfterYield = false
            releaseAudioRecordHardware(stopForegroundService = true)
            if (_isHandsFreeMode.value) {
                _handsFreeState.value = HandsFreeVoiceState.IDLE
            } else {
                _engineState.value = WakeWordManagerState.IDLE
            }
            _statusMessage.value = "Listening paused"
        } finally {
            lastStateTransitionMs = System.currentTimeMillis()
            isTransitioningAudioState = false
            WakeWordVoiceService.isTransitioningAudioState = false
        }
    }

    private var onEnrollmentSampleCaptured: ((ShortArray) -> Unit)? = null

    fun startEnrollmentCapture(onSampleCaptured: (ShortArray) -> Unit) {
        this.onEnrollmentSampleCaptured = onSampleCaptured
        _isEnrollmentRecording.value = true
        // Ensure recording is running
        if (!_isListening.value) {
            startContinuousListening()
        }
    }

    fun stopEnrollmentCapture() {
        this.onEnrollmentSampleCaptured = null
        _isEnrollmentRecording.value = false
    }

    fun destroy() {
        windowFocusDebounceJob?.cancel()
        windowFocusDebounceJob = null
        try {
            audioManager?.unregisterAudioRecordingCallback(audioRecordingCallback)
        } catch (_: Exception) {}
        stopContinuousListening()
    }
}
