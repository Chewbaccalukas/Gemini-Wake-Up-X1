package com.example.bridge

import android.content.Context
import com.example.coupler.ChromeBridgeSocketManager
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChromeBridgePackage(
    val bridgeId: String,
    val chatTitle: String,
    val wakeWord: String,
    val systemInstruction: String,
    val messages: List<ChatMessage>,
    val exportTimestamp: Long = System.currentTimeMillis()
)

class ChromeAppBridge(private val context: Context) {

    /**
     * Serializes chat thread into a structured sync package.
     */
    fun createBridgePayload(chat: ChatSession, messages: List<ChatMessage>): String {
        val root = JSONObject().apply {
            put("bridgeVersion", "1.0")
            put("bridgeId", "gemini_wake_${chat.id}")
            put("chatId", chat.id)
            put("chatTitle", chat.title)
            put("wakeWord", chat.wakeWord)
            put("systemInstruction", chat.systemInstruction)
            put("exportedAt", System.currentTimeMillis())

            val msgArray = JSONArray()
            messages.forEach { msg ->
                val validTimestamp = if (msg.timestamp > 0L) msg.timestamp else System.currentTimeMillis()
                val obj = JSONObject().apply {
                    put("id", msg.id)
                    put("sender", msg.sender)
                    put("content", msg.content)
                    put("timestamp", validTimestamp)
                    put("triggeredByWakeWord", msg.triggeredByWakeWord)
                }
                msgArray.put(obj)
            }
            put("messages", msgArray)
        }
        return root.toString(2)
    }

