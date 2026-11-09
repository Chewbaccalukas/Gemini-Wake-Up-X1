package com.example.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID

/**
 * Android [TextToSpeech] engine manager for speaking completed Gemini responses aloud:
 *
 * 1. **High-Quality English Synthesis**:
 *    - Initializes [TextToSpeech] with [Locale.US] (falling back to [Locale.ENGLISH] if needed)
 *      and selects a high-quality English voice when available.
 * 2. **Automatic Post-Stream Playback**:
 *    - Speaks the completed assistant response text aloud via `QUEUE_FLUSH` (or chunked `QUEUE_ADD`
 *      for long multi-paragraph replies).
 * 3. **Explicit 300ms Acoustic Settling Delay**:
 *    - Listens for [UtteranceProgressListener.onDone] and enforces a mandatory
 *      [ACOUSTIC_SETTLING_DELAY_MS] (`300ms`) delay after playback finishes before invoking
 *      [onPlaybackSettledAndReadyToListen] (`SpeechRecognitionCoordinator.startListening()`),
 *      preventing speaker tail audio from feeding back into the microphone.
 * 4. **Instant Barge-In Support**:
 *    - If the user begins speaking while TTS is active, [bargeInAndStop] immediately calls
 *      `tts.stop()`, cancels any pending settling timer, and switches back to listening.
 */
