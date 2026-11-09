package com.example.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log

enum class HandsFreeVoiceState(val displayName: String) {
    IDLE("Idle"),
    LISTENING("Listening"),
    TRANSCRIBING("Transcribing"),
    HEARTBEAT_PING("Heartbeat Ping"),
    SLEEPING("Sleep Mode")
}

/**
 * Predefined wake word sensitivity levels (Low, Medium, High) alongside custom slider tuning.
 */
enum class WakeSensitivityLevel(
    val label: String,
    val sensitivityValue: Float,
    val description: String
) {
    LOW(
        label = "Low",
        sensitivityValue = 0.62f,
        description = "Easy trigger — responds more readily in noisy rooms or soft speech"
    ),
    MEDIUM(
        label = "Medium",
        sensitivityValue = 0.78f,
        description = "Balanced accuracy and responsiveness"
    ),
    HIGH(
        label = "High",
        sensitivityValue = 0.90f,
        description = "Strict match — requires clear pronunciation to avoid false triggers"
    );

    companion object {
        fun fromSensitivity(value: Float): WakeSensitivityLevel {
            val clamped = value.coerceIn(0.50f, 0.98f)
            return when {
                clamped < 0.70f -> LOW
                clamped >= 0.85f -> HIGH
                else -> MEDIUM
            }
        }
    }
}

/**
 * Distinct tactile vibration patterns for Hands-Free Radar status toggles,
 * wake-word detections, and spoken voice command triggers.
 */
enum class HapticPatternType(
    val displayName: String,
    val timingsMs: LongArray,
    val amplitudes: IntArray,
    val fallbackDurationMs: Long
) {
    RADAR_TOGGLE_ON(
        displayName = "Radar Activated (Rising Double Pulse)",
        timingsMs = longArrayOf(0L, 35L, 45L, 65L),
        amplitudes = intArrayOf(0, 140, 0, 230),
        fallbackDurationMs = 65L
    ),
    RADAR_TOGGLE_OFF(
        displayName = "Radar Standby (Soft Single Pulse)",
        timingsMs = longArrayOf(0L, 45L),
        amplitudes = intArrayOf(0, 120),
        fallbackDurationMs = 45L
    ),
    WAKE_WORD_DETECTED(
        displayName = "Wake Word Detected (Double Confirmation Pulse)",
        timingsMs = longArrayOf(0L, 50L, 60L, 95L),
        amplitudes = intArrayOf(0, 180, 0, 255),
        fallbackDurationMs = 95L
    ),
    VOICE_COMMAND_TRIGGERED(
        displayName = "Voice Command Triggered (Crisp Action Pulse)",
        timingsMs = longArrayOf(0L, 110L),
        amplitudes = intArrayOf(0, 220),
        fallbackDurationMs = 110L
    ),
    HEARTBEAT_PING(
        displayName = "Heartbeat Ping Confirmation",
        timingsMs = longArrayOf(0L, 150L),
        amplitudes = intArrayOf(0, 200),
        fallbackDurationMs = 150L
    )
}

/**
 * AudioCommandSanitizer detects custom and default trigger phrases (Dictate, Send, Heartbeat, Sleep, Wake Up)
 * and scrubs internal action commands from speech buffers so command words never bleed into
 * Room database entries or Gemini AI prompts.
 */
object AudioCommandSanitizer {
    const val DEFAULT_DICTATE_TRIGGER = "Dictate"
    const val DEFAULT_SEND_TRIGGER = "Send"
    const val DEFAULT_HEARTBEAT_TRIGGER = "Heartbeat"
    const val DEFAULT_SLEEP_COMMAND = "Gemini, sleep"
    const val DEFAULT_WAKE_UP_COMMAND = "Gemini, wake up"
    private const val VOICE_HAPTIC_DEBOUNCE_MS = 220L

    @Volatile
    var lastHapticPattern: HapticPatternType? = null
        private set

    @Volatile
    var lastHapticTimestampMs: Long = 0L
        private set

    @Volatile
    var hapticTriggerCount: Int = 0
        private set

    private val recentHapticEvents = java.util.concurrent.ConcurrentLinkedDeque<String>()

    fun getRecentHapticEvents(): List<String> = recentHapticEvents.toList()

    fun resetHapticDiagnosticsForTest() {
        lastHapticPattern = null
        lastHapticTimestampMs = 0L
        hapticTriggerCount = 0
        recentHapticEvents.clear()
    }

