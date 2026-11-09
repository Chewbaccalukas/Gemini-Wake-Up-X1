package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.WebLinkHistoryManager
import kotlinx.coroutines.delay

/**
 * Represents a parsed segment of a Markdown message: either regular prose text or a fenced code block.
 */
sealed class MarkdownSegment {
    data class Prose(val text: String) : MarkdownSegment()
    data class CodeBlock(
        val language: String,
        val code: String,
        val blockIndex: Int
    ) : MarkdownSegment()
}

/**
 * Parses a raw Markdown string into alternating [MarkdownSegment.Prose] and [MarkdownSegment.CodeBlock]
 * segments separated by triple backticks (```` ``` ````).
 * Extracts the language identifier from the first line of each fenced block (e.g., `kotlin`, `bash`, `json`).
 */
fun parseMarkdownSegments(rawMarkdown: String): List<MarkdownSegment> {
    if (rawMarkdown.isEmpty()) return emptyList()
    if (!rawMarkdown.contains("```")) {
        return listOf(MarkdownSegment.Prose(rawMarkdown))
    }

    val parts = rawMarkdown.split("```")
    val segments = mutableListOf<MarkdownSegment>()
    var codeBlockCounter = 0

    parts.forEachIndexed { index, part ->
        if (index % 2 == 1) {
            val trimmedStart = part.trimStart('\r')
            val firstNewlineIndex = trimmedStart.indexOf('\n')
            val (detectedLang, body) = if (firstNewlineIndex != -1) {
                val firstLine = trimmedStart.substring(0, firstNewlineIndex).trim()
                val rest = trimmedStart.substring(firstNewlineIndex + 1).trimEnd()
                if (firstLine.isNotEmpty() && firstLine.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '+' || it == '#' }) {
                    firstLine.lowercase() to rest
                } else {
                    "code" to part.trim('\r', '\n')
                }
            } else {
                "code" to part.trim()
            }
            segments.add(
                MarkdownSegment.CodeBlock(
                    language = detectedLang.ifBlank { "code" },
                    code = body,
                    blockIndex = codeBlockCounter++
                )
            )
        } else if (part.isNotEmpty()) {
            segments.add(MarkdownSegment.Prose(part))
        }
    }

    return segments
}

/**
 * Renders message content inside a [SelectionContainer] (or participates directly in a parent
 * [SelectionContainer] when [wrapInSelectionContainer] is `false`) so long-pressing any text bubble
 * brings up native Android selection handles, magnifying glass, and the floating context action menu
 * ("Copy", "Select all", "Share").
 *
 * Fenced code blocks (```` ``` ````) render in a styled dark surface card with a language header
 * wrapped in [DisableSelection] (so "Select All" only grabs the message body and code content)
 * and a dedicated one-tap Copy button.
 */
@Composable
fun MarkdownMessageContent(
    content: String,
    textColor: Color,
    linkColor: Color,
    messageId: Long = 0L,
    onOpenLink: (String, String?) -> Unit = { _, _ -> },
    wrapInSelectionContainer: Boolean = true,
    modifier: Modifier = Modifier
) {
    val segments = remember(content) { parseMarkdownSegments(content) }

    val bodyContent: @Composable () -> Unit = {
        Column(
            modifier = if (wrapInSelectionContainer) Modifier else modifier,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            segments.forEach { segment ->
                when (segment) {
                    is MarkdownSegment.Prose -> {
                        val cleanText = segment.text.trim('\n', '\r')
                        if (cleanText.isNotEmpty()) {
                            SelectableLinkifiedProse(
                                text = cleanText,
                                textColor = textColor,
                                linkColor = linkColor,
                                onOpenLink = onOpenLink
                            )
                        }
                    }
                    is MarkdownSegment.CodeBlock -> {
                        MarkdownCodeBlockCard(
                            language = segment.language,
                            code = segment.code,
                            messageId = messageId,
                            blockIndex = segment.blockIndex
                        )
                    }
                }
            }
        }
    }

    if (wrapInSelectionContainer) {
        SelectionContainer(modifier = modifier) {
            bodyContent()
        }
    } else {
        bodyContent()
    }
}

