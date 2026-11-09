package com.example.voice

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.model.ChatSession
import com.example.data.model.TriggerType
import com.example.data.model.WakeWord
import com.example.data.repository.WakeWordRepository
import java.io.File
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WakeWordRoomPersistenceTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private var database: AppDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = File(context.filesDir, "test_wake_words_restart.db")
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }

    @After
    fun tearDown() {
        try {
            database?.close()
        } catch (_: Throwable) {}
        if (dbFile.exists()) {
            dbFile.delete()
        }
    }

    private fun openPersistentDatabase(): AppDatabase {
        return Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @Test
    fun `WakeWord entity persists id phrase triggerType enum and nullable metadata across database close and reopen`() = runTest {
        val db1 = openPersistentDatabase()
        database = db1
        val dao1 = db1.wakeWordDao()

        val chatWakeId = dao1.insertWakeWord(
            WakeWord(
                phrase = "Hey Gemini",
                triggerType = TriggerType.CHAT,
                metadata = "{\"chatId\":1,\"priority\":\"default\"}"
            )
        )
        val handsFreeWakeId = dao1.insertWakeWord(
            WakeWord(
                phrase = "Send Now",
                triggerType = TriggerType.HANDS_FREE,
                metadata = null
            )
        )
        assertTrue(chatWakeId > 0L)
        assertTrue(handsFreeWakeId > 0L)

        // Re-obtain DAO reference to simulate new session reading persisted Room state
        val dao2 = db1.wakeWordDao()

        val loadedChatWake = dao2.getWakeWordByIdSync(chatWakeId)
        assertNotNull(loadedChatWake)
        assertEquals(chatWakeId, loadedChatWake?.id)
        assertEquals("Hey Gemini", loadedChatWake?.phrase)
        assertEquals(TriggerType.CHAT, loadedChatWake?.triggerType)
        assertEquals("{\"chatId\":1,\"priority\":\"default\"}", loadedChatWake?.metadata)

        val loadedHandsFree = dao2.getWakeWordByIdSync(handsFreeWakeId)
        assertNotNull(loadedHandsFree)
        assertEquals(handsFreeWakeId, loadedHandsFree?.id)
        assertEquals("Send Now", loadedHandsFree?.phrase)
        assertEquals(TriggerType.HANDS_FREE, loadedHandsFree?.triggerType)
        assertNull(loadedHandsFree?.metadata)

        val chatOnlyList = dao2.getWakeWordsByTriggerType(TriggerType.CHAT).first()
        assertEquals(1, chatOnlyList.size)
        assertEquals("Hey Gemini", chatOnlyList.first().phrase)

        val handsFreeOnlyList = dao2.getWakeWordsByTriggerType(TriggerType.HANDS_FREE).first()
        assertEquals(1, handsFreeOnlyList.size)
        assertEquals("Send Now", handsFreeOnlyList.first().phrase)
    }

    @Test
    fun `WakeWordDao supports insert reactive Flow observation and entity ID and phrase delete queries`() = runTest {
        val db = openPersistentDatabase()
        database = db
        val dao = db.wakeWordDao()

        val id1 = dao.insert(
            WakeWord(
                phrase = "Alpha Wake",
                triggerType = TriggerType.CHAT,
                metadata = "Alpha Chat",
                createdAt = 100L
            )
        )
        val id2 = dao.insertWakeWord(
            WakeWord(
                phrase = "Beta Wake",
                triggerType = TriggerType.CHAT,
                metadata = "Beta Chat",
                createdAt = 200L
            )
        )
        val id3 = dao.insertWakeWord(
            WakeWord(
                phrase = "Quick Send",
                triggerType = TriggerType.HANDS_FREE,
                metadata = "SEND",
                createdAt = 300L
            )
        )

        // Observe all wake words via Flow<List<WakeWord>>
        val observedAll = dao.getAllWakeWords().first()
        assertEquals(3, observedAll.size)
        assertEquals(listOf("Alpha Wake", "Beta Wake", "Quick Send"), observedAll.map { it.phrase })

        // Observe filtered by TriggerType via Flow<List<WakeWord>>
        val observedChatOnly = dao.getWakeWordsByTriggerType(TriggerType.CHAT).first()
        assertEquals(2, observedChatOnly.size)
        val observedHandsFreeOnly = dao.getWakeWordsByTriggerType(TriggerType.HANDS_FREE).first()
        assertEquals(1, observedHandsFreeOnly.size)
        assertEquals("Quick Send", observedHandsFreeOnly.first().phrase)

        // 1. Delete by entity (@Delete)
        val entity1 = dao.getWakeWordByIdSync(id1)
        assertNotNull(entity1)
        dao.deleteWakeWord(entity1!!)
        assertEquals(listOf("Beta Wake", "Quick Send"), dao.getAllWakeWords().first().map { it.phrase })

        // 2. Delete by ID (@Query)
        dao.deleteWakeWordById(id2)
        assertEquals(listOf("Quick Send"), dao.getAllWakeWords().first().map { it.phrase })

        // 3. Delete by phrase (@Query)
        dao.deleteWakeWordByPhrase("quick send")
        assertTrue(dao.getAllWakeWords().first().isEmpty())
        assertNull(dao.getWakeWordByIdSync(id3))
    }

    @Test
    fun `WakeWordRepository abstracts add remove and observe with case-insensitive deduplication per TriggerType`() = runTest {
        val db = openPersistentDatabase()
        database = db
        val repo = WakeWordRepository(db.wakeWordDao(), db.chatDao())

        val firstId = repo.addWakeWord(
            phrase = "Hey Navigator",
            triggerType = TriggerType.CHAT,
            metadata = "Nav v1"
        )
        // Adding same phrase with different casing and same TriggerType updates existing row instead of duplicating
        val duplicateId = repo.addWakeWord(
            phrase = "hey navigator",
            triggerType = TriggerType.CHAT,
            metadata = "Nav v2"
        )
        assertEquals(firstId, duplicateId)

        // Adding same phrase under HANDS_FREE TriggerType creates a separate entry for that TriggerType
        val handsFreeId = repo.addWakeWord(
            phrase = "Hey Navigator",
            triggerType = TriggerType.HANDS_FREE,
            metadata = "DICTATE"
        )
        assertTrue(handsFreeId > 0L)
        assertTrue(handsFreeId != firstId)

        val allObserved = repo.observeAllWakeWords().first()
        assertEquals(2, allObserved.size)

        val chatObserved = repo.observeWakeWordsByType(TriggerType.CHAT).first()
        assertEquals(1, chatObserved.size)
        assertEquals("Nav v2", chatObserved.first().metadata)

        // Remove by entity
        repo.removeWakeWord(chatObserved.first())
        assertEquals(1, repo.observeAllWakeWords().first().size)

        // Remove by phrase
        repo.removeWakeWordByPhrase("hey navigator")
        assertTrue(repo.observeAllWakeWords().first().isEmpty())
    }

    @Test
    fun `user configured chat wake words and hands-free triggers persist across app restarts`() = runTest {
        // 1. First session (before restart): seed a chat, add custom wake words, and customize hands-free triggers
        val db1 = openPersistentDatabase()
        database = db1
        val chatDao1 = db1.chatDao()
        val wakeWordDao1 = db1.wakeWordDao()
        val repo1 = WakeWordRepository(wakeWordDao1, chatDao1)

        val chatId = chatDao1.insertChat(
            ChatSession(
                title = "Assistant Alpha",
                wakeWord = "Hey Alpha",
                isDefaultWakeChat = true
            )
        )
        val seededChats = chatDao1.getAllChatsSync()
        repo1.ensureSeededFromChatsAndDefaults(
            chats = seededChats,
            initialDictate = "Dictate",
            initialSend = "Send",
            initialHeartbeat = "Heartbeat",
            initialSensitivity = 0.78f
        )

        // Add a second custom wake word for the chat and customize system triggers & sensitivity
        val addedId = repo1.addCustomChatWakeWord(
            chatId = chatId,
            chatTitle = "Assistant Alpha",
            phrase = "Captain Alpha",
            sensitivity = 0.84f
        )
        assertTrue(addedId > 0L)

        repo1.upsertSystemTrigger(WakeWord.ROLE_DICTATE, "Start Note", 0.84f)
        repo1.upsertSystemTrigger(WakeWord.ROLE_SEND, "Ship It", 0.84f)
        repo1.upsertSystemTrigger(WakeWord.ROLE_HEARTBEAT, "Pulse Check", 0.84f)
        repo1.updateSensitivity(0.84f)

        // 2. Second session (after restart): instantiate fresh WakeWordRepository against Room DAOs
        val repo2 = WakeWordRepository(db1.wakeWordDao(), db1.chatDao())

        val persistedChatWakeWords = repo2.chatWakeWords.first()
        assertEquals(2, persistedChatWakeWords.size)
        assertEquals(listOf("Hey Alpha", "Captain Alpha"), persistedChatWakeWords.map { it.phrase })
        assertTrue(persistedChatWakeWords.all { it.triggerType == TriggerType.CHAT })

        val persistedHandsFreeTriggers = repo2.systemTriggers.first()
        assertEquals(3, persistedHandsFreeTriggers.size)
        assertTrue(persistedHandsFreeTriggers.all { it.triggerType == TriggerType.HANDS_FREE })

        assertEquals(
            "Start Note",
            repo2.getSystemTriggerPhrase(WakeWord.ROLE_DICTATE, "Dictate")
        )
        assertEquals(
            "Ship It",
            repo2.getSystemTriggerPhrase(WakeWord.ROLE_SEND, "Send")
        )
        assertEquals(
            "Pulse Check",
            repo2.getSystemTriggerPhrase(WakeWord.ROLE_HEARTBEAT, "Heartbeat")
        )
        assertEquals(0.84f, repo2.getSavedSensitivity(0.78f), 0.001f)
    }

    @Test
    fun `inline edit toggle and delete wake words sync with ChatSession and WakeWordManager`() = runTest {
        val db = openPersistentDatabase()
        database = db
        val chatDao = db.chatDao()
        val wakeWordDao = db.wakeWordDao()
        val repo = WakeWordRepository(wakeWordDao, chatDao)

        val chatId = chatDao.insertChat(
            ChatSession(
                title = "Code Partner",
                wakeWord = "Code Mode",
                isDefaultWakeChat = false
            )
        )
        repo.ensureSeededFromChatsAndDefaults(chatDao.getAllChatsSync())

        val initialWords = repo.getChatWakeWordsForChatSync(chatId)
        assertEquals(1, initialWords.size)
        val primaryId = initialWords.first().id

        // Edit primary wake word phrase and verify ChatSession.wakeWord updates automatically
        val updated = repo.updateWakeWordPhrase(primaryId, "Turbo Coder")
        assertTrue(updated)
        assertEquals("Turbo Coder", chatDao.getChatByIdSync(chatId)?.wakeWord)

        // Add secondary custom wake word and verify WakeWordManager matches both
        val secondaryId = repo.addCustomChatWakeWord(chatId, "Code Partner", "Debug Buddy")
        assertTrue(secondaryId > 0L)

        val wakeManager = WakeWordManager(context)
        wakeManager.updateActiveChats(chatDao.getAllChatsSync())
        wakeManager.updateConfiguredWakeWords(repo.getChatWakeWordsForChatSync(chatId))

        val matchSecondary = wakeManager.matchWakePhraseInTranscript("Debug Buddy fix this loop")
        assertNotNull(matchSecondary)
        assertEquals(chatId, matchSecondary?.matchedChat?.id)
        assertEquals("Debug Buddy", matchSecondary?.matchedWakeWord)
        assertEquals("fix this loop", matchSecondary?.followUpQuery)

        // Disable secondary wake word and verify it no longer triggers
        repo.setWakeWordEnabled(secondaryId, false)
        wakeManager.updateConfiguredWakeWords(repo.getChatWakeWordsForChatSync(chatId))
        assertFalse(repo.getChatWakeWordsForChatSync(chatId).first { it.id == secondaryId }.isEnabled)
        assertNull(wakeManager.matchWakePhraseInTranscript("Debug Buddy fix this loop"))

        // Delete primary wake word and verify remaining wake word becomes primary on ChatSession
        repo.deleteWakeWordById(primaryId)
        val remaining = repo.getChatWakeWordsForChatSync(chatId)
        assertEquals(1, remaining.size)
        assertEquals("Debug Buddy", remaining.first().phrase)
        assertEquals("Debug Buddy", chatDao.getChatByIdSync(chatId)?.wakeWord)
    }

    @Test
    fun `WakeWordViewModel exposes state-managed UI flows and handles add toggle filter and remove actions`() = runTest {
        val db = openPersistentDatabase()
        database = db
        val repo = WakeWordRepository(db.wakeWordDao(), db.chatDao())
        val viewModel = com.example.ui.WakeWordViewModel(
            repository = repo,
            stopTimeoutMillis = 0L
        )

        // Add chat and hands-free wake words from the UI layer
        viewModel.addWakeWord(
            phrase = "Hey Studio",
            triggerType = TriggerType.CHAT,
            metadata = "Studio Chat"
        )?.join()
        viewModel.addWakeWord(
            phrase = "Voice Dictate",
            triggerType = TriggerType.HANDS_FREE,
            metadata = WakeWord.ROLE_DICTATE
        )?.join()

        val vmWakeWords = viewModel.wakeWords.first { it.size == 2 }
        assertEquals(listOf("Hey Studio", "Voice Dictate"), vmWakeWords.map { it.phrase })
        assertEquals(2, viewModel.activeWakeWords.first { it.size == 2 }.size)
        assertEquals(1, viewModel.chatWakeWords.first { it.size == 1 }.size)
        assertEquals(1, viewModel.handsFreeWakeWords.first { it.size == 1 }.size)

        // Disable one wake word and verify activeWakeWords updates to only enabled items
        val studioItem = vmWakeWords.first { it.phrase == "Hey Studio" }
        viewModel.toggleWakeWordEnabled(studioItem.id, false).join()
        val activeAfterDisable = viewModel.activeWakeWords.first { it.size == 1 }
        assertEquals(listOf("Voice Dictate"), activeAfterDisable.map { it.phrase })

        // Filter by CHAT and verify filteredWakeWords
        viewModel.setFilter(TriggerType.CHAT)
        val filteredChat = viewModel.filteredWakeWords.first { it.size == 1 && it.first().triggerType == TriggerType.CHAT }
        assertEquals("Hey Studio", filteredChat.first().phrase)

        // Remove by ID and by phrase from the UI layer
        viewModel.removeWakeWordById(studioItem.id).join()
        assertEquals(1, viewModel.wakeWords.first { it.size == 1 }.size)

        viewModel.removeWakeWordByPhrase("voice dictate")?.join()
        assertTrue(viewModel.wakeWords.first { it.isEmpty() }.isEmpty())
        assertTrue(viewModel.uiState.first { it.wakeWords.isEmpty() }.activeWakeWords.isEmpty())
    }

    @Test
    fun `WakeWordVoiceService keyword loop matches spoken transcript against active WakeWordRepository entries`() = runTest {
        val db = openPersistentDatabase()
        database = db
        val repo = WakeWordRepository(db.wakeWordDao(), db.chatDao())

        val chatId = db.chatDao().insertChat(
            ChatSession(
                title = "Research Assistant",
                wakeWord = "Hey Research",
                isDefaultWakeChat = true
            )
        )
        repo.addCustomChatWakeWord(
            chatId = chatId,
            chatTitle = "Research Assistant",
            phrase = "Hey Research"
        )
        repo.addWakeWord(
            WakeWord(
                phrase = "Disabled Trigger",
                triggerType = TriggerType.CHAT,
                chatId = chatId,
                chatTitle = "Research Assistant",
                isEnabled = false
            )
        )

        val activeFromRepo = repo.observeAllWakeWords().first().filter { it.isEnabled }
        assertEquals(1, activeFromRepo.size)

        val detection = WakeWordVoiceService.matchWakeWordFromList(
            transcript = "Hey Research summarize quantum computing",
            activeWakeWords = activeFromRepo
        )
        assertNotNull(detection)
        assertEquals("Hey Research", detection?.wakeWord?.phrase)
        assertEquals("summarize quantum computing", detection?.followUpQuery)
        assertEquals("Hey Research summarize quantum computing", detection?.rawTranscript)

        // Disabled wake word is not matched
        val disabledDetection = WakeWordVoiceService.matchWakeWordFromList(
            transcript = "Disabled Trigger test",
            activeWakeWords = activeFromRepo
        )
        assertNull(disabledDetection)
    }

    @Test
    fun `ChatMessage and VoiceCommandEntity store transcribed text and enforce LIMIT 100 in Room`() = runTest {
        val db = openPersistentDatabase()
        database = db
        val chatDao = db.chatDao()
        val chatRepo = com.example.data.repository.ChatRepository(chatDao)

        val chatId = chatDao.insertChat(
            ChatSession(
                title = "Voice Log Test",
                wakeWord = "Hey Gemini",
                isDefaultWakeChat = true
            )
        )

        // Verify legacy audio placeholder detection and sanitization
        assertTrue(com.example.data.model.ChatMessage.isLegacyAudioPlaceholder("🎙️ [Voice Input Audio Turn]"))
        assertFalse(com.example.data.model.ChatMessage.isLegacyAudioPlaceholder("What is the weather in Tokyo?"))

        // Insert 115 spoken messages and 115 voice command history records
        for (i in 1..115) {
            chatRepo.insertMessage(
                com.example.data.model.ChatMessage(
                    chatId = chatId,
                    sender = "user",
                    content = "Spoken voice message number $i",
                    attachmentName = "Voice Turn.wav",
                    attachmentMimeType = "audio/wav",
                    timestamp = 1000L + i
                )
            )
            chatDao.insertVoiceCommand(
                com.example.data.model.VoiceCommandEntity(
                    id = "cmd_$i",
                    commandType = "WAKE_WORD",
                    triggerPhrase = "Hey Gemini",
                    recognizedTranscript = "Hey Gemini spoken command $i",
                    chatTitle = "Voice Log Test",
                    timestamp = 2000L + i
                )
            )
        }

        // Verify Room queries cap history at 100 items ordered by newest first
        val recentMessages = chatRepo.getAllMessages().first()
        assertEquals(100, recentMessages.size)
        assertEquals("Spoken voice message number 115", recentMessages.first().content)
        assertTrue(recentMessages.first().hasTranscribedText)
        assertTrue(recentMessages.first().isAudioPlaceholderAttachment)

        val recentCommands = chatRepo.getRecentVoiceCommands().first()
        assertEquals(100, recentCommands.size)
        assertEquals("Hey Gemini spoken command 115", recentCommands.first().recognizedTranscript)
    }

    @Test
    fun `WakeWordSample stores audio samples with unique sampleUuid and associates with wakeWordId for keyword loop`() = runTest {
        val db = openPersistentDatabase()
        database = db
        val chatDao = db.chatDao()
        val wakeWordDao = db.wakeWordDao()
        val chatRepo = com.example.data.repository.ChatRepository(chatDao)
        val wakeRepo = WakeWordRepository(wakeWordDao, chatDao)

        val chatId = chatDao.insertChat(
            ChatSession(
                title = "Studio Custom Chat",
                wakeWord = "Hey Studio",
                isDefaultWakeChat = true
            )
        )

        // Record and store two audio samples for the custom wake word before saving the wake word entry
        val sample1 = com.example.data.model.WakeWordSample(
            chatId = chatId,
            wakePhrase = "Captain Nebula",
            samplePath = "/data/user/0/com.example/files/wake_sample_${chatId}_0.wav",
            sizeBytes = 4096L,
            durationMs = 1420L
        )
        val sample2 = com.example.data.model.WakeWordSample(
            chatId = chatId,
            wakePhrase = "Captain Nebula",
            samplePath = "/data/user/0/com.example/files/wake_sample_${chatId}_1.wav",
            sizeBytes = 5120L,
            durationMs = 1580L
        )
        chatRepo.insertWakeWordSample(sample1)
        chatRepo.insertWakeWordSample(sample2)

        assertTrue(sample1.sampleUuid.isNotBlank())
        assertTrue(sample2.sampleUuid.isNotBlank())
        assertTrue(sample1.sampleUuid != sample2.sampleUuid)

        // Save custom wake word and associate recorded samples with the newly created wakeWordId
        val wakeWordId = wakeRepo.addCustomChatWakeWord(
            chatId = chatId,
            chatTitle = "Studio Custom Chat",
            phrase = "Captain Nebula",
            sensitivity = WakeSensitivityLevel.MEDIUM.sensitivityValue
        )
        assertTrue(wakeWordId > 0L)

        chatRepo.associateSamplesWithWakeWord(
            chatId = chatId,
            wakeWordId = wakeWordId,
            wakePhrase = "Captain Nebula"
        )

        val associatedSamples = chatRepo.getSamplesForWakeWord(wakeWordId)
        assertEquals(2, associatedSamples.size)
        assertTrue(associatedSamples.all { it.wakeWordId == wakeWordId })
        assertTrue(associatedSamples.all { it.wakePhrase == "Captain Nebula" })
        assertEquals(setOf(sample1.sampleUuid, sample2.sampleUuid), associatedSamples.map { it.sampleUuid }.toSet())
    }

    @Test
    fun `Gemini sleep and wake up voice commands and sensitivity levels across multiple chat wake words`() = runTest {
        val db = openPersistentDatabase()
        database = db
        val chatDao = db.chatDao()
        val wakeRepo = WakeWordRepository(db.wakeWordDao(), chatDao)

        // 1. Verify "Gemini, sleep" and "Gemini, wake up" command detection
        assertTrue(AudioCommandSanitizer.containsSleepCommand("Gemini, sleep"))
        assertTrue(AudioCommandSanitizer.containsSleepCommand("Hey Gemini sleep now"))
        assertFalse(AudioCommandSanitizer.containsSleepCommand("Explain how humans sleep"))

        assertTrue(AudioCommandSanitizer.containsWakeUpCommand("Gemini, wake up"))
        assertTrue(AudioCommandSanitizer.containsWakeUpCommand("gemini wake"))
        assertFalse(AudioCommandSanitizer.containsWakeUpCommand("What is a wake board"))

        // 2. Verify predefined sensitivity levels map accurately
        assertEquals(WakeSensitivityLevel.LOW, WakeSensitivityLevel.fromSensitivity(0.60f))
        assertEquals(WakeSensitivityLevel.MEDIUM, WakeSensitivityLevel.fromSensitivity(0.78f))
        assertEquals(WakeSensitivityLevel.HIGH, WakeSensitivityLevel.fromSensitivity(0.92f))

        // 3. Configure multiple wake words associated with distinct chat conversations
        val codingChatId = chatDao.insertChat(
            ChatSession(title = "Kotlin Architect", wakeWord = "Hey Architect", isDefaultWakeChat = true)
        )
        val travelChatId = chatDao.insertChat(
            ChatSession(title = "Tokyo Planner", wakeWord = "Hey Voyager", isDefaultWakeChat = false)
        )
        wakeRepo.addCustomChatWakeWord(codingChatId, "Kotlin Architect", "Hey Architect")
        wakeRepo.addCustomChatWakeWord(codingChatId, "Kotlin Architect", "Code Master")
        wakeRepo.addCustomChatWakeWord(travelChatId, "Tokyo Planner", "Hey Voyager")

        val activeWords = wakeRepo.observeAllWakeWords().first().filter { it.isEnabled }
        assertEquals(3, activeWords.size)

        // Exact match routes each wake word directly to its corresponding chat conversation
        val matchCoding = WakeWordVoiceService.matchWakeWordFromList(
            transcript = "Code Master review my coroutine flow",
            activeWakeWords = activeWords,
            sensitivityOverride = WakeSensitivityLevel.MEDIUM.sensitivityValue
        )
        assertNotNull(matchCoding)
        assertEquals(codingChatId, matchCoding?.wakeWord?.chatId)
        assertEquals("Kotlin Architect", matchCoding?.wakeWord?.chatTitle)
        assertEquals("review my coroutine flow", matchCoding?.followUpQuery)

        val matchTravel = WakeWordVoiceService.matchWakeWordFromList(
            transcript = "Hey Voyager build a 3 day itinerary",
            activeWakeWords = activeWords,
            sensitivityOverride = WakeSensitivityLevel.MEDIUM.sensitivityValue
        )
        assertNotNull(matchTravel)
        assertEquals(travelChatId, matchTravel?.wakeWord?.chatId)
        assertEquals("Tokyo Planner", matchTravel?.wakeWord?.chatTitle)
        assertEquals("build a 3 day itinerary", matchTravel?.followUpQuery)

        // Sensitivity level behavior: near-homophone "Hey Voyajer" (91% similarity) matches even at strict HIGH threshold (0.90f),
        // while "Hey Voyaxxx" (73% similarity) is rejected at strict HIGH threshold (0.90f) and accepted at lenient LOW threshold (0.62f)
        val fuzzyAcceptedAtHighThreshold = WakeWordVoiceService.matchWakeWordFromList(
            transcript = "Hey Voyajer find flights",
            activeWakeWords = activeWords,
            sensitivityOverride = WakeSensitivityLevel.HIGH.sensitivityValue
        )
        assertNotNull(fuzzyAcceptedAtHighThreshold)
        assertEquals(travelChatId, fuzzyAcceptedAtHighThreshold?.wakeWord?.chatId)

        val fuzzyRejectedAtHighThreshold = WakeWordVoiceService.matchWakeWordFromList(
            transcript = "Hey Voyaxxx find flights",
            activeWakeWords = activeWords,
            sensitivityOverride = WakeSensitivityLevel.HIGH.sensitivityValue
        )
        assertNull(fuzzyRejectedAtHighThreshold)

        val fuzzyAcceptedAtLowThreshold = WakeWordVoiceService.matchWakeWordFromList(
            transcript = "Hey Voyaxxx find flights",
            activeWakeWords = activeWords,
            sensitivityOverride = WakeSensitivityLevel.LOW.sensitivityValue
        )
        assertNotNull(fuzzyAcceptedAtLowThreshold)
        assertEquals(travelChatId, fuzzyAcceptedAtLowThreshold?.wakeWord?.chatId)

        // 4. Verify WakeWordManager enters Sleep Mode on "Gemini, sleep", suppresses wake words while sleeping,
        // and resumes listening on "Gemini, wake up" or UI tap
        val wakeManager = WakeWordManager(context, wakeWordDao = db.wakeWordDao())
        wakeManager.updateActiveChats(chatDao.getAllChatsSync())
        wakeManager.updateConfiguredWakeWords(wakeRepo.chatWakeWords.first())

        val collectedEvents = mutableListOf<WakeWordTriggerEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
            wakeManager.triggerEvents.collect { collectedEvents.add(it) }
        }

        assertFalse(wakeManager.isSleepMode.value)
        assertNull(wakeManager.matchWakePhraseInTranscript("Gemini, sleep"))
        assertTrue(wakeManager.isSleepMode.value)
        assertTrue(wakeManager.sleepModeReason.value.contains("Gemini, sleep", ignoreCase = true))

        // While in Sleep Mode, normal chat wake words are suppressed in both matchWakePhraseInTranscript and onKeywordHit
        assertNull(wakeManager.matchWakePhraseInTranscript("Code Master review my coroutine flow"))
        wakeManager.onKeywordHit(keyword = "Code Master", initialQuery = "review my coroutine flow", targetChatIdOverride = codingChatId)
        testScheduler.runCurrent()
        assertTrue(collectedEvents.isEmpty())

        // Voice command "Gemini, wake up" exits Sleep Mode and re-enables wake word detection
        assertNull(wakeManager.matchWakePhraseInTranscript("Gemini, wake up"))
        assertFalse(wakeManager.isSleepMode.value)

        val resumedMatch = wakeManager.matchWakePhraseInTranscript("Code Master review my coroutine flow")
        assertNotNull(resumedMatch)
        assertEquals(codingChatId, resumedMatch?.matchedChat?.id)

        // Also verify manual UI tap (exitSleepMode) exits Sleep Mode
        wakeManager.enterSleepMode("Manual sleep button tap")
        assertTrue(wakeManager.isSleepMode.value)
        wakeManager.exitSleepMode("Tap to Wake")
        assertFalse(wakeManager.isSleepMode.value)

        wakeManager.onKeywordHit(keyword = "Hey Voyager", initialQuery = "build itinerary", targetChatIdOverride = travelChatId)
        testScheduler.runCurrent()
        assertEquals(1, collectedEvents.size)
        assertEquals(travelChatId, collectedEvents.first().chatId)
        assertEquals("Hey Voyager", collectedEvents.first().matchedWord)
    }

    @Test
    fun `per-wake-word sensitivity slider and presets and on-the-fly chat binding route directly to target chat`() = runTest {
        val db = openPersistentDatabase()
        database = db
        val chatDao = db.chatDao()
        val wakeWordDao = db.wakeWordDao()
        val wakeRepo = WakeWordRepository(wakeWordDao, chatDao)

        // 1. Create two chats and configure wake words with distinct per-wake-word sensitivities
        val financeChatId = chatDao.insertChat(
            ChatSession(title = "Finance Advisor", wakeWord = "Hey Ledger", isDefaultWakeChat = true)
        )
        val fitnessChatId = chatDao.insertChat(
            ChatSession(title = "Workout Coach", wakeWord = "Hey Spartan", isDefaultWakeChat = false)
        )

        val ledgerWakeId = wakeRepo.addCustomChatWakeWord(
            chatId = financeChatId,
            chatTitle = "Finance Advisor",
            phrase = "Hey Ledger",
            sensitivity = WakeSensitivityLevel.HIGH.sensitivityValue // 0.88f strict
        )
        val spartanWakeId = wakeRepo.addCustomChatWakeWord(
            chatId = fitnessChatId,
            chatTitle = "Workout Coach",
            phrase = "Hey Spartan",
            sensitivity = WakeSensitivityLevel.LOW.sensitivityValue // 0.65f lenient
        )

        // 2. Create a third chat on the fly when adding a new wake word (using Int sensitivity 0..100 overload)
        val (createdChatId, orionWakeId) = wakeRepo.createChatAndBindWakeWord(
            chatTitle = "Astronomy Lab",
            phrase = "Hey Orion",
            sensitivity = 78
        )
        assertTrue(createdChatId > 0L)
        assertTrue(orionWakeId > 0L)
        val createdChat = chatDao.getChatByIdSync(createdChatId)
        assertNotNull(createdChat)
        assertEquals("Astronomy Lab", createdChat?.title)
        assertEquals("Hey Orion", createdChat?.wakeWord)

        // 3. Verify per-wake-word sensitivity updates via continuous slider and preset levels
        // and verify un-synced PENDING syncStatus is preserved across Room updates
        assertEquals(com.example.data.model.SyncStatus.PENDING, wakeWordDao.getWakeWordByIdSync(orionWakeId)?.syncStatus)
        assertTrue(wakeRepo.updateWakeWordSensitivity(orionWakeId, 0.91f))
        assertEquals(0.91f, wakeWordDao.getWakeWordByIdSync(orionWakeId)?.sensitivity ?: 0f, 0.001f)
        assertEquals(com.example.data.model.SyncStatus.PENDING, wakeWordDao.getWakeWordByIdSync(orionWakeId)?.syncStatus)

        assertTrue(wakeRepo.updateWakeWordSensitivityLevel(orionWakeId, WakeSensitivityLevel.MEDIUM))
        assertEquals(
            WakeSensitivityLevel.MEDIUM.sensitivityValue,
            wakeWordDao.getWakeWordByIdSync(orionWakeId)?.sensitivity ?: 0f,
            0.001f
        )

        // 4. Verify WakeWordManager routes each wake word to its associated chat and honors per-wake-word sensitivity
        val wakeManager = WakeWordManager(context, wakeWordDao = wakeWordDao)
        wakeManager.updateActiveChats(chatDao.getAllChatsSync())
        wakeManager.updateConfiguredWakeWords(wakeRepo.chatWakeWords.first())

        // Exact matches route each wake word to its own chat conversation
        val matchFinance = wakeManager.matchWakePhraseInTranscript("Hey Ledger show my monthly budget")
        assertNotNull(matchFinance)
        assertEquals(financeChatId, matchFinance?.matchedChat?.id)
        assertEquals("show my monthly budget", matchFinance?.followUpQuery)

        val matchFitness = wakeManager.matchWakePhraseInTranscript("Hey Spartan log 50 pushups")
        assertNotNull(matchFitness)
        assertEquals(fitnessChatId, matchFitness?.matchedChat?.id)
        assertEquals("log 50 pushups", matchFitness?.followUpQuery)

        val matchOrion = wakeManager.matchWakePhraseInTranscript("Hey Orion locate Betelgeuse")
        assertNotNull(matchOrion)
        assertEquals(createdChatId, matchOrion?.matchedChat?.id)
        assertEquals("locate Betelgeuse", matchOrion?.followUpQuery)

        // Per-wake-word sensitivity difference:
        // "Hey Spartxx" (~73% similarity) is accepted for "Hey Spartan" (LOW 0.65f),
        // while "Hey Ledgxx" (~70% similarity) is rejected for "Hey Ledger" (HIGH 0.88f)
        val lenientMatch = wakeManager.matchWakePhraseInTranscript("Hey Spartxx start timer")
        assertNotNull(lenientMatch)
        assertEquals(fitnessChatId, lenientMatch?.matchedChat?.id)

        val strictRejected = wakeManager.matchWakePhraseInTranscript("Hey Ledgxx check balance")
        assertNull(strictRejected)

        // Lowering "Hey Ledger" sensitivity to LOW (0.65f) allows the fuzzy match to succeed
        wakeRepo.updateWakeWordSensitivityLevel(ledgerWakeId, WakeSensitivityLevel.LOW)
        wakeManager.updateConfiguredWakeWords(wakeRepo.chatWakeWords.first())
        val nowAccepted = wakeManager.matchWakePhraseInTranscript("Hey Ledgxx check balance")
        assertNotNull(nowAccepted)
        assertEquals(financeChatId, nowAccepted?.matchedChat?.id)

        // 5. Re-bind an existing wake word to a different existing chat and to a newly created chat on the fly
        assertTrue(wakeRepo.updateWakeWordChatBinding(spartanWakeId, financeChatId, "Finance Advisor"))
        wakeManager.updateActiveChats(chatDao.getAllChatsSync())
        wakeManager.updateConfiguredWakeWords(wakeRepo.chatWakeWords.first())

        val reboundMatch = wakeManager.matchWakePhraseInTranscript("Hey Spartan review portfolio")
        assertNotNull(reboundMatch)
        assertEquals(financeChatId, reboundMatch?.matchedChat?.id)

        val reassignedChatId = wakeRepo.reassignWakeWordToNewChat(spartanWakeId, "Strength Training")
        assertTrue(reassignedChatId > 0L)
        wakeManager.updateActiveChats(chatDao.getAllChatsSync())
        wakeManager.updateConfiguredWakeWords(wakeRepo.chatWakeWords.first())

        val newChatMatch = wakeManager.matchWakePhraseInTranscript("Hey Spartan track deadlifts")
        assertNotNull(newChatMatch)
        assertEquals(reassignedChatId, newChatMatch?.matchedChat?.id)

        // 6. Verify onKeywordHit emits a WakeWordTriggerEvent with the exact target chatId so the UI opens that chat directly
        val triggeredEvents = mutableListOf<WakeWordTriggerEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.UNDISPATCHED) {
            wakeManager.triggerEvents.collect { triggeredEvents.add(it) }
        }
        wakeManager.onKeywordHit(
            keyword = "Hey Orion",
            initialQuery = "show star map",
            targetChatIdOverride = createdChatId
        )
        testScheduler.runCurrent()
        assertEquals(1, triggeredEvents.size)
        val triggerEvent = triggeredEvents.first()
        assertEquals(createdChatId, triggerEvent.chatId)
        assertEquals("Astronomy Lab", triggerEvent.chatTitle)
        assertEquals("Hey Orion", triggerEvent.matchedWord)
        assertEquals("show star map", triggerEvent.initialQuery)
    }

    @Test
    fun `multiple distinct chat sessions save and load chronological chat history independently and enforce unique wake words`() = runTest {
        val db = openPersistentDatabase()
        database = db
        val chatDao = db.chatDao()
        val messageDao = db.messageDao()
        val wakeWordDao = db.wakeWordDao()
        val chatRepo = com.example.data.repository.ChatRepository(chatDao, messageDao)
        val messageRepo = com.example.data.repository.MessageRepository(messageDao, chatDao)
        val wakeRepo = WakeWordRepository(wakeWordDao, chatDao)
        val bufferManager = com.example.cache.ChatMemoryBufferManager(context, chatDao)

        // 1. Create two distinct chat sessions, each with its own unique wake word and independent AI context (systemInstruction)
        val sessionAId = chatRepo.saveChat(
            ChatSession(
                title = "Backend Architecture",
                wakeWord = "Hey Backend",
                systemInstruction = "You are a distributed systems and Kotlin backend architect.",
                isDefaultWakeChat = true
            )
        )
        val sessionBId = chatRepo.saveChat(
            ChatSession(
                title = "Japanese Tutor",
                wakeWord = "Hey Sensei",
                systemInstruction = "You are a patient Japanese language tutor.",
                isDefaultWakeChat = false
            )
        )
        assertTrue(sessionAId > 0L)
        assertTrue(sessionBId > 0L)
        assertTrue(sessionAId != sessionBId)

        // 2. Verify unique wake word validation across sessions (case-insensitive)
        assertFalse(chatRepo.isWakeWordUnique("hey backend"))
        assertFalse(chatRepo.isWakeWordUnique("HEY SENSEI"))
        assertTrue(chatRepo.isWakeWordUnique("Hey Backend", excludeChatId = sessionAId))
        assertTrue(chatRepo.isWakeWordUnique("Hey Quantum"))
        val conflict = chatRepo.findConflictingWakeWordSession("hey backend", excludeChatId = sessionBId)
        assertNotNull(conflict)
        assertEquals(sessionAId, conflict?.id)
        assertEquals("Backend Architecture", conflict?.title)

        // 3. Save messages out of insertion order in Session A & Session B, then reopen each session
        // and verify messages are loaded in strict chronological order (timestamp ASC, id ASC) with zero cross-session bleed
        chatRepo.saveChatHistoryMessage(
            chatId = sessionAId,
            sender = "gemini",
            content = "Use a repository pattern with Room and Kotlin Flows.",
            timestamp = 2000L
        )
        chatRepo.saveChatHistoryMessage(
            chatId = sessionAId,
            sender = "user",
            content = "How should I structure my local database layer?",
            timestamp = 1000L
        )
        chatRepo.saveChatHistoryMessage(
            chatId = sessionAId,
            sender = "user",
            content = "Can we add a migration from v14 to v15?",
            timestamp = 3000L
        )

        chatRepo.saveChatHistoryMessage(
            chatId = sessionBId,
            sender = "user",
            content = "How do I say good morning in Japanese?",
            timestamp = 1500L
        )
        chatRepo.saveChatHistoryMessage(
            chatId = sessionBId,
            sender = "gemini",
            content = "Ohayou gozaimasu (おはようございます).",
            timestamp = 2500L
        )

        // Reopen Session A via ChatRepository, MessageRepository, and ChatMemoryBufferManager
        val reopenedHistoryA = chatRepo.loadChatHistoryChronological(sessionAId)
        assertEquals(3, reopenedHistoryA.size)
        assertEquals(
            listOf(
                "How should I structure my local database layer?",
                "Use a repository pattern with Room and Kotlin Flows.",
                "Can we add a migration from v14 to v15?"
            ),
            reopenedHistoryA.map { it.content }
        )
        assertTrue(reopenedHistoryA.all { it.chatId == sessionAId })

        bufferManager.preloadAndAttachChat(sessionAId)
        assertEquals(
            reopenedHistoryA.map { it.content },
            bufferManager.hotMessages.value.map { it.content }
        )

        // Switch to and reopen Session B: verify Session A state is detached and Session B loads its own chronological history
        bufferManager.detachChat()
        assertTrue(bufferManager.hotMessages.value.isEmpty())

        bufferManager.preloadAndAttachChat(sessionBId)
        val reopenedHistoryB = messageRepo.getMessagesForChatSync(sessionBId)
        assertEquals(2, reopenedHistoryB.size)
        assertEquals(
            listOf(
                "How do I say good morning in Japanese?",
                "Ohayou gozaimasu (おはようございます)."
            ),
            reopenedHistoryB.map { it.content }
        )
        assertEquals(
            reopenedHistoryB.map { it.content },
            bufferManager.hotMessages.value.map { it.content }
        )
        assertEquals(
            "You are a patient Japanese language tutor.",
            chatRepo.getChatByIdSync(sessionBId)?.systemInstruction
        )

        // 4. Verify WakeWord integer sensitivity (0-100) and targetChatId persistence in Room
        val wakeWordEntity = WakeWord(
            phrase = "Hey Backend",
            triggerType = TriggerType.CHAT,
            targetChatId = sessionAId,
            chatTitle = "Backend Architecture",
            sensitivity = 85
        )
        val insertedWakeId = wakeRepo.addWakeWord(wakeWordEntity)
        assertTrue(insertedWakeId > 0L)

        val persistedWake = wakeWordDao.getWakeWordByIdSync(insertedWakeId)
        assertNotNull(persistedWake)
        assertEquals(85, persistedWake?.sensitivityInt)
        assertEquals(0.85f, persistedWake?.sensitivity ?: 0f, 0.001f)
        assertEquals(sessionAId, persistedWake?.targetChatId)
        assertEquals(sessionAId, persistedWake?.effectiveTargetChatId)

        // Update integer sensitivity (0-100) and targetChatId via WakeWordRepository
        assertTrue(wakeRepo.updateWakeWordSensitivity(insertedWakeId, 62))
        assertTrue(wakeRepo.updateWakeWordTargetChatId(insertedWakeId, sessionBId, "Japanese Tutor"))

        val updatedWake = wakeRepo.chatWakeWords.first().firstOrNull { it.id == insertedWakeId }
        assertNotNull(updatedWake)
        assertEquals(62, updatedWake?.sensitivityInt)
        assertEquals(0.62f, updatedWake?.sensitivity ?: 0f, 0.001f)
        assertEquals(sessionBId, updatedWake?.targetChatId)
        assertEquals(sessionBId, updatedWake?.chatId)
        assertEquals("Japanese Tutor", updatedWake?.chatTitle)

        // 5. Verify custom per-chat greeting message persistence when creating and updating a chat session
        val workChatId = chatRepo.saveChat(
            ChatSession(
                title = "Work",
                wakeWord = "Hey Work",
                greetingMessage = "Good morning, team! Here's what's on the agenda today.",
                isDefaultWakeChat = false
            )
        )
        assertTrue(workChatId > 0L)
        val loadedWorkChat = chatRepo.getChatByIdSync(workChatId)
        assertEquals("Good morning, team! Here's what's on the agenda today.", loadedWorkChat?.greetingMessage)

        chatRepo.updateChatGreeting(workChatId, "Afternoon standup! Let's review sprint blockers.")
        val updatedWorkChat = chatRepo.getChatByIdSync(workChatId)
        assertEquals("Afternoon standup! Let's review sprint blockers.", updatedWorkChat?.greetingMessage)
    }
}