class TextToSpeechManager(
    context: Context,
    private val scope: CoroutineScope,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val onPlaybackStarted: () -> Unit = {},
    private val onPlaybackSettledAndReadyToListen: () -> Unit = {},
    private val onBargeInStopped: () -> Unit = {}
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "TextToSpeechManager"

        /**
         * Explicit 500ms debounced acoustic settling delay after TTS playback completes before
         * re-initializing `WakeWordVoiceService` and restarting `SpeechRecognitionCoordinator.startListening()`.
         */
        const val ACOUSTIC_SETTLING_DELAY_MS = 500L

        /**
         * Maximum character length per single `TextToSpeech.speak()` utterance chunk.
         */
        private const val MAX_UTTERANCE_CHARS = 3500

        /**
         * Strips markdown formatting symbols (`**`, `*`, `` ` ``, `#`) so spoken synthesis sounds natural.
         */
        fun sanitizeMarkdownForSpeech(rawText: String): String {
            if (rawText.isBlank()) return ""
            return rawText
                .replace(Regex("```[\\s\\S]*?```"), " code block ")
                .replace(Regex("`([^`]+)`"), "$1")
                .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
                .replace(Regex("\\*([^*]+)\\*"), "$1")
                .replace(Regex("^#{1,6}\\s*", RegexOption.MULTILINE), "")
                .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var settlingJob: Job? = null
    private var pendingTextBeforeInit: String? = null
    private var currentVolume: Float = 1.0f
    private val activeUtterances: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    @Volatile
    private var activeFinalUtteranceId: String? = null

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _lastSpokenText = MutableStateFlow("")
    val lastSpokenText: StateFlow<String> = _lastSpokenText.asStateFlow()

    val hasPendingSettlingDelay: Boolean
        get() = settlingJob?.isActive == true

    init {
        initializeEngine()
    }

    private fun initializeEngine() {
        try {
            tts = TextToSpeech(appContext, this)
        } catch (e: Exception) {
            Log.w(TAG, "TextToSpeech initialization failed in environment: ${e.message}")
            _isInitialized.value = false
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val engine = tts ?: return
            val localeResult = engine.setLanguage(Locale.US)
            if (localeResult == TextToSpeech.LANG_MISSING_DATA ||
                localeResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                engine.setLanguage(Locale.ENGLISH)
            }
            // Select a high-quality English voice when available
            try {
                val bestVoice = engine.voices
                    ?.filter { voice ->
                        voice.locale.language.equals(Locale.ENGLISH.language, ignoreCase = true) &&
                            !voice.isNetworkConnectionRequired
                    }
                    ?.maxByOrNull { it.quality }
                if (bestVoice != null) {
                    engine.voice = bestVoice
                }
            } catch (_: Exception) {
            }
            engine.setSpeechRate(1.02f)
            engine.setPitch(1.0f)
            engine.setOnUtteranceProgressListener(utteranceProgressListener)
            _isInitialized.value = true

            val queued = pendingTextBeforeInit
            pendingTextBeforeInit = null
            if (!queued.isNullOrBlank()) {
                speak(queued)
            }
        } else {
            Log.w(TAG, "TextToSpeech onInit returned status=$status")
            _isInitialized.value = false
        }
    }

    internal val utteranceProgressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            settlingJob?.cancel()
            settlingJob = null
            if (!utteranceId.isNullOrBlank()) {
                activeUtterances.add(utteranceId)
            }
            _isSpeaking.value = true
            scope.launch(mainDispatcher) {
                onPlaybackStarted()
            }
        }

        override fun onDone(utteranceId: String?) {
            if (!utteranceId.isNullOrBlank()) {
                activeUtterances.remove(utteranceId)
            }
            if (activeUtterances.isEmpty() || utteranceId == null || utteranceId == activeFinalUtteranceId) {
                activeUtterances.clear()
                scheduleAcousticSettlingBeforeListening(ACOUSTIC_SETTLING_DELAY_MS)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            if (!utteranceId.isNullOrBlank()) {
                activeUtterances.remove(utteranceId)
            }
            if (activeUtterances.isEmpty() || utteranceId == null || utteranceId == activeFinalUtteranceId) {
                activeUtterances.clear()
                scheduleAcousticSettlingBeforeListening(ACOUSTIC_SETTLING_DELAY_MS)
            }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            if (!utteranceId.isNullOrBlank()) {
                activeUtterances.remove(utteranceId)
            }
            if (activeUtterances.isEmpty() || utteranceId == null || utteranceId == activeFinalUtteranceId) {
                activeUtterances.clear()
                scheduleAcousticSettlingBeforeListening(ACOUSTIC_SETTLING_DELAY_MS)
            }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            if (!utteranceId.isNullOrBlank()) {
                activeUtterances.remove(utteranceId)
            }
            _isSpeaking.value = false
            if (activeUtterances.isEmpty() || utteranceId == null || utteranceId == activeFinalUtteranceId) {
                activeUtterances.clear()
                scheduleAcousticSettlingBeforeListening(ACOUSTIC_SETTLING_DELAY_MS)
            }
        }
    }

    /**
     * Enforces an explicit 500ms debounced delay after `UtteranceProgressListener.onDone`
     * (as well as `onError` and `onStop`) before re-initializing [WakeWordVoiceService]
     * and invoking [onPlaybackSettledAndReadyToListen].
     */
    private fun scheduleAcousticSettlingBeforeListening(delayMs: Long = ACOUSTIC_SETTLING_DELAY_MS) {
        settlingJob?.cancel()
        settlingJob = scope.launch(mainDispatcher) {
            _isSpeaking.value = false
            delay(delayMs.coerceAtLeast(ACOUSTIC_SETTLING_DELAY_MS))
            if (!_isSpeaking.value && activeUtterances.isEmpty()) {
                WakeWordVoiceService.resumeAfterInternalDictationInMemory()
                onPlaybackSettledAndReadyToListen()
            }
        }
    }

    /**
     * Speaks [text] aloud using Android [TextToSpeech].
     * Synchronously releases [WakeWordVoiceService]'s [android.media.AudioRecord] (`"tts_speaking"`)
     * before opening the TTS audio stream, and waits [ACOUSTIC_SETTLING_DELAY_MS] (`500ms`) after
     * `onDone` / `onError` / `onStop` before re-initializing [WakeWordVoiceService].
     */
    fun speak(text: String) {
        val cleanText = sanitizeMarkdownForSpeech(text)
        if (cleanText.isBlank()) {
            scheduleAcousticSettlingBeforeListening(ACOUSTIC_SETTLING_DELAY_MS)
            return
        }
        _lastSpokenText.value = cleanText
        settlingJob?.cancel()
        settlingJob = null

        // 1. Yield Before Speech: Synchronously stop and release WakeWordVoiceService's AudioRecord
        //    BEFORE invoking tts.speak() so the microphone is cleanly released prior to opening the TTS stream.
        WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("tts_speaking")
        _isSpeaking.value = true
        onPlaybackStarted()

        val engine = tts
        if (engine == null || !_isInitialized.value) {
            pendingTextBeforeInit = cleanText
            // If TTS is unavailable in headless/test container, still fire the 500ms settling hook
            if (engine == null) {
                scheduleAcousticSettlingBeforeListening(ACOUSTIC_SETTLING_DELAY_MS)
            }
            return
        }

        try {
            val chunks = chunkTextForUtterances(cleanText)
            if (chunks.isEmpty()) {
                scheduleAcousticSettlingBeforeListening(ACOUSTIC_SETTLING_DELAY_MS)
                return
            }
            engine.setOnUtteranceProgressListener(utteranceProgressListener)
            activeUtterances.clear()

            chunks.forEachIndexed { index, chunk ->
                val utteranceId = "gemini_tts_${UUID.randomUUID()}"
                activeUtterances.add(utteranceId)
                if (index == chunks.lastIndex) {
                    activeFinalUtteranceId = utteranceId
                }
                val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                val params = Bundle().apply {
                    putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, currentVolume.coerceIn(0f, 1f))
                }
                val result = engine.speak(chunk, queueMode, params, utteranceId)
                if (result == TextToSpeech.ERROR) {
                    activeUtterances.remove(utteranceId)
                    if (index == chunks.lastIndex && activeUtterances.isEmpty()) {
                        scheduleAcousticSettlingBeforeListening(ACOUSTIC_SETTLING_DELAY_MS)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error during TextToSpeech.speak: ${e.message}")
            activeUtterances.clear()
            scheduleAcousticSettlingBeforeListening(ACOUSTIC_SETTLING_DELAY_MS)
        }
    }

    /**
     * Barge-in handler: if the user begins speaking while TTS is active, immediately calls
     * `tts.stop()`, cancels any pending settling timer, and invokes [onBargeInStopped] so
     * recognition can immediately capture the user's speech.
     */
    fun bargeInAndStop() {
        settlingJob?.cancel()
        settlingJob = null
        pendingTextBeforeInit = null
        activeFinalUtteranceId = null
        activeUtterances.clear()
        val wasSpeaking = _isSpeaking.value
        _isSpeaking.value = false
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping TTS during barge-in: ${e.message}")
        }
        if (wasSpeaking) {
            scope.launch(mainDispatcher) {
                onBargeInStopped()
            }
        }
    }

    /**
     * Stops active TTS synthesis immediately.
     */
    fun stop() {
        settlingJob?.cancel()
        settlingJob = null
        pendingTextBeforeInit = null
        activeFinalUtteranceId = null
        activeUtterances.clear()
        _isSpeaking.value = false
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        _isInitialized.value = false
    }

    private fun chunkTextForUtterances(text: String): List<String> {
        if (text.length <= MAX_UTTERANCE_CHARS) return listOf(text)
        val chunks = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            if (remaining.length <= MAX_UTTERANCE_CHARS) {
                chunks.add(remaining.trim())
                break
            }
            val splitIdx = remaining.lastIndexOf(". ", MAX_UTTERANCE_CHARS)
                .takeIf { it > 200 }
                ?: remaining.lastIndexOf(' ', MAX_UTTERANCE_CHARS).takeIf { it > 200 }
                ?: MAX_UTTERANCE_CHARS
            chunks.add(remaining.substring(0, splitIdx + 1).trim())
            remaining = remaining.substring(splitIdx + 1).trim()
        }
        return chunks.filter { it.isNotBlank() }
    }
}