/**
 * Styled dark surface card for Markdown fenced code blocks (```` ``` ````) with:
 * 1. Header showing the detected language (e.g., `"kotlin"`, `"bash"`, `"json"`) wrapped in [DisableSelection]
 *    so "Select All" never includes header UI labels.
 * 2. Dedicated `"Copy"` icon button that copies only the code block contents to the system clipboard.
 * 3. Selectable code block body participating in the parent [SelectionContainer] for drag-selection.
 */
@Composable
fun MarkdownCodeBlockCard(
    language: String,
    code: String,
    messageId: Long = 0L,
    blockIndex: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isCopied by remember(messageId, blockIndex, code) { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(1800L)
            isCopied = false
        }
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0F172A),
        border = BorderStroke(1.dp, Color(0xFF334155)),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .testTag("markdown_code_block_${messageId}_$blockIndex")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header bar with language badge and Copy Code button — wrapped in DisableSelection
            // so "Select All" only selects the message prose and code block text.
            DisableSelection {
                Surface(
                    color = Color(0xFF1E293B),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = language.ifBlank { "code" },
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                ),
                                color = Color(0xFFE2E8F0),
                                modifier = Modifier.testTag("markdown_code_lang_${messageId}_$blockIndex")
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isCopied) Color(0xFF065F46) else Color(0xFF334155),
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Code ($language)", code)
                                    clipboard.setPrimaryClip(clip)
                                    isCopied = true
                                    Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                                }
                                .testTag("markdown_code_copy_button_${messageId}_$blockIndex")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = if (isCopied) Icons.Default.Check else Icons.Default.ContentCopy,
                                    contentDescription = if (isCopied) "Copied!" else "Copy code block",
                                    tint = if (isCopied) Color(0xFF6EE7B7) else Color(0xFF38BDF8),
                                    modifier = Modifier.size(12.dp)
                                )
                                Text(
                                    text = if (isCopied) "Copied!" else "Copy",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    ),
                                    color = if (isCopied) Color(0xFF6EE7B7) else Color(0xFFE2E8F0)
                                )
                            }
                        }
                    }
                }
            }

            // Selectable code block body honoring the parent SelectionContainer
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = code,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = Color(0xFFF8FAFC)
                    ),
                    modifier = Modifier.testTag("markdown_code_content_${messageId}_$blockIndex")
                )
            }
        }
    }
}

/**
 * Builds an [AnnotatedString] that styles Markdown headings, bullet/numbered lists,
 * inline code (`` `code` ``), bold (`**bold**`), italics (`*italic*`), and clickable URLs
 * via [LinkAnnotation.Url], rendered with a native [Text] composable so it fully honors
 * the parent [SelectionContainer] without intercepting long-press or fling gestures.
 */
