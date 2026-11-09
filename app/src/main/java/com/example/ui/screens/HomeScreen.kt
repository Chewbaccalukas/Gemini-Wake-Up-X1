package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.filled.Storage
import androidx.compose.ui.draw.scale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.importer.ImportProgressState
import com.example.ui.components.ChatImportProgressBanner
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ChatSession
import com.example.ui.components.VoiceWaveformVisualizer
import com.example.ui.components.WakeWordListeningStatusBanner
import com.example.ui.components.WakeWordSessionBarChartCard
import com.example.ui.components.WeeklyActivityHeatmapCard
import com.example.ui.components.computeWeeklyActivityHeatmap
import com.example.ui.components.getIconForName

import android.os.Build
import android.widget.Toast
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.ui.platform.LocalContext
import com.example.ui.components.ActiveChatTimerHUD
import com.example.ui.components.ChatStopwatchItemToggle
import com.example.ui.components.ScreenClockCard
import com.example.voice.VoiceEngineMode
import com.example.ui.components.BottomBarNetworkStatusIcon
import com.example.ui.components.VoiceOutputSettingsCard
import com.example.ui.components.WebSearchAndHistoryBar
import com.example.util.NetworkConnectionState
import com.example.util.VoiceCommandHistoryEntry
import com.example.util.WebLinkHistoryEntry

@Composable
fun CompactSettingRow(
    title: String,
    description: String? = null,
    icon: ImageVector,
    isChecked: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(
            checked = isChecked,
            onCheckedChange = onToggle,
            modifier = Modifier.scale(0.8f)
        )
    }
}

