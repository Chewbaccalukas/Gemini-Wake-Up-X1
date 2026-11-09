package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Multi-bar animated audio waveform canvas that uses spring-based physics (`spring()`)
 * to react fluidly and organically in real-time to fluctuating [audioRmsDb] amplitudes
 * when [isListening] is true.
 */
@Composable
fun VoiceWaveformVisualizer(
    isListening: Boolean,
    audioRmsDb: Float,
    modifier: Modifier = Modifier,
    barCount: Int = 24,
    height: Dp = 56.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wave_ambient")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.283185f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // Primary spring-physics amplitude tracker (bouncy, responsive attack & organic decay)
    val springRms by animateFloatAsState(
        targetValue = if (isListening) audioRmsDb.coerceIn(0f, 10f) else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "spring_rms_primary"
    )

    // Secondary trailing spring envelope for fluid harmonic ripple across outer bars
    val trailingSpringRms = remember { Animatable(0f) }
    LaunchedEffect(audioRmsDb, isListening) {
        val target = if (isListening) audioRmsDb.coerceIn(0f, 10f) else 0f
        trailingSpringRms.animateTo(
            targetValue = target,
            animationSpec = spring(
                dampingRatio = 0.58f,
                stiffness = 260f
            )
        )
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .testTag("voice_waveform_canvas")
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val safeBars = barCount.coerceAtLeast(3)
        val centerY = canvasHeight / 2f
        val barWidth = (canvasWidth / (safeBars * 1.65f)).coerceIn(2.5f, 10f)
        val gap = if (safeBars > 1) {
            ((canvasWidth - (safeBars * barWidth)) / (safeBars - 1)).coerceAtLeast(1.5f)
        } else {
            0f
        }

        val activeGradient = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF38BDF8),
                Color(0xFFA855F7),
                Color(0xFF38BDF8)
            )
        )

        val listeningAmbientGradient = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF38BDF8).copy(alpha = 0.85f),
                Color(0xFF818CF8).copy(alpha = 0.75f)
            )
        )

        val idleGradient = Brush.verticalGradient(
            colors = listOf(
                Color(0xFF64748B).copy(alpha = 0.65f),
                Color(0xFF475569).copy(alpha = 0.55f)
            )
        )

        for (i in 0 until safeBars) {
            val normalizedIndex = i.toFloat() / safeBars
            // Envelope peaks gently toward the center of the bar array
            val centerEnvelope = 0.52f + 0.48f * sin(normalizedIndex * 3.14159f)
            val wave = sin(phase + (normalizedIndex * 5.2f))
            val secondaryHarmonic = cos(phase * 1.5f - (normalizedIndex * 8.0f))
            val combinedWave = ((wave * 0.7f + secondaryHarmonic * 0.3f) + 1f) / 2f

            val currentHeight = if (isListening) {
                // Blend primary spring amplitude near center with trailing spring near edges for fluid wave propagation
                val blendedRms = (springRms * centerEnvelope) + (trailingSpringRms.value * (1f - centerEnvelope * 0.5f))
                val rmsFactor = (blendedRms / 10f).coerceIn(0f, 1.15f)
                val subtleListeningFloor = canvasHeight * (0.20f + 0.16f * combinedWave * centerEnvelope)
                val dynamicVoiceSurge = (canvasHeight * 0.72f) * rmsFactor * (0.45f + 0.55f * combinedWave) * centerEnvelope
                (subtleListeningFloor + dynamicVoiceSurge).coerceIn(4f, canvasHeight)
            } else {
                (canvasHeight * 0.14f * combinedWave + 3f).coerceIn(3f, canvasHeight * 0.28f)
            }

            val x = i * (barWidth + gap)
            val top = centerY - (currentHeight / 2f)

            val barBrush = when {
                isListening && springRms > 0.35f -> activeGradient
                isListening -> listeningAmbientGradient
                else -> idleGradient
            }

            drawRoundRect(
                brush = barBrush,
                topLeft = Offset(x, top),
                size = Size(barWidth, currentHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

/**
 * Compact, subtle spring-physics animated waveform pill designed for headers and top status bars.
 * Reacts fluidly in real-time to [audioRmsDb] whenever [SpeechRecognizer] is actively listening for the wake word.
 */
@Composable
fun SubtleWakeWordWaveformPill(
    isListening: Boolean,
    audioRmsDb: Float,
    modifier: Modifier = Modifier,
    label: String? = null,
    barCount: Int = 9,
    waveformWidth: Dp = 44.dp,
    waveformHeight: Dp = 16.dp
) {
    val borderColor = if (isListening) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    }
    val containerColor = if (isListening) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.32f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(containerColor)
            .border(1.dp, borderColor, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag("wake_word_waveform_pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(
                    if (isListening) Color(0xFF38BDF8)
                    else MaterialTheme.colorScheme.outline
                )
        )
        VoiceWaveformVisualizer(
            isListening = isListening,
            audioRmsDb = audioRmsDb,
            barCount = barCount,
            height = waveformHeight,
            modifier = Modifier.width(waveformWidth)
        )
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp
                ),
                color = if (isListening) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Top status banner that appears when [SpeechRecognizer] is actively listening for a wake word,
 * displaying the spring-physics animated waveform visualizer, active wake phrase hint, and live transcript preview.
 */
@Composable
fun WakeWordListeningStatusBanner(
    isListening: Boolean,
    audioRmsDb: Float,
    activeWakePhrase: String = "Hey Gemini",
    lastHeardText: String = "",
    totalVoiceCommands: Int = 0,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isListening,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
            expandVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow)),
        exit = fadeOut(tween(150)) + shrinkVertically(tween(150))
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 3.dp)
                .testTag("wake_word_top_status_banner"),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    SubtleWakeWordWaveformPill(
                        isListening = isListening,
                        audioRmsDb = audioRmsDb,
                        barCount = 11,
                        waveformWidth = 52.dp,
                        waveformHeight = 16.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (lastHeardText.isNotBlank()) {
                            "Heard: \"$lastHeardText\""
                        } else {
                            "Listening for \"$activeWakePhrase\" or \"Hey Jimmy\"..."
                        },
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (totalVoiceCommands > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "$totalVoiceCommands voice cmd${if (totalVoiceCommands == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        ),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("banner_total_voice_commands")
                    )
                }
            }
        }
    }
}
