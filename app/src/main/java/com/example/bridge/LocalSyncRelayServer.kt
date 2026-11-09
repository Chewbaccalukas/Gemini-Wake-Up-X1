package com.example.bridge

import android.util.Log
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "LocalSyncRelayServer"

data class RelayServerState(
    val isRunning: Boolean = false,
    val bindHost: String = LocalSyncRelayServer.LOOPBACK_HOST,
    val port: Int = LocalSyncRelayServer.DEFAULT_PORT,
    val sessionToken: String = "",
    val deduplicatedTurnCount: Int = 0,
    val lastSyncTimestamp: Long = 0L,
    val connectedClientsCount: Int = 0,
    val statusText: String = "Stopped",
    val stagedChatId: Long = 0L,
    val stagedChatTitle: String = "",
    val stagedUrlPayload: String = "",
    val stagedMessageCount: Int = 0
)

/**
 * Pillar 3 — Embedded Local Loopback HTTP/SSE Relay Server:
 * - Binds strictly to `127.0.0.1:8765` ([InetAddress.getByName]("127.0.0.1")) so only local
 *   loopback clients on the device can connect.
 * - Guards all incoming HTTP & SSE endpoints (`/sync`, `/events`, `/status`) with a
 *   runtime-generated ephemeral session token (`X-Session-Token` header, `Authorization: Bearer`,
 *   or `?token=` query parameter).
 * - Deduplicates incoming and outgoing turns using a thread-safe [ConcurrentHashMap] timestamp
 *   cache (`turnDeduplicationCache`) to prevent echo loops between Chrome and Android.
 * - Passively stages chat sessions, messages, and URL payloads in memory so Chrome clients
 *   can query `127.0.0.1:8765/sync` on demand without the app ever launching Chrome externally.
 */