    /**
     * Parses a sync package exported from Chrome.
     */
    fun parseBridgePayload(jsonString: String): ChromeBridgePackage? {
        return try {
            val root = JSONObject(jsonString)
            val title = root.optString("chatTitle", "Chrome Synced Chat")
            val wakeWord = root.optString("wakeWord", "Hey Gemini")
            val system = root.optString("systemInstruction", "You are Gemini.")
            val bridgeId = root.optString("bridgeId", "gemini_wake_synced")
            val exportedAt = root.optLong("exportedAt", System.currentTimeMillis()).let {
                if (it > 0L) it else System.currentTimeMillis()
            }

            val msgArray = root.optJSONArray("messages") ?: JSONArray()
            val list = mutableListOf<ChatMessage>()
            for (i in 0 until msgArray.length()) {
                val obj = msgArray.getJSONObject(i)
                val rawTimestamp = obj.optLong("timestamp", 0L)
                val resolvedTimestamp = if (rawTimestamp > 0L) rawTimestamp else System.currentTimeMillis()
                list.add(
                    ChatMessage(
                        id = obj.optLong("id"),
                        chatId = 0L,
                        sender = obj.optString("sender"),
                        content = obj.optString("content"),
                        timestamp = resolvedTimestamp,
                        triggeredByWakeWord = obj.optBoolean("triggeredByWakeWord", false)
                    )
                )
            }

            ChromeBridgePackage(
                bridgeId = bridgeId,
                chatTitle = title,
                wakeWord = wakeWord,
                systemInstruction = system,
                messages = list,
                exportTimestamp = exportedAt
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Generates a self-contained Chrome HTML5 Companion Web Page containing the preloaded chat thread.
     * When opened in Google Chrome, it immediately renders the entire thread with zero delay,
     * connects automatically to the background local sync relay, and supports two-way live sync.
     */
    fun generateChromeCompanionHtml(chat: ChatSession, messages: List<ChatMessage>): File {
        val payloadJson = createBridgePayload(chat, messages)
        val escapedPayload = payloadJson
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("$", "\\$")

        val html = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0">
  <title>Gemini Wake — ${chat.title} (Chrome Auto-Sync Bridge)</title>
  <style>
    :root {
      --bg: #0A0F1D;
      --surface: #111827;
      --surface-variant: #1F2937;
      --primary: #38BDF8;
      --secondary: #A855F7;
      --text: #F3F4F6;
      --text-muted: #9CA3AF;
      --user-bubble: #1E3A8A;
      --gemini-bubble: #1F2937;
    }
    body {
      margin: 0;
      padding: 0;
      background-color: var(--bg);
      color: var(--text);
      font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
      display: flex;
      flex-direction: column;
      height: 100vh;
    }
    header {
      background: var(--surface);
      border-bottom: 1px solid #374151;
      padding: 12px 20px;
      display: flex;
      align-items: center;
      justify-content: space-between;
      flex-wrap: wrap;
      gap: 10px;
    }
    .header-left {
      display: flex;
      align-items: center;
      gap: 12px;
    }
    .icon-badge {
      width: 36px;
      height: 36px;
      border-radius: 10px;
      background: linear-gradient(135deg, var(--primary), var(--secondary));
      display: flex;
      align-items: center;
      justify-content: center;
      font-size: 20px;
    }
    .title-group h1 {
      margin: 0;
      font-size: 1.1rem;
    }
    .wake-chip {
      display: inline-block;
      font-size: 0.75rem;
      background: #0284C7;
      color: white;
      padding: 2px 8px;
      border-radius: 12px;
      margin-top: 2px;
    }
    .bridge-pill {
      background: rgba(56, 189, 248, 0.15);
      border: 1px solid var(--primary);
      color: var(--primary);
      padding: 4px 10px;
      border-radius: 14px;
      font-size: 0.8rem;
      display: flex;
      align-items: center;
      gap: 6px;
      transition: all 0.3s ease;
    }
    #chat-container {
      flex: 1;
      overflow-y: auto;
      padding: 20px;
      display: flex;
      flex-direction: column;
      gap: 16px;
    }
    .msg {
      max-width: 80%;
      padding: 12px 16px;
      border-radius: 16px;
      line-height: 1.5;
      font-size: 0.95rem;
      position: relative;
      word-break: break-word;
    }
    .msg.user {
      align-self: flex-end;
      background: var(--user-bubble);
      color: white;
      border-bottom-right-radius: 4px;
    }
    .msg.gemini {
      align-self: flex-start;
      background: var(--gemini-bubble);
      color: var(--text);
      border-bottom-left-radius: 4px;
      border-left: 3px solid var(--primary);
    }
    .msg-time {
      font-size: 0.7rem;
      color: var(--text-muted);
      margin-top: 6px;
      text-align: right;
    }
    footer {
      background: var(--surface);
      border-top: 1px solid #374151;
      padding: 12px 20px;
      display: flex;
      gap: 12px;
      align-items: center;
    }
    input[type="text"] {
      flex: 1;
      background: var(--surface-variant);
      border: 1px solid #4B5563;
      padding: 12px 16px;
      border-radius: 24px;
      color: white;
      font-size: 0.95rem;
      outline: none;
    }
    input[type="text"]:focus {
      border-color: var(--primary);
    }
    button {
      background: var(--primary);
      color: #0F172A;
      font-weight: bold;
      border: none;
      padding: 10px 18px;
      border-radius: 20px;
      cursor: pointer;
      transition: opacity 0.2s;
    }
    button:hover {
      opacity: 0.9;
    }
    .export-btn {
      background: var(--secondary);
      color: white;
    }
  </style>
</head>
<body>
  <header>
    <div class="header-left">
      <div class="icon-badge">✨</div>
      <div class="title-group">
        <h1>${chat.title}</h1>
        <div class="wake-chip">🎙️ Wake: "${chat.wakeWord}"</div>
      </div>
    </div>
    <div style="display:flex; gap:8px; align-items:center;">
      <div class="bridge-pill" id="sync-status">⚡ Connecting Auto-Sync Relay...</div>
      <button class="export-btn" onclick="exportToApp()">📥 Export File</button>
    </div>
  </header>

  <div id="chat-container"></div>

  <footer>
    <input type="text" id="prompt-input" placeholder="Type a message to auto-sync to Gemini Wake..." onkeydown="if(event.key==='Enter') sendMsg()" />
    <button onclick="sendMsg()">Send</button>
  </footer>

  <script>
    const threadData = $escapedPayload;
    const container = document.getElementById('chat-container');
    const RELAY_URLS = ['http://localhost:8765/sync', 'http://127.0.0.1:8765/sync', 'http://10.0.2.2:8765/sync'];
    let activeRelayUrl = null;

    function renderMessages() {
      container.innerHTML = '';
      threadData.messages.forEach(msg => {
        const div = document.createElement('div');
        div.className = 'msg ' + (msg.sender === 'user' ? 'user' : 'gemini');
        div.innerHTML = '<div>' + escapeHtml(msg.content) + '</div>' +
          '<div class="msg-time">' + new Date(msg.timestamp).toLocaleTimeString() + '</div>';
        container.appendChild(div);
      });
      container.scrollTop = container.scrollHeight;
    }

    function escapeHtml(text) {
      const p = document.createElement('p');
      p.textContent = text;
      return p.innerHTML;
    }

    async function sendMsg() {
      const input = document.getElementById('prompt-input');
      const text = input.value.trim();
      if (!text) return;
      input.value = '';

      const now = Date.now();
      const userMsg = {
        id: now,
        chatId: threadData.chatId,
        sender: 'user',
        content: text,
        timestamp: now,
        triggeredByWakeWord: false
      };

      threadData.messages.push(userMsg);
      renderMessages();

      // Automatically relay to Android app in background
      if (activeRelayUrl) {
        try {
          await fetch(activeRelayUrl, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(userMsg)
          });
        } catch (e) {
          console.warn('Background relay POST error', e);
        }
      }
    }

    async function trySyncWithApp() {
      for (const url of RELAY_URLS) {
        try {
          const controller = new AbortController();
          const timeoutId = setTimeout(() => controller.abort(), 1800);
          const res = await fetch(url, { method: 'GET', headers: { 'Accept': 'application/json' }, signal: controller.signal });
          clearTimeout(timeoutId);

          if (res.ok) {
            activeRelayUrl = url;
            const data = await res.json();
            if (data && data.messages && data.messages.length > threadData.messages.length) {
              threadData.messages = data.messages;
              renderMessages();
            }
            updateStatusPill(true);
            return;
          }
        } catch (e) {
          // continue checking next host
        }
      }
      updateStatusPill(false);
    }

    function updateStatusPill(online) {
      const pill = document.getElementById('sync-status');
      if (pill) {
        if (online) {
          pill.innerHTML = '⚡ Auto-Sync Active (Live Relay)';
          pill.style.borderColor = '#10B981';
          pill.style.color = '#10B981';
          pill.style.background = 'rgba(16, 185, 129, 0.15)';
        } else {
          pill.innerHTML = '⚡ Middle Cache Preloaded';
          pill.style.borderColor = '#38BDF8';
          pill.style.color = '#38BDF8';
          pill.style.background = 'rgba(56, 189, 248, 0.15)';
        }
      }
    }

    function exportToApp() {
      const dataStr = "data:text/json;charset=utf-8," + encodeURIComponent(JSON.stringify(threadData, null, 2));
      const downloadAnchor = document.createElement('a');
      downloadAnchor.setAttribute("href", dataStr);
      downloadAnchor.setAttribute("download", "gemini_wake_thread_" + threadData.chatId + ".json");
      document.body.appendChild(downloadAnchor);
      downloadAnchor.click();
      downloadAnchor.remove();

      navigator.clipboard.writeText(JSON.stringify(threadData, null, 2))
        .then(() => alert("Bridge Sync Payload copied to clipboard! In Gemini Wake, tap 'Sync from Chrome'."))
        .catch(() => alert("Saved sync file. Use 'Sync from Chrome' in the app."));
    }

    renderMessages();
    setInterval(trySyncWithApp, 2500);
    trySyncWithApp();
  </script>
</body>
</html>
        """.trimIndent()

        val bridgeDir = File(context.cacheDir, "chrome_bridge").apply {
            if (!exists()) mkdirs()
        }
        val file = File(bridgeDir, "gemini_chrome_bridge_${chat.id}.html")
        file.writeText(html)
        return file
    }

    /**
     * Passively stages the companion HTML and JSON payload in the local bridge cache and
     * [ChromeBridgeSocketManager] socket buffer without launching any external browser activity.
     */
    fun stageChatForChrome(
        chat: ChatSession,
        messages: List<ChatMessage>,
        stagedUrl: String? = null
    ): File {
        val htmlFile = generateChromeCompanionHtml(chat, messages)
        val jsonPayload = createBridgePayload(chat, messages)
        val bridgeDir = File(context.cacheDir, "chrome_bridge").apply {
            if (!exists()) mkdirs()
        }
        File(bridgeDir, "gemini_chrome_bridge_${chat.id}.json").writeText(jsonPayload)
        ChromeBridgeSocketManager.stageChatAndUrlPayload(
            context = context,
            chatSession = chat,
            messages = messages,
            urlPayload = stagedUrl,
            bridgeJsonPayload = jsonPayload
        )
        return htmlFile
    }

    /**
     * Passively stages the companion HTML and JSON payload in the local Chrome Bridge buffer.
     * Never fires Intent(Intent.ACTION_VIEW) or startActivity().
     */
    fun openChatInChrome(chat: ChatSession, messages: List<ChatMessage>) {
        stageChatForChrome(chat, messages)
    }
}
