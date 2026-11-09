package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ChatSession
import com.example.util.AppSettingsManager
import kotlin.math.sin

/**
 * Standard quick-toggle categories displayed in the right-edge labeled filter chip bar.
 */
val SIDE_BAR_CATEGORIES = listOf("All", "Work", "Personal", "Projects")

/**
 * Sticky horizontal bar preset categories displayed below the TopAppBar on HomeScreen.
 */
val STICKY_PRESET_CATEGORIES = listOf("All", "Assistant", "Code", "Custom Personas")

@Composable
fun categoryAccentColor(category: String?): Color {
    return when (category?.trim()?.lowercase()) {
        "assistant" -> Color(0xFF38BDF8)
        "code" -> Color(0xFF10B981)
        "custom personas" -> Color(0xFFA855F7)
        "work" -> Color(0xFF38BDF8)
        "personal" -> Color(0xFF10B981)
        "projects" -> Color(0xFFF59E0B)
        "research" -> Color(0xFFA855F7)
        else -> MaterialTheme.colorScheme.primary
    }
}

/**
 * Determines whether a [ChatSession] matches the active category filter chip in the sticky horizontal bar
 * or side category filter bar. Supports both preset smart categories ("Assistant", "Code", "Custom Personas")
 * and explicit user-assigned categories ("Work", "Personal", "Projects", etc.).
 */
fun matchesCategoryFilter(
    chat: ChatSession,
    categoryFilter: String?,
    assignedCategory: String?,
    tags: Set<String> = emptySet()
): Boolean {
    val cleanFilter = categoryFilter?.trim().orEmpty()
    if (cleanFilter.isEmpty() || cleanFilter.equals("All", ignoreCase = true)) {
        return true
    }
    if (assignedCategory?.equals(cleanFilter, ignoreCase = true) == true) {
        return true
    }
    if (tags.any { it.equals(cleanFilter, ignoreCase = true) }) {
        return true
    }
    return when (cleanFilter.lowercase()) {
        "assistant" -> {
            chat.isDefaultWakeChat ||
                chat.iconName.equals("SmartToy", ignoreCase = true) ||
                chat.iconName.equals("AutoAwesome", ignoreCase = true) ||
                chat.title.contains("assistant", ignoreCase = true) ||
                chat.title.contains("gemini", ignoreCase = true) ||
                assignedCategory.isNullOrBlank()
        }
        "code" -> {
            chat.iconName.equals("Code", ignoreCase = true) ||
                chat.iconName.equals("Terminal", ignoreCase = true) ||
                chat.title.contains("code", ignoreCase = true) ||
                chat.title.contains("dev", ignoreCase = true) ||
                chat.title.contains("debug", ignoreCase = true) ||
                assignedCategory?.equals("Projects", ignoreCase = true) == true ||
                assignedCategory?.equals("Work", ignoreCase = true) == true ||
                tags.any { it.contains("code", ignoreCase = true) || it.contains("dev", ignoreCase = true) }
        }
        "custom personas" -> {
            chat.greetingMessage.isNotBlank() ||
                (chat.systemInstruction.isNotBlank() &&
                    !chat.systemInstruction.startsWith("You are Gemini, a helpful", ignoreCase = true)) ||
                chat.iconName.equals("Person", ignoreCase = true) ||
                chat.iconName.equals("Psychology", ignoreCase = true) ||
                assignedCategory?.equals("Personal", ignoreCase = true) == true ||
                tags.any { it.contains("persona", ignoreCase = true) || it.contains("role", ignoreCase = true) }
        }
        else -> false
    }
}

/**
 * Sticky horizontal Category Filter Bar positioned directly below the TopAppBar on HomeScreen.
 * Includes preset filters ("All", "Assistant", "Code", "Custom Personas") alongside existing custom categories.
 */
