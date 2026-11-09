package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.ActiveKeyType
import com.example.ai.ApiKeyManager
import com.example.ai.GeminiService
import com.example.bridge.ChromeAppBridge
import com.example.cache.BufferMetrics
import com.example.cache.ChatMemoryBufferManager
import com.example.coupler.ChromeBridgeSocketManager
import com.example.coupler.GeminiCouplerBridge
import com.example.coupler.StagedImageInfo
import com.example.data.db.AppDatabase
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.TriggerType
import com.example.data.model.WakeWord
import com.example.data.model.WakeWordSample
import com.example.data.repository.ChatRepository
import com.example.data.repository.FirebaseSyncRepository
import com.example.data.repository.MessageRepository
import com.example.data.repository.WakeWordRepository
import com.example.importer.ChatImportWorker
import com.example.importer.FileProcessingService
import com.example.importer.ImportProgressState
import com.example.screenshot.ScreenshotWatcher
import com.example.voice.WakeWordManager
import com.example.voice.AudioRecorder
import com.example.voice.SnippetAudioPlayer
import com.example.voice.WakeWordStorageManager
import com.example.ui.theme.AppTheme
import com.example.util.NetworkConnectionState
import com.example.util.NetworkStatusTracker
import com.example.util.ReplyNotificationHelper
import com.example.util.VoiceCommandHistoryEntry
import com.example.util.VoiceCommandType
import com.example.util.WebLinkHistoryEntry
import com.example.util.WebLinkHistoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.example.voice.HandsFreeVoiceState
import com.example.voice.WakeWordManagerState

data class TriggerBanner(
    val chatTitle: String,
    val matchedWord: String,
    val query: String?
)

