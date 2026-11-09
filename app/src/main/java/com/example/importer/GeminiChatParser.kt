package com.example.importer

import com.example.data.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

private const val TAG = "GeminiChatParser"

private fun logD(tag: String, msg: String) {
    try {
        android.util.Log.d(tag, msg)
    } catch (_: Throwable) {
        println("[$tag] $msg")
    }
}

private fun logE(tag: String, msg: String, tr: Throwable? = null) {
    try {
        android.util.Log.e(tag, msg, tr)
    } catch (_: Throwable) {
        System.err.println("[$tag] $msg: ${tr?.message}")
    }
}

data class ParsedGeminiChat(
    val title: String,
    val suggestedWakeWord: String,
    val systemInstruction: String = "You are Gemini, continuing this ongoing conversation.",
    val messages: List<ChatMessage>
)

class GeminiChatParser {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /**
     * Parses raw input which can be:
     * 1. A Gemini public share URL (e.g. https://gemini.google.com/share/...)
     * 2. A Google Takeout or Gemini JSON export
     * 3. Copied/pasted conversation text or markdown transcript
     */
    suspend fun parseGeminiInput(rawInput: String): ParsedGeminiChat? = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return@withContext null

        // Case 1: Public Gemini share URL
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            if (trimmed.contains("gemini.google.com") || trimmed.contains("g.co/gemini")) {
                val parsedFromUrl = fetchAndParseShareUrl(trimmed)
                if (parsedFromUrl != null) return@withContext parsedFromUrl
            }
        }

        // Case 2: JSON format (Takeout or Bridge export)
        if ((trimmed.startsWith("{") && trimmed.endsWith("}")) ||
            (trimmed.startsWith("[") && trimmed.endsWith("]"))
        ) {
            val parsedFromJson = parseJsonFormat(trimmed)
            if (parsedFromJson != null) return@withContext parsedFromJson
        }

        // Case 3: Text / Markdown conversation transcript
        return@withContext parseTextTranscript(trimmed)
    }

    private fun fetchAndParseShareUrl(url: String): ParsedGeminiChat? {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return null

            val html = response.body?.string().orEmpty()
            if (html.isBlank()) return null

            // Extract title from <title> or <meta property="og:title">
            val titleMatcher = Pattern.compile("<title>(.*?)</title>", Pattern.CASE_INSENSITIVE).matcher(html)
            var title = if (titleMatcher.find()) {
                titleMatcher.group(1)?.replace("- Gemini", "")?.trim().orEmpty()
            } else "Gemini Shared Chat"

            if (title.isBlank() || title.equals("Gemini", ignoreCase = true)) {
                title = "Imported Gemini Chat"
            }

            // Extract shared text / structured turns
            val messages = mutableListOf<ChatMessage>()
            val now = System.currentTimeMillis()

            // Look for JSON data embedded in page script (AF_initDataCallback or JSON-LD)
            val jsonLdMatcher = Pattern.compile("<script type=\"application/ld\\+json\">(.*?)</script>", Pattern.DOTALL).matcher(html)
            if (jsonLdMatcher.find()) {
                val jsonLd = jsonLdMatcher.group(1)?.trim().orEmpty()
                try {
                    val root = JSONObject(jsonLd)
                    val text = root.optString("text")
                    if (text.isNotBlank()) {
                        messages.add(ChatMessage(chatId = 0, sender = "gemini", content = text, timestamp = now))
                    }
                } catch (e: Exception) {
                    logD(TAG, "JSON-LD parse skipped: ${e.message}")
                }
            }

            // If no structured JSON-LD found, parse message blocks from HTML text
            if (messages.isEmpty()) {
                // Strip scripts and styles
                val cleanHtml = html
                    .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")

                // Extract paragraphs or content blocks
                val turnParser = parseTextTranscript(cleanHtml.replace(Regex("<[^>]+>"), "\n"))
                if (turnParser != null && turnParser.messages.isNotEmpty()) {
                    return turnParser.copy(title = title, suggestedWakeWord = generateSuggestedWakeWord(title))
                }
            }

            val suggestedWakeWord = generateSuggestedWakeWord(title)
            return ParsedGeminiChat(
                title = title,
                suggestedWakeWord = suggestedWakeWord,
                messages = messages
            )
        } catch (e: Exception) {
            logE(TAG, "Error fetching share URL", e)
            return null
        }
    }

    private fun parseJsonFormat(jsonString: String): ParsedGeminiChat? {
        try {
            val messages = mutableListOf<ChatMessage>()
            var title = "Imported Gemini Chat"
            val now = System.currentTimeMillis()

            if (jsonString.startsWith("{")) {
                val root = JSONObject(jsonString)
                if (root.has("chatTitle")) {
                    title = root.optString("chatTitle", title)
                } else if (root.has("title")) {
                    title = root.optString("title", title)
                }

                // Google Takeout format or custom export
                val msgArray = root.optJSONArray("messages")
                    ?: root.optJSONArray("parts")
                    ?: root.optJSONArray("turns")
                    ?: root.optJSONArray("history")

                if (msgArray != null) {
                    for (i in 0 until msgArray.length()) {
                        val obj = msgArray.optJSONObject(i) ?: continue
                        val sender = when {
                            obj.optString("sender").contains("user", true) -> "user"
                            obj.optString("author").contains("user", true) -> "user"
                            obj.optString("role").contains("user", true) -> "user"
                            else -> "gemini"
                        }
                        val content = obj.optString("content", "")
                            .ifEmpty { obj.optString("text", "") }
                            .ifEmpty { obj.optString("body", "") }

                        if (content.isNotBlank()) {
                            messages.add(
                                ChatMessage(
                                    chatId = 0,
                                    sender = sender,
                                    content = content.trim(),
                                    timestamp = obj.optLong("timestamp", now + i * 1000)
                                )
                            )
                        }
                    }
                }
            } else if (jsonString.startsWith("[")) {
                // Array of message turns
                val array = JSONArray(jsonString)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val sender = when {
                        obj.optString("sender").contains("user", true) -> "user"
                        obj.optString("author").contains("user", true) -> "user"
                        obj.optString("role").contains("user", true) -> "user"
                        else -> "gemini"
                    }
                    val content = obj.optString("content", "")
                        .ifEmpty { obj.optString("text", "") }
                    if (content.isNotBlank()) {
                        messages.add(
                            ChatMessage(
                                chatId = 0,
                                sender = sender,
                                content = content.trim(),
                                timestamp = obj.optLong("timestamp", now + i * 1000)
                            )
                        )
                    }
                }
            }

            if (messages.isEmpty()) return null

            // Generate title from first user prompt if still default
            val firstPrompt = messages.firstOrNull { it.sender == "user" }?.content
            if (title == "Imported Gemini Chat" && !firstPrompt.isNullOrBlank()) {
                title = firstPrompt.take(30).trim() + "..."
            }

            val suggestedWake = generateSuggestedWakeWord(title)
            return ParsedGeminiChat(
                title = title,
                suggestedWakeWord = suggestedWake,
                messages = messages
            )
        } catch (e: Exception) {
            logE(TAG, "Failed to parse JSON export", e)
            return null
        }
    }

    private fun parseTextTranscript(text: String): ParsedGeminiChat? {
        val lines = text.lines()
        val messages = mutableListOf<ChatMessage>()
        var currentSender = "user"
        val currentContent = StringBuilder()
        val now = System.currentTimeMillis()
        var turnIndex = 0

        // Regex patterns for speaker prefixes (e.g. You:, User:, Q:, Gemini:, Bard:, Model:, A:)
        val userPrefixRegex = Regex("^(you|user|human|prompt|question|q)\\s*:\\s*", RegexOption.IGNORE_CASE)
        val geminiPrefixRegex = Regex("^(gemini|model|bard|assistant|response|answer|a)\\s*:\\s*", RegexOption.IGNORE_CASE)

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isBlank()) {
                if (currentContent.isNotEmpty()) {
                    currentContent.append("\n\n")
                }
                continue
            }

            val isUser = userPrefixRegex.containsMatchIn(line)
            val isGemini = geminiPrefixRegex.containsMatchIn(line)

            if (isUser || isGemini) {
                // Flush previous turn
                if (currentContent.isNotBlank()) {
                    messages.add(
                        ChatMessage(
                            chatId = 0,
                            sender = currentSender,
                            content = currentContent.toString().trim(),
                            timestamp = now + turnIndex * 1000
                        )
                    )
                    turnIndex++
                    currentContent.clear()
                }

                if (isUser) {
                    currentSender = "user"
                    currentContent.append(line.replace(userPrefixRegex, ""))
                } else {
                    currentSender = "gemini"
                    currentContent.append(line.replace(geminiPrefixRegex, ""))
                }
            } else {
                currentContent.append(line).append("\n")
            }
        }

        // Flush last message
        if (currentContent.isNotBlank()) {
            messages.add(
                ChatMessage(
                    chatId = 0,
                    sender = currentSender,
                    content = currentContent.toString().trim(),
                    timestamp = now + turnIndex * 1000
                )
            )
        }

        // If no speaker prefixes were found, treat entire text as initial prompt or conversation
        if (messages.isEmpty() && text.isNotBlank()) {
            messages.add(
                ChatMessage(
                    chatId = 0,
                    sender = "user",
                    content = text.trim(),
                    timestamp = now
                )
            )
        }

        val firstPrompt = messages.firstOrNull { it.sender == "user" }?.content ?: text
        val title = firstPrompt.take(28).trim().replace(Regex("[^a-zA-Z0-9 ]"), "") + "..."
        val suggestedWake = generateSuggestedWakeWord(title)

        return ParsedGeminiChat(
            title = if (title.isBlank() || title == "...") "Gemini Chat" else title,
            suggestedWakeWord = suggestedWake,
            messages = messages
        )
    }

    private fun generateSuggestedWakeWord(title: String): String {
        val clean = title.replace("...", "").replace(Regex("[^a-zA-Z0-9 ]"), "").trim()
        val words = clean.split("\\s+".toRegex()).take(2)
        return if (words.isNotEmpty() && words[0].isNotBlank()) {
            words.joinToString(" ")
        } else {
            "Gemini Project"
        }
    }
}