@Composable
fun StickyHorizontalCategoryFilterBar(
    selectedCategory: String?,
    onSelectCategory: (String?) -> Unit,
    chats: List<ChatSession>,
    chatCategoryMap: Map<Long, String>,
    chatTagsMap: Map<Long, Set<String>> = emptyMap(),
    availableCategories: List<String> = AppSettingsManager.DEFAULT_CHAT_CATEGORIES,
    modifier: Modifier = Modifier
) {
    val combinedCategories = remember(availableCategories, chatCategoryMap) {
        (STICKY_PRESET_CATEGORIES +
            AppSettingsManager.PRIMARY_SIDE_FILTER_CATEGORIES +
            availableCategories +
            chatCategoryMap.values)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
        modifier = modifier
            .fillMaxWidth()
            .testTag("sticky_category_filter_bar")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(
                    items = combinedCategories,
                    key = { "sticky_cat_${it.lowercase()}" }
                ) { categoryLabel ->
                    val isAll = categoryLabel.equals("All", ignoreCase = true)
                    val isSelected = if (isAll) {
                        selectedCategory.isNullOrBlank() || selectedCategory.equals("All", ignoreCase = true)
                    } else {
                        selectedCategory?.equals(categoryLabel, ignoreCase = true) == true
                    }
                    val count = remember(chats, chatCategoryMap, chatTagsMap, categoryLabel) {
                        chats.count { chat ->
                            matchesCategoryFilter(
                                chat = chat,
                                categoryFilter = if (isAll) null else categoryLabel,
                                assignedCategory = chatCategoryMap[chat.id],
                                tags = chatTagsMap[chat.id].orEmpty()
                            )
                        }
                    }
                    val accent = categoryAccentColor(if (isAll) null else categoryLabel)
                    val tagSuffix = categoryLabel.lowercase().replace(Regex("[^a-z0-9]+"), "_")

                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            if (isAll) {
                                onSelectCategory(null)
                            } else {
                                onSelectCategory(if (isSelected) null else categoryLabel)
                            }
                        },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Text(
                                    text = categoryLabel,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                )
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) {
                                        accent.copy(alpha = 0.24f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    }
                                ) {
                                    Text(
                                        text = count.toString(),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        },
                        leadingIcon = {
                            val icon = when (categoryLabel.lowercase()) {
                                "all" -> Icons.Default.Folder
                                "assistant" -> Icons.Default.AutoAwesome
                                "code" -> Icons.Default.Code
                                "custom personas" -> Icons.Default.Person
                                else -> Icons.Default.Folder
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.16f),
                            selectedLabelColor = MaterialTheme.colorScheme.onSurface
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            selectedBorderColor = accent,
                            borderWidth = 1.dp,
                            selectedBorderWidth = 1.5.dp
                        ),
                        modifier = Modifier.testTag("sticky_category_chip_$tagSuffix")
                    )
                }
            }
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                thickness = 0.8.dp
            )
        }
    }
}

const val MIN_RADAR_PILL_CLICK_DEBOUNCE_MS = 400L

/**
 * Prominent TopAppBar Hands-Free Radar Toggle Pill shared across HomeScreen and ChatDetailScreen.
 * Directly binds its live green status dot and 4-bar pulsating wave HUD to [audioRmsDb] so it
 * visibly reacts to speech energy in real time instead of remaining static.
 */
