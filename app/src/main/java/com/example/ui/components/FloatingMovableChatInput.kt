package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.ui.AttachmentPayload
import com.example.util.NetworkConnectionState
import kotlin.math.roundToInt

/**
 * Floating movable user chat text box with an interactive Anchor Dot.
 * - Dragging the Anchor Dot moves the floating box anywhere in the chat window.
 * - Tapping the Anchor Dot toggles between collapsed dot view and expanded floating input view.
 * - Tapping the Dock button returns the input box to the standard bottom bar.
 */
@Composable
fun FloatingMovableChatInput(
    inputText: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    isGenerating: Boolean,
    isSending: Boolean,
    isCollapsedToDot: Boolean,
    onToggleCollapseToDot: () -> Unit,
    onDockToBottomBar: () -> Unit,
    offsetX: Float,
    offsetY: Float,
    onDragDelta: (Float, Float) -> Unit,
    accentColor: Color,
    networkState: NetworkConnectionState,
    onRefreshNetwork: () -> Unit = {},
    stagedAttachment: AttachmentPayload? = null,
    onTakePhoto: () -> Unit = {},
    onBrowseFiles: () -> Unit = {},
    isInChatDictationActive: Boolean = false,
    viewModel: com.example.ui.MainViewModel? = null,
    onStartVoiceRecording: () -> Unit = {},
    onToggleInChatDictation: () -> Unit = onStartVoiceRecording,
    onTextInputFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .navigationBarsPadding()
            .imePadding()
            .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
            .testTag("floating_movable_chat_container")
    ) {
        if (isCollapsedToDot) {
            // Compact Anchor Dot Only Mode
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                accentColor,
                                MaterialTheme.colorScheme.primary
                            )
                        )
                    )
                    .border(2.dp, Color.White.copy(alpha = 0.85f), CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onDragDelta(dragAmount.x, dragAmount.y)
                        }
                    }
                    .clickable { onToggleCollapseToDot() }
                    .testTag("floating_input_anchor_dot"),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        } else {
            // Expanded Movable Floating Chat Box with Anchor Dot Header
            Surface(
                modifier = Modifier
                    .widthIn(min = 260.dp, max = 380.dp)
                    .fillMaxWidth(0.92f)
                    .testTag("floating_chat_input_card"),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                tonalElevation = 10.dp,
                shadowElevation = 12.dp,
                border = BorderStroke(1.5.dp, accentColor.copy(alpha = 0.6f))
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Top Control Strip: Anchor Dot (Drag to move / Tap to collapse) + Network + Dock
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        onDragDelta(dragAmount.x, dragAmount.y)
                                    }
                                }
                        ) {
                            // Interactive Anchor Dot
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(accentColor.copy(alpha = 0.22f))
                                    .border(2.dp, accentColor, CircleShape)
                                    .clickable { onToggleCollapseToDot() }
                                    .testTag("floating_input_anchor_dot"),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(accentColor)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.OpenWith,
                                contentDescription = "Drag anchor dot to move",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Drag dot • Tap to collapse",
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BottomBarNetworkStatusIcon(
                                networkState = networkState,
                                onRefreshNetwork = onRefreshNetwork
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(
                                onClick = onDockToBottomBar,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .testTag("dock_floating_input_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "Dock to bottom bar",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Input Controls Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box {
                            var showAttachMenu by remember { mutableStateOf(false) }
                            IconButton(
                                onClick = { showAttachMenu = true },
                                enabled = !isSending,
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .testTag("floating_attachment_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Attach file",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showAttachMenu,
                                onDismissRequest = { showAttachMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Take Photo") },
                                    leadingIcon = { Icon(Icons.Default.CameraAlt, null) },
                                    onClick = {
                                        showAttachMenu = false
                                        onTakePhoto()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Browse Files") },
                                    leadingIcon = { Icon(Icons.Default.Folder, null) },
                                    onClick = {
                                        showAttachMenu = false
                                        onBrowseFiles()
                                    }
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
                                .onFocusChanged { onTextInputFocusChanged(it.isFocused) }
                                .testTag("floating_chat_input_textfield"),
                            shape = RoundedCornerShape(20.dp),
                            maxLines = 3,
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
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isInChatDictationActive) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .testTag("floating_voice_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = if (isInChatDictationActive) "Stop in-chat voice dictation" else "In-chat voice dictation",
                                tint = if (isInChatDictationActive) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        val canSend = (inputText.text.isNotBlank() || stagedAttachment != null) && !isGenerating && !isSending
                        IconButton(
                            onClick = onSend,
                            enabled = canSend,
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(
                                    if (canSend) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                                .testTag("floating_send_message_button")
                        ) {
                            if (isGenerating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Send message",
                                    tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
