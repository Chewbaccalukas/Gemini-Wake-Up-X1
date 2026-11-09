package com.example

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.ChatSession
import com.example.ui.MainViewModel
import com.example.ui.WakeWordViewModel
import com.example.ui.components.CreateEditChatDialog
import com.example.ui.components.GeminiCouplerDialog
import com.example.ui.components.ImportGeminiChatDialog
import com.example.ui.components.MemoryBufferBridgeDialog
import com.example.ui.components.WakeWordSettingsDialog
import com.example.ui.screens.ChatDetailScreen
import com.example.ui.screens.CloudBackupVaultScreen
import com.example.ui.screens.DiagnosticLogScreen
import com.example.ui.screens.GoogleAuthScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.WakeWordRecordingScreen
import com.example.ui.auth.GoogleAuthManager
import com.example.ui.theme.AppTheme
import com.example.ui.theme.GeminiWakeTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.example.importer.FileProcessingService
import com.example.ui.components.AudioPermissionRationaleDialog
import com.example.util.WebLinkHistoryManager
import com.example.util.configureAppCheck
import com.example.voice.WakeWordVoiceService
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

sealed class Screen {
    object Home : Screen()
    object History : Screen()
    object Settings : Screen()
    object WakeWordStudio : Screen()
    object DiagnosticLog : Screen()
    object CloudBackupVault : Screen()
    data class Chat(val chatId: Long) : Screen()
}

sealed interface PendingAudioAction {
    object ToggleListening : PendingAudioAction
    object StartVoiceRecording : PendingAudioAction
    data class RecordWakeSnippet(val chatId: Long, val slotIndex: Int) : PendingAudioAction
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val wakeWordViewModel: WakeWordViewModel by viewModels {
        WakeWordViewModel.provideFactory(viewModel.wakeWordRepository)
    }
    private val sessionStatisticsViewModel: com.example.ui.SessionStatisticsViewModel by viewModels()
    private val incomingSharedText = mutableStateOf<String?>(null)
    private val incomingExternalWebUrl = mutableStateOf<String?>(null)
    private val incomingOpenBackupExport = mutableStateOf(false)
    private var currentScreen by mutableStateOf<Screen>(Screen.Home)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Mandatory App Check configuration with launch intent before super.onCreate or any lazy ViewModel/Firebase access
        configureAppCheck(this, intent)
        super.onCreate(savedInstanceState)

        handleIncomingIntent(intent)

        enableEdgeToEdge()