@Composable
fun TopBarHandsFreeRadarPill(
    isActive: Boolean,
    audioRmsDb: Float,
    isSleepMode: Boolean = false,
    onToggle: () -> Unit,
    testTagPrefix: String = "home",
    modifier: Modifier = Modifier
) {
    val hapticFeedback = androidx.compose.ui.platform.LocalHapticFeedback.current
    val currentOnToggle by androidx.compose.runtime.rememberUpdatedState(onToggle)
    var lastClickTimestampMs by remember { androidx.compose.runtime.mutableLongStateOf(0L) }

    // Normalize audioRmsDb (typically -2dB..12dB during speech) into 0f..1f energy
    val rawNormalizedEnergy = remember(isActive, isSleepMode, audioRmsDb) {
        if (!isActive || isSleepMode) {
            0f
        } else {
            ((audioRmsDb + 2f) / 12f).coerceIn(0.05f, 1f)
        }
    }

    val smoothedEnergy by animateFloatAsState(
        targetValue = rawNormalizedEnergy,
        animationSpec = tween(durationMillis = 90, easing = FastOutSlowInEasing),
        label = "TopBarRmsEnergy"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "TopBarWavePhase")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 650, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "TopBarWavePhaseValue"
    )

    val liveGreen = Color(0xFF10B981)
    val dotScale = if (isActive && !isSleepMode) {
        1f + (smoothedEnergy * 0.48f)
    } else {
        1f
    }

    val containerColor = when {
        isSleepMode -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.75f)
        isActive -> liveGreen.copy(alpha = 0.14f + smoothedEnergy * 0.12f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }

    val borderColor = when {
        isSleepMode -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.6f)
        isActive -> liveGreen.copy(alpha = 0.55f + smoothedEnergy * 0.45f)
        else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = containerColor,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
            .padding(start = 6.dp)
            .widthIn(max = 102.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable {
                val now = System.currentTimeMillis()
                if (lastClickTimestampMs > 0L && (now - lastClickTimestampMs) < MIN_RADAR_PILL_CLICK_DEBOUNCE_MS) {
                    return@clickable
                }
                lastClickTimestampMs = now
                runCatching {
                    hapticFeedback.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                }
                currentOnToggle()
            }
            .testTag("${testTagPrefix}_topbar_hands_free_pill")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Compact live green status dot whose scale and halo react directly to audioRmsDb
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(9.dp)
                    .testTag("${testTagPrefix}_topbar_hands_free_dot")
            ) {
                if (isActive && !isSleepMode) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .scale(dotScale)
                            .clip(CircleShape)
                            .background(liveGreen.copy(alpha = 0.25f + smoothedEnergy * 0.35f))
                    )
                }
                Box(
                    modifier = Modifier
                        .size(5.5.dp)
                        .scale(if (isActive && !isSleepMode) 0.95f + smoothedEnergy * 0.25f else 1f)
                        .clip(CircleShape)
                        .background(
                            when {
                                isSleepMode -> MaterialTheme.colorScheme.tertiary
                                isActive -> liveGreen
                                else -> MaterialTheme.colorScheme.outline
                            }
                        )
                )
            }

            // Compact pulsating wave HUD bound directly to audioRmsDb
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(1.5.dp),
                modifier = Modifier
                    .height(11.dp)
                    .testTag("${testTagPrefix}_topbar_rms_wave_hud")
            ) {
                for (barIdx in 0 until 3) {
                    val waveModulation = if (isActive && !isSleepMode) {
                        val harmonic = ((sin(wavePhase + barIdx * 1.15f) + 1f) * 0.5f)
                        (0.22f + smoothedEnergy * (0.45f + 0.33f * harmonic)).coerceIn(0.2f, 1f)
                    } else {
                        0.22f
                    }
                    Box(
                        modifier = Modifier
                            .width(2.dp)
                            .height((11f * waveModulation).dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(
                                when {
                                    isSleepMode -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                                    isActive -> liveGreen.copy(alpha = (0.65f + smoothedEnergy * 0.35f).coerceIn(0.65f, 1f))
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                }
                            )
                    )
                }
            }

            Text(
                text = when {
                    isSleepMode -> "Sleep"
                    isActive -> if (smoothedEnergy > 0.18f) {
                        "${audioRmsDb.toInt()}dB"
                    } else {
                        "Radar"
                    }
                    else -> "Hands-Free"
                },
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.5.sp
                ),
                color = when {
                    isSleepMode -> MaterialTheme.colorScheme.tertiary
                    isActive -> liveGreen
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Icon(
                imageVector = if (isActive && !isSleepMode) Icons.Default.Hearing else Icons.Default.MicOff,
                contentDescription = if (isActive) "Disable Hands-Free Radar" else "Enable Hands-Free Radar",
                tint = when {
                    isSleepMode -> MaterialTheme.colorScheme.tertiary
                    isActive -> liveGreen
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(11.dp)
            )
        }
    }
}

/**
 * Small labeled filter chip bar positioned on the right edge of the screen that allows users to
 * quickly toggle between 'All', 'Work', 'Personal', and 'Projects' categories to navigate the
 * home screen views and easily navigate chats.
 *
 * Can be collapsed into a tappable pill at the side of the screen and also used inside
 * [com.example.ui.screens.ChatDetailScreen] via its persistent header toggle + side pill.
 */
