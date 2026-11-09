package com.example.voice

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Coordinates Android [SpeechRecognizer] lifecycle and [RecognitionListener] callbacks
 * to prevent repetitive system chime ("ding") restart loops:
 *
 * 1. **No Immediate Restart on `onEndOfSpeech()` or `ERROR_NO_MATCH` (7) / `ERROR_SPEECH_TIMEOUT` (6)**:
 *    - `onEndOfSpeech()` only updates internal state and waits for `onResults()` or `onError()`.
 *    - `ERROR_NO_MATCH` and `ERROR_SPEECH_TIMEOUT` increment [consecutiveIdleTimeouts] and stop
 *      auto-restarting once [maxIdleTimeoutRestarts] is reached.
 * 2. **Debounced Auto-Restart ($\ge 1500\text{ms}$)**:
 *    - Any automatic restart is debounced by at least [MIN_RESTART_DEBOUNCE_MS] (`1500ms`, default
 *      [DEFAULT_RESTART_DEBOUNCE_MS] = `1800ms`) and rate-limited against [lastStartAttemptEpochMs].
 * 3. **Strict Pause / Sleep / Processing Guards**:
 *    - Checks [isIntentionallyPaused], [isAsleep], [isProcessing], and [isExternalGuardBlocked]
 *      both when scheduling and immediately before invoking [SpeechRecognizer.startListening].
 * 4. **Temporary `AudioManager.STREAM_SYSTEM` Mute Around `startListening()`**:
 *    - Temporarily mutes `AudioManager.STREAM_SYSTEM` right before `startListening()` to suppress
 *      the OS start chime and restores the original stream state in `onReadyForSpeech()` (with
 *      safety fallbacks in `onError()`, `stopListening()`, `destroy()`, and a watchdog timer).
 */
