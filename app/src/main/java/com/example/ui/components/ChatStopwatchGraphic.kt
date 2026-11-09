package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Formats seconds into MM:SS (or HH:MM:SS if >= 1 hour).
 */
fun formatTrackedTime(seconds: Long): String {
    val hrs = seconds / 3600
    val mins = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hrs > 0) {
        String.format("%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format("%02d:%02d", mins, secs)
    }
}

/**
 * Custom Canvas graphic stopwatch dial with crown, radial tick marks,
 * and animated spinning needle / sweep arc when running.
 */
@Composable
fun ChatStopwatchDial(
    isRunning: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier,
    dialSize: Dp = 32.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "stopwatch_anim")

    // Needle sweep rotation angle: rotates full 360 deg every 4 seconds when running
    val needleAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "stopwatch_needle"
    )

    // Pulse glow animation for active state
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "stopwatch_pulse"
    )

    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    Canvas(modifier = modifier.size(dialSize)) {
        val width = size.width
        val height = size.height
        val center = Offset(width / 2f, height / 2f + height * 0.04f)
        val radius = (width.coerceAtMost(height) / 2f) * 0.78f

        // 1. Draw top stopwatch crown / pusher (at 12 o'clock)
        val crownWidth = radius * 0.40f
        val crownHeight = radius * 0.22f
        drawRoundRect(
            color = if (isRunning) accentColor else Color.Gray.copy(alpha = 0.7f),
            topLeft = Offset(center.x - crownWidth / 2f, center.y - radius - crownHeight * 0.8f),
            size = Size(crownWidth, crownHeight),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
        )

        // 2. Draw side lap pusher (at ~2 o'clock / 45 degrees)
        val lapAngleRad = Math.toRadians(-45.0)
        val lapX = center.x + (radius * 1.05f) * cos(lapAngleRad).toFloat()
        val lapY = center.y + (radius * 1.05f) * sin(lapAngleRad).toFloat()
        drawCircle(
            color = if (isRunning) accentColor.copy(alpha = 0.8f) else Color.Gray.copy(alpha = 0.5f),
            radius = radius * 0.12f,
            center = Offset(lapX, lapY)
        )

        // 3. Draw outer bezel
        val bezelColor = if (isRunning) accentColor.copy(alpha = pulseAlpha) else Color.Gray.copy(alpha = 0.35f)
        drawCircle(
            color = bezelColor,
            radius = radius,
            center = center,
            style = Stroke(width = radius * 0.14f)
        )

        // 4. Draw dial background
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    if (isRunning) accentColor.copy(alpha = 0.18f) else Color.Black.copy(alpha = 0.05f),
                    if (isRunning) accentColor.copy(alpha = 0.04f) else Color.Transparent
                ),
                center = center,
                radius = radius
            ),
            radius = radius - (radius * 0.07f),
            center = center
        )

        // 5. Draw 12 radial hour/minute tick marks
        for (i in 0 until 12) {
            val angleRad = Math.toRadians((i * 30.0) - 90.0)
            val isMajor = i % 3 == 0
            val tickInnerRadius = radius * (if (isMajor) 0.68f else 0.78f)
            val tickOuterRadius = radius * 0.88f

            val startX = center.x + tickInnerRadius * cos(angleRad).toFloat()
            val startY = center.y + tickInnerRadius * sin(angleRad).toFloat()
            val endX = center.x + tickOuterRadius * cos(angleRad).toFloat()
            val endY = center.y + tickOuterRadius * sin(angleRad).toFloat()

            drawLine(
                color = if (isRunning) accentColor.copy(alpha = if (isMajor) 0.9f else 0.5f) else Color.Gray.copy(alpha = if (isMajor) 0.7f else 0.35f),
                start = Offset(startX, startY),
                end = Offset(endX, endY),
                strokeWidth = if (isMajor) radius * 0.10f else radius * 0.05f,
                cap = StrokeCap.Round
            )
        }

        // 6. Draw active sweep arc if running
        if (isRunning) {
            drawArc(
                color = accentColor.copy(alpha = 0.35f),
                startAngle = -90f,
                sweepAngle = needleAngle,
                useCenter = true,
                topLeft = Offset(center.x - radius * 0.75f, center.y - radius * 0.75f),
                size = Size(radius * 1.5f, radius * 1.5f)
            )
        }

        // 7. Draw rotating needle / second hand
        val currentAngle = if (isRunning) needleAngle else 0f
        val needleRad = Math.toRadians((currentAngle.toDouble()) - 90.0)
        val needleLength = radius * 0.72f
        val needleEndX = center.x + needleLength * cos(needleRad).toFloat()
        val needleEndY = center.y + needleLength * sin(needleRad).toFloat()

        drawLine(
            color = if (isRunning) accentColor else onSurfaceColor.copy(alpha = 0.6f),
            start = center,
            end = Offset(needleEndX, needleEndY),
            strokeWidth = radius * 0.12f,
            cap = StrokeCap.Round
        )

        // 8. Draw center pivot pin
        drawCircle(
            color = if (isRunning) accentColor else onSurfaceColor,
            radius = radius * 0.16f,
            center = center
        )
        drawCircle(
            color = Color.White,
            radius = radius * 0.07f,
            center = center
        )
    }
}

