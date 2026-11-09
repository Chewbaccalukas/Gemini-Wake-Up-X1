package com.example.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.game.AdventureDrawerTab
import com.example.game.AdventureGameState
import com.example.game.AdventureInlineEvent
import com.example.game.AdventureQuest
import com.example.game.DifficultyLevel
import com.example.game.InventoryItem
import com.example.game.QuestFilterMode
import com.example.game.RarityTier
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Resolves an RPG item icon name to a Material [ImageVector].
 */
fun resolveAdventureItemIcon(iconName: String): ImageVector {
    return when (iconName.trim().lowercase(Locale.US)) {
        "shield", "armor" -> Icons.Default.Shield
        "explore", "compass", "relic" -> Icons.Default.Explore
        "bolt", "potion", "consumable", "elixir" -> Icons.Default.Bolt
        "menubook", "book", "tome", "scroll" -> Icons.Default.MenuBook
        "vpnkey", "key", "keycard" -> Icons.Default.VpnKey
        "public", "gadget", "beacon" -> Icons.Default.Public
        "diamond", "gem", "artifact", "soulstone" -> Icons.Default.Diamond
        "star" -> Icons.Default.Star
        else -> Icons.Default.AutoAwesome
    }
}

/**
 * Parses a hex string like `#38BDF8` into a Compose [Color] with a safe fallback.
 */
fun parseHexColorSafe(hex: String, fallback: Color = Color(0xFF38BDF8)): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex.trim()))
    } catch (_: Throwable) {
        fallback
    }
}

/**
 * Swipable right-side Adventure Engine drawer sheet (`GameDrawerSheet.kt`) containing:
 * - Top Game Controls Bar (Manual Save, Difficulty Selector, Advanced Lore Settings, Adventure Mode toggle)
 * - Character / Visual Portrait card with Level & XP progress bar
 * - 3-Column Inventory Grid with rarity borders & animated floating tooltip card ("Use/Inspect")
 * - Interactive Expandable Quest Cards with milestone progress bars
 * - Document Lore Context Attachment (`.txt`, `.md`, `.pdf`) & Global Preset Save/Load controls
 */
/**
 * Renders a multi-layered glowing border around an inventory item based on its [RarityTier]:
 * - Common (`#94A3B8` Grey) & Rare (`#38BDF8` Blue): Static outer glow halo + gradient border.
 * - Epic (`#C084FC` Purple) & Legendary (`#F59E0B` Orange): Animated pulsing outer aura + dual-tone gradient border.
 */
fun Modifier.rarityGlowBorder(
    rarityTier: RarityTier,
    isSelected: Boolean,
    pulseFraction: Float,
    cornerRadius: Dp = 14.dp
): Modifier {
    val primaryColor = parseHexColorSafe(rarityTier.primaryColorHex)
    val secondaryColor = parseHexColorSafe(rarityTier.secondaryGlowHex, primaryColor)
    val effectivePulse = if (rarityTier.hasPulsingAura) pulseFraction.coerceIn(0f, 1f) else 0f
    val glowAlpha = rarityTier.baseGlowAlpha +
        (rarityTier.peakGlowAlpha - rarityTier.baseGlowAlpha) * effectivePulse +
        (if (isSelected) 0.16f else 0f)
    val spreadFactor = 1f + (0.35f * effectivePulse) + (if (isSelected) 0.25f else 0f)

    return this
        .drawBehind {
            val cornerPx = cornerRadius.toPx()
            val outerSpreadPx = rarityTier.glowSpreadDp.dp.toPx() * spreadFactor
            val midSpreadPx = outerSpreadPx * 0.55f

            // Outer soft halo ring
            drawRoundRect(
                color = primaryColor.copy(alpha = (glowAlpha * 0.42f).coerceIn(0f, 0.85f)),
                topLeft = Offset(-outerSpreadPx * 0.35f, -outerSpreadPx * 0.35f),
                size = Size(size.width + outerSpreadPx * 0.7f, size.height + outerSpreadPx * 0.7f),
                cornerRadius = CornerRadius(cornerPx + outerSpreadPx * 0.35f, cornerPx + outerSpreadPx * 0.35f),
                style = Stroke(width = outerSpreadPx)
            )

            // Inner high-intensity neon rim glow
            drawRoundRect(
                color = secondaryColor.copy(alpha = (glowAlpha * 0.72f).coerceIn(0f, 0.95f)),
                topLeft = Offset.Zero,
                size = size,
                cornerRadius = CornerRadius(cornerPx, cornerPx),
                style = Stroke(width = midSpreadPx.coerceAtLeast(2f))
            )
        }
        .border(
            width = (if (isSelected) rarityTier.borderWidthDp + 0.7f else rarityTier.borderWidthDp).dp,
            brush = Brush.linearGradient(
                colors = if (rarityTier.hasPulsingAura) {
                    listOf(primaryColor, secondaryColor, primaryColor)
                } else {
                    listOf(primaryColor, secondaryColor.copy(alpha = 0.85f))
                }
            ),
            shape = RoundedCornerShape(cornerRadius)
        )
}