    val DEFAULT_DICTATE_SYNONYMS = listOf("dictate", "take note", "start dictation")
    val DEFAULT_SEND_SYNONYMS = listOf("send", "over", "send message", "dispatch")
    val DEFAULT_HEARTBEAT_SYNONYMS = listOf("heartbeat", "pulse", "ping", "heartbeat ping")
    val DEFAULT_SLEEP_SYNONYMS = listOf(
        "gemini, sleep",
        "gemini sleep",
        "hey gemini sleep",
        "hey gemini, sleep",
        "ok gemini sleep",
        "go to sleep",
        "sleep mode",
        "gemini pause listening"
    )
    val DEFAULT_WAKE_UP_SYNONYMS = listOf(
        "gemini, wake up",
        "gemini wake up",
        "gemini wake",
        "hey gemini wake up",
        "hey gemini, wake up",
        "hey gemini wake",
        "wake up gemini",
        "wake up",
        "resume listening",
        "gemini resume"
    )
    val DEFAULT_WAKE_WORDS = listOf(
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

    val DEFAULT_HISTORY_COMMAND_SYNONYMS = listOf(
        "open history",
        "show history",
        "voice history",
        "go to history",
        "view history",
        "open voice history",
        "show voice history",
        "history"
    )

    /**
     * Checks if spoken text is a voice history navigation command ("open history", "show history", "voice history")
     * either standalone or following a wake word prefix (e.g., "Hey Gemini, open history").
     * Note: Because "open" is also a default wake trigger, "open history" stripped of "open" yields "history",
     * which is explicitly handled here so "open history" routes to the History screen instead of a chat prompt.
     */
    fun isVoiceHistoryNavigationCommand(
        text: String?,
        customWakeWords: Collection<String> = emptyList()
    ): Boolean {
        if (text.isNullOrBlank()) return false
        val rawClean = text.trim()
            .lowercase(java.util.Locale.US)
            .trimEnd('.', '!', '?', ',', ';', ':')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (rawClean.isBlank()) return false

        // Direct match on full phrase ("open history", "show history", "voice history", etc.)
        if (rawClean == "open history" ||
            rawClean == "show history" ||
            rawClean == "voice history" ||
            rawClean == "go to history" ||
            rawClean == "view history" ||
            rawClean == "open voice history" ||
            rawClean == "show voice history"
        ) {
            return true
        }

        // Strip any non-"open" wake words first so "Hey Gemini, open history" -> "open history"
        val nonOpenWakeWords = (customWakeWords.map { it.trim() } + DEFAULT_WAKE_WORDS)
            .filter { it.isNotBlank() && !it.equals("open", ignoreCase = true) }
            .distinctBy { it.lowercase(java.util.Locale.US) }
            .sortedByDescending { it.length }

        var strippedNonOpen = rawClean
        var changed = true
        while (changed && strippedNonOpen.isNotBlank()) {
            changed = false
            for (wake in nonOpenWakeWords) {
                val escapedWake = Regex.escape(wake)
                val leadingRegex = Regex("(?i)^[,\\s.!?-]*(?:(?:hey|ok|okay|hi)\\s+)?$escapedWake\\b[,:\\s.!?;-]*")
                if (leadingRegex.containsMatchIn(strippedNonOpen)) {
                    strippedNonOpen = strippedNonOpen.replaceFirst(leadingRegex, "").trim()
                    changed = true
                }
            }
        }
        strippedNonOpen = strippedNonOpen
            .lowercase(java.util.Locale.US)
            .trimEnd('.', '!', '?', ',', ';', ':')
            .replace(Regex("\\s+"), " ")
            .trim()

        if (strippedNonOpen == "open history" ||
            strippedNonOpen == "show history" ||
            strippedNonOpen == "voice history" ||
            strippedNonOpen == "go to history" ||
            strippedNonOpen == "view history" ||
            strippedNonOpen == "open voice history" ||
            strippedNonOpen == "show voice history"
        ) {
            return true
        }

        return false
    }

    /**
     * Checks if spoken text is a sleep command (e.g., "Gemini, sleep") to temporarily disable the wake word listener.
     */
    fun containsSleepCommand(text: String, customSleepCommand: String = DEFAULT_SLEEP_COMMAND): Boolean {
        val lower = text.lowercase().trim()
        if (lower.isBlank()) return false
        val targets = (listOf(customSleepCommand.lowercase().trim()) + DEFAULT_SLEEP_SYNONYMS)
            .filter { it.isNotBlank() }
            .distinct()
        return targets.any { pattern ->
            val normalizedPattern = pattern.replace(",", "\\s*,?\\s*")
            Regex("(^|\\b)$normalizedPattern(\\b|$|[.!?])", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        }
    }

    /**
     * Checks if spoken text is a wake-up command (e.g., "Gemini, wake up") to re-enable the wake word listener from sleep mode.
     */
    fun containsWakeUpCommand(text: String, customWakeUpCommand: String = DEFAULT_WAKE_UP_COMMAND): Boolean {
        val lower = text.lowercase().trim()
        if (lower.isBlank()) return false
        val targets = (listOf(customWakeUpCommand.lowercase().trim()) + DEFAULT_WAKE_UP_SYNONYMS)
            .filter { it.isNotBlank() }
            .distinct()
        return targets.any { pattern ->
            val normalizedPattern = pattern.replace(",", "\\s*,?\\s*")
            Regex("(^|\\b)$normalizedPattern(\\b|$|[.!?])", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        }
    }

    /**
     * Computes normalized character-level similarity [0.0..1.0] between two strings for sensitivity-scaled wake phrase matching.
     */
    fun computePhraseSimilarity(a: String, b: String): Float {
        val s1 = a.trim().lowercase().replace(Regex("[^a-z0-9\\s]"), "").replace(Regex("\\s+"), " ")
        val s2 = b.trim().lowercase().replace(Regex("[^a-z0-9\\s]"), "").replace(Regex("\\s+"), " ")
        if (s1 == s2) return 1.0f
        if (s1.isEmpty() || s2.isEmpty()) return 0.0f
        val maxLen = maxOf(s1.length, s2.length)
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = temp
            }
        }
        val dist = dp[s2.length]
        return (1.0f - dist.toFloat() / maxLen.toFloat()).coerceIn(0.0f, 1.0f)
    }

    /**
     * Strips any leading or standalone wake word trigger (e.g. "open", custom chat wakeWord,
     * "hey gemini", "hey jimmy") from spoken or buffered text so the wake word itself is never
     * injected into the user prompt or Gemini chat message.
     */
    fun stripWakeWordTrigger(
        rawText: String,
        customWakeWords: Collection<String> = emptyList()
    ): String {
        var cleaned = rawText.trim()
        if (cleaned.isBlank()) return ""

        val wakeTargets = (customWakeWords.map { it.trim() } + DEFAULT_WAKE_WORDS)
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            // Sort longest first so multi-word wake phrases ("hey gemini", "hey jimmy") match before single words ("gemini", "jimmy")
            .sortedByDescending { it.length }

        var changed = true
        while (changed && cleaned.isNotBlank()) {
            changed = false
            for (wake in wakeTargets) {
                val escapedWake = Regex.escape(wake)
                val standaloneRegex = Regex("(?i)^[,\\s.!?-]*(?:(?:hey|ok|okay|hi)\\s+)?$escapedWake[,\\s.!?-]*$")
                if (standaloneRegex.matches(cleaned)) {
                    return ""
                }
                val leadingRegex = Regex("(?i)^[,\\s.!?-]*(?:(?:hey|ok|okay|hi)\\s+)?$escapedWake\\b[,:\\s.!?;-]*")
                if (leadingRegex.containsMatchIn(cleaned)) {
                    cleaned = cleaned.replaceFirst(leadingRegex, "").trim()
                    changed = true
                }
            }
        }
        return cleaned
    }

    /**
     * Returns true if the text is empty or consists solely of a wake word trigger phrase (such as "open").
     */
    fun isPureWakeWordTrigger(
        rawText: String?,
        customWakeWords: Collection<String> = emptyList()
    ): Boolean {
        if (rawText.isNullOrBlank()) return true
        return stripWakeWordTrigger(rawText, customWakeWords).isBlank()
    }

    /**
     * Checks if spoken text contains a heartbeat trigger phrase.
     */
    fun containsHeartbeatTrigger(text: String, customTrigger: String = DEFAULT_HEARTBEAT_TRIGGER): Boolean {
        val lower = text.lowercase().trim()
        if (lower.isBlank()) return false
        val targets = (listOf(customTrigger.lowercase().trim()) + DEFAULT_HEARTBEAT_SYNONYMS).filter { it.isNotBlank() }.distinct()
        return targets.any { pattern ->
            Regex("(^|\\b)${Regex.escape(pattern)}(\\b|$|[.!?])", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        }
    }

    /**
     * Checks if spoken text ends with or contains a send trigger phrase.
     */
    fun containsSendTrigger(text: String, customTrigger: String = DEFAULT_SEND_TRIGGER): Boolean {
        val lower = text.lowercase().trim()
        if (lower.isBlank()) return false
        val targets = (listOf(customTrigger.lowercase().trim()) + DEFAULT_SEND_SYNONYMS).filter { it.isNotBlank() }.distinct()
        return targets.any { pattern ->
            Regex("(^|\\b)${Regex.escape(pattern)}(\\b|$|[.!?])", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        }
    }

    /**
     * Checks if spoken text contains a dictate trigger phrase.
     */
    fun containsDictateTrigger(text: String, customTrigger: String = DEFAULT_DICTATE_TRIGGER): Boolean {
        val lower = text.lowercase().trim()
        if (lower.isBlank()) return false
        val targets = (listOf(customTrigger.lowercase().trim()) + DEFAULT_DICTATE_SYNONYMS).filter { it.isNotBlank() }.distinct()
        return targets.any { pattern ->
            Regex("(^|\\b)${Regex.escape(pattern)}(\\b|$|[.!?])", RegexOption.IGNORE_CASE).containsMatchIn(lower)
        }
    }

    /**
     * Extracts dictation content after the dictate trigger phrase.
     * E.g. "Dictate what is the capital of France" -> "what is the capital of France".
     */
    fun extractContentAfterDictate(text: String, customTrigger: String = DEFAULT_DICTATE_TRIGGER): String {
        val targets = (listOf(customTrigger.lowercase().trim()) + DEFAULT_DICTATE_SYNONYMS).filter { it.isNotBlank() }.distinct()
        val lower = text.lowercase()
        for (pattern in targets) {
            val match = Regex("(^|\\b)${Regex.escape(pattern)}(\\b|:|,|\\s*)", RegexOption.IGNORE_CASE).find(lower)
            if (match != null) {
                val candidate = text.substring(match.range.last + 1).trim()
                if (candidate.isNotBlank()) return candidate
            }
        }
        return text.trim()
    }

    /**
     * Scrubs recognized action phrases ("Send", "Over", "Heartbeat", "Pulse", custom triggers)
     * and wake-word prefixes from the transcribed buffer prior to dispatching to Room or Gemini API.
     */
    fun sanitizeSpokenText(
        text: String,
        sendTrigger: String = DEFAULT_SEND_TRIGGER,
        heartbeatTrigger: String = DEFAULT_HEARTBEAT_TRIGGER,
        wakeWords: Collection<String> = emptyList()
    ): String {
        var result = stripWakeWordTrigger(text.trim(), wakeWords)
        if (result.isBlank()) return ""

        val allActionWords = (
            listOf(sendTrigger.lowercase().trim(), heartbeatTrigger.lowercase().trim()) +
            DEFAULT_SEND_SYNONYMS +
            DEFAULT_HEARTBEAT_SYNONYMS
        ).filter { it.isNotBlank() }.distinct()

        for (action in allActionWords) {
            // 1. Scrub trailing trigger command (e.g. "What is the speed of light? Send", "Code refactor over.")
            val trailingRegex = Regex("(?i)[,\\s-]*\\b${Regex.escape(action)}\\b[.!?\\s]*$")
            result = result.replace(trailingRegex, "").trim()

            // 2. Scrub leading trigger command if spoken as a bare prefix
            val leadingRegex = Regex("(?i)^${Regex.escape(action)}\\b[,\\s-]*")
            result = result.replace(leadingRegex, "").trim()
        }

        result = stripWakeWordTrigger(result, wakeWords)

        // Clean up any double spaces or orphan punctuation
        result = result.replace(Regex("\\s+"), " ")
        result = result.trim().removeSuffix(",").removeSuffix("-").trim()
        return result
    }

    private val INTERNAL_STATUS_OR_COMMAND_STRINGS = setOf(
        "idle",
        "listening",
        "listening...",
        "processing",
        "processing...",
        "transcribing",
        "transcribing voice",
        "heartbeat",
        "heartbeat ping",
        "hands-free standby",
        "listening paused",
        "tap to start listening",
        "listening via hardware mic (audiorecord)...",
        "dictate",
        "send",
        "over",
        "dispatch",
        "pulse",
        "ping",
        "open",
        "ping received, orion voice online"
    )

    /**
     * Returns true if the candidate string is an internal system status, debug prompt,
     * wake word trigger, or command trigger that must NEVER be passed to StreamingVoicePlayer
     * or injected as a user chat message.
     */
    fun isSystemOrCommandStatusString(
        text: String?,
        dictateTrigger: String = DEFAULT_DICTATE_TRIGGER,
        sendTrigger: String = DEFAULT_SEND_TRIGGER,
        heartbeatTrigger: String = DEFAULT_HEARTBEAT_TRIGGER,
        wakeWords: Collection<String> = emptyList()
    ): Boolean {
        val normalized = text?.trim()?.lowercase()?.removeSuffix(".")?.removeSuffix("!") ?: return true
        if (normalized.isEmpty()) return true
        if (INTERNAL_STATUS_OR_COMMAND_STRINGS.contains(normalized)) return true
        val dynamicTriggers = (
            listOf(
                dictateTrigger.trim().lowercase(),
                sendTrigger.trim().lowercase(),
                heartbeatTrigger.trim().lowercase()
            ) + DEFAULT_WAKE_WORDS + wakeWords.map { it.trim().lowercase() }
        ).filter { it.isNotBlank() }.toSet()
        return dynamicTriggers.contains(normalized)
    }

    /**
     * Executes a distinct hardware haptic feedback pattern using [VibratorManager] / [Vibrator].
     * Applies a short debounce window for voice activation triggers so overlapping partial/final
     * callbacks do not double-vibrate for the same spoken event.
     */
    @Synchronized
    fun triggerHapticFeedback(
        context: Context,
        pattern: HapticPatternType,
        reason: String = pattern.displayName,
        bypassDebounce: Boolean = false
    ): Boolean {
        val now = System.currentTimeMillis()
        if (!bypassDebounce &&
            lastHapticPattern == pattern &&
            now - lastHapticTimestampMs in 0 until VOICE_HAPTIC_DEBOUNCE_MS
        ) {
            return false
        }
        lastHapticPattern = pattern
        lastHapticTimestampMs = now
        hapticTriggerCount += 1

        val timeFormatted = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            .format(java.util.Date(now))
        recentHapticEvents.addFirst("[$timeFormatted] ${pattern.name}: $reason")
        while (recentHapticEvents.size > 30) {
            recentHapticEvents.pollLast()
        }

        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (pattern.timingsMs.size > 2) {
                    VibrationEffect.createWaveform(pattern.timingsMs, pattern.amplitudes, -1)
                } else {
                    VibrationEffect.createOneShot(
                        pattern.fallbackDurationMs,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                }
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern.fallbackDurationMs)
            }
            return true
        } catch (e: Exception) {
            Log.w("AudioCommandSanitizer", "Could not trigger haptic (${pattern.name}): ${e.message}")
            return false
        }
    }

    /**
     * Triggers physical haptic confirmation for the Hands-Free Radar toggle (distinct rising double pulse
     * when activating radar / exiting sleep mode, or soft single pulse when entering standby/sleep).
     */
    fun playRadarToggleHaptic(context: Context, isActivating: Boolean, reason: String? = null): Boolean {
        val pattern = if (isActivating) {
            HapticPatternType.RADAR_TOGGLE_ON
        } else {
            HapticPatternType.RADAR_TOGGLE_OFF
        }
        return triggerHapticFeedback(
            context = context,
            pattern = pattern,
            reason = reason ?: pattern.displayName,
            bypassDebounce = true
        )
    }

    /**
     * Triggers a distinct double-pulse haptic confirmation when a wake-word phrase is recognized.
     */
    fun playWakeWordHaptic(context: Context, matchedWakeWord: String = "Wake Word"): Boolean {
        return triggerHapticFeedback(
            context = context,
            pattern = HapticPatternType.WAKE_WORD_DETECTED,
            reason = "Wake word \"$matchedWakeWord\" matched"
        )
    }

    /**
     * Triggers a crisp single-pulse haptic confirmation when a voice activation command
     * (Dictate, Send, Sleep, Wake Up, or Voice History Navigation) is triggered.
     */
    fun playVoiceCommandHaptic(context: Context, commandLabel: String = "Voice Command"): Boolean {
        return triggerHapticFeedback(
            context = context,
            pattern = HapticPatternType.VOICE_COMMAND_TRIGGERED,
            reason = "Voice command \"$commandLabel\" triggered"
        )
    }

    /**
     * Triggers silent haptic feedback pulse upon heartbeat trigger interception without synthesizing status audio.
     */
    fun playHeartbeatConfirmation(context: Context) {
        triggerHapticFeedback(
            context = context,
            pattern = HapticPatternType.HEARTBEAT_PING,
            reason = "Heartbeat ping confirmation",
            bypassDebounce = true
        )
    }
}