data class WakeReadyCueState(
    val wakePhrase: String,
    val chatId: Long,
    val chatTitle: String,
    val followUpQuery: String? = null,
    val detectedInBackground: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class AttachmentPayload(
    val uri: Uri,
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
    val sizeBytes: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AttachmentPayload
        if (uri != other.uri) return false
        if (fileName != other.fileName) return false
        if (mimeType != other.mimeType) return false
        if (!bytes.contentEquals(other.bytes)) return false
        return sizeBytes == other.sizeBytes
    }
    override fun hashCode(): Int {
        var result = uri.hashCode()
        result = 31 * result + fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        result = 31 * result + sizeBytes.hashCode()
        return result
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    @Volatile
    var isDatabaseInitialized: Boolean = false
        private set

    @Volatile
    var isGeminiServiceInitialized: Boolean = false
        private set

    @Volatile
    var isFirebaseSyncInitialized: Boolean = false
        private set

    private val database: AppDatabase by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        isDatabaseInitialized = true
        AppDatabase.getDatabase(application, viewModelScope)
    }
    val chatRepository: ChatRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ChatRepository(database.chatDao(), database.messageDao())
    }
    val repository: ChatRepository
        get() = chatRepository
    val messageRepository: MessageRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        MessageRepository(database.messageDao(), database.chatDao())
    }
    val wakeWordRepository: WakeWordRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        WakeWordRepository(database.wakeWordDao(), database.chatDao())
    }
    val adventureGameDao: com.example.game.AdventureGameDao by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        database.adventureGameDao()
    }
    private val _adventureState = MutableStateFlow(com.example.game.AdventureGameState())
    val adventureState: StateFlow<com.example.game.AdventureGameState> = _adventureState.asStateFlow()
    private var adventureSaveSentinelJob: kotlinx.coroutines.Job? = null
    val firebaseSyncRepository: FirebaseSyncRepository? by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        isFirebaseSyncInitialized = true
        try {
            FirebaseSyncRepository(application)
        } catch (_: Throwable) {
            null
        }
    }
    val wakeWordManager: WakeWordManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        WakeWordManager(application)
    }
    val streamingVoicePlayer: com.example.voice.StreamingVoicePlayer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.example.voice.StreamingVoicePlayer(scope = viewModelScope)
    }
    val textToSpeechManager: com.example.voice.TextToSpeechManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.example.voice.TextToSpeechManager(
            context = application,
            scope = viewModelScope,
            onPlaybackStarted = {
                com.example.voice.WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("tts_speaking")
                releaseMicAndAudioFocusForWakeHandoffAndTts("tts_speaking")
            },
            onPlaybackSettledAndReadyToListen = {
                _isWakeHandoffInProgress.value = false
                speechRecognitionCoordinator.setProcessingState(false)
                if (_isHandsFreeActive.value && !_isSleepMode.value && !_isGenerating.value) {
                    com.example.voice.WakeWordVoiceService.resumeAfterInternalDictationInMemory()
                    com.example.voice.WakeWordVoiceService.setIntentionallyPaused(false)
                    if (isAppInForeground) {
                        com.example.voice.WakeWordVoiceService.startServiceSafely(
                            context = application,
                            action = com.example.voice.WakeWordVoiceService.ACTION_START_VOICE_SERVICE
                        )
                    }
                    speechRecognitionCoordinator.setIntentionallyPaused(false)
                    speechRecognitionCoordinator.scheduleDebouncedRestart(
                        delayMs = com.example.voice.SpeechRecognitionCoordinator.DEFAULT_RESTART_DEBOUNCE_MS
                    )
                }
            }
        )
    }
    val isTtsSpeaking: StateFlow<Boolean>
        get() = textToSpeechManager.isSpeaking
    val chromeBridge: ChromeAppBridge by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ChromeAppBridge(application)
    }
    val bufferManager: ChatMemoryBufferManager by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ChatMemoryBufferManager(application, database.chatDao(), database.messageDao())
    }
    val promptRepository: com.example.data.repository.PromptRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.example.data.repository.PromptRepository(application)
    }
    val chunkedImporter: com.example.importer.ChunkedChatImporter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.example.importer.ChunkedChatImporter()
    }
    internal var geminiServiceOverrideForTest: GeminiService? = null
    private val defaultGeminiService: GeminiService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        isGeminiServiceInitialized = true
        GeminiService()
    }
    private val geminiService: GeminiService
        get() = geminiServiceOverrideForTest ?: defaultGeminiService

    private val _currentUserUid = MutableStateFlow<String?>(null)
    val currentUserUid: StateFlow<String?> = _currentUserUid.asStateFlow()

    private val _currentUserEmail = MutableStateFlow<String?>(null)
    val currentUserEmail: StateFlow<String?> = _currentUserEmail.asStateFlow()

    private val _currentUserDisplayName = MutableStateFlow<String?>(null)
    val currentUserDisplayName: StateFlow<String?> = _currentUserDisplayName.asStateFlow()

    private val _cloudSyncStatus = MutableStateFlow("Sign in to enable Cloud Firestore sync")
    val cloudSyncStatus: StateFlow<String> = _cloudSyncStatus.asStateFlow()

    private val _cloudBackupChats = MutableStateFlow<List<com.example.data.firebase.FirestoreChatDoc>>(emptyList())
    val cloudBackupChats: StateFlow<List<com.example.data.firebase.FirestoreChatDoc>> = _cloudBackupChats.asStateFlow()

    private val _cloudBackupMessages = MutableStateFlow<List<com.example.data.firebase.FirestoreMessageDoc>>(emptyList())
    val cloudBackupMessages: StateFlow<List<com.example.data.firebase.FirestoreMessageDoc>> = _cloudBackupMessages.asStateFlow()

    private val _cloudBackupWakeWords = MutableStateFlow<List<com.example.data.firebase.FirestoreWakeWordDoc>>(emptyList())
    val cloudBackupWakeWords: StateFlow<List<com.example.data.firebase.FirestoreWakeWordDoc>> = _cloudBackupWakeWords.asStateFlow()

    private val _isCloudOperationInProgress = MutableStateFlow(false)
    val isCloudOperationInProgress: StateFlow<Boolean> = _isCloudOperationInProgress.asStateFlow()

    private val _cloudVaultStatusMessage = MutableStateFlow<String?>(null)
    val cloudVaultStatusMessage: StateFlow<String?> = _cloudVaultStatusMessage.asStateFlow()

    private var cloudSyncJob: Job? = null

    fun onAuthStateChanged(user: com.google.firebase.auth.FirebaseUser?) {
        val uid = user?.uid
        val previousUid = _currentUserUid.value
        _currentUserUid.value = uid
        _currentUserEmail.value = user?.email
        _currentUserDisplayName.value = user?.displayName

        if (uid.isNullOrBlank()) {
            cloudSyncJob?.cancel()
            cloudSyncJob = null
            _cloudBackupChats.value = emptyList()
            _cloudBackupMessages.value = emptyList()
            _cloudBackupWakeWords.value = emptyList()
            _cloudVaultStatusMessage.value = null
            _cloudSyncStatus.value = "Signed out (Local Room cache active)"
            return
        }

        if (previousUid != uid || cloudSyncJob?.isActive != true) {
            startHybridCloudSync(uid)
        }
    }

    fun triggerManualCloudSync() {
        val uid = _currentUserUid.value ?: return
        startHybridCloudSync(uid)
    }

    private fun startHybridCloudSync(userId: String) {
        val syncRepo = firebaseSyncRepository ?: return
        cloudSyncJob?.cancel()
        cloudSyncJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _cloudSyncStatus.value = "Syncing with Cloud Firestore..."
                // Preserve local Room cache and rebind any unowned or switched-account rows to the active Google account
                chatRepository.rebindLocalRecordsForUser(userId)
                messageRepository.rebindLocalRecordsForUser(userId)
                wakeWordRepository.rebindLocalRecordsForUser(userId)

                // 1. Push active local Room chats, wake words, and messages to Firestore as cloud backups.
                // Note: Local deletions NEVER auto-delete from Firestore; cloud deletion is strictly manual via Cloud Backup Vault.
                val localChats = chatRepository.getPendingSyncChats().ifEmpty { chatRepository.getAllChatsSync() }
                for (chat in localChats) {
                    if (chat.id > 0L && chat.isPersistent && !chat.isDeleted) {
                        runCatching {
                            val docId = syncRepo.upsertChat(userId, chat)
                            chatRepository.markChatSynced(chat.id, docId, userId)
                        }
                    }
                }
                val localWakeWords = wakeWordRepository.getPendingSyncWakeWords().ifEmpty { wakeWordRepository.getAllWakeWordsSync() }
                for (ww in localWakeWords) {
                    if (ww.id > 0L && !ww.isDeleted) {
                        runCatching {
                            val docId = syncRepo.upsertWakeWord(userId, ww)
                            wakeWordRepository.markWakeWordSynced(ww.id, docId, userId)
                        }
                    }
                }
                val localMessages = messageRepository.getPendingSyncMessages().ifEmpty { allMessages.value }
                for (msg in localMessages) {
                    if (msg.id > 0L && msg.content.isNotBlank() && !msg.isDeleted) {
                        runCatching {
                            val docId = syncRepo.upsertMessage(userId, msg)
                            messageRepository.markMessageSynced(msg.id, docId, userId)
                        }
                    }
                }
                _cloudSyncStatus.value = "Synced with Cloud Firestore"

                // 2. Observe remote chat backups for the Cloud Backup & Restore Vault.
                // Do NOT auto-insert un-restored cloud chats into Room so HomeScreen only shows local Room chats.
                launch {
                    runCatching {
                        syncRepo.observeChats(userId).collect { remoteDocs ->
                            _cloudBackupChats.value = remoteDocs
                            _cloudSyncStatus.value = "Synced • ${remoteDocs.size} chat backup${if (remoteDocs.size == 1) "" else "s"} in cloud"
                        }
                    }.onFailure {
                        _cloudSyncStatus.value = "Offline cache active (Cloud sync paused)"
                    }
                }

                // 3. Observe remote wake word backups for the Cloud Backup & Restore Vault.
                launch {
                    runCatching {
                        syncRepo.observeWakeWords(userId).collect { remoteWakeDocs ->
                            _cloudBackupWakeWords.value = remoteWakeDocs
                        }
                    }
                }

                // 4. Observe remote message backups for the Cloud Backup & Restore Vault.
                launch {
                    runCatching {
                        syncRepo.observeMessages(userId).collect { remoteMsgDocs ->
                            _cloudBackupMessages.value = remoteMsgDocs
                        }
                    }
                }
            } catch (_: Exception) {
                _cloudSyncStatus.value = "Offline cache active (Cloud sync paused)"
            }
        }
    }

    /**
     * Explicitly restores a backed-up chat and all its messages/wake words from Firestore
     * into the local Room database, preloads the chat buffer, and opens the restored chat.
     */
    fun seedCloudBackupStateForTest(
        chats: List<com.example.data.firebase.FirestoreChatDoc>,
        messages: List<com.example.data.firebase.FirestoreMessageDoc> = emptyList(),
        wakeWords: List<com.example.data.firebase.FirestoreWakeWordDoc> = emptyList()
    ) {
        _cloudBackupChats.value = chats
        _cloudBackupMessages.value = messages
        _cloudBackupWakeWords.value = wakeWords
    }

    fun restoreChatFromCloud(
        chatDoc: com.example.data.firebase.FirestoreChatDoc,
        onComplete: ((Long) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val restoredChatId = restoreChatFromCloudSuspend(chatDoc)
            onComplete?.invoke(restoredChatId)
        }
    }

    suspend fun restoreChatFromCloudSuspend(
        chatDoc: com.example.data.firebase.FirestoreChatDoc,
        chatRepoOverride: ChatRepository = repository,
        messageRepoOverride: MessageRepository = messageRepository,
        wakeRepoOverride: WakeWordRepository = wakeWordRepository
    ): Long {
        val uid = _currentUserUid.value
        _isCloudOperationInProgress.value = true
        _cloudVaultStatusMessage.value = "Restoring \"${chatDoc.title}\" to device..."
        val restoredChatId = withContext(Dispatchers.IO) {
            try {
                val bundle = if (!uid.isNullOrBlank()) {
                    val syncRepo = firebaseSyncRepository
                    if (syncRepo != null) {
                        runCatching { syncRepo.fetchChatBackupBundle(uid, chatDoc) }.getOrNull()
                    } else {
                        null
                    }
                } else {
                    null
                }
                val effectiveChatDoc = bundle?.chatDoc ?: chatDoc
                val remoteSession = effectiveChatDoc.toChatSession()
                val targetOriginalLocalId = if (effectiveChatDoc.localChatId > 0L) {
                    effectiveChatDoc.localChatId
                } else {
                    remoteSession.id
                }

                // 1. Explicitly upsert/insert the ChatSession into local Room database first
                val upsertedLocalChatId = chatRepoOverride.upsertRemoteChat(remoteSession)
                val finalLocalChatId = if (upsertedLocalChatId > 0L) {
                    upsertedLocalChatId
                } else {
                    chatRepoOverride.saveChat(remoteSession.copy(id = 0L, isDeleted = false))
                }

                // Ensure the chat is marked active (not soft-deleted) in Room
                val verifiedLocalChat = chatRepoOverride.getChatByIdSync(finalLocalChatId)
                if (verifiedLocalChat == null) {
                    chatRepoOverride.saveChat(remoteSession.copy(id = finalLocalChatId, isDeleted = false))
                }

                // 2. Restore all associated messages (from fetched bundle + cached cloud stream) into local Room DB
                val combinedMessages = (
                    bundle?.messages.orEmpty() +
                        _cloudBackupMessages.value.filter { it.localChatId == targetOriginalLocalId || it.localChatId == finalLocalChatId }
                    ).distinctBy { it.id.ifBlank { "${it.localMessageId}_${it.messageTimestamp}" } }
                    .sortedBy { it.messageTimestamp }

                for (msgDoc in combinedMessages) {
                    val chatMsg = msgDoc.toChatMessage().copy(
                        chatId = finalLocalChatId,
                        isDeleted = false
                    )
                    if (chatMsg.content.isNotBlank()) {
                        messageRepoOverride.upsertRemoteMessage(chatMsg)
                    }
                }

                // 3. Restore all associated wake words into local Room DB
                val combinedWakeWords = (
                    bundle?.wakeWords.orEmpty() +
                        _cloudBackupWakeWords.value.filter { it.chatId == targetOriginalLocalId || it.chatId == finalLocalChatId }
                    ).distinctBy { it.id.ifBlank { it.phrase.lowercase() } }

                for (wakeDoc in combinedWakeWords) {
                    val wakeWord = wakeDoc.toWakeWord().copy(
                        chatId = finalLocalChatId,
                        isDeleted = false
                    )
                    if (wakeWord.phrase.isNotBlank()) {
                        wakeRepoOverride.upsertRemoteWakeWord(wakeWord)
                    }
                }

                // Ensure primary wake word from chat session is also synced locally
                wakeRepoOverride.syncChatWakeWordFromSession(
                    chatId = finalLocalChatId,
                    chatTitle = remoteSession.title,
                    wakeWord = remoteSession.wakeWord,
                    sensitivity = _wakeWordSensitivity.value
                )

                if (chatRepoOverride === repository) {
                    bufferManager.preloadAndAttachChat(finalLocalChatId)
                }
                finalLocalChatId
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to restore chat from cloud: ${e.message}", e)
                -1L
            }
        }
        _isCloudOperationInProgress.value = false
        if (restoredChatId > 0L) {
            _cloudVaultStatusMessage.value = "Restored \"${chatDoc.title}\" to device"
            _currentChatId.value = restoredChatId
            if (chatRepoOverride === repository) {
                selectChat(restoredChatId)
            }
        } else {
            _cloudVaultStatusMessage.value = "Failed to restore \"${chatDoc.title}\""
        }
        return restoredChatId
    }

    /**
     * Permanently deletes a backed-up chat, its messages, and its wake words from Cloud Firestore
     * when the user confirms "Delete from Cloud" in the Cloud Backup Vault.
     */
    fun deleteChatFromCloud(
        chatDoc: com.example.data.firebase.FirestoreChatDoc,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val succeeded = deleteChatFromCloudSuspend(chatDoc)
            onComplete?.invoke(succeeded)
        }
    }

    suspend fun deleteChatFromCloudSuspend(
        chatDoc: com.example.data.firebase.FirestoreChatDoc
    ): Boolean {
        val uid = _currentUserUid.value
        _isCloudOperationInProgress.value = true
        _cloudVaultStatusMessage.value = "Deleting \"${chatDoc.title}\" from Cloud Firestore..."
        val targetLocalId = if (chatDoc.localChatId > 0L) chatDoc.localChatId else chatDoc.toChatSession().id
        val cachedMsgs = _cloudBackupMessages.value.filter { it.localChatId == targetLocalId }
        val cachedWakes = _cloudBackupWakeWords.value.filter { it.chatId == targetLocalId }

        val succeeded = withContext(Dispatchers.IO) {
            try {
                if (!uid.isNullOrBlank()) {
                    val syncRepo = firebaseSyncRepository
                    if (syncRepo != null) {
                        syncRepo.deleteChatBackupFromCloud(
                            userId = uid,
                            chatDoc = chatDoc,
                            cachedMessagesForChat = cachedMsgs,
                            cachedWakeWordsForChat = cachedWakes
                        )
                    }
                }
                true
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to delete chat from cloud: ${e.message}", e)
                false
            }
        }

        // Immediately remove from in-memory cloud backup vault state
        _cloudBackupChats.update { list ->
            list.filterNot {
                (chatDoc.id.isNotBlank() && it.id == chatDoc.id) ||
                    (targetLocalId > 0L && it.localChatId == targetLocalId)
            }
        }
        _cloudBackupMessages.update { list ->
            list.filterNot { it.localChatId == targetLocalId }
        }
        _cloudBackupWakeWords.update { list ->
            list.filterNot { it.chatId == targetLocalId }
        }

        _isCloudOperationInProgress.value = false
        _cloudVaultStatusMessage.value = if (succeeded) {
            "Deleted \"${chatDoc.title}\" from Cloud Firestore"
        } else {
            "Removed \"${chatDoc.title}\" from Cloud Backup view"
        }
        return succeeded
    }

    private fun syncChatToCloudIfSignedIn(chatId: Long) {
        val uid = _currentUserUid.value ?: return
        val syncRepo = firebaseSyncRepository ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val chat = repository.getChatByIdSync(chatId) ?: return@launch
            if (chat.isPersistent) {
                runCatching {
                    val docId = syncRepo.upsertChat(uid, chat)
                    repository.markChatSynced(chat.id, docId, uid)
                }
            }
            val wakeWords = wakeWordRepository.getChatWakeWordsForChatSync(chatId)
            for (ww in wakeWords) {
                runCatching {
                    val docId = syncRepo.upsertWakeWord(uid, ww)
                    wakeWordRepository.markWakeWordSynced(ww.id, docId, uid)
                }
            }
        }
    }

    private fun syncMessageToCloudIfSignedIn(message: ChatMessage) {
        val uid = _currentUserUid.value ?: return
        val syncRepo = firebaseSyncRepository ?: return
        if (message.id <= 0L || message.content.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val docId = syncRepo.upsertMessage(uid, message)
                messageRepository.markMessageSynced(message.id, docId, uid)
            }
        }
    }

    private fun syncWakeWordToCloudIfSignedIn(wakeWordId: Long) {
        val uid = _currentUserUid.value ?: return
        val syncRepo = firebaseSyncRepository ?: return
        if (wakeWordId <= 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            val wakeWord = wakeWordRepository.getAllWakeWordsSync().firstOrNull { it.id == wakeWordId } ?: return@launch
            runCatching {
                val docId = syncRepo.upsertWakeWord(uid, wakeWord)
                wakeWordRepository.markWakeWordSynced(wakeWord.id, docId, uid)
            }
        }
    }

    private val appPrefs = application.getSharedPreferences("gemini_wake_settings", android.content.Context.MODE_PRIVATE)

    private val initialVoiceMode = try {
        val saved = appPrefs.getString("voice_engine_mode", com.example.voice.VoiceEngineMode.GEMINI_ORION.name)
        com.example.voice.VoiceEngineMode.valueOf(saved ?: com.example.voice.VoiceEngineMode.GEMINI_ORION.name)
    } catch (_: Exception) {
        com.example.voice.VoiceEngineMode.GEMINI_ORION
    }
    private val _voiceEngineMode = MutableStateFlow(initialVoiceMode)
    val voiceEngineMode: StateFlow<com.example.voice.VoiceEngineMode> = _voiceEngineMode.asStateFlow()
    val isOrionVoicePlaying: StateFlow<Boolean>
        get() = streamingVoicePlayer.isPlaying

    private val initialSelectedVoice = appPrefs.getString("selected_voice", "Fenrir") ?: "Fenrir"
    private val _selectedVoice = MutableStateFlow(initialSelectedVoice)
    val selectedVoice: StateFlow<String> = _selectedVoice.asStateFlow()

    private val initialAppTheme = try {
        val saved = appPrefs.getString("app_theme", AppTheme.SYSTEM.name)
        AppTheme.valueOf(saved ?: AppTheme.SYSTEM.name)
    } catch (_: Exception) {
        AppTheme.SYSTEM
    }
    private val _appTheme = MutableStateFlow(initialAppTheme)
    val appTheme: StateFlow<AppTheme> = _appTheme.asStateFlow()

    fun setAppTheme(theme: AppTheme) {
        _appTheme.value = theme
        appPrefs.edit().putString("app_theme", theme.name).apply()
    }

    fun selectVoice(voice: String) {
        _selectedVoice.value = voice
        appPrefs.edit().putString("selected_voice", voice).apply()
    }

    fun testVoice(voice: String) {
        // StreamingVoicePlayer strictly plays Gemini model responses only; do not synthesize local status/test audio.
        streamingVoicePlayer.stop()
    }

    private val _defaultPersistenceEnabled = MutableStateFlow(appPrefs.getBoolean("default_persistence", true))
    val defaultPersistenceEnabled: StateFlow<Boolean> = _defaultPersistenceEnabled.asStateFlow()

    private val _preserveCacheAcrossSessions = MutableStateFlow(appPrefs.getBoolean("preserve_cache_across_sessions", true))
    val preserveCacheAcrossSessions: StateFlow<Boolean> = _preserveCacheAcrossSessions.asStateFlow()

    private val _heartbeatPingOnlyMode = MutableStateFlow(appPrefs.getBoolean("heartbeat_ping_only_mode", true))
    val heartbeatPingOnlyMode: StateFlow<Boolean> = _heartbeatPingOnlyMode.asStateFlow()

    // Hands-Free Voice Dictation & Custom Trigger Words
    private val _dictateTrigger = MutableStateFlow(
        appPrefs.getString("dictate_trigger", com.example.voice.AudioCommandSanitizer.DEFAULT_DICTATE_TRIGGER)
            ?: com.example.voice.AudioCommandSanitizer.DEFAULT_DICTATE_TRIGGER
    )
    val dictateTrigger: StateFlow<String> = _dictateTrigger.asStateFlow()

    private val _sendTrigger = MutableStateFlow(
        appPrefs.getString("send_trigger", com.example.voice.AudioCommandSanitizer.DEFAULT_SEND_TRIGGER)
            ?: com.example.voice.AudioCommandSanitizer.DEFAULT_SEND_TRIGGER
    )
    val sendTrigger: StateFlow<String> = _sendTrigger.asStateFlow()

    private val _heartbeatTrigger = MutableStateFlow(
        appPrefs.getString("heartbeat_trigger", com.example.voice.AudioCommandSanitizer.DEFAULT_HEARTBEAT_TRIGGER)
            ?: com.example.voice.AudioCommandSanitizer.DEFAULT_HEARTBEAT_TRIGGER
    )
    val heartbeatTrigger: StateFlow<String> = _heartbeatTrigger.asStateFlow()

    private val _isHandsFreeActive = MutableStateFlow(appPrefs.getBoolean("hands_free_active", false))
    val isHandsFreeActive: StateFlow<Boolean> = _isHandsFreeActive.asStateFlow()

    val handsFreeVoiceState: StateFlow<com.example.voice.HandsFreeVoiceState> = wakeWordManager.handsFreeState
    val wakeWordLogs: StateFlow<List<com.example.voice.WakeWordLogEntry>> = wakeWordManager.wakeWordLogs
    val isTextInputFocused: StateFlow<Boolean> = wakeWordManager.isTextInputFocused
    val hasExclusiveAudioFocus: StateFlow<Boolean> = wakeWordManager.hasAudioFocus
    val isUserVoiceTurn: StateFlow<Boolean> = wakeWordManager.isUserVoiceTurn

    private val _isInChatDictationActive = MutableStateFlow(false)
    val isInChatDictationActive: StateFlow<Boolean> = _isInChatDictationActive.asStateFlow()

    val isChatTurnRecording: StateFlow<Boolean> = combine(
        wakeWordManager.isListening,
        wakeWordManager.engineState,
        wakeWordManager.handsFreeState,
        wakeWordManager.isUserVoiceTurn,
        _isInChatDictationActive
    ) { listening, engineState, hfState, userVoiceTurn, inChatDictating ->
        inChatDictating ||
            (listening && (engineState == WakeWordManagerState.ACTIVE_STREAMING || hfState == HandsFreeVoiceState.TRANSCRIBING)) ||
            (listening && userVoiceTurn)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = false
    )

    // Microphone Permission Verification & SpeechRecognizer Continuous Wake-Word Engine State
    private var permissionGrantedOverrideForTest: Boolean? = null

    private val _hasRecordAudioPermission = MutableStateFlow(
        androidx.core.content.ContextCompat.checkSelfPermission(
            application,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    )
    val hasRecordAudioPermission: StateFlow<Boolean> = _hasRecordAudioPermission.asStateFlow()

    private val _requiresAudioPermission = MutableStateFlow(false)
    val requiresAudioPermission: StateFlow<Boolean> = _requiresAudioPermission.asStateFlow()

    private var pendingWakeStartAfterPermission: Boolean = false
    private var pendingInChatDictationAfterPermission: Boolean = false
    private var lastPartialWakeMatchMs: Long = 0L

    private val _wakeRetriggerEventId = MutableStateFlow(0L)
    val wakeRetriggerEventId: StateFlow<Long> = _wakeRetriggerEventId.asStateFlow()

    private val _lastDispatchedVoicePrompt = MutableStateFlow<String?>(null)
    val lastDispatchedVoicePrompt: StateFlow<String?> = _lastDispatchedVoicePrompt.asStateFlow()

    // Pending wake-word trailing inquiry to auto-dispatch once when navigating into ChatDetailScreen
    private val _pendingWakeQuery = MutableStateFlow<String?>(null)
    val pendingWakeQuery: StateFlow<String?> = _pendingWakeQuery.asStateFlow()

    private val _hasWindowFocus = MutableStateFlow(true)
    val hasWindowFocus: StateFlow<Boolean> = _hasWindowFocus.asStateFlow()

    /**
     * Sanitizes a voice or wake query payload: discards `null`, empty, blank, legacy placeholder,
     * pure wake-word trigger, or any query shorter than 2 characters (`length < 2`).
     */
    fun sanitizeValidWakeQuery(query: String?): String? {
        val cleaned = query?.trim()
            ?.takeIf {
                it.length >= 2 &&
                    it.isNotBlank() &&
                    !ChatMessage.isLegacyAudioPlaceholder(it) &&
                    !com.example.voice.AudioCommandSanitizer.isPureWakeWordTrigger(it)
            }
        return cleaned
    }

    fun setPendingWakeQuery(query: String?) {
        val cleaned = sanitizeValidWakeQuery(query)
        _pendingWakeQuery.value = cleaned
        _currentDraftText.value = ""
    }

    fun consumePendingWakeQuery(): String? {
        val current = _pendingWakeQuery.value
        _pendingWakeQuery.value = null
        _currentDraftText.value = ""
        return sanitizeValidWakeQuery(current)
    }

    /**
     * Immediately clears [_pendingWakeQuery] and any unsolicited wake conversation summary
     * when entering ChatScreen so it cannot auto-submit or generate unsolicited summary overlays.
     */
    fun onEnterChatScreen(chatId: Long? = _currentChatId.value) {
        _pendingWakeQuery.value = null
        _currentDraftText.value = ""
        _wakeConversationSummary.value = null
        if (chatId != null && chatId > 0L) {
            loadAdventureStateForChat(chatId)
        }
    }

    internal fun setPermissionGrantedOverrideForTest(granted: Boolean?) {
        permissionGrantedOverrideForTest = granted
        if (granted != null) {
            _hasRecordAudioPermission.value = granted
        }
    }

    /**
     * Checks whether Manifest.permission.RECORD_AUDIO is currently granted.
     */
    fun checkRecordAudioPermission(): Boolean {
        val override = permissionGrantedOverrideForTest
        val granted = override ?: (
            androidx.core.content.ContextCompat.checkSelfPermission(
                getApplication(),
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
        _hasRecordAudioPermission.value = granted
        return granted
    }

    /**
     * Verifies RECORD_AUDIO permission before starting continuous wake-word listening or voice capture.
     * Emits requiresAudioPermission = true if permission must be requested from the UI.
     */
    fun ensureMicrophonePermission(startWakeListeningWhenGranted: Boolean = true): Boolean {
        return if (checkRecordAudioPermission()) {
            _requiresAudioPermission.value = false
            true
        } else {
            pendingWakeStartAfterPermission = startWakeListeningWhenGranted
            _requiresAudioPermission.value = true
            wakeWordManager.setStatusMessage("Microphone permission required for wake-word listening")
            false
        }
    }

    fun consumeAudioPermissionRequest() {
        _requiresAudioPermission.value = false
    }

    /**
     * Handles the runtime RECORD_AUDIO permission result from the Activity/Compose launcher.
     * Automatically invokes `speechRecognitionCoordinator.startListening()` when granted so
     * the user never needs to tap the radar toggle or mic button a second time.
     */
    fun onAudioPermissionResult(granted: Boolean) {
        if (permissionGrantedOverrideForTest != null) {
            permissionGrantedOverrideForTest = granted
        }
        _hasRecordAudioPermission.value = granted
        _requiresAudioPermission.value = false
        if (granted) {
            // Returning from the system permission dialog restores foreground window focus
            _hasWindowFocus.value = true
            speechRecognitionCoordinator.setWindowFocused(true)
            wakeWordManager.setWindowFocused(true)

            val shouldStartInChat = pendingInChatDictationAfterPermission
            pendingInChatDictationAfterPermission = false
            val shouldStartWake = pendingWakeStartAfterPermission || _isHandsFreeActive.value
            pendingWakeStartAfterPermission = false

            if (shouldStartInChat) {
                if (!_isInChatDictationActive.value) {
                    toggleInChatDictation()
                } else {
                    speechRecognitionCoordinator.startListening(userInitiated = true)
                }
            } else if (shouldStartWake) {
                audioRecorder.setIntentionallyPaused(false)
                speechRecognitionCoordinator.setIntentionallyPaused(false)
                com.example.voice.WakeWordVoiceService.resumeAfterInternalDictationInMemory()
                com.example.voice.WakeWordVoiceService.setIntentionallyPaused(false)
                if (_isHandsFreeActive.value && !_isSleepMode.value) {
                    com.example.voice.WakeWordVoiceService.startServiceSafely(
                        context = getApplication(),
                        action = com.example.voice.WakeWordVoiceService.ACTION_START_VOICE_SERVICE
                    )
                }
                speechRecognitionCoordinator.startListening(userInitiated = true)
                startContinuousWakeWordListening(userInitiated = true)
            }
        } else {
            pendingWakeStartAfterPermission = false
            pendingInChatDictationAfterPermission = false
            stopContinuousWakeWordListening()
            wakeWordManager.setStatusMessage("Microphone permission denied — wake-word listening disabled")
        }
    }

    /**
     * Builds the RecognizerIntent for continuous wake-word and hands-free speech recognition.
     * Uses explicit 3000ms/3000ms/5000ms timeout overrides and LANGUAGE_MODEL_FREE_FORM to prevent mid-sentence cutoff.
     */
    fun createWakeRecognizerIntent(): android.content.Intent {
        val intent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        return WakeWordManager.configureRecognizerIntent(
            intent = intent,
            callingPackage = getApplication<Application>().packageName
        )
    }

    /**
     * Dedicated coordinator managing SpeechRecognizer lifecycle, >=1500ms debounced restarts,
     * idle timeout cutoff on ERROR_NO_MATCH (7) / ERROR_SPEECH_TIMEOUT (6),
     * isIntentionallyPaused / sleep / processing guards, and temporary AudioManager.STREAM_SYSTEM muting.
     */
    val speechRecognitionCoordinator: com.example.voice.SpeechRecognitionCoordinator = com.example.voice.SpeechRecognitionCoordinator(
        context = application,
        scope = viewModelScope,
        hasRecordAudioPermission = { checkRecordAudioPermission() },
        hasActiveWindowFocus = { _hasWindowFocus.value },
        isContinuousModeEnabled = { _isHandsFreeActive.value },
        isExternalGuardBlocked = {
            _isGenerating.value ||
                isOrionVoicePlaying.value ||
                textToSpeechManager.isSpeaking.value ||
                textToSpeechManager.hasPendingSettlingDelay
        },
        onPrepareHardwareBeforeStart = {
            wakeWordManager.isExternalSpeechRecognizerActive = true
            releaseAllNativeAudioRecordTracksForSpeechRecognizer()
        },
        onReadyStateChanged = { listening, status ->
            wakeWordManager.updateExternalListeningState(
                listening = listening,
                status = status
            )
        },
        onStatusMessage = { msg ->
            wakeWordManager.setStatusMessage(msg)
        },
        onRmsChangedCallback = { normalizedRms ->
            wakeWordManager.updateExternalRmsDb(normalizedRms)
        },
        onAudioBufferReceived = { buffer ->
            wakeWordManager.appendPreRollPcm(buffer, buffer.size)
        },
        onPartialTranscripts = { matches ->
            processSpeechRecognizerResults(matches, isFinal = false)
        },
        onFinalTranscripts = { matches ->
            _currentDraftText.value = ""
            val handled = processSpeechRecognizerResults(matches, isFinal = true)
            _currentDraftText.value = ""
            handled
        },
        onInsufficientPermissions = {
            _hasRecordAudioPermission.value = false
            _requiresAudioPermission.value = true
            _isInChatDictationActive.value = false
            speechRecognitionCoordinator.setInChatDictationMode(false)
        },
        onSilenceTimeoutDuringVoiceTurn = {
            if (_isInChatDictationActive.value) {
                _isInChatDictationActive.value = false
                speechRecognitionCoordinator.setInChatDictationMode(false)
            }
            if (isUserVoiceTurn.value) {
                wakeWordManager.setUserVoiceTurn(false)
                if (_isHandsFreeActive.value) {
                    wakeWordManager.transitionToPassiveListening()
                }
            }
        },
        onIdleThresholdReached = {
            wakeWordManager.isExternalSpeechRecognizerActive = false
            wakeWordManager.updateExternalListeningState(
                listening = false,
                status = "Standby (paused after silence timeout)"
            )
            if (_isHandsFreeActive.value && !_isSleepMode.value) {
                wakeWordManager.startContinuousListening(forceActiveTurn = false)
            }
        },
        onFallbackToPcmRequested = {
            wakeWordManager.isExternalSpeechRecognizerActive = false
            wakeWordManager.startContinuousListening(forceActiveTurn = isUserVoiceTurn.value)
        },
        onBargeInSpeechDetected = {
            if (textToSpeechManager.isSpeaking.value) {
                textToSpeechManager.bargeInAndStop()
            }
            if (streamingVoicePlayer.isPlaying.value) {
                streamingVoicePlayer.stop()
            }
        },
        activeWakeWordsProvider = {
            (
                allChats.value.mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } } +
                    wakeWordManager.getActiveChats().mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } } +
                    allWakeWords.value.filter { it.isEnabled }.map { it.phrase }
                ).distinct()
        },
        onOneBreathWakeQueryFinalized = { matchedWake, strippedPrompt ->
            handleOneBreathWakeQueryDispatch(matchedWake, strippedPrompt)
        },
        onDiagnosticTrace = { stage, details ->
            wakeWordManager.appendDiagnosticTraceLog(stage, details)
        }
    )

    private val _selectedCategoryFilter = MutableStateFlow<String?>(null)
    val selectedCategoryFilter: StateFlow<String?> = _selectedCategoryFilter.asStateFlow()

    fun setSelectedCategoryFilter(category: String?) {
        _selectedCategoryFilter.value = category
    }

    val voiceCommandDispatcher = com.example.voice.VoiceCommandDispatcher(speechRecognitionCoordinator)

    private val voiceCommandCallbacks = object : com.example.voice.VoiceCommandDispatcher.Callbacks {
        override fun onNavigateToScreen(
            destination: com.example.voice.VoiceScreenDestination,
            rawTranscript: String
        ): Boolean {
            dispatchVoiceNavigationCommand(
                route = destination.route,
                rawTranscript = rawTranscript
            )
            return true
        }

        override fun onSelectCategoryFilter(
            category: String?,
            displayLabel: String,
            rawTranscript: String
        ): Boolean {
            _selectedCategoryFilter.value = category
            _currentDraftText.value = ""
            _pendingWakeQuery.value = null
            wakeWordManager.setUserVoiceTurn(false)
            webLinkHistoryManager.recordVoiceCommand(
                commandType = VoiceCommandType.WAKE_WORD,
                triggerPhrase = "Category Filter ($displayLabel)",
                recognizedTranscript = rawTranscript.ifBlank { "Filter by $displayLabel" },
                chatTitle = currentChat.value?.title ?: "Home"
            )
            wakeWordManager.setStatusMessage("Category filter: $displayLabel")
            _pendingVoiceNavigationRoute.value = com.example.voice.VoiceScreenDestination.HOME.route
            return true
        }

        override fun onClearChatHistory(targetChatId: Long?): Boolean {
            val chatId = targetChatId ?: _currentChatId.value ?: allChats.value.firstOrNull()?.id ?: return false
            clearChatHistory(chatId)
            wakeWordManager.setStatusMessage("Cleared chat history via voice command")
            return true
        }

        override fun onReadLastReply(targetChatId: Long?): Boolean {
            val lastAiReply = rawCurrentMessages.value
                .lastOrNull { it.sender != "user" && it.content.isNotBlank() }
                ?.content
            if (!lastAiReply.isNullOrBlank()) {
                speakMessage(lastAiReply)
                wakeWordManager.setStatusMessage("Reading last reply aloud")
            }
            return true
        }

        override fun onSetChatPinned(targetChatId: Long?, pinned: Boolean): Boolean {
            val chatId = targetChatId ?: _currentChatId.value ?: allChats.value.firstOrNull()?.id ?: return false
            if (pinned) {
                if (!pinnedChatIds.value.contains(chatId)) {
                    togglePinnedChat(chatId)
                }
                wakeWordManager.setStatusMessage("Pinned chat to top of list")
            } else {
                if (pinnedChatIds.value.contains(chatId)) {
                    togglePinnedChat(chatId)
                }
                wakeWordManager.setStatusMessage("Unpinned chat")
            }
            return true
        }

        override fun onSetThemeMode(theme: AppTheme, label: String): Boolean {
            val chatId = _currentChatId.value ?: allChats.value.firstOrNull()?.id ?: return false
            updateChatTheme(chatId, theme)
            wakeWordManager.setStatusMessage("Switched chat to $label theme")
            return true
        }

        override fun onSetThemePalette(
            paletteId: String,
            backgroundStyle: String,
            primaryHex: String,
            label: String
        ): Boolean {
            val chatId = _currentChatId.value ?: allChats.value.firstOrNull()?.id ?: return false
            when (paletteId.lowercase(java.util.Locale.US)) {
                "blue" -> {
                    updateChatAppearance(chatId, "OCEAN_CYAN", "DEFAULT", "#38BDF8")
                    wakeWordManager.setStatusMessage("Applied Ocean Cyan palette")
                }
                "green" -> {
                    updateChatAppearance(chatId, "EMERALD_FOREST", "AURORA_MESH", "#10B981")
                    wakeWordManager.setStatusMessage("Applied Emerald Forest palette")
                }
                "orange" -> {
                    updateChatAppearance(chatId, "SUNSET_AMBER", "WARM_DUSK", "#F59E0B")
                    wakeWordManager.setStatusMessage("Applied Sunset Amber palette")
                }
                "purple" -> {
                    updateChatAppearance(chatId, "ROYAL_VIOLET", "NEBULA_GLOW", "#A855F7")
                    wakeWordManager.setStatusMessage("Applied Royal Violet & Nebula Glow theme")
                }
                "pink" -> {
                    updateChatAppearance(chatId, "ROSE_QUARTZ", "WARM_DUSK", "#F43F5E")
                    wakeWordManager.setStatusMessage("Applied Rose Quartz palette")
                }
                else -> {
                    updateChatAppearance(chatId, paletteId, backgroundStyle, primaryHex)
                    wakeWordManager.setStatusMessage("Applied $label theme")
                }
            }
            return true
        }

        override fun onOpenExternalUrl(urlOrQuery: String, rawTranscript: String): Boolean {
            return openExternalUrlWithTransientGuard(
                context = getApplication(),
                rawUrlOrQuery = urlOrQuery
            )
        }

        override fun onOpenAdventureDrawer(tab: com.example.game.AdventureDrawerTab): Boolean {
            openAdventureDrawer(tab)
            wakeWordManager.setStatusMessage("Opened Adventure ${tab.title}")
            return true
        }

        override fun onSaveAdventureGame(): Boolean {
            saveAdventureGameManually()
            wakeWordManager.setStatusMessage("Adventure game saved locally & synced")
            return true
        }

        override fun onCloseAdventureDrawer(): Boolean {
            closeAdventureDrawer()
            wakeWordManager.setStatusMessage("Closed Adventure Drawer")
            return true
        }

        override fun onCommandCompletedAndRearm(
            action: com.example.voice.VoiceCommandAction,
            reason: String
        ) {
            completeVoiceCommandAndRearm(reason)
        }
    }

    /**
     * Passively stages chat data, messages, and optional URL payload in both
     * [LocalSyncRelayServer] (`127.0.0.1:8765`) and [ChromeBridgeSocketManager] without
     * ever launching an external browser activity.
     */
    fun stageChatAndUrlPassively(
        chat: ChatSession? = currentChat.value ?: allChats.value.firstOrNull { it.id == _currentChatId.value },
        messages: List<ChatMessage> = rawCurrentMessages.value,
        stagedUrl: String? = null
    ) {
        localSyncServer.stageChatAndUrlPayload(
            chat = chat,
            messages = messages,
            stagedUrl = stagedUrl
        )
        if (chat != null) {
            chromeBridge.stageChatForChrome(
                chat = chat,
                messages = messages,
                stagedUrl = stagedUrl
            )
        } else {
            ChromeBridgeSocketManager.stageChatAndUrlPayload(
                context = getApplication(),
                chatSession = null,
                messages = messages,
                urlPayload = stagedUrl
            )
        }
    }

    /**
     * Passively stages a URL or search query in [WebLinkHistoryManager], [LocalSyncRelayServer],
     * and [ChromeBridgeSocketManager] without launching any external browser activity, keeping
     * Hands-Free Radar active in the foreground.
     */
    fun openExternalUrlWithTransientGuard(
        context: android.content.Context = getApplication(),
        rawUrlOrQuery: String
    ): Boolean {
        val trimmed = rawUrlOrQuery.trim()
        if (trimmed.isBlank()) return false
        speechRecognitionCoordinator.pauseForCommandDispatch("passive_url_stage")
        return try {
            val entry = submitWebSearchOrUrl(trimmed)
            val staged = com.example.util.WebLinkHistoryManager.openUrlInDefaultBrowser(
                context = context,
                rawUrlOrQuery = trimmed
            )
            stageChatAndUrlPassively(stagedUrl = entry?.url ?: com.example.util.WebLinkHistoryManager.normalizeInputToUrl(trimmed))
            staged
        } finally {
            completeVoiceCommandAndRearm("passive_url_stage_completed")
        }
    }

    /**
     * Resets `isProcessingCommand = false` and re-arms continuous hands-free listening without
     * requiring a manual radar toggle.
     */
    fun completeVoiceCommandAndRearm(reason: String = "voice_command_completed") {
        wakeWordManager.setUserVoiceTurn(false)
        com.example.voice.WakeWordVoiceService.resumeMicrophoneAfterCommandNavigation(getApplication())
        speechRecognitionCoordinator.completeCommandProcessingAndRearm(
            delayMs = com.example.voice.SpeechRecognitionCoordinator.AUDIOFOCUS_GAIN_REARM_DEBOUNCE_MS,
            reason = reason
        )
    }

    private var lastDirectVoiceDispatchPrompt: String = ""
    private var lastDirectVoiceDispatchAtMs: Long = 0L
    private var lastSentWakeMessagePrompt: String = ""
    private var lastSentWakeMessageChatId: Long = 0L
    private var lastSentWakeMessageAtMs: Long = 0L

    private fun handleOneBreathWakeQueryDispatch(matchedWake: String, strippedPrompt: String): Boolean {
        val cleanPrompt = sanitizeValidWakeQuery(strippedPrompt)
        if (cleanPrompt == null || _isGenerating.value) {
            _pendingWakeQuery.value = null
            _currentDraftText.value = ""
            return false
        }
        val now = System.currentTimeMillis()
        if (now - lastPartialWakeMatchMs < 800L && _lastDispatchedVoicePrompt.value == cleanPrompt) {
            return false
        }
        lastPartialWakeMatchMs = now
        _isWakeHandoffInProgress.value = true
        com.example.voice.WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("wake_word_detected")
        com.example.voice.AudioCommandSanitizer.playWakeWordHaptic(getApplication(), matchedWake)
        stopSpeechRecognizerInternal(markIntentionallyPaused = false)
        audioRecorder.stopAndReleaseHardware()
        wakeWordManager.stopAndReleaseAudioRecordSynchronously()
        wakeWordManager.abandonExclusiveAudioFocus()
        _lastDispatchedVoicePrompt.value = cleanPrompt
        _pendingWakeQuery.value = cleanPrompt
        // Do NOT assign recognized speech to _currentDraftText—keep draft state and bottom input field blank.
        _currentDraftText.value = ""

        val activeWakeWords = (
            allChats.value.mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } } +
                wakeWordManager.getActiveChats().mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } } +
                allWakeWords.value.filter { it.isEnabled }.map { it.phrase }
            ).distinct()
        val parsedCommand = voiceCommandDispatcher.parseCommand(
            rawTranscript = cleanPrompt,
            customWakeWords = activeWakeWords,
            availableCategories = availableCategories.value
        )
        if (parsedCommand is com.example.voice.VoiceCommandAction.NavigateToScreen ||
            parsedCommand is com.example.voice.VoiceCommandAction.SelectCategoryFilter ||
            parsedCommand is com.example.voice.VoiceCommandAction.OpenExternalUrl
        ) {
            _pendingWakeQuery.value = null
            lastDirectVoiceDispatchPrompt = cleanPrompt
            lastDirectVoiceDispatchAtMs = System.currentTimeMillis()
            val handled = voiceCommandDispatcher.executeAction(
                action = parsedCommand,
                targetChatId = _currentChatId.value,
                callbacks = voiceCommandCallbacks
            )
            if (handled) {
                _isWakeHandoffInProgress.value = false
                wakeWordManager.setUserVoiceTurn(false)
                _currentDraftText.value = ""
                return true
            }
        }

        val routingDecision = com.example.voice.WakeWordRoutingEngine.resolveTargetChatForWakeWord(
            spokenWakePhrase = matchedWake,
            chats = allChats.value.ifEmpty { wakeWordManager.getActiveChats() },
            configuredWakeWords = allWakeWords.value,
            favoriteChatIds = favoriteChatIds.value,
            contextualRoutingEnabled = contextualRoutingEnabled.value,
            promptFavoritesOnGeneralWake = promptFavoritesOnWake.value,
            chatCategoryMap = chatCategoryMap.value,
            chatTagsMap = chatTagsMap.value,
            spokenContextOrTranscript = cleanPrompt
        )
        _matchedWakeCategory.value = routingDecision?.matchedCategory
        val resolvedTargetId = routingDecision?.targetChat?.id ?: _currentChatId.value
        if (resolvedTargetId != null && resolvedTargetId > 0L && _currentChatId.value != resolvedTargetId) {
            selectChat(resolvedTargetId)
        }

        // Validate pendingWakeQuery before dispatching to the chat session; discard if < 2 chars or blank
        val queryToDispatch = sanitizeValidWakeQuery(_pendingWakeQuery.value ?: cleanPrompt)
        _pendingWakeQuery.value = null
        if (queryToDispatch == null) {
            _isWakeHandoffInProgress.value = false
            return false
        }

        // Execute in-chat voice action command if matched, otherwise directly invoke sendMessage(text = queryToDispatch, triggeredByWakeWord = true)
        lastDirectVoiceDispatchPrompt = queryToDispatch
        lastDirectVoiceDispatchAtMs = System.currentTimeMillis()
        val handledAsActionCommand = executeInChatVoiceActionCommand(queryToDispatch, resolvedTargetId)
        if (handledAsActionCommand) {
            _isWakeHandoffInProgress.value = false
            wakeWordManager.setUserVoiceTurn(false)
            _currentDraftText.value = ""
            return true
        }

        releaseMicAndAudioFocusForWakeHandoffAndTts("wake_word_detected")
        sendMessage(text = queryToDispatch, triggeredByWakeWord = true)

        wakeWordManager.onKeywordHit(
            keyword = matchedWake,
            initialQuery = queryToDispatch,
            targetChatIdOverride = routingDecision?.targetChat?.id
        )
        _currentDraftText.value = ""
        return true
    }

    private val _pendingVoiceNavigationRoute = MutableStateFlow<String?>(null)
    val pendingVoiceNavigationRoute: StateFlow<String?> = _pendingVoiceNavigationRoute.asStateFlow()

    private val _isNavigatingViaVoiceCommand = MutableStateFlow(false)
    val isNavigatingViaVoiceCommand: StateFlow<Boolean> = _isNavigatingViaVoiceCommand.asStateFlow()

    private val _isWakeHandoffInProgress = MutableStateFlow(false)
    val isWakeHandoffInProgress: StateFlow<Boolean> = _isWakeHandoffInProgress.asStateFlow()

    @Volatile
    var awaitingVoiceNavigationCommitRoute: String? = null
        private set

    private var voiceNavigationSettleJob: kotlinx.coroutines.Job? = null
    private var lastVoiceNavigationAtMs: Long = 0L

    /**
     * Immediately clears/consumes the pending voice navigation route so it cannot re-trigger
     * in an infinite loop when the app resumes or recomposes.
     */
    fun consumePendingVoiceNavigationRoute() {
        val consumed = _pendingVoiceNavigationRoute.value
        _pendingVoiceNavigationRoute.value = null
        if (consumed != null) {
            wakeWordManager.appendDiagnosticTraceLog(
                stage = "VOICE_NAV_ROUTE_CONSUMED",
                details = "route=$consumed consumed by root NavController observer"
            )
        }
    }

    /**
     * Suspends active microphone capture and releases all AudioRecord / audio focus handles across
     * SpeechRecognitionCoordinator, WakeWordManager, AudioRecorder, and WakeWordVoiceService BEFORE
     * emitting an in-memory route ("history", "chat/$chatId", etc.) to the root NavController.
     * Waits for [onVoiceNavigationDestinationCommitted] (plus settle window) or the fallback settle
     * timer (650ms) before automatically resuming passive wake listening.
     */
    fun dispatchVoiceNavigationCommand(route: String, rawTranscript: String) {
        val cleanRoute = route.trim()
        if (cleanRoute.isBlank()) return
        val now = System.currentTimeMillis()
        if (now - lastVoiceNavigationAtMs < 600L && _pendingVoiceNavigationRoute.value == cleanRoute) {
            wakeWordManager.appendDiagnosticTraceLog(
                stage = "VOICE_NAV_DISPATCH_DEBOUNCED",
                details = "route=$cleanRoute ignored duplicate within 600ms"
            )
            return
        }
        lastVoiceNavigationAtMs = now
        val cleanedTranscript = rawTranscript.trim().ifBlank { "Open History" }

        wakeWordManager.appendDiagnosticTraceLog(
            stage = "VOICE_NAV_DISPATCH_START",
            details = "route=$cleanRoute transcript=\"${cleanedTranscript.take(50)}\" locking audio mutex before navigation"
        )

        // 1. Synchronously stop and release WakeWordVoiceService's AudioRecord and pause active
        //    utterance recognition BEFORE selecting the chat or emitting the navigation route.
        awaitingVoiceNavigationCommitRoute = cleanRoute
        _isNavigatingViaVoiceCommand.value = true
        _isWakeHandoffInProgress.value = true
        _isInChatDictationActive.value = false
        speechRecognitionCoordinator.setInChatDictationMode(false)
        speechRecognitionCoordinator.pauseForCommandDispatch(reason = "route:$cleanRoute")
        wakeWordManager.isExternalSpeechRecognizerActive = false
        wakeWordManager.setUserVoiceTurn(false)
        com.example.voice.WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("wake_word_detected")
        audioRecorder.stopAndReleaseHardware()
        wakeWordManager.stopAndReleaseAudioRecordSynchronously()
        com.example.voice.WakeWordVoiceService.pauseMicrophoneForCommandDispatch(getApplication())

        // 1b. Only AFTER synchronous release has completed, select and stage the target chat session
        if (cleanRoute.equals("chat", ignoreCase = true) || cleanRoute.startsWith("chat/", ignoreCase = true)) {
            val explicitId = cleanRoute.removePrefix("chat/").toLongOrNull()
            val resolvedChatId = explicitId
                ?: _currentChatId.value
                ?: allChats.value.firstOrNull { it.isDefaultWakeChat }?.id
                ?: allChats.value.firstOrNull()?.id
                ?: 1L
            if (_currentChatId.value != resolvedChatId) {
                selectChat(resolvedChatId)
            } else {
                stageChatAndUrlPassively()
            }
        }

        // 2. Clear any stale draft or pending wake query so it cannot auto-send
        _currentDraftText.value = ""
        _pendingWakeQuery.value = null

        // 3. Record command in Voice Command History
        val destinationTitle = com.example.voice.VoiceScreenDestination.entries
            .firstOrNull { it.route.equals(cleanRoute, ignoreCase = true) }
            ?.displayTitle
            ?: cleanRoute.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.US) else it.toString() }
        webLinkHistoryManager.recordVoiceCommand(
            commandType = VoiceCommandType.WAKE_WORD,
            triggerPhrase = "Voice Navigation ($destinationTitle)",
            recognizedTranscript = cleanedTranscript,
            chatTitle = currentChat.value?.title ?: "$destinationTitle Navigation"
        )
        wakeWordManager.setStatusMessage("Opening $destinationTitle...")

        // 4. Emit in-memory navigation route consumed by root NavController in MainActivity
        _pendingVoiceNavigationRoute.value = cleanRoute

        // 5. Fallback settle timer (500ms) in case NavController is already on destination or running in headless test
        voiceNavigationSettleJob?.cancel()
        voiceNavigationSettleJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            kotlinx.coroutines.delay(500L)
            if (_isNavigatingViaVoiceCommand.value) {
                completeVoiceNavigationMutexResume(
                    committedRoute = cleanRoute,
                    triggerSource = "fallback_settle_timer_500ms"
                )
            }
        }
    }

    /**
     * Invoked by [com.example.MainActivity] after `NavController` commits the target route
     * (e.g., `"history"`, `"chat"`) and the UI frame has settled. Schedules a post-commit settle delay
     * (`450ms`) before unlocking the microphone mutex and resuming passive wake listening.
     */
    fun onVoiceNavigationDestinationCommitted(committedRoute: String, settleDelayMs: Long = 450L) {
        val cleanCommitted = committedRoute.trim()
        if (cleanCommitted.isBlank()) return
        wakeWordManager.appendDiagnosticTraceLog(
            stage = "VOICE_NAV_DESTINATION_COMMITTED",
            details = "route=$cleanCommitted settleDelayMs=$settleDelayMs navMoving=${_isNavigatingViaVoiceCommand.value}"
        )
        voiceNavigationSettleJob?.cancel()
        voiceNavigationSettleJob = viewModelScope.launch(Dispatchers.Main.immediate) {
            kotlinx.coroutines.delay(settleDelayMs.coerceAtLeast(100L))
            completeVoiceNavigationMutexResume(
                committedRoute = cleanCommitted,
                triggerSource = "nav_controller_commit_plus_${settleDelayMs}ms"
            )
        }
    }

    private fun completeVoiceNavigationMutexResume(committedRoute: String, triggerSource: String) {
        awaitingVoiceNavigationCommitRoute = null
        _isNavigatingViaVoiceCommand.value = false
        _isWakeHandoffInProgress.value = false
        com.example.voice.WakeWordVoiceService.resumeMicrophoneAfterCommandNavigation(getApplication())
        val shouldResumeListening = _isHandsFreeActive.value && !_isSleepMode.value
        wakeWordManager.appendDiagnosticTraceLog(
            stage = "VOICE_NAV_MUTEX_RESUMED",
            details = "route=$committedRoute source=$triggerSource handsFree=${_isHandsFreeActive.value} sleep=${_isSleepMode.value} autoResume=$shouldResumeListening"
        )
        speechRecognitionCoordinator.resumeAfterCommandNavigation(
            delayMs = com.example.voice.SpeechRecognitionCoordinator.AUDIOFOCUS_GAIN_REARM_DEBOUNCE_MS,
            reason = "$triggerSource:$committedRoute"
        )
    }

    /**
     * Parses and executes in-app voice navigation, category filtering, in-chat voice actions,
     * and transient-guarded external URL commands via [voiceCommandDispatcher].
     * Returns true if a dedicated voice command was handled, or false if the transcript should be sent as a prompt.
     */
    fun executeInChatVoiceActionCommand(rawTranscript: String, targetChatId: Long? = _currentChatId.value): Boolean {
        val normalized = rawTranscript.trim().lowercase(java.util.Locale.US)
            .trimEnd('.', '!', '?')
            .trim()
        if (normalized.isBlank()) return false

        val activeWakeWords = (
            allChats.value.mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } } +
                wakeWordManager.getActiveChats().mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } } +
                allWakeWords.value.filter { it.isEnabled }.map { it.phrase }
            ).distinct()
        if (com.example.voice.AudioCommandSanitizer.isVoiceHistoryNavigationCommand(rawTranscript, activeWakeWords)) {
            dispatchVoiceNavigationCommand(route = "history", rawTranscript = rawTranscript)
            return true
        }

        if (voiceCommandDispatcher.dispatch(
                rawTranscript = rawTranscript,
                targetChatId = targetChatId,
                customWakeWords = activeWakeWords,
                availableCategories = availableCategories.value,
                callbacks = voiceCommandCallbacks
            )
        ) {
            return true
        }

        val chatId = targetChatId ?: _currentChatId.value ?: allChats.value.firstOrNull()?.id ?: return false

        val legacyHandled = when {
            normalized == "clear chat history" ||
                normalized == "delete chat history" -> {
                clearChatHistory(chatId)
                wakeWordManager.setStatusMessage("Cleared chat history via voice command")
                true
            }
            normalized == "read response" -> {
                val lastAiReply = rawCurrentMessages.value.lastOrNull { it.sender != "user" && it.content.isNotBlank() }?.content
                if (!lastAiReply.isNullOrBlank()) {
                    speakMessage(lastAiReply)
                    wakeWordManager.setStatusMessage("Reading last reply aloud")
                }
                true
            }
            normalized.contains("ocean theme") || normalized.contains("cyan theme") -> {
                updateChatAppearance(chatId, "OCEAN_CYAN", "DEFAULT", "#38BDF8")
                wakeWordManager.setStatusMessage("Applied Ocean Cyan palette")
                true
            }
            normalized.contains("emerald theme") || normalized.contains("forest theme") -> {
                updateChatAppearance(chatId, "EMERALD_FOREST", "AURORA_MESH", "#10B981")
                wakeWordManager.setStatusMessage("Applied Emerald Forest palette")
                true
            }
            normalized.contains("sunset theme") || normalized.contains("amber theme") -> {
                updateChatAppearance(chatId, "SUNSET_AMBER", "WARM_DUSK", "#F59E0B")
                wakeWordManager.setStatusMessage("Applied Sunset Amber palette")
                true
            }
            normalized.contains("violet theme") || normalized.contains("purple theme") || normalized.contains("nebula theme") -> {
                updateChatAppearance(chatId, "ROYAL_VIOLET", "NEBULA_GLOW", "#A855F7")
                wakeWordManager.setStatusMessage("Applied Royal Violet & Nebula Glow theme")
                true
            }
            normalized.contains("rose theme") || normalized.contains("pink theme") -> {
                updateChatAppearance(chatId, "ROSE_QUARTZ", "WARM_DUSK", "#F43F5E")
                wakeWordManager.setStatusMessage("Applied Rose Quartz palette")
                true
            }
            else -> false
        }
        if (legacyHandled) {
            completeVoiceCommandAndRearm("legacy_in_chat_command")
        }
        return legacyHandled
    }

    val isSpeechRecognizerListening: StateFlow<Boolean>
        get() = speechRecognitionCoordinator.isListening

    val speechRecognizerRmsDb: StateFlow<Float>
        get() = speechRecognitionCoordinator.liveRmsDb

    val speechRecognizerErrorCount: StateFlow<Int>
        get() = speechRecognitionCoordinator.errorCount

    val speechTraceHistory: StateFlow<List<String>>
        get() = speechRecognitionCoordinator.traceHistory

    val coordinatorMutexSnapshot: StateFlow<com.example.voice.SpeechRecognitionCoordinator.CoordinatorMutexSnapshot>
        get() = speechRecognitionCoordinator.mutexSnapshotFlow

    val wakeRecognitionListener: android.speech.RecognitionListener
        get() = speechRecognitionCoordinator.recognitionListener

    fun clearDiagnosticTraceLogs() {
        speechRecognitionCoordinator.clearTraceHistory()
        wakeWordManager.clearWakeWordLogs()
    }

    fun toggleDiagnosticSpeechRecognizer(): Boolean {
        val currentlyActive = speechRecognitionCoordinator.isListening.value || wakeWordManager.isListening.value
        return if (currentlyActive) {
            speechRecognitionCoordinator.recordTrace(
                stage = "DIAGNOSTIC_USER_STOP_REQUESTED",
                details = "User stopped SpeechRecognizer from Diagnostic Console"
            )
            stopContinuousWakeWordListening()
            false
        } else {
            speechRecognitionCoordinator.setSleeping(false)
            speechRecognitionCoordinator.setProcessingState(false)
            speechRecognitionCoordinator.setIntentionallyPaused(false)
            com.example.voice.WakeWordVoiceService.resumeAfterInternalDictationInMemory()
            com.example.voice.WakeWordVoiceService.setIntentionallyPaused(false)
            speechRecognitionCoordinator.recordTrace(
                stage = "DIAGNOSTIC_USER_START_REQUESTED",
                details = "User started SpeechRecognizer from Diagnostic Console"
            )
            val started = speechRecognitionCoordinator.startListening(userInitiated = true)
            if (!started) {
                wakeWordManager.startContinuousListening(forceActiveTurn = false)
            }
            started || wakeWordManager.isListening.value
        }
    }

    fun simulateDiagnosticWakeWordEvent(
        wakePhrase: String = allChats.value.firstOrNull { it.isDefaultWakeChat }?.wakeWord
            ?: allWakeWords.value.firstOrNull { it.isEnabled }?.phrase
            ?: "Hey Gemini",
        followUpPrompt: String = "diagnostic status check"
    ) {
        val cleanPhrase = wakePhrase.trim().ifBlank { "Hey Gemini" }
        val cleanFollowUp = followUpPrompt.trim().ifBlank { "diagnostic status check" }
        val simulatedTranscript = "$cleanPhrase $cleanFollowUp"

        speechRecognitionCoordinator.recordTrace(
            stage = "DIAGNOSTIC_WAKE_SIMULATION_START",
            details = "Simulating wake event phrase=\"$cleanPhrase\" transcript=\"$simulatedTranscript\""
        )
        speechRecognitionCoordinator.recognitionListener.onReadyForSpeech(android.os.Bundle())
        speechRecognitionCoordinator.recognitionListener.onBeginningOfSpeech()
        speechRecognitionCoordinator.recognitionListener.onRmsChanged(6.4f)

        val partialBundle = android.os.Bundle().apply {
            putStringArrayList(
                android.speech.SpeechRecognizer.RESULTS_RECOGNITION,
                arrayListOf(cleanPhrase)
            )
        }
        speechRecognitionCoordinator.recognitionListener.onPartialResults(partialBundle)

        val targetChat = allChats.value.firstOrNull {
            it.wakeWord.equals(cleanPhrase, ignoreCase = true)
        } ?: allChats.value.firstOrNull { it.isDefaultWakeChat } ?: allChats.value.firstOrNull()

        wakeWordManager.updateLastHeardText(simulatedTranscript)
        wakeWordManager.onKeywordHit(
            keyword = cleanPhrase,
            initialQuery = cleanFollowUp,
            targetChatIdOverride = targetChat?.id
        )
        speechRecognitionCoordinator.recordTrace(
            stage = "DIAGNOSTIC_WAKE_EVENT_VERIFIED",
            details = "Wake word \"$cleanPhrase\" routed to chat=\"${targetChat?.title ?: "Default Chat"}\" (id=${targetChat?.id ?: 0L})"
        )
    }

    fun resetDiagnosticAudioMutexLocks() {
        awaitingVoiceNavigationCommitRoute = null
        _isNavigatingViaVoiceCommand.value = false
        _isInChatDictationActive.value = false
        speechRecognitionCoordinator.setInChatDictationMode(false)
        speechRecognitionCoordinator.setProcessingState(false)
        speechRecognitionCoordinator.setSleeping(false)
        speechRecognitionCoordinator.setIntentionallyPaused(false)
        audioRecorder.setIntentionallyPaused(false)
        com.example.voice.WakeWordVoiceService.resumeAfterInternalDictationInMemory()
        com.example.voice.WakeWordVoiceService.setIntentionallyPaused(false)
        wakeWordManager.setUserVoiceTurn(false)
        wakeWordManager.exitSleepMode("Mutex reset from Diagnostic Console")
        speechRecognitionCoordinator.recordTrace(
            stage = "DIAGNOSTIC_MUTEX_LOCKS_RESET",
            details = "Cleared paused, processing, asleep, dictation, and navigation mutex locks"
        )
    }

    val sessionStartedAtMs: Long = System.currentTimeMillis()

    fun buildDiagnosticReportText(): String {
        val snap = speechRecognitionCoordinator.getMutexSnapshot()
        val traces = speechRecognitionCoordinator.traceHistory.value
        val logs = wakeWordManager.wakeWordLogs.value
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)
        return buildString {
            appendLine("=== GEMINI WAKE SPEECHRECOGNIZER & MUTEX DIAGNOSTIC REPORT ===")
            appendLine("Generated: ${sdf.format(java.util.Date(System.currentTimeMillis()))}")
            appendLine("Recognizer Listening: ${snap.isListening} | WakeManager Listening: ${wakeWordManager.isListening.value}")
            appendLine("Window Focus: ${snap.isWindowFocused} | Intentionally Paused: ${snap.isIntentionallyPaused}")
            appendLine("Processing Mutex: ${snap.isProcessing} | Sleep Mode: ${snap.isAsleep || _isSleepMode.value}")
            appendLine("In-Chat Dictation: ${snap.isInChatDictationMode} | Voice Nav Mutex Locked: ${_isNavigatingViaVoiceCommand.value}")
            appendLine("Exclusive Audio Focus: ${wakeWordManager.hasAudioFocus.value} | Hands-Free Mode: ${_isHandsFreeActive.value}")
            appendLine("Idle Timeouts: ${snap.consecutiveIdleTimeouts} | Transient Errors: ${snap.consecutiveTransientErrors} | Total Errors: ${snap.totalErrors}")
            appendLine("Status Message: ${wakeWordManager.statusMessage.value}")
            appendLine("Last Heard Transcript: \"${wakeWordManager.lastHeardText.value}\"")
            appendLine()
            appendLine("--- THREAD & MUTEX TRACE HISTORY (${traces.size} entries) ---")
            if (traces.isEmpty()) {
                appendLine("(No SpeechRecognizer trace entries recorded yet)")
            } else {
                traces.forEachIndexed { idx, line ->
                    appendLine("${idx + 1}. $line")
                }
            }
            appendLine()
            appendLine("--- WAKE-WORD & EVENT LOGS (${logs.size} entries) ---")
            if (logs.isEmpty()) {
                appendLine("(No wake-word event logs recorded yet)")
            } else {
                logs.forEachIndexed { idx, entry ->
                    appendLine("${idx + 1}. [${sdf.format(java.util.Date(entry.timestamp))}] ${entry.wakeWord} | rms=${String.format(java.util.Locale.US, "%.1f", entry.rmsDb)}dB | ${entry.chatTitle}")
                }
            }
        }
    }

    /**
     * Collects per-use session diagnostic information for debugging purposes when "Create a general log file"
     * is tapped in Settings, highlighting Network state, Permission states, Recent Error logs, and Session telemetry.
     */
    fun buildGeneralSessionLogFile(): String {
        val appContext = getApplication<Application>()
        val nowMs = System.currentTimeMillis()
        val uptimeSec = ((nowMs - sessionStartedAtMs) / 1000L).coerceAtLeast(0L)
        val snap = speechRecognitionCoordinator.getMutexSnapshot()
        val traces = speechRecognitionCoordinator.traceHistory.value
        val wakeLogs = wakeWordManager.wakeWordLogs.value
        val voiceCmds = voiceCommandHistory.value
        val relay = relayServerState.value
        val buf = bufferMetrics.value
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", java.util.Locale.US)

        val audioGranted = checkRecordAudioPermission()
        val cameraGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.CAMERA
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val notificationsGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                appContext,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val errorTraces = traces.filter { line ->
            line.contains("ERROR", ignoreCase = true) ||
                line.contains("EXCEPTION", ignoreCase = true) ||
                line.contains("UNAVAILABLE", ignoreCase = true) ||
                line.contains("ABORTED", ignoreCase = true) ||
                line.contains("OFF-MAIN-THREAD", ignoreCase = true) ||
                line.contains("RATE_LIMITED", ignoreCase = true) ||
                line.contains("WARN", ignoreCase = true)
        }
        val hapticEvents = com.example.voice.AudioCommandSanitizer.getRecentHapticEvents()

        return buildString {
            appendLine("=== GEMINI WAKE GENERAL SESSION DIAGNOSTIC LOG FILE ===")
            appendLine("Session Started: ${sdf.format(java.util.Date(sessionStartedAtMs))}")
            appendLine("Log Generated:   ${sdf.format(java.util.Date(nowMs))}")
            appendLine("Session Uptime:  ${uptimeSec / 60}m ${uptimeSec % 60}s (${nowMs - sessionStartedAtMs} ms)")
            appendLine("Active Chat:     ${currentChat.value?.title ?: "None"} (id=${_currentChatId.value ?: -1L})")
            appendLine("Loaded Chats:    ${allChats.value.size} | Configured Wake Words: ${allWakeWords.value.size}")
            appendLine("Voice Engine:    ${_voiceEngineMode.value.displayName} | Sensitivity: ${(_wakeWordSensitivity.value * 100).toInt()}%")
            appendLine("Memory Buffer:   Hot RAM=${buf.hotMemoryCount}/${buf.hotBufferSizeLimit} | Total Disk=${buf.totalDiskCount}")
            appendLine()
            appendLine("--- 1. NETWORK & CONNECTIVITY STATE ---")
            appendLine("Network Connection State: ${networkState.value.name}")
            appendLine("Cloud Firestore Sync:     ${_cloudSyncStatus.value}")
            appendLine("Signed-In Account:        ${_currentUserEmail.value ?: "Local / Unauthenticated"}")
            appendLine("Local Sync Relay:         ${relay.bindHost}:${relay.port} | Running=${relay.isRunning} | Clients=${relay.connectedClientsCount} | Deduplicated=${relay.deduplicatedTurnCount}")
            appendLine()
            appendLine("--- 2. PERMISSION & AUDIO FOCUS STATES ---")
            appendLine("RECORD_AUDIO Permission:       ${if (audioGranted) "GRANTED" else "DENIED"}")
            appendLine("CAMERA Permission:             ${if (cameraGranted) "GRANTED" else "DENIED"}")
            appendLine("POST_NOTIFICATIONS Permission: ${if (notificationsGranted) "GRANTED" else "DENIED"}")
            appendLine("Permission Rationale Enabled:  ${_permissionRationaleEnabled.value} | Permission Resets: ${_permissionsResetCount.value}")
            appendLine("Exclusive Audio Focus Held:    ${wakeWordManager.hasAudioFocus.value} | Window Focus: ${_hasWindowFocus.value}")
            appendLine("Hands-Free Radar Active:       ${_isHandsFreeActive.value} | Sleep Mode: ${_isSleepMode.value} (${_sleepModeReason.value.ifBlank { "Awake" }})")
            appendLine("Recognizer Listening:          ${snap.isListening} | WakeManager Listening: ${wakeWordManager.isListening.value}")
            appendLine()
            appendLine("--- 3. RECENT ERROR LOGS & WARNINGS ---")
            appendLine("Active UI Error Message:       ${_errorMessage.value ?: "None"}")
            appendLine("Recognizer Error Counters:     totalErrors=${snap.totalErrors}, transientErrors=${snap.consecutiveTransientErrors}, idleTimeouts=${snap.consecutiveIdleTimeouts}")
            if (errorTraces.isEmpty() && _errorMessage.value.isNullOrBlank()) {
                appendLine("(No error or warning trace entries recorded during this session)")
            } else {
                errorTraces.take(25).forEachIndexed { idx, errLine ->
                    appendLine("Error #${idx + 1}: $errLine")
                }
            }
            appendLine()
            appendLine("--- 4. HAPTIC FEEDBACK & VOICE ACTIVATION TELEMETRY ---")
            appendLine("Total Haptics Triggered: ${com.example.voice.AudioCommandSanitizer.hapticTriggerCount} | Last Pattern: ${com.example.voice.AudioCommandSanitizer.lastHapticPattern?.name ?: "NONE"}")
            if (hapticEvents.isEmpty()) {
                appendLine("(No haptic events triggered yet in this session)")
            } else {
                hapticEvents.take(10).forEachIndexed { idx, evt ->
                    appendLine("Haptic #${idx + 1}: $evt")
                }
            }
            appendLine("Session Voice Commands Recorded: ${voiceCmds.size}")
            voiceCmds.take(10).forEachIndexed { idx, cmd ->
                appendLine("Command #${idx + 1}: [${cmd.commandType.name}] trigger=\"${cmd.triggerPhrase}\" transcript=\"${cmd.recognizedTranscript}\"")
            }
            appendLine()
            appendLine("--- 5. RECENT SESSION TRACE & WAKE EVENT TAIL (${traces.size} traces, ${wakeLogs.size} logs) ---")
            if (traces.isEmpty() && wakeLogs.isEmpty()) {
                appendLine("(No trace entries recorded yet in this session)")
            } else {
                traces.take(20).forEachIndexed { idx, line ->
                    appendLine("Trace #${idx + 1}: $line")
                }
                wakeLogs.filter { !it.wakeWord.startsWith("[SR-Trace]") }.take(10).forEachIndexed { idx, entry ->
                    appendLine("Wake #${idx + 1}: [${sdf.format(java.util.Date(entry.timestamp))}] ${entry.wakeWord} | rms=${String.format(java.util.Locale.US, "%.1f", entry.rmsDb)}dB | ${entry.chatTitle}")
                }
            }
        }
    }

    /**
     * Builds the per-use general session log file and immediately copies it to the system clipboard on demand.
     */
    fun createAndCopyGeneralLogFileToClipboard(context: android.content.Context = getApplication()): String {
        val logContent = buildGeneralSessionLogFile()
        try {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("Gemini Wake General Session Log", logContent)
            clipboard?.setPrimaryClip(clip)
        } catch (e: Exception) {
            android.util.Log.w("MainViewModel", "Unable to copy general log file to clipboard: ${e.message}")
        }
        speechRecognitionCoordinator.recordTrace(
            stage = "GENERAL_LOG_FILE_CREATED",
            details = "Copied session general diagnostic log (${logContent.length} chars) to clipboard"
        )
        return logContent
    }

    /**
     * Evaluates partial or final SpeechRecognizer transcripts for wake-words, follow-up queries,
     * and hands-free commands (Dictate, Send, Heartbeat).
     * Returns true if a wake-word or terminal command was triggered.
     */
    fun processSpeechRecognizerResults(candidates: List<String>?, isFinal: Boolean): Boolean {
        val rawList = candidates
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() && !ChatMessage.isLegacyAudioPlaceholder(it) }
            .orEmpty()
        if (rawList.isEmpty()) return false

        val primaryTranscript = rawList.first().trim()

        // 0a. Check Wake-Up Voice Command ("Gemini, wake up") to exit Sleep Mode
        if (com.example.voice.AudioCommandSanitizer.containsWakeUpCommand(primaryTranscript)) {
            if (isFinal) {
                com.example.voice.AudioCommandSanitizer.playVoiceCommandHaptic(
                    getApplication(),
                    "Wake Up (\"$primaryTranscript\")"
                )
                exitSleepModeAndResumeListening(reason = "Voice command: \"$primaryTranscript\"")
                webLinkHistoryManager.recordVoiceCommand(
                    commandType = VoiceCommandType.WAKE_UP,
                    triggerPhrase = com.example.voice.AudioCommandSanitizer.DEFAULT_WAKE_UP_COMMAND,
                    recognizedTranscript = primaryTranscript,
                    chatTitle = currentChat.value?.title
                )
            }
            return isFinal
        }

        // 0b. Check Sleep Voice Command ("Gemini, sleep") to temporarily disable the wake word listener
        if (com.example.voice.AudioCommandSanitizer.containsSleepCommand(primaryTranscript)) {
            if (isFinal) {
                com.example.voice.AudioCommandSanitizer.playVoiceCommandHaptic(
                    getApplication(),
                    "Sleep (\"$primaryTranscript\")"
                )
                enterSleepMode(reason = "Voice command: \"$primaryTranscript\"")
                webLinkHistoryManager.recordVoiceCommand(
                    commandType = VoiceCommandType.SLEEP,
                    triggerPhrase = com.example.voice.AudioCommandSanitizer.DEFAULT_SLEEP_COMMAND,
                    recognizedTranscript = primaryTranscript,
                    chatTitle = currentChat.value?.title
                )
            }
            return isFinal
        }

        // If in sleep mode, ignore normal wake word and dictation triggers until woken up
        if (_isSleepMode.value) {
            return false
        }

        // 0c. Check Voice History or Screen/Category Navigation Commands ("open history", "open settings", "filter by work", etc.)
        //     Suspends active microphone/AudioRecord FIRST and navigates purely in-app via StateFlow.
        val activeWakeWordsForHistory = (
            allChats.value.mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } } +
                wakeWordManager.getActiveChats().mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } } +
                allWakeWords.value.filter { it.isEnabled }.map { it.phrase }
            ).distinct()
        val matchedHistoryCandidate = rawList.firstOrNull { candidate ->
            com.example.voice.AudioCommandSanitizer.isVoiceHistoryNavigationCommand(
                candidate,
                activeWakeWordsForHistory
            )
        }
        if (matchedHistoryCandidate != null) {
            wakeWordManager.updateLastHeardText(matchedHistoryCandidate)
            if (isFinal) {
                com.example.voice.AudioCommandSanitizer.playVoiceCommandHaptic(
                    getApplication(),
                    "Voice History Navigation (\"$matchedHistoryCandidate\")"
                )
                dispatchVoiceNavigationCommand(
                    route = "history",
                    rawTranscript = matchedHistoryCandidate
                )
                return true
            }
            return false
        }

        val matchedNavigationOrCategoryCandidate = rawList.firstOrNull { candidate ->
            val action = voiceCommandDispatcher.parseCommand(
                rawTranscript = candidate,
                customWakeWords = activeWakeWordsForHistory,
                availableCategories = availableCategories.value
            )
            action is com.example.voice.VoiceCommandAction.NavigateToScreen ||
                action is com.example.voice.VoiceCommandAction.SelectCategoryFilter ||
                action is com.example.voice.VoiceCommandAction.OpenExternalUrl
        }
        if (matchedNavigationOrCategoryCandidate != null) {
            wakeWordManager.updateLastHeardText(matchedNavigationOrCategoryCandidate)
            if (isFinal) {
                com.example.voice.AudioCommandSanitizer.playVoiceCommandHaptic(
                    getApplication(),
                    "Voice Navigation (\"$matchedNavigationOrCategoryCandidate\")"
                )
                executeInChatVoiceActionCommand(
                    rawTranscript = matchedNavigationOrCategoryCandidate,
                    targetChatId = _currentChatId.value
                )
                return true
            }
            return false
        }

        // 1. Check Heartbeat Trigger
        if (com.example.voice.AudioCommandSanitizer.containsHeartbeatTrigger(primaryTranscript, _heartbeatTrigger.value)) {
            if (isFinal) {
                wakeWordManager.onHandsFreeHeartbeat?.invoke()
                com.example.voice.AudioCommandSanitizer.playHeartbeatConfirmation(getApplication())
            }
            return isFinal
        }

        // 2. Check Send Trigger
        if (com.example.voice.AudioCommandSanitizer.containsSendTrigger(primaryTranscript, _sendTrigger.value)) {
            if (isFinal) {
                com.example.voice.AudioCommandSanitizer.playVoiceCommandHaptic(
                    getApplication(),
                    "Send (${_sendTrigger.value})"
                )
                wakeWordManager.onHandsFreeSend?.invoke(primaryTranscript)
            }
            return isFinal
        }

        // 3. Check Dictate Trigger
        if (com.example.voice.AudioCommandSanitizer.containsDictateTrigger(primaryTranscript, _dictateTrigger.value)) {
            val extracted = sanitizeValidWakeQuery(
                com.example.voice.AudioCommandSanitizer.extractContentAfterDictate(
                    primaryTranscript,
                    _dictateTrigger.value
                )
            )
            if (extracted != null) {
                _currentDraftText.value = ""
                wakeWordManager.updateLastHeardText(extracted)
                if (isFinal) {
                    com.example.voice.AudioCommandSanitizer.playVoiceCommandHaptic(
                        getApplication(),
                        "Dictate (${_dictateTrigger.value})"
                    )
                    _currentDraftText.value = ""
                    webLinkHistoryManager.recordVoiceCommand(
                        commandType = VoiceCommandType.DICTATE,
                        triggerPhrase = _dictateTrigger.value,
                        recognizedTranscript = extracted,
                        chatTitle = currentChat.value?.title
                    )
                    if (!executeInChatVoiceActionCommand(extracted, _currentChatId.value)) {
                        sendMessage(text = extracted, triggeredByWakeWord = true)
                    }
                    return true
                }
            }
            return false
        }

        // 3b. Explicit In-Chat Dictation Mode:
        // Leave _currentDraftText completely clear and on final transcripts directly invoke sendMessage(text = cleanPrompt, triggeredByWakeWord = true).
        if (_isInChatDictationActive.value) {
            val activeWakeWords = (
                allChats.value.mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } } +
                    wakeWordManager.getActiveChats().mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } } +
                    allWakeWords.value.filter { it.isEnabled }.map { it.phrase }
                ).distinct()
            val (_, scrubbed) = com.example.voice.SpeechRecognitionCoordinator.extractWakeTriggeredTranscript(
                rawTranscript = primaryTranscript,
                customWakeWords = activeWakeWords
            )
            val cleanPrompt = sanitizeValidWakeQuery(scrubbed)
            if (cleanPrompt != null &&
                !com.example.voice.AudioCommandSanitizer.isSystemOrCommandStatusString(
                    text = cleanPrompt,
                    dictateTrigger = _dictateTrigger.value,
                    sendTrigger = _sendTrigger.value,
                    heartbeatTrigger = _heartbeatTrigger.value
                )
            ) {
                _currentDraftText.value = ""
                wakeWordManager.updateLastHeardText(cleanPrompt)
                if (isFinal) {
                    com.example.voice.AudioCommandSanitizer.playVoiceCommandHaptic(
                        getApplication(),
                        "In-Chat Mic Dictation"
                    )
                    _isInChatDictationActive.value = false
                    speechRecognitionCoordinator.setInChatDictationMode(false)
                    wakeWordManager.setUserVoiceTurn(false)
                    _currentDraftText.value = ""
                    _lastDispatchedVoicePrompt.value = cleanPrompt
                    webLinkHistoryManager.recordVoiceCommand(
                        commandType = VoiceCommandType.DICTATE,
                        triggerPhrase = "In-Chat Mic Dictation",
                        recognizedTranscript = cleanPrompt,
                        chatTitle = currentChat.value?.title
                    )
                    if (!executeInChatVoiceActionCommand(cleanPrompt, _currentChatId.value)) {
                        sendMessage(text = cleanPrompt, triggeredByWakeWord = true)
                    }
                    if (_isHandsFreeActive.value && !_isSleepMode.value) {
                        com.example.voice.WakeWordVoiceService.resumeMicrophoneAfterCommandNavigation(getApplication())
                        scheduleSpeechRecognizerRestart(
                            delayMs = com.example.voice.SpeechRecognitionCoordinator.DEFAULT_RESTART_DEBOUNCE_MS,
                            allowDuringUserVoiceTurn = true
                        )
                    }
                    return true
                }
            }
            return false
        }

        // 4. Check Wake-Word Phrases across all recognition candidates
        for (candidate in rawList) {
            val wakeMatch = wakeWordManager.matchWakePhraseInTranscript(candidate)
            if (wakeMatch != null) {
                val now = System.currentTimeMillis()
                val customWordsForMatch = listOfNotNull(
                    wakeMatch.matchedWakeWord.takeIf { it.isNotBlank() },
                    wakeMatch.matchedChat?.wakeWord?.takeIf { it.isNotBlank() }
                ).distinct()
                val strippedFollowUp = wakeMatch.followUpQuery?.let { rawFollowUp ->
                    com.example.voice.SpeechRecognitionCoordinator.extractWakeTriggeredTranscript(
                        rawTranscript = rawFollowUp,
                        customWakeWords = customWordsForMatch
                    ).second.trim()
                }?.let { sanitizeValidWakeQuery(it) }

                // If user is still speaking a follow-up query during partial results, wait for final result
                // so the entire follow-up question is captured in one breath.
                if (!isFinal && !strippedFollowUp.isNullOrBlank()) {
                    wakeWordManager.updateLastHeardText(strippedFollowUp)
                    return false
                }
                if (!isFinal && now - lastPartialWakeMatchMs < 1500L) {
                    return false
                }
                lastPartialWakeMatchMs = now
                // 1. The exact moment the wake word is detected, immediately invoke synchronous release
                //    BEFORE firing any navigation intent, chat selection, or state update.
                _isWakeHandoffInProgress.value = true
                com.example.voice.WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("wake_word_detected")
                com.example.voice.AudioCommandSanitizer.playWakeWordHaptic(
                    getApplication(),
                    wakeMatch.matchedWakeWord
                )
                stopSpeechRecognizerInternal(markIntentionallyPaused = false)
                audioRecorder.stopAndReleaseHardware()
                wakeWordManager.stopAndReleaseAudioRecordSynchronously()
                wakeWordManager.abandonExclusiveAudioFocus()
                _currentDraftText.value = ""
                if (strippedFollowUp != null) {
                    _lastDispatchedVoicePrompt.value = strippedFollowUp
                    _pendingWakeQuery.value = strippedFollowUp
                    val validatedPending = sanitizeValidWakeQuery(_pendingWakeQuery.value)
                    _pendingWakeQuery.value = null
                    if (validatedPending != null) {
                        lastDirectVoiceDispatchPrompt = validatedPending
                        lastDirectVoiceDispatchAtMs = System.currentTimeMillis()
                        val handledAsAction = executeInChatVoiceActionCommand(
                            validatedPending,
                            wakeMatch.matchedChat?.id ?: _currentChatId.value
                        )
                        if (handledAsAction) {
                            _isWakeHandoffInProgress.value = false
                            wakeWordManager.setUserVoiceTurn(false)
                            _currentDraftText.value = ""
                            return true
                        }
                        releaseMicAndAudioFocusForWakeHandoffAndTts("wake_word_detected")
                        sendMessage(text = validatedPending, triggeredByWakeWord = true)
                    }
                } else {
                    _pendingWakeQuery.value = null
                }
                wakeWordManager.onKeywordHit(
                    keyword = wakeMatch.matchedWakeWord,
                    initialQuery = strippedFollowUp,
                    targetChatIdOverride = wakeMatch.matchedChat?.id
                )
                _currentDraftText.value = ""
                return true
            }
        }

        // 5. Follow-up Voice Turn (only when an explicit wake-word turn is already active):
        // Do NOT pipe ambient transcripts into _currentDraftText; keep _currentDraftText blank and directly invoke sendMessage.
        if (isUserVoiceTurn.value) {
            val activeWakeWords = (
                allChats.value.mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } } +
                    wakeWordManager.getActiveChats().mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } } +
                    allWakeWords.value.filter { it.isEnabled }.map { it.phrase }
                ).distinct()
            val (_, scrubbed) = com.example.voice.SpeechRecognitionCoordinator.extractWakeTriggeredTranscript(
                rawTranscript = primaryTranscript,
                customWakeWords = activeWakeWords
            )
            val cleanPrompt = sanitizeValidWakeQuery(scrubbed)
            if (cleanPrompt != null &&
                !com.example.voice.AudioCommandSanitizer.isSystemOrCommandStatusString(
                    text = cleanPrompt,
                    dictateTrigger = _dictateTrigger.value,
                    sendTrigger = _sendTrigger.value,
                    heartbeatTrigger = _heartbeatTrigger.value
                )
            ) {
                wakeWordManager.updateLastHeardText(cleanPrompt)
                if (isFinal) {
                    wakeWordManager.setUserVoiceTurn(false)
                    _currentDraftText.value = ""
                    _lastDispatchedVoicePrompt.value = cleanPrompt
                    webLinkHistoryManager.recordVoiceCommand(
                        commandType = VoiceCommandType.VOICE_TURN,
                        triggerPhrase = currentChat.value?.wakeWord?.ifBlank { "Voice Input" } ?: "Voice Input",
                        recognizedTranscript = cleanPrompt,
                        chatTitle = currentChat.value?.title
                    )
                    if (!executeInChatVoiceActionCommand(cleanPrompt, _currentChatId.value)) {
                        sendMessage(text = cleanPrompt, triggeredByWakeWord = true)
                    }
                    if (_isHandsFreeActive.value && !_isSleepMode.value) {
                        scheduleSpeechRecognizerRestart(
                            delayMs = com.example.voice.SpeechRecognitionCoordinator.DEFAULT_RESTART_DEBOUNCE_MS,
                            allowDuringUserVoiceTurn = true
                        )
                    }
                    return true
                }
            }
        }
        return false
    }

    private fun scheduleSpeechRecognizerRestart(
        delayMs: Long = com.example.voice.SpeechRecognitionCoordinator.DEFAULT_RESTART_DEBOUNCE_MS,
        allowDuringUserVoiceTurn: Boolean = false
    ) {
        if (_isSleepMode.value || (!allowDuringUserVoiceTurn && isUserVoiceTurn.value && !_isInChatDictationActive.value)) {
            return
        }
        speechRecognitionCoordinator.scheduleDebouncedRestart(
            delayMs = delayMs.coerceAtLeast(com.example.voice.SpeechRecognitionCoordinator.MIN_RESTART_DEBOUNCE_MS)
        )
    }

    /**
     * Explicitly stops and releases any active AudioRecord instances in WakeWordVoiceService,
     * AudioRecorder, and WakeWordManager before SpeechRecognizer.startListening() is called,
     * guaranteeing that legacy/service PCM AudioRecord and SpeechRecognizer NEVER run concurrently.
     */
    fun releaseAllNativeAudioRecordTracksForSpeechRecognizer() {
        com.example.voice.WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("prepare_hardware_for_speech_recognizer")
        audioRecorder.stopAndReleaseHardware()
        wakeWordManager.stopAndReleaseAudioRecordSynchronously()
    }

    private fun startSpeechRecognizerInternal(userInitiated: Boolean = false): Boolean {
        return speechRecognitionCoordinator.startListening(userInitiated = userInitiated)
    }

    private fun stopSpeechRecognizerInternal(markIntentionallyPaused: Boolean = false) {
        wakeWordManager.isExternalSpeechRecognizerActive = false
        speechRecognitionCoordinator.stopListening(markIntentionallyPaused = markIntentionallyPaused)
    }

    private fun destroySpeechRecognizerInternal(markIntentionallyPaused: Boolean = true) {
        wakeWordManager.isExternalSpeechRecognizerActive = false
        speechRecognitionCoordinator.destroy(markIntentionallyPaused = markIntentionallyPaused)
    }

    /**
     * Starts continuous wake-word listening via the ViewModel's Hybrid SpeechRecognizer + PCM engine,
     * verifying Manifest.permission.RECORD_AUDIO first.
     */
    fun startContinuousWakeWordListening(userInitiated: Boolean = false) {
        if (!ensureMicrophonePermission(startWakeListeningWhenGranted = true)) {
            return
        }
        if ((isUserVoiceTurn.value && !_isInChatDictationActive.value) || _isSleepMode.value) {
            return
        }
        if (!userInitiated && (
                _isWakeHandoffInProgress.value ||
                    textToSpeechManager.isSpeaking.value ||
                    textToSpeechManager.hasPendingSettlingDelay ||
                    speechRecognitionCoordinator.isProcessing ||
                    _isNavigatingViaVoiceCommand.value ||
                    _isGenerating.value
                )
        ) {
            return
        }
        speechRecognitionCoordinator.setIntentionallyPaused(false)
        com.example.voice.WakeWordVoiceService.setIntentionallyPaused(false)
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val context = getApplication<Application>()
            if (android.speech.SpeechRecognizer.isRecognitionAvailable(context) &&
                !wakeWordManager.isListening.value
            ) {
                if (!speechRecognitionCoordinator.isListening.value &&
                    !speechRecognitionCoordinator.hasPendingRestart
                ) {
                    val started = startSpeechRecognizerInternal(userInitiated = userInitiated)
                    if (!started &&
                        !speechRecognitionCoordinator.hasPendingRestart &&
                        !speechRecognitionCoordinator.shouldAbortListening()
                    ) {
                        wakeWordManager.startContinuousListening(forceActiveTurn = false)
                    }
                }
            } else if (!speechRecognitionCoordinator.isListening.value) {
                wakeWordManager.startContinuousListening(forceActiveTurn = false)
            }
        }
    }

    /**
     * Stops continuous wake-word listening across both SpeechRecognizer and WakeWordManager.
     * When [markIntentionallyPaused] is `true` (feature turned off), destroys the SpeechRecognizer
     * instance to fully release the Processing Mutex.
     */
    fun stopContinuousWakeWordListening(markIntentionallyPaused: Boolean = true) {
        _isInChatDictationActive.value = false
        speechRecognitionCoordinator.setInChatDictationMode(false)
        if (markIntentionallyPaused) {
            com.example.voice.WakeWordVoiceService.setIntentionallyPaused(true)
            destroySpeechRecognizerInternal(markIntentionallyPaused = true)
        } else {
            stopSpeechRecognizerInternal(markIntentionallyPaused = false)
        }
        wakeWordManager.stopContinuousListening()
    }

    /**
     * Decisively stops SpeechRecognitionCoordinator, releases all native AudioRecord hardware,
     * and abandons exclusive audio focus BEFORE Gemini model inference or TTS audio playback begins.
     */
    fun releaseMicAndAudioFocusForWakeHandoffAndTts(reason: String = "wake_word_or_tts_handoff") {
        _isInChatDictationActive.value = false
        speechRecognitionCoordinator.setInChatDictationMode(false)
        speechRecognitionCoordinator.setProcessingState(true)
        stopSpeechRecognizerInternal(markIntentionallyPaused = false)
        com.example.voice.WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously(reason)
        audioRecorder.stopAndReleaseHardware()
        wakeWordManager.completeChatTurnAndReleaseMic()
        wakeWordManager.stopAndReleaseAudioRecordSynchronously()
        wakeWordManager.abandonExclusiveAudioFocus()
    }

    /**
     * Toggles continuous wake-word listening and hands-free mode together via [toggleHandsFreeVoice].
     */
    fun toggleContinuousWakeWordListening() {
        toggleHandsFreeVoice()
    }

    private val _currentDraftText = MutableStateFlow("")
    val currentDraftText: StateFlow<String> = _currentDraftText.asStateFlow()

    private val _lastHeartbeatPingTime = MutableStateFlow<Long?>(null)
    val lastHeartbeatPingTime: StateFlow<Long?> = _lastHeartbeatPingTime.asStateFlow()

    val customPrompts: StateFlow<List<com.example.data.model.PromptTemplate>> = promptRepository.prompts

    private val _wakeWordSensitivity = MutableStateFlow(appPrefs.getFloat("wake_word_sensitivity", 0.78f))
    val wakeWordSensitivity: StateFlow<Float> = _wakeWordSensitivity.asStateFlow()

    private val _isSleepMode = MutableStateFlow(false)
    val isSleepMode: StateFlow<Boolean> = _isSleepMode.asStateFlow()

    private val _sleepModeReason = MutableStateFlow("")
    val sleepModeReason: StateFlow<String> = _sleepModeReason.asStateFlow()

    private val _backgroundCueOnlyMode = MutableStateFlow(appPrefs.getBoolean("background_cue_only_mode", true))
    val backgroundCueOnlyMode: StateFlow<Boolean> = _backgroundCueOnlyMode.asStateFlow()

    private val _wakeReadyVisualCue = MutableStateFlow<WakeReadyCueState?>(null)
    val wakeReadyVisualCue: StateFlow<WakeReadyCueState?> = _wakeReadyVisualCue.asStateFlow()

    val allWakeWordSamples: StateFlow<List<WakeWordSample>> = repository.getAllWakeWordSamplesFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000L),
            initialValue = emptyList()
        )

    fun setBackgroundCueOnlyMode(enabled: Boolean) {
        _backgroundCueOnlyMode.value = enabled
        appPrefs.edit().putBoolean("background_cue_only_mode", enabled).apply()
    }

    fun dismissWakeReadyVisualCue() {
        _wakeReadyVisualCue.value = null
    }

    /**
     * Temporarily disables the wake word listener (Sleep Mode) and displays visual feedback.
     * Can be triggered via the "Gemini, sleep" voice command or a UI tap.
     */
    fun enterSleepMode(reason: String = "Say \"Gemini, wake up\" or tap Wake Up to resume") {
        _isSleepMode.value = true
        _sleepModeReason.value = reason
        com.example.voice.AudioCommandSanitizer.playRadarToggleHaptic(
            context = getApplication(),
            isActivating = false,
            reason = "Entered Sleep Mode ($reason)"
        )
        audioRecorder.setIntentionallyPaused(true)
        speechRecognitionCoordinator.setSleeping(true)
        wakeWordManager.isExternalSpeechRecognizerActive = false
        wakeWordManager.enterSleepMode(reason)
        com.example.voice.WakeWordVoiceService.setSleepMode(true)
        com.example.voice.WakeWordVoiceService.startServiceSafely(
            context = getApplication(),
            action = com.example.voice.WakeWordVoiceService.ACTION_ENTER_SLEEP_MODE
        )
        wakeWordManager.stopContinuousListening()
        wakeWordManager.setStatusMessage("Sleep Mode active — tap Wake Up or say \"Gemini, wake up\"")
    }

    /**
     * Re-enables the wake word listener from Sleep Mode via UI tap or "Gemini, wake up" voice command.
     */
    fun exitSleepModeAndResumeListening(reason: String = "Listening resumed") {
        _isSleepMode.value = false
        _sleepModeReason.value = ""
        com.example.voice.AudioCommandSanitizer.playRadarToggleHaptic(
            context = getApplication(),
            isActivating = true,
            reason = "Exited Sleep Mode ($reason)"
        )
        audioRecorder.setIntentionallyPaused(false)
        speechRecognitionCoordinator.setSleeping(false)
        wakeWordManager.exitSleepMode(reason)
        com.example.voice.WakeWordVoiceService.setSleepMode(false)
        com.example.voice.WakeWordVoiceService.startServiceSafely(
            context = getApplication(),
            action = com.example.voice.WakeWordVoiceService.ACTION_WAKE_FROM_SLEEP
        )
        wakeWordManager.setStatusMessage("$reason — say a wake word")
        startContinuousWakeWordListening()
    }

    fun setWakeWordSensitivity(sensitivity: Float) {
        val clamped = sensitivity.coerceIn(0.50f, 0.98f)
        _wakeWordSensitivity.value = clamped
        appPrefs.edit().putFloat("wake_word_sensitivity", clamped).apply()
        wakeWordManager.wakeWordSensitivity = clamped
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.updateSensitivity(clamped)
        }
    }

    fun setWakeWordSensitivityLevel(level: com.example.voice.WakeSensitivityLevel) {
        setWakeWordSensitivity(level.sensitivityValue)
    }

    val localSyncServer = com.example.bridge.LocalSyncRelayServer(viewModelScope) { chromeMessage ->
        val chatId = _currentChatId.value ?: return@LocalSyncRelayServer
        // Insert message received from Chrome using safe transaction
        repository.insertMessageSafely(chromeMessage.copy(chatId = chatId))
        // Trigger Gemini reply if it's a user message
        if (chromeMessage.sender == "user") {
            sendMessage(chatId, chromeMessage.content, triggeredByWakeWord = false)
        }
    }

    val relayServerState: StateFlow<com.example.bridge.RelayServerState> = localSyncServer.serverState

    // AppSettingsManager state (Favorites, Pinned Chats, Contextual Routing, Categories/Tags, System Hygiene, 30-Day Backup Reminder)
    val favoriteChatIds: StateFlow<Set<Long>> = com.example.util.AppSettingsManager.favoriteChatIds
    val pinnedChatIds: StateFlow<Set<Long>> = com.example.util.AppSettingsManager.pinnedChatIds
    val pinnedTimestampsMap: StateFlow<Map<Long, Long>> = com.example.util.AppSettingsManager.pinnedTimestampsMap
    val contextualRoutingEnabled: StateFlow<Boolean> = com.example.util.AppSettingsManager.contextualRoutingEnabled
    val promptFavoritesOnWake: StateFlow<Boolean> = com.example.util.AppSettingsManager.promptFavoritesOnWake
    val lastHygieneResetMessage: StateFlow<String?> = com.example.util.AppSettingsManager.lastHygieneResetMessage
    val chatCategoryMap: StateFlow<Map<Long, String>> = com.example.util.AppSettingsManager.chatCategoryMap
    val messageCategoryMap: StateFlow<Map<Long, String>> = com.example.util.AppSettingsManager.messageCategoryMap
    val chatTagsMap: StateFlow<Map<Long, Set<String>>> = com.example.util.AppSettingsManager.chatTagsMap
    val availableCategories: StateFlow<List<String>> = com.example.util.AppSettingsManager.availableCategories

    private val _pendingNewChatCategory = MutableStateFlow<String?>(null)
    val pendingNewChatCategory: StateFlow<String?> = _pendingNewChatCategory.asStateFlow()

    private val _pendingNewChatTags = MutableStateFlow<Set<String>>(emptySet())
    val pendingNewChatTags: StateFlow<Set<String>> = _pendingNewChatTags.asStateFlow()

    fun setPendingNewChatCategory(category: String?, tags: Set<String> = emptySet()) {
        val cleaned = category?.trim()?.takeIf { it.isNotBlank() }
        _pendingNewChatCategory.value = cleaned
        _pendingNewChatTags.value = tags
        if (!cleaned.isNullOrBlank()) {
            com.example.util.AppSettingsManager.addCustomCategory(getApplication(), cleaned)
        }
    }

    private val _matchedWakeCategory = MutableStateFlow<String?>(null)
    val matchedWakeCategory: StateFlow<String?> = _matchedWakeCategory.asStateFlow()

    private val _wakeConversationSummary = MutableStateFlow<com.example.voice.WakeConversationSummary?>(null)
    val wakeConversationSummary: StateFlow<com.example.voice.WakeConversationSummary?> = _wakeConversationSummary.asStateFlow()

    private val _showWakeFavoritesPickerDialog = MutableStateFlow(false)
    val showWakeFavoritesPickerDialog: StateFlow<Boolean> = _showWakeFavoritesPickerDialog.asStateFlow()

    private val _lastDetectedWakePhrase = MutableStateFlow("Hey Gemini")
    val lastDetectedWakePhrase: StateFlow<String> = _lastDetectedWakePhrase.asStateFlow()

    val audioRecorder = AudioRecorder(application.applicationContext)
    val snippetPlayer = SnippetAudioPlayer()
    val wakeWordStorageManager = WakeWordStorageManager(application.applicationContext)

    private val _activeRecordingSlot = MutableStateFlow<Int?>(null)
    val activeRecordingSlot: StateFlow<Int?> = _activeRecordingSlot.asStateFlow()

    private var activeRecordingChatId: Long = 0L

    private val _snippetRecordingDurationMs = MutableStateFlow(0L)
    val snippetRecordingDurationMs: StateFlow<Long> = _snippetRecordingDurationMs.asStateFlow()

    private var recordingTimerJob: kotlinx.coroutines.Job? = null

    val audioRecorderState: StateFlow<AudioRecorder.State> = audioRecorder.state
    val currentlyPlayingSnippetPath: StateFlow<String?> = snippetPlayer.currentPlayingPath
    val snippetPlaybackProgress: StateFlow<Float> = snippetPlayer.playbackProgress
    val snippetPlaybackPositionMs: StateFlow<Int> = snippetPlayer.playbackPositionMs
    val snippetPlaybackDurationMs: StateFlow<Int> = snippetPlayer.playbackDurationMs
    val isSnippetPaused: StateFlow<Boolean> = snippetPlayer.isPaused

    private val _wakeWordStorageBytes = MutableStateFlow(wakeWordStorageManager.getTotalStorageBytes())
    val wakeWordStorageBytes: StateFlow<Long> = _wakeWordStorageBytes.asStateFlow()

    private val _autoStopLimitSec = MutableStateFlow(appPrefs.getInt("auto_stop_limit_sec", 3))
    val autoStopLimitSec: StateFlow<Int> = _autoStopLimitSec.asStateFlow()

    private val _masterVolume = MutableStateFlow(appPrefs.getFloat("master_volume", 1.0f))
    val masterVolume: StateFlow<Float> = _masterVolume.asStateFlow()

    fun setAutoStopLimit(seconds: Int) {
        _autoStopLimitSec.value = seconds.coerceAtLeast(0)
        appPrefs.edit().putInt("auto_stop_limit_sec", _autoStopLimitSec.value).apply()
    }

    fun setMasterVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _masterVolume.value = clamped
        appPrefs.edit().putFloat("master_volume", clamped).apply()
        snippetPlayer.setVolume(clamped)
        streamingVoicePlayer.setVolume(clamped)
    }

    private val networkStatusTracker = NetworkStatusTracker(application)
    val networkState: StateFlow<NetworkConnectionState> = networkStatusTracker.networkState

    fun refreshNetworkStatus() {
        networkStatusTracker.refresh()
    }

    private val webLinkHistoryManager = WebLinkHistoryManager(application)
    val webLinkHistory: StateFlow<List<WebLinkHistoryEntry>> = webLinkHistoryManager.historyEntries
    val voiceCommandHistory: StateFlow<List<VoiceCommandHistoryEntry>> = webLinkHistoryManager.voiceCommandEntries

    fun submitWebSearchOrUrl(input: String): WebLinkHistoryEntry? {
        val entry = webLinkHistoryManager.recordSearchOrUrl(input)
        if (entry != null) {
            stageChatAndUrlPassively(stagedUrl = entry.url)
        }
        return entry
    }

    fun recordChatLinkOpened(url: String, label: String? = null, chatTitle: String? = null): WebLinkHistoryEntry? {
        val entry = webLinkHistoryManager.recordChatLink(url, label, chatTitle)
        if (entry != null) {
            stageChatAndUrlPassively(stagedUrl = entry.url)
        }
        return entry
    }

    fun recordIncomingExternalLink(url: String): WebLinkHistoryEntry? {
        val entry = webLinkHistoryManager.recordIncomingExternalLink(url)
        if (entry != null) {
            stageChatAndUrlPassively(stagedUrl = entry.url)
        }
        return entry
    }

    fun deleteWebHistoryEntry(id: String) {
        webLinkHistoryManager.deleteEntry(id)
    }

    fun clearAllWebHistory() {
        webLinkHistoryManager.clearAllHistory()
    }

    fun recordVoiceCommand(
        commandType: VoiceCommandType,
        triggerPhrase: String,
        recognizedTranscript: String,
        chatTitle: String? = null
    ): VoiceCommandHistoryEntry? {
        return webLinkHistoryManager.recordVoiceCommand(
            commandType = commandType,
            triggerPhrase = triggerPhrase,
            recognizedTranscript = recognizedTranscript,
            chatTitle = chatTitle
        )
    }

    fun deleteVoiceCommandEntry(id: String) {
        webLinkHistoryManager.deleteVoiceCommandEntry(id)
    }

    fun clearAllVoiceCommandHistory() {
        webLinkHistoryManager.clearAllVoiceCommandHistory()
    }

    /**
     * Purges or repairs any corrupted JSON entries in web/voice history, chat disk caches,
     * and legacy audio placeholder rows in Room.
     */
    fun clearCorruptedHistory(): Int {
        val repairedWebAndVoice = webLinkHistoryManager.repairOrClearCorruptedHistory()
        val repairedDiskCaches = bufferManager.repairOrClearCorruptedDiskCaches()
        viewModelScope.launch(Dispatchers.IO) {
            repository.pruneLegacyAudioPlaceholders()
        }
        return repairedWebAndVoice + repairedDiskCaches
    }

    private val _notifyActiveChatReplies = MutableStateFlow(appPrefs.getBoolean("notify_active_chat_replies", true))
    val notifyActiveChatReplies: StateFlow<Boolean> = _notifyActiveChatReplies.asStateFlow()

    fun setNotifyActiveChatReplies(enabled: Boolean) {
        _notifyActiveChatReplies.value = enabled
        appPrefs.edit().putBoolean("notify_active_chat_replies", enabled).apply()
    }

    private val _permissionRationaleEnabled = MutableStateFlow(appPrefs.getBoolean("permission_rationale_enabled", true))
    val permissionRationaleEnabled: StateFlow<Boolean> = _permissionRationaleEnabled.asStateFlow()

    private val _permissionsResetCount = MutableStateFlow(appPrefs.getInt("permissions_reset_count", 0))
    val permissionsResetCount: StateFlow<Int> = _permissionsResetCount.asStateFlow()

    fun resetPermissionsState() {
        val nextCount = _permissionsResetCount.value + 1
        _permissionsResetCount.value = nextCount
        _permissionRationaleEnabled.value = true
        appPrefs.edit()
            .putBoolean("permission_rationale_enabled", true)
            .putInt("permissions_reset_count", nextCount)
            .apply()
    }

    private var isAppInForeground: Boolean = true

    fun refreshWakeWordStorageStats() {
        _wakeWordStorageBytes.value = wakeWordStorageManager.getTotalStorageBytes()
    }

    val allChats: StateFlow<List<ChatSession>> = repository.allChats
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val allWakeWords: StateFlow<List<WakeWord>> = wakeWordRepository.observeAllWakeWords()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val configuredWakeWords: StateFlow<List<WakeWord>> = wakeWordRepository.observeChatWakeWords()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _currentChatId = MutableStateFlow<Long?>(null)
    val currentChatId: StateFlow<Long?> = _currentChatId.asStateFlow()

    val currentChat: StateFlow<ChatSession?> = _currentChatId
        .flatMapLatest { id ->
            if (id != null) repository.getChatById(id) else flowOf(null)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )

    // Raw messages from MessageRepository
    val rawCurrentMessages: StateFlow<List<ChatMessage>> = _currentChatId
        .flatMapLatest { id ->
            if (id != null) messageRepository.getMessagesForChat(id) else flowOf(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Hot memory-buffered messages (avoids freezing on large chat threads)
    val hotMessages: StateFlow<List<ChatMessage>> = bufferManager.hotMessages
    val bufferMetrics: StateFlow<BufferMetrics> = bufferManager.bufferMetrics
    val hasMoreOlderMessages: StateFlow<Boolean> = bufferManager.hasMoreOlderMessages
    val isLoadingOlder: StateFlow<Boolean> = bufferManager.isLoadingOlder

    val allMessages: StateFlow<List<ChatMessage>> = messageRepository.getAllMessages()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _triggerBanner = MutableStateFlow<TriggerBanner?>(null)
    val triggerBanner: StateFlow<TriggerBanner?> = _triggerBanner.asStateFlow()

    private val _isAutoSpeakEnabled = MutableStateFlow(true)
    val isAutoSpeakEnabled: StateFlow<Boolean> = _isAutoSpeakEnabled.asStateFlow()

    // Active stopwatch/timer tracking state per listed chat
    private val _activeTimerChatIds = MutableStateFlow<Set<Long>>(emptySet())
    val activeTimerChatIds: StateFlow<Set<Long>> = _activeTimerChatIds.asStateFlow()

    private val _liveTrackedSeconds = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val liveTrackedSeconds: StateFlow<Map<Long, Long>> = _liveTrackedSeconds.asStateFlow()

    // Open chat stopwatch/timer: only persists as long as the chat is open (not necessarily active)
    private val _openChatTimerSeconds = MutableStateFlow(0L)
    val openChatTimerSeconds: StateFlow<Long> = _openChatTimerSeconds.asStateFlow()

    private val _isOpenChatTimerRunning = MutableStateFlow(false)
    val isOpenChatTimerRunning: StateFlow<Boolean> = _isOpenChatTimerRunning.asStateFlow()

    // Comparison UI element toggle for Usage Limits vs Cache
    private val _showUsageLimitsComparison = MutableStateFlow(true)
    val showUsageLimitsComparison: StateFlow<Boolean> = _showUsageLimitsComparison.asStateFlow()

    // Gemini Coupler & Piggyback Bridge State
    private val _lastCouplerHandoffTime = MutableStateFlow<Long?>(null)
    val lastCouplerHandoffTime: StateFlow<Long?> = _lastCouplerHandoffTime.asStateFlow()

    // Dedicated Screenshot & Image Staging Pipeline
    val stagedImageUri: StateFlow<Uri?> = GeminiCouplerBridge.stagedImageUri
    val stagedImageInfo: StateFlow<StagedImageInfo?> = GeminiCouplerBridge.stagedImageInfo
    val imagePayloadChannel = GeminiCouplerBridge.imagePayloadChannel

    private val _screenshotBanner = MutableStateFlow<StagedImageInfo?>(null)
    val screenshotBanner: StateFlow<StagedImageInfo?> = _screenshotBanner.asStateFlow()

    private val screenshotWatcher = ScreenshotWatcher(application) { uri ->
        stageImage(uri)
    }

    private val chatImportWorker = ChatImportWorker(application, database.chatDao())
    private val _importProgressState = MutableStateFlow(ImportProgressState())
    val importProgressState: StateFlow<ImportProgressState> = _importProgressState.asStateFlow()

    // Dual-Key Failover State
    val apiKeyManager = ApiKeyManager.getInstance(application)
    val activeKeyType: StateFlow<ActiveKeyType> = apiKeyManager.activeKeyType
    val primaryApiKey: StateFlow<String> = apiKeyManager.primaryKeyFlow
    val backupApiKey: StateFlow<String> = apiKeyManager.backupKeyFlow

    fun setPrimaryApiKey(key: String) {
        apiKeyManager.setPrimaryApiKey(key)
    }

    fun setBackupApiKey(key: String) {
        apiKeyManager.setBackupApiKey(key)
    }

    fun toggleActiveKey() {
        apiKeyManager.toggleActiveKey()
    }

    fun resetToPrimaryApiKey() {
        apiKeyManager.resetToPrimary()
    }

    private var timerTickerJob: Job? = null
    private var activeGenerationJob: Job? = null
    private var uiMicDebounceJob: Job? = null
    private val voicePcmOutputStream = java.io.ByteArrayOutputStream()
    private val _lastAudioPacketTimestampMs = MutableStateFlow(System.currentTimeMillis())
    val lastAudioPacketTimestampMs: StateFlow<Long> = _lastAudioPacketTimestampMs.asStateFlow()

    init {
        com.example.voice.WakeWordVoiceService.onDiagnosticTraceCallback = { stage, details ->
            speechRecognitionCoordinator.recordTrace(stage, details)
        }
        com.example.util.AppSettingsManager.ensureInitialized(application)
        com.example.heartbeat.BackupReminderWorker.schedulePeriodicBackupReminder(application)
        snippetPlayer.setVolume(_masterVolume.value)
        streamingVoicePlayer.setVolume(_masterVolume.value)
        wakeWordManager.wakeWordSensitivity = _wakeWordSensitivity.value
        screenshotWatcher.startWatching()
        viewModelScope.launch(Dispatchers.IO) {
            repository.pruneLegacyAudioPlaceholders()
        }
        // Sync active chats with wake word manager, seed Room wake-word records, and populate base tracked seconds
        viewModelScope.launch {
            wakeWordRepository.ensureSeededFromChatsAndDefaults(
                chats = emptyList(),
                initialDictate = _dictateTrigger.value,
                initialSend = _sendTrigger.value,
                initialHeartbeat = _heartbeatTrigger.value,
                initialSensitivity = _wakeWordSensitivity.value
            )
            allChats.collect { chats ->
                if (chats.isNotEmpty()) {
                    wakeWordManager.updateActiveChats(chats)
                    wakeWordRepository.ensureSeededFromChatsAndDefaults(
                        chats = chats,
                        initialDictate = _dictateTrigger.value,
                        initialSend = _sendTrigger.value,
                        initialHeartbeat = _heartbeatTrigger.value,
                        initialSensitivity = _wakeWordSensitivity.value
                    )
                }
                for (chat in chats) {
                    loadWakeWordSamples(chat.id)
                }
                _liveTrackedSeconds.update { currentMap ->
                    val mutable = currentMap.toMutableMap()
                    for (chat in chats) {
                        if (!mutable.containsKey(chat.id)) {
                            mutable[chat.id] = chat.totalTrackedSeconds
                        }
                    }
                    mutable
                }
            }
        }

        // Hydrate and reactively sync Room-persisted wake words and hands-free triggers into WakeWordManager
        viewModelScope.launch {
            wakeWordRepository.allWakeWords.collect { allConfigured ->
                wakeWordManager.updateConfiguredWakeWords(
                    allConfigured.filter { it.triggerType == WakeWord.TYPE_CHAT }
                )
                allConfigured.firstOrNull {
                    it.triggerType == WakeWord.TYPE_HANDS_FREE && it.commandRole == WakeWord.ROLE_DICTATE
                }?.let { row ->
                    if (row.phrase.isNotBlank() && _dictateTrigger.value != row.phrase) {
                        _dictateTrigger.value = row.phrase
                        wakeWordManager.dictateTrigger = row.phrase
                        appPrefs.edit().putString("dictate_trigger", row.phrase).apply()
                    }
                }
                allConfigured.firstOrNull {
                    it.triggerType == WakeWord.TYPE_HANDS_FREE && it.commandRole == WakeWord.ROLE_SEND
                }?.let { row ->
                    if (row.phrase.isNotBlank() && _sendTrigger.value != row.phrase) {
                        _sendTrigger.value = row.phrase
                        wakeWordManager.sendTrigger = row.phrase
                        appPrefs.edit().putString("send_trigger", row.phrase).apply()
                    }
                }
                allConfigured.firstOrNull {
                    it.triggerType == WakeWord.TYPE_HANDS_FREE && it.commandRole == WakeWord.ROLE_HEARTBEAT
                }?.let { row ->
                    if (row.phrase.isNotBlank() && _heartbeatTrigger.value != row.phrase) {
                        _heartbeatTrigger.value = row.phrase
                        wakeWordManager.heartbeatTrigger = row.phrase
                        appPrefs.edit().putString("heartbeat_trigger", row.phrase).apply()
                    }
                }
                allConfigured.firstOrNull {
                    it.triggerType == WakeWord.TYPE_HANDS_FREE
                }?.sensitivity?.let { savedSens ->
                    val clamped = savedSens.coerceIn(0.50f, 0.98f)
                    if (_wakeWordSensitivity.value != clamped) {
                        _wakeWordSensitivity.value = clamped
                        wakeWordManager.wakeWordSensitivity = clamped
                        appPrefs.edit().putFloat("wake_word_sensitivity", clamped).apply()
                    }
                }
            }
        }

        // Keep memory buffer and passive Chrome Bridge staging synced with repository messages
        viewModelScope.launch {
            rawCurrentMessages.collect { messages ->
                _currentChatId.value?.let { id ->
                    bufferManager.updateFullMessageList(id, messages)
                    val activeChat = currentChat.value ?: allChats.value.firstOrNull { it.id == id }
                    stageChatAndUrlPassively(chat = activeChat, messages = messages)
                }
            }
        }

        // Wire wake turn audio handoff so buffered wake audio is prepended into the active chat turn
        wakeWordManager.onWakeAudioHandoff = { wakeBytes ->
            if (!WakeWordManager.isPcmByteBufferSilent(wakeBytes, wakeBytes.size)) {
                _lastAudioPacketTimestampMs.value = WakeWordManager.resolvePresentationTimestamp(System.currentTimeMillis())
                synchronized(voicePcmOutputStream) {
                    val existing = voicePcmOutputStream.toByteArray()
                    voicePcmOutputStream.reset()
                    val maxBytes = 16000 * 2 * 30
                    val wakeToWrite = wakeBytes.size.coerceAtMost(maxBytes)
                    voicePcmOutputStream.write(wakeBytes, 0, wakeToWrite)
                    if (existing.isNotEmpty() && !existing.contentEquals(wakeBytes)) {
                        val remaining = (maxBytes - voicePcmOutputStream.size()).coerceAtLeast(0)
                        if (remaining > 0) {
                            voicePcmOutputStream.write(existing, 0, existing.size.coerceAtMost(remaining))
                        }
                    }
                }
            }
        }

        // Synchronously reset stale draft, hung recognizers, and active voice state whenever a wake word hits
        // (even if the user is ALREADY in ChatDetailScreen or holding an unsent draft).
        // Do NOT assign recognized speech to _currentDraftText—keep the draft state and bottom input field blank.
        wakeWordManager.onStopSpeechRecognizerForWakeHandoff = {
            stopSpeechRecognizerInternal(markIntentionallyPaused = false)
            releaseAllNativeAudioRecordTracksForSpeechRecognizer()
            wakeWordManager.abandonExclusiveAudioFocus()
        }

        wakeWordManager.onWakeForceReset = { chatId, chatTitle, keyword, sanitizedQuery ->
            _isInChatDictationActive.value = false
            speechRecognitionCoordinator.setInChatDictationMode(false)
            stopSpeechRecognizerInternal(markIntentionallyPaused = false)
            releaseAllNativeAudioRecordTracksForSpeechRecognizer()
            wakeWordManager.abandonExclusiveAudioFocus()
            streamingVoicePlayer.stop()
            _currentDraftText.value = ""
            _wakeRetriggerEventId.update { it + 1L }
            val validWakeQuery = sanitizeValidWakeQuery(sanitizedQuery)
            _triggerBanner.value = TriggerBanner(
                chatTitle = chatTitle,
                matchedWord = keyword,
                query = validWakeQuery
            )
            if (chatId > 0L && _currentChatId.value != chatId) {
                selectChat(chatId)
            }
            if (validWakeQuery != null) {
                speechRecognitionCoordinator.setProcessingState(true)
                _lastDispatchedVoicePrompt.value = validWakeQuery
                _pendingWakeQuery.value = validWakeQuery
            } else {
                _pendingWakeQuery.value = null
            }
        }

        // Listen for wake word detections
        viewModelScope.launch {
            wakeWordManager.triggerEvents.collect { event ->
                // 1. The exact moment the wake word is detected, immediately invoke synchronous release
                //    BEFORE firing any navigation intent or state update to open the Chat screen.
                _isWakeHandoffInProgress.value = true
                com.example.voice.WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("wake_word_detected")
                stopSpeechRecognizerInternal(markIntentionallyPaused = false)
                audioRecorder.stopAndReleaseHardware()
                wakeWordManager.stopAndReleaseAudioRecordSynchronously()
                wakeWordManager.abandonExclusiveAudioFocus()

                if (event.capturedAudioBytes.isNotEmpty() &&
                    !WakeWordManager.isPcmByteBufferSilent(event.capturedAudioBytes, event.capturedAudioBytes.size)
                ) {
                    synchronized(voicePcmOutputStream) {
                        if (voicePcmOutputStream.size() == 0) {
                            voicePcmOutputStream.write(event.capturedAudioBytes)
                        }
                    }
                }
                val routingDecision = com.example.voice.WakeWordRoutingEngine.resolveTargetChatForWakeWord(
                    spokenWakePhrase = event.matchedWord,
                    chats = allChats.value,
                    configuredWakeWords = allWakeWords.value,
                    favoriteChatIds = favoriteChatIds.value,
                    contextualRoutingEnabled = contextualRoutingEnabled.value,
                    promptFavoritesOnGeneralWake = promptFavoritesOnWake.value,
                    chatCategoryMap = chatCategoryMap.value,
                    chatTagsMap = chatTagsMap.value,
                    spokenContextOrTranscript = event.initialQuery.orEmpty()
                )
                _matchedWakeCategory.value = routingDecision?.matchedCategory
                val resolvedChatId = resolveOrEnsureTargetChatId(
                    routingDecision?.targetChat?.id ?: event.chatId
                )
                val resolvedChatSession = repository.getChatByIdSync(resolvedChatId)
                    ?: routingDecision?.targetChat
                val resolvedChatTitle = resolvedChatSession?.title ?: event.chatTitle
                val routingReasonText = routingDecision?.routingReason
                    ?: "Opened via wake word \"${event.matchedWord}\""
                val routingSourceType = routingDecision?.routingSource
                    ?: com.example.voice.WakeRoutingSource.SPECIFIC_WAKE_WORD
                _lastDetectedWakePhrase.value = event.matchedWord

                if (routingDecision?.shouldPromptFavoritesSheet == true &&
                    event.initialQuery.isNullOrBlank() &&
                    isAppInForeground
                ) {
                    _showWakeFavoritesPickerDialog.value = true
                }

                // Build instant local conversation recap when opened via wake word
                val recentMsgs = withContext(Dispatchers.IO) {
                    messageRepository.getMessagesForChatSync(resolvedChatId)
                }
                if (resolvedChatSession != null) {
                    _wakeConversationSummary.value = com.example.voice.WakeWordRoutingEngine.buildInstantWakeConversationSummary(
                        chat = resolvedChatSession,
                        messages = recentMsgs,
                        matchedWakeWord = event.matchedWord,
                        routingSource = routingSourceType,
                        routingReason = routingReasonText
                    )
                }

                val inBackground = !isAppInForeground
                _wakeReadyVisualCue.value = WakeReadyCueState(
                    wakePhrase = event.matchedWord,
                    chatId = resolvedChatId,
                    chatTitle = resolvedChatTitle,
                    followUpQuery = event.initialQuery,
                    detectedInBackground = inBackground
                )
                // Only post an external PendingIntent notification when the app is genuinely in the background
                // so foreground voice navigation never triggers external activity intents.
                if (inBackground) {
                    val matchedWake = allWakeWords.value.firstOrNull {
                        it.phrase.equals(event.matchedWord, ignoreCase = true)
                    } ?: WakeWord(
                        phrase = event.matchedWord,
                        triggerType = TriggerType.CHAT,
                        chatId = resolvedChatId,
                        chatTitle = resolvedChatTitle,
                        sensitivity = _wakeWordSensitivity.value
                    )
                    com.example.voice.WakeWordVoiceService.postWakeWordReadyCueNotification(
                        context = getApplication(),
                        result = com.example.voice.WakeWordDetectionResult(
                            wakeWord = matchedWake,
                            matchedPhrase = event.matchedWord,
                            followUpQuery = event.initialQuery,
                            rawTranscript = event.initialQuery ?: event.matchedWord
                        )
                    )
                }
                val customWords = listOfNotNull(event.matchedWord.takeIf { it.isNotBlank() })
                val strippedCandidate = com.example.voice.AudioCommandSanitizer.stripWakeWordTrigger(
                    rawText = event.initialQuery ?: "",
                    customWakeWords = customWords
                )
                val sanitizedQuery = sanitizeValidWakeQuery(strippedCandidate)
                _triggerBanner.value = TriggerBanner(
                    chatTitle = resolvedChatTitle,
                    matchedWord = event.matchedWord,
                    query = sanitizedQuery
                )
                val transcriptText = sanitizedQuery
                    ?: "Wake phrase \"${event.matchedWord}\" recognized ($routingReasonText)"
                webLinkHistoryManager.recordVoiceCommand(
                    commandType = VoiceCommandType.WAKE_WORD,
                    triggerPhrase = event.matchedWord,
                    recognizedTranscript = transcriptText,
                    chatTitle = resolvedChatTitle
                )
                // 2. Only AFTER WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("wake_word_detected")
                //    has completed, select the corresponding chat to open the Chat screen.
                selectChat(resolvedChatId)

                // Check pendingWakeQuery / sanitizedQuery before dispatching to the chat session:
                // If empty, blank, or < 2 characters, discard completely instead of sending to Gemini.
                val alreadyDispatchedDirectly = sanitizedQuery != null &&
                    sanitizedQuery.equals(lastDirectVoiceDispatchPrompt, ignoreCase = true) &&
                    (System.currentTimeMillis() - lastDirectVoiceDispatchAtMs) < 2500L
                if (sanitizedQuery != null && !_isGenerating.value && !alreadyDispatchedDirectly) {
                    _pendingWakeQuery.value = sanitizedQuery
                    val verifiedPending = sanitizeValidWakeQuery(_pendingWakeQuery.value)
                    _pendingWakeQuery.value = null
                    if (verifiedPending != null) {
                        val handoffAudio = synchronized(voicePcmOutputStream) {
                            val bytes = voicePcmOutputStream.toByteArray()
                            voicePcmOutputStream.reset()
                            bytes
                        }.takeIf { WakeWordManager.shouldTransmitAudioPacket(it.size, it) }
                        releaseMicAndAudioFocusForWakeHandoffAndTts("wake_word_detected")
                        sendMessage(
                            chatId = resolvedChatId,
                            text = verifiedPending,
                            audioBytes = handoffAudio,
                            triggeredByWakeWord = true
                        )
                    } else {
                        _isWakeHandoffInProgress.value = false
                    }
                } else if (sanitizedQuery == null) {
                    _pendingWakeQuery.value = null
                    val customGreeting = resolvedChatSession?.greetingMessage?.trim().orEmpty()
                    if (customGreeting.isNotBlank() && _isAutoSpeakEnabled.value) {
                        com.example.voice.WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("tts_speaking")
                        releaseMicAndAudioFocusForWakeHandoffAndTts("tts_speaking")
                        speakMessage(customGreeting)
                    } else if (_isHandsFreeActive.value && !_isSleepMode.value) {
                        _isWakeHandoffInProgress.value = false
                        val context = getApplication<Application>()
                        if (android.speech.SpeechRecognizer.isRecognitionAvailable(context) && checkRecordAudioPermission()) {
                            wakeWordManager.isExternalSpeechRecognizerActive = true
                            releaseAllNativeAudioRecordTracksForSpeechRecognizer()
                            speechRecognitionCoordinator.setProcessingState(false)
                            speechRecognitionCoordinator.setIntentionallyPaused(false)
                            scheduleSpeechRecognizerRestart(delayMs = 500L, allowDuringUserVoiceTurn = true)
                        }
                    } else {
                        _isWakeHandoffInProgress.value = false
                    }
                } else {
                    _pendingWakeQuery.value = null
                    _isWakeHandoffInProgress.value = false
                }
            }
        }

        // Sync sleep mode changes from WakeWordManager and WakeWordVoiceService notification actions
        wakeWordManager.onSleepModeChanged = { sleeping, reason ->
            _isSleepMode.value = sleeping
            _sleepModeReason.value = if (sleeping) {
                reason.ifBlank { "Sleep Mode active — tap Wake Up or say \"Gemini, wake up\"" }
            } else {
                ""
            }
        }

        com.example.voice.WakeWordVoiceService.onSleepModeChangedCallback = { sleeping ->
            _isSleepMode.value = sleeping
            if (!sleeping) {
                _sleepModeReason.value = ""
                wakeWordManager.exitSleepMode("Resumed from notification")
            } else {
                if (_sleepModeReason.value.isBlank()) {
                    _sleepModeReason.value = "Sleep Mode active — tap Wake Up or say \"Gemini, wake up\""
                }
                wakeWordManager.enterSleepMode(_sleepModeReason.value)
            }
        }

        com.example.voice.WakeWordVoiceService.onWakeWordDetectedCallback = { match ->
            _isWakeHandoffInProgress.value = true
            com.example.voice.WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("wake_word_detected")
            stopSpeechRecognizerInternal(markIntentionallyPaused = false)
            audioRecorder.stopAndReleaseHardware()
            wakeWordManager.stopAndReleaseAudioRecordSynchronously()
            wakeWordManager.abandonExclusiveAudioFocus()
            wakeWordManager.onKeywordHit(
                keyword = match.matchedPhrase,
                initialQuery = match.followUpQuery,
                targetChatIdOverride = match.wakeWord.chatId
            )
        }

        com.example.voice.WakeWordVoiceService.onVoiceHistoryNavigationRequestedCallback = { transcript ->
            dispatchVoiceNavigationCommand(route = "history", rawTranscript = transcript)
        }

        com.example.voice.WakeWordVoiceService.onBatterySaverResumeRequestedCallback = {
            if (_isHandsFreeActive.value && !_isSleepMode.value) {
                audioRecorder.setIntentionallyPaused(false)
                speechRecognitionCoordinator.setIntentionallyPaused(false)
                com.example.voice.WakeWordVoiceService.setIntentionallyPaused(false)
                wakeWordManager.setStatusMessage("Resumed from Battery Saver pause")
                startContinuousWakeWordListening()
            }
        }

        com.example.voice.BatterySaverMonitorWorker.registerVoiceComponents(
            coordinator = speechRecognitionCoordinator,
            audioRecorder = audioRecorder,
            activeUseChecker = {
                isUserVoiceTurn.value ||
                    _isGenerating.value ||
                    isOrionVoicePlaying.value ||
                    isChatTurnRecording.value ||
                    _activeRecordingSlot.value != null
            },
            onStateChanged = { pausedByBatterySaver ->
                if (pausedByBatterySaver) {
                    wakeWordManager.isExternalSpeechRecognizerActive = false
                    wakeWordManager.stopContinuousListening()
                    wakeWordManager.setStatusMessage("Battery Saver active — microphone paused to extend battery life")
                } else if (_isHandsFreeActive.value && !_isSleepMode.value) {
                    wakeWordManager.setStatusMessage("Battery Saver off — resuming wake-word listening")
                    startContinuousWakeWordListening()
                }
            }
        )
        com.example.voice.BatterySaverMonitorWorker.schedulePeriodicWork(application)

        // Wire AudioRecord PCM stream (16000Hz, MONO, PCM_16BIT, VOICE_RECOGNITION with MIC fallback)
        // and Auto-Silence Cutoff into Gemini Native Audio pipeline
        wakeWordManager.onAudioPacketCaptured = { packet ->
            if (!WakeWordManager.isPcmByteBufferSilent(packet.pcmBytes, packet.bytesRead)) {
                _lastAudioPacketTimestampMs.value = WakeWordManager.resolvePresentationTimestamp(packet.presentationTimestampMs)
                synchronized(voicePcmOutputStream) {
                    if (voicePcmOutputStream.size() < 16000 * 2 * 30) { // Max 30s buffer
                        voicePcmOutputStream.write(packet.pcmBytes, 0, packet.bytesRead.coerceAtMost(packet.pcmBytes.size))
                    }
                }
            }
        }

        wakeWordManager.onAudioFrameCaptured = { pcmChunk ->
            if (wakeWordManager.onAudioPacketCaptured == null &&
                !WakeWordManager.isPcmByteBufferSilent(pcmChunk, pcmChunk.size)
            ) {
                _lastAudioPacketTimestampMs.value = WakeWordManager.resolvePresentationTimestamp(System.currentTimeMillis())
                synchronized(voicePcmOutputStream) {
                    if (voicePcmOutputStream.size() < 16000 * 2 * 30) { // Max 30s buffer
                        voicePcmOutputStream.write(pcmChunk)
                    }
                }
            }
        }

        wakeWordManager.onSpeechStarted = {
            // Preserve already-captured non-zero PCM samples in voicePcmOutputStream
            wakeWordManager.setStatusMessage("Capturing speech...")
        }

        wakeWordManager.onSilenceCutoffDetected = {
            sendAudioTurn()
        }

        streamingVoicePlayer.onPlaybackCompleted = {
            if (_isHandsFreeActive.value && !_isGenerating.value) {
                wakeWordManager.restartWakeListeningAfterTurn()
            }
        }

        // Wire Hands-Free triggers and callbacks into WakeWordManager
        wakeWordManager.dictateTrigger = _dictateTrigger.value
        wakeWordManager.sendTrigger = _sendTrigger.value
        wakeWordManager.heartbeatTrigger = _heartbeatTrigger.value
        if (android.speech.SpeechRecognizer.isRecognitionAvailable(application)) {
            wakeWordManager.isExternalSpeechRecognizerActive = true
        }
        wakeWordManager.setHandsFreeMode(_isHandsFreeActive.value)

        wakeWordManager.onHandsFreePartialText = { partialText ->
            if (_isInChatDictationActive.value &&
                _currentChatId.value != null &&
                !com.example.voice.AudioCommandSanitizer.isSystemOrCommandStatusString(
                    text = partialText,
                    dictateTrigger = _dictateTrigger.value,
                    sendTrigger = _sendTrigger.value,
                    heartbeatTrigger = _heartbeatTrigger.value
                )
            ) {
                _currentDraftText.value = partialText
            }
        }

        wakeWordManager.onHandsFreeSend = { scrubbedUtterance ->
            val textToSend = if (scrubbedUtterance.isNotBlank()) scrubbedUtterance else _currentDraftText.value
            val cleaned = com.example.voice.AudioCommandSanitizer.sanitizeSpokenText(
                text = textToSend,
                sendTrigger = _sendTrigger.value,
                heartbeatTrigger = _heartbeatTrigger.value
            )
            if (cleaned.isNotBlank() &&
                !com.example.voice.AudioCommandSanitizer.isSystemOrCommandStatusString(
                    text = cleaned,
                    dictateTrigger = _dictateTrigger.value,
                    sendTrigger = _sendTrigger.value,
                    heartbeatTrigger = _heartbeatTrigger.value
                )
            ) {
                viewModelScope.launch {
                    val targetChatId = resolveOrEnsureTargetChatId(_currentChatId.value)
                    selectChat(targetChatId)
                    webLinkHistoryManager.recordVoiceCommand(
                        commandType = VoiceCommandType.VOICE_SEND,
                        triggerPhrase = _sendTrigger.value,
                        recognizedTranscript = cleaned,
                        chatTitle = currentChat.value?.title
                    )
                    _currentDraftText.value = ""
                    val bufferedAudio = synchronized(voicePcmOutputStream) {
                        val b = voicePcmOutputStream.toByteArray()
                        voicePcmOutputStream.reset()
                        b
                    }.takeIf { WakeWordManager.shouldTransmitAudioPacket(it.size, it) }
                    sendMessage(targetChatId, cleaned, audioBytes = bufferedAudio, triggeredByWakeWord = true)
                }
            }
        }

        wakeWordManager.onHandsFreeHeartbeat = {
            _lastHeartbeatPingTime.value = System.currentTimeMillis()
            webLinkHistoryManager.recordVoiceCommand(
                commandType = VoiceCommandType.HEARTBEAT,
                triggerPhrase = _heartbeatTrigger.value,
                recognizedTranscript = "Hands-free heartbeat ping command recognized",
                chatTitle = currentChat.value?.title
            )
            com.example.coupler.ChromeBridgeSocketManager.recordHeartbeatPing()
            com.example.heartbeat.HeartbeatAlarmReceiver.scheduleHeartbeatPing(getApplication(), 0L)
        }

        // Barge-In Interruption: user speaking wake or dictate words immediately halts TTS and Orion audio
        wakeWordManager.onBargeInDetected = {
            if (textToSpeechManager.isSpeaking.value) {
                textToSpeechManager.bargeInAndStop()
            }
            if (streamingVoicePlayer.isPlaying.value) {
                streamingVoicePlayer.stop()
            }
        }

        // Wire audio focus changes to SpeechRecognitionCoordinator (ignore CAN_DUCK, 500ms debounce on GAIN)
        wakeWordManager.onAudioFocusChangeCallback = { focusChange ->
            speechRecognitionCoordinator.handleAudioFocusChange(focusChange)
        }

        // Start the background sync relay server and local Chrome Bridge socket for passive staging
        localSyncServer.setChatDataProvider {
            val activeChat = currentChat.value ?: allChats.value.firstOrNull { it.id == _currentChatId.value }
            activeChat to rawCurrentMessages.value
        }
        localSyncServer.start(8765)
        ChromeBridgeSocketManager.start(application)

        // Observe progress and results from both direct ChatImportWorker and background FileProcessingService
        viewModelScope.launch {
            chatImportWorker.progressState.collect { state ->
                _importProgressState.value = state
            }
        }
        viewModelScope.launch {
            FileProcessingService.serviceProgressState.collect { state ->
                if (state.isImporting || state.statusMessage.isNotBlank() || state.error != null) {
                    _importProgressState.value = state
                }
            }
        }
        viewModelScope.launch {
            FileProcessingService.completedImportChatId.collect { importedChatId ->
                if (importedChatId != null && importedChatId > 0L) {
                    selectChat(importedChatId)
                    FileProcessingService.consumeCompletedImportChatId()
                }
            }
        }
        viewModelScope.launch {
            FileProcessingService.stagedAttachmentFlow.collect { payload ->
                if (payload != null) {
                    _stagedAttachment.value = payload
                    FileProcessingService.consumeStagedAttachment()
                }
            }
        }
    }

    fun selectChat(chatId: Long?) {
        val prevChatId = _currentChatId.value
        val isSwitching = prevChatId != chatId

        // Immediately clear _pendingWakeQuery and unsolicited summary overlays when entering/selecting a chat
        _pendingWakeQuery.value = null
        _wakeConversationSummary.value = null

        if (isSwitching) {
            // Cancel active generation & streaming text immediately so old session state doesn't bleed
            activeGenerationJob?.cancel()
            activeGenerationJob = null
            _isGenerating.value = false
            _streamingText.value = null
            _errorMessage.value = null
            streamingVoicePlayer.stop()

            // Detach and purge in-memory buffers for previous chat
            bufferManager.detachChat()
            _isInChatDictationActive.value = false
            speechRecognitionCoordinator.setInChatDictationMode(false)
            _currentDraftText.value = ""

            // Lifecycle Hook: Force reset and clear ChromeBridgeSocketManager on session switch or close
            ChromeBridgeSocketManager.forceResetAndClear(getApplication(), preserveDiskCache = _preserveCacheAcrossSessions.value)
        }

        _currentChatId.value = chatId

        if (chatId != null) {
            // Chat is OPENED: Start open-chat timer. It only persists as long as the chat is open.
            // It ticks continuously while open whether user is active, speaking, or idle (not necessarily active).
            _openChatTimerSeconds.value = 0L
            _isOpenChatTimerRunning.value = true
            _activeTimerChatIds.value = setOf(chatId)
            _liveTrackedSeconds.update { it + (chatId to 0L) }
            startTickerIfNeeded()
            loadAdventureStateForChat(chatId)

            viewModelScope.launch(Dispatchers.IO) {
                if (_cloudBackupChats.value.isNotEmpty()) {
                    val existingLocal = repository.getChatByIdSync(chatId)
                    if (existingLocal == null) {
                        val candidateFromCloud = _cloudBackupChats.value.firstOrNull {
                            it.localChatId == chatId || it.toChatSession().id == chatId
                        }
                        if (candidateFromCloud != null) {
                            repository.upsertRemoteChat(candidateFromCloud.toChatSession().copy(id = chatId, isDeleted = false))
                            val cloudMsgs = _cloudBackupMessages.value.filter { it.localChatId == chatId }
                            for (msgDoc in cloudMsgs) {
                                val chatMsg = msgDoc.toChatMessage().copy(chatId = chatId, isDeleted = false)
                                if (chatMsg.content.isNotBlank()) {
                                    messageRepository.upsertRemoteMessage(chatMsg)
                                }
                            }
                        }
                    }
                }
                bufferManager.preloadAndAttachChat(chatId)
                val targetChat = repository.getChatByIdSync(chatId) ?: allChats.value.firstOrNull { it.id == chatId }
                val msgs = messageRepository.getMessagesForChatSync(chatId).filter { !it.isDeleted }
                stageChatAndUrlPassively(chat = targetChat, messages = msgs)
            }
        } else {
            // Chat is CLOSED: Reset and cancel open chat timer so it only persists as long as the chat is open
            _isOpenChatTimerRunning.value = false
            _openChatTimerSeconds.value = 0L
            _activeTimerChatIds.value = emptySet()
            if (prevChatId != null) {
                _liveTrackedSeconds.update { it + (prevChatId to 0L) }
                viewModelScope.launch {
                    repository.updateTrackedSeconds(prevChatId, 0L)
                }
            }
        }
    }

    /**
     * Purges active view model session state and reloads the fresh message thread for a chat.
     */
    fun purgeActiveSessionState(chatId: Long) {
        if (_currentChatId.value == chatId) {
            _streamingText.value = null
            _errorMessage.value = null
            activeGenerationJob?.cancel()
            activeGenerationJob = null
            _isGenerating.value = false
            bufferManager.detachChat()
            ChromeBridgeSocketManager.forceResetAndClear(getApplication(), preserveDiskCache = _preserveCacheAcrossSessions.value)
            viewModelScope.launch {
                bufferManager.preloadAndAttachChat(chatId)
            }
        }
    }

    fun loadOlderMessages() {
        viewModelScope.launch {
            bufferManager.loadOlderMessagesChunk()
        }
    }

    fun setBufferSizeLimit(limit: Int) {
        bufferManager.setBufferSizeLimit(limit)
    }

    /**
     * Clears the active in-memory hot window (`ChatMemoryBufferManager.clear()`) without deleting Room database records.
     */
    fun clearActiveInMemoryBuffer() {
        bufferManager.clear()
    }

    /**
     * Alias for clearing the active in-memory RAM window (`ChatMemoryBufferManager.clear()`).
     */
    fun clearActiveRamWindow() {
        bufferManager.clear()
    }

    /**
     * Purges staged ephemeral disk cache files and resets system hygiene (`forceResetAndClear()`).
     */
    fun forceResetAndClear(): com.example.util.HygieneResetResult {
        bufferManager.forceResetAndClear()
        return forceResetAndClearSystemHygiene()
    }

    fun purgeDiskCache() {
        _currentChatId.value?.let { id ->
            bufferManager.clearDiskCache(id)
        }
    }

    fun clearIncomingDiskCache() {
        _currentChatId.value?.let { id ->
            bufferManager.clearIncomingDiskCache(id)
        }
    }

    fun clearOutgoingDiskCache() {
        _currentChatId.value?.let { id ->
            bufferManager.clearOutgoingDiskCache(id)
        }
    }

    fun openCurrentChatInChrome() {
        val chat = currentChat.value ?: allChats.value.firstOrNull { it.id == _currentChatId.value } ?: return
        val messages = rawCurrentMessages.value
        stageChatAndUrlPassively(chat = chat, messages = messages)
    }

    fun getChromeBridgePayload(): String {
        val chat = currentChat.value ?: return "{}"
        val messages = rawCurrentMessages.value
        return chromeBridge.createBridgePayload(chat, messages)
    }

    fun importFromChrome(payload: String, onComplete: ((Boolean) -> Unit)? = null) {
        val parsed = chromeBridge.parseBridgePayload(payload)
        if (parsed == null) {
            onComplete?.invoke(false)
            return
        }

        viewModelScope.launch {
            val chatId = _currentChatId.value ?: return@launch
            val existing = rawCurrentMessages.value
            val existingTimestamps = existing.map { it.timestamp }.toSet()

            // Merge newly added messages from Chrome
            for (newMsg in parsed.messages) {
                val resolvedTs = WakeWordManager.resolvePresentationTimestamp(newMsg.timestamp)
                if (!existingTimestamps.contains(resolvedTs)) {
                    repository.insertMessage(
                        ChatMessage(
                            chatId = chatId,
                            sender = newMsg.sender,
                            content = newMsg.content,
                            timestamp = resolvedTs,
                            triggeredByWakeWord = newMsg.triggeredByWakeWord
                        )
                    )
                }
            }
            onComplete?.invoke(true)
        }
    }

    fun toggleAutoSpeak() {
        _isAutoSpeakEnabled.value = !_isAutoSpeakEnabled.value
        if (!_isAutoSpeakEnabled.value) {
            streamingVoicePlayer.stop()
        }
    }

    private val _stagedAttachment = MutableStateFlow<AttachmentPayload?>(null)
    val stagedAttachment: StateFlow<AttachmentPayload?> = _stagedAttachment.asStateFlow()

    // Dedicated attachedImageUri StateFlow for picked or clipboard-pasted images in the chat composer
    private val _attachedImageUri = MutableStateFlow<Uri?>(null)
    val attachedImageUri: StateFlow<Uri?> = _attachedImageUri.asStateFlow()

    fun stageAttachedImageUri(uri: Uri?, context: android.content.Context = getApplication()) {
        if (uri == null) {
            clearAttachedImage()
            return
        }
        _attachedImageUri.value = uri
        stageFile(uri, context)
    }

    fun clearAttachedImage() {
        _attachedImageUri.value = null
        if (_stagedAttachment.value?.mimeType?.startsWith("image/") == true) {
            _stagedAttachment.value = null
        }
    }

    // Inspects Android's ClipboardManager.primaryClip for an image MIME type (image/png, image/jpeg).
    // If found, grabs the URI, stages it in attachedImageUri, and populates stagedAttachment.
    fun checkAndStageClipboardImage(context: android.content.Context = getApplication()): Boolean {
        return try {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                ?: return false
            val clip = clipboard.primaryClip ?: return false
            if (clip.itemCount <= 0) return false
            val desc = clip.description
            val hasImageMime = (0 until desc.mimeTypeCount).any { idx ->
                val mime = desc.getMimeType(idx).lowercase(java.util.Locale.US)
                mime == "image/png" || mime == "image/jpeg" || mime == "image/jpg" || mime.startsWith("image/")
            }
            for (i in 0 until clip.itemCount) {
                val itemUri = clip.getItemAt(i)?.uri ?: continue
                val resolvedMime = context.contentResolver.getType(itemUri)?.lowercase(java.util.Locale.US).orEmpty()
                if (hasImageMime || resolvedMime == "image/png" || resolvedMime == "image/jpeg" || resolvedMime.startsWith("image/")) {
                    if (_attachedImageUri.value != itemUri) {
                        stageAttachedImageUri(itemUri, context)
                    }
                    return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun stageFile(uri: Uri, context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val contentResolver = context.contentResolver
                var fileName = "attachment"
                val rawMimeType = contentResolver.getType(uri)
                val mimeType = when {
                    !rawMimeType.isNullOrBlank() -> rawMimeType
                    uri.toString().endsWith(".png", ignoreCase = true) -> "image/png"
                    uri.toString().endsWith(".jpg", ignoreCase = true) ||
                        uri.toString().endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
                    else -> "image/jpeg"
                }
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            val name = cursor.getString(nameIndex)
                            if (!name.isNullOrBlank()) fileName = name
                        }
                    }
                }
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: byteArrayOf()
                val sizeBytes = bytes.size.toLong()
                if (mimeType.startsWith("image/")) {
                    _attachedImageUri.value = uri
                    if (fileName == "attachment") {
                        fileName = "attached_image.jpg"
                    }
                }
                _stagedAttachment.value = AttachmentPayload(uri, fileName, mimeType, bytes, sizeBytes)
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to stage file: ${e.message}", e)
            }
        }
    }

    fun stageAttachment(payload: AttachmentPayload) {
        _stagedAttachment.value = payload
        if (payload.mimeType.startsWith("image/")) {
            _attachedImageUri.value = payload.uri
        }
    }

    fun clearStagedAttachment() {
        _stagedAttachment.value = null
        _attachedImageUri.value = null
    }

    fun clearTriggerBanner() {
        _triggerBanner.value = null
    }

    val recordingAudioSource: Int
        get() = wakeWordManager.audioSource

    val isLiveDuplexMode: StateFlow<Boolean> = wakeWordManager.isLiveDuplexMode

    fun setLiveDuplexMode(enabled: Boolean) {
        wakeWordManager.setLiveDuplexMode(enabled)
        if (enabled && !_isHandsFreeActive.value) {
            toggleHandsFreeMode(true)
        }
    }

    fun calculateRecordingBufferSize(): Int {
        val minBuf = android.media.AudioRecord.getMinBufferSize(
            16000,
            android.media.AudioFormat.CHANNEL_IN_MONO,
            android.media.AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf * 2, 4096)
        return bufferSize
    }

    fun onTextInputFocusChanged(focused: Boolean) {
        wakeWordManager.onTextInputFocusChanged(focused)
        if (!focused && _isHandsFreeActive.value && !isUserVoiceTurn.value && checkRecordAudioPermission()) {
            scheduleSpeechRecognizerRestart(delayMs = 200L)
        }
    }

    /**
     * Returns a snapshot of the currently buffered PCM bytes for the active voice turn.
     */
    fun getBufferedVoiceTurnBytes(): ByteArray {
        synchronized(voicePcmOutputStream) {
            return voicePcmOutputStream.toByteArray()
        }
    }

    /**
     * Resolves an existing chat ID or creates a default Gemini chat session if none exists,
     * ensuring hands-free voice turns always route into a valid active chat session.
     */
    private suspend fun resolveOrEnsureTargetChatId(preferredChatId: Long? = null): Long {
        if (preferredChatId != null && preferredChatId > 0L) {
            val existing = repository.getChatByIdSync(preferredChatId)
            if (existing != null) return existing.id
        }
        val currentId = _currentChatId.value
        if (currentId != null && currentId > 0L) {
            val existing = repository.getChatByIdSync(currentId)
            if (existing != null) return existing.id
        }
        val cachedDefault = allChats.value.firstOrNull { it.isDefaultWakeChat } ?: allChats.value.firstOrNull()
        if (cachedDefault != null && cachedDefault.id > 0L) {
            return cachedDefault.id
        }
        val defaultSession = ChatSession(
            title = "Gemini Assistant",
            wakeWord = "Gemini",
            systemInstruction = "You are Gemini, an intelligent and helpful AI assistant.",
            themeColorHex = "#38BDF8",
            iconName = "Sparkles",
            isDefaultWakeChat = true,
            isPersistent = _defaultPersistenceEnabled.value
        )
        return repository.saveChat(defaultSession)
    }

    /**
     * Toggles in-chat voice dictation strictly via the internal [speechRecognitionCoordinator]
     * (`stopListening()` when listening, `startListening()` when idle) with zero external intents
     * and zero `startActivity` calls, using the app's existing audio permissions.
     */
    fun toggleInternalDictation(manualDraftText: String = ""): Boolean {
        return if (_isInChatDictationActive.value || speechRecognitionCoordinator.isInChatDictationMode) {
            uiMicDebounceJob?.cancel()
            uiMicDebounceJob = null
            val capturedSpeech = manualDraftText.trim()
                .ifBlank { _currentDraftText.value.trim() }
                .ifBlank { wakeWordManager.lastHeardText.value.trim() }
            _isInChatDictationActive.value = false
            speechRecognitionCoordinator.setInChatDictationMode(false)
            wakeWordManager.setUserVoiceTurn(false)
            speechRecognitionCoordinator.stopListening(markIntentionallyPaused = !_isHandsFreeActive.value)
            _currentDraftText.value = ""
            wakeWordManager.clearLastHeardText()
            if (capturedSpeech.isNotBlank()) {
                val activeWakeWords = (
                    allChats.value.mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } } +
                        wakeWordManager.getActiveChats().mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } } +
                        allWakeWords.value.filter { it.isEnabled }.map { it.phrase }
                    ).distinct()
                val (_, scrubbed) = com.example.voice.SpeechRecognitionCoordinator.extractWakeTriggeredTranscript(
                    rawTranscript = capturedSpeech,
                    customWakeWords = activeWakeWords
                )
                val cleanPrompt = sanitizeValidWakeQuery(scrubbed.trim().ifBlank { capturedSpeech })
                if (cleanPrompt != null) {
                    _lastDispatchedVoicePrompt.value = cleanPrompt
                    if (!executeInChatVoiceActionCommand(cleanPrompt, _currentChatId.value)) {
                        sendMessage(text = cleanPrompt, triggeredByWakeWord = true)
                    }
                }
            }
            if (_isHandsFreeActive.value && !_isSleepMode.value) {
                com.example.voice.WakeWordVoiceService.resumeAfterInternalDictationInMemory()
                speechRecognitionCoordinator.scheduleDebouncedRestart(
                    delayMs = com.example.voice.SpeechRecognitionCoordinator.DEFAULT_RESTART_DEBOUNCE_MS
                )
            }
            false
        } else {
            if (!checkRecordAudioPermission()) {
                pendingInChatDictationAfterPermission = true
                _requiresAudioPermission.value = true
                wakeWordManager.setStatusMessage("Microphone permission required for in-chat voice dictation")
                return false
            }
            if (!_hasWindowFocus.value || !speechRecognitionCoordinator.isWindowFocused) {
                return false
            }
            if (textToSpeechManager.isSpeaking.value) {
                textToSpeechManager.bargeInAndStop()
            }
            if (streamingVoicePlayer.isPlaying.value) {
                streamingVoicePlayer.stop()
            }
            // Exclusive mutex handoff: synchronously stop and release WakeWordVoiceService's AudioRecord
            // alongside AudioRecorder and WakeWordManager BEFORE invoking SpeechRecognizer.startListening().
            com.example.voice.WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("ui_dictation_button_handoff")
            com.example.voice.WakeWordVoiceService.pauseForInternalDictationInMemory()
            wakeWordManager.stopContinuousListening()
            releaseAllNativeAudioRecordTracksForSpeechRecognizer()
            wakeWordManager.abandonExclusiveAudioFocus()
            if (speechRecognitionCoordinator.isListening.value) {
                speechRecognitionCoordinator.stopListening(markIntentionallyPaused = false)
            }
            _pendingWakeQuery.value = null
            _currentDraftText.value = ""
            wakeWordManager.clearLastHeardText()
            _isInChatDictationActive.value = true
            wakeWordManager.setUserVoiceTurn(true)
            speechRecognitionCoordinator.setProcessingState(false)
            speechRecognitionCoordinator.setIntentionallyPaused(false)
            speechRecognitionCoordinator.setInChatDictationMode(true)

            uiMicDebounceJob?.cancel()
            uiMicDebounceJob = viewModelScope.launch(Dispatchers.Main.immediate) {
                val debounceStartEpochMs = System.currentTimeMillis()
                speechRecognitionCoordinator.recordTrace(
                    stage = "UI_MIC_DEBOUNCE_START",
                    details = "delayMs=300 startEpochMs=$debounceStartEpochMs serviceNullifiedAtEpochMs=${com.example.voice.WakeWordVoiceService.lastAudioRecordNullifiedAtEpochMs} waiting for hardware lock release"
                )
                kotlinx.coroutines.delay(300L)
                val debounceEndEpochMs = System.currentTimeMillis()
                val elapsedMs = debounceEndEpochMs - debounceStartEpochMs
                speechRecognitionCoordinator.recordTrace(
                    stage = "UI_MIC_DEBOUNCE_END",
                    details = "delayMs=300 elapsedMs=${elapsedMs}ms endEpochMs=$debounceEndEpochMs hardware lock wait complete"
                )
                if (_isInChatDictationActive.value && speechRecognitionCoordinator.isInChatDictationMode) {
                    speechRecognitionCoordinator.startListening(userInitiated = true)
                }
            }
            true
        }
    }

    /**
     * Alias for [toggleInternalDictation] preserving backward compatibility across internal call sites.
     */
    fun toggleInChatDictation(manualDraftText: String = ""): Boolean {
        return toggleInternalDictation(manualDraftText)
    }

    /**
     * Starts or toggles an in-chat dictation turn via the in-process [speechRecognitionCoordinator].
     */
    fun startVoiceRecording() {
        if (!_isInChatDictationActive.value) {
            toggleInternalDictation()
        }
    }

    /**
     * Cancels the active voice recording / in-chat dictation turn, releases the chat-turn AudioRecord,
     * discards buffered PCM audio, and restarts passive wake listening if hands-free is active.
     */
    fun cancelVoiceRecording() {
        _isInChatDictationActive.value = false
        speechRecognitionCoordinator.setInChatDictationMode(false)
        speechRecognitionCoordinator.stopListening(markIntentionallyPaused = !_isHandsFreeActive.value)
        wakeWordManager.setUserVoiceTurn(false)
        wakeWordManager.completeChatTurnAndReleaseMic()
        _currentDraftText.value = ""
        wakeWordManager.clearLastHeardText()
        synchronized(voicePcmOutputStream) {
            voicePcmOutputStream.reset()
        }
        if (_isHandsFreeActive.value && !_isSleepMode.value) {
            scheduleSpeechRecognizerRestart(
                delayMs = com.example.voice.SpeechRecognitionCoordinator.DEFAULT_RESTART_DEBOUNCE_MS,
                allowDuringUserVoiceTurn = true
            )
            wakeWordManager.restartWakeListeningAfterTurn()
        }
    }

    /**
     * Manually finishes the active voice recording turn (or active SpeechRecognizer session),
     * grabs whatever partial/final transcription exists in the buffer, and immediately dispatches it to Gemini.
     * Aborts the turn with a brief notice if the captured buffer is empty or silent and no text was entered.
     */
    fun finishVoiceRecordingAndSend(manualPromptText: String = "") {
        _isInChatDictationActive.value = false
        speechRecognitionCoordinator.setInChatDictationMode(false)
        stopSpeechRecognizerInternal()
        wakeWordManager.setUserVoiceTurn(false)
        wakeWordManager.completeChatTurnAndReleaseMic()
        val rawBytes: ByteArray
        synchronized(voicePcmOutputStream) {
            rawBytes = voicePcmOutputStream.toByteArray()
            voicePcmOutputStream.reset()
        }
        val hasValidAudio = WakeWordManager.shouldTransmitAudioPacket(rawBytes.size, rawBytes)
        val activeWakeWords = (allChats.value + wakeWordManager.getActiveChats())
            .mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } }
            .distinct()
        val bufferedDraft = _currentDraftText.value.trim()
        val bufferedHeard = wakeWordManager.lastHeardText.value.trim()
        val rawCandidateText = manualPromptText.trim()
            .ifBlank { bufferedDraft }
            .ifBlank { bufferedHeard }
        val sanitizedPrompt = com.example.voice.AudioCommandSanitizer.stripWakeWordTrigger(
            rawText = rawCandidateText,
            customWakeWords = activeWakeWords
        )
        _currentDraftText.value = ""
        wakeWordManager.clearLastHeardText()

        if (!hasValidAudio && sanitizedPrompt.isEmpty()) {
            val notice = "No speech detected — voice turn cancelled"
            wakeWordManager.setStatusMessage(notice)
            _errorMessage.value = notice
            if (_isHandsFreeActive.value) {
                wakeWordManager.restartWakeListeningAfterTurn()
            }
            return
        }

        if (sanitizedPrompt.isNotBlank()) {
            _lastDispatchedVoicePrompt.value = sanitizedPrompt
        }

        val packetTimestamp = WakeWordManager.resolvePresentationTimestamp(System.currentTimeMillis())
        _lastAudioPacketTimestampMs.value = packetTimestamp

        viewModelScope.launch {
            val targetChatId = resolveOrEnsureTargetChatId(_currentChatId.value)
            if (_currentChatId.value != targetChatId) {
                selectChat(targetChatId)
            }
            val targetChat = repository.getChatByIdSync(targetChatId)
                ?: allChats.value.firstOrNull { it.id == targetChatId }
            val chatWakeWords = (listOfNotNull(targetChat?.wakeWord?.takeIf { it.isNotBlank() }) + activeWakeWords).distinct()
            val cleanedPrompt = com.example.voice.AudioCommandSanitizer.stripWakeWordTrigger(
                rawText = sanitizedPrompt,
                customWakeWords = chatWakeWords
            )

            var resolvedText = cleanedPrompt
            if (resolvedText.isBlank() && hasValidAudio) {
                val transcribedRaw = try {
                    geminiService.transcribeAudioSnippet(rawBytes, "audio/pcm").trim()
                } catch (_: Exception) {
                    ""
                }
                resolvedText = com.example.voice.AudioCommandSanitizer.stripWakeWordTrigger(
                    rawText = transcribedRaw,
                    customWakeWords = chatWakeWords
                )
            }
            if (resolvedText.isNotBlank()) {
                webLinkHistoryManager.recordVoiceCommand(
                    commandType = VoiceCommandType.VOICE_TURN,
                    triggerPhrase = targetChat?.wakeWord?.ifBlank { "Voice Turn" } ?: "Voice Turn",
                    recognizedTranscript = resolvedText,
                    chatTitle = targetChat?.title
                )
            }

            sendMessage(
                chatId = targetChatId,
                text = resolvedText,
                audioBytes = if (hasValidAudio) rawBytes else null,
                triggeredByWakeWord = true
            )
        }
    }

    /**
     * Dispatches the captured PCM audio buffer from WakeWordManager to Gemini for the active chat turn.
     * Aborts the turn with a brief notice if bytesRead <= 0 or if the buffer is silent/empty.
     */
    fun sendAudioTurn(chatId: Long? = _currentChatId.value) {
        wakeWordManager.setUserVoiceTurn(false)
        releaseMicAndAudioFocusForWakeHandoffAndTts("send_audio_turn")
        val capturedBytes: ByteArray
        synchronized(voicePcmOutputStream) {
            capturedBytes = voicePcmOutputStream.toByteArray()
            voicePcmOutputStream.reset()
        }
        val hasValidAudio = WakeWordManager.shouldTransmitAudioPacket(capturedBytes.size, capturedBytes)
        val activeWakeWords = allChats.value.mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } }
        val draftText = com.example.voice.AudioCommandSanitizer.stripWakeWordTrigger(
            rawText = _currentDraftText.value,
            customWakeWords = activeWakeWords
        )
        _currentDraftText.value = ""
        wakeWordManager.clearLastHeardText()

        if (!hasValidAudio && draftText.isEmpty()) {
            val notice = "No speech detected — voice turn cancelled"
            wakeWordManager.setStatusMessage(notice)
            if (_isHandsFreeActive.value) {
                wakeWordManager.restartWakeListeningAfterTurn()
            }
            return
        }

        val packetTimestamp = WakeWordManager.resolvePresentationTimestamp(System.currentTimeMillis())
        _lastAudioPacketTimestampMs.value = packetTimestamp

        viewModelScope.launch {
            val targetChatId = resolveOrEnsureTargetChatId(chatId ?: _currentChatId.value)
            if (_currentChatId.value != targetChatId) {
                selectChat(targetChatId)
            }
            val targetChat = repository.getChatByIdSync(targetChatId)
                ?: allChats.value.firstOrNull { it.id == targetChatId }
            val chatWakeWords = listOfNotNull(targetChat?.wakeWord?.takeIf { it.isNotBlank() }) + activeWakeWords
            var resolvedDraft = com.example.voice.AudioCommandSanitizer.stripWakeWordTrigger(
                rawText = draftText,
                customWakeWords = chatWakeWords
            )

            if (resolvedDraft.isBlank() && hasValidAudio) {
                val transcribedRaw = try {
                    geminiService.transcribeAudioSnippet(capturedBytes, "audio/pcm").trim()
                } catch (_: Exception) {
                    ""
                }
                resolvedDraft = com.example.voice.AudioCommandSanitizer.stripWakeWordTrigger(
                    rawText = transcribedRaw,
                    customWakeWords = chatWakeWords
                )
            }
            if (resolvedDraft.isNotBlank()) {
                webLinkHistoryManager.recordVoiceCommand(
                    commandType = VoiceCommandType.VOICE_TURN,
                    triggerPhrase = targetChat?.wakeWord ?: "Voice Turn",
                    recognizedTranscript = resolvedDraft,
                    chatTitle = targetChat?.title
                )
            }
            sendMessage(
                chatId = targetChatId,
                text = resolvedDraft,
                audioBytes = if (hasValidAudio) capturedBytes else null,
                triggeredByWakeWord = true
            )
        }
    }

    /**
     * Transcribes the currently captured voice buffer into text using the transcription model.
     */
    fun transcribeVoiceInput(onResult: (String) -> Unit) {
        val capturedBytes: ByteArray
        synchronized(voicePcmOutputStream) {
            capturedBytes = voicePcmOutputStream.toByteArray()
        }
        if (!WakeWordManager.shouldTransmitAudioPacket(capturedBytes.size, capturedBytes)) {
            wakeWordManager.setStatusMessage("No speech detected — voice turn cancelled")
            onResult("")
            return
        }
        _lastAudioPacketTimestampMs.value = WakeWordManager.resolvePresentationTimestamp(System.currentTimeMillis())
        viewModelScope.launch {
            val transcription = geminiService.transcribeAudioSnippet(capturedBytes, "audio/pcm")
            if (transcription.isNotBlank()) {
                _currentDraftText.value = transcription
                webLinkHistoryManager.recordVoiceCommand(
                    commandType = VoiceCommandType.DICTATE,
                    triggerPhrase = _dictateTrigger.value,
                    recognizedTranscript = transcription,
                    chatTitle = currentChat.value?.title
                )
            }
            onResult(transcription)
        }
    }

    /**
     * Convenience overload that dispatches recognized speech or text directly to the active/default chat session.
     */
    fun sendMessage(
        text: String,
        triggeredByWakeWord: Boolean = true
    ) {
        val targetChatId = _currentChatId.value
            ?: allChats.value.firstOrNull { it.isDefaultWakeChat }?.id
            ?: allChats.value.firstOrNull()?.id
            ?: wakeWordManager.getActiveChats().firstOrNull { it.isDefaultWakeChat }?.id
            ?: wakeWordManager.getActiveChats().firstOrNull()?.id
            ?: 0L
        sendMessage(
            chatId = targetChatId,
            text = text,
            audioBytes = null,
            triggeredByWakeWord = triggeredByWakeWord
        )
    }

    fun sendMessage(
        chatId: Long,
        text: String,
        audioBytes: ByteArray? = null,
        triggeredByWakeWord: Boolean = false
    ) {
        // Explicitly stop SpeechRecognizer and grab any buffered partial/final transcription if text is blank
        stopSpeechRecognizerInternal()
        val activeWakeWords = (
            allChats.value.mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } } +
                wakeWordManager.getActiveChats().mapNotNull { it.wakeWord.takeIf { w -> w.isNotBlank() } } +
                allWakeWords.value.filter { it.isEnabled }.map { it.phrase }
            ).distinct()
        val effectiveRawText = text.trim()
            .ifBlank { _currentDraftText.value.trim() }
            .ifBlank { wakeWordManager.lastHeardText.value.trim() }
        val (detectedWakePrefix, strippedWakeText) =
            com.example.voice.SpeechRecognitionCoordinator.extractWakeTriggeredTranscript(
                rawTranscript = effectiveRawText,
                customWakeWords = activeWakeWords
            )
        val effectiveTriggeredByWakeWord = triggeredByWakeWord || detectedWakePrefix
        val trimmed = if (effectiveTriggeredByWakeWord || text.isBlank()) {
            strippedWakeText
        } else {
            effectiveRawText
        }
        val currentAttachedUri = _attachedImageUri.value
        val attachment = _stagedAttachment.value ?: if (currentAttachedUri != null) {
            try {
                val cr = getApplication<Application>().contentResolver
                val mime = cr.getType(currentAttachedUri) ?: "image/jpeg"
                val bytes = cr.openInputStream(currentAttachedUri)?.use { it.readBytes() } ?: byteArrayOf()
                AttachmentPayload(
                    uri = currentAttachedUri,
                    fileName = "attached_image.jpg",
                    mimeType = mime,
                    bytes = bytes,
                    sizeBytes = bytes.size.toLong()
                )
            } catch (_: Exception) {
                null
            }
        } else null
        val validAudioBytes = audioBytes?.takeIf {
            WakeWordManager.shouldTransmitAudioPacket(it.size, it)
        }
        val hasAudio = validAudioBytes != null
        if (trimmed.isBlank() && attachment == null && currentAttachedUri == null && !hasAudio) {
            _pendingWakeQuery.value = null
            _currentDraftText.value = ""
            return
        }
        // Discard any voice-triggered payload shorter than 2 characters when no attachment or audio is present
        if (effectiveTriggeredByWakeWord && trimmed.length < 2 && attachment == null && currentAttachedUri == null && !hasAudio) {
            _pendingWakeQuery.value = null
            _currentDraftText.value = ""
            return
        }
        val immediateChatId = chatId.takeIf { it > 0L } ?: _currentChatId.value ?: 0L
        val checkNowMs = System.currentTimeMillis()
        if (effectiveTriggeredByWakeWord &&
            trimmed.isNotBlank() &&
            trimmed.equals(lastSentWakeMessagePrompt, ignoreCase = true) &&
            immediateChatId == lastSentWakeMessageChatId &&
            (checkNowMs - lastSentWakeMessageAtMs) < 2000L
        ) {
            _pendingWakeQuery.value = null
            _currentDraftText.value = ""
            return
        }
        if (_isGenerating.value) return
        if (trimmed.isNotBlank()) {
            _lastDispatchedVoicePrompt.value = trimmed
            if (effectiveTriggeredByWakeWord) {
                lastSentWakeMessagePrompt = trimmed
                lastSentWakeMessageChatId = immediateChatId
                lastSentWakeMessageAtMs = checkNowMs
                _pendingWakeQuery.value = null
            }
        }
        _currentDraftText.value = ""
        wakeWordManager.clearLastHeardText()

        val outgoingTimestamp = WakeWordManager.resolvePresentationTimestamp(System.currentTimeMillis())
        if (hasAudio) {
            _lastAudioPacketTimestampMs.value = outgoingTimestamp
        }

        // Release continuous microphone hardware and exclusive audio focus BEFORE Gemini & TTS response
        // so the continuous mic and the TTS engine never fight for the same exclusive audio focus channel.
        releaseMicAndAudioFocusForWakeHandoffAndTts("send_message_before_gemini_and_tts")

        clearStagedAttachment()
        _attachedImageUri.value = null

        // Immediately append optimistic user message into hotMessages so the user bubble renders on-screen instantly
        if (immediateChatId > 0L && trimmed.isNotEmpty() && !ChatMessage.isLegacyAudioPlaceholder(trimmed)) {
            val optimisticMsg = ChatMessage(
                chatId = immediateChatId,
                sender = "user",
                content = trimmed,
                timestamp = outgoingTimestamp,
                triggeredByWakeWord = effectiveTriggeredByWakeWord,
                attachmentUri = attachment?.uri?.toString(),
                attachmentName = attachment?.fileName,
                attachmentMimeType = attachment?.mimeType,
                attachmentSizeBytes = attachment?.sizeBytes
            ).sanitizeForPersistence()
            if (optimisticMsg.hasPersistableContent) {
                bufferManager.appendMessage(optimisticMsg)
            }
        }

        activeGenerationJob?.cancel()
        activeGenerationJob = viewModelScope.launch {
            var resolvedChatId = chatId.takeIf { it > 0L } ?: _currentChatId.value ?: 0L
            try {
                _errorMessage.value = null
                _isGenerating.value = true
                _streamingText.value = ""

                resolvedChatId = resolveOrEnsureTargetChatId(chatId)
                if (_currentChatId.value == null || _currentChatId.value != resolvedChatId) {
                    _currentChatId.value = resolvedChatId
                    bufferManager.preloadAndAttachChat(resolvedChatId)
                }
                val now = WakeWordManager.resolvePresentationTimestamp(outgoingTimestamp)

                val resolvedUserText = when {
                    trimmed.isNotEmpty() && !ChatMessage.isLegacyAudioPlaceholder(trimmed) -> trimmed
                    validAudioBytes != null -> {
                        val transcribedRaw = try {
                            geminiService.transcribeAudioSnippet(validAudioBytes, "audio/pcm", getApplication()).trim()
                        } catch (_: Exception) {
                            ""
                        }
                        com.example.voice.SpeechRecognitionCoordinator.extractWakeTriggeredTranscript(
                            rawTranscript = transcribedRaw,
                            customWakeWords = activeWakeWords
                        ).second.takeUnless { ChatMessage.isLegacyAudioPlaceholder(it) }
                            ?.ifBlank { trimmed.takeUnless { t -> ChatMessage.isLegacyAudioPlaceholder(t) }.orEmpty() }
                            .orEmpty()
                    }
                    else -> ""
                }

                if (resolvedUserText.isNotBlank()) {
                    _lastDispatchedVoicePrompt.value = resolvedUserText
                }

                val userMsg = ChatMessage(
                    chatId = resolvedChatId,
                    sender = "user",
                    content = resolvedUserText,
                    timestamp = now,
                    triggeredByWakeWord = effectiveTriggeredByWakeWord,
                    attachmentUri = attachment?.uri?.toString(),
                    attachmentName = attachment?.fileName,
                    attachmentMimeType = attachment?.mimeType,
                    attachmentSizeBytes = attachment?.sizeBytes
                ).sanitizeForPersistence()

                if (!userMsg.hasPersistableContent) {
                    // Never insert empty or legacy audio-placeholder-only messages into Room or memory buffer
                    return@launch
                }

                // Immediately append optimistic userMsg into ChatMemoryBufferManager.hotMessages before Room insertion
                bufferManager.appendMessage(userMsg)

                // Prompt text sent alongside structured inlineData parts
                val apiPrompt = when {
                    userMsg.sanitizedTextContent.isNotEmpty() -> userMsg.sanitizedTextContent
                    attachment != null -> "Please analyze this attached file."
                    else -> ""
                }

                // 1. Insert user message into Room safely via MessageDao (@Insert(onConflict = IGNORE))
                //    which also atomically increments ChatSession.totalVoiceCommands when triggeredByWakeWord = true.
                val userMsgId = messageRepository.insertMessage(userMsg)
                if (userMsgId <= 0) {
                    // Duplicate [chatId, timestamp, sender] turn ignored or parent chat missing; abort duplicate AI generation
                    return@launch
                }
                val savedUserMsg = userMsg.copy(id = userMsgId)
                com.example.util.AppSettingsManager.applyCategoryToMessage(
                    context = getApplication(),
                    messageId = userMsgId,
                    chatId = resolvedChatId
                )
                bufferManager.appendMessage(savedUserMsg)
                localSyncServer.publishOutgoingTurnToSse(savedUserMsg)
                syncMessageToCloudIfSignedIn(savedUserMsg)
                syncChatToCloudIfSignedIn(resolvedChatId)

                // 2. Fetch current chat details for system instruction and full chronological message history
                val chat = repository.getChatByIdSync(resolvedChatId)
                val baseSystemPrompt = chat?.systemInstruction ?: "You are Gemini, an intelligent and helpful AI assistant."
                val currentAdvState = _adventureState.value
                val systemPrompt = if (currentAdvState.isAdventureModeEnabled) {
                    com.example.game.AdventureEngine.buildGameMasterSystemPrompt(
                        baseSystemInstruction = baseSystemPrompt,
                        state = currentAdvState
                    )
                } else {
                    baseSystemPrompt
                }
                val history = withContext(Dispatchers.IO) {
                    messageRepository.getMessagesForChatSync(resolvedChatId)
                        .filter { it.id != userMsgId && !it.isDeleted }
                }
                stageChatAndUrlPassively(chat = chat, messages = history + savedUserMsg)

                val fullResponse = StringBuilder()
                runCatching { streamingVoicePlayer.stop() }
                runCatching { textToSpeechManager.stop() }
                val requestAudio = _voiceEngineMode.value == com.example.voice.VoiceEngineMode.GEMINI_ORION && _isAutoSpeakEnabled.value

                kotlinx.coroutines.withTimeout(45_000L) {
                    geminiService.streamChatResponse(
                        context = getApplication(),
                        systemInstruction = systemPrompt,
                        history = history,
                        userPrompt = apiPrompt,
                        userAudioBytes = validAudioBytes,
                        attachmentBytes = attachment?.bytes,
                        attachmentMimeType = attachment?.mimeType,
                        requestAudio = requestAudio,
                        onAudioChunk = { pcm ->
                            if (_voiceEngineMode.value == com.example.voice.VoiceEngineMode.GEMINI_ORION && _isAutoSpeakEnabled.value) {
                                streamingVoicePlayer.feedPcmChunk(pcm, System.currentTimeMillis())
                            }
                        }
                    ).collect { chunk ->
                        fullResponse.append(chunk)
                        _streamingText.value = fullResponse.toString()
                    }
                }

                val finalAnswer = fullResponse.toString().trim()
                if (finalAnswer.isNotEmpty() && _currentChatId.value == resolvedChatId) {
                    val parsedAdventure = com.example.game.AdventureEngine.parseInlineMarkers(finalAnswer)
                    if (parsedAdventure.events.isNotEmpty()) {
                        applyInlineAdventureEvents(parsedAdventure.events)
                    }
                    val aiMsg = ChatMessage(
                        chatId = resolvedChatId,
                        sender = "gemini",
                        content = finalAnswer,
                        timestamp = WakeWordManager.resolvePresentationTimestamp(System.currentTimeMillis())
                    )
                    val aiMsgId = repository.insertMessage(aiMsg)
                    if (aiMsgId > 0) {
                        val savedAiMsg = aiMsg.copy(id = aiMsgId)
                        com.example.util.AppSettingsManager.applyCategoryToMessage(
                            context = getApplication(),
                            messageId = aiMsgId,
                            chatId = resolvedChatId
                        )
                        bufferManager.appendMessage(savedAiMsg)
                        localSyncServer.publishOutgoingTurnToSse(savedAiMsg)
                        stageChatAndUrlPassively(chat = chat, messages = history + savedUserMsg + savedAiMsg)
                        syncMessageToCloudIfSignedIn(savedAiMsg)
                    }
                    if (_isAutoSpeakEnabled.value &&
                        _voiceEngineMode.value != com.example.voice.VoiceEngineMode.MUTED &&
                        !streamingVoicePlayer.isPlaying.value
                    ) {
                        com.example.voice.WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("tts_speaking")
                        releaseMicAndAudioFocusForWakeHandoffAndTts("tts_speaking")
                        textToSpeechManager.speak(finalAnswer)
                    }
                    ReplyNotificationHelper.notifyModelReplyIfBackgroundedOrScreenOff(
                        context = getApplication(),
                        notifySettingEnabled = _notifyActiveChatReplies.value,
                        isAppInForeground = isAppInForeground,
                        chatId = resolvedChatId,
                        chatTitle = chat?.title ?: "Gemini Chat",
                        replyText = finalAnswer
                    )
                } else if (finalAnswer.isEmpty()) {
                    throw IllegalStateException("Gemini returned an empty response.")
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) {
                    throw e
                }
                val errorDescription = e.localizedMessage ?: e.message ?: "Failed to generate response."
                val formattedChatError = "⚠️ Error: $errorDescription"
                _errorMessage.value = errorDescription
                withContext(kotlinx.coroutines.NonCancellable) {
                    val targetErrorChatId = resolvedChatId.takeIf { it > 0L } ?: _currentChatId.value ?: 0L
                    if (targetErrorChatId > 0L) {
                        val errorMsg = ChatMessage(
                            chatId = targetErrorChatId,
                            sender = "gemini",
                            content = formattedChatError,
                            timestamp = WakeWordManager.resolvePresentationTimestamp(System.currentTimeMillis())
                        )
                        val insertedErrorId = runCatching { repository.insertMessage(errorMsg) }.getOrDefault(0L)
                        val persistedErrorMsg = if (insertedErrorId > 0L) errorMsg.copy(id = insertedErrorId) else errorMsg
                        bufferManager.appendMessage(persistedErrorMsg)
                    }
                }
            } finally {
                _isGenerating.value = false
                _streamingText.value = null
                // Post-turn mic behavior:
                // - Hands-Free ON: Re-initialize and restart wake-word listening once audio playback has finished
                // - Hands-Free OFF (Walkie-Talkie mode): Stay stopped until manually triggered
                if (!streamingVoicePlayer.isPlaying.value &&
                    !textToSpeechManager.isSpeaking.value &&
                    !textToSpeechManager.hasPendingSettlingDelay
                ) {
                    _isWakeHandoffInProgress.value = false
                    speechRecognitionCoordinator.setProcessingState(false)
                }
                if (_isHandsFreeActive.value &&
                    !_isSleepMode.value &&
                    !streamingVoicePlayer.isPlaying.value &&
                    !textToSpeechManager.isSpeaking.value &&
                    !textToSpeechManager.hasPendingSettlingDelay
                ) {
                    com.example.voice.WakeWordVoiceService.resumeAfterInternalDictationInMemory()
                    com.example.voice.WakeWordVoiceService.setIntentionallyPaused(false)
                    speechRecognitionCoordinator.setIntentionallyPaused(false)
                    scheduleSpeechRecognizerRestart(
                        delayMs = com.example.voice.SpeechRecognitionCoordinator.DEFAULT_RESTART_DEBOUNCE_MS,
                        allowDuringUserVoiceTurn = true
                    )
                }
            }
        }
    }

    fun setDefaultPersistenceEnabled(enabled: Boolean) {
        _defaultPersistenceEnabled.value = enabled
        appPrefs.edit().putBoolean("default_persistence", enabled).apply()
    }

    fun setPreserveCacheAcrossSessions(enabled: Boolean) {
        _preserveCacheAcrossSessions.value = enabled
        appPrefs.edit().putBoolean("preserve_cache_across_sessions", enabled).apply()
    }

    fun setHeartbeatPingOnlyMode(enabled: Boolean) {
        _heartbeatPingOnlyMode.value = enabled
        appPrefs.edit().putBoolean("heartbeat_ping_only_mode", enabled).apply()
    }

    fun setDictateTrigger(trigger: String) {
        val trimmed = trigger.trim().ifBlank { com.example.voice.AudioCommandSanitizer.DEFAULT_DICTATE_TRIGGER }
        _dictateTrigger.value = trimmed
        appPrefs.edit().putString("dictate_trigger", trimmed).apply()
        wakeWordManager.dictateTrigger = trimmed
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.upsertSystemTrigger(
                role = WakeWord.ROLE_DICTATE,
                phrase = trimmed,
                sensitivity = _wakeWordSensitivity.value
            )
        }
    }

    fun setSendTrigger(trigger: String) {
        val trimmed = trigger.trim().ifBlank { com.example.voice.AudioCommandSanitizer.DEFAULT_SEND_TRIGGER }
        _sendTrigger.value = trimmed
        appPrefs.edit().putString("send_trigger", trimmed).apply()
        wakeWordManager.sendTrigger = trimmed
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.upsertSystemTrigger(
                role = WakeWord.ROLE_SEND,
                phrase = trimmed,
                sensitivity = _wakeWordSensitivity.value
            )
        }
    }

    fun setHeartbeatTrigger(trigger: String) {
        val trimmed = trigger.trim().ifBlank { com.example.voice.AudioCommandSanitizer.DEFAULT_HEARTBEAT_TRIGGER }
        _heartbeatTrigger.value = trimmed
        appPrefs.edit().putString("heartbeat_trigger", trimmed).apply()
        wakeWordManager.heartbeatTrigger = trimmed
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.upsertSystemTrigger(
                role = WakeWord.ROLE_HEARTBEAT,
                phrase = trimmed,
                sensitivity = _wakeWordSensitivity.value
            )
        }
    }

    fun resetTriggersToDefaults() {
        setDictateTrigger(com.example.voice.AudioCommandSanitizer.DEFAULT_DICTATE_TRIGGER)
        setSendTrigger(com.example.voice.AudioCommandSanitizer.DEFAULT_SEND_TRIGGER)
        setHeartbeatTrigger(com.example.voice.AudioCommandSanitizer.DEFAULT_HEARTBEAT_TRIGGER)
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.resetSystemTriggersToDefaults(_wakeWordSensitivity.value)
        }
    }

    fun addWakeWord(
        phrase: String,
        triggerType: TriggerType = TriggerType.CHAT,
        metadata: String? = null,
        chatId: Long? = _currentChatId.value
    ) {
        val trimmed = phrase.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            if (triggerType == TriggerType.CHAT && chatId != null && chatId > 0L) {
                val chat = repository.getChatByIdSync(chatId)
                    ?: allChats.value.firstOrNull { it.id == chatId }
                val title = chat?.title ?: (metadata ?: "Gemini Chat")
                val id = wakeWordRepository.addCustomChatWakeWord(
                    chatId = chatId,
                    chatTitle = title,
                    phrase = trimmed,
                    sensitivity = _wakeWordSensitivity.value,
                    metadata = metadata ?: title
                )
                syncWakeWordToCloudIfSignedIn(id)
            } else {
                val id = wakeWordRepository.addWakeWord(
                    phrase = trimmed,
                    triggerType = triggerType,
                    metadata = metadata,
                    chatId = chatId,
                    sensitivity = _wakeWordSensitivity.value
                )
                syncWakeWordToCloudIfSignedIn(id)
            }
        }
    }

    fun removeWakeWord(wakeWord: WakeWord) {
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.removeWakeWord(wakeWord)
            _currentUserUid.value?.let { uid ->
                runCatching { firebaseSyncRepository?.deleteWakeWord(uid, wakeWord.id) }
            }
        }
    }

    fun removeWakeWordById(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.removeWakeWordById(id)
            _currentUserUid.value?.let { uid ->
                runCatching { firebaseSyncRepository?.deleteWakeWord(uid, id) }
            }
        }
    }

    fun removeWakeWordByPhrase(phrase: String) {
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.removeWakeWordByPhrase(phrase)
        }
    }

    fun addCustomWakeWord(
        chatId: Long,
        phrase: String,
        sensitivity: Float = _wakeWordSensitivity.value
    ) {
        val trimmed = phrase.trim()
        if (trimmed.isBlank()) return
        val clampedSensitivity = sensitivity.coerceIn(0.50f, 0.98f)
        viewModelScope.launch(Dispatchers.IO) {
            val chat = repository.getChatByIdSync(chatId)
                ?: allChats.value.firstOrNull { it.id == chatId }
            val title = chat?.title ?: "Gemini Chat"
            val id = wakeWordRepository.addCustomChatWakeWord(
                chatId = chatId,
                chatTitle = title,
                phrase = trimmed,
                sensitivity = clampedSensitivity
            )
            syncWakeWordToCloudIfSignedIn(id)
        }
    }

    fun addCustomWakeWordWithNewChat(
        phrase: String,
        newChatTitle: String,
        sensitivity: Float = _wakeWordSensitivity.value,
        onCreated: ((Long) -> Unit)? = null
    ) {
        val trimmedPhrase = phrase.trim()
        if (trimmedPhrase.isBlank()) return
        val resolvedTitle = newChatTitle.trim().ifBlank { "$trimmedPhrase Chat" }
        val clampedSensitivity = sensitivity.coerceIn(0.50f, 0.98f)
        viewModelScope.launch(Dispatchers.IO) {
            val (createdChatId, wakeWordId) = wakeWordRepository.createChatAndBindWakeWord(
                chatTitle = resolvedTitle,
                phrase = trimmedPhrase,
                sensitivity = clampedSensitivity
            )
            if (createdChatId > 0L) {
                syncChatToCloudIfSignedIn(createdChatId)
            }
            if (wakeWordId > 0L) {
                syncWakeWordToCloudIfSignedIn(wakeWordId)
            }
            if (createdChatId > 0L && onCreated != null) {
                withContext(Dispatchers.Main) {
                    onCreated.invoke(createdChatId)
                }
            }
        }
    }

    fun setIndividualWakeWordSensitivity(id: Long, sensitivity: Float) {
        val clamped = sensitivity.coerceIn(0.50f, 0.98f)
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.updateWakeWordSensitivity(id, clamped)
            wakeWordManager.updateWakeWordItemSensitivity(id, clamped)
            syncWakeWordToCloudIfSignedIn(id)
        }
    }

    fun setIndividualWakeWordSensitivityPercent(id: Long, sensitivityPercent: Int) {
        val clampedInt = sensitivityPercent.coerceIn(0, 100)
        val floatVal = WakeWord.fromSensitivityInt(clampedInt)
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.updateWakeWordSensitivity(id, clampedInt)
            wakeWordManager.updateWakeWordItemSensitivity(id, floatVal)
            syncWakeWordToCloudIfSignedIn(id)
        }
    }

    fun setIndividualWakeWordSensitivityLevel(id: Long, level: com.example.voice.WakeSensitivityLevel) {
        setIndividualWakeWordSensitivity(id, level.sensitivityValue)
    }

    fun reassignWakeWordToChat(wakeWordId: Long, targetChatId: Long) {
        if (targetChatId <= 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            val targetChat = repository.getChatByIdSync(targetChatId)
                ?: allChats.value.firstOrNull { it.id == targetChatId }
            val resolvedTitle = targetChat?.title ?: "Gemini Chat"
            wakeWordRepository.updateWakeWordTargetChatId(wakeWordId, targetChatId, resolvedTitle)
            wakeWordManager.updateWakeWordChatAssociation(wakeWordId, targetChatId, resolvedTitle)
            syncWakeWordToCloudIfSignedIn(wakeWordId)
            syncChatToCloudIfSignedIn(targetChatId)
        }
    }

    fun reassignWakeWordToNewChat(
        wakeWordId: Long,
        newChatTitle: String,
        onCreated: ((Long) -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val newChatId = wakeWordRepository.reassignWakeWordToNewChat(wakeWordId, newChatTitle)
            if (newChatId > 0L) {
                wakeWordManager.retrieveWakeWordConfigurations()
                syncChatToCloudIfSignedIn(newChatId)
                syncWakeWordToCloudIfSignedIn(wakeWordId)
                if (onCreated != null) {
                    withContext(Dispatchers.Main) {
                        onCreated.invoke(newChatId)
                    }
                }
            }
        }
    }

    fun updateConfiguredWakeWordPhrase(id: Long, newPhrase: String) {
        val trimmed = newPhrase.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.updateWakeWordPhrase(id, trimmed)
            syncWakeWordToCloudIfSignedIn(id)
        }
    }

    fun toggleConfiguredWakeWord(id: Long, enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.setWakeWordEnabled(id, enabled)
            syncWakeWordToCloudIfSignedIn(id)
        }
    }

    fun deleteConfiguredWakeWord(id: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            wakeWordRepository.removeWakeWordById(id)
            _currentUserUid.value?.let { uid ->
                runCatching { firebaseSyncRepository?.deleteWakeWord(uid, id) }
            }
        }
    }

    var handsFreeToggleClockMs: () -> Long = { System.currentTimeMillis() }
    private var lastHandsFreeToggleAtMs: Long = 0L

    internal fun resetHandsFreeToggleDebounceForTest() {
        lastHandsFreeToggleAtMs = 0L
    }

    /**
     * Unified microphone & hands-free voice toggle shared by the Front Dashboard mic button,
     * the Settings screen test/listener toggle button, and hands-free controls.
     * Synchronizes persistent [isHandsFreeActive] state and active speech recognition together.
     *
     * Strictly uses [_isHandsFreeActive] as the single source of truth so transient
     * `isListening == false` states never force or invert `handsFreeMode` to OFF, and enforces
     * a minimum 400ms debounce between user toggles.
     */
    fun toggleHandsFreeVoice(enabled: Boolean? = null) {
        if (enabled != null && enabled == _isHandsFreeActive.value && !_isSleepMode.value) {
            return
        }
        val now = handsFreeToggleClockMs()
        if (lastHandsFreeToggleAtMs > 0L && (now - lastHandsFreeToggleAtMs) < 400L) {
            return
        }
        lastHandsFreeToggleAtMs = now

        val next = enabled ?: !_isHandsFreeActive.value

        com.example.voice.AudioCommandSanitizer.playRadarToggleHaptic(
            context = getApplication(),
            isActivating = next,
            reason = if (next) "Hands-Free Radar Toggled ON" else "Hands-Free Radar Toggled OFF"
        )

        if (next && _isSleepMode.value) {
            _isSleepMode.value = false
            _sleepModeReason.value = ""
            audioRecorder.setIntentionallyPaused(false)
            speechRecognitionCoordinator.setSleeping(false)
            wakeWordManager.exitSleepMode("Hands-free voice enabled")
            com.example.voice.WakeWordVoiceService.setSleepMode(false)
            com.example.voice.WakeWordVoiceService.setIntentionallyPaused(false)
        }

        if (next && !checkRecordAudioPermission()) {
            pendingWakeStartAfterPermission = true
            _requiresAudioPermission.value = true
            wakeWordManager.setStatusMessage("Microphone permission required for hands-free wake-word listening")
        }

        _isHandsFreeActive.value = next
        appPrefs.edit().putBoolean("hands_free_active", next).apply()

        if (next && _currentChatId.value == null) {
            viewModelScope.launch {
                val resolvedId = resolveOrEnsureTargetChatId(null)
                if (_currentChatId.value == null) {
                    selectChat(resolvedId)
                }
            }
        }

        wakeWordManager.setHandsFreeMode(next)

        if (next) {
            audioRecorder.setIntentionallyPaused(false)
            speechRecognitionCoordinator.setIntentionallyPaused(false)
            com.example.voice.WakeWordVoiceService.resumeAfterInternalDictationInMemory()
            com.example.voice.WakeWordVoiceService.setIntentionallyPaused(false)
            if (!_isSleepMode.value) {
                com.example.voice.WakeWordVoiceService.startServiceSafely(
                    context = getApplication(),
                    action = com.example.voice.WakeWordVoiceService.ACTION_START_VOICE_SERVICE
                )
            }
            if (checkRecordAudioPermission()) {
                startContinuousWakeWordListening(userInitiated = true)
            }
        } else {
            _isInChatDictationActive.value = false
            speechRecognitionCoordinator.setInChatDictationMode(false)
            com.example.voice.WakeWordVoiceService.setIntentionallyPaused(true)
            stopContinuousWakeWordListening(markIntentionallyPaused = true)
            wakeWordManager.setStatusMessage("Hands-free voice standby")
        }
    }

    fun toggleHandsFreeMode(enabled: Boolean? = null) {
        toggleHandsFreeVoice(enabled)
    }

    fun updateCurrentDraftText(text: String) {
        _currentDraftText.value = text
    }

    fun triggerManualHeartbeatPing() {
        _lastHeartbeatPingTime.value = System.currentTimeMillis()
        com.example.coupler.ChromeBridgeSocketManager.recordHeartbeatPing()
        com.example.heartbeat.HeartbeatAlarmReceiver.scheduleHeartbeatPing(getApplication(), 0L)
        com.example.voice.AudioCommandSanitizer.playHeartbeatConfirmation(getApplication())
    }

    private var windowFocusDebounceJob: Job? = null

    fun onWindowFocusChanged(hasFocus: Boolean) {
        _hasWindowFocus.value = hasFocus
        windowFocusDebounceJob?.cancel()
        windowFocusDebounceJob = null

        if (!hasFocus) {
            _isInChatDictationActive.value = false
            speechRecognitionCoordinator.setWindowFocused(false)
            wakeWordManager.setWindowFocused(false)
            return
        }

        val isHandsFreeModeEnabled = _isHandsFreeActive.value && !_isSleepMode.value
        val isProcessingCommand = speechRecognitionCoordinator.isProcessing ||
            _isGenerating.value ||
            isOrionVoicePlaying.value ||
            textToSpeechManager.isSpeaking.value ||
            textToSpeechManager.hasPendingSettlingDelay ||
            _isNavigatingViaVoiceCommand.value ||
            _isWakeHandoffInProgress.value

        if (isHandsFreeModeEnabled && !isProcessingCommand) {
            audioRecorder.setIntentionallyPaused(false)
            speechRecognitionCoordinator.setIntentionallyPaused(false)
            com.example.voice.WakeWordVoiceService.resumeAfterInternalDictationInMemory()
            com.example.voice.WakeWordVoiceService.setIntentionallyPaused(false)
        }
        speechRecognitionCoordinator.setWindowFocused(true)
        wakeWordManager.setWindowFocused(true)
    }

    /**
     * Invoked from `MainActivity.onPause()`. Preserves `_isHandsFreeActive` (`TRUE`), never emits
     * `RADAR_TOGGLE_OFF`, and never invokes `stopListening(markIntentionallyPaused = true)`.
     */
    fun onAppPaused() {
        _isInChatDictationActive.value = false
        speechRecognitionCoordinator.setInChatDictationMode(false)
        if (_isHandsFreeActive.value &&
            !_isSleepMode.value &&
            !speechRecognitionCoordinator.isProcessing &&
            !_isWakeHandoffInProgress.value &&
            !textToSpeechManager.isSpeaking.value &&
            !textToSpeechManager.hasPendingSettlingDelay
        ) {
            com.example.voice.WakeWordVoiceService.startServiceSafely(
                context = getApplication(),
                action = com.example.voice.WakeWordVoiceService.ACTION_START_VOICE_SERVICE
            )
        }
    }

    fun onAppBackgrounded() {
        isAppInForeground = false
        com.example.voice.WakeWordVoiceService.isMainActivityInForeground = false
        _hasWindowFocus.value = false
        _isInChatDictationActive.value = false
        speechRecognitionCoordinator.setWindowFocused(false)
        wakeWordManager.setWindowFocused(false)
        destroySpeechRecognizerInternal(markIntentionallyPaused = false)
        com.example.voice.BatterySaverMonitorWorker.enqueueImmediateCheck(getApplication())
        if (_heartbeatPingOnlyMode.value) {
            ChromeBridgeSocketManager.enterHeartbeatMode(getApplication())
        }
    }

    fun onAppForegrounded() {
        isAppInForeground = true
        com.example.voice.WakeWordVoiceService.isMainActivityInForeground = true
        _hasWindowFocus.value = true
        speechRecognitionCoordinator.setWindowFocused(true)
        wakeWordManager.setWindowFocused(true)
        networkStatusTracker.refresh()
        val isHandoffOrTtsBusy = _isWakeHandoffInProgress.value ||
            _isInChatDictationActive.value ||
            speechRecognitionCoordinator.isInChatDictationMode ||
            textToSpeechManager.isSpeaking.value ||
            textToSpeechManager.hasPendingSettlingDelay ||
            speechRecognitionCoordinator.isProcessing ||
            _isNavigatingViaVoiceCommand.value ||
            _isGenerating.value
        if (_isHandsFreeActive.value && !_isSleepMode.value && !isHandoffOrTtsBusy && checkRecordAudioPermission()) {
            com.example.voice.WakeWordVoiceService.startServiceSafely(
                context = getApplication(),
                action = com.example.voice.WakeWordVoiceService.ACTION_START_VOICE_SERVICE
            )
            if (!wakeWordManager.isListening.value && !speechRecognitionCoordinator.isListening.value) {
                startContinuousWakeWordListening()
            }
        }
        com.example.voice.BatterySaverMonitorWorker.enqueueImmediateCheck(getApplication())
        if (_heartbeatPingOnlyMode.value) {
            ChromeBridgeSocketManager.exitHeartbeatMode(getApplication())
        }
    }

    fun scheduleChatTimerAlarm(chatId: Long, delaySeconds: Long, chatTitle: String, alarmMessage: String? = null) {
        com.example.heartbeat.HeartbeatAlarmReceiver.scheduleChatTimerAlarm(
            context = getApplication(),
            chatId = chatId,
            chatTitle = chatTitle,
            delaySeconds = delaySeconds,
            alarmMessage = alarmMessage
        )
    }

    fun cancelChatTimerAlarm(chatId: Long) {
        com.example.heartbeat.HeartbeatAlarmReceiver.cancelChatTimerAlarm(getApplication(), chatId)
    }

    fun toggleChatPersistence(chatId: Long) {
        viewModelScope.launch {
            val chat = repository.getChatByIdSync(chatId) ?: return@launch
            val updated = chat.copy(isPersistent = !chat.isPersistent)
            repository.saveChat(updated)
        }
    }

    fun addCustomPrompt(title: String, promptText: String, iconName: String = "Sparkles") {
        promptRepository.addCustomPrompt(title, promptText, iconName)
    }

    fun deleteCustomPrompt(id: String) {
        promptRepository.deletePrompt(id)
    }

    suspend fun findConflictingWakeWordSession(wakeWord: String, excludeChatId: Long = -1L): ChatSession? {
        return repository.findConflictingWakeWordSession(wakeWord, excludeChatId)
    }

    suspend fun isWakeWordUnique(wakeWord: String, excludeChatId: Long = -1L): Boolean {
        return repository.isWakeWordUnique(wakeWord, excludeChatId)
    }

    fun saveChat(chat: ChatSession, onComplete: ((Long) -> Unit)? = null) {
        viewModelScope.launch {
            val conflict = repository.findConflictingWakeWordSession(
                wakeWord = chat.wakeWord,
                excludeChatId = if (chat.id > 0L) chat.id else -1L
            )
            if (conflict != null) {
                _errorMessage.value =
                    "Wake word \"${chat.wakeWord.trim()}\" is already used by \"${conflict.title}\". Each chat session must have a unique wake word."
                onComplete?.invoke(-1L)
                return@launch
            }
            val id = repository.saveChat(chat)
            if (id > 0L) {
                val pendingCat = _pendingNewChatCategory.value
                val pendingTags = _pendingNewChatTags.value
                if (!pendingCat.isNullOrBlank()) {
                    val existingMsgIds = withContext(Dispatchers.IO) {
                        messageRepository.getMessagesForChatSync(id).map { it.id }
                    }
                    com.example.util.AppSettingsManager.applyCategoryToChatAndMessages(
                        context = getApplication(),
                        chatId = id,
                        category = pendingCat,
                        messageIds = existingMsgIds
                    )
                    _pendingNewChatCategory.value = null
                }
                if (pendingTags.isNotEmpty()) {
                    com.example.util.AppSettingsManager.setChatTags(getApplication(), id, pendingTags)
                    _pendingNewChatTags.value = emptySet()
                }
                wakeWordRepository.syncChatWakeWordFromSession(
                    chatId = id,
                    chatTitle = chat.title,
                    wakeWord = chat.wakeWord,
                    sensitivity = _wakeWordSensitivity.value
                )
                val savedChat = chat.copy(id = id)
                val existingMessages = withContext(Dispatchers.IO) {
                    messageRepository.getMessagesForChatSync(id).filter { !it.isDeleted }
                }
                stageChatAndUrlPassively(chat = savedChat, messages = existingMessages)
                syncChatToCloudIfSignedIn(id)
            }
            onComplete?.invoke(id)
        }
    }

    fun updateChatTheme(chatId: Long, theme: AppTheme) {
        viewModelScope.launch {
            repository.updateChatTheme(chatId, theme)
        }
    }

    fun updateChatAppearance(
        chatId: Long,
        colorPaletteId: String,
        backgroundStyle: String,
        themeColorHex: String
    ) {
        viewModelScope.launch {
            repository.updateChatAppearance(
                chatId = chatId,
                colorPaletteId = colorPaletteId,
                backgroundStyle = backgroundStyle,
                themeColorHex = themeColorHex
            )
            syncChatToCloudIfSignedIn(chatId)
        }
    }

    fun importParsedChat(
        session: ChatSession,
        messages: List<ChatMessage>,
        onComplete: ((Long) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val newChatId = repository.saveChat(session)
            for (msg in messages) {
                repository.insertMessage(
                    msg.copy(chatId = newChatId)
                )
            }
            // Pre-warm the buffer for this chat and select it
            selectChat(newChatId)
            onComplete?.invoke(newChatId)
        }
    }

    fun recordCouplerHandoff() {
        _lastCouplerHandoffTime.value = System.currentTimeMillis()
    }

    /**
     * Piggyback load: Appends incoming Gemini response turns to the currently active chat
     * without duplicating existing messages.
     */
    fun appendPiggybackMessages(
        chatId: Long,
        incomingMessages: List<ChatMessage>,
        onComplete: ((Int) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val existing = rawCurrentMessages.value
            val existingFingerprints = existing.map { "${it.sender}:${it.content.trim()}" }.toSet()

            var insertedCount = 0
            var baseTimestamp = System.currentTimeMillis()

            for (msg in incomingMessages) {
                val fingerprint = "${msg.sender}:${msg.content.trim()}"
                if (!existingFingerprints.contains(fingerprint)) {
                    val ts = if (msg.timestamp > 0) msg.timestamp else baseTimestamp++
                    val newMsg = ChatMessage(
                        chatId = chatId,
                        sender = msg.sender,
                        content = msg.content,
                        timestamp = ts,
                        triggeredByWakeWord = false
                    )
                    repository.insertMessage(newMsg)
                    bufferManager.appendMessage(newMsg)
                    insertedCount++
                }
            }
            onComplete?.invoke(insertedCount)
        }
    }

    /**
     * Creates a new chat session from a Coupler payload or shared transcript.
     */
    fun createCoupledChat(
        title: String,
        wakeWord: String,
        messages: List<ChatMessage>,
        onComplete: ((Long) -> Unit)? = null
    ) {
        val session = ChatSession(
            title = title,
            wakeWord = wakeWord,
            systemInstruction = "You are Gemini, continuing this coupled project session.",
            themeColorHex = "#4285F4",
            isPersistent = true
        )
        importParsedChat(session, messages, onComplete)
    }

    /**
     * Paced chunked import to prevent choking on long threads (100+ turns).
     */
    fun importParsedChatChunked(
        session: ChatSession,
        messages: List<ChatMessage>,
        pacing: com.example.importer.ImportPacing = com.example.importer.ImportPacing.SAFE_SLOW,
        onProgress: (com.example.importer.ImportProgress) -> Unit,
        onComplete: (Long) -> Unit
    ) {
        viewModelScope.launch {
            val newChatId = repository.saveChat(session)
            chunkedImporter.importInChunks(messages, pacing) { chunk ->
                for (msg in chunk) {
                    repository.insertMessage(msg.copy(chatId = newChatId))
                }
            }.collect { progress ->
                onProgress(progress)
                if (progress.isComplete) {
                    selectChat(newChatId)
                    onComplete(newChatId)
                }
            }
        }
    }

    fun setDefaultWakeChat(chatId: Long) {
        viewModelScope.launch {
            repository.setDefaultWakeChat(chatId)
        }
    }

    fun deleteChat(chatId: Long) {
        viewModelScope.launch {
            if (_currentChatId.value == chatId) {
                activeGenerationJob?.cancel()
                activeGenerationJob = null
                _isGenerating.value = false
                _streamingText.value = null
                _errorMessage.value = null
                streamingVoicePlayer.stop()
                bufferManager.detachChat()
                _currentChatId.value = null
            }
            // Lifecycle Hook: Force reset and clear ChromeBridgeSocketManager when chat is deleted
            ChromeBridgeSocketManager.forceResetAndClear(getApplication(), preserveDiskCache = _preserveCacheAcrossSessions.value)
            bufferManager.clearDiskCache(chatId)
            com.example.util.AppSettingsManager.unpinChat(getApplication(), chatId)
            deleteWakeWordSamples(chatId)
            wakeWordRepository.deleteWakeWordsForChat(chatId)
            // Strictly delete from local Room database only; Cloud Firestore backups are preserved
            // unless explicitly deleted by the user in the Cloud Backup & Restore Vault.
            repository.deleteChat(chatId)
        }
    }

    fun clearChatHistory(chatId: Long) {
        viewModelScope.launch {
            repository.clearChatMessages(chatId)
            bufferManager.clearDiskCache(chatId)
            _streamingText.value = null
        }
    }

    // Wake Word Snippet Recording (MediaRecorder API) & Enrollment
    val isWakeWordEnrollmentRecording: StateFlow<Boolean> = wakeWordManager.isEnrollmentRecording

    fun startRecordingWakeSnippet(chatId: Long, slotIndex: Int): Boolean {
        if (!ensureMicrophonePermission(startWakeListeningWhenGranted = false)) {
            _activeRecordingSlot.value = null
            return false
        }
        // Stop any active snippet playback preview, SpeechRecognizer, and WakeWordManager mic first
        snippetPlayer.stop()
        destroySpeechRecognizerInternal()
        wakeWordManager.completeChatTurnAndReleaseMic()

        val outputFile = wakeWordStorageManager.getSnippetFile(chatId, slotIndex)
        val res = audioRecorder.start(outputFile)
        return if (res.isSuccess) {
            activeRecordingChatId = chatId
            _activeRecordingSlot.value = slotIndex
            _snippetRecordingDurationMs.value = 0L
            startRecordingTimer()
            true
        } else {
            _activeRecordingSlot.value = null
            if (_isHandsFreeActive.value) {
                wakeWordManager.restartWakeListeningAfterTurn()
                scheduleSpeechRecognizerRestart(delayMs = 250L)
            }
            false
        }
    }

    private fun startRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            val limitSec = _autoStopLimitSec.value
            val limitMs = if (limitSec > 0) limitSec * 1000L else 0L
            while (isActive) {
                val elapsed = System.currentTimeMillis() - startTime
                _snippetRecordingDurationMs.value = elapsed

                if (limitMs > 0L && elapsed >= limitMs) {
                    val slot = _activeRecordingSlot.value
                    if (slot != null) {
                        stopRecordingWakeSnippet(activeRecordingChatId, slot)
                    }
                    break
                }
                kotlinx.coroutines.delay(100)
            }
        }
    }

    private fun stopRecordingTimer() {
        recordingTimerJob?.cancel()
        recordingTimerJob = null
        _snippetRecordingDurationMs.value = 0L
    }

    fun stopRecordingWakeSnippet(chatId: Long, slotIndex: Int) {
        _activeRecordingSlot.value = null
        stopRecordingTimer()
        if (audioRecorder.lastRecordedPcmBytes.isEmpty()) {
            val fallbackBufferedPcm = wakeWordManager.getCapturedSnippetPcm()
            if (fallbackBufferedPcm.isNotEmpty()) {
                audioRecorder.appendPcmBytes(fallbackBufferedPcm, fallbackBufferedPcm.size)
            }
        }
        val res = audioRecorder.stop()
        val recordedPcm = audioRecorder.lastRecordedPcmBytes
        if (recordedPcm.isNotEmpty()) {
            wakeWordManager.recordSnippetPcmBytes(recordedPcm, recordedPcm.size)
        }
        val file = res.getOrNull()
        if (file != null && file.exists() && file.length() > 0) {
            viewModelScope.launch(Dispatchers.IO) {
                val meta = wakeWordStorageManager.extractMetadata(file)
                val chatWakeWords = wakeWordRepository.getChatWakeWordsForChatSync(chatId)
                val primaryWake = chatWakeWords.firstOrNull()
                val chatSession = repository.getChatByIdSync(chatId)
                val resolvedPhrase = primaryWake?.phrase ?: chatSession?.wakeWord ?: "Hey Gemini"
                val sample = com.example.data.model.WakeWordSample(
                    chatId = chatId,
                    wakeWordId = primaryWake?.id,
                    sampleUuid = java.util.UUID.randomUUID().toString(),
                    wakePhrase = resolvedPhrase,
                    samplePath = file.absolutePath,
                    recordedAt = System.currentTimeMillis(),
                    durationMs = meta.durationMs,
                    sizeBytes = meta.sizeBytes,
                    mimeType = meta.mimeType
                )
                repository.insertWakeWordSample(sample)
                loadWakeWordSamples(chatId)
                _wakeWordStorageBytes.value = wakeWordStorageManager.getTotalStorageBytes()
            }
        }
        if (_isHandsFreeActive.value && !_isSleepMode.value) {
            wakeWordManager.restartWakeListeningAfterTurn()
            scheduleSpeechRecognizerRestart(delayMs = 250L)
        }
    }

    /**
     * Saves a custom wake word associated with [chatId] and links all recorded audio samples
     * for [chatId] with the wake word's unique database ID for the keyword detection loop.
     */
    fun saveCustomWakeWordWithSamples(
        phrase: String,
        chatId: Long,
        sensitivity: Float = _wakeWordSensitivity.value,
        onComplete: ((Long) -> Unit)? = null
    ) {
        val cleanPhrase = phrase.trim()
        if (cleanPhrase.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val targetChatId = resolveOrEnsureTargetChatId(chatId)
            val chat = repository.getChatByIdSync(targetChatId)
            val chatTitle = chat?.title ?: "Gemini Chat"
            val wakeWordId = wakeWordRepository.addCustomChatWakeWord(
                chatId = targetChatId,
                chatTitle = chatTitle,
                phrase = cleanPhrase,
                sensitivity = sensitivity.coerceIn(0.50f, 0.98f),
                metadata = chatTitle
            )
            if (wakeWordId > 0L) {
                repository.associateSamplesWithWakeWord(
                    chatId = targetChatId,
                    wakeWordId = wakeWordId,
                    wakePhrase = cleanPhrase
                )
                loadWakeWordSamples(targetChatId)
                syncWakeWordToCloudIfSignedIn(wakeWordId)
                syncChatToCloudIfSignedIn(targetChatId)
            }
            withContext(Dispatchers.Main) {
                onComplete?.invoke(wakeWordId)
            }
        }
    }

    fun cancelRecordingWakeSnippet() {
        audioRecorder.cancel()
        _activeRecordingSlot.value = null
        stopRecordingTimer()
        if (_isHandsFreeActive.value) {
            wakeWordManager.restartWakeListeningAfterTurn()
            scheduleSpeechRecognizerRestart(delayMs = 250L)
        }
    }

    fun playSnippetPreview(filePath: String) {
        val file = java.io.File(filePath)
        if (file.exists() && file.length() > 0L) {
            snippetPlayer.play(file)
        } else {
            val fallbackWav = wakeWordManager.getCapturedSnippetWav()
            if (fallbackWav.isNotEmpty()) {
                snippetPlayer.playPcmOrWavBytes(fallbackWav, WakeWordManager.SAMPLE_RATE_HZ, filePath)
            }
        }
    }

    fun playCapturedSnippetTestBuffer() {
        val wavBytes = wakeWordManager.getCapturedSnippetWav()
        if (wavBytes.isNotEmpty()) {
            snippetPlayer.playPcmOrWavBytes(wavBytes, WakeWordManager.SAMPLE_RATE_HZ, "captured_snippet_buffer")
        }
    }

    fun pauseSnippetPreview() {
        snippetPlayer.pause()
    }

    fun resumeSnippetPreview() {
        snippetPlayer.resume()
    }

    fun stopSnippetPreview() {
        snippetPlayer.stop()
    }

    fun playAllWakeSnippets(chatId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val samples = repository.getSamplesForChat(chatId).sortedBy { it.sampleIndex }
            val files = samples.map { java.io.File(it.samplePath) }
            snippetPlayer.playSequence(files)
        }
    }

    fun deleteWakeWordSampleById(chatId: Long, sampleId: Long, filePath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            if (snippetPlayer.currentPlayingPath.value == filePath) {
                snippetPlayer.stop()
            }
            wakeWordStorageManager.deleteFile(filePath)
            repository.deleteSampleById(sampleId)
            loadWakeWordSamples(chatId)
            _wakeWordStorageBytes.value = wakeWordStorageManager.getTotalStorageBytes()
        }
    }

    fun pruneOrphanedWakeSnippets() {
        viewModelScope.launch(Dispatchers.IO) {
            val chats = allChats.value
            val activePaths = mutableSetOf<String>()
            for (chat in chats) {
                val samples = repository.getSamplesForChat(chat.id)
                for (s in samples) {
                    activePaths.add(s.samplePath)
                }
            }
            wakeWordStorageManager.pruneOrphanedFiles(activePaths)
            _wakeWordStorageBytes.value = wakeWordStorageManager.getTotalStorageBytes()
        }
    }

    fun startWakeWordEnrollment(onSampleCaptured: (ShortArray) -> Unit) {
        wakeWordManager.startEnrollmentCapture(onSampleCaptured)
    }

    fun stopWakeWordEnrollment() {
        wakeWordManager.stopEnrollmentCapture()
    }

    fun saveWakeWordSample(chatId: Long, sampleIndex: Int, pcm: ShortArray) {
        wakeWordManager.recordSnippetShorts(pcm, pcm.size)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val fileName = "wake_word_${chatId}_${sampleIndex}.pcm"
                val file = java.io.File(getApplication<Application>().filesDir, fileName)
                file.outputStream().use { out ->
                    val bytes = ByteArray(pcm.size * 2)
                    for (i in pcm.indices) {
                        val s = pcm[i].toInt()
                        bytes[i * 2] = (s and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                    }
                    out.write(bytes)
                }

                val sample = com.example.data.model.WakeWordSample(
                    chatId = chatId,
                    samplePath = file.absolutePath,
                    recordedAt = System.currentTimeMillis(),
                    durationMs = (pcm.size / 16).toLong().coerceAtLeast(1L),
                    sizeBytes = (pcm.size * 2).toLong(),
                    mimeType = "audio/pcm"
                )
                repository.insertWakeWordSample(sample)

                // Refresh WakeWordManager cache
                loadWakeWordSamples(chatId)
                _wakeWordStorageBytes.value = wakeWordStorageManager.getTotalStorageBytes()
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to save wake word sample", e)
            }
        }
    }

    fun deleteWakeWordSamples(chatId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            snippetPlayer.stop()
            wakeWordStorageManager.deleteAllSnippetsForChat(chatId)
            repository.deleteSamplesForChat(chatId)
            wakeWordManager.updateEnrollmentSamples(chatId, emptyList())
            _wakeWordStorageBytes.value = wakeWordStorageManager.getTotalStorageBytes()
        }
    }

    fun loadWakeWordSamples(chatId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val samples = repository.getSamplesForChat(chatId)
            val pcmList = mutableListOf<ShortArray>()
            for (s in samples) {
                try {
                    val file = java.io.File(s.samplePath)
                    val lower = s.samplePath.lowercase(java.util.Locale.US)
                    if (file.exists() && (lower.endsWith(".pcm") || lower.endsWith(".wav"))) {
                        val rawPcmBytes = WakeWordManager.extractPcmFromWavOrRaw(file.readBytes())
                        if (rawPcmBytes.size >= 2) {
                            wakeWordManager.recordSnippetPcmBytes(rawPcmBytes, rawPcmBytes.size)
                            val pcm = ShortArray(rawPcmBytes.size / 2)
                            for (i in pcm.indices) {
                                pcm[i] = ((rawPcmBytes[i * 2].toInt() and 0xFF) or ((rawPcmBytes[i * 2 + 1].toInt() and 0xFF) shl 8)).toShort()
                            }
                            pcmList.add(pcm)
                        }
                    }
                } catch (_: Exception) {}
            }
            wakeWordManager.updateEnrollmentSamples(chatId, pcmList)
        }
    }

    fun getSamplesForChatFlow(chatId: Long) = repository.getSamplesForChatFlow(chatId)

    fun deleteMessage(messageId: Long) {
        viewModelScope.launch {
            repository.deleteMessage(messageId)
            _currentUserUid.value?.let { uid ->
                withContext(Dispatchers.IO) {
                    runCatching { firebaseSyncRepository?.deleteMessage(uid, messageId) }
                }
            }
        }
    }

    fun speakMessage(text: String) {
        if (com.example.voice.AudioCommandSanitizer.isSystemOrCommandStatusString(
                text = text,
                dictateTrigger = _dictateTrigger.value,
                sendTrigger = _sendTrigger.value,
                heartbeatTrigger = _heartbeatTrigger.value
            )
        ) {
            return
        }
        if (_voiceEngineMode.value == com.example.voice.VoiceEngineMode.MUTED) return
        com.example.voice.WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("tts_speaking")
        releaseMicAndAudioFocusForWakeHandoffAndTts("tts_speaking")
        textToSpeechManager.speak(text)
    }

    fun stopSpeaking() {
        streamingVoicePlayer.stop()
        textToSpeechManager.bargeInAndStop()
    }

    fun setVoiceEngineMode(mode: com.example.voice.VoiceEngineMode) {
        _voiceEngineMode.value = mode
        appPrefs.edit().putString("voice_engine_mode", mode.name).apply()
        if (mode == com.example.voice.VoiceEngineMode.MUTED) {
            streamingVoicePlayer.stop()
            textToSpeechManager.stop()
        }
    }

    fun playOrionTestSample() {
        // StreamingVoicePlayer strictly plays incoming model responses from Gemini only.
        streamingVoicePlayer.stop()
    }

    private fun startTickerIfNeeded() {
        if (timerTickerJob?.isActive == true) return
        timerTickerJob = viewModelScope.launch {
            while (true) {
                delay(1000L)
                val openChatId = _currentChatId.value
                // When chat is open, track elapsed time continuously as long as chat is open (not necessarily active)
                if (openChatId != null && _isOpenChatTimerRunning.value) {
                    _openChatTimerSeconds.update { it + 1L }
                    val currentSecs = _openChatTimerSeconds.value
                    _liveTrackedSeconds.update { it + (openChatId to currentSecs) }
                } else {
                    val activeIds = _activeTimerChatIds.value
                    if (activeIds.isNotEmpty()) {
                        _liveTrackedSeconds.update { currentMap ->
                            val updated = currentMap.toMutableMap()
                            for (id in activeIds) {
                                val prev = updated[id] ?: 0L
                                updated[id] = prev + 1L
                            }
                            updated
                        }
                    }
                }
            }
        }
    }

    /**
     * Toggles stopwatch timer for the currently open chat ON or OFF.
     */
    fun toggleOpenChatTimer() {
        _isOpenChatTimerRunning.value = !_isOpenChatTimerRunning.value
        if (_isOpenChatTimerRunning.value) {
            _currentChatId.value?.let { id ->
                _activeTimerChatIds.update { it + id }
            }
            startTickerIfNeeded()
        }
    }

    /**
     * Resets open chat stopwatch timer back to 0 for the open session.
     */
    fun resetOpenChatTimer() {
        _openChatTimerSeconds.value = 0L
        _currentChatId.value?.let { id ->
            _liveTrackedSeconds.update { it + (id to 0L) }
        }
    }

    /**
     * Toggles the Usage Limits vs Cache Bars comparison UI element.
     */
    fun toggleUsageLimitsComparison(enabled: Boolean? = null) {
        _showUsageLimitsComparison.value = enabled ?: !_showUsageLimitsComparison.value
    }

    /**
     * Toggles stopwatch timer for the specified chat ON or OFF.
     */
    fun toggleChatTimer(chatId: Long) {
        val currentActive = _activeTimerChatIds.value
        if (currentActive.contains(chatId)) {
            // Turn OFF (pause)
            _activeTimerChatIds.value = currentActive - chatId
            val currentSecs = _liveTrackedSeconds.value[chatId] ?: 0L
            viewModelScope.launch {
                repository.updateTrackedSeconds(chatId, currentSecs)
            }
        } else {
            // Turn ON (start/resume)
            if (!_liveTrackedSeconds.value.containsKey(chatId)) {
                val chat = allChats.value.firstOrNull { it.id == chatId }
                val initial = chat?.totalTrackedSeconds ?: 0L
                _liveTrackedSeconds.update { it + (chatId to initial) }
            }
            _activeTimerChatIds.value = currentActive + chatId
            startTickerIfNeeded()
        }
    }

    /**
     * Resets the accumulated stopwatch time for the specified chat to 0 seconds.
     */
    fun resetChatTimer(chatId: Long) {
        _liveTrackedSeconds.update { it + (chatId to 0L) }
        viewModelScope.launch {
            repository.updateTrackedSeconds(chatId, 0L)
        }
    }

    fun dismissScreenshotBanner() {
        _screenshotBanner.value = null
    }

    fun stageImage(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = GeminiCouplerBridge.stageImage(getApplication(), uri)
            if (result != null) {
                _screenshotBanner.value = GeminiCouplerBridge.stagedImageInfo.value
            }
        }
    }

    fun stageImageBytes(bytes: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = GeminiCouplerBridge.stageImageBytes(getApplication(), bytes)
            if (result != null) {
                _screenshotBanner.value = GeminiCouplerBridge.stagedImageInfo.value
            }
        }
    }

    fun clearStagedImage() {
        GeminiCouplerBridge.clearStagedImage(getApplication())
        _screenshotBanner.value = null
    }

    fun checkForRecentScreenshot() {
        screenshotWatcher.checkForRecentScreenshot()
    }

    fun dispatchScreenshotToCoupler(chatId: Long, promptText: String) {
        val chat = allChats.value.firstOrNull { it.id == chatId } ?: return
        val messages = rawCurrentMessages.value
        val imageUri = stagedImageUri.value

        GeminiCouplerBridge.launchGeminiAppHandoff(
            context = getApplication(),
            chat = chat,
            messages = messages,
            activeTaskPrompt = promptText.ifBlank { "Analyze this attached screenshot and provide recommendations." },
            imageUri = imageUri
        )

        if (promptText.isNotBlank()) {
            viewModelScope.launch {
                val userMsg = ChatMessage(
                    chatId = chatId,
                    sender = "user",
                    content = "📸 [Attached Screenshot]\n$promptText",
                    timestamp = System.currentTimeMillis()
                )
                repository.insertMessage(userMsg)
            }
        }
    }

    fun importChatArchive(uri: Uri, targetChatId: Long? = null) {
        try {
            FileProcessingService.startServiceForUri(
                context = getApplication(),
                uri = uri,
                targetChatId = targetChatId,
                action = FileProcessingService.ACTION_PROCESS_FILE_IMPORT
            )
        } catch (_: Exception) {
            viewModelScope.launch(Dispatchers.IO) {
                val createdChatId = chatImportWorker.importChatArchive(uri, targetChatId)
                if (createdChatId != null && createdChatId > 0L) {
                    withContext(Dispatchers.Main) {
                        selectChat(createdChatId)
                    }
                }
            }
        }
    }

    fun cancelChatImport() {
        chatImportWorker.cancelImport()
        try {
            val app = getApplication<Application>()
            app.stopService(android.content.Intent(app, FileProcessingService::class.java))
        } catch (_: Exception) {}
    }

    fun cleanShutdown(activity: android.app.Activity?) {
        try {
            uiMicDebounceJob?.cancel()
            uiMicDebounceJob = null
            adventureSaveSentinelJob?.cancel()
            adventureSaveSentinelJob = null
            timerTickerJob?.cancel()
            cloudSyncJob?.cancel()
            voiceNavigationSettleJob?.cancel()
            activeGenerationJob?.cancel()
            com.example.voice.WakeWordVoiceService.onSleepModeChangedCallback = null
            com.example.voice.WakeWordVoiceService.onWakeWordDetectedCallback = null
            com.example.voice.WakeWordVoiceService.onVoiceHistoryNavigationRequestedCallback = null
            com.example.voice.WakeWordVoiceService.onBatterySaverResumeRequestedCallback = null
            com.example.voice.WakeWordVoiceService.onDiagnosticTraceCallback = null
            destroySpeechRecognizerInternal()
            wakeWordManager.stopContinuousListening()
            wakeWordManager.destroy()
            streamingVoicePlayer.stop()
            streamingVoicePlayer.release()
            textToSpeechManager.shutdown()
            localSyncServer.stop()
            screenshotWatcher.stopWatching()
            networkStatusTracker.stopTracking()
            viewModelScope.coroutineContext.cancelChildren()
        } catch (_: Exception) {}
        activity?.finishAffinity()
    }

    fun exportCurrentChatAsPlainText(): String {
        val chat = currentChat.value ?: return ""
        val messages = rawCurrentMessages.value
        val sb = java.lang.StringBuilder()
        sb.appendLine("=========================================")
        sb.appendLine("Chat: ${chat.title}")
        sb.appendLine("Wake Word: \"${chat.wakeWord}\"")
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        sb.appendLine("Exported: ${dateFormat.format(java.util.Date())}")
        sb.appendLine("=========================================\n")
        for (msg in messages) {
            val role = if (msg.sender == "user") "User" else "Gemini"
            val time = dateFormat.format(java.util.Date(msg.timestamp))
            sb.appendLine("[$time] $role:")
            sb.appendLine(msg.content)
            sb.appendLine()
        }
        return sb.toString()
    }

    /**
     * Exports the chat interaction logs stored in the Room database as a JSON string.
     * If [chatId] is provided, exports only that chat session's logs; otherwise exports all active chats.
     */
    suspend fun exportChatInteractionLogsAsJsonString(chatId: Long? = null): String {
        return repository.exportChatInteractionLogsToJsonString(chatId)
    }

    /**
     * Exports the chat interaction logs stored in Room to a `.json` file inside `cacheDir/exports`.
     */
    suspend fun exportChatInteractionLogsToJsonFile(
        context: android.content.Context = getApplication(),
        chatId: Long? = null
    ): java.io.File {
        return repository.exportChatInteractionLogsToJsonFile(context, chatId)
    }

    /**
     * Exports the chat interaction logs stored in Room to a simple `.json` file and shares it
     * via a standard Android `Intent.ACTION_SEND` chooser (`FileProvider`).
     */
    fun shareChatInteractionLogsAsJson(
        context: android.content.Context,
        chatId: Long? = null,
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                repository.exportAndShareChatInteractionLogs(context, chatId)
            } catch (e: Exception) {
                android.util.Log.e("MainViewModel", "Failed to export and share chat interaction logs JSON", e)
                onError(e.message ?: "Unable to share chat interaction logs")
            }
        }
    }

    fun toggleFavoriteChat(chatId: Long): Boolean {
        val updated = com.example.util.AppSettingsManager.toggleFavoriteChat(getApplication(), chatId)
        return updated.contains(chatId)
    }

    fun togglePinnedChat(chatId: Long): Boolean {
        val currentSession = allChats.value.firstOrNull { it.id == chatId }
        val currentlyPinned = currentSession?.isPinned == true || pinnedChatIds.value.contains(chatId)
        val nextPinned = !currentlyPinned
        val updated = com.example.util.AppSettingsManager.togglePinnedChat(getApplication(), chatId)
        val resolvedPinned = if (updated.contains(chatId) != nextPinned) {
            com.example.util.AppSettingsManager.togglePinnedChat(getApplication(), chatId).contains(chatId)
        } else {
            nextPinned
        }
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateChatPinned(chatId, resolvedPinned)
            syncChatToCloudIfSignedIn(chatId)
        }
        return resolvedPinned
    }

    fun setContextualRoutingEnabled(enabled: Boolean) {
        com.example.util.AppSettingsManager.setContextualRoutingEnabled(getApplication(), enabled)
    }

    fun setPromptFavoritesOnWake(enabled: Boolean) {
        com.example.util.AppSettingsManager.setPromptFavoritesOnWake(getApplication(), enabled)
    }

    fun openWakeFavoritesPicker() {
        _showWakeFavoritesPickerDialog.value = true
    }

    fun dismissWakeFavoritesPicker() {
        _showWakeFavoritesPickerDialog.value = false
    }

    fun dismissWakeConversationSummary() {
        _wakeConversationSummary.value = null
    }

    fun generateWakeAiSummary() {
        val currentSummary = _wakeConversationSummary.value ?: return
        if (currentSummary.isGeneratingAiSummary) return
        _wakeConversationSummary.value = currentSummary.copy(isGeneratingAiSummary = true)
        viewModelScope.launch {
            try {
                val recentMessages = withContext(Dispatchers.IO) {
                    messageRepository.getMessagesForChatSync(currentSummary.chatId)
                        .filter { !it.isDeleted && it.content.isNotBlank() }
                        .takeLast(8)
                }
                if (recentMessages.isEmpty()) {
                    _wakeConversationSummary.value = currentSummary.copy(
                        aiExpandedSummary = "No recent messages in this conversation to summarize yet.",
                        isGeneratingAiSummary = false
                    )
                    return@launch
                }
                val transcriptBlock = recentMessages.joinToString("\n") { msg ->
                    val speaker = if (msg.sender.equals("user", ignoreCase = true)) "User" else "Gemini"
                    "$speaker: ${msg.content.take(260)}"
                }
                val prompt = "Provide a concise 2-sentence context recap of the following recent conversation so the user can immediately resume where they left off:\n\n$transcriptBlock"
                val responseBuilder = StringBuilder()
                geminiService.streamChatResponse(
                    context = getApplication(),
                    systemInstruction = "You are a concise conversation summarizer. Respond in at most 2 short sentences.",
                    history = emptyList(),
                    userPrompt = prompt,
                    requestAudio = false
                ).collect { chunk ->
                    responseBuilder.append(chunk)
                }
                val fallbackRecap = currentSummary.instantRecapBullets.joinToString(" ")
                val generated = responseBuilder.toString().trim().ifBlank {
                    fallbackRecap
                }
                _wakeConversationSummary.value = _wakeConversationSummary.value?.copy(
                    aiExpandedSummary = generated,
                    isGeneratingAiSummary = false
                )
            } catch (e: Exception) {
                val fallbackRecap = currentSummary.instantRecapBullets.joinToString(" ")
                _wakeConversationSummary.value = _wakeConversationSummary.value?.copy(
                    aiExpandedSummary = "Instant Recap: $fallbackRecap",
                    isGeneratingAiSummary = false
                )
            }
        }
    }

    fun toggleRelayServer(enabled: Boolean) {
        if (enabled) {
            localSyncServer.start(com.example.bridge.LocalSyncRelayServer.DEFAULT_PORT)
        } else {
            localSyncServer.stop()
        }
    }

    fun rotateRelaySessionToken(): String {
        return localSyncServer.regenerateSessionToken()
    }

    fun forceResetAndClearSystemHygiene(): com.example.util.HygieneResetResult {
        return com.example.util.AppSettingsManager.forceResetAndClear(
            context = getApplication(),
            relayServer = localSyncServer,
            bufferManager = bufferManager,
            clearAudioBufferCallback = {
                synchronized(voicePcmOutputStream) {
                    voicePcmOutputStream.reset()
                }
            },
            preserveDiskCache = false
        )
    }

    fun setChatCategoryAndTags(chatId: Long, category: String, tags: Set<String>) {
        if (chatId <= 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            val messageIds = messageRepository.getMessagesForChatSync(chatId).map { it.id }
            com.example.util.AppSettingsManager.applyCategoryToChatAndMessages(
                context = getApplication(),
                chatId = chatId,
                category = category,
                messageIds = messageIds
            )
            com.example.util.AppSettingsManager.setChatTags(getApplication(), chatId, tags)
        }
    }

    fun reassignChatAndMessagesCategory(chatId: Long, category: String) {
        if (chatId <= 0L) return
        viewModelScope.launch(Dispatchers.IO) {
            val messageIds = messageRepository.getMessagesForChatSync(chatId).map { it.id }
            com.example.util.AppSettingsManager.applyCategoryToChatAndMessages(
                context = getApplication(),
                chatId = chatId,
                category = category,
                messageIds = messageIds
            )
        }
    }

    // =========================================================================
    // Gemini's Infinite Adventure Engine — RPG State, Procedural Loot & Sentinel
    // =========================================================================

    /**
     * Generates a deterministic procedural [com.example.game.InventoryItem] using a seeded RNG
     * across fantasy and sci-fi item pools with weighted rarity colors.
     */
    fun generateProceduralLoot(seed: Long = System.currentTimeMillis()): com.example.game.InventoryItem {
        return com.example.game.AdventureEngine.generateProceduralLoot(seed)
    }

    /**
     * Generates procedural loot from [seed] and adds it directly to the active campaign inventory.
     */
    fun rollAndAddProceduralLoot(seed: Long = System.currentTimeMillis()): com.example.game.InventoryItem {
        val loot = generateProceduralLoot(seed)
        _adventureState.update { current ->
            val updatedList = current.inventory.toMutableList()
            val existingIdx = updatedList.indexOfFirst {
                it.id.equals(loot.id, ignoreCase = true) || it.name.equals(loot.name, ignoreCase = true)
            }
            if (existingIdx >= 0) {
                val existing = updatedList[existingIdx]
                updatedList[existingIdx] = existing.copy(quantity = existing.quantity + loot.quantity)
            } else {
                updatedList.add(0, loot)
            }
            current.copy(
                inventory = updatedList,
                statusBannerMessage = "Rolled ${loot.rarityLabel} Loot: ${loot.name} (x${loot.quantity})"
            )
        }
        persistCurrentAdventureStateAsync()
        return loot
    }

    /**
     * Reads plain text, Markdown (`.md`), JSON, or extracts readable text from attached PDF documents
     * to dynamically inject custom lore into the Gemini Adventure Engine system prompt context.
     */
    fun attachDocumentToAdventure(uri: Uri, mimeType: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val app = getApplication<Application>()
                val extractedLore = com.example.game.AdventureEngine.extractLoreFromDocument(
                    context = app,
                    uri = uri,
                    mimeType = mimeType
                )
                val fileName = resolveDisplayFileName(app, uri) ?: "lore_document.txt"
                if (extractedLore.isNotBlank()) {
                    _adventureState.update { current ->
                        val mergedLore = if (current.settings.loreContext.isBlank()) {
                            "--- [$fileName] ---\n$extractedLore"
                        } else {
                            "${current.settings.loreContext.trim()}\n\n--- [$fileName] ---\n$extractedLore"
                        }.take(16_000)
                        val updatedFiles = (current.attachedLoreFileNames + fileName).distinct()
                        current.copy(
                            isAdventureModeEnabled = true,
                            settings = current.settings.copy(loreContext = mergedLore),
                            attachedLoreFileNames = updatedFiles,
                            statusBannerMessage = "Injected lore from $fileName (${extractedLore.length} chars)"
                        )
                    }
                    persistCurrentAdventureStateSuspend()
                } else {
                    _adventureState.update { current ->
                        current.copy(statusBannerMessage = "Could not extract readable text from $fileName")
                    }
                }
            } catch (e: Exception) {
                _adventureState.update { current ->
                    current.copy(statusBannerMessage = "Lore import error: ${e.message ?: "unknown"}")
                }
            }
        }
    }

    private fun resolveDisplayFileName(context: android.content.Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else null
            } ?: uri.lastPathSegment
        } catch (_: Exception) {
            uri.lastPathSegment
        }
    }

    fun loadAdventureStateForChat(chatId: Long) {
        if (chatId <= 0L) return
        val currentDrawerOpen = if (_adventureState.value.chatId == chatId) _adventureState.value.isDrawerOpen else false
        val currentTab = _adventureState.value.activeDrawerTab
        viewModelScope.launch(Dispatchers.IO) {
            val savedEntity = runCatching { adventureGameDao.getStateForChatSync(chatId) }.getOrNull()
            val presetEntity = if (savedEntity == null) {
                runCatching {
                    adventureGameDao.getStateForChatSync(com.example.game.AdventureGameState.GLOBAL_PRESET_CHAT_ID)
                }.getOrNull()
            } else null

            val resolvedState = when {
                savedEntity != null -> savedEntity.toDomainState(
                    isDrawerOpen = currentDrawerOpen,
                    activeDrawerTab = currentTab,
                    unsavedPlaySeconds = 0L
                )
                presetEntity != null -> presetEntity.copy(chatId = chatId).toDomainState(
                    isDrawerOpen = currentDrawerOpen,
                    activeDrawerTab = currentTab,
                    unsavedPlaySeconds = 0L
                )
                else -> com.example.game.AdventureGameState(
                    chatId = chatId,
                    isDrawerOpen = currentDrawerOpen,
                    activeDrawerTab = currentTab
                )
            }
            _adventureState.value = resolvedState
            startAdventureSaveSentinelTicker()
        }
    }

    private fun startAdventureSaveSentinelTicker() {
        if (adventureSaveSentinelJob?.isActive == true) return
        adventureSaveSentinelJob = viewModelScope.launch {
            while (isActive) {
                kotlinx.coroutines.delay(1_000L)
                val current = _adventureState.value
                if (current.chatId > 0L && (current.isAdventureModeEnabled || current.isDrawerOpen)) {
                    incrementAdventurePlaySeconds(1L)
                }
            }
        }
    }

    internal fun advanceAdventurePlayTimerSecondsForTest(seconds: Long) {
        incrementAdventurePlaySeconds(seconds)
    }

    private fun incrementAdventurePlaySeconds(deltaSeconds: Long) {
        if (deltaSeconds <= 0L) return
        var shouldNotify = false
        var snapshotForNotification: com.example.game.AdventureGameState? = null
        _adventureState.update { current ->
            val nextSeconds = current.unsavedPlaySeconds + deltaSeconds
            val crossedThreshold =
                nextSeconds >= com.example.game.AdventureGameState.SAVE_SENTINEL_THRESHOLD_SECONDS &&
                    !current.isSaveSentinelAlertVisible
            val updated = current.copy(
                unsavedPlaySeconds = nextSeconds,
                isSaveSentinelAlertVisible = current.isSaveSentinelAlertVisible ||
                    nextSeconds >= com.example.game.AdventureGameState.SAVE_SENTINEL_THRESHOLD_SECONDS
            )
            if (crossedThreshold) {
                shouldNotify = true
                snapshotForNotification = updated
            }
            updated
        }
        if (shouldNotify && snapshotForNotification != null) {
            com.example.game.AdventureEngine.postSaveSentinelNotification(
                context = getApplication(),
                state = snapshotForNotification!!
            )
        }
    }

    fun openAdventureDrawer(tab: com.example.game.AdventureDrawerTab = _adventureState.value.activeDrawerTab) {
        _adventureState.update { current ->
            val effectiveChatId = current.chatId.takeIf { it > 0L } ?: _currentChatId.value ?: 1L
            current.copy(
                chatId = effectiveChatId,
                isDrawerOpen = true,
                activeDrawerTab = tab
            )
        }
        startAdventureSaveSentinelTicker()
    }

    fun closeAdventureDrawer() {
        _adventureState.update { current ->
            current.copy(isDrawerOpen = false)
        }
    }

    fun selectAdventureDrawerTab(tab: com.example.game.AdventureDrawerTab) {
        _adventureState.update { current ->
            current.copy(activeDrawerTab = tab)
        }
    }

    fun setAdventureModeEnabled(enabled: Boolean) {
        _adventureState.update { current ->
            current.copy(
                isAdventureModeEnabled = enabled,
                statusBannerMessage = if (enabled) {
                    "Adventure Mode ON — Game Master context active"
                } else {
                    "Adventure Mode paused"
                }
            )
        }
        persistCurrentAdventureStateAsync()
        if (enabled) {
            startAdventureSaveSentinelTicker()
        }
    }

    fun setAdventureDifficulty(difficulty: com.example.game.DifficultyLevel) {
        _adventureState.update { current ->
            current.copy(
                settings = current.settings.copy(difficulty = difficulty),
                statusBannerMessage = "Difficulty set to ${difficulty.displayName}"
            )
        }
        persistCurrentAdventureStateAsync()
    }

    fun setAdventureQuestFilter(filter: com.example.game.QuestFilterMode) {
        _adventureState.update { current ->
            current.copy(
                settings = current.settings.copy(questFilter = filter)
            )
        }
        persistCurrentAdventureStateAsync()
    }

    fun setAdventureAutoSaveOnQuest(enabled: Boolean) {
        _adventureState.update { current ->
            current.copy(
                settings = current.settings.copy(autoSaveOnQuest = enabled)
            )
        }
        persistCurrentAdventureStateAsync()
    }

    fun setAdventureNotificationsEnabled(enabled: Boolean) {
        _adventureState.update { current ->
            current.copy(
                settings = current.settings.copy(notificationsEnabled = enabled)
            )
        }
        persistCurrentAdventureStateAsync()
    }

    fun updateAdventureLoreContext(loreContext: String) {
        _adventureState.update { current ->
            current.copy(
                settings = current.settings.copy(loreContext = loreContext)
            )
        }
        persistCurrentAdventureStateAsync()
    }

    fun updateAdventureCharacterProfile(name: String, characterClass: String) {
        _adventureState.update { current ->
            current.copy(
                characterName = name.ifBlank { "Aether Vanguard" },
                characterClass = characterClass.ifBlank { "Chrono-Wayfarer" }
            )
        }
        persistCurrentAdventureStateAsync()
    }

    fun updateAdventureCharacterPortrait(uri: Uri) {
        _adventureState.update { current ->
            current.copy(
                characterPortraitUri = uri.toString(),
                statusBannerMessage = "Updated character portrait"
            )
        }
        persistCurrentAdventureStateAsync()
    }

    fun toggleAdventureQuestCompleted(questId: String) {
        var newlyCompleted = false
        _adventureState.update { current ->
            var xpDelta = 0
            val updatedQuests = current.quests.map { q ->
                if (q.id == questId) {
                    val nextCompleted = !q.isCompleted
                    if (nextCompleted) {
                        newlyCompleted = true
                        xpDelta += q.xpReward
                    } else {
                        xpDelta -= q.xpReward
                    }
                    q.copy(isCompleted = nextCompleted)
                } else {
                    q
                }
            }
            current.copy(
                quests = updatedQuests,
                totalXp = (current.totalXp + xpDelta).coerceAtLeast(0),
                statusBannerMessage = if (newlyCompleted) {
                    "Quest Completed! Auto-saved campaign progress."
                } else {
                    "Quest marked active."
                }
            )
        }
        if (newlyCompleted && _adventureState.value.settings.autoSaveOnQuest) {
            saveAdventureGameManually(isAutoSave = true)
        } else {
            persistCurrentAdventureStateAsync()
        }
    }

    fun addCustomAdventureQuest(title: String, description: String, xpReward: Int = 200) {
        if (title.isBlank()) return
        val newQuest = com.example.game.AdventureQuest(
            id = "quest_${System.currentTimeMillis()}",
            title = title.trim(),
            description = description.trim().ifBlank { "Fulfill this campaign objective." },
            isCompleted = false,
            xpReward = xpReward.coerceAtLeast(25)
        )
        _adventureState.update { current ->
            current.copy(
                quests = listOf(newQuest) + current.quests,
                statusBannerMessage = "Added Quest: ${newQuest.title}"
            )
        }
        persistCurrentAdventureStateAsync()
    }

    fun useOrInspectAdventureItem(item: com.example.game.InventoryItem) {
        val isConsumable = item.category.equals("Consumable", ignoreCase = true)
        _adventureState.update { current ->
            val updatedInventory = if (isConsumable) {
                current.inventory.mapNotNull { existing ->
                    if (existing.id == item.id) {
                        if (existing.quantity > 1) existing.copy(quantity = existing.quantity - 1) else null
                    } else existing
                }
            } else {
                current.inventory
            }
            current.copy(
                isAdventureModeEnabled = true,
                isDrawerOpen = false,
                inventory = updatedInventory,
                statusBannerMessage = if (isConsumable) {
                    "Used ${item.name}"
                } else {
                    "Inspecting ${item.name} in chat..."
                }
            )
        }
        persistCurrentAdventureStateAsync()
        val actionPrompt = if (isConsumable) {
            "[Adventure Action] I use my ${item.name} (${item.description}). How does this affect my current situation?"
        } else {
            "[Adventure Action] I inspect and equip my ${item.name} (${item.description}). What hidden details or tactical options does it reveal?"
        }
        sendMessage(text = actionPrompt, triggeredByWakeWord = false)
    }

    fun applyInlineAdventureEvents(events: List<com.example.game.AdventureInlineEvent>) {
        if (events.isEmpty()) return
        var questCompleted = false
        _adventureState.update { current ->
            val (updated, completedAny) = com.example.game.AdventureEngine.applyInlineEventsToState(current, events)
            questCompleted = completedAny
            updated
        }
        if (questCompleted && _adventureState.value.settings.autoSaveOnQuest) {
            saveAdventureGameManually(isAutoSave = true)
        } else {
            persistCurrentAdventureStateAsync()
        }
    }

    fun saveAdventureGameManually(isAutoSave: Boolean = false) {
        val now = System.currentTimeMillis()
        val targetChatId = (_adventureState.value.chatId.takeIf { it > 0L } ?: _currentChatId.value ?: 1L)
        _adventureState.update { current ->
            current.copy(
                chatId = targetChatId,
                unsavedPlaySeconds = 0L,
                lastSavedAt = now,
                isSaveSentinelAlertVisible = false,
                statusBannerMessage = if (isAutoSave) {
                    "Auto-saved campaign on quest completion"
                } else {
                    "Campaign saved locally & queued for cloud sync"
                }
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            persistCurrentAdventureStateSuspend()
            if (targetChatId > 0L) {
                syncChatToCloudIfSignedIn(targetChatId)
            }
        }
    }

    fun saveAdventureGlobalPreset() {
        val now = System.currentTimeMillis()
        _adventureState.update { current ->
            current.copy(
                lastSavedAt = now,
                statusBannerMessage = "Saved current campaign as Global Preset"
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val presetEntity = _adventureState.value.toEntity(
                    targetChatId = com.example.game.AdventureGameState.GLOBAL_PRESET_CHAT_ID
                )
                adventureGameDao.upsertState(presetEntity)
            }
        }
    }

    fun loadAdventureGlobalPreset() {
        val activeChatId = _adventureState.value.chatId.takeIf { it > 0L } ?: _currentChatId.value ?: 1L
        viewModelScope.launch(Dispatchers.IO) {
            val preset = runCatching {
                adventureGameDao.getStateForChatSync(com.example.game.AdventureGameState.GLOBAL_PRESET_CHAT_ID)
            }.getOrNull()
            if (preset != null) {
                val restored = preset.copy(chatId = activeChatId).toDomainState(
                    isDrawerOpen = _adventureState.value.isDrawerOpen,
                    activeDrawerTab = _adventureState.value.activeDrawerTab,
                    unsavedPlaySeconds = 0L
                ).copy(statusBannerMessage = "Loaded Global Campaign Preset")
                _adventureState.value = restored
                persistCurrentAdventureStateSuspend()
            } else {
                _adventureState.update { current ->
                    current.copy(statusBannerMessage = "No Global Preset saved yet — tap Save Preset first")
                }
            }
        }
    }

    fun dismissAdventureSaveSentinelAlert() {
        _adventureState.update { current ->
            current.copy(isSaveSentinelAlertVisible = false)
        }
    }

    fun dismissAdventureStatusBanner() {
        _adventureState.update { current ->
            current.copy(statusBannerMessage = null)
        }
    }

    private fun persistCurrentAdventureStateAsync() {
        viewModelScope.launch(Dispatchers.IO) {
            persistCurrentAdventureStateSuspend()
        }
    }

    private suspend fun persistCurrentAdventureStateSuspend() {
        runCatching {
            val snapshot = _adventureState.value
            val targetChatId = snapshot.chatId.takeIf { it > 0L } ?: _currentChatId.value ?: 1L
            adventureGameDao.upsertState(snapshot.toEntity(targetChatId = targetChatId))
        }
    }

    override fun onCleared() {
        super.onCleared()
        adventureSaveSentinelJob?.cancel()
        timerTickerJob?.cancel()
        destroySpeechRecognizerInternal()
        localSyncServer.stop()
        wakeWordManager.destroy()
        audioRecorder.release()
        snippetPlayer.release()
        streamingVoicePlayer.release()
        textToSpeechManager.shutdown()
        screenshotWatcher.stopWatching()
        networkStatusTracker.stopTracking()
        activeGenerationJob?.cancel()
    }
}