class SpeechRecognitionCoordinator(
    context: Context,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val maxIdleTimeoutRestarts: Int = DEFAULT_MAX_IDLE_TIMEOUT_RESTARTS,
    private val maxTransientErrorRestarts: Int = DEFAULT_MAX_TRANSIENT_ERROR_RESTARTS,
    private val muteSystemChimeOnStart: Boolean = true,
    private val hasRecordAudioPermission: () -> Boolean = { true },
    private val hasActiveWindowFocus: () -> Boolean = { true },
    private val isContinuousModeEnabled: () -> Boolean = { false },
    private val isExternalGuardBlocked: () -> Boolean = { false },
    private val onPrepareHardwareBeforeStart: () -> Unit = {},
    private val onReadyStateChanged: (Boolean, String) -> Unit = { _, _ -> },
    private val onStatusMessage: (String) -> Unit = {},
    private val onRmsChangedCallback: (Float) -> Unit = {},
    private val onAudioBufferReceived: (ByteArray) -> Unit = {},
    private val onPartialTranscripts: (List<String>) -> Unit = {},
    private val onFinalTranscripts: (List<String>) -> Boolean = { false },
    private val activeWakeWordsProvider: () -> Collection<String> = { emptyList() },
    private val onOneBreathWakeQueryFinalized: (matchedWakePhrase: String, trailingPrompt: String) -> Boolean = { _, _ -> false },
    private val onBargeInSpeechDetected: () -> Unit = {},
    private val onInsufficientPermissions: () -> Unit = {},
    private val onSilenceTimeoutDuringVoiceTurn: () -> Unit = {},
    private val onIdleThresholdReached: () -> Unit = {},
    private val onFallbackToPcmRequested: () -> Unit = {},
    private val onDiagnosticTrace: (stage: String, details: String) -> Unit = { _, _ -> }
) {

    companion object {
        private const val TAG = "SpeechRecCoordinator"

        /** Minimum required debounce delay (1500ms) before any automatic restart attempt. */
        const val MIN_RESTART_DEBOUNCE_MS = 1500L

        /** Default debounced delay (1800ms) between recognition cycles in continuous mode. */
        const val DEFAULT_RESTART_DEBOUNCE_MS = 1800L

        /** Maximum back-to-back ERROR_NO_MATCH (7) / ERROR_SPEECH_TIMEOUT (6) restarts before pausing. */
        const val DEFAULT_MAX_IDLE_TIMEOUT_RESTARTS = 3

        /** Maximum consecutive non-timeout errors before switching to silent PCM fallback. */
        const val DEFAULT_MAX_TRANSIENT_ERROR_RESTARTS = 3

        /** Safety timeout to guarantee STREAM_SYSTEM is unmuted even if onReadyForSpeech never fires. */
        const val SYSTEM_STREAM_UNMUTE_WATCHDOG_MS = 600L

        /** Trailing silence debounce window (1250ms) to auto-finalize one-breath wake queries from partial transcripts. */
        const val TRAILING_SILENCE_DEBOUNCE_MS = 1250L

        /** Required 500ms debounce window before re-arming SpeechRecognizer on AUDIOFOCUS_GAIN. */
        const val AUDIOFOCUS_GAIN_REARM_DEBOUNCE_MS = 500L

        /** Strict 1500ms debounce window for windowFocus transitions to prevent rapid start/stop cycling on transient focus losses. */
        const val WINDOW_FOCUS_DEBOUNCE_MS = 1500L

        fun errorCodeToString(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT(1)"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK(2)"
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO(3)"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER(4)"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT(5)"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT(6)"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH(7)"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY(8)"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS(9)"
            else -> "ERROR_UNKNOWN($error)"
        }

        /**
         * Inspects [rawTranscript] for a leading wake phrase (such as "Hey Jimmy", "Hey Gemini",
         * or any phrase in [customWakeWords]), strips the matched wake phrase prefix cleanly,
         * and returns `(detectedWakePhrase, strippedTranscript)`.
         */
        fun extractWakeTriggeredTranscript(
            rawTranscript: String,
            customWakeWords: Collection<String> = emptyList()
        ): Pair<Boolean, String> {
            val trimmed = rawTranscript.trim()
            if (trimmed.isBlank()) return false to ""
            val stripped = AudioCommandSanitizer.stripWakeWordTrigger(
                rawText = trimmed,
                customWakeWords = customWakeWords
            )
            val hadWakePrefix = !stripped.equals(trimmed, ignoreCase = true) ||
                AudioCommandSanitizer.isPureWakeWordTrigger(trimmed, customWakeWords)
            return hadWakePrefix to stripped
        }

        /**
         * Extracts `(matchedWakePhrase, strippedTrailingPrompt)` when [rawTranscript] starts with
         * a configured or built-in wake word. Returns `null` if no wake word prefix is present,
         * or if the transcript is a dedicated voice navigation/action command ("open chat", "open history",
         * "show history", "open settings", etc.) so it is routed via in-app NavController instead of chat send.
         */
        fun extractMatchedWakeWordAndPrompt(
            rawTranscript: String,
            customWakeWords: Collection<String> = emptyList()
        ): Pair<String, String>? {
            val trimmed = rawTranscript.trim()
            if (trimmed.isBlank()) return null
            if (AudioCommandSanitizer.isVoiceHistoryNavigationCommand(trimmed, customWakeWords) ||
                VoiceCommandDispatcher.isStandaloneNavigationOrActionCommand(trimmed, customWakeWords)
            ) {
                return null
            }
            val (detected, stripped) = extractWakeTriggeredTranscript(trimmed, customWakeWords)
            if (!detected) return null
            val matchedCandidate = customWakeWords
                .filter { it.isNotBlank() }
                .sortedByDescending { it.length }
                .firstOrNull { trimmed.lowercase().startsWith(it.trim().lowercase()) }
                ?: "Hey Gemini"
            return matchedCandidate to stripped.trim()
        }
    }

    private val appContext: Context = context.applicationContext
    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var speechRecognizer: SpeechRecognizer? = null
    private var restartJob: Job? = null
    private var windowFocusDebounceJob: Job? = null
    private var unmuteWatchdogJob: Job? = null
    private var trailingSilenceDebounceJob: Job? = null
    private var lastPartialCandidates: List<String> = emptyList()

    val hasPendingRestart: Boolean
        get() = restartJob?.isActive == true

    val hasPendingWindowFocusDebounce: Boolean
        get() = windowFocusDebounceJob?.isActive == true

    var recognizerRecreationCount: Int = 0
        private set

    var destroyCount: Int = 0
        private set

    val hasActiveRecognizerInstance: Boolean
        get() = speechRecognizer != null

    /**
     * Window focus guard: `startListening()` and `AudioRecord.startRecording()` fallback are blocked
     * whenever `MainActivity` does not have active window focus.
     */
    @Volatile
    var isWindowFocused: Boolean = true
        private set

    /**
     * Strict transient focus loss flag: set to `true` immediately when window focus is lost
     * (`setWindowFocused(false)`) and only cleared after the 1500ms focus-gain debounce completes.
     * While `true`, `onError(ERROR_CLIENT)` returns immediately without recreating the recognizer
     * or queuing any restart.
     */
    @Volatile
    var isTransientFocusLoss: Boolean = false
        internal set

    /**
     * Transient audio focus loss flag set on `AUDIOFOCUS_LOSS_TRANSIENT` (-2) when
     * `SpeechRecognizer.cancel()` is invoked. Prevents immediate restarts and is checked on
     * `AUDIOFOCUS_GAIN` (1) before scheduling the 500ms debounced restart.
     */
    @Volatile
    var isTransientAudioFocusLoss: Boolean = false
        internal set

    /**
     * Guard flag indicating listening was intentionally paused by the user, by UI focus,
     * or by hitting the consecutive idle timeout threshold. When `true`, `startListening()`
     * is completely aborted until explicitly resumed.
     */
    @Volatile
    var isIntentionallyPaused: Boolean = false
        private set

    /**
     * Guard flag indicating the app is in Sleep Mode (e.g., after `"Gemini, sleep"`).
     * When `true`, `startListening()` and auto-restarts are completely aborted.
     */
    @Volatile
    var isAsleep: Boolean = false
        private set

    /**
     * Guard flag indicating the app is currently processing a voice turn, generating an AI
     * response, or speaking TTS audio. When `true`, `startListening()` is completely aborted.
     */
    @Volatile
    var isProcessing: Boolean = false
        private set

    var isProcessingCommand: Boolean
        get() = isProcessing
        set(value) {
            isProcessing = value
            refreshMutexSnapshotFlow()
        }

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    /**
     * Indicates whether the coordinator is currently running an explicit in-chat dictation session
     * initiated from the active chat composer microphone button.
     */
    @Volatile
    var isInChatDictationMode: Boolean = false
        private set

    private val _isDictating = MutableStateFlow(false)
    val isDictating: StateFlow<Boolean> = _isDictating.asStateFlow()

    private val _liveRmsDb = MutableStateFlow(0f)
    val liveRmsDb: StateFlow<Float> = _liveRmsDb.asStateFlow()

    private val _errorCount = MutableStateFlow(0)
    val errorCount: StateFlow<Int> = _errorCount.asStateFlow()

    private val _traceHistory = MutableStateFlow<List<String>>(emptyList())
    val traceHistory: StateFlow<List<String>> = _traceHistory.asStateFlow()

    var consecutiveIdleTimeouts: Int = 0
        private set

    var consecutiveTransientErrors: Int = 0
        private set

    var lastStartAttemptEpochMs: Long = 0L
        private set

    data class CoordinatorMutexSnapshot(
        val isListening: Boolean = false,
        val isWindowFocused: Boolean = true,
        val isTransientFocusLoss: Boolean = false,
        val isIntentionallyPaused: Boolean = false,
        val isProcessing: Boolean = false,
        val isProcessingCommand: Boolean = false,
        val isAsleep: Boolean = false,
        val isInChatDictationMode: Boolean = false,
        val consecutiveIdleTimeouts: Int = 0,
        val consecutiveTransientErrors: Int = 0,
        val totalErrors: Int = 0,
        val lastStartAttemptEpochMs: Long = 0L,
        val isSystemStreamMuted: Boolean = false
    )

    private val _mutexSnapshotFlow = MutableStateFlow(CoordinatorMutexSnapshot())
    val mutexSnapshotFlow: StateFlow<CoordinatorMutexSnapshot> = _mutexSnapshotFlow.asStateFlow()

    fun getMutexSnapshot(): CoordinatorMutexSnapshot {
        val windowFocusedNow = isWindowFocused && hasActiveWindowFocus()
        return CoordinatorMutexSnapshot(
            isListening = _isListening.value,
            isWindowFocused = windowFocusedNow,
            isTransientFocusLoss = isTransientFocusLoss,
            isIntentionallyPaused = isIntentionallyPaused,
            isProcessing = isProcessing,
            isProcessingCommand = isProcessing,
            isAsleep = isAsleep,
            isInChatDictationMode = isInChatDictationMode,
            consecutiveIdleTimeouts = consecutiveIdleTimeouts,
            consecutiveTransientErrors = consecutiveTransientErrors,
            totalErrors = _errorCount.value,
            lastStartAttemptEpochMs = lastStartAttemptEpochMs,
            isSystemStreamMuted = isSystemStreamMutedByCoordinator
        )
    }

    private fun refreshMutexSnapshotFlow() {
        _mutexSnapshotFlow.value = getMutexSnapshot()
    }

    fun clearTraceHistory() {
        _traceHistory.value = emptyList()
        refreshMutexSnapshotFlow()
    }

    // Saved AudioManager.STREAM_SYSTEM state for temporary chime suppression
    private var isSystemStreamMutedByCoordinator: Boolean = false
    private var savedSystemStreamVolume: Int = -1
    private var wasSystemStreamAlreadyMuted: Boolean = false

    private fun isOnMainThread(): Boolean {
        val mainLooper = android.os.Looper.getMainLooper() ?: return true
        return android.os.Looper.myLooper() == mainLooper
    }

    internal fun recordTrace(stage: String, details: String, warnIfOffMain: Boolean = false) {
        val thread = Thread.currentThread()
        val onMain = isOnMainThread()
        val windowFocusedNow = isWindowFocused && hasActiveWindowFocus()
        val mutexSnapshot =
            "listening=${_isListening.value} windowFocus=$windowFocusedNow transientLoss=$isTransientFocusLoss paused=$isIntentionallyPaused processing=$isProcessing asleep=$isAsleep dictating=$isInChatDictationMode"
        val entry =
            "[SR-Trace] stage=$stage | thread=${thread.name}#${thread.id}(main=$onMain) | $mutexSnapshot | $details"
        if (warnIfOffMain && !onMain) {
            Log.w(TAG, "$entry [OFF-MAIN-THREAD]")
        } else {
            Log.d(TAG, entry)
        }
        _traceHistory.update { current ->
            (listOf(entry) + current).take(120)
        }
        refreshMutexSnapshotFlow()
        onDiagnosticTrace(stage, "$mutexSnapshot | $details")
    }

    /**
     * Updates the active window focus state from `MainActivity.onWindowFocusChanged`.
     * - When focus is lost (`focused == false`): immediately sets `isTransientFocusLoss = true` and
     *   cancels `windowFocusDebounceJob` and `restartJob`. Does NOT recreate or restart [SpeechRecognizer].
     * - When focus is gained (`focused == true`): waits the full [WINDOW_FOCUS_DEBOUNCE_MS] (`1500ms`)
     *   before clearing `isTransientFocusLoss = false` and re-arming if idle.
     */
    fun setWindowFocused(focused: Boolean) {
        isWindowFocused = focused
        windowFocusDebounceJob?.cancel()
        windowFocusDebounceJob = null
        if (!focused) {
            isTransientFocusLoss = true
            restartJob?.cancel()
            restartJob = null
            recordTrace(
                "SET_WINDOW_FOCUSED",
                "focused=false isTransientFocusLoss=true -> cancelled windowFocusDebounceJob & restartJob"
            )
        } else {
            recordTrace(
                "SET_WINDOW_FOCUSED",
                "focused=true isTransientFocusLoss=$isTransientFocusLoss debounceMs=$WINDOW_FOCUS_DEBOUNCE_MS"
            )
            windowFocusDebounceJob = scope.launch(mainDispatcher) {
                delay(WINDOW_FOCUS_DEBOUNCE_MS)
                if (!isWindowFocused) return@launch
                isTransientFocusLoss = false
                val isHandsFreeModeEnabled = isContinuousModeEnabled() && !isAsleep
                val isProcessingCommandNow = isProcessing || isExternalGuardBlocked()
                if (isHandsFreeModeEnabled && !isProcessingCommandNow) {
                    isIntentionallyPaused = false
                    consecutiveIdleTimeouts = 0
                    consecutiveTransientErrors = 0
                    recordTrace(
                        "SET_WINDOW_FOCUSED_REARM",
                        "focused=true isTransientFocusLoss=false -> debounced 1500ms re-arm"
                    )
                    if (!_isListening.value && !hasPendingRestart) {
                        startListening(userInitiated = false)
                    }
                }
            }
        }
    }

    /**
     * Sets [isIntentionallyPaused]. When `true`, cancels any pending restart and stops active listening.
     */
    fun setIntentionallyPaused(paused: Boolean) {
        isIntentionallyPaused = paused
        recordTrace("SET_INTENTIONALLY_PAUSED", "paused=$paused")
        if (paused) {
            stopListening(markIntentionallyPaused = true)
        }
    }

    /**
     * Enters a transient command-processing state (`isProcessingCommand = true`) before a voice
     * command or external Intent is dispatched, without setting `isIntentionallyPaused = true`
     * or disabling the user's Hands-Free Radar state.
     */
    fun pauseForCommandDispatch(reason: String = "voice_navigation_command") {
        isProcessing = true
        isIntentionallyPaused = false
        recordTrace(
            "PAUSE_FOR_COMMAND_DISPATCH",
            "reason=$reason (transient isProcessingCommand=true, isIntentionallyPaused=false)"
        )
        stopListening(markIntentionallyPaused = false)
    }

    /**
     * Completes command processing (`isProcessingCommand = false`) and re-arms continuous
     * hands-free listening without requiring a manual radar toggle.
     */
    fun completeCommandProcessingAndRearm(
        delayMs: Long = DEFAULT_RESTART_DEBOUNCE_MS,
        reason: String = "command_completed"
    ) {
        resumeAfterCommandNavigation(delayMs = delayMs, reason = reason)
    }

    /**
     * Clears the command-dispatch suspension guard after the in-app navigation transition
     * settles and schedules a debounced passive wake-word listening restart if continuous mode
     * is enabled and not in Sleep Mode.
     */
    fun resumeAfterCommandNavigation(
        delayMs: Long = DEFAULT_RESTART_DEBOUNCE_MS,
        reason: String = "navigation_settled"
    ) {
        isProcessing = false
        if (isWindowFocused) {
            isTransientFocusLoss = false
        }
        if (!isAsleep) {
            isIntentionallyPaused = false
            consecutiveIdleTimeouts = 0
            val canRestart = isContinuousModeEnabled() && !shouldAbortListening()
            recordTrace(
                "RESUME_AFTER_COMMAND_NAVIGATION",
                "reason=$reason delayMs=$delayMs continuous=${isContinuousModeEnabled()} canRestart=$canRestart"
            )
            if (canRestart) {
                scheduleDebouncedRestart(delayMs = delayMs)
            }
        } else {
            recordTrace(
                "RESUME_AFTER_COMMAND_SKIPPED",
                "reason=$reason skipped because isAsleep=true"
            )
        }
    }

    /**
     * Updates the Sleep Mode state. When `asleep == true`, stops active recognition and aborts restarts.
     */
    fun setSleeping(asleep: Boolean) {
        isAsleep = asleep
        recordTrace("SET_SLEEPING", "asleep=$asleep")
        if (asleep) {
            stopListening(markIntentionallyPaused = true)
        } else {
            isIntentionallyPaused = false
            consecutiveIdleTimeouts = 0
        }
    }

    /**
     * Updates the processing guard state (e.g., AI generation or TTS playback in progress).
     */
    fun setProcessingState(processing: Boolean) {
        isProcessing = processing
        recordTrace("SET_PROCESSING_STATE", "processing=$processing")
        if (processing) {
            restartJob?.cancel()
            restartJob = null
        }
    }

    /**
     * Enables or disables explicit in-chat dictation mode for the active chat composer.
     * Always executes in-process via [SpeechRecognizer] so the app never leaves the foreground.
     */
    fun setInChatDictationMode(enabled: Boolean) {
        isInChatDictationMode = enabled
        _isDictating.value = enabled
        if (enabled) {
            isIntentionallyPaused = false
            consecutiveIdleTimeouts = 0
            consecutiveTransientErrors = 0
        }
    }

    /**
     * Toggles in-chat dictation on or off using the on-device [SpeechRecognizer] instance.
     * Returns `true` if in-chat dictation is now active.
     */
    fun toggleInChatDictation(): Boolean {
        return if (isInChatDictationMode) {
            setInChatDictationMode(false)
            stopListening(markIntentionallyPaused = !isContinuousModeEnabled())
            if (isContinuousModeEnabled() && !shouldAbortListening()) {
                scheduleDebouncedRestart(delayMs = DEFAULT_RESTART_DEBOUNCE_MS)
            }
            false
        } else {
            if (_isListening.value) {
                stopListening(markIntentionallyPaused = false)
            }
            setInChatDictationMode(true)
            val started = startListening(userInitiated = true)
            if (!started) {
                setInChatDictationMode(false)
            }
            started
        }
    }

    /**
     * Returns `true` if any window focus, pause, sleep, processing, or permission guard currently
     * blocks starting the [SpeechRecognizer] or `AudioRecord.startRecording()`.
     */
    fun shouldAbortListening(): Boolean {
        val continuousHandsFreeActive = isContinuousModeEnabled() && !isAsleep && !isInChatDictationMode
        if (!continuousHandsFreeActive && (!isWindowFocused || !hasActiveWindowFocus())) {
            Log.d(TAG, "Aborting startListening: MainActivity does not have active window focus")
            return true
        }
        if (isIntentionallyPaused) {
            Log.d(TAG, "Aborting startListening: isIntentionallyPaused=true")
            return true
        }
        if (isAsleep) {
            Log.d(TAG, "Aborting startListening: isAsleep=true")
            return true
        }
        if (isProcessing) {
            Log.d(TAG, "Aborting startListening: isProcessing=true")
            return true
        }
        if (isExternalGuardBlocked()) {
            Log.d(TAG, "Aborting startListening: external guard blocked")
            return true
        }
        if (!hasRecordAudioPermission()) {
            Log.d(TAG, "Aborting startListening: RECORD_AUDIO permission not granted")
            return true
        }
        return false
    }

    /**
     * Builds the [RecognizerIntent] configured with free-form speech model and silence windows.
     */
    fun createRecognizerIntent(): Intent {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        return WakeWordManager.configureRecognizerIntent(
            intent = intent,
            callingPackage = appContext.packageName
        )
    }

    /**
     * Temporarily mutes [AudioManager.STREAM_SYSTEM] prior to calling [SpeechRecognizer.startListening]
     * so the repetitive OS start chime ("ding") is silenced during continuous recognition.
     * Restored immediately in [RecognitionListener.onReadyForSpeech] or by [unmuteWatchdogJob].
     */
    internal fun muteSystemStreamForStartChime() {
        if (!muteSystemChimeOnStart) return
        val am = audioManager ?: return
        try {
            if (!isSystemStreamMutedByCoordinator) {
                savedSystemStreamVolume = am.getStreamVolume(AudioManager.STREAM_SYSTEM)
                wasSystemStreamAlreadyMuted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.isStreamMute(AudioManager.STREAM_SYSTEM)
                } else {
                    savedSystemStreamVolume == 0
                }
                if (!wasSystemStreamAlreadyMuted) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        am.adjustStreamVolume(
                            AudioManager.STREAM_SYSTEM,
                            AudioManager.ADJUST_MUTE,
                            0
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        am.setStreamMute(AudioManager.STREAM_SYSTEM, true)
                    }
                    isSystemStreamMutedByCoordinator = true
                }
            }
            unmuteWatchdogJob?.cancel()
            unmuteWatchdogJob = scope.launch(mainDispatcher) {
                delay(SYSTEM_STREAM_UNMUTE_WATCHDOG_MS)
                restoreSystemStreamAfterStartChime()
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "Cannot mute STREAM_SYSTEM due to DND policy: ${se.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mute STREAM_SYSTEM: ${e.message}")
        }
    }

    /**
     * Restores [AudioManager.STREAM_SYSTEM] volume/mute state after [SpeechRecognizer] is ready
     * or if recognition stops/errors.
     */
    internal fun restoreSystemStreamAfterStartChime() {
        unmuteWatchdogJob?.cancel()
        unmuteWatchdogJob = null
        if (!isSystemStreamMutedByCoordinator) return
        val am = audioManager
        isSystemStreamMutedByCoordinator = false
        if (am == null) return
        try {
            if (!wasSystemStreamAlreadyMuted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.adjustStreamVolume(
                        AudioManager.STREAM_SYSTEM,
                        AudioManager.ADJUST_UNMUTE,
                        0
                    )
                } else {
                    @Suppress("DEPRECATION")
                    am.setStreamMute(AudioManager.STREAM_SYSTEM, false)
                }
                if (savedSystemStreamVolume > 0) {
                    am.setStreamVolume(AudioManager.STREAM_SYSTEM, savedSystemStreamVolume, 0)
                }
            }
        } catch (se: SecurityException) {
            Log.w(TAG, "Cannot restore STREAM_SYSTEM due to DND policy: ${se.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore STREAM_SYSTEM: ${e.message}")
        } finally {
            savedSystemStreamVolume = -1
            wasSystemStreamAlreadyMuted = false
        }
    }

    /**
     * Returns `true` if `AudioManager.STREAM_SYSTEM` is currently muted by this coordinator.
     */
    internal fun isSystemStreamCurrentlyMutedByCoordinator(): Boolean =
        isSystemStreamMutedByCoordinator

    /**
     * Starts listening with [SpeechRecognizer] if `MainActivity` has active window focus,
     * all state guards pass, and the minimum debounce window has elapsed (unless [userInitiated] is `true`).
     */
    fun startListening(userInitiated: Boolean = false): Boolean {
        restartJob?.cancel()
        restartJob = null

        val continuousHandsFreeActive = isContinuousModeEnabled() && !isAsleep && !isInChatDictationMode
        if (!continuousHandsFreeActive && (!isWindowFocused || !hasActiveWindowFocus())) {
            recordTrace(
                "START_LISTENING_ABORTED_NO_WINDOW_FOCUS",
                "userInitiated=$userInitiated windowFocused=false"
            )
            return false
        }

        if (!userInitiated && (isTransientFocusLoss || isTransientAudioFocusLoss)) {
            recordTrace(
                "START_LISTENING_ABORTED_TRANSIENT_FOCUS_LOSS",
                "userInitiated=false isTransientFocusLoss=$isTransientFocusLoss isTransientAudioFocusLoss=$isTransientAudioFocusLoss"
            )
            return false
        }

        if (userInitiated) {
            if (isWindowFocused && hasActiveWindowFocus()) {
                isTransientFocusLoss = false
            }
            isTransientAudioFocusLoss = false
            isIntentionallyPaused = false
            consecutiveIdleTimeouts = 0
            consecutiveTransientErrors = 0
        }

        if (shouldAbortListening()) {
            recordTrace("START_LISTENING_ABORTED", "userInitiated=$userInitiated")
            return false
        }

        if (_isListening.value) {
            recordTrace("START_LISTENING_SKIPPED_ALREADY_ACTIVE", "userInitiated=$userInitiated")
            return false
        }

        val now = clockMs()
        val elapsedSinceLastStart = now - lastStartAttemptEpochMs
        if (!userInitiated && lastStartAttemptEpochMs > 0L && elapsedSinceLastStart < MIN_RESTART_DEBOUNCE_MS) {
            val remainingMs = (MIN_RESTART_DEBOUNCE_MS - elapsedSinceLastStart).coerceAtLeast(MIN_RESTART_DEBOUNCE_MS)
            recordTrace(
                "START_LISTENING_RATE_LIMITED",
                "elapsedMs=$elapsedSinceLastStart schedulingAfterMs=$remainingMs"
            )
            scheduleDebouncedRestart(delayMs = remainingMs)
            return false
        }

        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            recordTrace("START_LISTENING_UNAVAILABLE", "SpeechRecognizer.isRecognitionAvailable=false")
            return false
        }

        if (!isOnMainThread()) {
            recordTrace(
                "START_LISTENING_MARSHAL_TO_MAIN",
                "userInitiated=$userInitiated called off main thread; marshaling to mainDispatcher",
                warnIfOffMain = true
            )
            scope.launch(mainDispatcher) {
                startListening(userInitiated = userInitiated)
            }
            return true
        }

        if (!continuousHandsFreeActive && (!isWindowFocused || !hasActiveWindowFocus())) {
            recordTrace(
                "START_LISTENING_ABORTED_NO_WINDOW_FOCUS",
                "userInitiated=$userInitiated lost window focus before start"
            )
            return false
        }

        return try {
            onPrepareHardwareBeforeStart()
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext).apply {
                    setRecognitionListener(recognitionListener)
                }
            }
            lastStartAttemptEpochMs = now
            muteSystemStreamForStartChime()
            val intent = createRecognizerIntent()
            speechRecognizer?.startListening(intent)
            _isListening.value = true
            recordTrace("START_LISTENING_SUCCESS", "userInitiated=$userInitiated")
            onReadyStateChanged(
                true,
                if (isInChatDictationMode) {
                    "Dictating into chat composer..."
                } else {
                    "Listening for wake word (SpeechRecognizer)..."
                }
            )
            true
        } catch (e: Exception) {
            restoreSystemStreamAfterStartChime()
            recordTrace("START_LISTENING_EXCEPTION", "error=${e.message}", warnIfOffMain = true)
            _isListening.value = false
            setInChatDictationMode(false)
            onReadyStateChanged(false, "SpeechRecognizer unavailable")
            false
        }
    }

    /**
     * Schedules a debounced auto-restart of [SpeechRecognizer].
     * Enforces a minimum delay of [MIN_RESTART_DEBOUNCE_MS] (`1500ms`) so the system chime is
     * never hammered in a tight loop, and re-verifies all guards before starting.
     */
    fun scheduleDebouncedRestart(delayMs: Long = DEFAULT_RESTART_DEBOUNCE_MS) {
        restartJob?.cancel()
        restartJob = null

        if (isTransientFocusLoss || isTransientAudioFocusLoss || shouldAbortListening() || !isContinuousModeEnabled()) {
            recordTrace(
                "SCHEDULE_RESTART_SKIPPED",
                "delayMs=$delayMs transientLoss=$isTransientFocusLoss transientAudioLoss=$isTransientAudioFocusLoss continuous=${isContinuousModeEnabled()}"
            )
            return
        }

        val safeDelayMs = delayMs.coerceAtLeast(MIN_RESTART_DEBOUNCE_MS)
        recordTrace("SCHEDULE_RESTART_QUEUED", "safeDelayMs=$safeDelayMs")
        restartJob = scope.launch(mainDispatcher) {
            delay(safeDelayMs)
            if (!isTransientFocusLoss && !isTransientAudioFocusLoss && !shouldAbortListening() && isContinuousModeEnabled() && !_isListening.value) {
                recordTrace("SCHEDULE_RESTART_FIRING", "afterDelayMs=$safeDelayMs")
                startListening(userInitiated = false)
            } else {
                recordTrace(
                    "SCHEDULE_RESTART_CANCELLED_AT_FIRE",
                    "afterDelayMs=$safeDelayMs transientLoss=$isTransientFocusLoss transientAudioLoss=$isTransientAudioFocusLoss continuous=${isContinuousModeEnabled()}"
                )
            }
        }
    }

    /**
     * Dedicated [AudioManager.OnAudioFocusChangeListener] for [SpeechRecognizer] audio focus handling.
     */
    val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        handleAudioFocusChange(focusChange)
    }

    /**
     * Cancels the active [SpeechRecognizer] session immediately on transient audio focus loss
     * (`AUDIOFOCUS_LOSS_TRANSIENT = -2`) without calling `stopListening()` or triggering an immediate restart.
     */
    private fun cancelRecognizerOnTransientAudioFocusLoss() {
        restartJob?.cancel()
        restartJob = null
        trailingSilenceDebounceJob?.cancel()
        trailingSilenceDebounceJob = null
        lastPartialCandidates = emptyList()
        _isListening.value = false
        _liveRmsDb.value = 0f
        restoreSystemStreamAfterStartChime()
        if (isOnMainThread()) {
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {
            }
        } else {
            scope.launch(mainDispatcher) {
                try {
                    speechRecognizer?.cancel()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Handles AudioManager audio focus transitions for SpeechRecognizer:
     * - On [AudioManager.AUDIOFOCUS_LOSS_TRANSIENT] (`-2`, and [AudioManager.AUDIOFOCUS_LOSS] `-1`),
     *   sets [isTransientAudioFocusLoss] = `true` and calls `SpeechRecognizer.cancel()` — does NOT restart immediately.
     * - Does NOT yield on [AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK].
     * - On [AudioManager.AUDIOFOCUS_GAIN] (`1`), checks [isTransientAudioFocusLoss] and only restarts
     *   listening after a 500ms debounced delay ([AUDIOFOCUS_GAIN_REARM_DEBOUNCE_MS]).
     */
    fun handleAudioFocusChange(focusChange: Int) {
        val responseLabel = WakeWordVoiceService.formatAudioFocusChange(focusChange)
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS -> {
                isTransientAudioFocusLoss = true
                recordTrace(
                    "AUDIOFOCUS_YIELD",
                    "response=$responseLabel focusChange=$focusChange isTransientAudioFocusLoss=true -> calling SpeechRecognizer.cancel() without immediate restart"
                )
                cancelRecognizerOnTransientAudioFocusLoss()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                recordTrace(
                    "AUDIOFOCUS_CAN_DUCK_IGNORED",
                    "response=$responseLabel focusChange=$focusChange -> keeping SpeechRecognizer active without yielding"
                )
            }
            AudioManager.AUDIOFOCUS_GAIN,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE -> {
                val shouldRearmAfterLoss = isTransientAudioFocusLoss || isTransientFocusLoss || !_isListening.value
                isTransientAudioFocusLoss = false
                if (shouldRearmAfterLoss) {
                    recordTrace(
                        "AUDIOFOCUS_GAIN_DEBOUNCE",
                        "response=$responseLabel focusChange=$focusChange -> scheduling 500ms debounced re-arm"
                    )
                    scheduleAudioFocusGainRearm(delayMs = AUDIOFOCUS_GAIN_REARM_DEBOUNCE_MS)
                }
            }
            else -> {
                recordTrace(
                    "AUDIOFOCUS_CALLBACK_UNHANDLED",
                    "response=$responseLabel focusChange=$focusChange"
                )
            }
        }
    }

    /**
     * Schedules a 500ms debounced re-arm of [SpeechRecognizer] when audio focus is regained
     * ([AudioManager.AUDIOFOCUS_GAIN]).
     */
    fun scheduleAudioFocusGainRearm(delayMs: Long = AUDIOFOCUS_GAIN_REARM_DEBOUNCE_MS) {
        restartJob?.cancel()
        restartJob = null

        isTransientAudioFocusLoss = false
        if (isWindowFocused) {
            isTransientFocusLoss = false
        }
        if (isTransientFocusLoss || isTransientAudioFocusLoss || shouldAbortListening() || !isContinuousModeEnabled()) {
            recordTrace(
                "AUDIOFOCUS_GAIN_REARM_SKIPPED",
                "delayMs=$delayMs transientLoss=$isTransientFocusLoss transientAudioLoss=$isTransientAudioFocusLoss continuous=${isContinuousModeEnabled()}"
            )
            return
        }

        val safeDelayMs = delayMs.coerceAtLeast(AUDIOFOCUS_GAIN_REARM_DEBOUNCE_MS)
        recordTrace("AUDIOFOCUS_GAIN_REARM_QUEUED", "debounceMs=$safeDelayMs")
        restartJob = scope.launch(mainDispatcher) {
            delay(safeDelayMs)
            if (!isTransientFocusLoss && !isTransientAudioFocusLoss && !shouldAbortListening() && isContinuousModeEnabled() && !_isListening.value) {
                lastStartAttemptEpochMs = 0L
                recordTrace("AUDIOFOCUS_GAIN_REARM_FIRING", "afterDebounceMs=$safeDelayMs")
                startListening(userInitiated = false)
            } else {
                recordTrace(
                    "AUDIOFOCUS_GAIN_REARM_CANCELLED_AT_FIRE",
                    "afterDebounceMs=$safeDelayMs transientLoss=$isTransientFocusLoss transientAudioLoss=$isTransientAudioFocusLoss continuous=${isContinuousModeEnabled()}"
                )
            }
        }
    }

    /**
     * Stops active listening, cancels any pending debounced restart job, and restores system audio.
     */
    fun stopListening(markIntentionallyPaused: Boolean = true) {
        if (markIntentionallyPaused) {
            isIntentionallyPaused = true
            setInChatDictationMode(false)
        }
        restartJob?.cancel()
        restartJob = null
        trailingSilenceDebounceJob?.cancel()
        trailingSilenceDebounceJob = null
        lastPartialCandidates = emptyList()
        val wasListening = _isListening.value
        _isListening.value = false
        _liveRmsDb.value = 0f
        restoreSystemStreamAfterStartChime()
        recordTrace(
            "STOP_LISTENING",
            "markIntentionallyPaused=$markIntentionallyPaused wasListening=$wasListening",
            warnIfOffMain = true
        )
        if (isOnMainThread()) {
            try {
                speechRecognizer?.stopListening()
                speechRecognizer?.cancel()
            } catch (_: Exception) {
            }
        } else {
            scope.launch(mainDispatcher) {
                try {
                    speechRecognizer?.stopListening()
                    speechRecognizer?.cancel()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Destroys and nulls out the current [SpeechRecognizer] instance without setting
     * [isIntentionallyPaused], allowing a clean recreation on the next [startListening] call
     * (e.g., after `ERROR_CLIENT(5)` during Hands-Free Mode).
     */
    internal fun resetRecognizerInstanceInternal() {
        recognizerRecreationCount++
        val instanceToDestroy = speechRecognizer
        speechRecognizer = null
        recordTrace(
            "RESET_RECOGNIZER_INSTANCE",
            "recreationCount=$recognizerRecreationCount hadInstance=${instanceToDestroy != null}",
            warnIfOffMain = true
        )
        if (instanceToDestroy != null) {
            if (isOnMainThread()) {
                try {
                    instanceToDestroy.cancel()
                } catch (_: Exception) {
                }
                try {
                    instanceToDestroy.destroy()
                } catch (_: Exception) {
                }
            } else {
                scope.launch(mainDispatcher) {
                    try {
                        instanceToDestroy.cancel()
                    } catch (_: Exception) {
                    }
                    try {
                        instanceToDestroy.destroy()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    /**
     * Destroys the underlying [SpeechRecognizer] instance and fully releases the Processing Mutex
     * (`isProcessing = false`) and all pending jobs when the feature is turned off or backgrounded.
     */
    fun destroy(markIntentionallyPaused: Boolean = true) {
        destroyCount++
        windowFocusDebounceJob?.cancel()
        windowFocusDebounceJob = null
        restartJob?.cancel()
        restartJob = null
        trailingSilenceDebounceJob?.cancel()
        trailingSilenceDebounceJob = null
        lastPartialCandidates = emptyList()
        isProcessing = false
        setInChatDictationMode(false)
        if (markIntentionallyPaused) {
            isIntentionallyPaused = true
        }
        _isListening.value = false
        _liveRmsDb.value = 0f
        restoreSystemStreamAfterStartChime()
        recordTrace(
            "DESTROY",
            "markIntentionallyPaused=$markIntentionallyPaused isProcessing=false destroyCount=$destroyCount destroying SpeechRecognizer instance",
            warnIfOffMain = true
        )
        val recognizerToDestroy = speechRecognizer
        speechRecognizer = null
        if (recognizerToDestroy != null) {
            if (isOnMainThread()) {
                try {
                    recognizerToDestroy.cancel()
                } catch (_: Exception) {
                }
                try {
                    recognizerToDestroy.destroy()
                } catch (_: Exception) {
                }
            } else {
                scope.launch(mainDispatcher) {
                    try {
                        recognizerToDestroy.cancel()
                    } catch (_: Exception) {
                    }
                    try {
                        recognizerToDestroy.destroy()
                    } catch (_: Exception) {
                    }
                }
            }
        }
        refreshMutexSnapshotFlow()
    }

    /**
     * The refactored [RecognitionListener] with debounced error handling, idle cutoff threshold,
     * `isIntentionallyPaused` / sleep / processing guards, `STREAM_SYSTEM` chime restoration,
     * and full callback-chain thread/mutex diagnostic tracing.
     */
    val recognitionListener: RecognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            // Restore STREAM_SYSTEM as soon as the recognizer is ready so normal system sounds work
            restoreSystemStreamAfterStartChime()
            consecutiveTransientErrors = 0
            _isListening.value = true
            recordTrace("CB_ON_READY_FOR_SPEECH", "recognizer ready for speech", warnIfOffMain = true)
            onReadyStateChanged(
                true,
                if (isInChatDictationMode) {
                    "Dictating into chat composer..."
                } else {
                    "Listening for wake word (SpeechRecognizer)..."
                }
            )
        }

        override fun onBeginningOfSpeech() {
            // User started speaking — reset idle timeout counter and trigger barge-in if TTS is playing
            consecutiveIdleTimeouts = 0
            _isListening.value = true
            recordTrace("CB_ON_BEGINNING_OF_SPEECH", "speech onset detected", warnIfOffMain = true)
            onBargeInSpeechDetected()
            onStatusMessage("Detecting speech...")
        }

        override fun onRmsChanged(rmsdB: Float) {
            val normalizedRms = rmsdB.coerceIn(0f, 10f)
            _liveRmsDb.value = normalizedRms
            onRmsChangedCallback(normalizedRms)
        }

        override fun onBufferReceived(buffer: ByteArray?) {
            if (buffer != null && buffer.isNotEmpty()) {
                onAudioBufferReceived(buffer)
            }
        }

        override fun onEndOfSpeech() {
            // CRITICAL: Never call startListening() inside onEndOfSpeech().
            // Wait for onResults() or onError() so we never enter a rapid restart loop.
            _isListening.value = false
            _liveRmsDb.value = 0f
            recordTrace("CB_ON_END_OF_SPEECH", "speech ended; awaiting onResults/onError", warnIfOffMain = true)
        }

        override fun onError(error: Int) {
            trailingSilenceDebounceJob?.cancel()
            trailingSilenceDebounceJob = null
            _isListening.value = false
            _liveRmsDb.value = 0f
            restoreSystemStreamAfterStartChime()
            val errorLabel = errorCodeToString(error)
            recordTrace(
                "CB_ON_ERROR",
                "error=$errorLabel idleTimeouts=$consecutiveIdleTimeouts transientErrors=$consecutiveTransientErrors",
                warnIfOffMain = true
            )

            if (isTransientAudioFocusLoss && error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                recordTrace(
                    "CB_ON_ERROR_TRANSIENT_AUDIOFOCUS_LOSS_SKIP",
                    "error=$errorLabel isTransientAudioFocusLoss=true -> returning immediately without restarting"
                )
                return
            }

            when (error) {
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    if (hasRecordAudioPermission()) {
                        recordTrace(
                            "CB_ON_ERROR_FALSE_POSITIVE_INSUFFICIENT_PERMISSIONS_IGNORED",
                            "OS RECORD_AUDIO permission is granted; treating ERROR_INSUFFICIENT_PERMISSIONS(9) as transient audio/TTS contention"
                        )
                        onPrepareHardwareBeforeStart()
                        if (!isTransientAudioFocusLoss &&
                            !isTransientFocusLoss &&
                            !shouldAbortListening() &&
                            isContinuousModeEnabled()
                        ) {
                            scheduleDebouncedRestart(delayMs = DEFAULT_RESTART_DEBOUNCE_MS)
                        }
                        return
                    }
                    isIntentionallyPaused = true
                    restartJob?.cancel()
                    restartJob = null
                    onInsufficientPermissions()
                    onReadyStateChanged(
                        false,
                        "Microphone permission required for wake-word listening"
                    )
                }

                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                SpeechRecognizer.ERROR_NO_MATCH -> {
                    val wasDictating = isInChatDictationMode
                    val bufferedPartials = lastPartialCandidates
                    lastPartialCandidates = emptyList()
                    if (bufferedPartials.isNotEmpty()) {
                        if (wasDictating) {
                            setInChatDictationMode(false)
                            if (onFinalTranscripts(bufferedPartials)) {
                                return
                            }
                        } else {
                            // If we captured a valid partial transcript with a wake word before timeout, finalize it now
                            val customWakeWords = activeWakeWordsProvider()
                            val extracted = extractMatchedWakeWordAndPrompt(bufferedPartials.first(), customWakeWords)
                            if (extracted != null && extracted.second.isNotBlank()) {
                                val dispatched = onOneBreathWakeQueryFinalized(extracted.first, extracted.second)
                                if (dispatched || onFinalTranscripts(bufferedPartials)) {
                                    return
                                }
                            }
                        }
                    }
                    if (wasDictating) {
                        setInChatDictationMode(false)
                    }

                    consecutiveTransientErrors = 0
                    consecutiveIdleTimeouts++
                    onSilenceTimeoutDuringVoiceTurn()

                    // Abort immediately if transient focus loss, intentionally paused, asleep, processing, or not in continuous mode
                    if (isTransientFocusLoss || shouldAbortListening() || !isContinuousModeEnabled()) {
                        recordTrace(
                            "CB_ON_ERROR_RESTART_ABORTED",
                            "error=$errorLabel transientLoss=$isTransientFocusLoss guard blocked restart after timeout"
                        )
                        return
                    }

                    // Stop infinite restart loops on repeated silence/no-match (ERROR_SPEECH_TIMEOUT=6 / ERROR_NO_MATCH=7)
                    if (consecutiveIdleTimeouts >= maxIdleTimeoutRestarts) {
                        recordTrace(
                            "CB_ON_ERROR_IDLE_THRESHOLD_REACHED",
                            "consecutiveIdleTimeouts=$consecutiveIdleTimeouts max=$maxIdleTimeoutRestarts"
                        )
                        isIntentionallyPaused = true
                        restartJob?.cancel()
                        restartJob = null
                        onIdleThresholdReached()
                        return
                    }

                    // Debounce restart by at least 1500ms (1800ms default) to prevent hammering the system chime
                    val debouncedDelayMs = (DEFAULT_RESTART_DEBOUNCE_MS + (consecutiveIdleTimeouts - 1) * 400L)
                        .coerceAtLeast(MIN_RESTART_DEBOUNCE_MS)
                    scheduleDebouncedRestart(delayMs = debouncedDelayMs)
                }

                SpeechRecognizer.ERROR_CLIENT -> {
                    if (isTransientFocusLoss) {
                        recordTrace(
                            "CB_ON_ERROR_CLIENT_TRANSIENT_FOCUS_HARD_KILL",
                            "isTransientFocusLoss=true -> returning immediately without recreating recognizer or queuing restart"
                        )
                        return
                    }
                    if (isInChatDictationMode) {
                        setInChatDictationMode(false)
                    }
                    if (isContinuousModeEnabled() && !isAsleep) {
                        isIntentionallyPaused = false
                        consecutiveTransientErrors = 0
                        if (isWindowFocused && hasActiveWindowFocus() && !isProcessing && !isExternalGuardBlocked()) {
                            resetRecognizerInstanceInternal()
                            recordTrace(
                                "CB_ON_ERROR_CLIENT_TRANSIENT_RESET",
                                "handsFree=true cleared isIntentionallyPaused and recreated recognizer"
                            )
                            scheduleDebouncedRestart(delayMs = DEFAULT_RESTART_DEBOUNCE_MS)
                        } else {
                            recordTrace(
                                "CB_ON_ERROR_CLIENT_TRANSIENT_FOCUS_SKIP",
                                "handsFree=true skipped recognizer recreation during focus loss"
                            )
                        }
                    } else {
                        recordTrace("CB_ON_ERROR_CLIENT_ABORTED", "client stop while handsFree=false or asleep")
                    }
                }

                else -> {
                    if (isTransientFocusLoss) {
                        recordTrace(
                            "CB_ON_ERROR_TRANSIENT_FOCUS_HARD_KILL",
                            "error=$errorLabel isTransientFocusLoss=true -> returning immediately"
                        )
                        return
                    }
                    consecutiveTransientErrors++
                    _errorCount.update { it + 1 }

                    if (shouldAbortListening() || !isContinuousModeEnabled()) {
                        recordTrace(
                            "CB_ON_ERROR_TRANSIENT_ABORTED",
                            "error=$errorLabel guard blocked transient restart"
                        )
                        return
                    }

                    if (consecutiveTransientErrors <= maxTransientErrorRestarts) {
                        val backoffMs = (MIN_RESTART_DEBOUNCE_MS * consecutiveTransientErrors)
                            .coerceAtLeast(MIN_RESTART_DEBOUNCE_MS)
                            .coerceAtMost(4500L)
                        scheduleDebouncedRestart(delayMs = backoffMs)
                    } else {
                        // Switch cleanly to silent hardware AudioRecord PCM fallback only if MainActivity has window focus
                        stopListening(markIntentionallyPaused = !isContinuousModeEnabled())
                        if (isWindowFocused && hasActiveWindowFocus()) {
                            recordTrace(
                                "CB_ON_ERROR_FALLBACK_TO_PCM",
                                "error=$errorLabel transientErrors=$consecutiveTransientErrors"
                            )
                            onFallbackToPcmRequested()
                        } else {
                            recordTrace(
                                "CB_ON_ERROR_FALLBACK_TO_PCM_SKIPPED_NO_WINDOW_FOCUS",
                                "error=$errorLabel skipped AudioRecord.startRecording() because MainActivity lacks window focus"
                            )
                        }
                    }
                }
            }
        }

        override fun onResults(results: Bundle?) {
            trailingSilenceDebounceJob?.cancel()
            trailingSilenceDebounceJob = null
            lastPartialCandidates = emptyList()
            _isListening.value = false
            _liveRmsDb.value = 0f
            restoreSystemStreamAfterStartChime()
            consecutiveIdleTimeouts = 0
            consecutiveTransientErrors = 0

            val rawMatches = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() && !com.example.data.model.ChatMessage.isLegacyAudioPlaceholder(it) }
                .orEmpty()

            val primarySpokenText = rawMatches.firstOrNull().orEmpty()
            val matches = if (primarySpokenText.isNotBlank()) {
                listOf(primarySpokenText) + rawMatches.drop(1)
            } else {
                emptyList()
            }

            recordTrace(
                "CB_ON_RESULTS",
                "matchesCount=${matches.size} primary=\"${primarySpokenText.take(60)}\"",
                warnIfOffMain = true
            )

            var handledTerminalAction = false
            val wasDictating = isInChatDictationMode
            if (matches.isNotEmpty()) {
                if (wasDictating) {
                    handledTerminalAction = onFinalTranscripts(matches)
                    setInChatDictationMode(false)
                } else {
                    val customWakeWords = activeWakeWordsProvider()
                    val extracted = extractMatchedWakeWordAndPrompt(matches.first(), customWakeWords)
                    if (extracted != null && extracted.second.isNotBlank()) {
                        handledTerminalAction = onOneBreathWakeQueryFinalized(extracted.first, extracted.second)
                    }
                    if (!handledTerminalAction) {
                        handledTerminalAction = onFinalTranscripts(matches)
                    }
                }
            } else if (wasDictating) {
                setInChatDictationMode(false)
            }

            recordTrace(
                "CB_ON_RESULTS_POST_DISPATCH",
                "handledTerminalAction=$handledTerminalAction shouldAbort=${shouldAbortListening()}"
            )

            // Only schedule a debounced restart (>= 1500ms) if continuous listening is still active
            // and we are not paused, asleep, or busy processing a command.
            if (!handledTerminalAction && !shouldAbortListening() && isContinuousModeEnabled()) {
                scheduleDebouncedRestart(delayMs = DEFAULT_RESTART_DEBOUNCE_MS)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
            if (matches.isNotEmpty()) {
                consecutiveIdleTimeouts = 0
                lastPartialCandidates = matches
                recordTrace(
                    "CB_ON_PARTIAL_RESULTS",
                    "partial=\"${matches.first().take(60)}\"",
                    warnIfOffMain = true
                )
                onBargeInSpeechDetected()
                onPartialTranscripts(matches)

                // Trailing silence debounce for one-breath wake-word + prompt execution or active in-chat dictation
                val customWakeWords = activeWakeWordsProvider()
                val extracted = extractMatchedWakeWordAndPrompt(matches.first(), customWakeWords)
                val shouldDebounceInChatDictation = isInChatDictationMode && matches.first().length >= 2
                trailingSilenceDebounceJob?.cancel()
                if ((extracted != null && extracted.second.length >= 3) || shouldDebounceInChatDictation) {
                    trailingSilenceDebounceJob = scope.launch(mainDispatcher) {
                        delay(TRAILING_SILENCE_DEBOUNCE_MS)
                        val pending = lastPartialCandidates
                        if (pending.isNotEmpty() && _isListening.value) {
                            lastPartialCandidates = emptyList()
                            val wasDictatingNow = isInChatDictationMode
                            recordTrace(
                                "PARTIAL_SILENCE_FINALIZE",
                                "finalizing partial=\"${pending.first().take(60)}\""
                            )
                            try {
                                speechRecognizer?.stopListening()
                            } catch (_: Exception) {
                            }
                            if (wasDictatingNow) {
                                setInChatDictationMode(false)
                                onFinalTranscripts(pending)
                            } else {
                                val latestExtracted = extractMatchedWakeWordAndPrompt(pending.first(), customWakeWords)
                                if (latestExtracted != null && latestExtracted.second.isNotBlank()) {
                                    val dispatched = onOneBreathWakeQueryFinalized(
                                        latestExtracted.first,
                                        latestExtracted.second
                                    )
                                    if (!dispatched) {
                                        onFinalTranscripts(pending)
                                    }
                                } else {
                                    onFinalTranscripts(pending)
                                }
                            }
                        }
                    }
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {
            recordTrace("CB_ON_EVENT", "eventType=$eventType", warnIfOffMain = true)
        }
    }
}