@Composable
fun GameDrawerSheet(
    adventureState: AdventureGameState,
    accentColor: Color,
    onSelectTab: (AdventureDrawerTab) -> Unit,
    onToggleAdventureMode: (Boolean) -> Unit,
    onManualSave: () -> Unit,
    onSelectDifficulty: (DifficultyLevel) -> Unit,
    onToggleAutoSaveOnQuest: (Boolean) -> Unit,
    onToggleNotifications: (Boolean) -> Unit,
    onUpdateLoreContext: (String) -> Unit,
    onAttachLoreDocument: (Uri, String?) -> Unit,
    onUpdateCharacterProfile: (String, String) -> Unit,
    onUpdateCharacterPortrait: (Uri) -> Unit,
    onUseOrInspectItem: (InventoryItem) -> Unit,
    onRollProceduralLoot: () -> Unit,
    onToggleQuestCompleted: (String) -> Unit,
    onAddCustomQuest: (String, String, Int) -> Unit,
    onSaveGlobalPreset: () -> Unit,
    onLoadGlobalPreset: () -> Unit,
    onDismissStatusBanner: () -> Unit,
    onCloseDrawer: () -> Unit,
    onSelectQuestFilter: (QuestFilterMode) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTooltipItem by remember { mutableStateOf<InventoryItem?>(null) }
    var showAddQuestFields by rememberSaveable { mutableStateOf(false) }
    var newQuestTitle by rememberSaveable { mutableStateOf("") }
    var newQuestDesc by rememberSaveable { mutableStateOf("") }
    var newQuestXp by rememberSaveable { mutableStateOf("200") }

    // Photo Picker for Character Portrait (PNG, JPEG, WEBP)
    val portraitPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            onUpdateCharacterPortrait(uri)
        }
    }

    // OpenDocument launcher for Lore Context documents (.txt, .md, .pdf)
    val loreDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val mimeType = context.contentResolver.getType(uri) ?: "text/plain"
            onAttachLoreDocument(uri, mimeType)
        }
    }

    ModalDrawerSheet(
        drawerContainerColor = Color(0xFF0F172A),
        drawerContentColor = Color(0xFFF8FAFC),
        drawerShape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp),
        windowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier
            .fillMaxHeight()
            .widthIn(min = 310.dp, max = 372.dp)
            .testTag("adventure_game_drawer_sheet")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            // 1. Header & Top-Bar Game Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(accentColor, Color(0xFFC084FC))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Adventure Engine",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Infinite Adventure Engine",
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val unsavedMins = adventureState.unsavedPlaySeconds / 60L
                        val saveSubtitle = if (adventureState.lastSavedAt > 0L) {
                            val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                            "Saved ${fmt.format(Date(adventureState.lastSavedAt))} • ${unsavedMins}m unsaved"
                        } else {
                            "Per-Chat RPG Campaign • ${unsavedMins}m session"
                        }
                        Text(
                            text = saveSubtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = adventureState.isAdventureModeEnabled,
                        onCheckedChange = onToggleAdventureMode,
                        modifier = Modifier.testTag("adventure_mode_master_switch")
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = onCloseDrawer,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("adventure_close_drawer_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Adventure Drawer",
                            tint = Color(0xFFCBD5E1)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Top Game Control Action Bar: Manual Save, Difficulty Selector, Advanced Lore Settings
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF1E293B),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = onManualSave,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor,
                                contentColor = Color(0xFF0F172A)
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(40.dp)
                                .testTag("adventure_manual_save_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Save,
                                contentDescription = "Manual Save",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Manual Save",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                            )
                        }

                        FilledTonalButton(
                            onClick = { onSelectTab(AdventureDrawerTab.LORE) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier
                                .height(40.dp)
                                .testTag("adventure_lore_settings_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Advanced Lore Settings",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Lore Setup",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Difficulty Selector Row (Story, Balanced, Hardcore)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        DifficultyLevel.entries.forEach { diff ->
                            val isSelected = adventureState.settings.difficulty == diff
                            val diffColor = parseHexColorSafe(diff.badgeColorHex, accentColor)
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) diffColor.copy(alpha = 0.22f) else Color(0xFF0F172A),
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) diffColor else Color(0xFF334155)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { onSelectDifficulty(diff) }
                                    .testTag("adventure_difficulty_${diff.name.lowercase(Locale.US)}")
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp)
                                ) {
                                    Text(
                                        text = diff.displayName,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                                        ),
                                        color = if (isSelected) diffColor else Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Optional Status / Event Feedback Toast Banner inside Drawer
            AnimatedVisibility(
                visible = !adventureState.statusBannerMessage.isNullOrBlank(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF10B981).copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.5f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag("adventure_status_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = adventureState.statusBannerMessage.orEmpty(),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color(0xFFA7F3D0),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismissStatusBanner,
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss status banner",
                                tint = Color(0xFFA7F3D0),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 2. Compact Character Portrait & Level Summary Card
            CharacterSummaryBannerCard(
                adventureState = adventureState,
                accentColor = accentColor,
                onPickPortrait = {
                    portraitPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onOpenCharacterTab = { onSelectTab(AdventureDrawerTab.CHARACTER) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 3. Segmented Tab Bar (Inventory, Quests, Character, Lore)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                AdventureDrawerTab.entries.forEach { tab ->
                    val isSelected = adventureState.activeDrawerTab == tab
                    val tabTag = "adventure_tab_${tab.name.lowercase(Locale.US)}"
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) accentColor.copy(alpha = 0.22f) else Color(0xFF1E293B),
                        border = BorderStroke(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) accentColor else Color(0xFF334155)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onSelectTab(tab) }
                            .testTag(tabTag)
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                        ) {
                            Text(
                                text = when (tab) {
                                    AdventureDrawerTab.INVENTORY -> "Bag (${adventureState.inventory.size})"
                                    AdventureDrawerTab.QUESTS -> "Quests (${adventureState.activeQuestCount})"
                                    AdventureDrawerTab.CHARACTER -> "Hero"
                                    AdventureDrawerTab.LORE -> "Lore"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 11.sp
                                ),
                                color = if (isSelected) accentColor else Color(0xFFCBD5E1),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 4. Active Tab Content Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (adventureState.activeDrawerTab) {
                    AdventureDrawerTab.INVENTORY -> {
                        InventoryGridSection(
                            inventory = adventureState.inventory,
                            selectedItem = selectedTooltipItem,
                            accentColor = accentColor,
                            onSelectItem = { clicked ->
                                selectedTooltipItem = if (selectedTooltipItem?.id == clicked.id) null else clicked
                            },
                            onDismissTooltip = { selectedTooltipItem = null },
                            onUseOrInspectItem = { item ->
                                onUseOrInspectItem(item)
                                selectedTooltipItem = null
                            },
                            onRollProceduralLoot = onRollProceduralLoot
                        )
                    }

                    AdventureDrawerTab.QUESTS -> {
                        QuestsListSection(
                            adventureState = adventureState,
                            accentColor = accentColor,
                            showAddQuestFields = showAddQuestFields,
                            newQuestTitle = newQuestTitle,
                            newQuestDesc = newQuestDesc,
                            newQuestXp = newQuestXp,
                            onSelectQuestFilter = onSelectQuestFilter,
                            onToggleShowAddQuest = { showAddQuestFields = !showAddQuestFields },
                            onNewQuestTitleChange = { newQuestTitle = it },
                            onNewQuestDescChange = { newQuestDesc = it },
                            onNewQuestXpChange = { newQuestXp = it },
                            onConfirmAddQuest = {
                                if (newQuestTitle.isNotBlank()) {
                                    val xpVal = newQuestXp.filter { it.isDigit() }.toIntOrNull() ?: 200
                                    onAddCustomQuest(
                                        newQuestTitle.trim(),
                                        newQuestDesc.trim().ifBlank { "Complete this objective in your adventure." },
                                        xpVal
                                    )
                                    newQuestTitle = ""
                                    newQuestDesc = ""
                                    showAddQuestFields = false
                                }
                            },
                            onToggleQuestCompleted = onToggleQuestCompleted
                        )
                    }

                    AdventureDrawerTab.CHARACTER -> {
                        CharacterPortraitAndStatsSection(
                            adventureState = adventureState,
                            accentColor = accentColor,
                            onPickPortrait = {
                                portraitPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onUpdateCharacterProfile = onUpdateCharacterProfile
                        )
                    }

                    AdventureDrawerTab.LORE -> {
                        LoreAndAdvancedSettingsSection(
                            adventureState = adventureState,
                            accentColor = accentColor,
                            onToggleAutoSaveOnQuest = onToggleAutoSaveOnQuest,
                            onToggleNotifications = onToggleNotifications,
                            onUpdateLoreContext = onUpdateLoreContext,
                            onLaunchLoreFilePicker = {
                                loreDocumentLauncher.launch(
                                    arrayOf(
                                        "text/plain",
                                        "text/markdown",
                                        "application/pdf",
                                        "application/json",
                                        "*/*"
                                    )
                                )
                            },
                            onSaveGlobalPreset = onSaveGlobalPreset,
                            onLoadGlobalPreset = onLoadGlobalPreset
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CharacterSummaryBannerCard(
    adventureState: AdventureGameState,
    accentColor: Color,
    onPickPortrait: () -> Unit,
    onOpenCharacterTab: () -> Unit
) {
    val context = LocalContext.current
    val portraitBitmap = remember(adventureState.characterPortraitUri) {
        try {
            adventureState.characterPortraitUri?.takeIf { it.isNotBlank() }?.let { uriStr ->
                val uri = Uri.parse(uriStr)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    val animatedXpProgress by animateFloatAsState(
        targetValue = adventureState.levelProgressFraction,
        animationSpec = spring(),
        label = "xp_progress"
    )

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1E293B),
        border = BorderStroke(1.dp, Color(0xFF334155)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenCharacterTab() }
            .testTag("adventure_character_summary_card")
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Character Portrait Slot (supports PNG, JPEG, WEBP)
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF1E293B), accentColor.copy(alpha = 0.45f))
                        )
                    )
                    .border(1.5.dp, accentColor, RoundedCornerShape(12.dp))
                    .clickable { onPickPortrait() }
                    .testTag("adventure_character_portrait_slot"),
                contentAlignment = Alignment.Center
            ) {
                if (portraitBitmap != null) {
                    Image(
                        bitmap = portraitBitmap,
                        contentDescription = "Character Portrait",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Upload Character Portrait",
                        tint = accentColor,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${adventureState.characterName} • ${adventureState.characterClass}",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = accentColor.copy(alpha = 0.2f),
                        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.6f))
                    ) {
                        Text(
                            text = "LVL ${adventureState.level}",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                            color = accentColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                LinearProgressIndicator(
                    progress = { animatedXpProgress },
                    color = accentColor,
                    trackColor = Color(0xFF0F172A),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${adventureState.totalXp} XP Total",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = "${adventureState.xpIntoCurrentLevel}/${AdventureGameState.XP_PER_LEVEL} to Next Lvl",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        }
    }
}

@Composable
private fun InventoryGridSection(
    inventory: List<InventoryItem>,
    selectedItem: InventoryItem?,
    accentColor: Color,
    onSelectItem: (InventoryItem) -> Unit,
    onDismissTooltip: () -> Unit,
    onUseOrInspectItem: (InventoryItem) -> Unit,
    onRollProceduralLoot: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "rarity_aura_pulse")
    val pulseFraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rarity_aura_fraction"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "3-Column Tactical Inventory",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE2E8F0)
                )
            )
            OutlinedButton(
                onClick = onRollProceduralLoot,
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.7f)),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(34.dp)
                    .testTag("adventure_roll_loot_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Casino,
                    contentDescription = "Roll Procedural Loot",
                    tint = accentColor,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Roll Loot",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Visual Rarity Indicator Legend Bar (Common: Grey, Rare: Blue, Epic: Purple, Legendary: Orange)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("adventure_rarity_legend_row"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RarityTier.entries.forEach { tier ->
                val tierColor = parseHexColorSafe(tier.primaryColorHex)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = tierColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, tierColor.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("adventure_rarity_legend_${tier.name.lowercase(Locale.US)}")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(tierColor)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = tier.displayName,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = tierColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Animated Floating Tooltip Card when an item in the 3-column grid is tapped
        AnimatedVisibility(
            visible = selectedItem != null,
            enter = fadeIn() + scaleIn(animationSpec = spring(dampingRatio = 0.75f)),
            exit = fadeOut() + scaleOut()
        ) {
            selectedItem?.let { item ->
                val rarityTier = item.rarityTier
                val rarityColor = parseHexColorSafe(rarityTier.primaryColorHex, accentColor)
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .rarityGlowBorder(
                            rarityTier = rarityTier,
                            isSelected = true,
                            pulseFraction = pulseFraction,
                            cornerRadius = 16.dp
                        )
                        .testTag("adventure_item_tooltip_card")
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(rarityColor.copy(alpha = 0.2f))
                                        .border(1.dp, rarityColor, RoundedCornerShape(10.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = resolveAdventureItemIcon(item.iconName),
                                        contentDescription = item.name,
                                        tint = rarityColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = Color.White
                                        )
                                    )
                                    Text(
                                        text = "${rarityTier.displayName} ${item.category} • Qty: x${item.quantity}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                        color = rarityColor
                                    )
                                }
                            }
                            IconButton(
                                onClick = onDismissTooltip,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Item Tooltip",
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = item.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFCBD5E1)
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = { onUseOrInspectItem(item) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = rarityColor,
                                    contentColor = Color(0xFF0F172A)
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                modifier = Modifier
                                    .height(36.dp)
                                    .testTag("adventure_item_use_inspect_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Use / Inspect in Chat",
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 3-Column Scrollable Grid of Inventory Items with Glowing Rarity Border System
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .testTag("adventure_inventory_grid")
        ) {
            items(inventory, key = { it.id }) { item ->
                val rarityTier = item.rarityTier
                val rarityColor = parseHexColorSafe(rarityTier.primaryColorHex, accentColor)
                val isSelected = selectedItem?.id == item.id
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (isSelected) rarityColor.copy(alpha = 0.24f) else Color(0xFF1E293B),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(104.dp)
                        .rarityGlowBorder(
                            rarityTier = rarityTier,
                            isSelected = isSelected,
                            pulseFraction = pulseFraction,
                            cornerRadius = 14.dp
                        )
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelectItem(item) }
                        .testTag("adventure_inventory_item_${item.id}")
                ) {
                    Box(modifier = Modifier.padding(7.dp)) {
                        // Rarity Tier Badge in top-left corner
                        Surface(
                            shape = RoundedCornerShape(5.dp),
                            color = rarityColor.copy(alpha = 0.2f),
                            border = BorderStroke(0.5.dp, rarityColor.copy(alpha = 0.75f)),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .testTag("adventure_item_rarity_badge_${item.id}")
                        ) {
                            Text(
                                text = when (rarityTier) {
                                    RarityTier.LEGENDARY -> "LEGEND"
                                    RarityTier.EPIC -> "EPIC"
                                    RarityTier.RARE -> "RARE"
                                    RarityTier.COMMON -> "COMMON"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.ExtraBold
                                ),
                                color = rarityColor,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }

                        // Quantity Pill Badge in top-right corner
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF0F172A).copy(alpha = 0.85f),
                            border = BorderStroke(0.5.dp, rarityColor.copy(alpha = 0.7f)),
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            Text(
                                text = "x${item.quantity}",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = rarityColor,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(rarityColor.copy(alpha = 0.20f))
                                    .border(1.dp, rarityColor.copy(alpha = 0.65f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = resolveAdventureItemIcon(item.iconName),
                                    contentDescription = item.name,
                                    tint = rarityColor,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                ),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestsListSection(
    adventureState: AdventureGameState,
    accentColor: Color,
    showAddQuestFields: Boolean,
    newQuestTitle: String,
    newQuestDesc: String,
    newQuestXp: String,
    onSelectQuestFilter: (QuestFilterMode) -> Unit,
    onToggleShowAddQuest: () -> Unit,
    onNewQuestTitleChange: (String) -> Unit,
    onNewQuestDescChange: (String) -> Unit,
    onNewQuestXpChange: (String) -> Unit,
    onConfirmAddQuest: () -> Unit,
    onToggleQuestCompleted: (String) -> Unit
) {
    var expandedQuestIds by remember { mutableStateOf(setOf(adventureState.quests.firstOrNull()?.id.orEmpty())) }
    val animatedMilestone by animateFloatAsState(
        targetValue = adventureState.questCompletionProgressFraction,
        animationSpec = spring(),
        label = "quest_milestone_progress"
    )
    val visibleQuests = adventureState.filteredQuests

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("adventure_quests_list")
    ) {
        // Milestone Progress Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF1E293B),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Campaign Quest Milestones",
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                    Text(
                        text = "${adventureState.completedQuestCount} / ${adventureState.quests.size} Done",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = accentColor
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { animatedMilestone },
                    color = Color(0xFF10B981),
                    trackColor = Color(0xFF0F172A),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Adventure Objectives",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE2E8F0)
                )
            )
            OutlinedButton(
                onClick = onToggleShowAddQuest,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(32.dp)
                    .testTag("adventure_add_quest_toggle_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Custom Quest",
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("New Quest", style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 3-Way Quest Filter Toggle Row ('All', 'Active', 'Completed') with Live Count Badges
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("adventure_quest_filter_row"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuestFilterMode.entries.forEach { mode ->
                val isSelected = adventureState.questFilter == mode
                val countForMode = when (mode) {
                    QuestFilterMode.ALL -> adventureState.quests.size
                    QuestFilterMode.ACTIVE -> adventureState.activeQuestCount
                    QuestFilterMode.COMPLETED -> adventureState.completedQuestCount
                }
                val activeChipColor = when (mode) {
                    QuestFilterMode.COMPLETED -> Color(0xFF10B981)
                    QuestFilterMode.ACTIVE -> Color(0xFF38BDF8)
                    QuestFilterMode.ALL -> accentColor
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) activeChipColor.copy(alpha = 0.22f) else Color(0xFF1E293B),
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) activeChipColor else Color(0xFF334155)
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectQuestFilter(mode) }
                        .testTag("adventure_quest_filter_${mode.name.lowercase(Locale.US)}")
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 7.dp, horizontal = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = mode.displayName,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                fontSize = 11.sp
                            ),
                            color = if (isSelected) activeChipColor else Color(0xFFCBD5E1),
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isSelected) activeChipColor.copy(alpha = 0.28f) else Color(0xFF0F172A)
                        ) {
                            Text(
                                text = countForMode.toString(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = if (isSelected) activeChipColor else Color(0xFF94A3B8),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = showAddQuestFields) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF1E293B),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    OutlinedTextField(
                        value = newQuestTitle,
                        onValueChange = onNewQuestTitleChange,
                        label = { Text("Quest Title") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("adventure_new_quest_title_input")
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = newQuestDesc,
                        onValueChange = onNewQuestDescChange,
                        label = { Text("Quest Objective Description") },
                        maxLines = 3,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("adventure_new_quest_desc_input")
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newQuestXp,
                            onValueChange = onNewQuestXpChange,
                            label = { Text("XP Reward") },
                            singleLine = true,
                            modifier = Modifier.width(120.dp)
                        )
                        Button(
                            onClick = onConfirmAddQuest,
                            modifier = Modifier.testTag("adventure_confirm_add_quest_button")
                        ) {
                            Text("Add Quest")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (visibleQuests.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF1E293B).copy(alpha = 0.7f),
                border = BorderStroke(1.dp, Color(0xFF334155)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .testTag("adventure_quests_empty_filter_state")
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = if (adventureState.questFilter == QuestFilterMode.COMPLETED) {
                            Icons.Default.CheckCircle
                        } else {
                            Icons.Default.Explore
                        },
                        contentDescription = null,
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = when (adventureState.questFilter) {
                            QuestFilterMode.ACTIVE -> "No active quests remaining"
                            QuestFilterMode.COMPLETED -> "No completed quests yet"
                            QuestFilterMode.ALL -> "No quests in campaign log"
                        },
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = when (adventureState.questFilter) {
                            QuestFilterMode.ACTIVE -> "Switch to 'Completed' or add a new quest objective to continue your journey."
                            QuestFilterMode.COMPLETED -> "Complete an active quest objective to earn XP and record it here."
                            QuestFilterMode.ALL -> "Tap 'New Quest' above or explore in chat to discover new objectives."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF94A3B8)
                    )
                }
            }
        }

        visibleQuests.forEach { quest ->
            val isExpanded = expandedQuestIds.contains(quest.id)
            val statusColor = if (quest.isCompleted) Color(0xFF10B981) else accentColor
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                border = BorderStroke(
                    width = 1.dp,
                    color = if (quest.isCompleted) Color(0xFF10B981).copy(alpha = 0.5f) else Color(0xFF334155)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clickable {
                        expandedQuestIds = if (isExpanded) {
                            expandedQuestIds - quest.id
                        } else {
                            expandedQuestIds + quest.id
                        }
                    }
                    .testTag("adventure_quest_card_${quest.id}")
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (quest.isCompleted) Icons.Default.CheckCircle else Icons.Default.Explore,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = quest.title,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                )
                                Text(
                                    text = if (quest.isCompleted) "Completed • +${quest.xpReward} XP Earned" else "Active Objective • +${quest.xpReward} XP",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = statusColor
                                )
                            }
                        }

                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse quest" else "Expand quest",
                            tint = Color(0xFF94A3B8)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { if (quest.isCompleted) 1f else 0.45f },
                        color = statusColor,
                        trackColor = Color(0xFF0F172A),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )

                    AnimatedVisibility(visible = isExpanded) {
                        Column(modifier = Modifier.padding(top = 8.dp)) {
                            Text(
                                text = quest.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFCBD5E1)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                FilledTonalButton(
                                    onClick = { onToggleQuestCompleted(quest.id) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier
                                        .height(34.dp)
                                        .testTag("adventure_quest_toggle_${quest.id}")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (quest.isCompleted) "Mark Active" else "Complete (+${quest.xpReward} XP)",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
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

@Composable
private fun CharacterPortraitAndStatsSection(
    adventureState: AdventureGameState,
    accentColor: Color,
    onPickPortrait: () -> Unit,
    onUpdateCharacterProfile: (String, String) -> Unit
) {
    val context = LocalContext.current
    var nameInput by remember(adventureState.characterName) { mutableStateOf(adventureState.characterName) }
    var classInput by remember(adventureState.characterClass) { mutableStateOf(adventureState.characterClass) }

    val portraitBitmap = remember(adventureState.characterPortraitUri) {
        try {
            adventureState.characterPortraitUri?.takeIf { it.isNotBlank() }?.let { uriStr ->
                val uri = Uri.parse(uriStr)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Large Visual Portrait Card (PNG, JPEG, WEBP)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF1E293B),
            border = BorderStroke(2.dp, accentColor),
            modifier = Modifier
                .size(168.dp)
                .clip(RoundedCornerShape(20.dp))
                .clickable { onPickPortrait() }
                .testTag("adventure_character_large_portrait")
        ) {
            if (portraitBitmap != null) {
                Image(
                    bitmap = portraitBitmap,
                    contentDescription = "Character Visual Portrait",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Upload Portrait",
                        tint = accentColor,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap to Upload Portrait\n(PNG / JPEG / WEBP)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFCBD5E1)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = onPickPortrait,
            modifier = Modifier.testTag("adventure_upload_portrait_button")
        ) {
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Choose Character Portrait")
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = nameInput,
            onValueChange = {
                nameInput = it
                onUpdateCharacterProfile(it, classInput)
            },
            label = { Text("Character Name") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("adventure_character_name_input")
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = classInput,
            onValueChange = {
                classInput = it
                onUpdateCharacterProfile(nameInput, it)
            },
            label = { Text("Class / Archetype") },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("adventure_character_class_input")
        )
    }
}

@Composable
private fun LoreAndAdvancedSettingsSection(
    adventureState: AdventureGameState,
    accentColor: Color,
    onToggleAutoSaveOnQuest: (Boolean) -> Unit,
    onToggleNotifications: (Boolean) -> Unit,
    onUpdateLoreContext: (String) -> Unit,
    onLaunchLoreFilePicker: () -> Unit,
    onSaveGlobalPreset: () -> Unit,
    onLoadGlobalPreset: () -> Unit
) {
    var loreText by remember(adventureState.settings.loreContext) {
        mutableStateOf(adventureState.settings.loreContext)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("adventure_lore_settings_section")
    ) {
        Text(
            text = "Game Master Lore & Persistence",
            style = MaterialTheme.typography.titleSmall.copy(
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Auto-Save on Quest & Save Sentinel Notification Toggles
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF1E293B),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Save on Quest Completion",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                        Text(
                            text = "Automatically persists campaign state to Room SQLite whenever a quest completes.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Switch(
                        checked = adventureState.settings.autoSaveOnQuest,
                        onCheckedChange = onToggleAutoSaveOnQuest,
                        modifier = Modifier.testTag("adventure_autosave_quest_switch")
                    )
                }

                HorizontalDivider(
                    color = Color(0xFF334155),
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "20-Min Save Sentinel Alerts",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                        Text(
                            text = "Alerts via Quick Save snackbar & status bar notification after 20m unsaved play.",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF94A3B8)
                        )
                    }
                    Switch(
                        checked = adventureState.settings.notificationsEnabled,
                        onCheckedChange = onToggleNotifications,
                        modifier = Modifier.testTag("adventure_sentinel_notifications_switch")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Attach Document Lore Context (.txt, .md, .pdf)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "World Lore & Attached Documents",
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE2E8F0)
                )
            )
            FilledTonalButton(
                onClick = onLaunchLoreFilePicker,
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier
                    .height(34.dp)
                    .testTag("adventure_attach_lore_doc_button")
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = "Attach Lore Document",
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "Attach .TXT/.MD/.PDF",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                )
            }
        }

        if (adventureState.attachedLoreFileNames.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Attached Lore: ${adventureState.attachedLoreFileNames.joinToString(", ")}",
                style = MaterialTheme.typography.labelSmall,
                color = accentColor
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        OutlinedTextField(
            value = loreText,
            onValueChange = {
                loreText = it
                onUpdateLoreContext(it)
            },
            label = { Text("Custom World Lore & Rules Context") },
            placeholder = { Text("Paste world rules, factions, or attach a .txt / .md / .pdf lore book...") },
            minLines = 4,
            maxLines = 8,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("adventure_lore_context_input")
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Global Preset Save / Load Option (Phase 1 Confirmed Choice)
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF1E293B),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "Global Campaign Preset",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
                Text(
                    text = "Save this chat's hero, lore, and inventory as your Global Preset or load it into any chat.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onSaveGlobalPreset,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("adventure_save_global_preset_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Save Preset", style = MaterialTheme.typography.labelSmall)
                    }
                    OutlinedButton(
                        onClick = onLoadGlobalPreset,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("adventure_load_global_preset_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Restore,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Load Preset", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * Renders styled RPG event badges inside a chat message bubble when inline markers
 * (`[QUEST_ADD: ...]`, `[QUEST_COMPLETE: ...]`, `[LOOT_DROP: ...]`) are present.
 */
@Composable
fun AdventureInlineEventBadges(
    events: List<AdventureInlineEvent>,
    onOpenDrawerToTab: (AdventureDrawerTab) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (events.isEmpty()) return

    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag("adventure_inline_event_badges")
    ) {
        events.forEachIndexed { idx, event ->
            when (event) {
                is AdventureInlineEvent.QuestAdded -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF0F172A).copy(alpha = 0.88f),
                        border = BorderStroke(1.dp, Color(0xFF38BDF8)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpenDrawerToTab(AdventureDrawerTab.QUESTS) }
                            .testTag("adventure_badge_quest_add_$idx")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Explore,
                                contentDescription = "New Quest Added",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "NEW QUEST: ${event.quest.title}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF38BDF8)
                                    )
                                )
                                Text(
                                    text = "${event.quest.description} (+${event.quest.xpReward} XP)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFE2E8F0),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                is AdventureInlineEvent.QuestCompleted -> {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF0F172A).copy(alpha = 0.88f),
                        border = BorderStroke(1.dp, Color(0xFF10B981)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpenDrawerToTab(AdventureDrawerTab.QUESTS) }
                            .testTag("adventure_badge_quest_complete_$idx")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Quest Completed",
                                tint = Color(0xFF10B981),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "QUEST COMPLETED: ${event.questIdOrTitle}",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color(0xFF10B981)
                                    )
                                )
                                Text(
                                    text = "Campaign progress auto-saved • Tap to view Quest Log",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFA7F3D0)
                                )
                            }
                        }
                    }
                }

                is AdventureInlineEvent.LootDropped -> {
                    val rarityColor = parseHexColorSafe(event.item.rarityColorHex, Color(0xFFF59E0B))
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF0F172A).copy(alpha = 0.88f),
                        border = BorderStroke(1.2.dp, rarityColor),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onOpenDrawerToTab(AdventureDrawerTab.INVENTORY) }
                            .testTag("adventure_badge_loot_drop_$idx")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = resolveAdventureItemIcon(event.item.iconName),
                                contentDescription = "Loot Dropped",
                                tint = rarityColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "LOOT ACQUIRED: ${event.item.name} (x${event.item.quantity})",
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = FontWeight.ExtraBold,
                                        color = rarityColor
                                    )
                                )
                                Text(
                                    text = event.item.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFE2E8F0),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