/**
 * Interactive graphic stopwatch control for each listed chat card in HomeScreen.
 * Tap to toggle tracking ON / OFF.
 * Also provides an optional reset button when time > 0.
 */
@Composable
fun ChatStopwatchItemToggle(
    chatId: Long,
    seconds: Long,
    isRunning: Boolean,
    accentColor: Color,
    onToggle: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val formattedTime = formatTrackedTime(seconds)

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Main Interactive Stopwatch Capsule (Tap to Toggle On/Off)
        Surface(
            onClick = onToggle,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .border(
                    width = 1.dp,
                    color = if (isRunning) accentColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(12.dp)
                )
                .testTag("chat_timer_toggle_$chatId"),
            color = if (isRunning) accentColor.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Graphic Dial with animations
                ChatStopwatchDial(
                    isRunning = isRunning,
                    accentColor = accentColor,
                    dialSize = 28.dp
                )

                // Monospace Timer Readout
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            ),
                            color = if (isRunning) accentColor else MaterialTheme.colorScheme.onSurface
                        )
                        if (isRunning) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(accentColor)
                            )
                        }
                    }

                    Text(
                        text = if (isRunning) "Tracking • Persists while chat open" else if (seconds > 0) "Paused • Tap to resume" else "Open chat to time • Tap to track",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = if (isRunning) accentColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Mini Action Icon (Play / Pause)
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            if (isRunning) accentColor.copy(alpha = 0.2f)
                            else MaterialTheme.colorScheme.surface
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isRunning) "Pause timer for chat" else "Start timer for chat",
                        tint = if (isRunning) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }

        // Secondary Reset Button (Visible when seconds > 0)
        AnimatedVisibility(
            visible = seconds > 0,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            IconButton(
                onClick = onReset,
                modifier = Modifier
                    .size(32.dp)
                    .testTag("chat_timer_reset_$chatId")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset chat timer",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * Top HUD banner shown when any chat has an active running timer.
 */
@Composable
fun ActiveChatTimerHUD(
    chatTitle: String,
    chatId: Long,
    seconds: Long,
    accentColor: Color,
    onPause: () -> Unit,
    onOpenChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("active_chat_timer_hud"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, accentColor.copy(alpha = 0.8f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                ChatStopwatchDial(
                    isRunning = true,
                    accentColor = accentColor,
                    dialSize = 36.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(accentColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "ACTIVE CHAT TIMER",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = accentColor
                        )
                    }
                    Text(
                        text = chatTitle,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = formatTrackedTime(seconds),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        ),
                        color = accentColor
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onPause,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor.copy(alpha = 0.15f),
                        contentColor = accentColor
                    ),
                    modifier = Modifier.testTag("active_timer_hud_pause")
                ) {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = "Pause", style = MaterialTheme.typography.labelMedium)
                }

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = onOpenChat,
                    modifier = Modifier.testTag("active_timer_hud_open")
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowForward,
                        contentDescription = "Open Chat",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
