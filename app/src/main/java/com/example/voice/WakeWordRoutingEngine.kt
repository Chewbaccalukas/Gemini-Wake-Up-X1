package com.example.voice

import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.TriggerType
import com.example.data.model.WakeWord
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class WakeRoutingSource(val displayLabel: String) {
    SPECIFIC_WAKE_WORD("Custom Wake Word Match"),
    CATEGORY_MATCH("Category Match"),
    CONTEXTUAL_AUTO("Smart Contextual Match"),
    FAVORITES_PROMPT("Favorite Chats Selection"),
    FALLBACK_DEFAULT("Fallback Default Chat")
}

/**
 * Contextual suggestion item scored by time-of-day, workday schedule, recent activity,
 * and favorite status (without requiring location permissions).
 */
data class ContextualChatSuggestion(
    val chat: ChatSession,
    val score: Int,
    val isFavorite: Boolean,
    val contextReason: String,
    val contextBadge: String
)

/**
 * Result of evaluating which chat to open (and what suggestions/favorites/categories to surface)
 * when a wake word is detected.
 */
data class WakeRoutingDecision(
    val targetChat: ChatSession,
    val routingSource: WakeRoutingSource,
    val routingReason: String,
    val matchedWakePhrase: String,
    val shouldPromptFavoritesSheet: Boolean,
    val favoriteChats: List<ChatSession>,
    val contextualSuggestions: List<ContextualChatSuggestion>,
    val fallbackChat: ChatSession,
    val matchedCategory: String? = null,
    val categoryChats: List<ChatSession> = emptyList()
)

/**
 * Structured summary of the most recent messages in a chat session when opened via wake word.
 */
