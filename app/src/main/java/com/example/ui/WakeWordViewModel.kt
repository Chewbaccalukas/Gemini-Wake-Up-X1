package com.example.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.model.TriggerType
import com.example.data.model.WakeWord
import com.example.data.repository.WakeWordRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Immutable UI state snapshot managed by [WakeWordViewModel].
 */
data class WakeWordUiState(
    val wakeWords: List<WakeWord> = emptyList(),
    val activeWakeWords: List<WakeWord> = emptyList(),
    val chatWakeWords: List<WakeWord> = emptyList(),
    val handsFreeWakeWords: List<WakeWord> = emptyList(),
    val filteredWakeWords: List<WakeWord> = emptyList(),
    val selectedFilter: TriggerType? = null,
    val sensitivity: Float = 0.78f,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

/**
 * ViewModel that consumes [WakeWordRepository] and exposes state-managed [StateFlow] streams
 * for observing active/configured wake words and triggering additions, updates, or removals from the UI layer.
 */
class WakeWordViewModel(
    private val repository: WakeWordRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val stopTimeoutMillis: Long = 5000L
) : ViewModel() {

    private val stateScope = CoroutineScope(
        SupervisorJob(viewModelScope.coroutineContext[Job]) + ioDispatcher
    )

    private val _selectedTriggerType = MutableStateFlow<TriggerType?>(null)
    val selectedTriggerType: StateFlow<TriggerType?> = _selectedTriggerType.asStateFlow()

    private val _sensitivity = MutableStateFlow(0.78f)
    val sensitivity: StateFlow<Float> = _sensitivity.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /**
     * All persisted wake words (both enabled and disabled, across CHAT and HANDS_FREE trigger types).
     */
    val allWakeWords: StateFlow<List<WakeWord>> = repository.observeAllWakeWords()
        .stateIn(
            scope = stateScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis),
            initialValue = emptyList()
        )

    /**
     * Alias for [allWakeWords] for convenient UI observation.
     */
    val wakeWords: StateFlow<List<WakeWord>> = allWakeWords

    /**
     * Reactive list of currently active (`isEnabled == true`) wake words.
     */
    val activeWakeWords: StateFlow<List<WakeWord>> = allWakeWords
        .map { list -> list.filter { it.isEnabled } }
        .stateIn(
            scope = stateScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis),
            initialValue = emptyList()
        )

    /**
     * Reactive list of [TriggerType.CHAT] wake words.
     */
    val chatWakeWords: StateFlow<List<WakeWord>> = allWakeWords
        .map { list -> list.filter { it.triggerType == TriggerType.CHAT } }
        .stateIn(
            scope = stateScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis),
            initialValue = emptyList()
        )

    /**
     * Reactive list of [TriggerType.HANDS_FREE] system trigger wake words.
     */
    val handsFreeWakeWords: StateFlow<List<WakeWord>> = allWakeWords
        .map { list -> list.filter { it.triggerType == TriggerType.HANDS_FREE } }
        .stateIn(
            scope = stateScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis),
            initialValue = emptyList()
        )

    /**
     * Wake words filtered by [selectedTriggerType] (or all wake words when filter is null).
     */
    val filteredWakeWords: StateFlow<List<WakeWord>> = combine(
        allWakeWords,
        _selectedTriggerType
    ) { all, filter ->
        if (filter == null) all else all.filter { it.triggerType == filter }
    }.stateIn(
        scope = stateScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis),
        initialValue = emptyList()
    )

    /**
     * Consolidated UI state flow combining wake word lists, filter state, sensitivity, and error status.
     */
    val uiState: StateFlow<WakeWordUiState> = combine(
        allWakeWords,
        _selectedTriggerType,
        _sensitivity,
        _isLoading,
        _errorMessage
    ) { all, filter, sens, loading, error ->
        val active = all.filter { it.isEnabled }
        val chat = all.filter { it.triggerType == TriggerType.CHAT }
        val handsFree = all.filter { it.triggerType == TriggerType.HANDS_FREE }
        val filtered = if (filter == null) all else all.filter { it.triggerType == filter }
        WakeWordUiState(
            wakeWords = all,
            activeWakeWords = active,
            chatWakeWords = chat,
            handsFreeWakeWords = handsFree,
            filteredWakeWords = filtered,
            selectedFilter = filter,
            sensitivity = sens,
            isLoading = loading,
            errorMessage = error
        )
    }.stateIn(
        scope = stateScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis),
        initialValue = WakeWordUiState()
    )

    /**
     * Adds a wake word by phrase, [TriggerType], and optional metadata/chat association.
     */
    fun addWakeWord(
        phrase: String,
        triggerType: TriggerType = TriggerType.CHAT,
        metadata: String? = null,
        chatId: Long? = null,
        chatTitle: String = "",
        sensitivity: Float = _sensitivity.value
    ): Job? {
        val trimmed = phrase.trim()
        if (trimmed.isBlank()) {
            _errorMessage.value = "Wake word phrase cannot be empty"
            return null
        }
        _errorMessage.value = null
        return stateScope.launch {
            val insertedId = if (triggerType == TriggerType.CHAT && chatId != null && chatId > 0L) {
                repository.addCustomChatWakeWord(
                    chatId = chatId,
                    chatTitle = chatTitle.ifBlank { metadata ?: "Gemini Chat" },
                    phrase = trimmed,
                    sensitivity = sensitivity,
                    metadata = metadata
                )
            } else {
                repository.addWakeWord(
                    phrase = trimmed,
                    triggerType = triggerType,
                    metadata = metadata,
                    chatId = chatId,
                    chatTitle = chatTitle,
                    sensitivity = sensitivity
                )
            }
            if (insertedId <= 0L) {
                _errorMessage.value = "Failed to save wake word \"$trimmed\""
            }
        }
    }

    /**
     * Adds a [WakeWord] entity directly to the repository.
     */
    fun addWakeWord(wakeWord: WakeWord): Job? {
        val trimmed = wakeWord.phrase.trim()
        if (trimmed.isBlank()) {
            _errorMessage.value = "Wake word phrase cannot be empty"
            return null
        }
        _errorMessage.value = null
        return stateScope.launch {
            val insertedId = repository.addWakeWord(wakeWord.copy(phrase = trimmed))
            if (insertedId <= 0L) {
                _errorMessage.value = "Failed to save wake word \"$trimmed\""
            }
        }
    }

    /**
     * Convenience method for the UI to add a custom wake word associated with a specific chat session.
     */
    fun addCustomChatWakeWord(
        chatId: Long,
        phrase: String,
        chatTitle: String = "Gemini Chat",
        metadata: String? = null
    ): Job? {
        return addWakeWord(
            phrase = phrase,
            triggerType = TriggerType.CHAT,
            metadata = metadata ?: chatTitle,
            chatId = chatId,
            chatTitle = chatTitle,
            sensitivity = _sensitivity.value
        )
    }

    /**
     * Removes a [WakeWord] entity via the repository.
     */
    fun removeWakeWord(wakeWord: WakeWord): Job {
        _errorMessage.value = null
        return stateScope.launch {
            repository.removeWakeWord(wakeWord)
        }
    }

    /**
     * Removes a wake word by its primary key [id].
     */
    fun removeWakeWord(id: Long): Job = removeWakeWordById(id)

    /**
     * Removes wake word(s) matching [phrase] case-insensitively.
     */
    fun removeWakeWord(phrase: String): Job? = removeWakeWordByPhrase(phrase)

    /**
     * Removes a wake word by its primary key [id].
     */
    fun removeWakeWordById(id: Long): Job {
        _errorMessage.value = null
        return stateScope.launch {
            repository.removeWakeWordById(id)
        }
    }

    /**
     * Removes wake word(s) matching [phrase] case-insensitively.
     */
    fun removeWakeWordByPhrase(phrase: String): Job? {
        val trimmed = phrase.trim()
        if (trimmed.isBlank()) return null
        _errorMessage.value = null
        return stateScope.launch {
            repository.removeWakeWordByPhrase(trimmed)
        }
    }

    /**
     * Alias for [removeWakeWord] for UI callbacks.
     */
    fun deleteWakeWord(wakeWord: WakeWord): Job = removeWakeWord(wakeWord)

    /**
     * Alias for [removeWakeWordById] for UI callbacks.
     */
    fun deleteWakeWordById(id: Long): Job = removeWakeWordById(id)

    /**
     * Alias for [removeWakeWordByPhrase] for UI callbacks.
     */
    fun deleteWakeWordByPhrase(phrase: String): Job? = removeWakeWordByPhrase(phrase)

    /**
     * Updates the phrase of an existing wake word by [id].
     */
    fun updateWakeWordPhrase(id: Long, newPhrase: String): Job? {
        val trimmed = newPhrase.trim()
        if (trimmed.isBlank()) {
            _errorMessage.value = "Wake word phrase cannot be empty"
            return null
        }
        _errorMessage.value = null
        return stateScope.launch {
            val updated = repository.updateWakeWordPhrase(id, trimmed)
            if (!updated) {
                _errorMessage.value = "Could not update wake word"
            }
        }
    }

    /**
     * Toggles whether a specific wake word is enabled or disabled.
     */
    fun toggleWakeWordEnabled(id: Long, enabled: Boolean): Job {
        return stateScope.launch {
            repository.setWakeWordEnabled(id, enabled)
        }
    }

    /**
     * Alias for [toggleWakeWordEnabled].
     */
    fun setWakeWordEnabled(id: Long, enabled: Boolean): Job = toggleWakeWordEnabled(id, enabled)

    /**
     * Updates the active [TriggerType] filter (`null` shows all wake words).
     */
    fun setFilter(triggerType: TriggerType?) {
        _selectedTriggerType.value = triggerType
    }

    /**
     * Updates the acoustic sensitivity across all wake words in the repository.
     */
    fun setSensitivity(sensitivity: Float): Job {
        val clamped = sensitivity.coerceIn(0.50f, 0.98f)
        _sensitivity.value = clamped
        return stateScope.launch {
            repository.updateSensitivity(clamped)
        }
    }

    /**
     * Updates global sensitivity to a predefined [com.example.voice.WakeSensitivityLevel].
     */
    fun setSensitivityLevel(level: com.example.voice.WakeSensitivityLevel): Job =
        setSensitivity(level.sensitivityValue)

    /**
     * Updates the sensitivity threshold (`0.50f..0.98f`) for an individual [WakeWord] by [id].
     */
    fun setWakeWordSensitivity(id: Long, sensitivity: Float): Job {
        val clamped = sensitivity.coerceIn(0.50f, 0.98f)
        return stateScope.launch {
            repository.updateWakeWordSensitivity(id, clamped)
        }
    }

    /**
     * Updates an individual [WakeWord]'s sensitivity to a predefined [com.example.voice.WakeSensitivityLevel].
     */
    fun setWakeWordSensitivityLevel(id: Long, level: com.example.voice.WakeSensitivityLevel): Job =
        setWakeWordSensitivity(id, level.sensitivityValue)

    /**
     * Re-binds an existing [WakeWord] ([id]) to a specific target [ChatSession] ([chatId]).
     */
    fun updateWakeWordChatBinding(id: Long, chatId: Long, chatTitle: String = ""): Job {
        _errorMessage.value = null
        return stateScope.launch {
            val updated = repository.updateWakeWordChatBinding(id, chatId, chatTitle)
            if (!updated) {
                _errorMessage.value = "Failed to bind wake word to chat"
            }
        }
    }

    /**
     * Creates a new chat conversation on the fly and binds a custom wake word phrase to it.
     */
    fun createChatAndBindWakeWord(
        chatTitle: String,
        phrase: String,
        sensitivity: Float = _sensitivity.value,
        onChatCreated: ((Long, Long) -> Unit)? = null
    ): Job? {
        val trimmedPhrase = phrase.trim()
        if (trimmedPhrase.isBlank()) {
            _errorMessage.value = "Wake word phrase cannot be empty"
            return null
        }
        _errorMessage.value = null
        return stateScope.launch {
            val (chatId, wakeId) = repository.createChatAndBindWakeWord(
                chatTitle = chatTitle.trim().ifBlank { "$trimmedPhrase Chat" },
                phrase = trimmedPhrase,
                sensitivity = sensitivity.coerceIn(0.50f, 0.98f)
            )
            if (wakeId <= 0L) {
                _errorMessage.value = "Failed to create chat and wake word \"$trimmedPhrase\""
            } else {
                onChatCreated?.invoke(chatId, wakeId)
            }
        }
    }

    /**
     * Creates a new chat conversation on the fly and re-assigns an existing wake word ([wakeWordId]) to it.
     */
    fun reassignWakeWordToNewChat(
        wakeWordId: Long,
        newChatTitle: String,
        onChatCreated: ((Long) -> Unit)? = null
    ): Job {
        _errorMessage.value = null
        return stateScope.launch {
            val newChatId = repository.reassignWakeWordToNewChat(wakeWordId, newChatTitle)
            if (newChatId <= 0L) {
                _errorMessage.value = "Failed to create new chat for wake word"
            } else {
                onChatCreated?.invoke(newChatId)
            }
        }
    }

    /**
     * Updates or inserts a hands-free system trigger (e.g., DICTATE, SEND, HEARTBEAT).
     */
    fun upsertSystemTrigger(role: String, phrase: String): Job {
        return stateScope.launch {
            repository.upsertSystemTrigger(role, phrase, _sensitivity.value)
        }
    }

    /**
     * Resets hands-free system triggers (Dictate, Send, Heartbeat) to defaults.
     */
    fun resetSystemTriggersToDefaults(): Job {
        return stateScope.launch {
            repository.resetSystemTriggersToDefaults(_sensitivity.value)
        }
    }

    /**
     * Clears any current validation or operation error message.
     */
    fun clearError() {
        _errorMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stateScope.cancel()
    }

    companion object {
        fun provideFactory(
            repository: WakeWordRepository,
            ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(WakeWordViewModel::class.java)) {
                    return WakeWordViewModel(repository, ioDispatcher) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }

        fun provideFactory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(WakeWordViewModel::class.java)) {
                        val db = AppDatabase.getDatabase(application)
                        val repo = WakeWordRepository(db.wakeWordDao(), db.chatDao())
                        return WakeWordViewModel(repo) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