@Composable
fun CollapsibleSideCategoryFilterBar(
    selectedCategory: String?,
    isCollapsed: Boolean,
    onToggleCollapsed: () -> Unit,
    onSelectCategory: (String?) -> Unit,
    chats: List<ChatSession>,
    chatCategoryMap: Map<Long, String>,
    modifier: Modifier = Modifier,
    isInChatInterface: Boolean = false,
    currentChatId: Long? = null,
    currentChatCategory: String? = null,
    onNavigateToChat: ((Long) -> Unit)? = null,
    onReassignCurrentChatCategory: ((String) -> Unit)? = null,
    testTagPrefix: String = "home"
) {
    val activeCategoryLabel = selectedCategory?.takeIf { it.isNotBlank() } ?: "All"

    val categoryCounts = remember(chats, chatCategoryMap) {
        val counts = mutableMapOf<String, Int>()
        counts["All"] = chats.size
        for (cat in AppSettingsManager.PRIMARY_SIDE_FILTER_CATEGORIES) {
            counts[cat] = chats.count { chat ->
                chatCategoryMap[chat.id]?.equals(cat, ignoreCase = true) == true
            }
        }
        counts
    }

    val matchingChatsForInChatSwitcher = remember(chats, chatCategoryMap, selectedCategory) {
        val filtered = if (!isInChatInterface) {
            emptyList()
        } else if (selectedCategory.isNullOrBlank() || selectedCategory.equals("All", ignoreCase = true)) {
            chats
        } else {
            chats.filter { chat ->
                chatCategoryMap[chat.id]?.equals(selectedCategory, ignoreCase = true) == true
            }
        }
        com.example.util.AppSettingsManager.sortChatsWithPinnedFirst(filtered)
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.CenterEnd
    ) {
        AnimatedContent(
            targetState = isCollapsed,
            transitionSpec = {
                (slideInHorizontally { fullWidth -> fullWidth / 2 } + fadeIn())
                    .togetherWith(slideOutHorizontally { fullWidth -> fullWidth / 2 } + fadeOut())
            },
            label = "SideCategoryFilterBarTransition"
        ) { collapsed ->
            if (collapsed) {
                // Collapsed tappable pill docked at the right edge of the screen
                val badgeColor = categoryAccentColor(
                    if (activeCategoryLabel.equals("All", ignoreCase = true)) currentChatCategory else activeCategoryLabel
                )
                Surface(
                    shape = RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
                    tonalElevation = 6.dp,
                    shadowElevation = 4.dp,
                    border = BorderStroke(1.dp, badgeColor.copy(alpha = 0.45f)),
                    modifier = Modifier
                        .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                        .clickable { onToggleCollapsed() }
                        .testTag("${testTagPrefix}_side_category_filter_pill")
                ) {
                    Row(
                        modifier = Modifier.padding(start = 10.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Expand Category Filter Bar",
                            tint = badgeColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Icon(
                            imageVector = Icons.Default.Folder,
                            contentDescription = null,
                            tint = badgeColor,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (isInChatInterface && !currentChatCategory.isNullOrBlank() && selectedCategory == null) {
                                currentChatCategory
                            } else {
                                activeCategoryLabel
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else {
                // Expanded small labeled filter chip bar on the right side
                Surface(
                    shape = RoundedCornerShape(topStart = 18.dp, bottomStart = 18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.97f),
                    tonalElevation = 8.dp,
                    shadowElevation = 6.dp,
                    border = BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier
                        .widthIn(min = 112.dp, max = if (isInChatInterface) 216.dp else 134.dp)
                        .testTag("${testTagPrefix}_side_category_filter_bar")
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Header row with title + collapse button
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "Categories",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            IconButton(
                                onClick = onToggleCollapsed,
                                modifier = Modifier
                                    .size(24.dp)
                                    .testTag("${testTagPrefix}_side_category_collapse_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "Collapse Category Bar to Side Pill",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        // Labeled filter chips: 'All', 'Work', 'Personal', 'Projects'
                        SIDE_BAR_CATEGORIES.forEach { categoryLabel ->
                            val isAll = categoryLabel.equals("All", ignoreCase = true)
                            val isSelected = if (isAll) {
                                selectedCategory.isNullOrBlank()
                            } else {
                                selectedCategory?.equals(categoryLabel, ignoreCase = true) == true
                            }
                            val count = categoryCounts[categoryLabel] ?: 0
                            val chipAccent = categoryAccentColor(if (isAll) null else categoryLabel)

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) {
                                    chipAccent.copy(alpha = 0.22f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = if (isSelected) chipAccent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        if (isAll) {
                                            onSelectCategory(null)
                                        } else {
                                            onSelectCategory(if (isSelected && !isInChatInterface) null else categoryLabel)
                                        }
                                    }
                                    .testTag("${testTagPrefix}_side_chip_${categoryLabel.lowercase()}")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        modifier = Modifier.weight(1f, fill = false)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(7.dp)
                                                .clip(CircleShape)
                                                .background(chipAccent)
                                        )
                                        Text(
                                            text = categoryLabel,
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            ),
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.onSurface
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = count.toString(),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = if (isSelected) chipAccent else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        // When inside ChatDetailScreen: allow reassigning the current chat or switching to chats in the category
                        if (isInChatInterface) {
                            val targetCat = selectedCategory?.takeIf {
                                it.isNotBlank() && !it.equals("All", ignoreCase = true)
                            }
                            if (targetCat != null &&
                                onReassignCurrentChatCategory != null &&
                                !currentChatCategory.equals(targetCat, ignoreCase = true)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { onReassignCurrentChatCategory(targetCat) }
                                        .testTag("chat_side_assign_category_button")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = "Move Chat to $targetCat",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }

                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
                                modifier = Modifier.padding(vertical = 2.dp)
                            )

                            Text(
                                text = "Jump to Chat (${matchingChatsForInChatSwitcher.size}):",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth()
                            )

                            if (matchingChatsForInChatSwitcher.isEmpty()) {
                                Text(
                                    text = "No chats in ${selectedCategory ?: "All"}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 170.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    contentPadding = PaddingValues(vertical = 2.dp)
                                ) {
                                    items(
                                        items = matchingChatsForInChatSwitcher,
                                        key = { "in_chat_nav_${it.id}" }
                                    ) { chatItem ->
                                        val isCurrent = chatItem.id == currentChatId
                                        val itemCat = chatCategoryMap[chatItem.id]
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = if (isCurrent) {
                                                MaterialTheme.colorScheme.secondaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.surface
                                            },
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .clickable {
                                                    if (!isCurrent) {
                                                        onNavigateToChat?.invoke(chatItem.id)
                                                    }
                                                }
                                                .testTag("chat_side_switch_item_${chatItem.id}")
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 8.dp, vertical = 5.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = chatItem.title,
                                                        style = MaterialTheme.typography.labelSmall.copy(
                                                            fontSize = 11.sp,
                                                            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                                                        ),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    if (!itemCat.isNullOrBlank()) {
                                                        Text(
                                                            text = itemCat,
                                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                                                            color = categoryAccentColor(itemCat)
                                                        )
                                                    }
                                                }
                                                if (!isCurrent) {
                                                    Icon(
                                                        imageVector = Icons.Default.SwapHoriz,
                                                        contentDescription = "Switch to ${chatItem.title}",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(13.dp)
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
}

/**
 * Dedicated 'Select Category' dialog displayed when starting a new chat so the user can
 * pre-assign the conversation's category ('Work', 'Personal', 'Projects', 'Research', or custom),
 * ensuring that category is automatically applied to the session and all subsequent messages.
 */
@Composable
fun SelectCategoryDialog(
    availableCategories: List<String> = AppSettingsManager.DEFAULT_CHAT_CATEGORIES,
    initialCategory: String = "Work",
    onCategoryConfirmed: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val allCategories = remember(availableCategories) {
        (AppSettingsManager.PRIMARY_SIDE_FILTER_CATEGORIES + AppSettingsManager.DEFAULT_CHAT_CATEGORIES + availableCategories)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
    }

    var selectedCategory by rememberSaveable(initialCategory) {
        mutableStateOf(initialCategory.ifBlank { "Work" })
    }
    var customCategoryDraft by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("select_category_dialog"),
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Column {
                    Text(
                        text = "Select Category",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Pre-assign this conversation & all subsequent messages",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Choose a category for your new chat session. Every message sent or received in this session will automatically inherit this category:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    allCategories.forEach { cat ->
                        val isSelected = selectedCategory.equals(cat, ignoreCase = true)
                        val accent = categoryAccentColor(cat)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) {
                                accent.copy(alpha = 0.18f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                            },
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isSelected) accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selectedCategory = cat
                                }
                                .testTag("select_category_option_${cat.lowercase()}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(accent)
                                    )
                                    Column {
                                        Text(
                                            text = cat,
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            )
                                        )
                                        Text(
                                            text = when (cat.lowercase()) {
                                                "work" -> "Meetings, coding, tasks & professional projects"
                                                "personal" -> "Daily life, reminders, wellness & personal notes"
                                                "projects" -> "Builds, roadmaps, architecture & experiments"
                                                "research" -> "Deep dives, citations & study threads"
                                                else -> "Custom conversation category"
                                            },
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = accent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Optional custom category input
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customCategoryDraft,
                        onValueChange = { customCategoryDraft = it },
                        label = { Text("Or Custom Category") },
                        placeholder = { Text("e.g. Finance, Travel") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("select_category_custom_input")
                    )
                    if (customCategoryDraft.trim().isNotBlank()) {
                        FilterChip(
                            selected = selectedCategory.equals(customCategoryDraft.trim(), ignoreCase = true),
                            onClick = {
                                selectedCategory = customCategoryDraft.trim()
                            },
                            label = { Text("Use") },
                            leadingIcon = {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                            },
                            modifier = Modifier.testTag("select_category_use_custom_chip")
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val chosen = customCategoryDraft.trim().ifBlank { selectedCategory.trim().ifBlank { "Work" } }
                    onCategoryConfirmed(chosen)
                },
                modifier = Modifier.testTag("select_category_continue_button")
            ) {
                Text("Continue (${customCategoryDraft.trim().ifBlank { selectedCategory }})")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("select_category_cancel_button")
            ) {
                Text("Cancel")
            }
        }
    )
}
