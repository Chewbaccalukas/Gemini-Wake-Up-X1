package com.example.voice

import com.example.game.AdventureDrawerTab
import com.example.ui.theme.AppTheme
import java.util.Locale

enum class VoiceScreenDestination(
    val route: String,
    val displayTitle: String
) {
    HOME("home", "Home"),
    CHAT("chat", "Chat"),
    HISTORY("history", "History"),
    SETTINGS("settings", "Settings"),
    STUDIO("studio", "Wake Word Studio"),
    DIAGNOSTICS("diagnostics", "Diagnostic Log")
}

sealed interface VoiceCommandAction {
    data class NavigateToScreen(
        val destination: VoiceScreenDestination,
        val rawTranscript: String
    ) : VoiceCommandAction

    data class SelectCategoryFilter(
        val category: String?,
        val displayLabel: String,
        val rawTranscript: String
    ) : VoiceCommandAction

    data object ClearChatHistory : VoiceCommandAction
    data object ReadLastReply : VoiceCommandAction
    data class SetChatPinned(val pinned: Boolean) : VoiceCommandAction
    data class SetThemeMode(val theme: AppTheme, val label: String) : VoiceCommandAction
    data class SetThemePalette(
        val paletteId: String,
        val backgroundStyle: String,
        val primaryHex: String,
        val label: String
    ) : VoiceCommandAction

    data class OpenExternalUrl(
        val urlOrQuery: String,
        val rawTranscript: String
    ) : VoiceCommandAction

    data class OpenAdventureDrawer(
        val tab: AdventureDrawerTab
    ) : VoiceCommandAction

    data object SaveAdventureGame : VoiceCommandAction
    data object CloseAdventureDrawer : VoiceCommandAction

    data object None : VoiceCommandAction
}