internal fun buildMarkdownProseAnnotatedString(
    text: String,
    textColor: Color,
    linkColor: Color,
    onOpenLink: (String, String?) -> Unit
): AnnotatedString {
    val links = WebLinkHistoryManager.extractLinksFromText(text)
    val inlineCodeBackground = textColor.copy(alpha = 0.12f)

    return buildAnnotatedString {
        append(text)

        // 1. Style Markdown headings and list prefixes line by line while preserving exact character offsets
        var lineStart = 0
        while (lineStart < text.length) {
            val newlineIdx = text.indexOf('\n', lineStart)
            val lineEnd = if (newlineIdx == -1) text.length else newlineIdx
            val line = text.substring(lineStart, lineEnd)
            val trimmedStart = line.trimStart()
            val leadingSpaces = line.length - trimmedStart.length

            if (trimmedStart.startsWith("#")) {
                addStyle(
                    style = SpanStyle(fontWeight = FontWeight.Bold),
                    start = lineStart,
                    end = lineEnd
                )
            } else if (trimmedStart.startsWith("- ") || trimmedStart.startsWith("* ") || trimmedStart.startsWith("• ")) {
                val bulletStart = lineStart + leadingSpaces
                val bulletEnd = (bulletStart + 2).coerceAtMost(lineEnd)
                addStyle(
                    style = SpanStyle(fontWeight = FontWeight.Bold, color = linkColor),
                    start = bulletStart,
                    end = bulletEnd
                )
            } else {
                val numberedMatch = NUMBERED_LIST_PREFIX_REGEX.find(trimmedStart)
                if (numberedMatch != null) {
                    val numStart = lineStart + leadingSpaces
                    val numEnd = (numStart + numberedMatch.value.length).coerceAtMost(lineEnd)
                    addStyle(
                        style = SpanStyle(fontWeight = FontWeight.SemiBold, color = linkColor),
                        start = numStart,
                        end = numEnd
                    )
                }
            }

            lineStart = if (newlineIdx == -1) text.length else newlineIdx + 1
        }

        // 2. Style inline code spans (`...`)
        INLINE_CODE_REGEX.findAll(text).forEach { match ->
            addStyle(
                style = SpanStyle(
                    fontFamily = FontFamily.Monospace,
                    background = inlineCodeBackground,
                    fontWeight = FontWeight.Medium
                ),
                start = match.range.first,
                end = match.range.last + 1
            )
        }

        // 3. Style bold spans (**...**)
        BOLD_SPAN_REGEX.findAll(text).forEach { match ->
            addStyle(
                style = SpanStyle(fontWeight = FontWeight.Bold),
                start = match.range.first,
                end = match.range.last + 1
            )
        }

        // 4. Style italic spans (*...*)
        ITALIC_SPAN_REGEX.findAll(text).forEach { match ->
            addStyle(
                style = SpanStyle(fontStyle = FontStyle.Italic),
                start = match.range.first,
                end = match.range.last + 1
            )
        }

        // 5. Attach LinkAnnotation.Url for detected links so links remain clickable inside native Text
        //    without breaking SelectionContainer long-press selection handles or LazyColumn flinging.
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

private val NUMBERED_LIST_PREFIX_REGEX = Regex("^\\d+[.)]\\s+")
private val INLINE_CODE_REGEX = Regex("`[^`\\n]+`")
private val BOLD_SPAN_REGEX = Regex("\\*\\*[^*\\n]+\\*\\*")
private val ITALIC_SPAN_REGEX = Regex("(?<!\\*)\\*[^*\\n]+\\*(?!\\*)")

@Composable
private fun SelectableLinkifiedProse(
    text: String,
    textColor: Color,
    linkColor: Color,
    onOpenLink: (String, String?) -> Unit
) {
    val annotatedString = remember(text, textColor, linkColor) {
        buildMarkdownProseAnnotatedString(
            text = text,
            textColor = textColor,
            linkColor = linkColor,
            onOpenLink = onOpenLink
        )
    }

    Text(
        text = annotatedString,
        style = MaterialTheme.typography.bodyLarge.copy(color = textColor)
    )
}

// ============================================================================
// Chat Theme Customization: Predefined Color Palettes & Background Presets
// ============================================================================

data class ChatColorPaletteOption(
    val id: String,
    val displayName: String,
    val primaryHex: String,
    val secondaryHex: String,
    val userBubbleColor: Color,
    val userBubbleTextColor: Color,
    val assistantAccentColor: Color
) {
    val primaryColor: Color
        get() = assistantAccentColor
}

data class ChatBackgroundPresetOption(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val gradientColors: List<Color>
)

val PREDEFINED_CHAT_COLOR_PALETTES: List<ChatColorPaletteOption> = listOf(
    ChatColorPaletteOption(
        id = "OCEAN_CYAN",
        displayName = "Ocean Cyan",
        primaryHex = "#38BDF8",
        secondaryHex = "#0284C7",
        userBubbleColor = Color(0xFF0284C7),
        userBubbleTextColor = Color.White,
        assistantAccentColor = Color(0xFF38BDF8)
    ),
    ChatColorPaletteOption(
        id = "EMERALD_FOREST",
        displayName = "Emerald Forest",
        primaryHex = "#10B981",
        secondaryHex = "#059669",
        userBubbleColor = Color(0xFF059669),
        userBubbleTextColor = Color.White,
        assistantAccentColor = Color(0xFF34D399)
    ),
    ChatColorPaletteOption(
        id = "SUNSET_AMBER",
        displayName = "Sunset Amber",
        primaryHex = "#F59E0B",
        secondaryHex = "#D97706",
        userBubbleColor = Color(0xFFD97706),
        userBubbleTextColor = Color.White,
        assistantAccentColor = Color(0xFFFBBF24)
    ),
    ChatColorPaletteOption(
        id = "ROYAL_VIOLET",
        displayName = "Royal Violet",
        primaryHex = "#A855F7",
        secondaryHex = "#7C3AED",
        userBubbleColor = Color(0xFF7C3AED),
        userBubbleTextColor = Color.White,
        assistantAccentColor = Color(0xFFC084FC)
    ),
    ChatColorPaletteOption(
        id = "ROSE_QUARTZ",
        displayName = "Rose Quartz",
        primaryHex = "#F43F5E",
        secondaryHex = "#E11D48",
        userBubbleColor = Color(0xFFE11D48),
        userBubbleTextColor = Color.White,
        assistantAccentColor = Color(0xFFFB7185)
    ),
    ChatColorPaletteOption(
        id = "MIDNIGHT_SLATE",
        displayName = "Midnight Slate",
        primaryHex = "#64748B",
        secondaryHex = "#334155",
        userBubbleColor = Color(0xFF334155),
        userBubbleTextColor = Color.White,
        assistantAccentColor = Color(0xFF94A3B8)
    )
)

val PREDEFINED_CHAT_BACKGROUNDS: List<ChatBackgroundPresetOption> = listOf(
    ChatBackgroundPresetOption(
        id = "DEFAULT",
        displayName = "Default Clean",
        subtitle = "Adaptive Material surface",
        gradientColors = emptyList()
    ),
    ChatBackgroundPresetOption(
        id = "NEBULA_GLOW",
        displayName = "Nebula Glow",
        subtitle = "Deep cosmic indigo & violet",
        gradientColors = listOf(Color(0x261E1B4B), Color(0x33311042), Color(0x1A0F172A))
    ),
    ChatBackgroundPresetOption(
        id = "AURORA_MESH",
        displayName = "Aurora Mesh",
        subtitle = "Northern lights teal & emerald",
        gradientColors = listOf(Color(0x26064E3B), Color(0x260F766E), Color(0x1A0F172A))
    ),
    ChatBackgroundPresetOption(
        id = "CYBER_GRID",
        displayName = "Cyber Horizon",
        subtitle = "Futuristic cyan & midnight blue",
        gradientColors = listOf(Color(0x26082F49), Color(0x261E3A8A), Color(0x1A090D16))
    ),
    ChatBackgroundPresetOption(
        id = "WARM_DUSK",
        displayName = "Warm Dusk",
        subtitle = "Soft amber & twilight rose",
        gradientColors = listOf(Color(0x26451A03), Color(0x264C0519), Color(0x1A18181B))
    )
)

fun resolveChatColorPalette(paletteId: String?, fallbackHex: String? = null): ChatColorPaletteOption {
    val byId = PREDEFINED_CHAT_COLOR_PALETTES.firstOrNull {
        it.id.equals(paletteId?.trim(), ignoreCase = true)
    }
    if (byId != null) return byId
    val byHex = PREDEFINED_CHAT_COLOR_PALETTES.firstOrNull {
        it.primaryHex.equals(fallbackHex?.trim(), ignoreCase = true)
    }
    return byHex ?: PREDEFINED_CHAT_COLOR_PALETTES.first()
}

fun resolveChatBackgroundPreset(backgroundStyle: String?): ChatBackgroundPresetOption {
    return PREDEFINED_CHAT_BACKGROUNDS.firstOrNull {
        it.id.equals(backgroundStyle?.trim(), ignoreCase = true)
    } ?: PREDEFINED_CHAT_BACKGROUNDS.first()
}

/**
 * Renders the customizable background layer for a chat interface (either a curated gradient/pattern
 * preset or a custom user-selected background image URI).
 */
@Composable
fun ChatCustomizableBackgroundLayer(
    backgroundStyle: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    if (backgroundStyle.startsWith("custom:", ignoreCase = true)) {
        val uriStr = backgroundStyle.removePrefix("custom:").trim()
        val decodedBitmap = remember(uriStr) {
            try {
                val uri = Uri.parse(uriStr)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            } catch (_: Exception) {
                null
            }
        }
        if (decodedBitmap != null) {
            Box(modifier = modifier.fillMaxSize()) {
                Image(
                    bitmap = decodedBitmap,
                    contentDescription = "Custom chat background",
                    contentScale = ContentScale.Crop,
                    alpha = 0.22f,
                    modifier = Modifier.fillMaxSize()
                )
            }
            return
        }
    }

    val preset = remember(backgroundStyle) { resolveChatBackgroundPreset(backgroundStyle) }
    if (preset.gradientColors.isNotEmpty()) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(preset.gradientColors))
        )
    }
}

