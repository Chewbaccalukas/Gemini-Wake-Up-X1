package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ScreenClockMode {
    LIVE_CLOCK,
    OPEN_CHAT_STOPWATCH
}

/**
 * Screen Clock function card with mode switcher (Live Clock vs Stopwatch)
 * and one-tap toggle for larger jumbo text display.
 */
@Composable
fun ScreenClockCard(
    stopwatchSeconds: Long,
    isStopwatchRunning: Boolean,
    accentColor: Color,
    onToggleStopwatch: () -> Unit,
    onResetStopwatch: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isLargeText by remember { mutableStateOf(false) }
    var currentMode by remember { mutableStateOf(ScreenClockMode.LIVE_CLOCK) }
    var currentTimeString by remember { mutableStateOf("") }
    var currentDateString by remember { mutableStateOf("") }

    // Live clock ticker
    LaunchedEffect(Unit) {
        val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, MMM d", Locale.getDefault())
        while (true) {
            val now = Date()
            currentTimeString = timeFormat.format(now)
            currentDateString = dateFormat.format(now)
            delay(1000L)
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
            .testTag("screen_clock_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isLargeText) accentColor.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Top Controls: Mode Chips and Large Text Toggle Action
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Clock Mode Switcher Chips
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = currentMode == ScreenClockMode.LIVE_CLOCK,
                        onClick = { currentMode = ScreenClockMode.LIVE_CLOCK },
                        label = {
                            Text(
                                "Clock",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.AccessTime,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accentColor.copy(alpha = 0.2f),
                            selectedLabelColor = accentColor,
                            selectedLeadingIconColor = accentColor
                        ),
                        modifier = Modifier.testTag("clock_mode_live")
                    )

                    FilterChip(
                        selected = currentMode == ScreenClockMode.OPEN_CHAT_STOPWATCH,
                        onClick = { currentMode = ScreenClockMode.OPEN_CHAT_STOPWATCH },
                        label = {
                            Text(
                                "Stopwatch",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Timer,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accentColor.copy(alpha = 0.2f),
                            selectedLabelColor = accentColor,
                            selectedLeadingIconColor = accentColor
                        ),
                        modifier = Modifier.testTag("clock_mode_stopwatch")
                    )
                }

                // Explicit Toggle Larger Text Button
                Surface(
                    onClick = { isLargeText = !isLargeText },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isLargeText) accentColor.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isLargeText) accentColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    ),
                    modifier = Modifier.testTag("screen_clock_toggle_size")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FormatSize,
                            contentDescription = "Toggle Larger Text",
                            tint = if (isLargeText) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (isLargeText) "Large" else "Normal",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = if (isLargeText) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Interactive Clock Display Area (Tap whole area to toggle larger text!)
            Surface(
                onClick = { isLargeText = !isLargeText },
                shape = RoundedCornerShape(12.dp),
                color = if (isLargeText) MaterialTheme.colorScheme.surface else Color.Transparent,
                border = if (isLargeText) {
                    androidx.compose.foundation.BorderStroke(1.5.dp, accentColor.copy(alpha = 0.4f))
                } else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("screen_clock_tap_target")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(if (isLargeText) 14.dp else 4.dp),
                    horizontalAlignment = if (isLargeText) Alignment.CenterHorizontally else Alignment.Start
                ) {
                    when (currentMode) {
                        ScreenClockMode.LIVE_CLOCK -> {
                            // Live World Clock Display
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = if (isLargeText) Arrangement.Center else Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(if (isLargeText) 38.dp else 26.dp)
                                            .clip(CircleShape)
                                            .background(accentColor.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AccessTime,
                                            contentDescription = null,
                                            tint = accentColor,
                                            modifier = Modifier.size(if (isLargeText) 22.dp else 16.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(if (isLargeText) 12.dp else 8.dp))
                                    Column {
                                        Text(
                                            text = currentTimeString.ifBlank { "00:00:00" },
                                            style = MaterialTheme.typography.titleLarge.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.ExtraBold,
                                                fontSize = if (isLargeText) 32.sp else 18.sp
                                            ),
                                            color = if (isLargeText) accentColor else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = currentDateString.ifBlank { "Today" },
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = if (isLargeText) 13.sp else 11.sp
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                if (!isLargeText) {
                                    Text(
                                        text = "Tap to enlarge",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        ScreenClockMode.OPEN_CHAT_STOPWATCH -> {
                            // Open Chat Stopwatch Display
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = if (isLargeText) Arrangement.Center else Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ChatStopwatchDial(
                                        isRunning = isStopwatchRunning,
                                        accentColor = accentColor,
                                        dialSize = if (isLargeText) 44.dp else 28.dp
                                    )
                                    Spacer(modifier = Modifier.width(if (isLargeText) 14.dp else 8.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = formatTrackedTime(stopwatchSeconds),
                                                style = MaterialTheme.typography.titleLarge.copy(
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = if (isLargeText) 34.sp else 18.sp
                                                ),
                                                color = if (isStopwatchRunning) accentColor else MaterialTheme.colorScheme.onSurface
                                            )
                                            if (isStopwatchRunning) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .clip(CircleShape)
                                                        .background(accentColor)
                                                )
                                            }
                                        }
                                        Text(
                                            text = if (isStopwatchRunning) "Open Chat Timer • Active" else "Open Chat Timer • Paused",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = if (isLargeText) 13.sp else 11.sp
                                            ),
                                            color = if (isStopwatchRunning) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                // Stopwatch Action Buttons
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = onToggleStopwatch,
                                        modifier = Modifier.size(if (isLargeText) 40.dp else 32.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isStopwatchRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            contentDescription = "Toggle Timer",
                                            tint = accentColor,
                                            modifier = Modifier.size(if (isLargeText) 24.dp else 18.dp)
                                        )
                                    }

                                    if (stopwatchSeconds > 0) {
                                        IconButton(
                                            onClick = onResetStopwatch,
                                            modifier = Modifier.size(if (isLargeText) 40.dp else 32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = "Reset Timer",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(if (isLargeText) 20.dp else 16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Helper label explaining tap toggle
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isLargeText) "💡 Tapped clock for JUMBO text • Tap again to reduce" else "💡 Tap clock anytime to toggle larger text",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}