        setContent {
            val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()
            val currentChat by viewModel.currentChat.collectAsStateWithLifecycle()

            val effectiveTheme = if (currentChat != null && currentChat!!.themeMode != AppTheme.SYSTEM) {
                currentChat!!.themeMode
            } else {
                appTheme
            }

            GeminiWakeTheme(appTheme = effectiveTheme) {
                val authFlow = remember { GoogleAuthManager.observeAuthState() }
                val currentUser by authFlow.collectAsStateWithLifecycle(
                    initialValue = try {
                        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                    } catch (_: Exception) {
                        null
                    }
                )
                LaunchedEffect(currentUser) {
                    viewModel.onAuthStateChanged(currentUser)
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    if (currentUser == null) {
                        GoogleAuthScreen(
                            onSignedIn = {
                                val signedInUser = try {
                                    com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                                } catch (_: Exception) {
                                    null
                                }
                                viewModel.onAuthStateChanged(signedInUser)
                            }
                        )
                    } else {
                        MainApp(
                            viewModel = viewModel,
                            wakeWordViewModel = wakeWordViewModel,
                            sessionStatisticsViewModel = sessionStatisticsViewModel,
                            currentScreen = currentScreen,
                            onNavigate = { currentScreen = it },
                            incomingSharedText = incomingSharedText.value,
                            incomingExternalWebUrl = incomingExternalWebUrl.value,
                            incomingOpenBackupExport = incomingOpenBackupExport.value,
                            onClearIncomingSharedText = { incomingSharedText.value = null },
                            onClearIncomingExternalWebUrl = { incomingExternalWebUrl.value = null },
                            onClearIncomingOpenBackupExport = { incomingOpenBackupExport.value = false }
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
    }

    override fun onPause() {
        super.onPause()
        viewModel.onAppPaused()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onAppBackgrounded()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        viewModel.onWindowFocusChanged(hasFocus)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        configureAppCheck(this, intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: android.content.Intent?) {
        if (intent?.getBooleanExtra(com.example.heartbeat.BackupReminderWorker.EXTRA_OPEN_BACKUP_EXPORT, false) == true) {
            incomingOpenBackupExport.value = true
        }
        val targetChatId = intent?.getLongExtra(com.example.heartbeat.HeartbeatAlarmReceiver.EXTRA_TARGET_CHAT_ID, -1L) ?: -1L
        val wakeTargetChatId = intent?.getLongExtra(WakeWordVoiceService.EXTRA_WAKE_TARGET_CHAT_ID, -1L) ?: -1L
        val resolvedTargetChatId = when {
            wakeTargetChatId != -1L -> wakeTargetChatId
            targetChatId != -1L -> targetChatId
            else -> -1L
        }
        if (resolvedTargetChatId != -1L) {
            if (wakeTargetChatId != -1L) {
                WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("wake_word_detected")
            }
            viewModel.selectChat(resolvedTargetChatId)
            currentScreen = Screen.Chat(resolvedTargetChatId)
        }

        if (intent?.action == android.content.Intent.ACTION_SEND) {
            if (intent.type?.startsWith("text/") == true) {
                val sharedUri = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
                if (sharedUri != null) {
                    handlePickedFileUri(
                        uri = sharedUri,
                        targetChatId = viewModel.currentChatId.value,
                        action = FileProcessingService.ACTION_PROCESS_FILE_IMPORT
                    )
                } else {
                    val sharedText = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
                    if (!sharedText.isNullOrBlank()) {
                        val trimmed = sharedText.trim()
                        if (WebLinkHistoryManager.isDirectUrlOrDomain(trimmed)) {
                            val normalizedUrl = WebLinkHistoryManager.normalizeDirectUrl(trimmed)
                            viewModel.recordIncomingExternalLink(normalizedUrl)
                            incomingExternalWebUrl.value = normalizedUrl
                        } else {
                            incomingSharedText.value = sharedText
                        }
                    }
                }
            } else if (intent.type?.startsWith("image/") == true) {
                val imageUri = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
                if (imageUri != null) {
                    viewModel.stageImage(imageUri)
                    Toast.makeText(this, "Incoming screenshot staged for Coupler!", Toast.LENGTH_SHORT).show()
                }
            } else {
                val streamUri = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
                if (streamUri != null) {
                    handlePickedFileUri(
                        uri = streamUri,
                        targetChatId = viewModel.currentChatId.value,
                        action = FileProcessingService.ACTION_PROCESS_FILE_IMPORT
                    )
                }
            }
        } else if (intent?.action == android.content.Intent.ACTION_VIEW && intent.data != null) {
            val dataUri = intent.data!!
            val scheme = dataUri.scheme?.lowercase()
            if (scheme == "http" || scheme == "https") {
                val urlString = dataUri.toString()
                viewModel.recordIncomingExternalLink(urlString)
                incomingExternalWebUrl.value = urlString
            } else {
                handlePickedFileUri(
                    uri = dataUri,
                    targetChatId = viewModel.currentChatId.value,
                    action = FileProcessingService.ACTION_PROCESS_FILE_IMPORT
                )
            }
        }
    }

    /**
     * Builds an ACTION_OPEN_DOCUMENT file picker Intent filtered specifically for
     * .md, .txt, .pdf, .doc, .html, and .json files.
     */
    fun createSupportedFilePickerIntent(): Intent = Companion.createSupportedFilePickerIntent()

    /**
     * File picker intent result handler that validates the selected URI against
     * supported formats (.md, .txt, .pdf, .doc, .html, .json), persists read URI permission,
     * and dispatches the URI to the background FileProcessingService.
     */
    fun handlePickedFileUri(
        uri: Uri?,
        targetChatId: Long? = viewModel.currentChatId.value,
        action: String = FileProcessingService.ACTION_PROCESS_FILE_IMPORT
    ): Boolean = Companion.handlePickedFileUri(this, uri, targetChatId, action)

    companion object {
        /**
         * Builds an ACTION_OPEN_DOCUMENT file picker Intent filtered specifically for
         * .md, .txt, .pdf, .doc, .html, and .json files.
         */
        fun createSupportedFilePickerIntent(): Intent {
            return FileProcessingService.createFilePickerIntent()
        }

        /**
         * Validates the selected file URI against .md, .txt, .pdf, .doc, .html, and .json formats,
         * persists read URI permission, and passes the URI to the background FileProcessingService.
         */
        fun handlePickedFileUri(
            context: Context,
            uri: Uri?,
            targetChatId: Long? = null,
            action: String = FileProcessingService.ACTION_PROCESS_FILE_IMPORT
        ): Boolean {
            if (uri == null) return false

            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val fileName = FileProcessingService.resolveFileName(context, uri)
            val mimeType = FileProcessingService.resolveMimeType(context, uri, fileName)

            if (!FileProcessingService.isSupportedFile(fileName, mimeType)) {
                Toast.makeText(
                    context,
                    "Unsupported file type ($fileName). Please select a .md, .txt, .pdf, .doc, .html, or .json file.",
                    Toast.LENGTH_SHORT
                ).show()
                return false
            }

            FileProcessingService.startServiceForUri(
                context = context,
                uri = uri,
                targetChatId = targetChatId,
                action = action
            )
            return true
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(
    viewModel: MainViewModel,
    wakeWordViewModel: WakeWordViewModel = remember(viewModel) {
        WakeWordViewModel(viewModel.wakeWordRepository)
    },
    sessionStatisticsViewModel: com.example.ui.SessionStatisticsViewModel? = null,
    currentScreen: Screen,
    onNavigate: (Screen) -> Unit,
    incomingSharedText: String? = null,
    incomingExternalWebUrl: String? = null,
    incomingOpenBackupExport: Boolean = false,
    onClearIncomingSharedText: () -> Unit = {},
    onClearIncomingExternalWebUrl: () -> Unit = {},
    onClearIncomingOpenBackupExport: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    val chats by viewModel.allChats.collectAsStateWithLifecycle()
    val currentChatId by viewModel.currentChatId.collectAsStateWithLifecycle()
    val currentChat by viewModel.currentChat.collectAsStateWithLifecycle()
    val sessionStatisticsState = sessionStatisticsViewModel?.uiState?.collectAsStateWithLifecycle()?.value

    // Persistence & Custom Prompts
    val defaultPersistenceEnabled by viewModel.defaultPersistenceEnabled.collectAsStateWithLifecycle()
    val preserveCacheAcrossSessions by viewModel.preserveCacheAcrossSessions.collectAsStateWithLifecycle()
    val heartbeatPingOnlyMode by viewModel.heartbeatPingOnlyMode.collectAsStateWithLifecycle()
    val customPrompts by viewModel.customPrompts.collectAsStateWithLifecycle()
    val appTheme by viewModel.appTheme.collectAsStateWithLifecycle()

    // Hands-Free Voice Dictation & Triggers
    val dictateTrigger by viewModel.dictateTrigger.collectAsStateWithLifecycle()
    val sendTrigger by viewModel.sendTrigger.collectAsStateWithLifecycle()
    val heartbeatTrigger by viewModel.heartbeatTrigger.collectAsStateWithLifecycle()
    val isHandsFreeActive by viewModel.isHandsFreeActive.collectAsStateWithLifecycle()
    val handsFreeVoiceState by viewModel.handsFreeVoiceState.collectAsStateWithLifecycle()
    val currentDraftText by viewModel.currentDraftText.collectAsStateWithLifecycle()
    val wakeRetriggerEventId by viewModel.wakeRetriggerEventId.collectAsStateWithLifecycle()
    val pendingWakeQuery by viewModel.pendingWakeQuery.collectAsStateWithLifecycle()
    val pendingVoiceNavigationRoute by viewModel.pendingVoiceNavigationRoute.collectAsStateWithLifecycle()
    val isNavigatingViaVoiceCommand by viewModel.isNavigatingViaVoiceCommand.collectAsStateWithLifecycle()
    val hasWindowFocus by viewModel.hasWindowFocus.collectAsStateWithLifecycle()

    // Active stopwatch/timer tracking state for listed chats
    val activeTimerChatIds by viewModel.activeTimerChatIds.collectAsStateWithLifecycle()
    val liveTrackedSeconds by viewModel.liveTrackedSeconds.collectAsStateWithLifecycle()
    val openChatTimerSeconds by viewModel.openChatTimerSeconds.collectAsStateWithLifecycle()
    val isOpenChatTimerRunning by viewModel.isOpenChatTimerRunning.collectAsStateWithLifecycle()
    val showUsageLimitsComparison by viewModel.showUsageLimitsComparison.collectAsStateWithLifecycle()

    // Buffered hot memory messages to prevent freezing
    val hotMessages by viewModel.hotMessages.collectAsStateWithLifecycle()
    val allMessages by viewModel.allMessages.collectAsStateWithLifecycle()
    val bufferMetrics by viewModel.bufferMetrics.collectAsStateWithLifecycle()
    val relayServerState by viewModel.relayServerState.collectAsStateWithLifecycle()
    val hasMoreOlderMessages by viewModel.hasMoreOlderMessages.collectAsStateWithLifecycle()
    val isLoadingOlder by viewModel.isLoadingOlder.collectAsStateWithLifecycle()

    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val triggerBanner by viewModel.triggerBanner.collectAsStateWithLifecycle()
    val isAutoSpeakEnabled by viewModel.isAutoSpeakEnabled.collectAsStateWithLifecycle()
    val wakeWordSensitivity by viewModel.wakeWordSensitivity.collectAsStateWithLifecycle()
    val isSleepMode by viewModel.isSleepMode.collectAsStateWithLifecycle()
    val sleepModeReason by viewModel.sleepModeReason.collectAsStateWithLifecycle()
    val backgroundCueOnlyMode by viewModel.backgroundCueOnlyMode.collectAsStateWithLifecycle()
    val wakeReadyVisualCue by viewModel.wakeReadyVisualCue.collectAsStateWithLifecycle()
    val allWakeWordSamples by viewModel.allWakeWordSamples.collectAsStateWithLifecycle()
    val currentUserEmail by viewModel.currentUserEmail.collectAsStateWithLifecycle()
    val currentUserDisplayName by viewModel.currentUserDisplayName.collectAsStateWithLifecycle()
    val cloudSyncStatus by viewModel.cloudSyncStatus.collectAsStateWithLifecycle()
    val cloudBackupChats by viewModel.cloudBackupChats.collectAsStateWithLifecycle()
    val cloudBackupMessages by viewModel.cloudBackupMessages.collectAsStateWithLifecycle()
    val isCloudOperationInProgress by viewModel.isCloudOperationInProgress.collectAsStateWithLifecycle()
    val cloudVaultStatusMessage by viewModel.cloudVaultStatusMessage.collectAsStateWithLifecycle()
    val favoriteChatIds by viewModel.favoriteChatIds.collectAsStateWithLifecycle()
    val pinnedChatIds by viewModel.pinnedChatIds.collectAsStateWithLifecycle()
    val contextualRoutingEnabled by viewModel.contextualRoutingEnabled.collectAsStateWithLifecycle()
    val promptFavoritesOnWake by viewModel.promptFavoritesOnWake.collectAsStateWithLifecycle()
    val lastHygieneResetMessage by viewModel.lastHygieneResetMessage.collectAsStateWithLifecycle()
    val wakeConversationSummary by viewModel.wakeConversationSummary.collectAsStateWithLifecycle()
    val showWakeFavoritesPickerDialog by viewModel.showWakeFavoritesPickerDialog.collectAsStateWithLifecycle()
    val lastDetectedWakePhrase by viewModel.lastDetectedWakePhrase.collectAsStateWithLifecycle()
    val authScope = rememberCoroutineScope()

    var showControlSheet by remember { mutableStateOf(false) }
    var showPowerConfirmDialog by remember { mutableStateOf(false) }
    var showBackupExportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(incomingOpenBackupExport) {
        if (incomingOpenBackupExport) {
            showBackupExportDialog = true
            onClearIncomingOpenBackupExport()
        }
    }

    val exportDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val content = viewModel.exportCurrentChatAsPlainText()
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(content.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(context, "Chat exported successfully!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val saveJsonBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            sessionStatisticsViewModel?.saveBackupToDocumentUri(
                context = context,
                targetUri = uri,
                format = com.example.util.BackupExportFormat.JSON
            ) { _, msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val saveCsvBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) {
            sessionStatisticsViewModel?.saveBackupToDocumentUri(
                context = context,
                targetUri = uri,
                format = com.example.util.BackupExportFormat.CSV
            ) { _, msg ->
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val launchSaveBackupToDevice: (com.example.util.BackupExportFormat) -> Unit = { format ->
        val suggestedName = com.example.util.ChatLogJsonExporter.buildSuggestedBackupFileName(format)
        when (format) {
            com.example.util.BackupExportFormat.JSON -> saveJsonBackupLauncher.launch(suggestedName)
            com.example.util.BackupExportFormat.CSV -> saveCsvBackupLauncher.launch(suggestedName)
        }
    }

    val launchShareBackupFile: (com.example.util.BackupExportFormat) -> Unit = { format ->
        if (sessionStatisticsViewModel != null) {
            sessionStatisticsViewModel.shareBackup(
                context = context,
                format = format,
                onError = { err ->
                    Toast.makeText(context, "Export failed: $err", Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            viewModel.shareChatInteractionLogsAsJson(
                context = context,
                chatId = null,
                onError = { err ->
                    Toast.makeText(context, "Export failed: $err", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    val handleCreateNewWakeTargetChat: (String, String) -> Unit = { newTitle, wakePhrase ->
        val newChat = com.example.data.model.ChatSession(
            title = newTitle.trim().ifBlank { "Voice Assistant Chat" },
            wakeWord = wakePhrase.trim().ifBlank { "Hey Gemini" },
            isDefaultWakeChat = true,
            isPersistent = defaultPersistenceEnabled
        )
        viewModel.saveChat(newChat) { newId ->
            viewModel.setDefaultWakeChat(newId)
            Toast.makeText(
                context,
                "Created \"${newChat.title}\" as active wake target chat!",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val isListening by viewModel.wakeWordManager.isListening.collectAsStateWithLifecycle()
    val isSpeechRecognizerListening by viewModel.isSpeechRecognizerListening.collectAsStateWithLifecycle()
    val speechRecognizerRmsDb by viewModel.speechRecognizerRmsDb.collectAsStateWithLifecycle()
    val isChatTurnRecording by viewModel.isChatTurnRecording.collectAsStateWithLifecycle()
    val isInChatDictationActive by viewModel.isInChatDictationActive.collectAsStateWithLifecycle()
    val isUserVoiceTurn by viewModel.isUserVoiceTurn.collectAsStateWithLifecycle()
    val rawAudioRmsDb by viewModel.wakeWordManager.audioRmsDb.collectAsStateWithLifecycle()
    val audioRmsDb = maxOf(rawAudioRmsDb, speechRecognizerRmsDb)
    val statusMessage by viewModel.wakeWordManager.statusMessage.collectAsStateWithLifecycle()
    val lastHeardText by viewModel.wakeWordManager.lastHeardText.collectAsStateWithLifecycle()
    val attachedImageUri by viewModel.attachedImageUri.collectAsStateWithLifecycle()
    val stagedAttachment by viewModel.stagedAttachment.collectAsStateWithLifecycle()
    val stagedImageUri by viewModel.stagedImageUri.collectAsStateWithLifecycle()
    val stagedImageInfo by viewModel.stagedImageInfo.collectAsStateWithLifecycle()
    val screenshotBanner by viewModel.screenshotBanner.collectAsStateWithLifecycle()
    val isOrionVoicePlaying by viewModel.isOrionVoicePlaying.collectAsStateWithLifecycle()
    val voiceEngineMode by viewModel.voiceEngineMode.collectAsStateWithLifecycle()
    val selectedVoice by viewModel.selectedVoice.collectAsStateWithLifecycle()
    val wakeWordLogs by viewModel.wakeWordLogs.collectAsStateWithLifecycle()
    val speechTraceHistory by viewModel.speechTraceHistory.collectAsStateWithLifecycle()
    val coordinatorMutexSnapshot by viewModel.coordinatorMutexSnapshot.collectAsStateWithLifecycle()
    val hasExclusiveAudioFocus by viewModel.hasExclusiveAudioFocus.collectAsStateWithLifecycle()
    val importProgressState by viewModel.importProgressState.collectAsStateWithLifecycle()
    val activeKeyType by viewModel.activeKeyType.collectAsStateWithLifecycle()
    val primaryApiKey by viewModel.primaryApiKey.collectAsStateWithLifecycle()
    val backupApiKey by viewModel.backupApiKey.collectAsStateWithLifecycle()
    val isTtsSpeaking by viewModel.isTtsSpeaking.collectAsStateWithLifecycle()
    val isAudioSpeaking = isOrionVoicePlaying || isTtsSpeaking
    val masterVolume by viewModel.masterVolume.collectAsStateWithLifecycle()
    val autoStopLimitSec by viewModel.autoStopLimitSec.collectAsStateWithLifecycle()
    val networkState by viewModel.networkState.collectAsStateWithLifecycle()
    val notifyActiveChatReplies by viewModel.notifyActiveChatReplies.collectAsStateWithLifecycle()
    val permissionsResetCount by viewModel.permissionsResetCount.collectAsStateWithLifecycle()
    val permissionRationaleEnabled by viewModel.permissionRationaleEnabled.collectAsStateWithLifecycle()
    val webLinkHistory by viewModel.webLinkHistory.collectAsStateWithLifecycle()
    val voiceCommandHistory by viewModel.voiceCommandHistory.collectAsStateWithLifecycle()
    val requiresAudioPermission by viewModel.requiresAudioPermission.collectAsStateWithLifecycle()
    val isWakeHandoffInProgress by viewModel.isWakeHandoffInProgress.collectAsStateWithLifecycle()

    var showCreateEditDialog by remember { mutableStateOf(false) }
    var showSelectCategoryDialog by remember { mutableStateOf(false) }
    var chatToEdit by remember { mutableStateOf<ChatSession?>(null) }
    var showBufferBridgeDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importInitialText by remember { mutableStateOf("") }
    var pendingArchiveUri by remember { mutableStateOf<Uri?>(null) }
    var showArchivePromptDialog by remember { mutableStateOf(false) }

    var showCouplerDialog by remember { mutableStateOf(false) }
    var couplerInitialPayload by remember { mutableStateOf("") }
    var couplerInitialTab by remember { mutableIntStateOf(0) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val activity = context as? MainActivity
            val started = activity?.handlePickedFileUri(
                uri = uri,
                targetChatId = currentChatId,
                action = FileProcessingService.ACTION_PROCESS_FILE_IMPORT
            ) ?: run {
                FileProcessingService.startServiceForUri(
                    context = context,
                    uri = uri,
                    targetChatId = currentChatId,
                    action = FileProcessingService.ACTION_PROCESS_FILE_IMPORT
                )
                true
            }
            if (started) {
                Toast.makeText(context, "Processing file in background service...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Handle incoming share intents (e.g. from standard Gemini app share button or web browser)
    LaunchedEffect(incomingSharedText) {
        if (!incomingSharedText.isNullOrBlank()) {
            couplerInitialPayload = incomingSharedText
            couplerInitialTab = 1 // Open Piggyback Load tab
            showCouplerDialog = true
            onClearIncomingSharedText()
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview()
    ) { bitmap: Bitmap? ->
        if (bitmap != null) {
            // Save bitmap to file and stage it
            val file = java.io.File(context.cacheDir, "staged_camera_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            viewModel.stageFile(uri, context)
            com.example.coupler.GeminiCouplerBridge.stageImage(context, uri)
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val fileName = FileProcessingService.resolveFileName(context, uri)
            val mimeType = FileProcessingService.resolveMimeType(context, uri, fileName)

            if (FileProcessingService.isSupportedFile(fileName, mimeType)) {
                pendingArchiveUri = uri
                showArchivePromptDialog = true
            } else {
                Toast.makeText(
                    context,
                    "Unsupported file ($fileName). Allowed: .md, .txt, .pdf, .doc, .html, .json",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // Audio Permission Management & Pending Action Execution
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    var pendingAudioAction by remember { mutableStateOf<PendingAudioAction?>(null) }
    var showPermissionRationaleDialog by remember { mutableStateOf(false) }

    fun executePendingAction(action: PendingAudioAction) {
        when (action) {
            is PendingAudioAction.ToggleListening -> {
                viewModel.toggleHandsFreeVoice()
            }
            is PendingAudioAction.StartVoiceRecording -> {
                viewModel.toggleInternalDictation()
            }
            is PendingAudioAction.RecordWakeSnippet -> {
                viewModel.startRecordingWakeSnippet(action.chatId, action.slotIndex)
                Toast.makeText(context, "Recording snippet #${action.slotIndex + 1}...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
        val action = pendingAudioAction
        pendingAudioAction = null

        if (granted) {
            // Restore foreground window focus immediately after the system permission dialog closes
            viewModel.onWindowFocusChanged(true)
            viewModel.onAudioPermissionResult(true)
            when (action) {
                is PendingAudioAction.ToggleListening -> {
                    if (!viewModel.isHandsFreeActive.value) {
                        viewModel.toggleHandsFreeVoice(true)
                    } else {
                        viewModel.speechRecognitionCoordinator.startListening(userInitiated = true)
                    }
                }
                is PendingAudioAction.StartVoiceRecording -> {
                    if (!viewModel.isInChatDictationActive.value) {
                        viewModel.toggleInternalDictation()
                    }
                }
                is PendingAudioAction.RecordWakeSnippet -> {
                    executePendingAction(action)
                }
                null -> {
                    Toast.makeText(context, "Microphone access enabled.", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            viewModel.onAudioPermissionResult(false)
            if (permissionRationaleEnabled) {
                showPermissionRationaleDialog = true
            }
        }
    }

    val requiredPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        hasAudioPermission = audioGranted
        viewModel.onAudioPermissionResult(audioGranted)
        val grantedCount = results.values.count { it }
        val totalRequested = results.size
        if (totalRequested > 0) {
            Toast.makeText(
                context,
                "Permissions updated ($grantedCount/$totalRequested granted).",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun launchRequiredPermissionsRequest() {
        val permsToRequest = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requiredPermissionsLauncher.launch(permsToRequest.toTypedArray())
    }

    fun handleResetPermissions() {
        viewModel.resetPermissionsState()
        showPermissionRationaleDialog = false
        pendingAudioAction = null
        hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        Toast.makeText(
            context,
            "In-app permission flags reset & rationale prompts re-enabled",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun requestAudioPermissionAndExecute(action: PendingAudioAction) {
        hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasAudioPermission) {
            executePendingAction(action)
        } else {
            pendingAudioAction = action
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Lifecycle Observer: Re-check permission on resume (e.g. returning from System App Settings)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (granted && !hasAudioPermission) {
                    hasAudioPermission = true
                    val action = pendingAudioAction
                    if (action != null) {
                        pendingAudioAction = null
                        executePendingAction(action)
                    }
                } else {
                    hasAudioPermission = granted
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    fun handleToggleListening() {
        requestAudioPermissionAndExecute(PendingAudioAction.ToggleListening)
    }

    // Observe ViewModel microphone permission requests (e.g., when SpeechRecognizer or Hands-Free requires RECORD_AUDIO)
    LaunchedEffect(requiresAudioPermission) {
        if (requiresAudioPermission) {
            viewModel.consumeAudioPermissionRequest()
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val navController = rememberNavController()

    fun isValidNavHostRoute(route: String?): Boolean {
        val clean = route?.trim() ?: return false
        if (clean.isEmpty()) return false
        return when {
            clean == "home" || clean == "chat" || clean == "history" || clean == "settings" || clean == "studio" || clean == "diagnostics" || clean == "cloud_vault" -> true
            clean.startsWith("chat/") -> {
                val id = clean.removePrefix("chat/").toLongOrNull()
                id != null && id > 0L
            }
            else -> false
        }
    }

    fun navigateNavControllerSafely(targetRoute: String) {
        if (!isValidNavHostRoute(targetRoute)) return
        runCatching {
            val currentRoute = navController.currentDestination?.route
            if (currentRoute != null && currentRoute != targetRoute) {
                navController.navigate(targetRoute) {
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    val navigateInApp: (Screen) -> Unit = { targetScreen ->
        onNavigate(targetScreen)
        val targetRoute = when (targetScreen) {
            is Screen.Home -> "home"
            is Screen.History -> "history"
            is Screen.Settings -> "settings"
            is Screen.WakeWordStudio -> "studio"
            is Screen.DiagnosticLog -> "diagnostics"
            is Screen.CloudBackupVault -> "cloud_vault"
            is Screen.Chat -> "chat/${targetScreen.chatId}"
        }
        navigateNavControllerSafely(targetRoute)
    }

    LaunchedEffect(currentScreen) {
        val targetRoute = when (val s = currentScreen) {
            is Screen.Home -> "home"
            is Screen.History -> "history"
            is Screen.Settings -> "settings"
            is Screen.WakeWordStudio -> "studio"
            is Screen.DiagnosticLog -> "diagnostics"
            is Screen.CloudBackupVault -> "cloud_vault"
            is Screen.Chat -> "chat/${s.chatId}"
        }
        navigateNavControllerSafely(targetRoute)
    }

    // Consume voice navigation commands (such as "open chat", "open history", "show history", "voice history")
    // purely in-memory via root NavController without ever calling startActivity() or external intents.
    LaunchedEffect(pendingVoiceNavigationRoute) {
        val route = pendingVoiceNavigationRoute
        if (route != null) {
            // Immediately clear/consume pending state so it cannot re-trigger on resume or recomposition
            viewModel.consumePendingVoiceNavigationRoute()
            val cleanRoute = route.trim()
            if (isValidNavHostRoute(cleanRoute)) {
                showControlSheet = false
                when {
                    cleanRoute == "history" -> {
                        onNavigate(Screen.History)
                        runCatching {
                            if (navController.currentDestination != null) {
                                navController.navigate("history") {
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        }
                    }
                    cleanRoute == "home" -> navigateInApp(Screen.Home)
                    cleanRoute == "settings" -> navigateInApp(Screen.Settings)
                    cleanRoute == "studio" -> navigateInApp(Screen.WakeWordStudio)
                    cleanRoute == "diagnostics" -> navigateInApp(Screen.DiagnosticLog)
                    cleanRoute == "chat" -> {
                        val targetId = currentChatId
                            ?: chats.firstOrNull { it.isDefaultWakeChat }?.id
                            ?: chats.firstOrNull()?.id
                            ?: 1L
                        viewModel.selectChat(targetId)
                        navigateInApp(Screen.Chat(targetId))
                    }
                    cleanRoute.startsWith("chat/") -> {
                        val chatId = cleanRoute.removePrefix("chat/").toLongOrNull()
                        if (chatId != null && chatId > 0L) {
                            viewModel.selectChat(chatId)
                            navigateInApp(Screen.Chat(chatId))
                        }
                    }
                }
                androidx.compose.runtime.withFrameNanos { }
                viewModel.onVoiceNavigationDestinationCommitted(cleanRoute)
            }
        }
    }

    // Root-level continuous voice radar lifecycle across ALL screens (Home, ChatDetail, Settings, History, Studio).
    // Note: Do NOT key on transient hasWindowFocus changes here so notification shade pull-downs or dialogs
    // never bypass the strict 1500ms windowFocus debounce in SpeechRecognitionCoordinator and WakeWordManager.
    // Also guard against isWakeHandoffInProgress, isTtsSpeaking, and speechRecognitionCoordinator.isProcessing
    // so transitioning to ChatScreen never aggressively grabs the mic before the wake-word or TTS handoff finishes.
    LaunchedEffect(
        hasAudioPermission,
        isHandsFreeActive,
        isUserVoiceTurn,
        isInChatDictationActive,
        isSleepMode,
        isNavigatingViaVoiceCommand,
        isWakeHandoffInProgress,
        isTtsSpeaking,
        currentScreen
    ) {
        if (hasWindowFocus &&
            hasAudioPermission &&
            isHandsFreeActive &&
            !isSleepMode &&
            !isListening &&
            !isSpeechRecognizerListening &&
            !isInChatDictationActive &&
            !viewModel.speechRecognitionCoordinator.isInChatDictationMode &&
            !viewModel.speechRecognitionCoordinator.hasPendingRestart &&
            !viewModel.speechRecognitionCoordinator.hasPendingWindowFocusDebounce &&
            !viewModel.speechRecognitionCoordinator.isProcessing &&
            !isUserVoiceTurn &&
            !isNavigatingViaVoiceCommand &&
            !isWakeHandoffInProgress &&
            !isTtsSpeaking &&
            !viewModel.textToSpeechManager.hasPendingSettlingDelay &&
            !viewModel.wakeWordManager.isTransitioningAudioState
        ) {
            if (currentScreen !is Screen.Chat) {
                WakeWordVoiceService.startServiceSafely(
                    context = context,
                    action = WakeWordVoiceService.ACTION_START_VOICE_SERVICE
                )
            }
            viewModel.startContinuousWakeWordListening(userInitiated = false)
        }
    }

    // Automatically open or refresh the corresponding chat screen via Compose NavController ("chat/$targetId")
    // when a wake word triggers a chat turn—never calling startActivity() or external intents.
    LaunchedEffect(wakeRetriggerEventId, triggerBanner, currentChatId, pendingWakeQuery) {
        val targetId = currentChatId
        if (!isNavigatingViaVoiceCommand &&
            (triggerBanner != null || !pendingWakeQuery.isNullOrBlank()) &&
            targetId != null &&
            targetId > 0L
        ) {
            WakeWordVoiceService.stopAndReleaseServiceAudioRecordSynchronously("wake_word_detected")
            showControlSheet = false
            if (currentScreen != Screen.Chat(targetId)) {
                navigateInApp(Screen.Chat(targetId))
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(heartbeatPingOnlyMode) {
        if (heartbeatPingOnlyMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Hidden/synced Compose NavHost backing in-app route navigation ("home", "chat/{chatId}", "settings", "history", "studio", "cloud_vault")
    NavHost(
        navController = navController,
        startDestination = "home"
    ) {
        composable("home") {}
        composable("history") {}
        composable("settings") {}
        composable("studio") {}
        composable("diagnostics") {}
        composable("cloud_vault") {}
        composable(
            route = "chat/{chatId}",
            arguments = listOf(navArgument("chatId") { type = NavType.LongType })
        ) {}
    }

    val activeChatForScreen = currentChat ?: (currentScreen as? Screen.Chat)?.let { screen ->
        chats.firstOrNull { it.id == screen.chatId }
            ?: ChatSession(
                id = screen.chatId,
                title = "Gemini Chat",
                wakeWord = "Hey Gemini"
            )
    }

    if (currentScreen is Screen.Chat && activeChatForScreen != null) {
        androidx.compose.runtime.key(activeChatForScreen.id) {
            ChatDetailScreen(
                chat = activeChatForScreen,
                messages = hotMessages,
                streamingText = streamingText,
                isGenerating = isGenerating,
                errorMessage = errorMessage,
                triggerBanner = triggerBanner,
                isAutoSpeakEnabled = isAutoSpeakEnabled,
                isSpeaking = isAudioSpeaking,
                bufferMetrics = bufferMetrics,
                hasMoreOlderMessages = hasMoreOlderMessages,
                isLoadingOlder = isLoadingOlder,
                customPrompts = customPrompts,
                onBack = { navigateInApp(Screen.Home) },
                onSendMessage = { text ->
                    viewModel.sendMessage(activeChatForScreen.id, text)
                },
                onToggleAutoSpeak = { viewModel.toggleAutoSpeak() },
                onSpeakText = { text -> viewModel.speakMessage(text) },
                onStopSpeaking = { viewModel.stopSpeaking() },
                onClearHistory = { viewModel.clearChatHistory(activeChatForScreen.id) },
                onEditChat = {
                    chatToEdit = activeChatForScreen
                    showCreateEditDialog = true
                },
                onLoadOlderMessages = { viewModel.loadOlderMessages() },
                onOpenBufferBridgeDialog = { showBufferBridgeDialog = true },
                onOpenCouplerDialog = {
                    couplerInitialPayload = ""
                    couplerInitialTab = 0
                    showCouplerDialog = true
                },
                onDismissTriggerBanner = { viewModel.clearTriggerBanner() },
                onToggleChatPersistence = { viewModel.toggleChatPersistence(activeChatForScreen.id) },
                openChatTimerSeconds = openChatTimerSeconds,
                isOpenChatTimerRunning = isOpenChatTimerRunning,
                showUsageLimitsComparison = showUsageLimitsComparison,
                onToggleOpenChatTimer = { viewModel.toggleOpenChatTimer() },
                onResetOpenChatTimer = { viewModel.resetOpenChatTimer() },
                onToggleUsageLimitsComparison = { enabled -> viewModel.toggleUsageLimitsComparison(enabled) },
                onAddCustomPrompt = { pTitle, pText -> viewModel.addCustomPrompt(pTitle, pText) },
                onDeleteCustomPrompt = { id -> viewModel.deleteCustomPrompt(id) },
                isHandsFreeActive = isHandsFreeActive,
                handsFreeVoiceState = handsFreeVoiceState,
                dictateTrigger = dictateTrigger,
                sendTrigger = sendTrigger,
                heartbeatTrigger = heartbeatTrigger,
                draftSpokenText = currentDraftText,
                onToggleHandsFree = { handleToggleListening() },
                stagedImageUri = stagedImageUri,
                stagedImageInfo = stagedImageInfo,
                screenshotBanner = screenshotBanner,
                onDismissScreenshotBanner = { viewModel.dismissScreenshotBanner() },
                onRemoveStagedImage = { viewModel.clearStagedImage() },
                onDispatchScreenshotToCoupler = { prompt ->
                    currentChat?.id?.let { id ->
                        viewModel.dispatchScreenshotToCoupler(id, prompt)
                        viewModel.clearStagedImage()
                    }
                },
                importProgressState = importProgressState,
                onCancelImport = { viewModel.cancelChatImport() },
                activeKeyType = activeKeyType,
                onToggleActiveKey = { viewModel.toggleActiveKey() },
                onTakePhoto = { cameraLauncher.launch(null) },
                onBrowseFiles = { documentPickerLauncher.launch(FileProcessingService.SUPPORTED_MIME_TYPES) },
                stagedAttachment = stagedAttachment,
                onClearStagedAttachment = { viewModel.clearStagedAttachment() },
                onUpdateChatTheme = { theme ->
                    currentChat?.id?.let { id ->
                        viewModel.updateChatTheme(id, theme)
                    }
                },
                onOpenControlSheet = { showControlSheet = true },
                onOpenImportDialog = {
                    importInitialText = ""
                    showImportDialog = true
                },
                onPowerExit = { showPowerConfirmDialog = true },
                isVoiceRecording = isChatTurnRecording,
                isInChatDictationActive = isInChatDictationActive,
                audioRmsDb = audioRmsDb,
                viewModel = viewModel,
                onStartVoiceRecording = {
                    viewModel.toggleInternalDictation()
                },
                onToggleInChatDictation = {
                    viewModel.toggleInternalDictation()
                },
                onSendVoiceRecording = { promptText ->
                    viewModel.finishVoiceRecordingAndSend(promptText)
                },
                onCancelVoiceRecording = {
                    viewModel.cancelVoiceRecording()
                },
                networkState = networkState,
                onRefreshNetwork = { viewModel.refreshNetworkStatus() },
                webLinkHistory = webLinkHistory,
                onOpenChatLink = { url, label ->
                    val entry = viewModel.recordChatLinkOpened(url, label, currentChat?.title)
                    val targetUrl = entry?.url ?: url
                    viewModel.openExternalUrlWithTransientGuard(context, targetUrl)
                },
                onSubmitWebSearchOrUrl = { queryOrUrl ->
                    val entry = viewModel.submitWebSearchOrUrl(queryOrUrl)
                    if (entry != null) {
                        viewModel.openExternalUrlWithTransientGuard(context, entry.url)
                    }
                },
                onOpenWebHistoryEntry = { entry ->
                    viewModel.submitWebSearchOrUrl(entry.url)
                    viewModel.openExternalUrlWithTransientGuard(context, entry.url)
                },
                onDeleteWebHistoryEntry = { id -> viewModel.deleteWebHistoryEntry(id) },
                onClearAllWebHistory = { viewModel.clearAllWebHistory() },
                voiceCommandHistory = voiceCommandHistory,
                onDeleteVoiceCommandEntry = { id -> viewModel.deleteVoiceCommandEntry(id) },
                onClearAllVoiceCommands = { viewModel.clearAllVoiceCommandHistory() },
                onClearCorruptedHistory = {
                    val count = viewModel.clearCorruptedHistory()
                    Toast.makeText(context, "History verified & repaired ($count entries cleaned).", Toast.LENGTH_SHORT).show()
                },
                onTextInputFocusChanged = { focused -> viewModel.onTextInputFocusChanged(focused) },
                wakeRetriggerEventId = wakeRetriggerEventId,
                isSleepMode = isSleepMode,
                sleepModeReason = sleepModeReason,
                onEnterSleepMode = { viewModel.enterSleepMode("Manual sleep mode enabled") },
                onExitSleepMode = { viewModel.exitSleepModeAndResumeListening() },
                onShareLogsAsJson = {
                    if (sessionStatisticsViewModel != null) {
                        sessionStatisticsViewModel.shareBackup(
                            context = context,
                            format = com.example.util.BackupExportFormat.JSON,
                            chatId = currentChat?.id,
                            onError = { err ->
                                Toast.makeText(context, "JSON export failed: $err", Toast.LENGTH_SHORT).show()
                            }
                        )
                    } else {
                        viewModel.shareChatInteractionLogsAsJson(
                            context = context,
                            chatId = currentChat?.id,
                            onError = { err ->
                                Toast.makeText(context, "JSON export failed: $err", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                },
                onShareLogsAsCsv = {
                    sessionStatisticsViewModel?.shareBackup(
                        context = context,
                        format = com.example.util.BackupExportFormat.CSV,
                        chatId = currentChat?.id,
                        onError = { err ->
                            Toast.makeText(context, "CSV export failed: $err", Toast.LENGTH_SHORT).show()
                        }
                    )
                },
                isWakeWordListening = isListening || isSpeechRecognizerListening,
                lastHeardText = lastHeardText,
                wakeConversationSummary = wakeConversationSummary?.takeIf { it.chatId == activeChatForScreen.id },
                onGenerateWakeAiSummary = { viewModel.generateWakeAiSummary() },
                onOpenWakeFavoritesPicker = { viewModel.openWakeFavoritesPicker() },
                onDismissWakeSummary = { viewModel.dismissWakeConversationSummary() },
                isFavoriteChat = favoriteChatIds.contains(activeChatForScreen.id),
                onToggleFavoriteChat = {
                    val isNowFav = viewModel.toggleFavoriteChat(activeChatForScreen.id)
                    Toast.makeText(
                        context,
                        if (isNowFav) "Added \"${activeChatForScreen.title}\" to Favorite Wake Chats" else "Removed from Favorite Wake Chats",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                isPinnedChat = activeChatForScreen.isPinned || pinnedChatIds.contains(activeChatForScreen.id),
                onTogglePinChat = {
                    val isNowPinned = viewModel.togglePinnedChat(activeChatForScreen.id)
                    Toast.makeText(
                        context,
                        if (isNowPinned) "Pinned \"${activeChatForScreen.title}\" to top of list" else "Unpinned \"${activeChatForScreen.title}\"",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                chatCategory = viewModel.chatCategoryMap.collectAsStateWithLifecycle().value[activeChatForScreen.id],
                chatTags = viewModel.chatTagsMap.collectAsStateWithLifecycle().value[activeChatForScreen.id].orEmpty(),
                allChats = chats,
                chatCategoryMap = viewModel.chatCategoryMap.collectAsStateWithLifecycle().value,
                messageCategoryMap = viewModel.messageCategoryMap.collectAsStateWithLifecycle().value,
                onSelectChatFromCategoryBar = { targetId ->
                    viewModel.selectChat(targetId)
                    onNavigate(Screen.Chat(targetId))
                },
                onReassignChatCategory = { newCategory ->
                    viewModel.reassignChatAndMessagesCategory(activeChatForScreen.id, newCategory)
                    Toast.makeText(
                        context,
                        "Assigned \"${activeChatForScreen.title}\" & messages to $newCategory",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                attachedImageUri = attachedImageUri,
                onStageAttachedImageUri = { uri ->
                    viewModel.stageAttachedImageUri(uri, context)
                },
                onClearAttachedImage = {
                    viewModel.clearAttachedImage()
                },
                onCheckAndStageClipboardImage = {
                    viewModel.checkAndStageClipboardImage(context)
                },
                onUpdateChatAppearance = { paletteId, backgroundStyle, primaryHex ->
                    viewModel.updateChatAppearance(
                        chatId = activeChatForScreen.id,
                        colorPaletteId = paletteId,
                        backgroundStyle = backgroundStyle,
                        themeColorHex = primaryHex
                    )
                },
                pendingWakeQuery = pendingWakeQuery,
                onConsumePendingWakeQuery = { viewModel.consumePendingWakeQuery() }
            )
        }
    } else if (currentScreen is Screen.WakeWordStudio) {
        MainWakeWordStudioSection(
            viewModel = viewModel,
            wakeWordViewModel = wakeWordViewModel,
            chats = chats,
            onNavigate = navigateInApp,
            onToggleListening = { handleToggleListening() },
            onRequestRecordSnippet = { chatId, slotIndex ->
                requestAudioPermissionAndExecute(PendingAudioAction.RecordWakeSnippet(chatId, slotIndex))
            }
        )
    } else if (currentScreen is Screen.History) {
        HistoryScreen(
            messages = allMessages,
            chats = chats,
            onBack = { navigateInApp(Screen.Home) },
            onDeleteMessage = { viewModel.deleteMessage(it) },
            onClearCorruptedHistory = {
                val count = viewModel.clearCorruptedHistory()
                Toast.makeText(context, "History verified & repaired ($count entries cleaned).", Toast.LENGTH_SHORT).show()
            },
            onShareLogsAsJson = {
                launchShareBackupFile(com.example.util.BackupExportFormat.JSON)
            },
            onShareLogsAsCsv = {
                launchShareBackupFile(com.example.util.BackupExportFormat.CSV)
            },
            onOpenBackupExportDialog = { showBackupExportDialog = true },
            sessionStatisticsState = sessionStatisticsState,
            onSelectStatisticsDateRange = { range ->
                sessionStatisticsViewModel?.selectDateRange(range)
            },
            voiceCommands = voiceCommandHistory,
            onDeleteVoiceCommand = { id -> viewModel.deleteVoiceCommandEntry(id) },
            onNavigateToChat = { chatId ->
                if (chatId > 0L) {
                    viewModel.selectChat(chatId)
                    navigateInApp(Screen.Chat(chatId))
                }
            }
        )
    } else if (currentScreen is Screen.DiagnosticLog) {
        DiagnosticLogScreen(
            speechTraceHistory = speechTraceHistory,
            wakeWordLogs = wakeWordLogs,
            mutexSnapshot = coordinatorMutexSnapshot,
            isSpeechRecognizerListening = isSpeechRecognizerListening,
            isWakeManagerListening = isListening,
            hasWindowFocus = hasWindowFocus,
            isSleepMode = isSleepMode,
            isNavigatingViaVoiceCommand = isNavigatingViaVoiceCommand,
            hasExclusiveAudioFocus = hasExclusiveAudioFocus,
            isHandsFreeActive = isHandsFreeActive,
            audioRmsDb = audioRmsDb,
            statusMessage = statusMessage,
            lastHeardText = lastHeardText,
            activeWakePhrase = chats.firstOrNull { it.isDefaultWakeChat }?.wakeWord ?: "Hey Gemini",
            onBack = { navigateInApp(Screen.Home) },
            onToggleRecognizer = {
                viewModel.toggleDiagnosticSpeechRecognizer()
            },
            onSimulateWakeEvent = { phrase ->
                viewModel.simulateDiagnosticWakeWordEvent(wakePhrase = phrase)
            },
            onResetMutexLocks = {
                viewModel.resetDiagnosticAudioMutexLocks()
            },
            onClearLogs = {
                viewModel.clearDiagnosticTraceLogs()
            },
            onBuildDiagnosticReport = {
                viewModel.buildDiagnosticReportText()
            }
        )
    } else if (currentScreen is Screen.Settings) {
        MainSettingsSection(
            viewModel = viewModel,
            wakeWordViewModel = wakeWordViewModel,
            chats = chats,
            sessionStatisticsState = sessionStatisticsState,
            onNavigate = navigateInApp,
            onToggleListening = { handleToggleListening() },
            onCreateNewWakeTargetChat = handleCreateNewWakeTargetChat,
            onSaveBackupToDevice = launchSaveBackupToDevice,
            onShareBackupFile = launchShareBackupFile,
            onRequestRecordSnippet = { chatId, slotIndex ->
                requestAudioPermissionAndExecute(PendingAudioAction.RecordWakeSnippet(chatId, slotIndex))
            }
        )
    } else if (currentScreen is Screen.CloudBackupVault) {
        CloudBackupVaultScreen(
            cloudChats = cloudBackupChats,
            cloudMessages = cloudBackupMessages,
            localChats = chats,
            userEmail = currentUserEmail,
            userDisplayName = currentUserDisplayName,
            cloudSyncStatus = cloudSyncStatus,
            isCloudOperationInProgress = isCloudOperationInProgress,
            cloudVaultStatusMessage = cloudVaultStatusMessage,
            onBack = { navigateInApp(Screen.Home) },
            onSyncNow = {
                viewModel.triggerManualCloudSync()
                Toast.makeText(context, "Refreshing Cloud Backup Vault...", Toast.LENGTH_SHORT).show()
            },
            onSignOut = {
                authScope.launch {
                    GoogleAuthManager.signOut(context)
                    viewModel.onAuthStateChanged(null)
                    Toast.makeText(context, "Signed out of Google Account.", Toast.LENGTH_SHORT).show()
                }
            },
            onRestoreToDevice = { cloudDoc ->
                viewModel.restoreChatFromCloud(cloudDoc) { restoredChatId ->
                    if (restoredChatId > 0L) {
                        navigateInApp(Screen.Chat(restoredChatId))
                    }
                }
            },
            onDeleteFromCloud = { cloudDoc ->
                viewModel.deleteChatFromCloud(cloudDoc)
            }
        )
    } else {
        HomeScreen(
            chats = chats,
            isListening = isListening,
            audioRmsDb = audioRmsDb,
            statusMessage = statusMessage,
            lastHeardText = lastHeardText,
            defaultPersistenceEnabled = defaultPersistenceEnabled,
            preserveCacheAcrossSessions = preserveCacheAcrossSessions,
            heartbeatPingOnlyMode = heartbeatPingOnlyMode,
            isHandsFreeActive = isHandsFreeActive,
            dictateTrigger = dictateTrigger,
            sendTrigger = sendTrigger,
            heartbeatTrigger = heartbeatTrigger,
            activeTimerChatIds = activeTimerChatIds,
            liveTrackedSeconds = liveTrackedSeconds,
            voiceEngineMode = voiceEngineMode,
            isOrionVoicePlaying = isOrionVoicePlaying,
            onSetVoiceEngineMode = { viewModel.setVoiceEngineMode(it) },
            onPlayOrionTestSample = { viewModel.playOrionTestSample() },
            onToggleDefaultPersistence = { enabled -> viewModel.setDefaultPersistenceEnabled(enabled) },
            onTogglePreserveCacheAcrossSessions = { enabled -> viewModel.setPreserveCacheAcrossSessions(enabled) },
            onToggleHeartbeatPingOnlyMode = { enabled -> viewModel.setHeartbeatPingOnlyMode(enabled) },
            onToggleHandsFree = { enabled -> viewModel.toggleHandsFreeVoice(enabled) },
            onToggleListening = { handleToggleListening() },
            onSelectChat = { id ->
                if (id > 0L) {
                    viewModel.selectChat(id)
                    navigateInApp(Screen.Chat(id))
                }
            },
            onCreateChat = {
                chatToEdit = null
                showSelectCategoryDialog = true
            },
            onEditChat = { chat ->
                chatToEdit = chat
                showCreateEditDialog = true
            },
            onDeleteChat = { id -> viewModel.deleteChat(id) },
            onSetDefaultWakeChat = { id -> viewModel.setDefaultWakeChat(id) },
            onOpenWakeSettings = { navigateInApp(Screen.Settings) },
            onNavigateToSettings = { navigateInApp(Screen.Settings) },
            onNavigateToWakeWordStudio = { navigateInApp(Screen.WakeWordStudio) },
            onNavigateToDiagnostics = { navigateInApp(Screen.DiagnosticLog) },
            isSleepMode = isSleepMode,
            sleepModeReason = sleepModeReason,
            wakeReadyVisualCue = wakeReadyVisualCue,
            onEnterSleepMode = { viewModel.enterSleepMode("Manual sleep mode enabled") },
            onExitSleepMode = { viewModel.exitSleepModeAndResumeListening() },
            onDismissWakeReadyCue = { viewModel.dismissWakeReadyVisualCue() },
            onOpenImportDialog = {
                importInitialText = ""
                showImportDialog = true
            },
            onOpenCouplerDialog = {
                couplerInitialPayload = ""
                couplerInitialTab = 1
                showCouplerDialog = true
            },
            onToggleChatTimer = { id -> viewModel.toggleChatTimer(id) },
            onResetChatTimer = { id -> viewModel.resetChatTimer(id) },
            importProgressState = importProgressState,
            onCancelImport = { viewModel.cancelChatImport() },
            appTheme = appTheme,
            onSetAppTheme = { viewModel.setAppTheme(it) },
            onNavigateToHistory = { navigateInApp(Screen.History) },
            onOpenControlSheet = { showControlSheet = true },
            onPowerExit = { showPowerConfirmDialog = true },
            networkState = networkState,
            onRefreshNetwork = { viewModel.refreshNetworkStatus() },
            webLinkHistory = webLinkHistory,
            onSubmitWebSearchOrUrl = { queryOrUrl ->
                val entry = viewModel.submitWebSearchOrUrl(queryOrUrl)
                if (entry != null) {
                    viewModel.openExternalUrlWithTransientGuard(context, entry.url)
                }
            },
            onOpenWebHistoryEntry = { entry ->
                viewModel.submitWebSearchOrUrl(entry.url)
                viewModel.openExternalUrlWithTransientGuard(context, entry.url)
            },
            onDeleteWebHistoryEntry = { id -> viewModel.deleteWebHistoryEntry(id) },
            onClearAllWebHistory = { viewModel.clearAllWebHistory() },
            voiceCommandHistory = voiceCommandHistory,
            onUseVoiceCommandTranscript = { voiceEntry ->
                Toast.makeText(
                    context,
                    "Loaded transcript: \"${voiceEntry.recognizedTranscript}\"",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onDeleteVoiceCommandEntry = { id -> viewModel.deleteVoiceCommandEntry(id) },
            onClearAllVoiceCommands = { viewModel.clearAllVoiceCommandHistory() },
            onClearCorruptedHistory = {
                val count = viewModel.clearCorruptedHistory()
                Toast.makeText(context, "History verified & repaired ($count entries cleaned).", Toast.LENGTH_SHORT).show()
            },
            onTextInputFocusChanged = { focused -> viewModel.onTextInputFocusChanged(focused) },
            userEmail = currentUserEmail,
            userDisplayName = currentUserDisplayName,
            cloudSyncStatus = cloudSyncStatus,
            onSyncNow = {
                viewModel.triggerManualCloudSync()
                Toast.makeText(context, "Syncing with Cloud Firestore...", Toast.LENGTH_SHORT).show()
            },
            onSignOut = {
                authScope.launch {
                    GoogleAuthManager.signOut(context)
                    viewModel.onAuthStateChanged(null)
                    Toast.makeText(context, "Signed out of Google Account.", Toast.LENGTH_SHORT).show()
                }
            },
            sessionStatisticsState = sessionStatisticsState,
            onSelectStatisticsDateRange = { range ->
                sessionStatisticsViewModel?.selectDateRange(range)
            },
            onQuickShareCsv = {
                launchShareBackupFile(com.example.util.BackupExportFormat.CSV)
            },
            favoriteChatIds = favoriteChatIds,
            onToggleFavoriteChat = { chatId ->
                val isNowFav = viewModel.toggleFavoriteChat(chatId)
                Toast.makeText(
                    context,
                    if (isNowFav) "Added to Favorite Wake Chats" else "Removed from Favorite Wake Chats",
                    Toast.LENGTH_SHORT
                ).show()
            },
            pinnedChatIds = pinnedChatIds,
            onTogglePinChat = { chatId ->
                val isNowPinned = viewModel.togglePinnedChat(chatId)
                Toast.makeText(
                    context,
                    if (isNowPinned) "Pinned chat to top of list" else "Unpinned chat",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onOpenWakeFavoritesPicker = {
                viewModel.openWakeFavoritesPicker()
            },
            onCreateNewWakeTargetChat = handleCreateNewWakeTargetChat,
            onOpenBackupExportDialog = { showBackupExportDialog = true },
            chatCategoryMap = viewModel.chatCategoryMap.collectAsStateWithLifecycle().value,
            chatTagsMap = viewModel.chatTagsMap.collectAsStateWithLifecycle().value,
            availableCategories = viewModel.availableCategories.collectAsStateWithLifecycle().value,
            onOpenBufferBridgeDialog = { showBufferBridgeDialog = true },
            onOpenSyncCompanionRelay = { showBufferBridgeDialog = true },
            onOpenCloudBackupVault = { navigateInApp(Screen.CloudBackupVault) },
            onClearChatThread = { chatId -> viewModel.clearChatHistory(chatId) },
            externalSelectedCategoryFilter = viewModel.selectedCategoryFilter.collectAsStateWithLifecycle().value,
            onSelectCategoryFilter = { category -> viewModel.setSelectedCategoryFilter(category) }
        )
    }

    if (showWakeFavoritesPickerDialog) {
        val categoryMap = viewModel.chatCategoryMap.collectAsStateWithLifecycle().value
        val matchedCategory = viewModel.matchedWakeCategory.collectAsStateWithLifecycle().value
        val rankedSuggestions = remember(chats, favoriteChatIds) {
            com.example.voice.WakeWordRoutingEngine.rankContextualChatSuggestions(
                chats = chats,
                favoriteChatIds = favoriteChatIds
            )
        }
        val fallbackChat = remember(chats) {
            com.example.voice.WakeWordRoutingEngine.resolveFallbackChat(chats)
        }
        val favoriteChatsList = remember(chats, favoriteChatIds) {
            chats.filter { favoriteChatIds.contains(it.id) }
        }
        com.example.ui.components.WakeFavoritesAndSuggestionsDialog(
            spokenWakePhrase = lastDetectedWakePhrase,
            favoriteChats = favoriteChatsList,
            contextualSuggestions = rankedSuggestions,
            fallbackChat = fallbackChat,
            favoriteChatIds = favoriteChatIds,
            onSelectChat = { selectedChat ->
                viewModel.dismissWakeFavoritesPicker()
                viewModel.selectChat(selectedChat.id)
                onNavigate(Screen.Chat(selectedChat.id))
            },
            onToggleFavorite = { chatId ->
                viewModel.toggleFavoriteChat(chatId)
            },
            onDismiss = { viewModel.dismissWakeFavoritesPicker() },
            chatCategoryMap = categoryMap,
            initialCategoryFilter = matchedCategory
        )
    }

    if (showBackupExportDialog) {
        com.example.ui.components.BackupExportDialog(
            statisticsState = sessionStatisticsState,
            onDismiss = { showBackupExportDialog = false },
            onSaveToDevice = launchSaveBackupToDevice,
            onShareFile = launchShareBackupFile
        )
    }

    if (!incomingExternalWebUrl.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { onClearIncomingExternalWebUrl() },
            modifier = Modifier.testTag("incoming_web_link_dialog"),
            title = { Text("Web Link Opened") },
            text = {
                Text(
                    "Saved to your Web Link History:\n$incomingExternalWebUrl\n\nWould you like to analyze this link in Gemini Chat or open it in your default browser?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val urlToAnalyze = incomingExternalWebUrl
                        onClearIncomingExternalWebUrl()
                        val targetChat = currentChat
                            ?: chats.firstOrNull { it.isDefaultWakeChat }
                            ?: chats.firstOrNull()
                        if (targetChat != null) {
                            viewModel.selectChat(targetChat.id)
                            onNavigate(Screen.Chat(targetChat.id))
                            viewModel.sendMessage(
                                targetChat.id,
                                "Please analyze and summarize this web link: $urlToAnalyze"
                            )
                        } else {
                            Toast.makeText(context, "Create a chat first to analyze links.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.testTag("analyze_incoming_link_button")
                ) {
                    Text("Analyze in Gemini")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val urlToOpen = incomingExternalWebUrl
                        onClearIncomingExternalWebUrl()
                        if (!urlToOpen.isNullOrBlank()) {
                            viewModel.openExternalUrlWithTransientGuard(context, urlToOpen)
                        }
                    },
                    modifier = Modifier.testTag("open_incoming_link_browser_button")
                ) {
                    Text("Open in Browser")
                }
            }
        )
    }

    if (showArchivePromptDialog && pendingArchiveUri != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                showArchivePromptDialog = false
                pendingArchiveUri = null
            },
            title = { Text("Chat Archive Detected") },
            text = { Text("Would you like to import this archive as a new chat session, or attach it to the current chat for analysis?") },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val uri = pendingArchiveUri
                        showArchivePromptDialog = false
                        pendingArchiveUri = null
                        if (uri != null) {
                            val activity = context as? MainActivity
                            if (activity != null) {
                                activity.handlePickedFileUri(
                                    uri = uri,
                                    targetChatId = currentChatId,
                                    action = FileProcessingService.ACTION_PROCESS_FILE_IMPORT
                                )
                            } else {
                                viewModel.importChatArchive(uri, currentChatId)
                            }
                            Toast.makeText(context, "Started background archive import...", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.testTag("import_as_new_chat_button")
                ) {
                    Text("Import as New Chat")
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        val uri = pendingArchiveUri
                        showArchivePromptDialog = false
                        pendingArchiveUri = null
                        if (uri != null) {
                            val activity = context as? MainActivity
                            if (activity != null) {
                                activity.handlePickedFileUri(
                                    uri = uri,
                                    targetChatId = currentChatId,
                                    action = FileProcessingService.ACTION_PROCESS_FILE_CONTEXT
                                )
                            } else {
                                viewModel.stageFile(uri, context)
                            }
                            Toast.makeText(context, "File attached to current chat via background service.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.testTag("attach_to_current_chat_button")
                ) {
                    Text("Attach to Current Chat")
                }
            }
        )
    }

    if (showSelectCategoryDialog) {
        val availableCats by viewModel.availableCategories.collectAsStateWithLifecycle()
        val pendingCat by viewModel.pendingNewChatCategory.collectAsStateWithLifecycle()
        com.example.ui.components.SelectCategoryDialog(
            availableCategories = availableCats,
            initialCategory = pendingCat ?: "Work",
            onCategoryConfirmed = { chosenCategory ->
                viewModel.setPendingNewChatCategory(chosenCategory)
                showSelectCategoryDialog = false
                chatToEdit = null
                showCreateEditDialog = true
            },
            onDismiss = {
                showSelectCategoryDialog = false
            }
        )
    }

    if (showCreateEditDialog) {
        val enrollmentRecording by viewModel.isWakeWordEnrollmentRecording.collectAsStateWithLifecycle()
        val activeRecordingSlot by viewModel.activeRecordingSlot.collectAsStateWithLifecycle()
        val recordingDurationMs by viewModel.snippetRecordingDurationMs.collectAsStateWithLifecycle()
        val currentlyPlayingPath by viewModel.currentlyPlayingSnippetPath.collectAsStateWithLifecycle()
        val playbackProgress by viewModel.snippetPlaybackProgress.collectAsStateWithLifecycle()
        val playbackPositionMs by viewModel.snippetPlaybackPositionMs.collectAsStateWithLifecycle()
        val playbackDurationMs by viewModel.snippetPlaybackDurationMs.collectAsStateWithLifecycle()
        val isSnippetPaused by viewModel.isSnippetPaused.collectAsStateWithLifecycle()
        val pendingCategory by viewModel.pendingNewChatCategory.collectAsStateWithLifecycle()

        CreateEditChatDialog(
            initialChat = chatToEdit,
            existingChats = chats,
            defaultPersistence = defaultPersistenceEnabled,
            isEnrollmentRecording = enrollmentRecording,
            activeRecordingSlot = activeRecordingSlot,
            recordingDurationMs = recordingDurationMs,
            currentlyPlayingPath = currentlyPlayingPath,
            playbackProgress = playbackProgress,
            playbackPositionMs = playbackPositionMs,
            playbackDurationMs = playbackDurationMs,
            isSnippetPaused = isSnippetPaused,
            autoStopLimitSec = autoStopLimitSec,
            onSetAutoStopLimit = { viewModel.setAutoStopLimit(it) },
            onStartSnippetRecording = { chatId, slotIndex ->
                requestAudioPermissionAndExecute(PendingAudioAction.RecordWakeSnippet(chatId, slotIndex))
            },
            onStopSnippetRecording = { chatId, slotIndex ->
                viewModel.stopRecordingWakeSnippet(chatId, slotIndex)
            },
            onPlaySnippetPreview = { filePath ->
                viewModel.playSnippetPreview(filePath)
            },
            onPauseSnippetPreview = {
                viewModel.pauseSnippetPreview()
            },
            onResumeSnippetPreview = {
                viewModel.resumeSnippetPreview()
            },
            onStopSnippetPreview = {
                viewModel.stopSnippetPreview()
            },
            onPlayAllSnippets = {
                chatToEdit?.let { viewModel.playAllWakeSnippets(it.id) }
            },
            onDeleteSampleById = { chatId, sampleId, filePath ->
                viewModel.deleteWakeWordSampleById(chatId, sampleId, filePath)
            },
            onStartEnrollment = { viewModel.startWakeWordEnrollment(it) },
            onStopEnrollment = { viewModel.stopWakeWordEnrollment() },
            onSaveSample = { chatId, index, pcm -> viewModel.saveWakeWordSample(chatId, index, pcm) },
            onDeleteSamples = { chatId -> viewModel.deleteWakeWordSamples(chatId) },
            getSamplesFlow = { chatId -> viewModel.getSamplesForChatFlow(chatId) },
            onDismiss = {
                viewModel.cancelRecordingWakeSnippet()
                viewModel.stopSnippetPreview()
                showCreateEditDialog = false
                chatToEdit = null
            },
            onSave = { savedChat ->
                viewModel.cancelRecordingWakeSnippet()
                viewModel.stopSnippetPreview()
                viewModel.saveChat(savedChat) { _ ->
                    showCreateEditDialog = false
                    chatToEdit = null
                }
            },
            initialCategory = chatToEdit?.let { viewModel.chatCategoryMap.collectAsStateWithLifecycle().value[it.id] }
                ?: pendingCategory
                ?: "Work",
            initialTags = chatToEdit?.let { viewModel.chatTagsMap.collectAsStateWithLifecycle().value[it.id] }.orEmpty(),
            availableCategories = viewModel.availableCategories.collectAsStateWithLifecycle().value,
            onSaveCategoryAndTags = { selectedCat, selectedTags ->
                val targetId = chatToEdit?.id ?: 0L
                if (targetId > 0L) {
                    viewModel.setChatCategoryAndTags(targetId, selectedCat, selectedTags)
                } else {
                    viewModel.setPendingNewChatCategory(selectedCat, selectedTags)
                }
            }
        )
    }

    if (showPermissionRationaleDialog) {
        AudioPermissionRationaleDialog(
            onDismiss = {
                showPermissionRationaleDialog = false
                pendingAudioAction = null
            }
        )
    }

    if (showImportDialog) {
        ImportGeminiChatDialog(
            initialInput = importInitialText,
            defaultPersistence = defaultPersistenceEnabled,
            onDismiss = {
                showImportDialog = false
                importInitialText = ""
            },
            onImportComplete = { session, messages ->
                viewModel.importParsedChat(session, messages) { newId ->
                    showImportDialog = false
                    importInitialText = ""
                    Toast.makeText(
                        context,
                        "Imported \"${session.title}\" with wake word \"${session.wakeWord}\"!",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            onChunkedImportStart = { session, messages, pacing, onProgress, onComplete ->
                viewModel.importParsedChatChunked(
                    session = session,
                    messages = messages,
                    pacing = pacing,
                    onProgress = onProgress,
                    onComplete = { newId ->
                        onComplete(newId)
                        showImportDialog = false
                        importInitialText = ""
                        Toast.makeText(
                            context,
                            "Imported \"${session.title}\" (${messages.size} messages) safely via chunked ingestion!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            },
            onBrowseImportFile = {
                documentPickerLauncher.launch(FileProcessingService.SUPPORTED_MIME_TYPES)
            }
        )
    }

    if (showBufferBridgeDialog && currentChat != null) {
        MemoryBufferBridgeDialog(
            chat = currentChat!!,
            metrics = bufferMetrics,
            relayState = relayServerState,
            onSetBufferSizeLimit = { limit -> viewModel.setBufferSizeLimit(limit) },
            onPurgeDiskCache = {
                viewModel.purgeDiskCache()
                Toast.makeText(context, "All disk caches purged & re-indexed", Toast.LENGTH_SHORT).show()
            },
            onClearIncomingDiskCache = {
                viewModel.clearIncomingDiskCache()
                Toast.makeText(context, "Cleared incoming (AI responses) disk buffer", Toast.LENGTH_SHORT).show()
            },
            onClearOutgoingDiskCache = {
                viewModel.clearOutgoingDiskCache()
                Toast.makeText(context, "Cleared outgoing (User queries) disk buffer", Toast.LENGTH_SHORT).show()
            },
            onOpenInChrome = {
                viewModel.openCurrentChatInChrome()
            },
            onCopyBridgePayload = {
                val payload = viewModel.getChromeBridgePayload()
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Gemini Chrome Bridge", payload)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Sync package copied to clipboard", Toast.LENGTH_SHORT).show()
            },
            onImportFromChrome = { payload ->
                viewModel.importFromChrome(payload) { success ->
                    if (success) {
                        Toast.makeText(context, "Synced successfully from Chrome!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Invalid Chrome sync package", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onOpenCouplerDialog = {
                couplerInitialPayload = ""
                couplerInitialTab = 0
                showCouplerDialog = true
            },
            onDismiss = { showBufferBridgeDialog = false }
        )
    }

    if (showCouplerDialog) {
        val rawMessages by viewModel.rawCurrentMessages.collectAsStateWithLifecycle()
        GeminiCouplerDialog(
            currentChat = currentChat,
            messages = rawMessages,
            initialPayload = couplerInitialPayload,
            initialTab = couplerInitialTab,
            onDismiss = {
                showCouplerDialog = false
                couplerInitialPayload = ""
            },
            onAppendToCurrentChat = { incoming ->
                currentChat?.let { chat ->
                    viewModel.appendPiggybackMessages(chat.id, incoming)
                }
            },
            onCreateCoupledChat = { title, wakeWord, messages ->
                viewModel.createCoupledChat(title, wakeWord, messages) { newId ->
                    viewModel.selectChat(newId)
                }
            },
            onRecordHandoff = {
                viewModel.recordCouplerHandoff()
            }
        )
    }

    if (showPowerConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showPowerConfirmDialog = false },
            title = { Text("Exit Application") },
            text = { Text("Are you sure you want to release microphone handles, stop voice listening, and exit Gemini Wake?") },
            confirmButton = {
                Button(
                    onClick = {
                        showPowerConfirmDialog = false
                        viewModel.cleanShutdown(context as? android.app.Activity)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Exit Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPowerConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showControlSheet) {
        ModalBottomSheet(
            onDismissRequest = { showControlSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            com.example.ui.components.GeminiControlSheet(
                chat = currentChat,
                bufferMetrics = bufferMetrics,
                appTheme = appTheme,
                onSetAppTheme = { viewModel.setAppTheme(it) },
                isHandsFreeActive = isHandsFreeActive,
                handsFreeVoiceState = handsFreeVoiceState,
                onToggleHandsFree = { viewModel.toggleHandsFreeVoice(it) },
                wakeWordSensitivity = wakeWordSensitivity,
                onSetWakeWordSensitivity = { viewModel.setWakeWordSensitivity(it) },
                stopwatchSeconds = openChatTimerSeconds,
                isStopwatchRunning = isOpenChatTimerRunning,
                onToggleStopwatch = { viewModel.toggleOpenChatTimer() },
                onResetStopwatch = { viewModel.resetOpenChatTimer() },
                onOpenCoupler = {
                    showControlSheet = false
                    couplerInitialPayload = ""
                    couplerInitialTab = 0
                    showCouplerDialog = true
                },
                onOpenBufferBridge = {
                    showControlSheet = false
                    showBufferBridgeDialog = true
                },
                onOpenSettingsScreen = {
                    showControlSheet = false
                    navigateInApp(Screen.Settings)
                },
                onOpenDiagnosticLog = {
                    showControlSheet = false
                    navigateInApp(Screen.DiagnosticLog)
                },
                onEditChat = {
                    showControlSheet = false
                    chatToEdit = currentChat
                    showCreateEditDialog = true
                },
                onExportConversation = {
                    showControlSheet = false
                    val title = currentChat?.title ?: "chat"
                    val sanitized = title.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
                    exportDocLauncher.launch("${sanitized}_export.txt")
                },
                onShareLogsAsJson = {
                    showControlSheet = false
                    if (sessionStatisticsViewModel != null) {
                        sessionStatisticsViewModel.shareBackup(
                            context = context,
                            format = com.example.util.BackupExportFormat.JSON,
                            chatId = currentChat?.id,
                            onError = { err ->
                                Toast.makeText(context, "JSON export failed: $err", Toast.LENGTH_SHORT).show()
                            }
                        )
                    } else {
                        viewModel.shareChatInteractionLogsAsJson(
                            context = context,
                            chatId = currentChat?.id,
                            onError = { err ->
                                Toast.makeText(context, "JSON export failed: $err", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                },
                onShareLogsAsCsv = {
                    showControlSheet = false
                    sessionStatisticsViewModel?.shareBackup(
                        context = context,
                        format = com.example.util.BackupExportFormat.CSV,
                        chatId = currentChat?.id,
                        onError = { err ->
                            Toast.makeText(context, "CSV export failed: $err", Toast.LENGTH_SHORT).show()
                        }
                    )
                },
                notifyActiveChatReplies = notifyActiveChatReplies,
                permissionsResetCount = permissionsResetCount,
                onToggleNotifyActiveChatReplies = { enabled ->
                    viewModel.setNotifyActiveChatReplies(enabled)
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                },
                onResetPermissions = { handleResetPermissions() },
                onRequestRequiredPermissions = { launchRequiredPermissionsRequest() },
                primaryApiKey = primaryApiKey,
                backupApiKey = backupApiKey,
                activeKeyType = activeKeyType,
                onSavePrimaryApiKey = { key ->
                    viewModel.setPrimaryApiKey(key)
                    Toast.makeText(context, "Saved Primary Gemini API Key", Toast.LENGTH_SHORT).show()
                },
                onSaveBackupApiKey = { key ->
                    viewModel.setBackupApiKey(key)
                },
                onToggleActiveKey = { viewModel.toggleActiveKey() },
                onDismiss = { showControlSheet = false }
            )
        }
    }
}

@Composable
private fun MainWakeWordStudioSection(
    viewModel: MainViewModel,
    wakeWordViewModel: WakeWordViewModel,
    chats: List<ChatSession>,
    onNavigate: (Screen) -> Unit,
    onToggleListening: () -> Unit,
    onRequestRecordSnippet: (Long, Int) -> Unit
) {
    val allWakeWordSamples by viewModel.allWakeWordSamples.collectAsStateWithLifecycle()
    val isListening by viewModel.wakeWordManager.isListening.collectAsStateWithLifecycle()
    val isSleepMode by viewModel.isSleepMode.collectAsStateWithLifecycle()
    val sleepModeReason by viewModel.sleepModeReason.collectAsStateWithLifecycle()
    val backgroundCueOnlyMode by viewModel.backgroundCueOnlyMode.collectAsStateWithLifecycle()
    val wakeReadyVisualCue by viewModel.wakeReadyVisualCue.collectAsStateWithLifecycle()
    val rawAudioRmsDb by viewModel.wakeWordManager.audioRmsDb.collectAsStateWithLifecycle()
    val speechRecognizerRmsDb by viewModel.speechRecognizerRmsDb.collectAsStateWithLifecycle()
    val audioRmsDb = maxOf(rawAudioRmsDb, speechRecognizerRmsDb)
    val statusMessage by viewModel.wakeWordManager.statusMessage.collectAsStateWithLifecycle()
    val wakeWordSensitivity by viewModel.wakeWordSensitivity.collectAsStateWithLifecycle()
    val activeRecordingSlot by viewModel.activeRecordingSlot.collectAsStateWithLifecycle()
    val recordingDurationMs by viewModel.snippetRecordingDurationMs.collectAsStateWithLifecycle()
    val currentlyPlayingPath by viewModel.currentlyPlayingSnippetPath.collectAsStateWithLifecycle()
    val isSnippetPaused by viewModel.isSnippetPaused.collectAsStateWithLifecycle()
    val configuredWakeWords by wakeWordViewModel.chatWakeWords.collectAsStateWithLifecycle()

    WakeWordRecordingScreen(
        chats = chats,
        configuredWakeWords = configuredWakeWords,
        allSamples = allWakeWordSamples,
        isListening = isListening,
        isSleepMode = isSleepMode,
        sleepModeReason = sleepModeReason,
        backgroundCueOnlyMode = backgroundCueOnlyMode,
        wakeReadyVisualCue = wakeReadyVisualCue,
        audioRmsDb = audioRmsDb,
        statusMessage = statusMessage,
        wakeWordSensitivity = wakeWordSensitivity,
        activeRecordingSlot = activeRecordingSlot,
        recordingDurationMs = recordingDurationMs,
        currentlyPlayingPath = currentlyPlayingPath,
        isSnippetPaused = isSnippetPaused,
        getSamplesFlow = { chatId -> viewModel.getSamplesForChatFlow(chatId) },
        onBack = { onNavigate(Screen.Home) },
        onToggleListening = onToggleListening,
        onEnterSleepMode = { viewModel.enterSleepMode("Manual sleep mode enabled") },
        onExitSleepMode = { viewModel.exitSleepModeAndResumeListening() },
        onToggleBackgroundCueOnlyMode = { viewModel.setBackgroundCueOnlyMode(it) },
        onDismissWakeReadyCue = { viewModel.dismissWakeReadyVisualCue() },
        onSetWakeWordSensitivity = { viewModel.setWakeWordSensitivity(it) },
        onSetWakeWordSensitivityLevel = { viewModel.setWakeWordSensitivityLevel(it) },
        onStartSnippetRecording = onRequestRecordSnippet,
        onStopSnippetRecording = { chatId, slotIndex ->
            viewModel.stopRecordingWakeSnippet(chatId, slotIndex)
        },
        onPlaySnippetPreview = { filePath -> viewModel.playSnippetPreview(filePath) },
        onPauseSnippetPreview = { viewModel.pauseSnippetPreview() },
        onResumeSnippetPreview = { viewModel.resumeSnippetPreview() },
        onDeleteSampleById = { chatId, sampleId, filePath ->
            viewModel.deleteWakeWordSampleById(chatId, sampleId, filePath)
        },
        onDeleteAllSamplesForChat = { chatId -> viewModel.deleteWakeWordSamples(chatId) },
        onSaveCustomWakeWordWithSamples = { phrase, chatId, sensitivity ->
            viewModel.saveCustomWakeWordWithSamples(phrase, chatId, sensitivity)
        },
        onSaveCustomWakeWordWithNewChat = { phrase, newChatTitle, sensitivity ->
            viewModel.addCustomWakeWordWithNewChat(
                phrase = phrase,
                newChatTitle = newChatTitle,
                sensitivity = sensitivity
            )
        },
        onSetIndividualWakeWordSensitivity = { id, sensitivity ->
            viewModel.setIndividualWakeWordSensitivity(id, sensitivity)
        },
        onReassignWakeWordToChat = { wakeWordId, targetChatId ->
            viewModel.reassignWakeWordToChat(wakeWordId, targetChatId)
        },
        onToggleWakeWordEnabled = { id, enabled -> wakeWordViewModel.toggleWakeWordEnabled(id, enabled) },
        onDeleteWakeWord = { id -> wakeWordViewModel.removeWakeWordById(id) },
        onTriggerWakeWordAndOpenChat = { wakeWord ->
            val targetId = wakeWord.effectiveTargetChatId
                ?: chats.firstOrNull { it.wakeWord.equals(wakeWord.phrase, ignoreCase = true) }?.id
                ?: chats.firstOrNull()?.id
                ?: 0L
            if (targetId > 0L) {
                viewModel.selectChat(targetId)
                viewModel.wakeWordManager.onKeywordHit(
                    keyword = wakeWord.phrase,
                    initialQuery = "",
                    targetChatIdOverride = targetId
                )
                onNavigate(Screen.Chat(targetId))
            }
        },
        onOpenChat = { chatId ->
            viewModel.selectChat(chatId)
            onNavigate(Screen.Chat(chatId))
        }
    )
}

@Composable
private fun MainSettingsSection(
    viewModel: MainViewModel,
    wakeWordViewModel: WakeWordViewModel,
    chats: List<ChatSession>,
    sessionStatisticsState: com.example.ui.SessionStatisticsUiState?,
    onNavigate: (Screen) -> Unit,
    onToggleListening: () -> Unit,
    onCreateNewWakeTargetChat: (String, String) -> Unit,
    onSaveBackupToDevice: (com.example.util.BackupExportFormat) -> Unit,
    onShareBackupFile: (com.example.util.BackupExportFormat) -> Unit,
    onRequestRecordSnippet: (Long, Int) -> Unit = { _, _ -> }
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val configuredWakeWords by wakeWordViewModel.chatWakeWords.collectAsStateWithLifecycle()
    val wakeWordSensitivity by viewModel.wakeWordSensitivity.collectAsStateWithLifecycle()
    val isListening by viewModel.wakeWordManager.isListening.collectAsStateWithLifecycle()
    val isHandsFreeActive by viewModel.isHandsFreeActive.collectAsStateWithLifecycle()
    val isSleepMode by viewModel.isSleepMode.collectAsStateWithLifecycle()
    val sleepModeReason by viewModel.sleepModeReason.collectAsStateWithLifecycle()
    val rawAudioRmsDb by viewModel.wakeWordManager.audioRmsDb.collectAsStateWithLifecycle()
    val speechRecognizerRmsDb by viewModel.speechRecognizerRmsDb.collectAsStateWithLifecycle()
    val audioRmsDb = maxOf(rawAudioRmsDb, speechRecognizerRmsDb)
    val statusMessage by viewModel.wakeWordManager.statusMessage.collectAsStateWithLifecycle()
    val lastHeardText by viewModel.wakeWordManager.lastHeardText.collectAsStateWithLifecycle()
    val dictateTrigger by viewModel.dictateTrigger.collectAsStateWithLifecycle()
    val sendTrigger by viewModel.sendTrigger.collectAsStateWithLifecycle()
    val heartbeatTrigger by viewModel.heartbeatTrigger.collectAsStateWithLifecycle()
    val relayServerState by viewModel.relayServerState.collectAsStateWithLifecycle()
    val lastHygieneResetMessage by viewModel.lastHygieneResetMessage.collectAsStateWithLifecycle()
    val favoriteChatIds by viewModel.favoriteChatIds.collectAsStateWithLifecycle()
    val contextualRoutingEnabled by viewModel.contextualRoutingEnabled.collectAsStateWithLifecycle()
    val promptFavoritesOnWake by viewModel.promptFavoritesOnWake.collectAsStateWithLifecycle()
    val allWakeWordSamples by viewModel.allWakeWordSamples.collectAsStateWithLifecycle()
    val activeRecordingSlot by viewModel.activeRecordingSlot.collectAsStateWithLifecycle()
    val recordingDurationMs by viewModel.snippetRecordingDurationMs.collectAsStateWithLifecycle()
    val currentlyPlayingPath by viewModel.currentlyPlayingSnippetPath.collectAsStateWithLifecycle()
    val voiceEngineMode by viewModel.voiceEngineMode.collectAsStateWithLifecycle()
    val masterVolume by viewModel.masterVolume.collectAsStateWithLifecycle()
    val autoStopLimitSec by viewModel.autoStopLimitSec.collectAsStateWithLifecycle()
    val defaultPersistenceEnabled by viewModel.defaultPersistenceEnabled.collectAsStateWithLifecycle()
    val preserveCacheAcrossSessions by viewModel.preserveCacheAcrossSessions.collectAsStateWithLifecycle()
    val bufferMetrics by viewModel.bufferMetrics.collectAsStateWithLifecycle()

    SettingsScreen(
        chats = chats,
        configuredWakeWords = configuredWakeWords,
        wakeWordSensitivity = wakeWordSensitivity,
        isListening = isListening,
        isHandsFreeActive = isHandsFreeActive,
        isSleepMode = isSleepMode,
        sleepModeReason = sleepModeReason,
        audioRmsDb = audioRmsDb,
        statusMessage = statusMessage,
        lastHeardText = lastHeardText,
        dictateTrigger = dictateTrigger,
        sendTrigger = sendTrigger,
        heartbeatTrigger = heartbeatTrigger,
        onBack = { onNavigate(Screen.Home) },
        onSetWakeWordSensitivity = { viewModel.setWakeWordSensitivity(it) },
        onSetWakeWordSensitivityLevel = { viewModel.setWakeWordSensitivityLevel(it) },
        onToggleListening = onToggleListening,
        onToggleHandsFree = { viewModel.toggleHandsFreeVoice(it) },
        onExitSleepMode = { viewModel.exitSleepModeAndResumeListening() },
        onAddCustomWakeWord = { chatId, phrase, sensitivity ->
            viewModel.addCustomWakeWord(chatId = chatId, phrase = phrase, sensitivity = sensitivity)
        },
        onAddCustomWakeWordWithNewChat = { phrase, newChatTitle, sensitivity ->
            viewModel.addCustomWakeWordWithNewChat(
                phrase = phrase,
                newChatTitle = newChatTitle,
                sensitivity = sensitivity
            )
        },
        onUpdateWakeWordPhrase = { id, newPhrase ->
            viewModel.updateConfiguredWakeWordPhrase(id, newPhrase)
        },
        onToggleWakeWordEnabled = { id, enabled ->
            viewModel.toggleConfiguredWakeWord(id, enabled)
        },
        onDeleteWakeWord = { id ->
            viewModel.deleteConfiguredWakeWord(id)
        },
        onSetIndividualWakeWordSensitivity = { id, sensitivity ->
            viewModel.setIndividualWakeWordSensitivity(id, sensitivity)
        },
        onReassignWakeWordToChat = { wakeWordId, targetChatId ->
            viewModel.reassignWakeWordToChat(wakeWordId, targetChatId)
        },
        onUpdateChatSessionWakeWord = { chatSession, newWakeWord ->
            viewModel.saveChat(chatSession.copy(wakeWord = newWakeWord.trim()))
        },
        onUpdateDictateTrigger = { viewModel.setDictateTrigger(it) },
        onUpdateSendTrigger = { viewModel.setSendTrigger(it) },
        onUpdateHeartbeatTrigger = { viewModel.setHeartbeatTrigger(it) },
        onResetTriggersToDefaults = { viewModel.resetTriggersToDefaults() },
        onOpenWakeWordStudio = { onNavigate(Screen.WakeWordStudio) },
        onOpenDiagnosticLog = { onNavigate(Screen.DiagnosticLog) },
        onOpenChat = { chatId ->
            viewModel.selectChat(chatId)
            onNavigate(Screen.Chat(chatId))
        },
        onSetDefaultWakeChat = { chatId -> viewModel.setDefaultWakeChat(chatId) },
        onCreateNewWakeTargetChat = onCreateNewWakeTargetChat,
        sessionStatisticsState = sessionStatisticsState,
        onSaveBackupToDevice = onSaveBackupToDevice,
        onShareBackupFile = onShareBackupFile,
        relayServerState = relayServerState,
        onToggleRelayServer = { enabled -> viewModel.toggleRelayServer(enabled) },
        onRotateRelaySessionToken = {
            val newToken = viewModel.rotateRelaySessionToken()
            Toast.makeText(context, "Session token rotated: ${newToken.take(8)}…", Toast.LENGTH_SHORT).show()
        },
        onCopyRelaySessionToken = { token ->
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Gemini Wake Relay Session Token", token)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Relay session token copied to clipboard!", Toast.LENGTH_SHORT).show()
        },
        onForceResetAndClearHygiene = {
            val report = viewModel.forceResetAndClearSystemHygiene()
            Toast.makeText(context, report.summaryMessage, Toast.LENGTH_LONG).show()
        },
        lastHygieneResetMessage = lastHygieneResetMessage,
        favoriteChatIds = favoriteChatIds,
        onToggleFavoriteChat = { chatId -> viewModel.toggleFavoriteChat(chatId) },
        contextualRoutingEnabled = contextualRoutingEnabled,
        onToggleContextualRouting = { enabled -> viewModel.setContextualRoutingEnabled(enabled) },
        promptFavoritesOnWake = promptFavoritesOnWake,
        onTogglePromptFavoritesOnWake = { enabled -> viewModel.setPromptFavoritesOnWake(enabled) },
        allSamples = allWakeWordSamples,
        activeRecordingSlot = activeRecordingSlot,
        recordingDurationMs = recordingDurationMs,
        currentlyPlayingPath = currentlyPlayingPath,
        onStartSnippetRecording = onRequestRecordSnippet,
        onStopSnippetRecording = { chatId, slotIndex ->
            viewModel.stopRecordingWakeSnippet(chatId, slotIndex)
        },
        onPlaySnippetPreview = { filePath -> viewModel.playSnippetPreview(filePath) },
        onStopSnippetPreview = { viewModel.stopSnippetPreview() },
        onDeleteSampleById = { chatId, sampleId, filePath ->
            viewModel.deleteWakeWordSampleById(chatId, sampleId, filePath)
        },
        voiceEngineMode = voiceEngineMode,
        onSetVoiceEngineMode = { viewModel.setVoiceEngineMode(it) },
        masterVolume = masterVolume,
        onSetMasterVolume = { viewModel.setMasterVolume(it) },
        autoStopLimitSec = autoStopLimitSec,
        onSetAutoStopLimit = { viewModel.setAutoStopLimit(it) },
        defaultPersistenceEnabled = defaultPersistenceEnabled,
        onToggleDefaultPersistence = { viewModel.setDefaultPersistenceEnabled(it) },
        preserveCacheAcrossSessions = preserveCacheAcrossSessions,
        onTogglePreserveCacheAcrossSessions = { viewModel.setPreserveCacheAcrossSessions(it) },
        bufferMetrics = bufferMetrics,
        onClearActiveInMemoryBuffer = {
            viewModel.clearActiveInMemoryBuffer()
            Toast.makeText(
                context,
                "Cleared active in-memory RAM window (hot messages reset to 0).",
                Toast.LENGTH_SHORT
            ).show()
        },
        onPurgeStagedDiskCache = {
            val report = viewModel.forceResetAndClear()
            Toast.makeText(
                context,
                report.summaryMessage,
                Toast.LENGTH_SHORT
            ).show()
        },
        onCreateGeneralLogFile = {
            viewModel.createAndCopyGeneralLogFileToClipboard(context)
        }
    )
}