data class WakeConversationSummary(
    val chatId: Long,
    val chatTitle: String,
    val matchedWakeWord: String,
    val routingSource: WakeRoutingSource,
    val routingReason: String,
    val headline: String,
    val instantRecapBullets: List<String>,
    val keyTopics: List<String>,
    val aiExpandedSummary: String? = null,
    val isGeneratingAiSummary: Boolean = false,
    val recentMessageCount: Int = 0,
    val generatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Multi-tier wake-word routing engine implementing:
 * 1. **Specific Wake Word Match** -> Opens the chat explicitly associated with the spoken wake word.
 * 2. **Category / Folder & Specific Chat in Category Match** -> When a user specifies a category
 *    (e.g., "Work", "Personal", "Projects", "Research") or a specific chat within a category,
 *    opens that chat directly or prompts the user to pick among the chats in that category if multiple exist.
 * 3. **Contextual Suggestion / Auto-Selection & Favorites Prompt** -> Uses time-of-day, workday vs.
 *    weekend schedule, recent activity, and starred favorite chats (no location permission needed).
 * 4. **Fallback Default Chat** -> Ensures a default/general chat always opens if a wake word is
 *    unrecognized or unlinked.
 * 5. **Wake-Word Conversation Summary Generator** -> Builds an instant local recap of the most
 *    recent messages (with optional AI expansion) when a chat opens via wake word.
 */
object WakeWordRoutingEngine {

    private val GENERIC_WAKE_PHRASES = setOf(
        "hey gemini",
        "gemini",
        "ok gemini",
        "hi gemini",
        "wake up",
        "hey assistant"
    )

    private val WORKDAY_KEYWORDS = listOf(
        "work", "code", "project", "dev", "engineering", "meeting", "sprint",
        "task", "bug", "architecture", "sql", "kotlin", "review", "office", "study"
    )

    private val MORNING_KEYWORDS = listOf(
        "morning", "brief", "daily", "plan", "schedule", "todo", "news", "agenda", "standup"
    )

    private val EVENING_CREATIVE_KEYWORDS = listOf(
        "creative", "idea", "brainstorm", "story", "journal", "design", "music", "spark", "hobby"
    )

    /**
     * Resolves the best target chat and contextual suggestions when [spokenWakePhrase] is detected.
     *
     * Priority order:
     * 1. Specific wake word linked to a chat.
     * 2. Category / Folder match (or specific chat within a category; prompts if multiple exist).
     * 3. Context-aware auto-selection or Favorites prompt.
     * 4. Fallback default chat.
     */
    fun resolveTargetChatForWakeWord(
        spokenWakePhrase: String,
        chats: List<ChatSession>,
        configuredWakeWords: List<WakeWord>,
        favoriteChatIds: Set<Long> = emptySet(),
        chatCategoryMap: Map<Long, String> = emptyMap(),
        chatTagsMap: Map<Long, Set<String>> = emptyMap(),
        spokenContextOrTranscript: String = "",
        contextualRoutingEnabled: Boolean = true,
        promptFavoritesOnGeneralWake: Boolean = false,
        nowMillis: Long = System.currentTimeMillis()
    ): WakeRoutingDecision? {
        val activeChats = chats.filter { !it.isDeleted }
        if (activeChats.isEmpty()) return null

        val fallbackChat = resolveFallbackChat(activeChats)
        val favoriteChats = activeChats.filter { favoriteChatIds.contains(it.id) }
        val suggestions = rankContextualChatSuggestions(
            chats = activeChats,
            favoriteChatIds = favoriteChatIds,
            nowMillis = nowMillis
        )

        val normalizedSpoken = spokenWakePhrase.trim().lowercase(Locale.ROOT)
        val combinedSpoken = "$normalizedSpoken ${spokenContextOrTranscript.trim().lowercase(Locale.ROOT)}".trim()
        val isGenericPhrase = normalizedSpoken.isBlank() || normalizedSpoken in GENERIC_WAKE_PHRASES

        // 1. Check for a specific custom wake word match first
        if (normalizedSpoken.isNotBlank()) {
            val matchedConfigured = configuredWakeWords
                .filter { it.isEnabled && !it.isDeleted && it.triggerType == TriggerType.CHAT }
                .firstOrNull { it.phrase.trim().equals(spokenWakePhrase.trim(), ignoreCase = true) }

            val linkedFromConfigured = matchedConfigured?.effectiveTargetChatId?.let { targetId ->
                activeChats.firstOrNull { it.id == targetId }
            } ?: matchedConfigured?.chatTitle?.takeIf { it.isNotBlank() }?.let { title ->
                activeChats.firstOrNull { it.title.equals(title, ignoreCase = true) }
            }

            val linkedFromChatEntity = activeChats.firstOrNull { chat ->
                chat.wakeWord.trim().equals(spokenWakePhrase.trim(), ignoreCase = true)
            }

            val specificChat = linkedFromConfigured ?: linkedFromChatEntity
            if (specificChat != null && (!isGenericPhrase || !contextualRoutingEnabled)) {
                return WakeRoutingDecision(
                    targetChat = specificChat,
                    routingSource = WakeRoutingSource.SPECIFIC_WAKE_WORD,
                    routingReason = "Matched wake word \"${spokenWakePhrase.trim()}\" → ${specificChat.title}",
                    matchedWakePhrase = spokenWakePhrase.trim(),
                    shouldPromptFavoritesSheet = promptFavoritesOnGeneralWake && isGenericPhrase && favoriteChats.size > 1,
                    favoriteChats = favoriteChats,
                    contextualSuggestions = suggestions,
                    fallbackChat = fallbackChat
                )
            }
        }

        // 2. Check for Category / Folder or Tag specification in spoken phrase or trailing utterance
        if (combinedSpoken.isNotBlank() && chatCategoryMap.isNotEmpty()) {
            val distinctCategories = chatCategoryMap.values.map { it.trim() }.filter { it.isNotBlank() }.distinct()
            val matchedCategoryName = distinctCategories.firstOrNull { cat ->
                val catLower = cat.lowercase(Locale.ROOT)
                Regex("\\b${Regex.escape(catLower)}\\b").containsMatchIn(combinedSpoken)
            }
            if (matchedCategoryName != null) {
                val chatsInCategory = activeChats.filter { chat ->
                    chatCategoryMap[chat.id]?.equals(matchedCategoryName, ignoreCase = true) == true
                }
                if (chatsInCategory.isNotEmpty()) {
                    // Check if the user also named a specific chat inside that category
                    val namedChatInCat = chatsInCategory.firstOrNull { chat ->
                        combinedSpoken.contains(chat.title.trim().lowercase(Locale.ROOT)) ||
                            combinedSpoken.contains(chat.wakeWord.trim().lowercase(Locale.ROOT))
                    }
                    val chosenTarget = namedChatInCat
                        ?: suggestions.firstOrNull { s -> chatsInCategory.any { it.id == s.chat.id } }?.chat
                        ?: chatsInCategory.first()
                    val promptDisambiguation = namedChatInCat == null && chatsInCategory.size > 1

                    return WakeRoutingDecision(
                        targetChat = chosenTarget,
                        routingSource = WakeRoutingSource.CATEGORY_MATCH,
                        routingReason = if (namedChatInCat != null) {
                            "Category \"$matchedCategoryName\" → ${namedChatInCat.title}"
                        } else if (chatsInCategory.size > 1) {
                            "Category \"$matchedCategoryName\" (${chatsInCategory.size} chats) • Choose or continue in ${chosenTarget.title}"
                        } else {
                            "Category \"$matchedCategoryName\" → ${chosenTarget.title}"
                        },
                        matchedWakePhrase = spokenWakePhrase.ifBlank { matchedCategoryName },
                        shouldPromptFavoritesSheet = promptDisambiguation,
                        favoriteChats = favoriteChats,
                        contextualSuggestions = suggestions,
                        fallbackChat = fallbackChat,
                        matchedCategory = matchedCategoryName,
                        categoryChats = chatsInCategory
                    )
                }
            }
        }

        // 3. If user enabled Favorites prompt and has favorite chats, prompt or route to top favorite
        if (promptFavoritesOnGeneralWake && favoriteChats.isNotEmpty()) {
            val primaryFav = suggestions.firstOrNull { it.isFavorite }?.chat ?: favoriteChats.first()
            return WakeRoutingDecision(
                targetChat = primaryFav,
                routingSource = WakeRoutingSource.FAVORITES_PROMPT,
                routingReason = "Choose from your ${favoriteChats.size} favorite chat(s) or contextual suggestions",
                matchedWakePhrase = spokenWakePhrase.ifBlank { "Hey Gemini" },
                shouldPromptFavoritesSheet = true,
                favoriteChats = favoriteChats,
                contextualSuggestions = suggestions,
                fallbackChat = fallbackChat
            )
        }

        // 4. Contextual Auto-Selection (time of day, workday schedule, recent activity)
        if (contextualRoutingEnabled && suggestions.isNotEmpty()) {
            val topSuggestion = suggestions.first()
            if (topSuggestion.score > 20) {
                return WakeRoutingDecision(
                    targetChat = topSuggestion.chat,
                    routingSource = WakeRoutingSource.CONTEXTUAL_AUTO,
                    routingReason = "${topSuggestion.contextBadge}: ${topSuggestion.contextReason}",
                    matchedWakePhrase = spokenWakePhrase.ifBlank { topSuggestion.chat.wakeWord },
                    shouldPromptFavoritesSheet = false,
                    favoriteChats = favoriteChats,
                    contextualSuggestions = suggestions,
                    fallbackChat = fallbackChat
                )
            }
        }

        // 5. Fallback Default Chat
        return WakeRoutingDecision(
            targetChat = fallbackChat,
            routingSource = WakeRoutingSource.FALLBACK_DEFAULT,
            routingReason = "Fallback chat opened (unrecognized or general wake trigger)",
            matchedWakePhrase = spokenWakePhrase.ifBlank { fallbackChat.wakeWord },
            shouldPromptFavoritesSheet = false,
            favoriteChats = favoriteChats,
            contextualSuggestions = suggestions,
            fallbackChat = fallbackChat
        )
    }

    /**
     * Resolves the designated fallback chat (`isDefaultWakeChat == true` or first available chat).
     */
    fun resolveFallbackChat(chats: List<ChatSession>): ChatSession {
        val active = chats.filter { !it.isDeleted }
        return active.firstOrNull { it.isDefaultWakeChat }
            ?: active.firstOrNull()
            ?: ChatSession(
                id = 1L,
                title = "General Gemini",
                wakeWord = "Hey Gemini",
                isDefaultWakeChat = true
            )
    }

    /**
     * Ranks all active chats by contextual relevance using:
     * - Time of Day (Morning 5–11, Workday Focus Mon–Fri 9–17, Evening/Creative 18–23)
     * - Workday vs. Weekend schedule
     * - Recent conversation activity (`lastUpdatedAt` within 2 hours or 24 hours)
     * - Starred Favorite status (`favoriteChatIds`)
     */
    fun rankContextualChatSuggestions(
        chats: List<ChatSession>,
        favoriteChatIds: Set<Long> = emptySet(),
        nowMillis: Long = System.currentTimeMillis()
    ): List<ContextualChatSuggestion> {
        val activeChats = chats.filter { !it.isDeleted }
        if (activeChats.isEmpty()) return emptyList()

        val calendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val hourOfDay = calendar.get(Calendar.HOUR_OF_DAY)
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val isWorkday = dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY
        val isWorkHours = isWorkday && hourOfDay in 9..17
        val isMorning = hourOfDay in 5..11
        val isEvening = hourOfDay >= 18 || hourOfDay < 5

        val mostRecentChatId = activeChats.maxByOrNull { it.lastUpdatedAt }?.id

        return activeChats.map { chat ->
            val searchable = "${chat.title} ${chat.wakeWord} ${chat.systemInstruction}".lowercase(Locale.ROOT)
            val isFav = favoriteChatIds.contains(chat.id)
            var score = 0
            val reasons = mutableListOf<String>()
            var badge = "Suggested"

            // Workday Focus Hours match (e.g. "Work Projects", "Code Mode")
            if (isWorkHours && (WORKDAY_KEYWORDS.any { searchable.contains(it) } || chat.iconName.equals("Code", ignoreCase = true))) {
                score += 45
                badge = "Workday Focus"
                reasons.add("Matches workday hours")
            }

            // Morning Briefing match
            if (isMorning && MORNING_KEYWORDS.any { searchable.contains(it) }) {
                score += 40
                badge = "Morning Routine"
                reasons.add("Matches morning schedule")
            }

            // Evening / Weekend Creative match
            if ((isEvening || !isWorkday) && (EVENING_CREATIVE_KEYWORDS.any { searchable.contains(it) } || chat.iconName.equals("Lightbulb", ignoreCase = true))) {
                score += 38
                badge = if (!isWorkday) "Weekend Creative" else "Evening Focus"
                reasons.add(if (!isWorkday) "Great for weekend brainstorming" else "Matches evening creative hours")
            }

            // Recent conversation activity
            val ageMinutes = ((nowMillis - chat.lastUpdatedAt).coerceAtLeast(0L)) / 60_000L
            when {
                chat.id == mostRecentChatId && ageMinutes <= 120L -> {
                    score += 30
                    if (badge == "Suggested") badge = "Recent Activity"
                    reasons.add("Active ${ageMinutes.coerceAtLeast(1)}m ago")
                }
                ageMinutes <= 24L * 60L -> {
                    score += 15
                    reasons.add("Used today")
                }
            }

            // Favorite boost
            if (isFav) {
                score += 25
                if (badge == "Suggested") badge = "Favorite"
                reasons.add("Starred favorite")
            }

            // Voice command usage boost
            if (chat.totalVoiceCommands > 0) {
                score += (chat.totalVoiceCommands * 3).coerceAtMost(15)
            }

            // Default wake chat baseline
            if (chat.isDefaultWakeChat) {
                score += 12
                if (reasons.isEmpty()) {
                    badge = "Fallback Default"
                    reasons.add("Default wake-word chat")
                }
            }

            val reasonSummary = if (reasons.isNotEmpty()) {
                reasons.joinToString(" • ")
            } else {
                "General conversation"
            }

            ContextualChatSuggestion(
                chat = chat,
                score = score,
                isFavorite = isFav,
                contextReason = reasonSummary,
                contextBadge = badge
            )
        }.sortedWith(
            compareByDescending<ContextualChatSuggestion> { it.score }
                .thenByDescending { it.isFavorite }
                .thenByDescending { it.chat.lastUpdatedAt }
        )
    }

    /**
     * Generates an instant local summary of the most recent messages in [chat] when opened
     * via a wake word so the user can immediately grasp the conversation context without scrolling.
     */
    fun buildInstantWakeConversationSummary(
        chat: ChatSession,
        messages: List<ChatMessage>,
        matchedWakeWord: String = chat.wakeWord,
        routingSource: WakeRoutingSource = WakeRoutingSource.SPECIFIC_WAKE_WORD,
        routingReason: String = "Opened via wake word \"$matchedWakeWord\""
    ): WakeConversationSummary {
        val validMessages = messages
            .filter { !it.isDeleted && it.hasPersistableContent }
            .sortedWith(compareBy<ChatMessage> { it.timestamp }.thenBy { it.id })

        val recentSlice = validMessages.takeLast(6)
        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

        val bullets = if (recentSlice.isEmpty()) {
            listOf(
                "No prior messages in ${chat.title} yet.",
                "Speak your prompt or tap the microphone to start this session."
            )
        } else {
            val lastUserMsg = recentSlice.lastOrNull { it.sender.equals("user", ignoreCase = true) }
            val lastGeminiMsg = recentSlice.lastOrNull { !it.sender.equals("user", ignoreCase = true) }
            val earliestInSlice = recentSlice.first()

            buildList {
                if (lastUserMsg != null) {
                    val snippet = lastUserMsg.content.replace("\n", " ").trim().take(110)
                    val ellipsis = if (lastUserMsg.content.length > 110) "…" else ""
                    add("Last You (${timeFormat.format(Date(lastUserMsg.timestamp))}): \"$snippet$ellipsis\"")
                }
                if (lastGeminiMsg != null) {
                    val snippet = lastGeminiMsg.content.replace("\n", " ").trim().take(130)
                    val ellipsis = if (lastGeminiMsg.content.length > 130) "…" else ""
                    add("Last Gemini (${timeFormat.format(Date(lastGeminiMsg.timestamp))}): \"$snippet$ellipsis\"")
                }
                if (recentSlice.size > 2) {
                    val wakeCount = recentSlice.count { it.triggeredByWakeWord }
                    add("Recent context: ${recentSlice.size} recent turns (${wakeCount} voice-triggered) since ${timeFormat.format(Date(earliestInSlice.timestamp))}.")
                }
            }
        }

        val topics = extractKeyTopics(recentSlice, chat.title)
        val headline = if (validMessages.isEmpty()) {
            "Fresh conversation • Ready for \"${matchedWakeWord.ifBlank { chat.wakeWord }}\""
        } else {
            "Recap of last ${recentSlice.size} of ${validMessages.size} message(s) in ${chat.title}"
        }

        return WakeConversationSummary(
            chatId = chat.id,
            chatTitle = chat.title,
            matchedWakeWord = matchedWakeWord.ifBlank { chat.wakeWord },
            routingSource = routingSource,
            routingReason = routingReason,
            headline = headline,
            instantRecapBullets = bullets,
            keyTopics = topics,
            recentMessageCount = validMessages.size
        )
    }

    private fun extractKeyTopics(messages: List<ChatMessage>, fallbackTitle: String): List<String> {
        val stopWords = setOf(
            "the", "and", "for", "that", "this", "with", "from", "have", "your", "what",
            "when", "where", "which", "would", "could", "should", "about", "into", "just",
            "like", "some", "more", "than", "then", "them", "they", "were", "been", "will",
            "hello", "gemini", "please", "help", "can", "you", "are", "how", "make", "tell"
        )
        val counts = mutableMapOf<String, Int>()
        messages.forEach { msg ->
            msg.content
                .lowercase(Locale.ROOT)
                .split("[^a-z0-9]+".toRegex())
                .filter { it.length in 4..18 && it !in stopWords }
                .forEach { word ->
                    counts[word] = (counts[word] ?: 0) + 1
                }
        }
        val topWords = counts.entries
            .sortedByDescending { it.value }
            .take(4)
            .map { entry -> entry.key.replaceFirstChar { it.titlecase(Locale.ROOT) } }

        return if (topWords.isNotEmpty()) topWords else listOf(fallbackTitle)
    }
}
