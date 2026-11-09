package com.example.importer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.IBinder
import android.provider.OpenableColumns
import android.util.Log
import com.example.data.db.AppDatabase
import com.example.ui.AttachmentPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Background processing service that receives a file URI (.md, .txt, .pdf, .doc, .html, .json)
 * from the file picker intent handler in MainActivity and processes it asynchronously
 * (either importing conversation turns into Room or staging the document for chat context).
 */
class FileProcessingService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): FileProcessingService = this@FileProcessingService
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeWorker: ChatImportWorker? = null
    private var activeJob: Job? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val uri = extractFileUri(intent)
        if (uri == null) {
            Log.w(TAG, "FileProcessingService started without a valid file URI")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val action = intent?.action ?: ACTION_PROCESS_FILE_IMPORT
        val rawChatId = intent?.getLongExtra(EXTRA_TARGET_CHAT_ID, -1L) ?: -1L
        val targetChatId = if (rawChatId > 0L) rawChatId else null

        _lastProcessedUri.value = uri

        activeJob = serviceScope.launch {
            try {
                when (action) {
                    ACTION_PROCESS_FILE_CONTEXT -> {
                        processFileForContext(uri)
                    }
                    else -> {
                        processFileForImport(uri, targetChatId)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing file URI: $uri", e)
                _serviceProgressState.value = ImportProgressState(
                    isImporting = false,
                    error = "Background processing failed: ${e.message}"
                )
            } finally {
                stopSelfResult(startId)
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun processFileForImport(uri: Uri, targetChatId: Long?) {
        val db = AppDatabase.getDatabase(applicationContext, serviceScope)
        val worker = ChatImportWorker(applicationContext, db.chatDao())
        activeWorker = worker

        val progressCollector = serviceScope.launch {
            worker.progressState.collect { state ->
                _serviceProgressState.value = state
            }
        }

        try {
            val createdChatId = worker.importChatArchive(uri, targetChatId)
            if (createdChatId != null && createdChatId > 0L) {
                _completedImportChatId.value = createdChatId
            }
        } finally {
            progressCollector.cancel()
        }
    }

    private fun processFileForContext(uri: Uri) {
        val resolver = applicationContext.contentResolver
        val fileName = resolveFileName(applicationContext, uri)
        val mimeType = resolveMimeType(applicationContext, uri, fileName)
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: byteArrayOf()
        val payload = AttachmentPayload(
            uri = uri,
            fileName = fileName,
            mimeType = mimeType,
            bytes = bytes,
            sizeBytes = bytes.size.toLong()
        )
        _stagedAttachmentFlow.value = payload
        _serviceProgressState.value = ImportProgressState(
            isImporting = false,
            percentage = 1f,
            statusMessage = "Staged $fileName (${bytes.size} bytes) for chat context."
        )
    }

    override fun onDestroy() {
        activeWorker?.cancelImport()
        activeJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FileProcessingService"

        const val ACTION_PROCESS_FILE_IMPORT = "com.example.importer.ACTION_PROCESS_FILE_IMPORT"
        const val ACTION_PROCESS_FILE_CONTEXT = "com.example.importer.ACTION_PROCESS_FILE_CONTEXT"
        const val ACTION_CANCEL_PROCESSING = "com.example.importer.ACTION_CANCEL_PROCESSING"

        const val EXTRA_FILE_URI = "com.example.importer.EXTRA_FILE_URI"
        const val EXTRA_TARGET_CHAT_ID = "com.example.importer.EXTRA_TARGET_CHAT_ID"

        /**
         * Supported file extensions: .md, .txt, .pdf, .doc (.docx), .html (.htm), and .json
         */
        val SUPPORTED_EXTENSIONS: Set<String> = setOf(
            "md",
            "markdown",
            "txt",
            "pdf",
            "doc",
            "docx",
            "html",
            "htm",
            "json"
        )

        /**
         * MIME types passed to Intent.EXTRA_MIME_TYPES for filtering .md, .txt, .pdf, .doc, .html, and .json files.
         */
        val SUPPORTED_MIME_TYPES: Array<String> = arrayOf(
            "text/markdown",
            "text/x-markdown",
            "text/plain",
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/html",
            "application/json",
            "application/octet-stream"
        )

        private val _serviceProgressState = MutableStateFlow(ImportProgressState())
        val serviceProgressState: StateFlow<ImportProgressState> = _serviceProgressState.asStateFlow()

        private val _completedImportChatId = MutableStateFlow<Long?>(null)
        val completedImportChatId: StateFlow<Long?> = _completedImportChatId.asStateFlow()

        private val _stagedAttachmentFlow = MutableStateFlow<AttachmentPayload?>(null)
        val stagedAttachmentFlow: StateFlow<AttachmentPayload?> = _stagedAttachmentFlow.asStateFlow()

        private val _lastProcessedUri = MutableStateFlow<Uri?>(null)
        val lastProcessedUri: StateFlow<Uri?> = _lastProcessedUri.asStateFlow()

        fun consumeCompletedImportChatId() {
            _completedImportChatId.value = null
        }

        fun consumeStagedAttachment() {
            _stagedAttachmentFlow.value = null
        }

        /**
         * Creates an ACTION_OPEN_DOCUMENT file picker Intent filtered for
         * .md, .txt, .pdf, .doc, .html, and .json files.
         */
        fun createFilePickerIntent(): Intent {
            return Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, SUPPORTED_MIME_TYPES)
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            }
        }

        /**
         * Extracts the file URI from either Intent.data or EXTRA_FILE_URI.
         */
        fun extractFileUri(intent: Intent?): Uri? {
            if (intent == null) return null
            intent.data?.let { return it }
            val uriString = intent.getStringExtra(EXTRA_FILE_URI)
            if (!uriString.isNullOrBlank()) {
                return Uri.parse(uriString)
            }
            @Suppress("DEPRECATION")
            return intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

        /**
         * Resolves the display name of a content or file URI.
         */
        fun resolveFileName(context: Context, uri: Uri): String {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            val displayName = cursor.getString(nameIndex)
                            if (!displayName.isNullOrBlank()) {
                                return displayName
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            return uri.lastPathSegment?.substringAfterLast('/') ?: "document"
        }

        /**
         * Resolves the MIME type of a file URI, falling back to extension mapping when needed.
         */
        fun resolveMimeType(context: Context, uri: Uri, fileName: String = resolveFileName(context, uri)): String {
            val resolverType = try {
                context.contentResolver.getType(uri)
            } catch (_: Exception) {
                null
            }
            if (!resolverType.isNullOrBlank() && resolverType != "application/octet-stream") {
                return resolverType
            }
            val ext = fileName.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "md", "markdown" -> "text/markdown"
                "txt" -> "text/plain"
                "pdf" -> "application/pdf"
                "doc" -> "application/msword"
                "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                "html", "htm" -> "text/html"
                "json" -> "application/json"
                else -> resolverType ?: "application/octet-stream"
            }
        }

        /**
         * Checks whether a file name or MIME type matches the supported formats:
         * .md, .txt, .pdf, .doc (.docx), .html (.htm), or .json.
         */
        fun isSupportedFile(fileName: String?, mimeType: String?): Boolean {
            val cleanName = fileName?.trim().orEmpty()
            val ext = cleanName.substringAfterLast('.', "").lowercase()
            if (ext in SUPPORTED_EXTENSIONS) {
                return true
            }
            val lowerMime = mimeType?.lowercase().orEmpty()
            return lowerMime == "text/markdown" ||
                lowerMime == "text/x-markdown" ||
                lowerMime == "text/plain" ||
                lowerMime == "application/pdf" ||
                lowerMime == "application/msword" ||
                lowerMime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                lowerMime == "text/html" ||
                lowerMime == "application/json"
        }

        /**
         * Starts FileProcessingService in the background with the given file URI.
         */
        fun startServiceForUri(
            context: Context,
            uri: Uri,
            targetChatId: Long? = null,
            action: String = ACTION_PROCESS_FILE_IMPORT
        ): Intent {
            val serviceIntent = Intent(context, FileProcessingService::class.java).apply {
                this.action = action
                this.data = uri
                putExtra(EXTRA_FILE_URI, uri.toString())
                if (targetChatId != null && targetChatId > 0L) {
                    putExtra(EXTRA_TARGET_CHAT_ID, targetChatId)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startService(serviceIntent)
            return serviceIntent
        }
    }
}
