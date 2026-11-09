package com.example.coupler

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * Represents a recorded socket/bridge packet inside the in-memory ring buffer.
 */
data class BridgePacket(
    val id: Long,
    val timestamp: Long,
    val type: String,
    val sizeBytes: Int,
    val summary: String
)

/**
 * State representing the status of the local Chrome Bridge socket connection
 * and passively staged chat/URL payloads.
 */
data class ChromeBridgeState(
    val isRunning: Boolean = false,
    val boundPort: Int = 0,
    val sessionNonce: String = "",
    val activeClientsCount: Int = 0,
    val bufferedPacketsCount: Int = 0,
    val cacheDirSizeBytes: Long = 0L,
    val lastResetTimestamp: Long = 0L,
    val lastError: String? = null,
    val isHeartbeatMode: Boolean = false,
    val stagedChatId: Long = 0L,
    val stagedChatTitle: String = "",
    val stagedUrlPayload: String = "",
    val stagedMessagesCount: Int = 0,
    val lastStagedTimestamp: Long = 0L
)

/**
 * Fixed-capacity thread-safe in-memory ring buffer for bridge packets.
 */
class PacketRingBuffer(private val capacity: Int = 64) {
    private val buffer = ArrayDeque<BridgePacket>(capacity)
    private val lock = Any()

    fun add(packet: BridgePacket) = synchronized(lock) {
        if (buffer.size >= capacity) {
            buffer.removeFirst()
        }
        buffer.addLast(packet)
    }

    fun clear() = synchronized(lock) {
        buffer.clear()
    }

    fun size(): Int = synchronized(lock) {
        buffer.size
    }

    fun toList(): List<BridgePacket> = synchronized(lock) {
        buffer.toList()
    }
}

/**
 * ChromeBridgeSocketManager coordinates passive background staging between Gemini Wake
 * and local browser environments (e.g. Chrome extension/client querying the local relay).
 *
 * Key Architecture:
 * 1. Dynamic Ephemeral Port: Binds to ServerSocket(0) on 127.0.0.1 (loopback only) to ensure
 *    zero port collisions and prevent port locks across activity lifecycles.
 * 2. Cryptographic Session Nonce: Generated via SecureRandom on each bind. Inbound HTTP/WebSocket
 *    requests MUST supply ?nonce=... or receive HTTP 403 Forbidden, protecting against local port sniffing.
 * 3. Force Reset & Clear: Closes active client streams, flushes in-memory packet ring buffers,
 *    wipes local bridge cache directories, and immediately rebinds to a fresh random port.
 * 4. Passive Socket Buffer Staging: Passively stages chat sessions, messages, and URL payloads
 *    in the local socket buffer without ever launching Chrome via Intent or startActivity().
 */
object ChromeBridgeSocketManager {

    private const val TAG = "ChromeBridgeSocket"
    private const val BRIDGE_CACHE_DIR = "chrome_bridge"
    private const val STAGED_PAYLOAD_FILENAME = "staged_bridge_payload.json"

    const val ROUTE_SCREENSHOT = "/screenshot"
    const val ROUTE_STAGED = "/staged"
    const val ROUTE_SYNC = "/sync"
    const val FRAME_TYPE_TEXT: Byte = 0x01
    const val FRAME_TYPE_SCREENSHOT: Byte = 0x02

    private val secureRandom = SecureRandom()
    private val packetIdCounter = AtomicLong(1)
    private val packetRingBuffer = PacketRingBuffer(capacity = 64)

    private val activeClientSockets = Collections.synchronizedSet(mutableSetOf<Socket>())

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val managerScope = CoroutineScope(Dispatchers.IO)

    private val _bridgeState = MutableStateFlow(ChromeBridgeState())
    val bridgeState: StateFlow<ChromeBridgeState> = _bridgeState.asStateFlow()

    private val stateLock = Any()

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
    var stagedJsonPayload: String = ""
        private set

    /**
     * Initializes and starts the loopback bridge socket on an ephemeral port if not already running.
     */
    fun start(context: Context): Int = synchronized(stateLock) {
        if (_bridgeState.value.isRunning && serverSocket != null && !serverSocket!!.isClosed) {
            return _bridgeState.value.boundPort
        }
        return bindAndListen(context)
    }