class VoiceCommandDispatcher(
    private val coordinator: SpeechRecognitionCoordinator
) {
    interface Callbacks {
        fun onNavigateToScreen(destination: VoiceScreenDestination, rawTranscript: String): Boolean
        fun onSelectCategoryFilter(category: String?, displayLabel: String, rawTranscript: String): Boolean
        fun onClearChatHistory(targetChatId: Long?): Boolean
        fun onReadLastReply(targetChatId: Long?): Boolean
        fun onSetChatPinned(targetChatId: Long?, pinned: Boolean): Boolean
        fun onSetThemeMode(theme: AppTheme, label: String): Boolean
        fun onSetThemePalette(
            paletteId: String,
            backgroundStyle: String,
            primaryHex: String,
            label: String
        ): Boolean
        fun onOpenExternalUrl(urlOrQuery: String, rawTranscript: String): Boolean
        fun onOpenAdventureDrawer(tab: AdventureDrawerTab): Boolean = false
        fun onSaveAdventureGame(): Boolean = false
        fun onCloseAdventureDrawer(): Boolean = false
        fun onCommandCompletedAndRearm(action: VoiceCommandAction, reason: String)
    }

    fun parseCommand(
        rawTranscript: String,
        customWakeWords: Collection<String> = emptyList(),
        availableCategories: Collection<String> = DEFAULT_CATEGORIES
    ): VoiceCommandAction {
        val cleaned = stripLeadingWakeWordPrefix(rawTranscript, customWakeWords)
        val normalized = normalizeCommandText(cleaned)
        if (normalized.isBlank()) return VoiceCommandAction.None

        parseScreenDestination(normalized)?.let { destination ->
            return VoiceCommandAction.NavigateToScreen(
                destination = destination,
                rawTranscript = rawTranscript.trim()
            )
        }

        parseCategoryFilterCommand(normalized, availableCategories)?.let { categoryAction ->
            return categoryAction.copy(rawTranscript = rawTranscript.trim())
        }

        parseInChatOrThemeCommand(normalized)?.let { inChatAction ->
            return inChatAction
        }

        parseExternalUrlCommand(cleaned, normalized)?.let { externalAction ->
            return externalAction.copy(rawTranscript = rawTranscript.trim())
        }

        return VoiceCommandAction.None
    }

    fun dispatch(
        rawTranscript: String,
        targetChatId: Long?,
        customWakeWords: Collection<String> = emptyList(),
        availableCategories: Collection<String> = DEFAULT_CATEGORIES,
        callbacks: Callbacks
    ): Boolean {
        val action = parseCommand(
            rawTranscript = rawTranscript,
            customWakeWords = customWakeWords,
            availableCategories = availableCategories
        )
        if (action is VoiceCommandAction.None) return false
        return executeAction(action, targetChatId, callbacks)
    }

    fun executeAction(
        action: VoiceCommandAction,
        targetChatId: Long?,
        callbacks: Callbacks
    ): Boolean {
        if (action is VoiceCommandAction.None) return false

        // Enter transient processing state without setting isIntentionallyPaused = true
        coordinator.pauseForCommandDispatch("voice_command_dispatch:${action::class.simpleName}")

        var handled = false
        var deferredNavigationCommit = false
        try {
            handled = when (action) {
                is VoiceCommandAction.NavigateToScreen -> {
                    val result = callbacks.onNavigateToScreen(action.destination, action.rawTranscript)
                    deferredNavigationCommit = result
                    result
                }
                is VoiceCommandAction.SelectCategoryFilter -> {
                    callbacks.onSelectCategoryFilter(
                        category = action.category,
                        displayLabel = action.displayLabel,
                        rawTranscript = action.rawTranscript
                    )
                }
                is VoiceCommandAction.ClearChatHistory -> {
                    callbacks.onClearChatHistory(targetChatId)
                }
                is VoiceCommandAction.ReadLastReply -> {
                    callbacks.onReadLastReply(targetChatId)
                }
                is VoiceCommandAction.SetChatPinned -> {
                    callbacks.onSetChatPinned(targetChatId, action.pinned)
                }
                is VoiceCommandAction.SetThemeMode -> {
                    callbacks.onSetThemeMode(action.theme, action.label)
                }
                is VoiceCommandAction.SetThemePalette -> {
                    callbacks.onSetThemePalette(
                        paletteId = action.paletteId,
                        backgroundStyle = action.backgroundStyle,
                        primaryHex = action.primaryHex,
                        label = action.label
                    )
                }
                is VoiceCommandAction.OpenExternalUrl -> {
                    callbacks.onOpenExternalUrl(action.urlOrQuery, action.rawTranscript)
                }
                is VoiceCommandAction.OpenAdventureDrawer -> {
                    callbacks.onOpenAdventureDrawer(action.tab)
                }
                is VoiceCommandAction.SaveAdventureGame -> {
                    callbacks.onSaveAdventureGame()
                }
                is VoiceCommandAction.CloseAdventureDrawer -> {
                    callbacks.onCloseAdventureDrawer()
                }
                is VoiceCommandAction.None -> false
            }
            return handled
        } finally {
            if (!handled) {
                coordinator.completeCommandProcessingAndRearm(
                    delayMs = SpeechRecognitionCoordinator.MIN_RESTART_DEBOUNCE_MS,
                    reason = "voice_command_unhandled"
                )
            } else if (!deferredNavigationCommit) {
                callbacks.onCommandCompletedAndRearm(
                    action = action,
                    reason = "voice_command_completed:${action::class.simpleName}"
                )
            }
        }
    }

    companion object {
        val DEFAULT_CATEGORIES = listOf(
            "Work",
            "Personal",
            "Projects",
            "Assistant",
            "Code",
            "Custom Personas"
        )

        private val BUILT_IN_WAKE_PREFIXES = listOf(
            "hey gemini",
            "ok gemini",
            "okay gemini",
            "hi gemini",
            "gemini",
            "hey google",
            "ok google",
            "okay google",
            "computer",
            "assistant",
            "jarvis"
        )

        fun stripLeadingWakeWordPrefix(
            rawTranscript: String,
            customWakeWords: Collection<String> = emptyList()
        ): String {
            var working = rawTranscript.trim()
            if (working.isEmpty()) return ""

            val allWakeWords = (customWakeWords.map { it.trim() }.filter { it.isNotEmpty() } +
                BUILT_IN_WAKE_PREFIXES)
                .distinctBy { it.lowercase(Locale.US) }
                .sortedByDescending { it.length }

            var strippedAny = true
            while (strippedAny && working.isNotEmpty()) {
                strippedAny = false
                for (wake in allWakeWords) {
                    val regex = Regex(
                        pattern = "^[\\s,.:;!?\\-]*${Regex.escape(wake)}(?:\\b|[\\s,.:;!?\\-]+)(.*)$",
                        option = RegexOption.IGNORE_CASE
                    )
                    val match = regex.find(working)
                    if (match != null) {
                        val remainder = match.groupValues[1].trim()
                        if (remainder.isNotEmpty()) {
                            working = remainder
                            strippedAny = true
                            break
                        }
                    }
                }
            }
            return working.trim()
        }

        fun normalizeCommandText(raw: String): String {
            return raw.lowercase(Locale.US)
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun isStandaloneNavigationOrActionCommand(
            rawTranscript: String,
            customWakeWords: Collection<String> = emptyList(),
            availableCategories: Collection<String> = DEFAULT_CATEGORIES
        ): Boolean {
            val cleaned = stripLeadingWakeWordPrefix(rawTranscript, customWakeWords)
            val normalized = normalizeCommandText(cleaned)
            if (normalized.isBlank()) return false
            return parseScreenDestination(normalized) != null ||
                parseCategoryFilterCommand(normalized, availableCategories) != null ||
                parseInChatOrThemeCommand(normalized) != null
        }

        fun parseScreenDestination(normalized: String): VoiceScreenDestination? {
            if (normalized.isBlank()) return null

            if (normalized in setOf(
                    "open chat",
                    "go to chat",
                    "show chat",
                    "view chat",
                    "navigate to chat",
                    "active chat",
                    "open active chat",
                    "current chat",
                    "open current chat",
                    "back to chat",
                    "return to chat",
                    "chat screen",
                    "chat"
                )
            ) {
                return VoiceScreenDestination.CHAT
            }

            if (normalized in setOf(
                    "open history",
                    "go to history",
                    "show history",
                    "view history",
                    "navigate to history",
                    "chat history",
                    "open chat history",
                    "show chat history",
                    "history screen",
                    "history"
                )
            ) {
                return VoiceScreenDestination.HISTORY
            }

            if (normalized in setOf(
                    "open settings",
                    "go to settings",
                    "show settings",
                    "view settings",
                    "navigate to settings",
                    "app settings",
                    "open app settings",
                    "settings screen",
                    "preferences",
                    "open preferences",
                    "settings"
                )
            ) {
                return VoiceScreenDestination.SETTINGS
            }

            if (normalized in setOf(
                    "open wake word studio",
                    "go to wake word studio",
                    "show wake word studio",
                    "wake word studio",
                    "open studio",
                    "go to studio",
                    "show studio",
                    "voice studio",
                    "open voice studio",
                    "wake word settings",
                    "open wake word settings",
                    "record wake word"
                )
            ) {
                return VoiceScreenDestination.STUDIO
            }

            if (normalized in setOf(
                    "open diagnostics",
                    "go to diagnostics",
                    "show diagnostics",
                    "view diagnostics",
                    "open diagnostic log",
                    "go to diagnostic log",
                    "show diagnostic log",
                    "diagnostic log",
                    "diagnostics screen",
                    "open logs",
                    "show logs",
                    "system logs"
                )
            ) {
                return VoiceScreenDestination.DIAGNOSTICS
            }

            if (normalized in setOf(
                    "go home",
                    "open home",
                    "go to home",
                    "show home",
                    "home screen",
                    "navigate home",
                    "navigate to home",
                    "back to home",
                    "return home",
                    "main screen",
                    "open main screen"
                )
            ) {
                return VoiceScreenDestination.HOME
            }

            return null
        }

        fun parseCategoryFilterCommand(
            normalized: String,
            availableCategories: Collection<String> = DEFAULT_CATEGORIES
        ): VoiceCommandAction.SelectCategoryFilter? {
            if (normalized.isBlank()) return null

            if (normalized in setOf(
                    "show all categories",
                    "all categories",
                    "filter by all",
                    "clear category filter",
                    "reset category filter",
                    "show all chats",
                    "open all categories",
                    "switch to all categories"
                )
            ) {
                return VoiceCommandAction.SelectCategoryFilter(
                    category = null,
                    displayLabel = "All",
                    rawTranscript = normalized
                )
            }

            val categories = (DEFAULT_CATEGORIES + availableCategories)
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals("All", ignoreCase = true) }
                .distinctBy { it.lowercase(Locale.US) }

            for (cat in categories) {
                val catNorm = normalizeCommandText(cat)
                if (catNorm.isEmpty()) continue
                val candidatePhrases = setOf(
                    "filter by $catNorm",
                    "filter $catNorm",
                    "filter category $catNorm",
                    "show $catNorm category",
                    "open $catNorm category",
                    "go to $catNorm category",
                    "switch to $catNorm category",
                    "select $catNorm category",
                    "category $catNorm",
                    "$catNorm category",
                    "show $catNorm chats",
                    "open $catNorm chats"
                )
                if (normalized in candidatePhrases) {
                    return VoiceCommandAction.SelectCategoryFilter(
                        category = cat,
                        displayLabel = cat,
                        rawTranscript = normalized
                    )
                }
            }

            return null
        }

        fun parseInChatOrThemeCommand(normalized: String): VoiceCommandAction? {
            if (normalized.isBlank()) return null

            return when {
                normalized in setOf(
                    "clear chat",
                    "clear history",
                    "clear conversation",
                    "delete chat messages",
                    "wipe chat"
                ) -> VoiceCommandAction.ClearChatHistory

                normalized in setOf(
                    "read last reply",
                    "read reply",
                    "speak last reply",
                    "read last message",
                    "repeat that",
                    "say that again"
                ) -> VoiceCommandAction.ReadLastReply

                normalized in setOf(
                    "pin chat",
                    "pin this chat",
                    "pin conversation",
                    "favorite chat"
                ) -> VoiceCommandAction.SetChatPinned(pinned = true)

                normalized in setOf(
                    "unpin chat",
                    "unpin this chat",
                    "unpin conversation",
                    "unfavorite chat"
                ) -> VoiceCommandAction.SetChatPinned(pinned = false)

                normalized in setOf(
                    "dark mode",
                    "enable dark mode",
                    "switch to dark mode",
                    "turn on dark mode",
                    "dark theme"
                ) -> VoiceCommandAction.SetThemeMode(AppTheme.DARK, "Dark")

                normalized in setOf(
                    "light mode",
                    "enable light mode",
                    "switch to light mode",
                    "turn on light mode",
                    "light theme"
                ) -> VoiceCommandAction.SetThemeMode(AppTheme.LIGHT, "Light")

                normalized in setOf(
                    "system theme",
                    "system mode",
                    "switch to system theme",
                    "auto theme"
                ) -> VoiceCommandAction.SetThemeMode(AppTheme.SYSTEM, "System")

                normalized in setOf(
                    "purple theme",
                    "purple palette",
                    "color purple"
                ) -> VoiceCommandAction.SetThemePalette("purple", "nebula", "#D0BCFF", "Purple")

                normalized in setOf(
                    "blue theme",
                    "blue palette",
                    "ocean theme",
                    "color blue"
                ) -> VoiceCommandAction.SetThemePalette("blue", "ocean", "#82B1FF", "Blue")

                normalized in setOf(
                    "green theme",
                    "green palette",
                    "forest theme",
                    "color green"
                ) -> VoiceCommandAction.SetThemePalette("green", "forest", "#80CBC4", "Green")

                normalized in setOf(
                    "orange theme",
                    "orange palette",
                    "amber theme",
                    "color orange"
                ) -> VoiceCommandAction.SetThemePalette("orange", "sunset", "#FFCC80", "Orange")

                normalized in setOf(
                    "pink theme",
                    "pink palette",
                    "rose theme",
                    "color pink"
                ) -> VoiceCommandAction.SetThemePalette("pink", "rose", "#F48FB1", "Pink")

                normalized in setOf(
                    "open inventory",
                    "show inventory",
                    "view inventory",
                    "check inventory",
                    "my inventory",
                    "inventory"
                ) -> VoiceCommandAction.OpenAdventureDrawer(AdventureDrawerTab.INVENTORY)

                normalized in setOf(
                    "check quests",
                    "show quests",
                    "open quests",
                    "view quests",
                    "quest log",
                    "open quest log",
                    "my quests",
                    "quests"
                ) -> VoiceCommandAction.OpenAdventureDrawer(AdventureDrawerTab.QUESTS)

                normalized in setOf(
                    "save game",
                    "save adventure",
                    "save campaign",
                    "save progress",
                    "quick save"
                ) -> VoiceCommandAction.SaveAdventureGame

                normalized in setOf(
                    "close drawer",
                    "close adventure drawer",
                    "dismiss drawer",
                    "hide drawer",
                    "close inventory",
                    "close quests"
                ) -> VoiceCommandAction.CloseAdventureDrawer

                else -> null
            }
        }

        fun parseExternalUrlCommand(
            cleanedRaw: String,
            normalized: String
        ): VoiceCommandAction.OpenExternalUrl? {
            val prefixes = listOf(
                "open browser to ",
                "open url ",
                "open website ",
                "browse to "
            )
            for (prefix in prefixes) {
                if (normalized.startsWith(prefix)) {
                    val target = cleanedRaw.drop(prefix.length).trim()
                    if (target.isNotEmpty()) {
                        return VoiceCommandAction.OpenExternalUrl(
                            urlOrQuery = target,
                            rawTranscript = cleanedRaw
                        )
                    }
                }
            }
            return null
        }
    }
}
