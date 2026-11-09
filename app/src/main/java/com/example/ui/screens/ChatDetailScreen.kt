package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import android.graphics.BitmapFactory
import com.example.voice.HandsFreeVoiceState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.input.key.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.importer.ImportProgressState
import com.example.ui.components.ChatImportProgressBanner
import com.example.ai.ActiveKeyType
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Folder
import com.example.ui.components.SubtleWakeWordWaveformPill
import com.example.ui.components.VoiceWaveformVisualizer
import com.example.ui.components.WakeWordListeningStatusBanner
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.coupler.StagedImageInfo
import android.net.Uri
import com.example.ui.components.StagedImageTray
import com.example.ui.components.ScreenshotHeadsUpBanner
import com.example.ui.TriggerBanner
import com.example.ui.components.getIconForName
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import com.example.ui.components.ChatStopwatchDial
import com.example.ui.components.ScreenClockCard
import com.example.ui.components.UsageLimitVsCacheComparisonCard
import com.example.ui.components.formatTrackedTime
import com.example.data.model.PromptTemplate
import com.example.ui.components.BottomBarNetworkStatusIcon
import com.example.ui.components.CreatePromptDialog
import com.example.ui.components.FloatingMovableChatInput
import com.example.ui.components.WebSearchAndHistoryBar
import com.example.util.NetworkConnectionState
import com.example.util.VoiceCommandHistoryEntry
import com.example.util.WebLinkHistoryEntry
import com.example.util.WebLinkHistoryManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import com.example.ui.components.ChatCustomizableBackgroundLayer
import com.example.ui.components.ChatThemeCustomizationDialog
import com.example.ui.components.MarkdownMessageContent
import com.example.ui.components.PREDEFINED_CHAT_COLOR_PALETTES
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatDetailScreen(
    chat: ChatSession,
    messages: List<ChatMessage>,
    streamingText: String?,
    isGenerating: Boolean,
    errorMessage: String?,
    triggerBanner: TriggerBanner?,
    isAutoSpeakEnabled: Boolean,
    isSpeaking: Boolean,
    bufferMetrics: com.example.cache.BufferMetrics,
    hasMoreOlderMessages: Boolean,
    isLoadingOlder: Boolean,
    openChatTimerSeconds: Long = 0L,
    isOpenChatTimerRunning: Boolean = false,
    showUsageLimitsComparison: Boolean = true,
    customPrompts: List<PromptTemplate> = emptyList(),
    onBack: () -> Unit,
    onSendMessage: (String) -> Unit,
    onToggleAutoSpeak: () -> Unit,
    onSpeakText: (String) -> Unit,
    onStopSpeaking: () -> Unit,
    onClearHistory: () -> Unit,
    onEditChat: () -> Unit,
    onLoadOlderMessages: () -> Unit,
    onOpenBufferBridgeDialog: () -> Unit,
    onOpenCouplerDialog: () -> Unit = {},
    onDismissTriggerBanner: () -> Unit,
    onToggleChatPersistence: () -> Unit = {},
    onToggleOpenChatTimer: () -> Unit = {},
    onResetOpenChatTimer: () -> Unit = {},
    onToggleUsageLimitsComparison: (Boolean) -> Unit = {},
    onAddCustomPrompt: (String, String) -> Unit = { _, _ -> },
    onDeleteCustomPrompt: (String) -> Unit = {},
    isHandsFreeActive: Boolean = false,
    handsFreeVoiceState: HandsFreeVoiceState = HandsFreeVoiceState.IDLE,
    dictateTrigger: String = "Dictate",
    sendTrigger: String = "Send",
    heartbeatTrigger: String = "Heartbeat",
    draftSpokenText: String = "",
    onToggleHandsFree: () -> Unit = {},
    stagedImageUri: Uri? = null,
    stagedImageInfo: StagedImageInfo? = null,
    screenshotBanner: StagedImageInfo? = null,
    onDismissScreenshotBanner: () -> Unit = {},
    onRemoveStagedImage: () -> Unit = {},
    onDispatchScreenshotToCoupler: (String) -> Unit = {},
    importProgressState: ImportProgressState = ImportProgressState(),
    onCancelImport: () -> Unit = {},
    activeKeyType: ActiveKeyType = ActiveKeyType.PRIMARY,
    onToggleActiveKey: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onBrowseFiles: () -> Unit = {},
    stagedAttachment: com.example.ui.AttachmentPayload? = null,
    onClearStagedAttachment: () -> Unit = {},
    onUpdateChatTheme: (com.example.ui.theme.AppTheme) -> Unit = {},
    isVoiceRecording: Boolean = false,
    isInChatDictationActive: Boolean = false,
    audioRmsDb: Float = 0f,
    viewModel: com.example.ui.MainViewModel? = null,
    onStartVoiceRecording: () -> Unit = {},
    onToggleInChatDictation: () -> Unit = {
        if (viewModel != null) {
            viewModel.toggleInternalDictation()
        } else {
            onStartVoiceRecording()
        }
    },
    onSendVoiceRecording: (String) -> Unit = {},
    onCancelVoiceRecording: () -> Unit = {},
    onOpenControlSheet: () -> Unit = {},
    onOpenImportDialog: () -> Unit = {},
    onPowerExit: () -> Unit = {},
    networkState: NetworkConnectionState = NetworkConnectionState.ONLINE_WIFI,
    onRefreshNetwork: () -> Unit = {},
    webLinkHistory: List<WebLinkHistoryEntry> = emptyList(),
    onOpenChatLink: (String, String?) -> Unit = { _, _ -> },
    onSubmitWebSearchOrUrl: (String) -> Unit = {},
    onOpenWebHistoryEntry: (WebLinkHistoryEntry) -> Unit = {},
    onDeleteWebHistoryEntry: (String) -> Unit = {},
    onClearAllWebHistory: () -> Unit = {},
    voiceCommandHistory: List<VoiceCommandHistoryEntry> = emptyList(),
    onDeleteVoiceCommandEntry: (String) -> Unit = {},
    onClearAllVoiceCommands: () -> Unit = {},
    onClearCorruptedHistory: () -> Unit = {},
    onTextInputFocusChanged: (Boolean) -> Unit = {},
    wakeRetriggerEventId: Long = 0L,
    isSleepMode: Boolean = false,
    sleepModeReason: String = "",
    onEnterSleepMode: () -> Unit = {},
    onExitSleepMode: () -> Unit = {},
    onShareLogsAsJson: () -> Unit = {},
    onShareLogsAsCsv: () -> Unit = {},
    isWakeWordListening: Boolean = false,
    lastHeardText: String = "",
    wakeConversationSummary: com.example.voice.WakeConversationSummary? = null,
    isFavoriteChat: Boolean = false,
    onToggleFavoriteChat: () -> Unit = {},
    isPinnedChat: Boolean = false,
    onTogglePinChat: () -> Unit = {},
    onGenerateWakeAiSummary: () -> Unit = {},
    onOpenWakeFavoritesPicker: () -> Unit = {},
    onDismissWakeSummary: () -> Unit = {},
    chatCategory: String? = null,
    chatTags: Set<String> = emptySet(),
    allChats: List<ChatSession> = emptyList(),
    chatCategoryMap: Map<Long, String> = emptyMap(),
    messageCategoryMap: Map<Long, String> = emptyMap(),
    onSelectChatFromCategoryBar: (Long) -> Unit = {},
    onReassignChatCategory: (String) -> Unit = {},
    attachedImageUri: Uri? = stagedAttachment?.uri?.takeIf { stagedAttachment.mimeType.startsWith("image/") },
    onStageAttachedImageUri: (Uri?) -> Unit = {},
    onClearAttachedImage: () -> Unit = onClearStagedAttachment,
    onCheckAndStageClipboardImage: () -> Boolean = { false },
    onUpdateChatAppearance: (paletteId: String, backgroundStyle: String, primaryHex: String) -> Unit = { _, _, _ -> },
    pendingWakeQuery: String? = null,
    onConsumePendingWakeQuery: () -> String? = { pendingWakeQuery }
) {
    BackHandler {
        onBack()
    }

    val context = LocalContext.current
    var inputText by remember(chat.id) { mutableStateOf(TextFieldValue("")) }
    var isSending by remember { mutableStateOf(false) }
    var menuExpanded by remember(chat.id) { mutableStateOf(false) }
    var showPromptsBar by remember(chat.id) { mutableStateOf(false) }
    var showCreatePromptDialog by remember(chat.id) { mutableStateOf(false) }
    var showThemeCustomizationDialog by remember(chat.id) { mutableStateOf(false) }
    var showWebHistoryBar by remember(chat.id) { mutableStateOf(false) }
    var isSummaryCardVisible by remember(chat.id) { mutableStateOf(false) }
    var isSummaryCardExpanded by remember(chat.id) { mutableStateOf(false) }
    var isChatCategoryBarCollapsed by remember(chat.id) { mutableStateOf(true) }
    var selectedChatCategoryFilter by remember(chat.id, chatCategory) { mutableStateOf<String?>(chatCategory) }
    var isInputFloating by remember(chat.id) { mutableStateOf(false) }
    var isFloatingCollapsedToDot by remember(chat.id) { mutableStateOf(false) }
    var floatingOffsetX by remember(chat.id) { mutableStateOf(24f) }
    var floatingOffsetY by remember(chat.id) { mutableStateOf(160f) }
    val listState = rememberLazyListState()

    // Photo Picker for Staging Image Attachment
    val attachImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            onStageAttachedImageUri(uri)
        }
    }

    // Photo Picker for Custom Chat Background Image
    val customBackgroundPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}
            val matchedPalette = PREDEFINED_CHAT_COLOR_PALETTES.firstOrNull { it.id == chat.colorPaletteId }
            val hex = matchedPalette?.primaryHex ?: chat.themeColorHex
            onUpdateChatAppearance(chat.colorPaletteId, "custom:$uri", hex)
        }
    }

    // Helper to inspect system ClipboardManager.primaryClip for image/png or image/jpeg URI
    val tryPasteImageFromClipboard: (Boolean) -> Boolean = { showFeedbackToast ->
        val handledByViewModel = onCheckAndStageClipboardImage()
        if (handledByViewModel) {
            if (showFeedbackToast) {
                Toast.makeText(context, "Image pasted from clipboard", Toast.LENGTH_SHORT).show()
            }
            true
        } else {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipboard?.primaryClip
            val desc = clip?.description
            val hasImageMime = desc != null && (
                desc.hasMimeType("image/png") ||
                    desc.hasMimeType("image/jpeg") ||
                    desc.hasMimeType("image/*")
                )
            var foundUri: Uri? = null
            if (clip != null && clip.itemCount > 0) {
                for (i in 0 until clip.itemCount) {
                    val candidateUri = clip.getItemAt(i)?.uri
                    if (candidateUri != null) {
                        val resolvedMime = context.contentResolver.getType(candidateUri)?.lowercase().orEmpty()
                        if (hasImageMime || resolvedMime.startsWith("image/")) {
                            foundUri = candidateUri
                            break
                        }
                    }
                }
            }
            if (foundUri != null) {
                onStageAttachedImageUri(foundUri)
                if (showFeedbackToast) {
                    Toast.makeText(context, "Image pasted from clipboard", Toast.LENGTH_SHORT).show()
                }
                true
            } else {
                if (showFeedbackToast) {
                    Toast.makeText(context, "No image (PNG/JPEG) found on clipboard", Toast.LENGTH_SHORT).show()
                }
                false
            }
        }
    }

    val matchedPalette = remember(chat.colorPaletteId) {
        PREDEFINED_CHAT_COLOR_PALETTES.firstOrNull { it.id.equals(chat.colorPaletteId, ignoreCase = true) }
    }
    val accentColor = remember(chat.themeColorHex, matchedPalette) {
        matchedPalette?.primaryColor ?: try {
            Color(android.graphics.Color.parseColor(chat.themeColorHex.trim()))
        } catch (_: Throwable) {
            Color(0xFF38BDF8)
        }
    }

    // STRICT SESSION ISOLATION & PLACEHOLDER FILTERING:
    // Filter messages strictly to this chat.id and exclude empty/legacy audio placeholders
    val isolatedMessages = remember(chat.id, messages) {
        messages.filter { it.chatId == chat.id && it.hasPersistableContent }
    }

    // Re-key LaunchedEffect(chat.id) so switching between different chats immediately purges active state & scrolls to fresh thread.
    // Immediately clear _pendingWakeQuery when entering ChatScreen so it cannot auto-submit or generate unsolicited summary overlays.
    LaunchedEffect(chat.id) {
        viewModel?.onEnterChatScreen(chat.id)
        onConsumePendingWakeQuery()
        isSummaryCardVisible = false
        isSummaryCardExpanded = false
        inputText = TextFieldValue("")
        isSending = false
        menuExpanded = false
        showPromptsBar = false
        if (isolatedMessages.isNotEmpty()) {
            val headerOffset = if (hasMoreOlderMessages) 1 else 0
            listState.scrollToItem((headerOffset + isolatedMessages.size - 1).coerceAtLeast(0))
        } else {
            listState.scrollToItem(0)
        }
    }

    // Voice recognition is decoupled from the bottom text input box:
    // When a wake-word or voice trigger executes, keep inputText completely blank and do NOT request focus or trigger the soft keyboard (IME).
    LaunchedEffect(wakeRetriggerEventId) {
        if (wakeRetriggerEventId > 0L) {
            inputText = TextFieldValue("")
            isSending = false
        }
    }

    // When manual in-chat voice dictation is active, mirror the live spoken transcript into the composer
    // text field in real time so the user can see their words while speaking, and clear it once auto-sent.
    val isDictationScopedToComposer = isInChatDictationActive
    var wasInChatDictationActive by remember { mutableStateOf(false) }
    LaunchedEffect(isInChatDictationActive, lastHeardText, draftSpokenText) {
        if (isInChatDictationActive) {
            wasInChatDictationActive = true
            val liveTranscript = lastHeardText.trim().ifBlank { draftSpokenText.trim() }
            if (liveTranscript.isNotBlank() && inputText.text != liveTranscript) {
                inputText = TextFieldValue(
                    text = liveTranscript,
                    selection = androidx.compose.ui.text.TextRange(liveTranscript.length)
                )
            }
        } else if (wasInChatDictationActive) {
            wasInChatDictationActive = false
            inputText = TextFieldValue("")
        }
    }

    val density = androidx.compose.ui.platform.LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val latestMessage = isolatedMessages.lastOrNull()

    // Automatically scroll to the bottom whenever a new turn is added at the end of the conversation
    // (especially after a wake-word trigger), during streaming responses, or when the keyboard opens,
    // without jumping to the bottom when older messages are prepended at the top via keyset pagination.
    LaunchedEffect(
        latestMessage?.id,
        latestMessage?.timestamp,
        latestMessage?.triggeredByWakeWord,
        wakeRetriggerEventId,
        streamingText,
        imeBottomPx > 0
    ) {
        if (isolatedMessages.isNotEmpty() || streamingText != null) {
            val headerCount = if (hasMoreOlderMessages) 1 else 0
            val streamingCount = if (streamingText != null) 1 else 0
            val totalItems = headerCount + isolatedMessages.size + streamingCount
            if (totalItems > 0) {
                val targetIndex = maxOf(totalItems - 1, listState.layoutInfo.totalItemsCount - 1)
                listState.animateScrollToItem(targetIndex.coerceAtMost(totalItems - 1))
            }
        }
    }

    val adventureScope = rememberCoroutineScope()
    var fallbackAdventureState by remember(chat.id) {
        mutableStateOf(com.example.game.AdventureGameState(chatId = chat.id))
    }
    val vmAdventureState = viewModel?.adventureState?.collectAsState()?.value
    val activeAdventureState = vmAdventureState ?: fallbackAdventureState

    val adventureDrawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // Sync ViewModel drawer open/close state (including voice commands "open inventory", "check quests", "close drawer") with ModalNavigationDrawer
    LaunchedEffect(activeAdventureState.isDrawerOpen) {
        if (activeAdventureState.isDrawerOpen && !adventureDrawerState.isOpen) {
            adventureDrawerState.open()
        } else if (!activeAdventureState.isDrawerOpen && adventureDrawerState.isOpen) {
            adventureDrawerState.close()
        }
    }

    // Sync manual swipe open/close gestures on ModalNavigationDrawer back to ViewModel
    LaunchedEffect(adventureDrawerState.currentValue) {
        val isOpenNow = adventureDrawerState.currentValue == DrawerValue.Open
        if (viewModel != null) {
            if (isOpenNow && !activeAdventureState.isDrawerOpen) {
                viewModel.openAdventureDrawer(activeAdventureState.activeDrawerTab)
            } else if (!isOpenNow && activeAdventureState.isDrawerOpen) {
                viewModel.closeAdventureDrawer()
            }
        } else {
            fallbackAdventureState = fallbackAdventureState.copy(isDrawerOpen = isOpenNow)
        }
    }

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = adventureDrawerState,
            gesturesEnabled = true,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    com.example.ui.components.GameDrawerSheet(
                        adventureState = activeAdventureState,
                        accentColor = accentColor,
                        onSelectTab = { tab ->
                            if (viewModel != null) {
                                viewModel.selectAdventureDrawerTab(tab)
                            } else {
                                fallbackAdventureState = fallbackAdventureState.copy(activeDrawerTab = tab)
                            }
                        },
                        onToggleAdventureMode = { enabled ->
                            if (viewModel != null) {
                                viewModel.setAdventureModeEnabled(enabled)
                            } else {
                                fallbackAdventureState = fallbackAdventureState.copy(isAdventureModeEnabled = enabled)
                            }
                        },
                        onManualSave = {
                            if (viewModel != null) {
                                viewModel.saveAdventureGameManually()
                            } else {
                                fallbackAdventureState = fallbackAdventureState.copy(
                                    unsavedPlaySeconds = 0L,
                                    lastSavedAt = System.currentTimeMillis(),
                                    isSaveSentinelAlertVisible = false,
                                    statusBannerMessage = "Campaign saved locally"
                                )
                            }
                        },
                        onSelectDifficulty = { diff ->
                            if (viewModel != null) {
                                viewModel.setAdventureDifficulty(diff)
                            } else {
                                fallbackAdventureState = fallbackAdventureState.copy(
                                    settings = fallbackAdventureState.settings.copy(difficulty = diff)
                                )
                            }
                        },
                        onToggleAutoSaveOnQuest = { enabled ->
                            if (viewModel != null) {
                                viewModel.setAdventureAutoSaveOnQuest(enabled)
                            } else {
                                fallbackAdventureState = fallbackAdventureState.copy(
                                    settings = fallbackAdventureState.settings.copy(autoSaveOnQuest = enabled)
                                )
                            }
                        },
                        onToggleNotifications = { enabled ->
                            if (viewModel != null) {
                                viewModel.setAdventureNotificationsEnabled(enabled)
                            } else {
                                fallbackAdventureState = fallbackAdventureState.copy(
                                    settings = fallbackAdventureState.settings.copy(notificationsEnabled = enabled)
                                )
                            }
                        },
                        onUpdateLoreContext = { lore ->
                            if (viewModel != null) {
                                viewModel.updateAdventureLoreContext(lore)
                            } else {
                                fallbackAdventureState = fallbackAdventureState.copy(
                                    settings = fallbackAdventureState.settings.copy(loreContext = lore)
                                )
                            }
                        },
                        onAttachLoreDocument = { uri, mime ->
                            viewModel?.attachDocumentToAdventure(uri, mime)
                        },
                        onUpdateCharacterProfile = { cName, cClass ->
                            if (viewModel != null) {
                                viewModel.updateAdventureCharacterProfile(cName, cClass)
                            } else {
                                fallbackAdventureState = fallbackAdventureState.copy(
                                    characterName = cName,
                                    characterClass = cClass
                                )
                            }
                        },
                        onUpdateCharacterPortrait = { uri ->
                            if (viewModel != null) {
                                viewModel.updateAdventureCharacterPortrait(uri)
                            } else {
                                fallbackAdventureState = fallbackAdventureState.copy(
                                    characterPortraitUri = uri.toString()
                                )
                            }
                        },
                        onUseOrInspectItem = { item ->
                            if (viewModel != null) {
                                viewModel.useOrInspectAdventureItem(item)
                            } else {
                                onSendMessage("[Adventure Action] I use/inspect my ${item.name} (${item.description}).")
                                fallbackAdventureState = fallbackAdventureState.copy(isDrawerOpen = false)
                            }
                            adventureScope.launch { adventureDrawerState.close() }
                        },
                        onRollProceduralLoot = {
                            if (viewModel != null) {
                                viewModel.rollAndAddProceduralLoot()
                            } else {
                                val loot = com.example.game.AdventureEngine.generateProceduralLoot(System.currentTimeMillis())
                                fallbackAdventureState = fallbackAdventureState.copy(
                                    inventory = listOf(loot) + fallbackAdventureState.inventory
                                )
                            }
                        },
                        onToggleQuestCompleted = { questId ->
                            if (viewModel != null) {
                                viewModel.toggleAdventureQuestCompleted(questId)
                            } else {
                                fallbackAdventureState = fallbackAdventureState.copy(
                                    quests = fallbackAdventureState.quests.map {
                                        if (it.id == questId) it.copy(isCompleted = !it.isCompleted) else it
                                    }
                                )
                            }
                        },
                        onAddCustomQuest = { qTitle, qDesc, qXp ->
                            if (viewModel != null) {
                                viewModel.addCustomAdventureQuest(qTitle, qDesc, qXp)
                            } else {
                                val newQ = com.example.game.AdventureQuest(
                                    id = "quest_${System.currentTimeMillis()}",
                                    title = qTitle,
                                    description = qDesc,
                                    isCompleted = false,
                                    xpReward = qXp
                                )
                                fallbackAdventureState = fallbackAdventureState.copy(
                                    quests = listOf(newQ) + fallbackAdventureState.quests
                                )
                            }
                        },
                        onSaveGlobalPreset = {
                            viewModel?.saveAdventureGlobalPreset()
                        },
                        onLoadGlobalPreset = {
                            viewModel?.loadAdventureGlobalPreset()
                        },
                        onDismissStatusBanner = {
                            if (viewModel != null) {
                                viewModel.dismissAdventureStatusBanner()
                            } else {
                                fallbackAdventureState = fallbackAdventureState.copy(statusBannerMessage = null)
                            }
                        },
                        onCloseDrawer = {
                            if (viewModel != null) {
                                viewModel.closeAdventureDrawer()
                            } else {
                                fallbackAdventureState = fallbackAdventureState.copy(isDrawerOpen = false)
                            }
                            adventureScope.launch { adventureDrawerState.close() }
                        },
                        onSelectQuestFilter = { filter ->
                            if (viewModel != null) {
                                viewModel.setAdventureQuestFilter(filter)
                            } else {
                                fallbackAdventureState = fallbackAdventureState.copy(
                                    settings = fallbackAdventureState.settings.copy(questFilter = filter)
                                )
                            }
                        }
                    )
                }
            }
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    topBar = {
                        TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("chat_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(accentColor.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = getIconForName(chat.iconName),
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = chat.title,
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                // API Key Status Pill
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (activeKeyType == ActiveKeyType.PRIMARY) MaterialTheme.colorScheme.surfaceVariant
                                            else MaterialTheme.colorScheme.tertiaryContainer
                                        )
                                        .clickable(onClick = onToggleActiveKey)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                        .testTag("api_key_status_pill")
                                ) {
                                    Text(
                                        text = activeKeyType.shortLabel,
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = if (activeKeyType == ActiveKeyType.PRIMARY) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                }

                                if (isPinnedChat) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.PushPin,
                                        contentDescription = "Pinned to Top of Chat List",
                                        tint = accentColor,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .testTag("chat_detail_pinned_indicator")
                                    )
                                }

                                if (chat.isDefaultWakeChat) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = "Default Wake Target",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            val isActivelyListeningForWake = (
                                isWakeWordListening ||
                                    isVoiceRecording ||
                                    (isHandsFreeActive && handsFreeVoiceState == HandsFreeVoiceState.LISTENING)
                                ) && !isSleepMode
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = buildString {
                                        append("Wake: \"${chat.wakeWord}\"")
                                        if (!chatCategory.isNullOrBlank()) {
                                            append(" • 📁 $chatCategory")
                                        }
                                        if (chatTags.isNotEmpty()) {
                                            append(" • ${chatTags.take(2).joinToString(" ") { "#$it" }}")
                                        }
                                        if (chat.totalVoiceCommands > 0) {
                                            append(" • ${chat.totalVoiceCommands} cmd${if (chat.totalVoiceCommands == 1) "" else "s"}")
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                AnimatedVisibility(
                                    visible = isActivelyListeningForWake,
                                    enter = fadeIn(),
                                    exit = fadeOut()
                                ) {
                                    SubtleWakeWordWaveformPill(
                                        isListening = isActivelyListeningForWake,
                                        audioRmsDb = audioRmsDb,
                                        barCount = 7,
                                        waveformWidth = 30.dp,
                                        waveformHeight = 12.dp
                                    )
                                }
                            }
                        }
                    }
                },
                actions = {
                    val isActivelyListeningInChat = (
                        isWakeWordListening ||
                            isVoiceRecording ||
                            isHandsFreeActive
                        ) && !isSleepMode

                    // Compact Hands-Free Radar Toggle Pill with start padding and max width so back arrow is unobstructed
                    com.example.ui.components.TopBarHandsFreeRadarPill(
                        isActive = isActivelyListeningInChat,
                        audioRmsDb = audioRmsDb,
                        isSleepMode = isSleepMode,
                        onToggle = {
                            if (isSleepMode) {
                                onExitSleepMode()
                            } else {
                                onToggleHandsFree()
                            }
                        },
                        testTagPrefix = "chat",
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .widthIn(max = 98.dp)
                    )

                    // Persistent Category Filter Toggle + Icon in Chat Interface Header
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (!isChatCategoryBarCollapsed) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        },
                        modifier = Modifier
                            .padding(start = 4.dp, end = 2.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { isChatCategoryBarCollapsed = !isChatCategoryBarCollapsed }
                            .testTag("chat_header_category_filter_toggle")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Folder,
                                contentDescription = "Toggle Category Filter Bar",
                                tint = if (!isChatCategoryBarCollapsed) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = (chatCategory ?: selectedChatCategoryFilter ?: "All").take(8),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (!isChatCategoryBarCollapsed) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }

                    // Header Toggle to Expand / Collapse Conversation Summary & Sync Drawer (collapsed by default)
                    IconButton(
                        onClick = {
                            if (!isSummaryCardVisible) {
                                isSummaryCardVisible = true
                                isSummaryCardExpanded = true
                            } else {
                                isSummaryCardExpanded = !isSummaryCardExpanded
                                isSummaryCardVisible = isSummaryCardExpanded
                            }
                        },
                        modifier = Modifier.testTag("chat_header_summary_toggle_button")
                    ) {
                        Box(contentAlignment = Alignment.TopEnd) {
                            Icon(
                                imageVector = Icons.Default.Description,
                                contentDescription = if (isSummaryCardVisible && isSummaryCardExpanded) {
                                    "Collapse Conversation Summary Drawer"
                                } else {
                                    "Expand Conversation Summary Drawer"
                                },
                                tint = if (isSummaryCardVisible && isSummaryCardExpanded) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            if (triggerBanner != null || wakeConversationSummary != null) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF10B981))
                                )
                            }
                        }
                    }

                    // Header Toggle to Open / Close Infinite Adventure Engine Side Drawer
                    IconButton(
                        onClick = {
                            val nextOpen = !activeAdventureState.isDrawerOpen
                            if (viewModel != null) {
                                if (nextOpen) {
                                    viewModel.openAdventureDrawer()
                                } else {
                                    viewModel.closeAdventureDrawer()
                                }
                            } else {
                                fallbackAdventureState = fallbackAdventureState.copy(isDrawerOpen = nextOpen)
                            }
                            adventureScope.launch {
                                if (nextOpen) adventureDrawerState.open() else adventureDrawerState.close()
                            }
                        },
                        modifier = Modifier.testTag("chat_header_adventure_drawer_button")
                    ) {
                        Box(contentAlignment = Alignment.TopEnd) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Toggle Infinite Adventure Engine Drawer",
                                tint = if (activeAdventureState.isAdventureModeEnabled || activeAdventureState.isDrawerOpen) {
                                    accentColor
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                            if (activeAdventureState.isAdventureModeEnabled || activeAdventureState.isSaveSentinelAlertVisible) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (activeAdventureState.isSaveSentinelAlertVisible) Color(0xFFF59E0B)
                                            else Color(0xFF10B981)
                                        )
                                )
                            }
                        }
                    }

                    if (isSpeaking) {
                        IconButton(
                            onClick = onStopSpeaking,
                            modifier = Modifier.testTag("stop_speaking_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Stop,
                                contentDescription = "Stop Speech",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.testTag("chat_detail_menu_button")
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Options")
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(if (isAutoSpeakEnabled) "Disable Read Aloud (TTS)" else "Enable Read Aloud (TTS)")
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isAutoSpeakEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                        contentDescription = null,
                                        tint = if (isAutoSpeakEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                modifier = Modifier.testTag("toggle_auto_speak_button"),
                                onClick = {
                                    menuExpanded = false
                                    onToggleAutoSpeak()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Two-Tier Buffer & Chrome Bridge") },
                                leadingIcon = { Icon(Icons.Default.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenBufferBridgeDialog()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Gemini Coupler & Piggyback") },
                                leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                onClick = {
                                    menuExpanded = false
                                    onOpenCouplerDialog()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Web Search & Link History Bar") },
                                leadingIcon = { Icon(Icons.Default.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.testTag("chat_web_history_button"),
                                onClick = {
                                    menuExpanded = false
                                    showWebHistoryBar = !showWebHistoryBar
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Import Chat or Context") },
                                leadingIcon = { Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.testTag("chat_import_button"),
                                onClick = {
                                    menuExpanded = false
                                    onOpenImportDialog()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Gemini Control & Settings Sheet") },
                                leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                                modifier = Modifier.testTag("chat_control_sheet_button"),
                                onClick = {
                                    menuExpanded = false
                                    onOpenControlSheet()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Edit Chat, Greeting & Wake Word") },
                                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onEditChat()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (isPinnedChat) "Unpin from Top of List" else "Pin Chat to Top of List")
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isPinnedChat) Icons.Default.PushPin else Icons.Outlined.PushPin,
                                        contentDescription = null,
                                        tint = if (isPinnedChat) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                modifier = Modifier.testTag("chat_menu_toggle_pin"),
                                onClick = {
                                    menuExpanded = false
                                    onTogglePinChat()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (isFavoriteChat) "Remove from Favorite Chats" else "Star as Favorite Chat")
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Star,
                                        contentDescription = null,
                                        tint = if (isFavoriteChat) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                modifier = Modifier.testTag("chat_menu_toggle_favorite"),
                                onClick = {
                                    menuExpanded = false
                                    onToggleFavoriteChat()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Export & Share Logs (.json)") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                modifier = Modifier.testTag("chat_menu_share_json_logs"),
                                onClick = {
                                    menuExpanded = false
                                    onShareLogsAsJson()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Export & Share Data (.csv)") },
                                leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                                modifier = Modifier.testTag("chat_menu_share_csv_logs"),
                                onClick = {
                                    menuExpanded = false
                                    onShareLogsAsCsv()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (isSleepMode) "Wake Up Listener (\"Gemini, wake up\")"
                                        else "Sleep Listener (\"Gemini, sleep\")"
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = if (isSleepMode) Icons.Default.Mic else Icons.Default.MicOff,
                                        contentDescription = null,
                                        tint = if (isSleepMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    if (isSleepMode) onExitSleepMode() else onEnterSleepMode()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Customize Palette & Background") },
                                leadingIcon = { Icon(Icons.Default.Palette, contentDescription = null, tint = accentColor) },
                                modifier = Modifier.testTag("chat_menu_customize_theme"),
                                onClick = {
                                    menuExpanded = false
                                    showThemeCustomizationDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Clear Chat History", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menuExpanded = false
                                    onClearHistory()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Power Off / Exit App", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                modifier = Modifier.testTag("chat_power_exit_button"),
                                onClick = {
                                    menuExpanded = false
                                    onPowerExit()
                                }
                            )

                            androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Text(
                                text = "Chat Theme",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(start = 12.dp, bottom = 4.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            com.example.ui.theme.AppTheme.values().forEach { theme ->
                                val isSelected = chat.themeMode == theme
                                DropdownMenuItem(
                                    text = { Text(theme.displayName) },
                                    leadingIcon = {
                                        if (isSelected) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                        } else {
                                            Spacer(modifier = Modifier.size(24.dp))
                                        }
                                    },
                                    onClick = {
                                        menuExpanded = false
                                        onUpdateChatTheme(theme)
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                AnimatedVisibility(
                    visible = screenshotBanner != null,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it }
                ) {
                    if (screenshotBanner != null) {
                        ScreenshotHeadsUpBanner(
                            imageInfo = screenshotBanner,
                            onDismiss = onDismissScreenshotBanner,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = stagedImageUri != null,
                    enter = fadeIn() + slideInVertically { it },
                    exit = fadeOut() + slideOutVertically { it }
                ) {
                    if (stagedImageUri != null) {
                        StagedImageTray(
                            stagedImageUri = stagedImageUri,
                            stagedImageInfo = stagedImageInfo,
                            onRemove = onRemoveStagedImage,
                            onDispatchToCoupler = { onDispatchScreenshotToCoupler(inputText.text) },
                            promptText = inputText.text,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }

                if (isInputFloating) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BottomBarNetworkStatusIcon(
                                networkState = networkState,
                                onRefreshNetwork = onRefreshNetwork
                            )
                            FilledTonalButton(
                                onClick = {
                                    isInputFloating = false
                                    isFloatingCollapsedToDot = false
                                },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier
                                    .height(32.dp)
                                    .testTag("bottom_bar_redock_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VerticalAlignBottom,
                                    contentDescription = "Dock Input Box",
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Dock Input Box",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
                                )
                            }
                        }
                    }
                } else {
                    ChatBottomInputBar(
                        inputText = inputText,
                        onInputChange = { inputText = it },
                        onSend = {
                            val messageToSend = inputText.text.trim().ifBlank { draftSpokenText.trim() }
                            if ((messageToSend.isNotBlank() || attachedImageUri != null || stagedAttachment != null || isVoiceRecording) && !isSending) {
                                isSending = true
                                inputText = TextFieldValue("")
                                onSendMessage(messageToSend)
                                isSending = false
                            }
                        },
                        isGenerating = isGenerating,
                        isSending = isSending,
                        customPrompts = customPrompts,
                        showPromptsBar = showPromptsBar,
                        onTogglePromptsBar = { showPromptsBar = !showPromptsBar },
                        onSelectPrompt = { prompt ->
                            val currentText = inputText.text
                            val newText = if (currentText.isBlank()) prompt.promptText else "$currentText\n${prompt.promptText}"
                            inputText = TextFieldValue(
                                text = newText,
                                selection = androidx.compose.ui.text.TextRange(newText.length)
                            )
                        },
                        onOpenCreatePrompt = { showCreatePromptDialog = true },
                        onDeletePrompt = onDeleteCustomPrompt,
                        onPickImage = {
                            attachImagePickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        onPasteClipboardImage = {
                            tryPasteImageFromClipboard(true)
                        },
                        onTakePhoto = onTakePhoto,
                        onBrowseFiles = onBrowseFiles,
                        attachedImageUri = attachedImageUri,
                        stagedAttachment = stagedAttachment,
                        onClearStagedAttachment = onClearAttachedImage,
                        isVoiceRecording = isVoiceRecording,
                        isInChatDictationActive = isInChatDictationActive,
                        audioRmsDb = audioRmsDb,
                        viewModel = viewModel,
                        onStartVoiceRecording = {
                            if (viewModel != null) {
                                viewModel.toggleInternalDictation()
                            } else {
                                onToggleInChatDictation()
                            }
                        },
                        onToggleInChatDictation = {
                            if (viewModel != null) {
                                viewModel.toggleInternalDictation()
                            } else {
                                onToggleInChatDictation()
                            }
                        },
                        onSendVoiceRecording = {
                            val textToInclude = inputText.text.trim().ifBlank { draftSpokenText.trim() }
                            if (!isSending) {
                                isSending = true
                                inputText = TextFieldValue("")
                                onSendVoiceRecording(textToInclude)
                                isSending = false
                            }
                        },
                        onCancelVoiceRecording = onCancelVoiceRecording,
                        networkState = networkState,
                        onRefreshNetwork = onRefreshNetwork,
                        onUndockToFloating = {
                            isInputFloating = true
                            isFloatingCollapsedToDot = false
                        },
                        onTextInputFocusChanged = { focused ->
                            if (focused) {
                                tryPasteImageFromClipboard(false)
                            }
                            onTextInputFocusChanged(focused)
                        },
                        hasBufferedSpeech = isDictationScopedToComposer && draftSpokenText.isNotBlank()
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            // Custom / Curated Background Layer for Chat Interface
            ChatCustomizableBackgroundLayer(
                backgroundStyle = chat.backgroundStyle,
                modifier = Modifier.fillMaxSize()
            )

            Column(
                modifier = Modifier.fillMaxSize()
            ) {
            ChatImportProgressBanner(
                progressState = importProgressState,
                onCancelImport = onCancelImport
            )

            AnimatedVisibility(visible = showWebHistoryBar) {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    WebSearchAndHistoryBar(
                        historyEntries = webLinkHistory,
                        onSubmitSearchOrUrl = onSubmitWebSearchOrUrl,
                        onOpenHistoryEntry = onOpenWebHistoryEntry,
                        onDeleteHistoryEntry = onDeleteWebHistoryEntry,
                        onClearAllHistory = onClearAllWebHistory,
                        voiceCommandEntries = voiceCommandHistory,
                        onUseVoiceCommandTranscript = { voiceEntry ->
                            inputText = TextFieldValue(
                                text = voiceEntry.recognizedTranscript,
                                selection = androidx.compose.ui.text.TextRange(voiceEntry.recognizedTranscript.length)
                            )
                        },
                        onDeleteVoiceCommandEntry = onDeleteVoiceCommandEntry,
                        onClearAllVoiceCommands = onClearAllVoiceCommands,
                        onClearCorruptedHistory = onClearCorruptedHistory,
                        isHistoryExpandedExternal = showWebHistoryBar,
                        onSetHistoryExpandedExternal = { showWebHistoryBar = it },
                        onTextInputFocusChanged = onTextInputFocusChanged
                    )
                }
            }

            SleepModeVisualBanner(
                isSleepMode = isSleepMode,
                sleepReason = sleepModeReason,
                onWakeUpTap = onExitSleepMode
            )

            // Collapsible Top Summary & Wake Telemetry Drawer (collapsed by default behind chat_header_summary_toggle_button)
            val resolvedWakeSummary = remember(wakeConversationSummary, triggerBanner, isSummaryCardVisible, chat, isolatedMessages) {
                wakeConversationSummary ?: if (triggerBanner != null || isSummaryCardVisible) {
                    com.example.voice.WakeWordRoutingEngine.buildInstantWakeConversationSummary(
                        chat = chat,
                        messages = isolatedMessages,
                        matchedWakeWord = triggerBanner?.matchedWord ?: chat.wakeWord
                    )
                } else {
                    null
                }
            }
            AnimatedVisibility(
                visible = isSummaryCardVisible && isSummaryCardExpanded,
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { -it / 2 }
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 4.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("chat_top_summary_drawer")
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 6.dp)
                    ) {
                        val showWakeWaveformBanner = (
                            isWakeWordListening ||
                                isVoiceRecording ||
                                (isHandsFreeActive && handsFreeVoiceState == HandsFreeVoiceState.LISTENING)
                            ) && !isSleepMode
                        WakeWordListeningStatusBanner(
                            isListening = showWakeWaveformBanner,
                            audioRmsDb = audioRmsDb,
                            activeWakePhrase = chat.wakeWord.ifBlank { "Hey Gemini" },
                            lastHeardText = lastHeardText.ifBlank { draftSpokenText },
                            totalVoiceCommands = chat.totalVoiceCommands
                        )

                        if (triggerBanner != null) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Mic,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Opened by wake word: \"${triggerBanner.matchedWord}\"",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    IconButton(
                                        onClick = onDismissTriggerBanner,
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Dismiss",
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (resolvedWakeSummary != null) {
                            com.example.ui.components.CollapsibleWakeWordSummaryCard(
                                summary = resolvedWakeSummary,
                                isFavorite = isFavoriteChat,
                                externalExpanded = isSummaryCardExpanded,
                                onExpandedChange = { isSummaryCardExpanded = it },
                                onToggleFavorite = onToggleFavoriteChat,
                                onGenerateAiSummary = onGenerateWakeAiSummary,
                                onOpenQuickSwitchDialog = onOpenWakeFavoritesPicker,
                                onDismiss = {
                                    isSummaryCardVisible = false
                                    isSummaryCardExpanded = false
                                    onDismissWakeSummary()
                                    onDismissTriggerBanner()
                                }
                            )
                        }
                    }
                }
            }



            // Pinned Custom Chat Greeting Banner
            if (chat.greetingMessage.isNotBlank()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("chat_greeting_banner"),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = accentColor.copy(alpha = 0.12f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = accentColor.copy(alpha = 0.35f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(accentColor.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = getIconForName(chat.iconName),
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${chat.title} Greeting",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = accentColor
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = chat.greetingMessage,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.testTag("chat_greeting_text")
                            )
                        }
                        IconButton(
                            onClick = { onSpeakText(chat.greetingMessage) },
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("speak_chat_greeting_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                contentDescription = "Read greeting aloud",
                                tint = accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = onEditChat,
                            modifier = Modifier
                                .size(32.dp)
                                .testTag("edit_chat_greeting_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit custom greeting",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            if (showCreatePromptDialog) {
                CreatePromptDialog(
                    onDismiss = { showCreatePromptDialog = false },
                    onSave = { pTitle, pText ->
                        onAddCustomPrompt(pTitle, pText)
                        showCreatePromptDialog = false
                        Toast.makeText(context, "Added shortcut \"$pTitle\"", Toast.LENGTH_SHORT).show()
                    }
                )
            }

            if (showThemeCustomizationDialog) {
                ChatThemeCustomizationDialog(
                    currentPaletteId = chat.colorPaletteId,
                    currentBackgroundStyle = chat.backgroundStyle,
                    onSelectAppearance = { paletteId, bgStyle, primaryHex ->
                        onUpdateChatAppearance(paletteId, bgStyle, primaryHex)
                    },
                    onPickCustomBackgroundPhoto = {
                        customBackgroundPhotoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onDismiss = { showThemeCustomizationDialog = false }
                )
            }

            // 20-Minute Save Sentinel In-App Quick Save Snackbar Banner
            AnimatedVisibility(
                visible = activeAdventureState.isSaveSentinelAlertVisible,
                enter = fadeIn() + slideInVertically { -it / 2 },
                exit = fadeOut() + slideOutVertically { -it / 2 }
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color(0xFF1E293B),
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFF59E0B)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("adventure_save_sentinel_snackbar")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Save Sentinel: 20+ Mins Unsaved Campaign",
                                style = MaterialTheme.typography.labelLarge.copy(
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFFFBBF24)
                                )
                            )
                            Text(
                                text = "Save your Level ${activeAdventureState.level} progress now or say \"save game\".",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFE2E8F0)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = {
                                if (viewModel != null) {
                                    viewModel.saveAdventureGameManually()
                                } else {
                                    fallbackAdventureState = fallbackAdventureState.copy(
                                        unsavedPlaySeconds = 0L,
                                        lastSavedAt = System.currentTimeMillis(),
                                        isSaveSentinelAlertVisible = false
                                    )
                                }
                            },
                            modifier = Modifier.testTag("adventure_snackbar_quick_save_button")
                        ) {
                            Text("Quick Save", fontWeight = FontWeight.Bold)
                        }
                        IconButton(
                            onClick = {
                                if (viewModel != null) {
                                    viewModel.dismissAdventureSaveSentinelAlert()
                                } else {
                                    fallbackAdventureState = fallbackAdventureState.copy(isSaveSentinelAlertVisible = false)
                                }
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss Save Sentinel",
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Message List (Maximized Vertical Viewport with SelectionContainer Handle Clearance)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("chat_messages_lazy_column"),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 16.dp,
                    bottom = if (isInputFloating) 108.dp else 36.dp
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (hasMoreOlderMessages) {
                    item(key = "load_older_messages_header") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            OutlinedButton(
                                onClick = onLoadOlderMessages,
                                enabled = !isLoadingOlder,
                                modifier = Modifier.testTag("load_older_messages_button")
                            ) {
                                if (isLoadingOlder) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Paging from Disk Cache...")
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Load Older History from Disk Cache")
                                }
                            }
                        }
                    }
                }

                items(
                    items = isolatedMessages,
                    key = { message -> message.id }
                ) { message ->
                    val effectiveMessageCategory = messageCategoryMap[message.id] ?: chatCategory
                    ChatMessageBubble(
                        message = message,
                        messageCategory = effectiveMessageCategory,
                        onCopy = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Gemini Message", message.content)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        onSpeak = {
                            onSpeakText(message.content)
                        },
                        onOpenLink = { url, label ->
                            onOpenChatLink(url, label)
                        },
                        onOpenAdventureTab = { targetTab ->
                            if (viewModel != null) {
                                viewModel.openAdventureDrawer(targetTab)
                            } else {
                                fallbackAdventureState = fallbackAdventureState.copy(
                                    isDrawerOpen = true,
                                    activeDrawerTab = targetTab
                                )
                            }
                            adventureScope.launch { adventureDrawerState.open() }
                        }
                    )
                }

                // Streaming in-progress message
                if (streamingText != null) {
                    item(key = "streaming_in_progress_bubble") {
                        GeminiStreamingBubble(text = streamingText)
                    }
                }

                // Error message banner
                if (errorMessage != null) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Generation Error",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Text(
                                        text = errorMessage,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }

            if (isInputFloating) {
                FloatingMovableChatInput(
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    onSend = {
                        val messageToSend = inputText.text.trim().ifBlank { draftSpokenText.trim() }
                        if ((messageToSend.isNotBlank() || stagedAttachment != null || isVoiceRecording) && !isSending) {
                            isSending = true
                            inputText = TextFieldValue("")
                            onSendMessage(messageToSend)
                            isSending = false
                        }
                    },
                    isGenerating = isGenerating,
                    isSending = isSending,
                    isCollapsedToDot = isFloatingCollapsedToDot,
                    onToggleCollapseToDot = { isFloatingCollapsedToDot = !isFloatingCollapsedToDot },
                    onDockToBottomBar = {
                        isInputFloating = false
                        isFloatingCollapsedToDot = false
                    },
                    offsetX = floatingOffsetX,
                    offsetY = floatingOffsetY,
                    onDragDelta = { dx, dy ->
                        floatingOffsetX = (floatingOffsetX + dx).coerceAtLeast(0f)
                        floatingOffsetY = (floatingOffsetY + dy).coerceAtLeast(0f)
                    },
                    accentColor = accentColor,
                    networkState = networkState,
                    onRefreshNetwork = onRefreshNetwork,
                    stagedAttachment = stagedAttachment,
                    onTakePhoto = onTakePhoto,
                    onBrowseFiles = onBrowseFiles,
                    isInChatDictationActive = isInChatDictationActive,
                    viewModel = viewModel,
                    onStartVoiceRecording = {
                        if (viewModel != null) {
                            viewModel.toggleInternalDictation()
                        } else {
                            onToggleInChatDictation()
                        }
                    },
                    onToggleInChatDictation = {
                        if (viewModel != null) {
                            viewModel.toggleInternalDictation()
                        } else {
                            onToggleInChatDictation()
                        }
                    },
                    onTextInputFocusChanged = {}
                )
            }

            // Persistent Right-Edge Collapsible Category Filter Chip Bar inside Chat Interface
            com.example.ui.components.CollapsibleSideCategoryFilterBar(
                selectedCategory = selectedChatCategoryFilter,
                onSelectCategory = { selectedChatCategoryFilter = it },
                isCollapsed = isChatCategoryBarCollapsed,
                onToggleCollapsed = { isChatCategoryBarCollapsed = !isChatCategoryBarCollapsed },
                chats = allChats,
                chatCategoryMap = chatCategoryMap,
                currentChatId = chat.id,
                currentChatCategory = chatCategory,
                isInChatInterface = true,
                onNavigateToChat = { targetId: Long ->
                    isChatCategoryBarCollapsed = true
                    onSelectChatFromCategoryBar(targetId)
                },
                onReassignCurrentChatCategory = { newCategory: String ->
                    selectedChatCategoryFilter = newCategory
                    onReassignChatCategory(newCategory)
                },
                testTagPrefix = "chat",
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp)
            )
        }
    }
            }
        }
    }
}

@Composable
fun ChatMessageBubble(
    message: ChatMessage,
    messageCategory: String? = null,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onOpenLink: (String, String?) -> Unit = { _, _ -> },
    onOpenAdventureTab: (com.example.game.AdventureDrawerTab) -> Unit = {}
) {
    val isUser = message.sender == "user"
    val parsedAdventure = remember(message.content) {
        com.example.game.AdventureEngine.parseInlineMarkers(message.content)
    }
    val extractedLinks = remember(message.content) {
        WebLinkHistoryManager.extractLinksFromText(message.content)
    }
    val timeFormatted = remember(message.timestamp) {
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
        sdf.format(Date(message.timestamp))
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            val context = LocalContext.current
            val transcribedText = message.sanitizedTextContent
            val hasTranscribedText = transcribedText.isNotEmpty()
            // Hide legacy audio attachment placeholders when actual transcribed text is available or when placeholder-only
            val hasVisibleAttachment = message.hasVisibleNonAudioAttachment
            if (!hasTranscribedText && !hasVisibleAttachment) {
                return@Column
            }
            val isImageAttachment = message.attachmentMimeType?.startsWith("image/") == true

            // User message bubble
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .widthIn(max = 300.dp)
                        .testTag("user_message_bubble_${message.id}")
                ) {
                    SelectionContainer(
                        modifier = Modifier.testTag("user_message_selection_container_${message.id}")
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            if (hasVisibleAttachment) {
                                DisableSelection {
                                    if (isImageAttachment) {
                                        val decodedBitmap = remember(message.attachmentUri) {
                                            try {
                                                message.attachmentUri?.let { uriStr ->
                                                    val uri = Uri.parse(uriStr)
                                                    context.contentResolver.openInputStream(uri)?.use { stream ->
                                                        BitmapFactory.decodeStream(stream)?.asImageBitmap()
                                                    }
                                                }
                                            } catch (_: Exception) {
                                                null
                                            }
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = if (hasTranscribedText) 8.dp else 4.dp)
                                                .testTag("message_attachment_image_preview")
                                        ) {
                                            Column {
                                                if (decodedBitmap != null) {
                                                    Image(
                                                        bitmap = decodedBitmap,
                                                        contentDescription = message.attachmentName ?: "Attached photo",
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(150.dp)
                                                            .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp))
                                                    )
                                                } else {
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(96.dp)
                                                            .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.1f)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.CameraAlt,
                                                            contentDescription = "Photo preview",
                                                            tint = MaterialTheme.colorScheme.onPrimary,
                                                            modifier = Modifier.size(32.dp)
                                                        )
                                                    }
                                                }
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.CameraAlt,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onPrimary,
                                                        modifier = Modifier.size(14.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(
                                                        text = message.attachmentName ?: "Image",
                                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                                                        color = MaterialTheme.colorScheme.onPrimary,
                                                        maxLines = 1,
                                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(bottom = if (hasTranscribedText) 8.dp else 4.dp)
                                                .testTag("message_attachment_file_chip")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Description,
                                                    contentDescription = "Attached document",
                                                    tint = MaterialTheme.colorScheme.onPrimary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = message.attachmentName ?: "Document",
                                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                                        color = MaterialTheme.colorScheme.onPrimary,
                                                        maxLines = 1,
                                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                    )
                                                    if (message.attachmentSizeBytes != null) {
                                                        Text(
                                                            text = formatFileSize(message.attachmentSizeBytes),
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if (hasTranscribedText) {
                                MarkdownMessageContent(
                                    content = transcribedText,
                                    textColor = MaterialTheme.colorScheme.onPrimary,
                                    linkColor = MaterialTheme.colorScheme.onPrimary,
                                    messageId = message.id,
                                    onOpenLink = onOpenLink,
                                    wrapInSelectionContainer = false,
                                    modifier = Modifier.testTag("user_message_text_${message.id}")
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                            }
                            var userMessageCopied by remember(message.id) { mutableStateOf(false) }
                            LaunchedEffect(userMessageCopied) {
                                if (userMessageCopied) {
                                    delay(1800)
                                    userMessageCopied = false
                                }
                            }
                            // Wrap category badge, timestamp, sender metadata, and copy button in DisableSelection
                            // so "Select All" only selects the actual message body text.
                            DisableSelection {
                                Row(
                                    modifier = Modifier.align(Alignment.End),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (!messageCategory.isNullOrBlank()) {
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f),
                                            modifier = Modifier.testTag("message_category_badge_${message.id}")
                                        ) {
                                            Text(
                                                text = "📁 $messageCategory",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                    Text(
                                        text = timeFormatted,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                                    )
                                    // Out-of-Turn Message Copy button on User bubble (reads directly from Room ChatMessage.content)
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (userMessageCopied) {
                                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.28f)
                                        } else {
                                            MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.14f)
                                        },
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable {
                                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                val rawText = message.content.ifBlank { transcribedText }
                                                clipboard.setPrimaryClip(ClipData.newPlainText("Chat Message", rawText))
                                                userMessageCopied = true
                                            }
                                            .testTag("copy_message_button_${message.id}")
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = if (userMessageCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                                contentDescription = "Copy user message to clipboard",
                                                tint = MaterialTheme.colorScheme.onPrimary,
                                                modifier = Modifier.size(11.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = if (userMessageCopied) "Copied!" else "Copy",
                                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // Gemini message bubble
            val context = LocalContext.current
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                DisableSelection {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    listOf(Color(0xFF38BDF8), Color(0xFFA855F7))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Gemini",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .testTag("gemini_message_bubble_${message.id}")
                ) {
                    SelectionContainer(
                        modifier = Modifier.testTag("gemini_message_selection_container_${message.id}")
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            // Markdown prose, lists, inline styles & fenced code blocks inside parent SelectionContainer
                            MarkdownMessageContent(
                                content = parsedAdventure.cleanedText,
                                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                linkColor = MaterialTheme.colorScheme.primary,
                                messageId = message.id,
                                onOpenLink = onOpenLink,
                                wrapInSelectionContainer = false
                            )

                            if (parsedAdventure.events.isNotEmpty()) {
                                DisableSelection {
                                    com.example.ui.components.AdventureInlineEventBadges(
                                        events = parsedAdventure.events,
                                        onOpenDrawerToTab = onOpenAdventureTab
                                    )
                                }
                            }

                            if (extractedLinks.isNotEmpty()) {
                                DisableSelection {
                                    Column {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            extractedLinks.forEachIndexed { linkIdx, link ->
                                                Surface(
                                                    shape = RoundedCornerShape(10.dp),
                                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .clickable { onOpenLink(link.url, link.label) }
                                                        .testTag("chat_message_link_chip_${message.id}_$linkIdx")
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.weight(1f),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Public,
                                                                contentDescription = "Web link",
                                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                                modifier = Modifier.size(15.dp)
                                                            )
                                                            Spacer(modifier = Modifier.width(6.dp))
                                                            Column {
                                                                Text(
                                                                    text = link.label,
                                                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                                    maxLines = 1,
                                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                                )
                                                                Text(
                                                                    text = link.url,
                                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
                                                                    maxLines = 1,
                                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                                )
                                                            }
                                                        }
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Icon(
                                                            imageVector = Icons.Default.OpenInNew,
                                                            contentDescription = "Open link in default browser",
                                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                            modifier = Modifier.size(15.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            var copied by remember(message.id) { mutableStateOf(false) }
                            LaunchedEffect(copied) {
                                if (copied) {
                                    delay(1800)
                                    copied = false
                                }
                            }
                            // Wrap category badge, timestamp, speaker icon, and copy button in DisableSelection
                            // so "Select All" only grabs the actual message body without trailing UI metadata.
                            DisableSelection {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        if (!messageCategory.isNullOrBlank()) {
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                                                modifier = Modifier.testTag("message_category_badge_${message.id}")
                                            ) {
                                                Text(
                                                    text = "📁 $messageCategory",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = timeFormatted,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                        )
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        IconButton(
                                            onClick = onSpeak,
                                            modifier = Modifier
                                                .size(26.dp)
                                                .testTag("speak_message_button_${message.id}")
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                                                contentDescription = "Read message aloud",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (copied) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                                            },
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    // Out-of-Turn Message Copy: read directly from Room ChatMessage.content
                                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                    clipboard.setPrimaryClip(ClipData.newPlainText("Gemini Message", message.content))
                                                    copied = true
                                                    onCopy()
                                                }
                                                .testTag("copy_message_button_${message.id}")
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(
                                                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                                    contentDescription = "Copy model response to clipboard",
                                                    tint = if (copied) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = if (copied) "Copied!" else "Copy",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                                    color = if (copied) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LinkifiedMessageText(
    text: String,
    textColor: Color,
    linkColor: Color,
    onOpenLink: (String, String?) -> Unit
) {
    val links = remember(text) { WebLinkHistoryManager.extractLinksFromText(text) }
    if (links.isEmpty()) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            color = textColor
        )
        return
    }

    val annotatedString = remember(text, links, textColor, linkColor) {
        buildAnnotatedString {
            append(text)
            links.forEach { link ->
                val safeStart = link.startIndex.coerceIn(0, text.length)
                val safeEnd = link.endIndex.coerceIn(safeStart, text.length)
                if (safeStart < safeEnd) {
                    val linkStyle = SpanStyle(
                        color = linkColor,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline
                    )
                    addStyle(
                        style = linkStyle,
                        start = safeStart,
                        end = safeEnd
                    )
                    addLink(
                        url = LinkAnnotation.Url(
                            url = link.url,
                            styles = TextLinkStyles(style = linkStyle),
                            linkInteractionListener = {
                                onOpenLink(link.url, link.label)
                            }
                        ),
                        start = safeStart,
                        end = safeEnd
                    )
                }
            }
        }
    }

    Text(
        text = annotatedString,
        style = MaterialTheme.typography.bodyLarge.copy(color = textColor)
    )
}

@Composable
fun GeminiStreamingBubble(text: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        DisableSelection {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(Color(0xFF38BDF8), Color(0xFFA855F7))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "Gemini",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Card(
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            SelectionContainer {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (text.isNotBlank()) {
                        MarkdownMessageContent(
                            content = text,
                            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            linkColor = MaterialTheme.colorScheme.primary,
                            wrapInSelectionContainer = false
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    DisableSelection {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Gemini is replying...",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatBottomInputBar(
    inputText: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    isGenerating: Boolean,
    isSending: Boolean = false,
    customPrompts: List<PromptTemplate> = emptyList(),
    showPromptsBar: Boolean = false,
    onTogglePromptsBar: () -> Unit = {},
    onSelectPrompt: (PromptTemplate) -> Unit = {},
    onOpenCreatePrompt: () -> Unit = {},
    onDeletePrompt: (String) -> Unit = {},
    onPickImage: () -> Unit = {},
    onPasteClipboardImage: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onBrowseFiles: () -> Unit = {},
    attachedImageUri: Uri? = null,
    stagedAttachment: com.example.ui.AttachmentPayload? = null,
    onClearStagedAttachment: () -> Unit = {},
    isVoiceRecording: Boolean = false,
    isInChatDictationActive: Boolean = false,
    audioRmsDb: Float = 0f,
    viewModel: com.example.ui.MainViewModel? = null,
    onStartVoiceRecording: () -> Unit = {},
    onToggleInChatDictation: () -> Unit = {
        if (viewModel != null) {
            viewModel.toggleInternalDictation()
        } else {
            onStartVoiceRecording()
        }
    },
    onSendVoiceRecording: () -> Unit = {},
    onCancelVoiceRecording: () -> Unit = {},
    networkState: NetworkConnectionState = NetworkConnectionState.ONLINE_WIFI,
    onRefreshNetwork: () -> Unit = {},
    onUndockToFloating: () -> Unit = {},
    onTextInputFocusChanged: (Boolean) -> Unit = {},
    hasBufferedSpeech: Boolean = false
) {
    val context = LocalContext.current
    val hasStagedPreview = attachedImageUri != null || stagedAttachment != null

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat_bottom_composer_surface"),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .padding(top = 4.dp)
        ) {
            // Compact Bottom Bar Utility Strip: Network Status Icon + Paste Image + Floating Input Anchor Toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomBarNetworkStatusIcon(
                    networkState = networkState,
                    onRefreshNetwork = onRefreshNetwork
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onPasteClipboardImage() }
                            .testTag("paste_clipboard_image_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = "Paste image from clipboard",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Paste Image",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onUndockToFloating() }
                            .testTag("toggle_floating_input_button")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.OpenWith,
                                contentDescription = "Float Input Box with Anchor Dot",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Float Input",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            // Removable Image Thumbnail Preview Chip with dismiss "✕" icon above text input bar
            AnimatedVisibility(
                visible = hasStagedPreview,
                enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 })
            ) {
                if (hasStagedPreview) {
                    val isImage = attachedImageUri != null || stagedAttachment?.mimeType?.startsWith("image/") == true
                    val thumbnailBitmap = remember(stagedAttachment?.bytes, attachedImageUri, isImage) {
                        if (stagedAttachment != null && isImage && stagedAttachment.bytes.isNotEmpty()) {
                            try {
                                BitmapFactory.decodeByteArray(stagedAttachment.bytes, 0, stagedAttachment.bytes.size)?.asImageBitmap()
                            } catch (_: Exception) {
                                null
                            }
                        } else if (attachedImageUri != null) {
                            try {
                                context.contentResolver.openInputStream(attachedImageUri)?.use { stream ->
                                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                                }
                            } catch (_: Exception) {
                                null
                            }
                        } else null
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("staged_attachment_chip"),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .testTag("attached_image_preview_chip"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isImage) {
                                    if (thumbnailBitmap != null) {
                                        Image(
                                            bitmap = thumbnailBitmap,
                                            contentDescription = stagedAttachment?.fileName ?: "Staged image preview",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .testTag("staged_attachment_thumbnail")
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier
                                                .size(44.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(MaterialTheme.colorScheme.primaryContainer)
                                                .testTag("staged_attachment_thumbnail"),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Image,
                                                contentDescription = "Photo attachment",
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Description,
                                        contentDescription = "Document attachment",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stagedAttachment?.fileName ?: attachedImageUri?.lastPathSegment ?: "Attached Image",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = if (stagedAttachment != null) {
                                            formatFileSize(stagedAttachment.sizeBytes)
                                        } else {
                                            "Ready to send with message"
                                        },
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(
                                onClick = onClearStagedAttachment,
                                modifier = Modifier
                                    .size(28.dp)
                                    .testTag("remove_attachment_button")
                            ) {
                                Text(
                                    text = "✕",
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = showPromptsBar) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                        .padding(vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "QUICK PROMPTS",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Tap to insert into chat",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                modifier = Modifier.testTag("add_custom_prompt_chip"),
                                onClick = onOpenCreatePrompt
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Add custom prompt",
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        "New Shortcut",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }

                        items(customPrompts, key = { it.id }) { prompt ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)),
                                modifier = Modifier.testTag("prompt_chip_${prompt.id}"),
                                onClick = { onSelectPrompt(prompt) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = when (prompt.iconName) {
                                            "Code" -> Icons.Default.Code
                                            "Lightbulb" -> Icons.Default.Lightbulb
                                            else -> Icons.Default.AutoAwesome
                                        },
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = prompt.title,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (prompt.isCustom) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        IconButton(
                                            onClick = { onDeletePrompt(prompt.id) },
                                            modifier = Modifier.size(16.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Delete shortcut",
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(12.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Combined Input & Voice Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (isVoiceRecording || isInChatDictationActive) {
                    // COMPACT LIVE RECORDING BANNER (Shown above composer when in-chat dictation is active so composer text field remains visible)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f))
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onCancelVoiceRecording,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .testTag("cancel_voice_button")
                        ) {
                            Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(18.dp))
                        }

                        // Compact Waveform
                        VoiceWaveformVisualizer(
                            isListening = true,
                            audioRmsDb = audioRmsDb,
                            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            height = 24.dp,
                            barCount = 14
                        )

                        IconButton(
                            onClick = onSendVoiceRecording,
                            enabled = !isSending,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .testTag("send_voice_button")
                        ) {
                            Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                if (!isVoiceRecording || isInChatDictationActive) {
                    // STANDARD INPUT ROW (Always stays visible during manual in-chat dictation so live transcript populates in real time)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onTogglePromptsBar,
                            enabled = !isSending,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (showPromptsBar) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .size(40.dp)
                                .testTag("toggle_custom_prompts_button")
                        ) {
                            Icon(Icons.Default.Tune, "Quick Prompts", tint = if (showPromptsBar) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary)
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        // Dedicated Attach Image button (PickVisualMedia)
                        IconButton(
                            onClick = onPickImage,
                            enabled = !isSending,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .size(40.dp)
                                .testTag("attach_image_picker_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Image,
                                contentDescription = "Attach Image",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        Box {
                            var showAttachmentMenu by remember { mutableStateOf(false) }
                            IconButton(
                                onClick = { showAttachmentMenu = true },
                                enabled = !isSending,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .size(40.dp)
                                    .testTag("attachment_button")
                            ) {
                                Icon(Icons.Default.Add, "More attachments", tint = MaterialTheme.colorScheme.primary)
                            }
                            DropdownMenu(expanded = showAttachmentMenu, onDismissRequest = { showAttachmentMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Pick Image from Gallery") },
                                    leadingIcon = { Icon(Icons.Default.Image, null) },
                                    onClick = { showAttachmentMenu = false; onPickImage() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Paste Image from Clipboard") },
                                    leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                                    onClick = { showAttachmentMenu = false; onPasteClipboardImage() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Take Photo") },
                                    leadingIcon = { Icon(Icons.Default.CameraAlt, null) },
                                    onClick = { showAttachmentMenu = false; onTakePhoto() }
                                )
                                DropdownMenuItem(
                                    text = { Text("Browse Files") },
                                    leadingIcon = { Icon(Icons.Default.Folder, null) },
                                    onClick = { showAttachmentMenu = false; onBrowseFiles() }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        OutlinedTextField(
                            value = inputText,
                            onValueChange = onInputChange,
                            enabled = !isSending,
                            placeholder = { Text("Ask Gemini...") },
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { focusState ->
                                    onTextInputFocusChanged(focusState.isFocused)
                                }
                                .testTag("chat_input_textfield"),
                            shape = RoundedCornerShape(24.dp),
                            maxLines = 4,
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Send,
                                autoCorrectEnabled = false
                            ),
                            keyboardActions = KeyboardActions(onSend = { onSend() })
                        )

                        Spacer(modifier = Modifier.width(6.dp))

                        IconButton(
                            onClick = {
                                if (viewModel != null) {
                                    viewModel.toggleInternalDictation()
                                } else {
                                    onToggleInChatDictation()
                                }
                            },
                            enabled = !isGenerating && !isSending,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (isInChatDictationActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .size(40.dp)
                                .testTag("start_voice_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = if (isInChatDictationActive) "Stop in-chat voice dictation" else "In-chat voice dictation",
                                tint = if (isInChatDictationActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        val canSend = (inputText.text.isNotBlank() || hasBufferedSpeech || hasStagedPreview) && !isGenerating && !isSending
                        IconButton(
                            onClick = onSend,
                            enabled = canSend,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .size(40.dp)
                                .testTag("send_message_button")
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Send, "Send message", tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HandsFreeListeningPill(
    isActive: Boolean,
    state: HandsFreeVoiceState,
    dictateTrigger: String,
    sendTrigger: String,
    heartbeatTrigger: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "handsFreePulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val (bgColor, borderColor, icon, statusTitle, statusSubtext) = when {
        !isActive -> {
            val bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            val border = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
            listOf(bg, border, Icons.Default.MicOff, "Hands-Free Standby", "Tap mic to activate voice triggers")
        }
        state == HandsFreeVoiceState.HEARTBEAT_PING -> {
            val bg = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f)
            val border = MaterialTheme.colorScheme.secondary
            listOf(bg, border, Icons.Default.Bolt, "Heartbeat Ping", "Ping intercepted & confirmation returned")
        }
        state == HandsFreeVoiceState.TRANSCRIBING -> {
            val bg = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
            val border = MaterialTheme.colorScheme.primary
            listOf(bg, border, Icons.Default.Mic, "Transcribing Voice", "Say \"$sendTrigger\" to dispatch message")
        }
        else -> { // LISTENING
            val bg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            val border = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            listOf(
                bg,
                border,
                Icons.Default.Hearing,
                "Listening",
                "Triggers: \"$dictateTrigger\" to type • \"$sendTrigger\" to send • \"$heartbeatTrigger\" to ping"
            )
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("hands_free_listening_pill"),
        shape = RoundedCornerShape(12.dp),
        color = bgColor as Color,
        border = androidx.compose.foundation.BorderStroke(1.dp, borderColor as Color)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
                            else MaterialTheme.colorScheme.outline
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = statusTitle as String,
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        ) {
                            Text(
                                text = if (isActive) "ACTIVE" else "OFF",
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.SemiBold),
                                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                    Text(
                        text = statusSubtext as String,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }

            IconButton(
                onClick = onToggle,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                    .testTag("toggle_hands_free_button")
            ) {
                Icon(
                    imageVector = icon as androidx.compose.ui.graphics.vector.ImageVector,
                    contentDescription = "Toggle Hands-Free Mode",
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    val kb = bytes / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1.0 -> String.format(Locale.getDefault(), "%.1f MB", mb)
        kb >= 1.0 -> String.format(Locale.getDefault(), "%.1f KB", kb)
        else -> "$bytes B"
    }
}