class LocalSyncRelayServer(
    private val scope: CoroutineScope,
    private val onMessageReceivedFromChrome: suspend (ChatMessage) -> Unit
) {
    companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 8765
        const val DEDUP_WINDOW_MS = 60_000L // 60-second deduplication window for identical turn payloads
        private const val MAX_DEDUP_ENTRIES = 512

        fun generateEphemeralToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    @Volatile
    var sessionToken: String = generateEphemeralToken()
        private set

    /**
     * Thread-safe deduplication cache mapping a canonical turn key (`chatId|sender|normalizedContent`)
     * to its most recent epoch millisecond timestamp.
     */
    val turnDeduplicationCache: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    private val sseClientStreams = CopyOnWriteArrayList<OutputStream>()

    private val _serverState = MutableStateFlow(
        RelayServerState(
            bindHost = LOOPBACK_HOST,
            port = DEFAULT_PORT,
            sessionToken = sessionToken
        )
    )
    val serverState: StateFlow<RelayServerState> = _serverState.asStateFlow()

    private var activeChatProvider: (() -> Pair<ChatSession?, List<ChatMessage>>)? = null

    @Volatile
    var stagedChatSession: ChatSession? = null
        private set

    @Volatile
    var stagedMessages: List<ChatMessage> = emptyList()
        private set

    @Volatile
    var stagedUrlPayload: String = ""
        private set

    @Volatile
    var stagedTimestampMs: Long = 0L
        private set

    fun setChatDataProvider(provider: () -> Pair<ChatSession?, List<ChatMessage>>) {
        this.activeChatProvider = provider
    }

    /**
     * Passively stages chat data and URL payload inside the local relay buffer on `127.0.0.1:8765`.
     * When the user manually opens Chrome later, the Chrome extension/client queries `/sync` or `/staged`
     * and hydrates the page on demand.
     */
    fun stageChatAndUrlPayload(
        chat: ChatSession?,
        messages: List<ChatMessage> = emptyList(),
        stagedUrl: String? = null
    ) {
        if (chat != null) {
            stagedChatSession = chat
        }
        if (messages.isNotEmpty() || chat != null) {
            stagedMessages = messages
        }
        val activeChat = stagedChatSession
        val activeMessages = stagedMessages
        val resolvedUrl = when {
            !stagedUrl.isNullOrBlank() -> stagedUrl
            stagedUrlPayload.isNotBlank() && chat == null -> stagedUrlPayload
            activeChat != null -> "http://$LOOPBACK_HOST:${_serverState.value.port}/sync?token=$sessionToken&chatId=${activeChat.id}"
            else -> "http://$LOOPBACK_HOST:${_serverState.value.port}/sync?token=$sessionToken"
        }
        stagedUrlPayload = resolvedUrl
        stagedTimestampMs = System.currentTimeMillis()

        _serverState.value = _serverState.value.copy(
            stagedChatId = activeChat?.id ?: 0L,
            stagedChatTitle = activeChat?.title ?: "",
            stagedUrlPayload = resolvedUrl,
            stagedMessageCount = activeMessages.size
        )

        if (sseClientStreams.isNotEmpty()) {
            val eventPayload = JSONObject().apply {
                put("chatId", activeChat?.id ?: 0L)
                put("chatTitle", activeChat?.title ?: "Gemini Chat")
                put("stagedUrl", resolvedUrl)
                put("messageCount", activeMessages.size)
                put("stagedAt", stagedTimestampMs)
            }.toString()
            val sseFrame = "event: staged\ndata: $eventPayload\n\n".toByteArray(Charsets.UTF_8)
            for (stream in sseClientStreams) {
                try {
                    stream.write(sseFrame)
                    stream.flush()
                } catch (_: Exception) {
                    sseClientStreams.remove(stream)
                }
            }
        }
    }

    /**
     * Rotates the ephemeral session token at runtime and updates [serverState].
     */
    fun regenerateSessionToken(): String {
        val newToken = generateEphemeralToken()
        sessionToken = newToken
        _serverState.value = _serverState.value.copy(
            sessionToken = newToken,
            statusText = if (_serverState.value.isRunning) {
                "Token Rotated (${LOOPBACK_HOST}:${_serverState.value.port})"
            } else {
                "Stopped (New Token Ready)"
            }
        )
        return newToken
    }

    /**
     * Clears the [ConcurrentHashMap] turn deduplication cache.
     */
    fun clearTurnDeduplicationCache() {
        turnDeduplicationCache.clear()
        _serverState.value = _serverState.value.copy(deduplicatedTurnCount = 0)
    }

    /**
     * Computes a canonical deduplication key for a message turn and checks whether an identical
     * turn was already processed within [dedupWindowMs].
     *
     * @return `true` if this turn is unique and was recorded; `false` if it is a duplicate within the window.
     */
    fun recordTurnIfUnique(
        chatId: Long,
        sender: String,
        content: String,
        timestampMs: Long = System.currentTimeMillis(),
        dedupWindowMs: Long = DEDUP_WINDOW_MS
    ): Boolean {
        val normalizedSender = sender.trim().lowercase(Locale.ROOT).ifBlank { "user" }
        val normalizedContent = content.trim().replace("\\s+".toRegex(), " ")
        if (normalizedContent.isBlank()) return false

        val key = "$chatId|$normalizedSender|${normalizedContent.hashCode()}"
        val previousTs = turnDeduplicationCache[key]
        if (previousTs != null && kotlin.math.abs(timestampMs - previousTs) <= dedupWindowMs) {
            _serverState.value = _serverState.value.copy(
                deduplicatedTurnCount = _serverState.value.deduplicatedTurnCount + 1
            )
            return false
        }

        turnDeduplicationCache[key] = timestampMs
        pruneDeduplicationCacheIfNeeded(timestampMs, dedupWindowMs)
        return true
    }

    private fun pruneDeduplicationCacheIfNeeded(nowMs: Long, dedupWindowMs: Long) {
        if (turnDeduplicationCache.size <= MAX_DEDUP_ENTRIES) return
        val iterator = turnDeduplicationCache.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if ((nowMs - entry.value) > dedupWindowMs * 2) {
                iterator.remove()
            }
        }
    }

    /**
     * Broadcasts an outgoing or newly committed [ChatMessage] to any connected SSE clients
     * on `127.0.0.1:8765/events` if it passes the [ConcurrentHashMap] deduplication check.
     */
    fun publishOutgoingTurnToSse(message: ChatMessage) {
        val isUnique = recordTurnIfUnique(
            chatId = message.chatId,
            sender = message.sender,
            content = message.content,
            timestampMs = message.timestamp
        )
        if (!isUnique || sseClientStreams.isEmpty()) return

        val payload = JSONObject().apply {
            put("id", message.id)
            put("chatId", message.chatId)
            put("sender", message.sender)
            put("content", message.content)
            put("timestamp", message.timestamp)
            put("triggeredByWakeWord", message.triggeredByWakeWord)
        }.toString()

        val sseFrame = "event: message\ndata: $payload\n\n".toByteArray(Charsets.UTF_8)
        for (stream in sseClientStreams) {
            try {
                stream.write(sseFrame)
                stream.flush()
            } catch (_: Exception) {
                sseClientStreams.remove(stream)
            }
        }
        _serverState.value = _serverState.value.copy(
            connectedClientsCount = sseClientStreams.size
        )
    }

    /**
     * Validates whether a request's headers or query string carry the active [sessionToken].
     */
    fun isAuthorizedRequest(
        pathAndQuery: String,
        headers: Map<String, String>
    ): Boolean {
        val expected = sessionToken
        if (expected.isBlank()) return false

        val headerToken = headers["x-session-token"]
            ?: headers["x-gemini-wake-token"]
            ?: headers["authorization"]?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()

        if (headerToken == expected) return true

        val queryPart = pathAndQuery.substringAfter("?", "")
        if (queryPart.isNotEmpty()) {
            val pairs = queryPart.split("&")
            for (pair in pairs) {
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2 && (kv[0] == "token" || kv[0] == "session_token")) {
                    val decoded = try {
                        URLDecoder.decode(kv[1], "UTF-8")
                    } catch (_: Exception) {
                        kv[1]
                    }
                    if (decoded == expected) return true
                }
            }
        }
        return false
    }

    fun start(port: Int = DEFAULT_PORT) {
        if (_serverState.value.isRunning) return
        if (sessionToken.isBlank()) {
            sessionToken = generateEphemeralToken()
        }

        serverJob = scope.launch(Dispatchers.IO) {
            try {
                val loopbackAddress = InetAddress.getByName(LOOPBACK_HOST)
                serverSocket = ServerSocket(port, 50, loopbackAddress).apply {
                    reuseAddress = true
                }
                val boundPort = serverSocket?.localPort?.takeIf { it > 0 } ?: port
                val prev = _serverState.value
                _serverState.value = RelayServerState(
                    isRunning = true,
                    bindHost = LOOPBACK_HOST,
                    port = boundPort,
                    sessionToken = sessionToken,
                    deduplicatedTurnCount = prev.deduplicatedTurnCount,
                    statusText = "Relay Active ($LOOPBACK_HOST:$boundPort)",
                    stagedChatId = prev.stagedChatId,
                    stagedChatTitle = prev.stagedChatTitle,
                    stagedUrlPayload = prev.stagedUrlPayload,
                    stagedMessageCount = prev.stagedMessageCount
                )
                Log.d(TAG, "LocalSyncRelayServer started strictly on $LOOPBACK_HOST:$boundPort")

                while (isActive && serverSocket != null && !serverSocket!!.isClosed) {
                    try {
                        val client = serverSocket!!.accept()
                        // Enforce loopback client verification
                        val remoteAddr = client.inetAddress
                        if (remoteAddr != null && !remoteAddr.isLoopbackAddress) {
                            Log.w(TAG, "Rejected non-loopback connection from ${remoteAddr.hostAddress}")
                            client.close()
                            continue
                        }
                        handleClient(client)
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.w(TAG, "Error accepting client connection", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start LocalSyncRelayServer on $LOOPBACK_HOST:$port", e)
                _serverState.value = _serverState.value.copy(
                    isRunning = false,
                    bindHost = LOOPBACK_HOST,
                    port = port,
                    sessionToken = sessionToken,
                    statusText = "Server Error: ${e.localizedMessage}"
                )
            }
        }
    }

    fun stop() {
        try {
            for (stream in sseClientStreams) {
                try {
                    stream.close()
                } catch (_: Exception) {
                }
            }
            sseClientStreams.clear()
            serverSocket?.close()
            serverSocket = null
            serverJob?.cancel()
            serverJob = null
            _serverState.value = _serverState.value.copy(
                isRunning = false,
                connectedClientsCount = 0,
                statusText = "Stopped"
            )
            Log.d(TAG, "LocalSyncRelayServer stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping server", e)
        }
    }

    private fun handleClient(socket: Socket) {
        scope.launch(Dispatchers.IO) {
            try {
                socket.use { client ->
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    val line = reader.readLine() ?: return@use
                    val parts = line.split(" ")
                    val method = if (parts.isNotEmpty()) parts[0].uppercase(Locale.ROOT) else "GET"
                    val fullPath = if (parts.size > 1) parts[1] else "/"
                    val pathOnly = fullPath.substringBefore("?")

                    // Read HTTP headers
                    val headers = mutableMapOf<String, String>()
                    var contentLength = 0
                    var headerLine = reader.readLine()
                    while (!headerLine.isNullOrEmpty()) {
                        val colonIdx = headerLine.indexOf(':')
                        if (colonIdx > 0) {
                            val hKey = headerLine.substring(0, colonIdx).trim().lowercase(Locale.ROOT)
                            val hVal = headerLine.substring(colonIdx + 1).trim()
                            headers[hKey] = hVal
                            if (hKey == "content-length") {
                                contentLength = hVal.toIntOrNull() ?: 0
                            }
                        }
                        headerLine = reader.readLine()
                    }

                    if (method == "OPTIONS") {
                        sendResponse(client.getOutputStream(), 200, "OK", "application/json", "{}")
                        return@use
                    }

                    // Enforce Ephemeral Session Token Authentication on all non-OPTIONS requests
                    if (!isAuthorizedRequest(fullPath, headers)) {
                        sendResponse(
                            client.getOutputStream(),
                            401,
                            "Unauthorized",
                            "application/json",
                            "{\"error\":\"unauthorized\",\"message\":\"Valid X-Session-Token or ?token= parameter required.\"}"
                        )
                        return@use
                    }

                    when {
                        method == "GET" && pathOnly.startsWith("/events") -> {
                            handleSseStream(client)
                        }
                        method == "GET" && (pathOnly.startsWith("/sync") || pathOnly.startsWith("/status") || pathOnly.startsWith("/staged")) -> {
                            val data = activeChatProvider?.invoke()
                            val chat = data?.first ?: stagedChatSession
                            val rawMessages = if (data?.second?.isNotEmpty() == true) {
                                data.second
                            } else {
                                stagedMessages
                            }

                            // Deduplicate outgoing messages using ConcurrentHashMap timestamp cache
                            val seenTurnSignatures = HashSet<String>(rawMessages.size * 2)
                            val deduplicatedMessages = rawMessages.filter { msg ->
                                val sig = "${msg.chatId}|${msg.sender.lowercase(Locale.ROOT)}|${msg.content.trim().hashCode()}"
                                turnDeduplicationCache.putIfAbsent(sig, msg.timestamp)
                                seenTurnSignatures.add("$sig|${msg.timestamp}")
                            }

                            val root = JSONObject().apply {
                                put("bindHost", LOOPBACK_HOST)
                                put("port", _serverState.value.port)
                                put("chatId", chat?.id ?: _serverState.value.stagedChatId)
                                put("chatTitle", chat?.title ?: _serverState.value.stagedChatTitle.ifBlank { "Gemini Chat" })
                                put("wakeWord", chat?.wakeWord ?: "Hey Gemini")
                                put("stagedUrl", stagedUrlPayload)
                                put("stagedAt", stagedTimestampMs)
                                put("timestamp", System.currentTimeMillis())

                                val msgArray = JSONArray()
                                deduplicatedMessages.forEach { msg ->
                                    val obj = JSONObject().apply {
                                        put("id", msg.id)
                                        put("sender", msg.sender)
                                        put("content", msg.content)
                                        put("timestamp", msg.timestamp)
                                        put("triggeredByWakeWord", msg.triggeredByWakeWord)
                                    }
                                    msgArray.put(obj)
                                }
                                put("messages", msgArray)
                            }

                            _serverState.value = _serverState.value.copy(
                                lastSyncTimestamp = System.currentTimeMillis(),
                                statusText = "Synced with Chrome (${deduplicatedMessages.size} msgs)"
                            )

                            sendResponse(
                                client.getOutputStream(),
                                200,
                                "OK",
                                "application/json",
                                root.toString()
                            )
                        }
                        method == "POST" && pathOnly.startsWith("/sync") -> {
                            val safeLength = contentLength.coerceIn(0, 1_048_576)
                            val body = CharArray(safeLength)
                            var read = 0
                            while (read < safeLength) {
                                val r = reader.read(body, read, safeLength - read)
                                if (r == -1) break
                                read += r
                            }
                            val bodyString = String(body, 0, read)

                            try {
                                val json = JSONObject(bodyString)
                                val sender = json.optString("sender", "user")
                                val content = json.optString("content", "")
                                val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                                val chatId = json.optLong("chatId", 0L)

                                if (content.isNotBlank()) {
                                    val isUnique = recordTurnIfUnique(
                                        chatId = chatId,
                                        sender = sender,
                                        content = content,
                                        timestampMs = timestamp
                                    )
                                    if (isUnique) {
                                        val newMsg = ChatMessage(
                                            chatId = chatId,
                                            sender = sender,
                                            content = content,
                                            timestamp = timestamp,
                                            triggeredByWakeWord = false
                                        )
                                        onMessageReceivedFromChrome(newMsg)
                                        _serverState.value = _serverState.value.copy(
                                            lastSyncTimestamp = System.currentTimeMillis(),
                                            statusText = "Received message from Chrome"
                                        )
                                        sendResponse(
                                            client.getOutputStream(),
                                            200,
                                            "OK",
                                            "application/json",
                                            "{\"status\":\"ok\",\"deduplicated\":false}"
                                        )
                                    } else {
                                        sendResponse(
                                            client.getOutputStream(),
                                            200,
                                            "OK",
                                            "application/json",
                                            "{\"status\":\"duplicate_ignored\",\"deduplicated\":true}"
                                        )
                                    }
                                } else {
                                    sendResponse(
                                        client.getOutputStream(),
                                        400,
                                        "Bad Request",
                                        "application/json",
                                        "{\"error\":\"empty_content\"}"
                                    )
                                }
                            } catch (e: Exception) {
                                sendResponse(
                                    client.getOutputStream(),
                                    400,
                                    "Bad Request",
                                    "application/json",
                                    "{\"error\":\"invalid_json\"}"
                                )
                            }
                        }
                        else -> {
                            sendResponse(
                                client.getOutputStream(),
                                200,
                                "OK",
                                "application/json",
                                "{\"status\":\"running\",\"host\":\"$LOOPBACK_HOST\",\"port\":${_serverState.value.port}}"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error handling client request", e)
            }
        }
    }

    private fun handleSseStream(client: Socket) {
        val out = client.getOutputStream()
        val headers = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: keep-alive\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Headers: Content-Type, X-Session-Token, Authorization\r\n\r\n"
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write("event: connected\ndata: {\"host\":\"$LOOPBACK_HOST\",\"port\":${_serverState.value.port}}\n\n".toByteArray(Charsets.UTF_8))
        out.flush()

        sseClientStreams.add(out)
        _serverState.value = _serverState.value.copy(
            connectedClientsCount = sseClientStreams.size,
            statusText = "SSE Stream Active (${sseClientStreams.size} client)"
        )
        try {
            // Keep socket open while client is connected
            val input = client.getInputStream()
            val buf = ByteArray(16)
            while (input.read(buf) != -1) {
                // Wait until client disconnects
            }
        } catch (_: Exception) {
        } finally {
            sseClientStreams.remove(out)
            _serverState.value = _serverState.value.copy(
                connectedClientsCount = sseClientStreams.size
            )
        }
    }

    private fun sendResponse(
        out: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: String
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 $statusCode $statusText\r\n" +
            "Content-Type: $contentType; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type, X-Session-Token, Authorization\r\n" +
            "Connection: close\r\n\r\n"

        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(bytes)
        out.flush()
    }
}