/**
 * Dialog allowing users to customize the active chat's color palette and background image/preset.
 */
@Composable
fun ChatThemeCustomizationDialog(
    currentPaletteId: String,
    currentBackgroundStyle: String,
    onSelectAppearance: (paletteId: String, backgroundStyle: String, primaryHex: String) -> Unit,
    onPickCustomBackgroundPhoto: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedPalette by remember(currentPaletteId) {
        mutableStateOf(resolveChatColorPalette(currentPaletteId))
    }
    var selectedBackground by remember(currentBackgroundStyle) {
        mutableStateOf(currentBackgroundStyle.ifBlank { "DEFAULT" })
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    tint = selectedPalette.assistantAccentColor
                )
                Text(
                    text = "Customize Chat Theme & Background",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "COLOR PALETTE",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    items(PREDEFINED_CHAT_COLOR_PALETTES, key = { it.id }) { palette ->
                        val isSelected = selectedPalette.id == palette.id
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) {
                                palette.userBubbleColor.copy(alpha = 0.22f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                            },
                            border = BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) palette.assistantAccentColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { selectedPalette = palette }
                                .testTag("theme_palette_option_${palette.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(palette.assistantAccentColor)
                                        .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                                )
                                Text(
                                    text = palette.displayName,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                Text(
                    text = "BACKGROUND CANVAS & WALLPAPER",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PREDEFINED_CHAT_BACKGROUNDS.forEach { bgPreset ->
                        val isSelected = selectedBackground.equals(bgPreset.id, ignoreCase = true)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            },
                            border = BorderStroke(
                                width = if (isSelected) 1.5.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { selectedBackground = bgPreset.id }
                                .testTag("theme_background_preset_${bgPreset.id}")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bgPreset.displayName,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = bgPreset.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Custom Background Photo Picker option
                    FilledTonalButton(
                        onClick = onPickCustomBackgroundPhoto,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pick_custom_background_photo_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (selectedBackground.startsWith("custom:", ignoreCase = true)) {
                                "Change Custom Background Image"
                            } else {
                                "Choose Custom Background Image"
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    onSelectAppearance(
                        selectedPalette.id,
                        selectedBackground,
                        selectedPalette.primaryHex
                    )
                    onDismiss()
                },
                modifier = Modifier.testTag("apply_chat_theme_button")
            ) {
                Text("Apply Theme")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
