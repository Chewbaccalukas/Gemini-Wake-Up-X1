package com.example.util

import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.TriggerType
import com.example.data.model.WakeWord
import com.example.ui.SessionStatisticsUiState
import com.example.ui.components.DailyWakeSessionBucket
import com.example.ui.components.StatisticsDateRange
import com.example.voice.WakeRoutingSource
import com.example.voice.WakeWordRoutingEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class ChatSessionCsvTransformerTest {

    @Test
    fun `transformToCsv prefixes UTF8 BOM and formats 7D 30D AllTime summaries and active range trends`() {
        val fixedNow = 1_762_413_000_000L // 2025-11-06T07:10:00Z
        val chat = ChatSession(
            id = 10L,
            title = "Daily Voice Assistant",
            wakeWord = "Hey Gemini",
            isDefaultWakeChat = true,
            createdAt = fixedNow - 3_600_000L,
            lastUpdatedAt = fixedNow,
            totalTrackedSeconds = 420L,
            totalVoiceCommands = 3
        )
        val userWakeMsg = ChatMessage(
            id = 101L,
            chatId = 10L,
            sender = "user",
            content = "Summarize my schedule,\nand highlight \"urgent\" items",
            timestamp = fixedNow - 60_000L,
            triggeredByWakeWord = true
        )
        val aiReplyMsg = ChatMessage(
            id = 102L,
            chatId = 10L,
            sender = "gemini",
            content = "Here is your schedule:\n1. Team sync\n2. Code review",
            timestamp = fixedNow - 30_000L,
            triggeredByWakeWord = false
        )
        val statsState = SessionStatisticsUiState(
            selectedDateRange = StatisticsDateRange.LAST_30_DAYS,
            dailyBuckets = listOf(
                DailyWakeSessionBucket(
                    dayLabel = "Today",
                    dateLabel = "11/6",
                    startOfDayMillis = fixedNow - 86_400_000L,
                    endOfDayMillis = fixedNow,
                    wakeTriggeredSessions = 2,
                    totalSessions = 4,
                    wakeTriggerCount = 5,
                    totalMessagesCount = 12,
                    isToday = true
                )
            ),
            buckets30d = listOf(
                DailyWakeSessionBucket(
                    dayLabel = "11/2-11/6",
                    dateLabel = "11/6",
                    startOfDayMillis = fixedNow - 5 * 86_400_000L,
                    endOfDayMillis = fixedNow,
                    wakeTriggeredSessions = 3,
                    totalSessions = 5,
                    wakeTriggerCount = 7,
                    totalMessagesCount = 18,
                    isToday = true
                )
            ),
            totalSessions7d = 4,
            wakeTriggeredSessions7d = 2,
            wakeTriggerCount7d = 5,
            wakeSessionPercentage7d = 50,
            totalSessions30d = 5,
            wakeTriggeredSessions30d = 3,
            wakeTriggerCount30d = 7,
            wakeSessionPercentage30d = 60,
            allTimeSessionsCount = 6,
            allTimeWakeTriggeredSessionsCount = 4,
            allTimeMessagesCount = 24,
            allTimeWakeTriggerCount = 9,
            allTimeWakeSessionPercentage = 67,
            allTimeTrackedSeconds = 420L,
            isLoading = false
        )

        val csv = ChatSessionCsvTransformer.transformToCsv(
            chats = listOf(chat),
            messages = listOf(userWakeMsg, aiReplyMsg),
            voiceCommands = listOf(
                VoiceCommandHistoryEntry(
                    id = "vc_1",
                    commandType = VoiceCommandType.WAKE_WORD,
                    triggerPhrase = "Hey Gemini",
                    recognizedTranscript = "Summarize my schedule",
                    chatTitle = "Daily Voice Assistant",
                    timestamp = fixedNow - 60_000L
                )
            ),
            statisticsState = statsState,
            exportedAtMs = fixedNow,
            includeUtf8Bom = true,
            localTimeZone = TimeZone.getTimeZone("UTC")
        )

        // 1. Verify UTF-8 BOM prefix
        assertTrue(csv.startsWith(ChatSessionCsvTransformer.UTF8_BOM))

        // 2. Verify header columns
        val withoutBom = csv.removePrefix(ChatSessionCsvTransformer.UTF8_BOM)
        val firstLine = withoutBom.substringBefore(ChatSessionCsvTransformer.CRLF)
        assertEquals(ChatSessionCsvTransformer.HEADER_COLUMNS.joinToString(","), firstLine)

        // 3. Verify SUMMARY_7D, SUMMARY_30D, SUMMARY_ALL_TIME, BUCKET_TREND_30D, CHAT_SESSION, CHAT_MESSAGE, VOICE_COMMAND rows
        assertTrue(csv.contains("SUMMARY_7D,summary_7d,ALL,Last 7 Days Usage Summary"))
        assertTrue(csv.contains("SUMMARY_30D,summary_30d,ALL,Last 30 Days Usage Summary"))
        assertTrue(csv.contains("SUMMARY_ALL_TIME,summary_all_time,ALL,All-Time Usage Summary"))
        assertTrue(csv.contains("BUCKET_TREND_30D,bucket_30d_"))
        assertTrue(csv.contains("CHAT_SESSION,10,10,Daily Voice Assistant,Hey Gemini,DEFAULT_WAKE_CHAT,true"))
        assertTrue(csv.contains("\"Summarize my schedule,\nand highlight \"\"urgent\"\" items\""))
        assertTrue(csv.contains("VOICE_COMMAND,vc_1,,Daily Voice Assistant,Hey Gemini,WAKE_WORD,true"))
    }

    @Test
    fun `WakeWordRoutingEngine prioritizes specific wake word first then context or favorites then fallback`() {
        val workChat = ChatSession(
            id = 1L,
            title = "Work Projects",
            wakeWord = "Work Mode",
            isDefaultWakeChat = false
        )
        val fallbackChat = ChatSession(
            id = 2L,
            title = "General Assistant",
            wakeWord = "Hey Gemini",
            isDefaultWakeChat = true
        )
        val configuredWakeWords = listOf(
            WakeWord(
                id = 100L,
                phrase = "Work Mode",
                triggerType = TriggerType.CHAT,
                chatId = 1L,
                chatTitle = "Work Projects",
                isEnabled = true
            )
        )

        // 1. Specific wake word matches Work Projects directly
        val specificDecision = WakeWordRoutingEngine.resolveTargetChatForWakeWord(
            spokenWakePhrase = "Work Mode",
            chats = listOf(workChat, fallbackChat),
            configuredWakeWords = configuredWakeWords,
            favoriteChatIds = setOf(2L),
            contextualRoutingEnabled = true
        )
        assertEquals(WakeRoutingSource.SPECIFIC_WAKE_WORD, specificDecision?.routingSource)
        assertEquals(1L, specificDecision?.targetChat?.id)

        // 2. Unrecognized phrase falls back to default chat when contextual routing is disabled
        val fallbackDecision = WakeWordRoutingEngine.resolveTargetChatForWakeWord(
            spokenWakePhrase = "Unknown Trigger",
            chats = listOf(workChat, fallbackChat),
            configuredWakeWords = configuredWakeWords,
            favoriteChatIds = emptySet(),
            contextualRoutingEnabled = false
        )
        assertEquals(WakeRoutingSource.FALLBACK_DEFAULT, fallbackDecision?.routingSource)
        assertEquals(2L, fallbackDecision?.targetChat?.id)
    }
}