    /**
     * Generates a 128-bit cryptographic random session nonce.
     */
    private fun generateCryptographicNonce(): String {
        val randomBytes = ByteArray(16)
        secureRandom.nextBytes(randomBytes)
        val sb = StringBuilder(randomBytes.size * 2)
        for (b in randomBytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    /**
     * Binds to an ephemeral random port (ServerSocket(0)) on 127.0.0.1 and initializes the accept loop.
     */
    private fun bindAndListen(context: Context): Int {
        try {
            // Ephemeral dynamic port binding strictly on loopback (127.0.0.1)
            val loopback = InetAddress.getByName("127.0.0.1")
            val newServerSocket = ServerSocket(0, 50, loopback)
            serverSocket = newServerSocket

            val port = newServerSocket.localPort
            val nonce = generateCryptographicNonce()

            // Initialize cache directory if not present
            val cacheDir = getCacheDir(context)
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            // Create initial state packet
            recordPacket(
                type = "SOCKET_BOUND",
                sizeBytes = 0,
                summary = "Ephemeral port $port bound with fresh session nonce"
            )

            val prev = _bridgeState.value
            _bridgeState.value = ChromeBridgeState(
                isRunning = true,
                boundPort = port,
                sessionNonce = nonce,
                activeClientsCount = 0,
                bufferedPacketsCount = packetRingBuffer.size(),
                cacheDirSizeBytes = calculateCacheDirSize(context),
                lastResetTimestamp = System.currentTimeMillis(),
                lastError = null,
                isHeartbeatMode = false,
                stagedChatId = prev.stagedChatId,
                stagedChatTitle = prev.stagedChatTitle,
                stagedUrlPayload = prev.stagedUrlPayload,
                stagedMessagesCount = prev.stagedMessagesCount,
                lastStagedTimestamp = prev.lastStagedTimestamp
            )

            // Start listening loop
            acceptJob?.cancel()
            acceptJob = managerScope.launch {
                listenLoop(newServerSocket, nonce, context)
            }

            Log.i(TAG, "ChromeBridgeSocketManager running on http://127.0.0.1:$port/?nonce=$nonce")
            return port
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind ephemeral socket: ${e.message}", e)
            _bridgeState.value = _bridgeState.value.copy(
                isRunning = false,
                lastError = e.message
            )
            return -1
        }
    }

    /**
     * Forcefully closes active client sockets/streams and rebinds to a fresh random ephemeral port.
     * When [preserveDiskCache] is true, staged disk buffers and message caches are preserved across sessions.
     * When [preserveDiskCache] is false, executes a full nuclear wipe of the cache directories and packet buffers.
     */
    fun forceResetAndClear(context: Context, preserveDiskCache: Boolean = false): Int = synchronized(stateLock) {
        Log.i(TAG, "Resetting ChromeBridgeSocketManager (preserveDiskCache=$preserveDiskCache)...")

        // 1. Forcefully close all active client streams and sockets
        val clientsToClose = synchronized(activeClientSockets) {
            val list = activeClientSockets.toList()
            activeClientSockets.clear()
            list
        }
        for (client in clientsToClose) {
            try {
                client.shutdownInput()
            } catch (_: Exception) {}
            try {
                client.shutdownOutput()
            } catch (_: Exception) {}
            try {
                client.close()
            } catch (_: Exception) {}
        }

        // 2. Cancel accept coroutine and close ServerSocket
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        if (!preserveDiskCache) {
            // Full nuclear wipe: flush ring buffer, staged payloads, and wipe bridge cache directory
            packetRingBuffer.clear()
            stagedChatSession = null
            stagedMessages = emptyList()
            stagedUrlPayload = ""
            stagedJsonPayload = ""
            _bridgeState.value = _bridgeState.value.copy(
                stagedChatId = 0L,
                stagedChatTitle = "",
                stagedUrlPayload = "",
                stagedMessagesCount = 0,
                lastStagedTimestamp = 0L
            )
            try {
                val cacheDir = getCacheDir(context)
                if (cacheDir.exists()) {
                    cacheDir.deleteRecursively()
                }
                cacheDir.mkdirs()
            } catch (e: Exception) {
                Log.w(TAG, "Error wiping bridge cache dir: ${e.message}")
            }
            // Clear staged screenshot files and reset image channel on nuclear wipe
            GeminiCouplerBridge.clearStagedImage(context)
        }

        // 3. Rebind to a fresh random ephemeral port
        val newPort = bindAndListen(context)

        // Record reset packet in the fresh buffer
        recordPacket(
            type = if (preserveDiskCache) "FORCE_RESET_PRESERVED" else "FORCE_RESET_WIPED",
            sizeBytes = 0,
            summary = if (preserveDiskCache) {
                "Streams cleanly closed, staged disk cache preserved, rebound to port $newPort"
            } else {
                "Nuclear cache wipe completed, streams closed, rebound to port $newPort"
            }
        )

        updateStateCounters(context)
        return newPort
    }

    /**
     * Enters low-power background heartbeat mode:
     * Tears down the heavy HTTP bridge and packet streaming sockets,
     * but activates lightweight AlarmManager heartbeat receiver.
     */
    fun enterHeartbeatMode(context: Context) = synchronized(stateLock) {
        Log.i(TAG, "Entering low-power heartbeat mode: tearing down heavy HTTP bridge...")

        // Close client streams
        val clientsToClose = synchronized(activeClientSockets) {
            val list = activeClientSockets.toList()
            activeClientSockets.clear()
            list
        }
        for (client in clientsToClose) {
            try { client.close() } catch (_: Exception) {}
        }

        // Close server socket and cancel accept job
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        _bridgeState.value = _bridgeState.value.copy(
            isRunning = false,
            isHeartbeatMode = true,
            activeClientsCount = 0
        )

        // Schedule lightweight AlarmManager heartbeat ping
        com.example.heartbeat.HeartbeatAlarmReceiver.scheduleHeartbeatPing(context)

        recordPacket(
            type = "HEARTBEAT_ENTER",
            sizeBytes = 0,
            summary = "HTTP bridge torn down for background; low-power heartbeat receiver active"
        )
    }

    /**
     * Exits heartbeat mode: cancels background alarm heartbeat ping and re-spawns full HTTP bridge.
     */
    fun exitHeartbeatMode(context: Context): Int = synchronized(stateLock) {
        Log.i(TAG, "Exiting heartbeat mode: restoring full HTTP Chrome bridge...")
        com.example.heartbeat.HeartbeatAlarmReceiver.cancelHeartbeatPing(context)
        _bridgeState.value = _bridgeState.value.copy(isHeartbeatMode = false)

        val port = start(context)
        recordPacket(
            type = "HEARTBEAT_EXIT",
            sizeBytes = 0,
            summary = "Restored full HTTP Chrome bridge on port $port"
        )
        return port
    }

    /**
     * Records a low-power heartbeat pulse packet to trace background vitality.
     */
    fun recordHeartbeatPing() {
        recordPacket(
            type = "HEARTBEAT_PING",
            sizeBytes = 0,
            summary = "Background heartbeat pulse OK"
        )
    }

    /**
     * Constructs the local bridge authentication URL with the active ephemeral port and session nonce.
     */
    fun getBridgeUrl(): String {
        val state = _bridgeState.value
        return "http://127.0.0.1:${state.boundPort}/?nonce=${state.sessionNonce}"
    }

    /**
     * Passively stages chat data, messages, and URL payload into the local socket buffer and disk cache.
     * The data sits ready in the local socket buffer for when the user manually opens Chrome later.
     * Never fires Intent(Intent.ACTION_VIEW) or startActivity().
     */
    fun stageChatAndUrlPayload(
        context: Context,
        chatSession: ChatSession? = stagedChatSession,
        messages: List<ChatMessage> = stagedMessages,
        urlPayload: String? = null,
        bridgeJsonPayload: String? = null
    ): String = synchronized(stateLock) {
        if (!_bridgeState.value.isRunning && !_bridgeState.value.isHeartbeatMode) {
            bindAndListen(context)
        }

        if (chatSession != null) {
            stagedChatSession = chatSession
        }
        if (messages.isNotEmpty() || chatSession != null) {
            stagedMessages = messages
        }

        val activeChat = stagedChatSession
        val activeMessages = stagedMessages
        val baseUrl = getBridgeUrl()
        val resolvedUrl = when {
            !urlPayload.isNullOrBlank() -> urlPayload
            stagedUrlPayload.isNotBlank() && chatSession == null -> stagedUrlPayload
            activeChat != null -> "$baseUrl&chatId=${activeChat.id}&chatTitle=${Uri.encode(activeChat.title)}&wakeWord=${Uri.encode(activeChat.wakeWord)}"
            else -> baseUrl
        }
        stagedUrlPayload = resolvedUrl

        val now = System.currentTimeMillis()
        val jsonString = bridgeJsonPayload ?: buildStagedPayloadJson(
            chat = activeChat,
            messages = activeMessages,
            stagedUrl = resolvedUrl,
            timestamp = now
        )
        stagedJsonPayload = jsonString

        writeCacheRecord(context, STAGED_PAYLOAD_FILENAME, jsonString)
        val payloadBytes = jsonString.toByteArray(Charsets.UTF_8)

        recordPacket(
            type = "CHROME_BRIDGE_STAGED",
            sizeBytes = payloadBytes.size,
            summary = "Passively staged chat '${activeChat?.title ?: "Default"}' (${activeMessages.size} msgs) & URL payload in local socket buffer"
        )

        _bridgeState.value = _bridgeState.value.copy(
            stagedChatId = activeChat?.id ?: 0L,
            stagedChatTitle = activeChat?.title ?: "",
            stagedUrlPayload = resolvedUrl,
            stagedMessagesCount = activeMessages.size,
            lastStagedTimestamp = now,
            bufferedPacketsCount = packetRingBuffer.size(),
            cacheDirSizeBytes = calculateCacheDirSize(context)
        )
        return resolvedUrl
    }

    /**
     * Passively stages a normalized URL payload in the local socket buffer without launching Chrome.
     */
    fun stageUrlPayload(context: Context, normalizedUrl: String): String {
        return stageChatAndUrlPayload(
            context = context,
            chatSession = stagedChatSession,
            messages = stagedMessages,
            urlPayload = normalizedUrl
        )
    }

    private fun buildStagedPayloadJson(
        chat: ChatSession?,
        messages: List<ChatMessage>,
        stagedUrl: String,
        timestamp: Long
    ): String {
        val msgArray = JSONArray()
        messages.forEach { msg ->
            msgArray.put(
                JSONObject().apply {
                    put("id", msg.id)
                    put("chatId", msg.chatId)
                    put("sender", msg.sender)
                    put("content", msg.content)
                    put("timestamp", msg.timestamp)
                    put("triggeredByWakeWord", msg.triggeredByWakeWord)
                }
            )
        }
        return JSONObject().apply {
            put("status", "staged")
            put("boundPort", _bridgeState.value.boundPort)
            put("chatId", chat?.id ?: 0L)
            put("chatTitle", chat?.title ?: "Gemini Chat")
            put("wakeWord", chat?.wakeWord ?: "Hey Gemini")
            put("systemInstruction", chat?.systemInstruction ?: "")
            put("stagedUrl", stagedUrl)
            put("stagedAt", timestamp)
            put("messages", msgArray)
        }.toString()
    }

    /**
     * Passively stages the authenticated bridge URL and optional [chatSession] in the local socket buffer.
     * NEVER launches Google Chrome via Intent(Intent.ACTION_VIEW) or context.startActivity().
     */
    fun launchChromeBridge(context: Context, chatSession: ChatSession? = null): Boolean {
        stageChatAndUrlPayload(
            context = context,
            chatSession = chatSession ?: stagedChatSession,
            messages = stagedMessages
        )
        return _bridgeState.value.isRunning
    }

    /**
     * Accept loop for inbound loopback HTTP connections.
     */
    private fun listenLoop(socket: ServerSocket, expectedNonce: String, context: Context) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                activeClientSockets.add(client)
                updateStateCounters(context)

                managerScope.launch {
                    handleClientConnection(client, expectedNonce, context)
                }
            } catch (se: SocketException) {
                // Socket was closed by forceResetAndClear or shutdown
                break
            } catch (e: Exception) {
                Log.e(TAG, "Error accepting client: ${e.message}")
            }
        }
    }

    /**
     * Handles an incoming client connection with nonce verification and standard HTTP responses.
     */
    private fun handleClientConnection(client: Socket, expectedNonce: String, context: Context) {
        try {
            client.soTimeout = 10000 // 10s socket read timeout
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val writer = PrintWriter(client.getOutputStream(), true)

            val requestLine = reader.readLine() ?: ""
            val headers = mutableListOf<String>()
            var line: String? = reader.readLine()
            while (!line.isNullOrBlank()) {
                headers.add(line)
                line = reader.readLine()
            }

            val requestSize = requestLine.length + headers.sumOf { it.length }

            // Extract nonce from request line (e.g. GET /?nonce=abc HTTP/1.1) or header
            val nonceInUrl = Regex("""[?&]nonce=([a-fA-F0-9]+)""").find(requestLine)?.groupValues?.get(1)
            val nonceInHeader = headers.firstOrNull { it.startsWith("X-Bridge-Nonce:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()

            val providedNonce = nonceInUrl ?: nonceInHeader

            if (providedNonce == null || !providedNonce.equals(expectedNonce, ignoreCase = true)) {
                // Cryptographic nonce authentication failed: return 403 Forbidden
                val forbiddenBody = "403 Forbidden: Invalid or missing bridge session nonce."
                writer.print("HTTP/1.1 403 Forbidden\r\n")
                writer.print("Content-Type: text/plain\r\n")
                writer.print("Content-Length: ${forbiddenBody.length + 1}\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.println(forbiddenBody)
                writer.flush()

                recordPacket(
                    type = "AUTH_FORBIDDEN",
                    sizeBytes = requestSize,
                    summary = "Rejected unauthorized client connection (nonce mismatch)"
                )
            } else if (requestLine.contains(" $ROUTE_SCREENSHOT", ignoreCase = true) || requestLine.startsWith("GET $ROUTE_SCREENSHOT", ignoreCase = true) || requestLine.startsWith("POST $ROUTE_SCREENSHOT", ignoreCase = true)) {
                // Multi-stream binary screenshot route (ROUTE_SCREENSHOT)
                if (requestLine.startsWith("POST", ignoreCase = true)) {
                    val contentLength = headers.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                        ?.substringAfter(":")?.trim()?.toIntOrNull() ?: 0
                    if (contentLength > 0) {
                        val rawInput = client.getInputStream()
                        val buffer = ByteArray(contentLength)
                        var totalRead = 0
                        while (totalRead < contentLength) {
                            val count = rawInput.read(buffer, totalRead, contentLength - totalRead)
                            if (count == -1) break
                            totalRead += count
                        }
                        if (totalRead > 0) {
                            GeminiCouplerBridge.stageImageBytes(context, buffer.copyOf(totalRead))
                            recordPacket(
                                type = "SCREENSHOT_STREAM_IN",
                                sizeBytes = totalRead,
                                summary = "Received direct binary screenshot stream ($totalRead bytes)"
                            )
                        }
                    }
                    val okBody = "{\"status\":\"OK\",\"message\":\"Screenshot staged successfully\"}"
                    writer.print("HTTP/1.1 200 OK\r\n")
                    writer.print("Content-Type: application/json\r\n")
                    writer.print("Content-Length: ${okBody.length + 1}\r\n")
                    writer.print("Access-Control-Allow-Origin: *\r\n")
                    writer.print("Connection: close\r\n\r\n")
                    writer.println(okBody)
                    writer.flush()
                } else {
                    // GET /screenshot
                    val screenshotBytes = GeminiCouplerBridge.stagedScreenshotBytes
                    if (screenshotBytes != null) {
                        writer.print("HTTP/1.1 200 OK\r\n")
                        writer.print("Content-Type: image/jpeg\r\n")
                        writer.print("Content-Length: ${screenshotBytes.size}\r\n")
                        writer.print("Access-Control-Allow-Origin: *\r\n")
                        writer.print("Connection: close\r\n\r\n")
                        writer.flush()
                        client.getOutputStream().write(screenshotBytes)
                        client.getOutputStream().flush()

                        recordPacket(
                            type = "SCREENSHOT_STREAM_OUT",
                            sizeBytes = screenshotBytes.size,
                            summary = "Direct multi-stream binary screenshot dispatch (${screenshotBytes.size} bytes)"
                        )
                    } else {
                        val notFoundBody = "404 No staged screenshot currently available."
                        writer.print("HTTP/1.1 404 Not Found\r\n")
                        writer.print("Content-Type: text/plain\r\n")
                        writer.print("Content-Length: ${notFoundBody.length + 1}\r\n")
                        writer.print("Connection: close\r\n\r\n")
                        writer.println(notFoundBody)
                        writer.flush()
                    }
                }
            } else if (requestLine.contains(" $ROUTE_STAGED", ignoreCase = true) || requestLine.contains(" $ROUTE_SYNC", ignoreCase = true)) {
                val jsonPayload = stagedJsonPayload.ifBlank {
                    buildStagedPayloadJson(
                        chat = stagedChatSession,
                        messages = stagedMessages,
                        stagedUrl = stagedUrlPayload.ifBlank { getBridgeUrl() },
                        timestamp = System.currentTimeMillis()
                    )
                }
                val responseBytes = jsonPayload.toByteArray(Charsets.UTF_8)
                writer.print("HTTP/1.1 200 OK\r\n")
                writer.print("Content-Type: application/json; charset=UTF-8\r\n")
                writer.print("Content-Length: ${responseBytes.size}\r\n")
                writer.print("Access-Control-Allow-Origin: *\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.flush()
                client.getOutputStream().write(responseBytes)
                client.getOutputStream().flush()

                recordPacket(
                    type = "STAGED_PAYLOAD_SERVED",
                    sizeBytes = requestSize + responseBytes.size,
                    summary = "Served passively staged chat & URL payload (HTTP 200)"
                )
            } else {
                // Authenticated bridge response
                val htmlResponse = buildBridgeDashboardHtml(_bridgeState.value.boundPort, expectedNonce)
                val responseBytes = htmlResponse.toByteArray(Charsets.UTF_8)

                writer.print("HTTP/1.1 200 OK\r\n")
                writer.print("Content-Type: text/html; charset=UTF-8\r\n")
                writer.print("Content-Length: ${responseBytes.size}\r\n")
                writer.print("Connection: close\r\n\r\n")
                writer.flush()
                client.getOutputStream().write(responseBytes)
                client.getOutputStream().flush()

                recordPacket(
                    type = "HTTP_SERVED",
                    sizeBytes = requestSize + responseBytes.size,
                    summary = "Served Chrome Bridge handshake dashboard (HTTP 200)"
                )

                // Write exchange record to local bridge cache
                writeCacheRecord(context, "client_handshake_${System.currentTimeMillis()}.log", "Handshake OK - Port: ${_bridgeState.value.boundPort}")
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client socket handled/closed: ${e.message}")
        } finally {
            activeClientSockets.remove(client)
            try {
                client.close()
            } catch (_: Exception) {}
            updateStateCounters(context)
        }
    }

    private fun buildBridgeDashboardHtml(port: Int, nonce: String): String {
        val state = _bridgeState.value
        val stagedChatTitle = state.stagedChatTitle.ifBlank { "Gemini Chat" }
        val stagedUrl = state.stagedUrlPayload.ifBlank { "http://127.0.0.1:$port/?nonce=$nonce" }
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Gemini Wake - Chrome Coupler Bridge</title>
                <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #0f172a; color: #f8fafc; padding: 24px; margin: 0; }
                    .card { background: #1e293b; border-radius: 12px; padding: 20px; max-width: 600px; margin: 0 auto; box-shadow: 0 4px 6px -1px rgba(0,0,0,0.3); border: 1px solid #334155; }
                    .badge { display: inline-block; background: #22c55e; color: #052e16; font-weight: bold; font-size: 12px; padding: 4px 10px; border-radius: 9999px; }
                    .code { font-family: monospace; background: #0f172a; padding: 8px 12px; border-radius: 6px; color: #38bdf8; word-break: break-all; margin: 8px 0; }
                    h2 { margin-top: 0; color: #e2e8f0; }
                    p { line-height: 1.5; color: #94a3b8; }
                </style>
            </head>
            <body>
                <div class="card">
                    <span class="badge">BRIDGE ACTIVE</span>
                    <h2>Gemini Wake Chrome Coupler</h2>
                    <p>Dynamic loopback socket connected successfully.</p>
                    <p><strong>Ephemeral Port:</strong> $port</p>
                    <p><strong>Staged Chat:</strong> $stagedChatTitle (${state.stagedMessagesCount} messages)</p>
                    <p><strong>Staged URL Payload:</strong></p>
                    <div class="code">$stagedUrl</div>
                    <p><strong>Session Nonce:</strong></p>
                    <div class="code">$nonce</div>
                    <p>Ready to synchronize prompts, snapshots, and piggyback task completions with Gemini Web (gemini.google.com).</p>
                </div>
            </body>
            </html>
        """.trimIndent()
    }

    private fun recordPacket(type: String, sizeBytes: Int, summary: String) {
        val packet = BridgePacket(
            id = packetIdCounter.getAndIncrement(),
            timestamp = System.currentTimeMillis(),
            type = type,
            sizeBytes = sizeBytes,
            summary = summary
        )
        packetRingBuffer.add(packet)
    }

    private fun getCacheDir(context: Context): File {
        return File(context.cacheDir, BRIDGE_CACHE_DIR)
    }

    private fun writeCacheRecord(context: Context, filename: String, content: String) {
        try {
            val dir = getCacheDir(context)
            if (!dir.exists()) dir.mkdirs()
            File(dir, filename).writeText(content)
        } catch (_: Exception) {}
    }

    private fun calculateCacheDirSize(context: Context): Long {
        return try {
            val dir = getCacheDir(context)
            if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
        } catch (_: Exception) {
            0L
        }
    }

    private fun updateStateCounters(context: Context) {
        _bridgeState.value = _bridgeState.value.copy(
            activeClientsCount = activeClientSockets.size,
            bufferedPacketsCount = packetRingBuffer.size(),
            cacheDirSizeBytes = calculateCacheDirSize(context)
        )
    }

    /**
     * Ingests a raw binary framed socket packet without string encoding overhead.
     * Frame type 0x02 for binary screenshot bitmaps, 0x01 for text frames.
     */
    fun processBinaryFramedPacket(context: Context, frameType: Byte, payload: ByteArray) {
        if (frameType == FRAME_TYPE_SCREENSHOT) {
            GeminiCouplerBridge.stageImageBytes(context, payload)
            recordPacket(
                type = "FRAME_SCREENSHOT_IN",
                sizeBytes = payload.size,
                summary = "Multi-stream binary framed screenshot ingested directly (${payload.size} bytes)"
            )
        } else if (frameType == FRAME_TYPE_TEXT) {
            recordPacket(
                type = "FRAME_TEXT_IN",
                sizeBytes = payload.size,
                summary = "Multi-stream binary text frame received (${payload.size} bytes)"
            )
        }
    }

    /**
     * Packages a binary payload into a framed packet:
     * [1 byte frameType] [4 bytes big-endian length] [binary payload bytes]
     */
    fun createBinaryFrame(frameType: Byte, payload: ByteArray): ByteArray {
        val frame = ByteArray(1 + 4 + payload.size)
        frame[0] = frameType
        val len = payload.size
        frame[1] = ((len shr 24) and 0xFF).toByte()
        frame[2] = ((len shr 16) and 0xFF).toByte()
        frame[3] = ((len shr 8) and 0xFF).toByte()
        frame[4] = (len and 0xFF).toByte()
        System.arraycopy(payload, 0, frame, 5, payload.size)
        return frame
    }

    fun getBufferedPackets(): List<BridgePacket> = packetRingBuffer.toList()

    fun shutdown() {
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        acceptJob?.cancel()
        _bridgeState.value = _bridgeState.value.copy(isRunning = false)
    }
}