@Composable
fun CollapsibleSection(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null,
                modifier = Modifier.rotate(if (expanded) 180f else 0f))
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                content()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    chats: List<ChatSession>,
    isListening: Boolean,
    audioRmsDb: Float,
    statusMessage: String,
    lastHeardText: String,
    defaultPersistenceEnabled: Boolean = true,
    preserveCacheAcrossSessions: Boolean = true,
    heartbeatPingOnlyMode: Boolean = true,
    isHandsFreeActive: Boolean = false,
    dictateTrigger: String = "Dictate",
    sendTrigger: String = "Send",
    heartbeatTrigger: String = "Heartbeat",
    activeTimerChatIds: Set<Long> = emptySet(),
    liveTrackedSeconds: Map<Long, Long> = emptyMap(),
    voiceEngineMode: VoiceEngineMode = VoiceEngineMode.GEMINI_ORION,
    isOrionVoicePlaying: Boolean = false,
    onSetVoiceEngineMode: (VoiceEngineMode) -> Unit = {},
    onPlayOrionTestSample: () -> Unit = {},
    onToggleDefaultPersistence: (Boolean) -> Unit = {},
    onTogglePreserveCacheAcrossSessions: (Boolean) -> Unit = {},
    onToggleHeartbeatPingOnlyMode: (Boolean) -> Unit = {},
    onToggleHandsFree: (Boolean) -> Unit = {},
    onToggleListening: () -> Unit,
    onSelectChat: (Long) -> Unit,
    onCreateChat: () -> Unit,
    onEditChat: (ChatSession) -> Unit,
    onDeleteChat: (Long) -> Unit,
    onSetDefaultWakeChat: (Long) -> Unit,
    onOpenWakeSettings: () -> Unit,
    onOpenImportDialog: () -> Unit,
    onOpenCouplerDialog: () -> Unit = {},
    onToggleChatTimer: (Long) -> Unit = {},
    onResetChatTimer: (Long) -> Unit = {},
    importProgressState: ImportProgressState = ImportProgressState(),
    onCancelImport: () -> Unit = {},
    appTheme: com.example.ui.theme.AppTheme = com.example.ui.theme.AppTheme.SYSTEM,
    onSetAppTheme: (com.example.ui.theme.AppTheme) -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onOpenControlSheet: () -> Unit = {},
    onPowerExit: () -> Unit = {},
    networkState: NetworkConnectionState = NetworkConnectionState.ONLINE_WIFI,
    onRefreshNetwork: () -> Unit = {},
    webLinkHistory: List<WebLinkHistoryEntry> = emptyList(),
    onSubmitWebSearchOrUrl: (String) -> Unit = {},
    onOpenWebHistoryEntry: (WebLinkHistoryEntry) -> Unit = {},
    onDeleteWebHistoryEntry: (String) -> Unit = {},
    onClearAllWebHistory: () -> Unit = {},
    voiceCommandHistory: List<VoiceCommandHistoryEntry> = emptyList(),
    onUseVoiceCommandTranscript: (VoiceCommandHistoryEntry) -> Unit = {},
    onDeleteVoiceCommandEntry: (String) -> Unit = {},
    onClearAllVoiceCommands: () -> Unit = {},
    onClearCorruptedHistory: () -> Unit = {},
    onTextInputFocusChanged: (Boolean) -> Unit = {},
    isSleepMode: Boolean = false,
    sleepModeReason: String = "",
    wakeReadyVisualCue: com.example.ui.WakeReadyCueState? = null,
    onEnterSleepMode: () -> Unit = {},
    onExitSleepMode: () -> Unit = {},
    onDismissWakeReadyCue: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToWakeWordStudio: () -> Unit = {},
    onNavigateToDiagnostics: () -> Unit = {},
    userEmail: String? = null,
    userDisplayName: String? = null,
    cloudSyncStatus: String = "Synced with Cloud Firestore",
    onSyncNow: () -> Unit = {},
    onSignOut: () -> Unit = {},
    sessionStatisticsState: com.example.ui.SessionStatisticsUiState? = null,
    onSelectStatisticsDateRange: (com.example.ui.components.StatisticsDateRange) -> Unit = {},
    onQuickShareCsv: () -> Unit = {},
    onCreateNewWakeTargetChat: (title: String, wakePhrase: String) -> Unit = { _, _ -> },
    onOpenBackupExportDialog: () -> Unit = {},
    favoriteChatIds: Set<Long> = emptySet(),
    onToggleFavoriteChat: (Long) -> Unit = {},
    onOpenWakeFavoritesPicker: () -> Unit = {},
    pinnedChatIds: Set<Long> = emptySet(),
    onTogglePinChat: (Long) -> Unit = {},
    chatCategoryMap: Map<Long, String> = emptyMap(),
    chatTagsMap: Map<Long, Set<String>> = emptyMap(),
    availableCategories: List<String> = com.example.util.AppSettingsManager.DEFAULT_CHAT_CATEGORIES,
    onOpenBufferBridgeDialog: () -> Unit = {},
    onOpenSyncCompanionRelay: () -> Unit = {},
    onOpenCloudBackupVault: () -> Unit = {},
    onClearChatThread: (Long) -> Unit = {},
    externalSelectedCategoryFilter: String? = null,
    onSelectCategoryFilter: ((String?) -> Unit)? = null
) {
    val context = LocalContext.current
    var selectedCategoryFilter by rememberSaveable { mutableStateOf(externalSelectedCategoryFilter) }
    androidx.compose.runtime.LaunchedEffect(externalSelectedCategoryFilter) {
        if (onSelectCategoryFilter != null && selectedCategoryFilter != externalSelectedCategoryFilter) {
            selectedCategoryFilter = externalSelectedCategoryFilter
        }
    }
    var selectedTagFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var isSideCategoryBarCollapsed by rememberSaveable { mutableStateOf(true) }
    // Cloud Sync & Summary drawer is collapsed by default behind the top-bar icon
    var isCloudSummaryDrawerExpanded by rememberSaveable { mutableStateOf(false) }
    var isAnalyticsSectionExpanded by rememberSaveable { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showClearThreadPickerDialog by remember { mutableStateOf(false) }

    val filteredChats = remember(chats, selectedCategoryFilter, selectedTagFilter, chatCategoryMap, chatTagsMap, pinnedChatIds) {
        val matched = chats.filter { chat ->
            val matchesCat = com.example.ui.components.matchesCategoryFilter(
                chat = chat,
                categoryFilter = selectedCategoryFilter,
                assignedCategory = chatCategoryMap[chat.id],
                tags = chatTagsMap[chat.id].orEmpty()
            )
            val matchesTag = selectedTagFilter.isNullOrBlank() ||
                chatTagsMap[chat.id]?.any { it.equals(selectedTagFilter, ignoreCase = true) } == true
            matchesCat && matchesTag
        }
        com.example.util.AppSettingsManager.sortChatsWithPinnedFirst(
            chats = matched,
            pinnedIds = pinnedChatIds
        )
    }

    val allKnownTags = remember(chatTagsMap) {
        (com.example.util.AppSettingsManager.DEFAULT_SUGGESTED_TAGS + chatTagsMap.values.flatten())
            .map { it.trim().removePrefix("#").lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    if (showClearThreadPickerDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showClearThreadPickerDialog = false },
            modifier = Modifier.testTag("clear_thread_picker_dialog"),
            title = {
                Text(
                    text = "Select Chat Thread to Clear",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            },
            text = {
                if (chats.isEmpty()) {
                    Text(
                        text = "No chat threads available to clear.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(chats, key = { "clear_pick_${it.id}" }) { chatSession ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        onClearChatThread(chatSession.id)
                                        showClearThreadPickerDialog = false
                                        Toast.makeText(
                                            context,
                                            "Cleared messages in \"${chatSession.title}\"",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    .testTag("clear_thread_option_${chatSession.id}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = chatSession.title,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                                        )
                                        Text(
                                            text = "Wake word: \"${chatSession.wakeWord}\"",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Clear messages in ${chatSession.title}",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showClearThreadPickerDialog = false },
                    modifier = Modifier.testTag("clear_thread_cancel_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(0xFF38BDF8), Color(0xFFA855F7))
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = "Gemini Wake",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1
                                )
                                Text(
                                    text = when {
                                        isSleepMode -> "Sleep mode"
                                        isListening || isHandsFreeActive -> "Hands-Free Active"
                                        else -> "Standby"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if ((isListening || isHandsFreeActive) && !isSleepMode) {
                                        Color(0xFF10B981)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    maxLines = 1
                                )
                            }
                        }
                    },
                    actions = {
                        // Prominent Hands-Free Radar Toggle Pill bound directly to audioRmsDb
                        com.example.ui.components.TopBarHandsFreeRadarPill(
                            isActive = (isHandsFreeActive || isListening) && !isSleepMode,
                            audioRmsDb = audioRmsDb,
                            isSleepMode = isSleepMode,
                            onToggle = {
                                if (isSleepMode) {
                                    onExitSleepMode()
                                } else {
                                    onToggleListening()
                                }
                            },
                            testTagPrefix = "home"
                        )

                        // Cloud Backup & Restore Vault Top-Bar Icon
                        IconButton(
                            onClick = onOpenCloudBackupVault,
                            modifier = Modifier.testTag("home_cloud_summary_drawer_toggle")
                        ) {
                            Box(contentAlignment = Alignment.TopEnd) {
                                Icon(
                                    imageVector = Icons.Default.CloudDone,
                                    contentDescription = "Open Cloud Backup & Restore Vault",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (!userEmail.isNullOrBlank()) Color(0xFF10B981)
                                            else MaterialTheme.colorScheme.primary
                                        )
                                )
                            }
                        }

                        IconButton(
                            onClick = onNavigateToSettings,
                            modifier = Modifier.testTag("home_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Wake Word & Sensitivity Settings",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Top-Bar 3-Dot Overflow Drop-Down Menu
                        Box {
                            IconButton(
                                onClick = { showOverflowMenu = true },
                                modifier = Modifier.testTag("home_overflow_menu_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More Actions Menu",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Diagnostic Log") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.BugReport,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    modifier = Modifier.testTag("home_menu_diagnostic_log"),
                                    onClick = {
                                        showOverflowMenu = false
                                        onNavigateToDiagnostics()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Two-Tier Buffer Settings") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Storage,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    modifier = Modifier.testTag("home_menu_buffer_settings"),
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenBufferBridgeDialog()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Sync Companion Relay") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Sync,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    modifier = Modifier.testTag("home_menu_sync_companion_relay"),
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenSyncCompanionRelay()
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Clear Thread",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    modifier = Modifier.testTag("home_menu_clear_thread"),
                                    onClick = {
                                        showOverflowMenu = false
                                        showClearThreadPickerDialog = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Cloud Backup & Restore Vault") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.CloudDone,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    },
                                    modifier = Modifier.testTag("home_menu_cloud_backup_vault"),
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenCloudBackupVault()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Wake Radar & Analytics Drawer") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Hearing,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        isCloudSummaryDrawerExpanded = !isCloudSummaryDrawerExpanded
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Custom Wake Word Studio") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Mic,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onNavigateToWakeWordStudio()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Import Chat or Context") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Download,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenImportDialog()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Gemini Coupler & Piggyback") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.SwapHoriz,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenCouplerDialog()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Conversation & Voice History") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.History,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onNavigateToHistory()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Gemini Control Sheet") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Tune,
                                            contentDescription = null
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenControlSheet()
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = "Power Off / Exit App",
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.PowerSettingsNew,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        showOverflowMenu = false
                                        onPowerExit()
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )

                // Sticky Horizontal Category Filter Bar directly below TopAppBar
                com.example.ui.components.StickyHorizontalCategoryFilterBar(
                    selectedCategory = selectedCategoryFilter,
                    onSelectCategory = { selectedCategoryFilter = it },
                    chats = chats,
                    chatCategoryMap = chatCategoryMap,
                    chatTagsMap = chatTagsMap,
                    availableCategories = availableCategories
                )
            }
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FloatingActionButton(
                    onClick = onQuickShareCsv,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.testTag("fab_share_csv_statistics")
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share Session Statistics CSV via FileProvider"
                    )
                }
                FloatingActionButton(
                    onClick = onCreateChat,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("create_chat_fab")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "New Chat")
                }
            }
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .imePadding()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BottomBarNetworkStatusIcon(
                        networkState = networkState,
                        onRefreshNetwork = onRefreshNetwork
                    )
                    Text(
                        text = when {
                            isSleepMode -> "Sleep Mode (Paused)"
                            isListening || isHandsFreeActive -> "Wake Radar Active (${audioRmsDb.toInt()} dB)"
                            else -> "Wake Radar Standby"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                ChatImportProgressBanner(
                    progressState = importProgressState,
                    onCancelImport = onCancelImport
                )

                SleepModeVisualBanner(
                    isSleepMode = isSleepMode,
                    sleepReason = sleepModeReason,
                    onWakeUpTap = onExitSleepMode
                )

                WakeReadyVisualCueBanner(
                    cueState = wakeReadyVisualCue,
                    onOpenChat = onSelectChat,
                    onDismiss = onDismissWakeReadyCue
                )

                // Expandable Top Drawer for Cloud Sync, Wake Summary, Radar & Analytics (collapsed by default)
                AnimatedVisibility(
                    visible = isCloudSummaryDrawerExpanded,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 4.dp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("home_cloud_summary_drawer")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .testTag("cloud_sync_account_bar"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDone,
                                        contentDescription = "Cloud Firestore Sync",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = userDisplayName?.takeIf { it.isNotBlank() }
                                                ?: userEmail?.takeIf { it.isNotBlank() }
                                                ?: "Cloud Firestore Sync",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = cloudSyncStatus,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = onSyncNow,
                                        modifier = Modifier.testTag("cloud_sync_now_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Sync,
                                            contentDescription = "Sync Now with Cloud Firestore",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    if (!userEmail.isNullOrBlank() || !userDisplayName.isNullOrBlank()) {
                                        IconButton(
                                            onClick = onSignOut,
                                            modifier = Modifier.testTag("google_sign_out_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Logout,
                                                contentDescription = "Sign Out of Google Account",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            WakeWordListeningStatusBanner(
                                isListening = isListening && !isSleepMode,
                                audioRmsDb = audioRmsDb,
                                activeWakePhrase = chats.firstOrNull { it.isDefaultWakeChat }?.wakeWord ?: "Hey Gemini",
                                lastHeardText = lastHeardText,
                                totalVoiceCommands = chats.sumOf { it.totalVoiceCommands }
                            )

                            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                                WebSearchAndHistoryBar(
                                    historyEntries = webLinkHistory,
                                    onSubmitSearchOrUrl = onSubmitWebSearchOrUrl,
                                    onOpenHistoryEntry = onOpenWebHistoryEntry,
                                    onDeleteHistoryEntry = onDeleteWebHistoryEntry,
                                    onClearAllHistory = onClearAllWebHistory,
                                    voiceCommandEntries = voiceCommandHistory,
                                    onUseVoiceCommandTranscript = onUseVoiceCommandTranscript,
                                    onDeleteVoiceCommandEntry = onDeleteVoiceCommandEntry,
                                    onClearAllVoiceCommands = onClearAllVoiceCommands,
                                    onClearCorruptedHistory = onClearCorruptedHistory,
                                    onTextInputFocusChanged = onTextInputFocusChanged
                                )
                            }

                            Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
                                WakeWordRadarCard(
                                    isListening = (isHandsFreeActive || isListening) && !isSleepMode,
                                    audioRmsDb = audioRmsDb,
                                    statusMessage = statusMessage,
                                    lastHeardText = lastHeardText,
                                    onToggleListening = {
                                        if (isSleepMode) onExitSleepMode() else onToggleListening()
                                    },
                                    onOpenWakeSettings = onOpenWakeSettings
                                )
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                androidx.compose.material3.FilledTonalButton(
                                    onClick = onNavigateToWakeWordStudio,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("open_wake_word_studio_button"),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Wake Studio", style = MaterialTheme.typography.labelMedium)
                                }

                                androidx.compose.material3.OutlinedButton(
                                    onClick = { isAnalyticsSectionExpanded = !isAnalyticsSectionExpanded },
                                    modifier = Modifier.testTag("home_toggle_analytics_charts_button"),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = if (isAnalyticsSectionExpanded) "Hide Charts" else "Show Charts",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }

                                androidx.compose.material3.OutlinedButton(
                                    onClick = { if (isSleepMode) onExitSleepMode() else onEnterSleepMode() },
                                    modifier = Modifier.testTag("home_sleep_mode_toggle_button"),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isSleepMode) Icons.Default.Mic else Icons.Default.MicOff,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isSleepMode) "Wake Up" else "Sleep",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }
                    }
                }

                // Maximized Conversation Threads Viewport
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .imePadding(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = if (isSideCategoryBarCollapsed) 24.dp else 112.dp,
                        top = 10.dp,
                        bottom = 96.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (isCloudSummaryDrawerExpanded && isAnalyticsSectionExpanded) {
                        item(key = "wake_session_bar_chart_dashboard") {
                            WakeWordSessionBarChartCard(
                                chats = chats,
                                voiceCommands = voiceCommandHistory,
                                statisticsState = sessionStatisticsState,
                                onSelectDateRange = onSelectStatisticsDateRange,
                                onSelectDefaultWakeChat = onSetDefaultWakeChat,
                                onCreateNewWakeTargetChat = onCreateNewWakeTargetChat,
                                onOpenExportDialog = onOpenBackupExportDialog,
                                onQuickShareCsv = onQuickShareCsv
                            )
                        }

                        item(key = "weekly_activity_heatmap_dashboard") {
                            val heatmapCells = remember(sessionStatisticsState, chats, voiceCommandHistory) {
                                sessionStatisticsState?.weeklyHeatmapCells?.takeIf { it.isNotEmpty() }
                                    ?: computeWeeklyActivityHeatmap(
                                        chats = chats,
                                        messages = emptyList(),
                                        voiceCommands = voiceCommandHistory
                                    )
                            }
                            val peakLabel = sessionStatisticsState?.peakHeatmapSlotLabel ?: "Peak Activity Slot"
                            WeeklyActivityHeatmapCard(
                                heatmapCells = heatmapCells,
                                peakSlotSummary = peakLabel,
                                onShareCsvClick = onQuickShareCsv
                            )
                        }
                    }

                    // Compact header with tag filter chips + active count
                    item(key = "chat_category_and_tag_filter_bar") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (selectedCategoryFilter.isNullOrBlank()) {
                                        "Your Chosen Chats"
                                    } else {
                                        "$selectedCategoryFilter Chats"
                                    },
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    val pinnedCountInView = filteredChats.count { it.isPinned || pinnedChatIds.contains(it.id) }
                                    Text(
                                        text = if (pinnedCountInView > 0) {
                                            "📌 $pinnedCountInView pinned • ${filteredChats.size}/${chats.size}"
                                        } else {
                                            "${filteredChats.size} of ${chats.size} shown"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (pinnedCountInView > 0) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        }
                                    )
                                    if (favoriteChatIds.isNotEmpty()) {
                                        TextButton(
                                            onClick = onOpenWakeFavoritesPicker,
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                            modifier = Modifier.testTag("home_open_favorites_picker_button")
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Star,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text(
                                                text = "Fav (${favoriteChatIds.size})",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                            )
                                        }
                                    }
                                }
                            }

                            if (allKnownTags.isNotEmpty()) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("home_tag_filter_row")
                                ) {
                                    items(allKnownTags, key = { "tag_$it" }) { tag ->
                                        val isTagSelected = selectedTagFilter?.equals(tag, ignoreCase = true) == true
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isTagSelected) {
                                                MaterialTheme.colorScheme.secondaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                                            },
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    selectedTagFilter = if (isTagSelected) null else tag
                                                }
                                                .testTag("home_tag_chip_$tag")
                                        ) {
                                            Text(
                                                text = "#$tag",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = if (isTagSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isTagSelected) {
                                                    MaterialTheme.colorScheme.onSecondaryContainer
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (chats.isEmpty()) {
                        item {
                            EmptyChatsPlaceholder(
                                onCreateChat = onCreateChat,
                                onOpenImportDialog = onOpenImportDialog
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = filteredChats,
                            key = { _, chat -> "chat_${chat.id}_${chat.isPinned || pinnedChatIds.contains(chat.id)}" }
                        ) { _, chat ->
                            val isRunning = activeTimerChatIds.contains(chat.id)
                            val seconds = liveTrackedSeconds[chat.id] ?: chat.totalTrackedSeconds
                            val isPinned = chat.isPinned || pinnedChatIds.contains(chat.id)
                            ChatSessionCard(
                                chat = chat,
                                trackedSeconds = seconds,
                                isTimerRunning = isRunning,
                                isPinned = isPinned,
                                category = chatCategoryMap[chat.id],
                                tags = chatTagsMap[chat.id].orEmpty(),
                                onClick = { onSelectChat(chat.id) },
                                onTogglePin = { onTogglePinChat(chat.id) },
                                onEdit = { onEditChat(chat) },
                                onDelete = { onDeleteChat(chat.id) },
                                onSetDefault = { onSetDefaultWakeChat(chat.id) },
                                onToggleTimer = { onToggleChatTimer(chat.id) },
                                onResetTimer = { onResetChatTimer(chat.id) }
                            )
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(72.dp))
                    }
                }
            }

            // Right-edge Collapsible Category Filter Chip Bar ('All', 'Work', 'Personal', 'Projects')
            com.example.ui.components.CollapsibleSideCategoryFilterBar(
                selectedCategory = selectedCategoryFilter,
                onSelectCategory = {
                    selectedCategoryFilter = it
                    onSelectCategoryFilter?.invoke(it)
                },
                isCollapsed = isSideCategoryBarCollapsed,
                onToggleCollapsed = { isSideCategoryBarCollapsed = !isSideCategoryBarCollapsed },
                chats = chats,
                chatCategoryMap = chatCategoryMap,
                isInChatInterface = false,
                testTagPrefix = "home",
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 2.dp)
            )
        }
    }
}




@Composable
fun WakeWordRadarCard(
    isListening: Boolean,
    audioRmsDb: Float,
    statusMessage: String,
    lastHeardText: String,
    onToggleListening: () -> Unit,
    onOpenWakeSettings: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("wake_word_radar_card"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        border = if (isListening) {
            androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
        } else null,
        onClick = onOpenWakeSettings
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. Status Section
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(0.8f)) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (isListening) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = if (isListening) "RADAR ACTIVE" else "RADAR PAUSED",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                        color = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                    if (lastHeardText.isNotBlank() && isListening) {
                        Text(
                            text = "Heard: \"$lastHeardText\"",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = "Say wake phrase to jump to chat",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1
                        )
                    }
                }
            }

            // 2. Waveform Visualizer Section
            VoiceWaveformVisualizer(
                isListening = isListening,
                audioRmsDb = audioRmsDb,
                modifier = Modifier.weight(1.2f).padding(horizontal = 8.dp),
                height = 24.dp,
                barCount = 18
            )

            // 3. Toggle Button Section (with 400ms click debounce)
            var lastHudClickAtMs by remember { androidx.compose.runtime.mutableLongStateOf(0L) }
            val currentOnToggleListening by androidx.compose.runtime.rememberUpdatedState(onToggleListening)
            IconButton(
                onClick = {
                    val now = System.currentTimeMillis()
                    if (lastHudClickAtMs > 0L && (now - lastHudClickAtMs) < com.example.ui.components.MIN_RADAR_PILL_CLICK_DEBOUNCE_MS) {
                        return@IconButton
                    }
                    lastHudClickAtMs = now
                    currentOnToggleListening()
                },
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (isListening) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.primary
                    )
                    .testTag("toggle_listening_hud_button")
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.MicOff else Icons.Default.Mic,
                    contentDescription = null,
                    tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun ChatSessionCard(
    chat: ChatSession,
    trackedSeconds: Long = 0L,
    isTimerRunning: Boolean = false,
    isPinned: Boolean = false,
    category: String? = null,
    tags: Set<String> = emptySet(),
    onClick: () -> Unit,
    onTogglePin: () -> Unit = {},
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSetDefault: () -> Unit,
    onToggleTimer: () -> Unit = {},
    onResetTimer: () -> Unit = {}
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val accentColor = remember(chat.themeColorHex) {
        try {
            Color(android.graphics.Color.parseColor(chat.themeColorHex.trim()))
        } catch (_: Throwable) {
            Color(0xFF38BDF8)
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chat_card_${chat.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isPinned) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = when {
            isPinned -> androidx.compose.foundation.BorderStroke(1.5.dp, accentColor.copy(alpha = 0.8f))
            chat.isDefaultWakeChat -> androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
            else -> androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        },
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getIconForName(chat.iconName),
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = chat.title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        if (isPinned) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = accentColor.copy(alpha = 0.18f),
                                modifier = Modifier.testTag("chat_pinned_badge_${chat.id}")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PushPin,
                                        contentDescription = "Pinned to Top",
                                        tint = accentColor,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text(
                                        text = "Pinned",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp
                                        ),
                                        color = accentColor
                                    )
                                }
                            }
                        }
                        if (chat.isDefaultWakeChat) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = "Default Wake Target",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "\"${chat.wakeWord}\"",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(
                                    if (chat.isPersistent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                                    else MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (chat.isPersistent) "💾 Disk" else "⚡ RAM",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                color = if (chat.isPersistent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
                            )
                        }

                        if (!category.isNullOrBlank()) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "📁 $category",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }

                        if (chat.isDefaultWakeChat) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Default Target",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    if (tags.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            tags.take(4).forEach { tag ->
                                Text(
                                    text = "#$tag",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                                )
                            }
                        }
                    }

                    if (chat.greetingMessage.isNotBlank()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Greeting: \"${chat.greetingMessage}\"",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("home_chat_greeting_preview_${chat.id}")
                        )
                    }
                }

                IconButton(
                    onClick = onTogglePin,
                    modifier = Modifier.testTag("chat_pin_button_${chat.id}")
                ) {
                    Icon(
                        imageVector = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                        contentDescription = if (isPinned) "Unpin Chat from Top" else "Pin Chat to Top",
                        tint = if (isPinned) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.testTag("chat_card_menu_${chat.id}")
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Open Chat") },
                            leadingIcon = { Icon(Icons.Default.ArrowForward, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(if (isPinned) "Unpin from Top" else "Pin to Top of List") },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                                    contentDescription = null,
                                    tint = if (isPinned) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            modifier = Modifier.testTag("chat_menu_toggle_pin_${chat.id}"),
                            onClick = {
                                menuExpanded = false
                                onTogglePin()
                            }
                        )
                        if (!chat.isDefaultWakeChat) {
                            DropdownMenuItem(
                                text = { Text("Set as Default Wake Target") },
                                leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    onSetDefault()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Edit Chat, Greeting & Wake Word") },
                            leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Delete Chat", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuExpanded = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Stopwatch / Timer UI graphic toggle to track time on this listed chat
            ChatStopwatchItemToggle(
                chatId = chat.id,
                seconds = trackedSeconds,
                isRunning = isTimerRunning,
                accentColor = accentColor,
                onToggle = onToggleTimer,
                onReset = onResetTimer,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun ImportGeminiBannerCard(onOpenImportDialog: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("import_gemini_banner_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Load Preexisting Gemini Chat",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Import via share link or transcript & assign a voice wake word.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onOpenImportDialog,
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.testTag("banner_import_button")
            ) {
                Text(
                    text = "Import",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

@Composable
fun EmptyChatsPlaceholder(
    onCreateChat: () -> Unit,
    onOpenImportDialog: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Hearing,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = "No chats yet",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "Create a new Gemini chat with a custom wake word, or import a preexisting chat from standard Gemini.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onCreateChat,
                    modifier = Modifier.testTag("empty_create_chat_button")
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("New Chat")
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = onOpenImportDialog,
                    modifier = Modifier.testTag("empty_import_chat_button")
                ) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Import Chat")
                }
            }
        }
    }
}



@Composable
fun DefaultPersistenceSettingsCard(
    defaultPersistenceEnabled: Boolean,
    onToggleDefaultPersistence: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("default_persistence_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Default Storage Persistence",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = if (defaultPersistenceEnabled) "New chats default to SQLite disk persistence" else "New chats default to ephemeral RAM buffers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            androidx.compose.material3.Switch(
                checked = defaultPersistenceEnabled,
                onCheckedChange = onToggleDefaultPersistence,
                modifier = Modifier.testTag("global_persistence_switch")
            )
        }
    }
}

@Composable
fun CachePreservationSettingsCard(
    preserveCacheEnabled: Boolean,
    onTogglePreserveCache: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("preserve_cache_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Save,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Preserve Cache Across Sessions",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = if (preserveCacheEnabled)
                            "Cleanly closes sockets while preserving staged disk buffers and message history"
                        else
                            "Session switches execute nuclear wipe of local bridge caches",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            androidx.compose.material3.Switch(
                checked = preserveCacheEnabled,
                onCheckedChange = onTogglePreserveCache,
                modifier = Modifier.testTag("preserve_cache_switch")
            )
        }
    }
}

@Composable
fun HeartbeatModeSettingsCard(
    heartbeatEnabled: Boolean,
    onToggleHeartbeat: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("heartbeat_settings_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Heartbeat Ping Only (Background)",
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = if (heartbeatEnabled)
                            "Tears down heavy HTTP bridge when minimized, maintaining low-power AlarmManager heartbeat & alarm alerts"
                        else
                            "Keeps continuous HTTP bridge running while minimized",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            androidx.compose.material3.Switch(
                checked = heartbeatEnabled,
                onCheckedChange = onToggleHeartbeat,
                modifier = Modifier.testTag("heartbeat_ping_switch")
            )
        }
    }
}

@Composable
fun HandsFreeVoiceSettingsCard(
    isHandsFreeActive: Boolean,
    dictateTrigger: String,
    sendTrigger: String,
    heartbeatTrigger: String,
    onToggleHandsFree: (Boolean) -> Unit,
    onOpenWakeSettings: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("hands_free_settings_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = if (isHandsFreeActive) {
            androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f))
        } else null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Hearing,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Hands-Free Voice Dictation",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = if (isHandsFreeActive)
                                "Real-time voice stream typing directly into chat input"
                            else
                                "Paused - toggle to activate continuous voice triggers",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                androidx.compose.material3.Switch(
                    checked = isHandsFreeActive,
                    onCheckedChange = onToggleHandsFree,
                    modifier = Modifier.testTag("hands_free_global_switch")
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                onClick = onOpenWakeSettings
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Configured Trigger Words:",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "🎤 \"$dictateTrigger\"",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "🚀 \"$sendTrigger\"",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "⚡ \"$heartbeatTrigger\"",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = "Edit Triggers",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
