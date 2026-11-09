package com.example.importer

import android.content.Context
import android.net.Uri
import com.example.data.dao.ChatDao
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

data class ImportProgressState(
    val isImporting: Boolean = false,
    val currentChunkIndex: Int = 0,
    val totalChunks: Int = 0,
    val importedCount: Int = 0,
    val totalCount: Int = 0,
    val percentage: Float = 0f,
    val statusMessage: String = "",
    val error: String? = null
)

class ChatImportWorker(private val context: Context, private val chatDao: ChatDao) {

    private val parser = GeminiChatParser()

    private val _progressState = MutableStateFlow(ImportProgressState())
    val progressState: StateFlow<ImportProgressState> = _progressState.asStateFlow()

    private var isCancelled = false

    fun cancelImport() {
        isCancelled = true
        _progressState.value = ImportProgressState(
            isImporting = false,
            statusMessage = "Import cancelled by user."
        )
    }

    /**
     * Streams and parses large .json or .md files without reading the entire file into memory at once,
     * then inserts messages in batches of 50 to avoid SQLite transaction locks.
     */
    suspend fun importChatArchive(uri: Uri, targetChatId: Long? = null): Long? = withContext(Dispatchers.IO) {
        isCancelled = false
        _progressState.value = ImportProgressState(
            isImporting = true,
            statusMessage = "Opening chat archive stream..."
        )

        try {
            val mimeType = (context.contentResolver.getType(uri) ?: "").lowercase()
            var fileName = ""
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx != -1) {
                        fileName = cursor.getString(idx) ?: ""
                    }
                }
            }
            if (fileName.isBlank()) {
                fileName = uri.lastPathSegment ?: ""
            }
            val lowerName = fileName.lowercase()
            val isBinaryDoc = mimeType.contains("pdf") ||
                mimeType.contains("msword") ||
                mimeType.contains("officedocument") ||
                lowerName.endsWith(".pdf") ||
                lowerName.endsWith(".doc") ||
                lowerName.endsWith(".docx")

            val isHtmlDoc = mimeType.contains("html") ||
                lowerName.endsWith(".html") ||
                lowerName.endsWith(".htm")

            val parsedChat: ParsedGeminiChat? = if (isBinaryDoc) {
                _progressState.value = _progressState.value.copy(statusMessage = "Extracting chat from document...")
                extractChatFromDocument(uri)
            } else {
                // Stream text file contents (.md, .txt, .json, .html)
                val stringBuilder = StringBuilder()
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null

                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    val charBuffer = CharArray(8192)
                    var charsRead: Int
                    while (reader.read(charBuffer).also { charsRead = it } != -1) {
                        if (isCancelled) return@withContext null
                        stringBuilder.append(charBuffer, 0, charsRead)
                    }
                }

                val rawContent = stringBuilder.toString()
                if (rawContent.isBlank()) {
                    _progressState.value = ImportProgressState(isImporting = false, error = "File is empty.")
                    return@withContext null
                }

                _progressState.value = _progressState.value.copy(statusMessage = "Parsing messages from archive...")

                if (isHtmlDoc) {
                    val cleanText = rawContent
                        .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), "")
                        .replace(Regex("<[^>]+>"), "\n")
                    parser.parseGeminiInput(cleanText) ?: extractChatFromDocument(uri)
                } else {
                    parser.parseGeminiInput(rawContent)
                }
            }

            if (parsedChat == null || parsedChat.messages.isEmpty()) {
                _progressState.value = ImportProgressState(isImporting = false, error = "Could not recognize chat messages in archive.")
                return@withContext null
            }

            val allMessages = parsedChat.messages
            val totalCount = allMessages.size
            
            // Determine chatId
            val chatId = if (targetChatId != null && targetChatId > 0L) {
                targetChatId
            } else {
                // Create new chat session for import
                val newSession = ChatSession(
                    title = parsedChat.title.ifBlank { "Imported Archive" },
                    wakeWord = parsedChat.suggestedWakeWord,
                    isPersistent = true,
                    totalTrackedSeconds = 0L
                )
                chatDao.insertChat(newSession)
            }

            // Step 3: Chunk messages in batches of 50 for non-blocking persistence
            val batchSize = 50
            val batches = allMessages.chunked(batchSize)
            val totalChunks = batches.size
            var importedSoFar = 0

            _progressState.value = ImportProgressState(
                isImporting = true,
                currentChunkIndex = 0,
                totalChunks = totalChunks,
                importedCount = 0,
                totalCount = totalCount,
                percentage = 0f,
                statusMessage = "Importing 0/$totalCount messages..."
            )

            for (index in batches.indices) {
                if (isCancelled) break
                val batch = batches[index]

                // Assign correct chatId to each message in batch
                val assignedBatch = batch.map { it.copy(chatId = chatId) }

                // Insert batch via Room
                chatDao.insertMessagesSafely(assignedBatch)
                importedSoFar += batch.size

                val ratio = (index + 1).toFloat() / totalChunks
                _progressState.value = ImportProgressState(
                    isImporting = true,
                    currentChunkIndex = index + 1,
                    totalChunks = totalChunks,
                    importedCount = importedSoFar,
                    totalCount = totalCount,
                    percentage = ratio,
                    statusMessage = "Importing archive... $importedSoFar/$totalCount messages (${(ratio * 100).toInt()}%)."
                )
            }

            if (!isCancelled) {
                _progressState.value = ImportProgressState(
                    isImporting = false,
                    currentChunkIndex = totalChunks,
                    totalChunks = totalChunks,
                    importedCount = importedSoFar,
                    totalCount = totalCount,
                    percentage = 1f,
                    statusMessage = "Successfully imported $importedSoFar messages!"
                )
            }

            return@withContext chatId
        } catch (e: Exception) {
            _progressState.value = ImportProgressState(
                isImporting = false,
                error = "Import error: ${e.message}"
            )
            return@withContext null
        }
    }

    /**
     * Uses Gemini to extract a structured chat history from a PDF, DOC, or HTML file.
     */
    private suspend fun extractChatFromDocument(uri: Uri): ParsedGeminiChat? = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
            val mimeType = contentResolver.getType(uri) ?: "application/pdf"

            val model = Firebase.ai.generativeModel(
                modelName = "gemini-3.8-flash",
                systemInstruction = content {
                    text("Extract the full conversation history from this document. Format it as a structured chat with a title, a suggested wake word, and a list of message turns (sender: 'user' or 'gemini').")
                },
                generationConfig = generationConfig {
                    responseMimeType = "application/json"
                    responseSchema = Schema.obj(
                        properties = mapOf(
                            "title" to Schema.string(description = "Chat title"),
                            "wakeWord" to Schema.string(description = "Suggested 1-2 word wake phrase"),
                            "messages" to Schema.array(
                                items = Schema.obj(
                                    properties = mapOf(
                                        "sender" to Schema.string(description = "Either 'user' or 'gemini'"),
                                        "content" to Schema.string(description = "Text content of the message")
                                    )
                                )
                            )
                        )
                    )
                }
            )

            val response = withTimeout(60_000L) {
                model.generateContent(
                    content {
                        inlineData(bytes, mimeType)
                        text("Extract conversation turns from this document into valid JSON.")
                    }
                )
            }

            val jsonString = response.text ?: return@withContext null
            val json = JSONObject(jsonString)
            val title = json.optString("title", "Imported Document")
            val wake = json.optString("wakeWord", "Hey Gemini")
            val msgArray = json.optJSONArray("messages") ?: return@withContext null
            
            val messages = mutableListOf<ChatMessage>()
            val now = System.currentTimeMillis()
            for (i in 0 until msgArray.length()) {
                val obj = msgArray.getJSONObject(i)
                messages.add(
                    ChatMessage(
                        chatId = 0,
                        sender = obj.optString("sender", "user").lowercase(),
                        content = obj.optString("content", ""),
                        timestamp = now + i * 1000
                    )
                )
            }

            ParsedGeminiChat(title, wake, messages = messages)
        } catch (e: Exception) {
            android.util.Log.e("ChatImportWorker", "Document extraction failed: ${e.message}")
            null
        }
    }
}
