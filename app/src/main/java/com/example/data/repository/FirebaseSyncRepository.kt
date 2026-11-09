package com.example.data.repository

import android.content.Context
import com.example.R
import com.example.data.firebase.FirestoreChatDoc
import com.example.data.firebase.FirestoreMessageDoc
import com.example.data.firebase.FirestoreWakeWordDoc
import com.example.data.firebase.OperationType
import com.example.data.firebase.handleFirestoreError
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.WakeWord
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.snapshots
import com.google.firebase.firestore.toObject
import com.google.firebase.firestore.toObjects
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Cloud Firestore repository syncing user-owned chats, messages, and wake words
 * under `/users/{userId}/chats`, `/users/{userId}/messages`, and `/users/{userId}/wake_words`.
 *
 * Always resolves the named Firestore database ID from `R.string.firestore_database_id` when
 * constructed from an Android [Context], and accepts an injected [FirebaseFirestore] instance
 * for unit and emulator rule tests.
 */
class FirebaseSyncRepository(
    private val firestore: FirebaseFirestore
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun chatsPath(userId: String) = "users/$userId/chats"
    private fun messagesPath(userId: String) = "users/$userId/messages"
    private fun wakeWordsPath(userId: String) = "users/$userId/wake_words"

    // =========================================================================
    // Chats (/users/{userId}/chats/{chatId})
    // =========================================================================

    fun observeChats(userId: String): Flow<List<FirestoreChatDoc>> {
        val path = chatsPath(userId)
        return firestore.collection(path)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
            .snapshots()
            .map { snapshot -> snapshot.toObjects<FirestoreChatDoc>() }
            .catch { e ->
                if (e is Exception && e !is CancellationException) {
                    throw handleFirestoreError(e, OperationType.LIST, path)
                } else {
                    throw e
                }
            }
    }

    suspend fun getChat(userId: String, docId: String): FirestoreChatDoc? {
        val path = "${chatsPath(userId)}/$docId"
        return try {
            val snap = firestore.document(path).get().await()
            if (snap.exists()) snap.toObject<FirestoreChatDoc>() else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.GET, path)
        }
    }

    suspend fun upsertChat(userId: String, chat: ChatSession): String {
        val doc = FirestoreChatDoc.fromChatSession(userId, chat)
        return saveChatDoc(userId, doc)
    }

    suspend fun saveChatDoc(userId: String, doc: FirestoreChatDoc): String {
        val docId = doc.id.ifBlank { FirestoreChatDoc.docIdForChat(doc.localChatId) }
        val path = "${chatsPath(userId)}/$docId"
        return try {
            val docRef = firestore.document(path)
            val existing = docRef.get().await()
            if (existing.exists()) {
                val updates = mapOf(
                    "title" to doc.title,
                    "wakeWord" to doc.wakeWord,
                    "systemInstruction" to doc.systemInstruction,
                    "themeColorHex" to doc.themeColorHex,
                    "iconName" to doc.iconName,
                    "isDefaultWakeChat" to doc.isDefaultWakeChat,
                    "isPersistent" to doc.isPersistent,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            } else {
                docRef.set(doc.copy(id = docId, userId = userId)).await()
            }
            docId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.WRITE, path)
        }
    }

    suspend fun createChatDoc(userId: String, doc: FirestoreChatDoc): String {
        val docId = doc.id.ifBlank { FirestoreChatDoc.docIdForChat(doc.localChatId) }
        val path = "${chatsPath(userId)}/$docId"
        return try {
            firestore.document(path).set(doc.copy(id = docId, userId = userId)).await()
            docId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.CREATE, path)
        }
    }

    suspend fun updateChatTitleAndWakeWord(
        userId: String,
        docId: String,
        title: String,
        wakeWord: String
    ) {
        val path = "${chatsPath(userId)}/$docId"
        try {
            firestore.document(path).update(
                mapOf(
                    "title" to title.take(199),
                    "wakeWord" to wakeWord.take(119),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.UPDATE, path)
        }
    }

    suspend fun deleteChat(userId: String, localChatId: Long) {
        deleteChatByDocId(userId, FirestoreChatDoc.docIdForChat(localChatId))
    }

    suspend fun deleteChatByDocId(userId: String, docId: String) {
        val path = "${chatsPath(userId)}/$docId"
        try {
            firestore.document(path).delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.DELETE, path)
        }
    }

    // =========================================================================
    // Messages (/users/{userId}/messages/{messageId})
    // =========================================================================

    fun observeMessages(userId: String, limit: Long = 100L): Flow<List<FirestoreMessageDoc>> {
        val path = messagesPath(userId)
        return firestore.collection(path)
            .orderBy("messageTimestamp", Query.Direction.DESCENDING)
            .limit(limit)
            .snapshots()
            .map { snapshot -> snapshot.toObjects<FirestoreMessageDoc>() }
            .catch { e ->
                if (e is Exception && e !is CancellationException) {
                    throw handleFirestoreError(e, OperationType.LIST, path)
                } else {
                    throw e
                }
            }
    }

    suspend fun getMessage(userId: String, docId: String): FirestoreMessageDoc? {
        val path = "${messagesPath(userId)}/$docId"
        return try {
            val snap = firestore.document(path).get().await()
            if (snap.exists()) snap.toObject<FirestoreMessageDoc>() else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.GET, path)
        }
    }

    suspend fun upsertMessage(userId: String, message: ChatMessage): String {
        val doc = FirestoreMessageDoc.fromChatMessage(userId, message)
        return createMessageDoc(userId, doc)
    }

    suspend fun createMessageDoc(userId: String, doc: FirestoreMessageDoc): String {
        val docId = doc.id.ifBlank { FirestoreMessageDoc.docIdForMessage(doc.localMessageId) }
        val path = "${messagesPath(userId)}/$docId"
        return try {
            firestore.document(path).set(doc.copy(id = docId, userId = userId)).await()
            docId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.CREATE, path)
        }
    }

    suspend fun updateMessageContent(userId: String, docId: String, content: String) {
        val path = "${messagesPath(userId)}/$docId"
        try {
            firestore.document(path).update(
                mapOf(
                    "content" to content.take(19999),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.UPDATE, path)
        }
    }

    suspend fun deleteMessage(userId: String, localMessageId: Long) {
        deleteMessageByDocId(userId, FirestoreMessageDoc.docIdForMessage(localMessageId))
    }

    suspend fun deleteMessageByDocId(userId: String, docId: String) {
        val path = "${messagesPath(userId)}/$docId"
        try {
            firestore.document(path).delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.DELETE, path)
        }
    }

    // =========================================================================
    // Wake Words (/users/{userId}/wake_words/{wakeWordId})
    // =========================================================================

    fun observeWakeWords(userId: String): Flow<List<FirestoreWakeWordDoc>> {
        val path = wakeWordsPath(userId)
        return firestore.collection(path)
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .snapshots()
            .map { snapshot -> snapshot.toObjects<FirestoreWakeWordDoc>() }
            .catch { e ->
                if (e is Exception && e !is CancellationException) {
                    throw handleFirestoreError(e, OperationType.LIST, path)
                } else {
                    throw e
                }
            }
    }

    suspend fun getWakeWord(userId: String, docId: String): FirestoreWakeWordDoc? {
        val path = "${wakeWordsPath(userId)}/$docId"
        return try {
            val snap = firestore.document(path).get().await()
            if (snap.exists()) snap.toObject<FirestoreWakeWordDoc>() else null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.GET, path)
        }
    }

    suspend fun upsertWakeWord(userId: String, wakeWord: WakeWord): String {
        val doc = FirestoreWakeWordDoc.fromWakeWord(userId, wakeWord)
        return saveWakeWordDoc(userId, doc)
    }

    suspend fun saveWakeWordDoc(userId: String, doc: FirestoreWakeWordDoc): String {
        val docId = doc.id.ifBlank { FirestoreWakeWordDoc.docIdForWakeWord(doc.localWakeWordId) }
        val path = "${wakeWordsPath(userId)}/$docId"
        return try {
            val docRef = firestore.document(path)
            val existing = docRef.get().await()
            if (existing.exists()) {
                val updates = mapOf(
                    "phrase" to doc.phrase,
                    "triggerType" to doc.triggerType,
                    "chatId" to doc.chatId,
                    "chatTitle" to doc.chatTitle,
                    "isEnabled" to doc.isEnabled,
                    "sensitivity" to doc.sensitivity,
                    "metadata" to doc.metadata,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                docRef.update(updates).await()
            } else {
                docRef.set(doc.copy(id = docId, userId = userId)).await()
            }
            docId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.WRITE, path)
        }
    }

    suspend fun createWakeWordDoc(userId: String, doc: FirestoreWakeWordDoc): String {
        val docId = doc.id.ifBlank { FirestoreWakeWordDoc.docIdForWakeWord(doc.localWakeWordId) }
        val path = "${wakeWordsPath(userId)}/$docId"
        return try {
            firestore.document(path).set(doc.copy(id = docId, userId = userId)).await()
            docId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.CREATE, path)
        }
    }

    suspend fun updateWakeWordSensitivityAndState(
        userId: String,
        docId: String,
        phrase: String,
        isEnabled: Boolean,
        sensitivity: Double
    ) {
        val path = "${wakeWordsPath(userId)}/$docId"
        try {
            firestore.document(path).update(
                mapOf(
                    "phrase" to phrase.take(119),
                    "isEnabled" to isEnabled,
                    "sensitivity" to sensitivity.coerceIn(0.50, 0.98),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.UPDATE, path)
        }
    }

    suspend fun deleteWakeWord(userId: String, localWakeWordId: Long) {
        deleteWakeWordByDocId(userId, FirestoreWakeWordDoc.docIdForWakeWord(localWakeWordId))
    }

    suspend fun deleteWakeWordByDocId(userId: String, docId: String) {
        val path = "${wakeWordsPath(userId)}/$docId"
        try {
            firestore.document(path).delete().await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw handleFirestoreError(e, OperationType.DELETE, path)
        }
    }

    // =========================================================================
    // Explicit Cloud Backup Vault Operations (Restore to Device & Delete from Cloud)
    // =========================================================================

    data class CloudChatBackupBundle(
        val chatDoc: FirestoreChatDoc,
        val messages: List<FirestoreMessageDoc>,
        val wakeWords: List<FirestoreWakeWordDoc>
    )

    /**
     * Fetches the chat document, all associated messages, and wake words from Firestore
     * so they can be explicitly restored into the local Room database on user demand.
     */
    suspend fun fetchChatBackupBundle(
        userId: String,
        chatDoc: FirestoreChatDoc
    ): CloudChatBackupBundle {
        val resolvedSession = chatDoc.toChatSession()
        val targetLocalChatId = if (chatDoc.localChatId > 0L) chatDoc.localChatId else resolvedSession.id
        val docId = chatDoc.id.ifBlank { FirestoreChatDoc.docIdForChat(targetLocalChatId) }

        val latestChatDoc = runCatching { getChat(userId, docId) }.getOrNull() ?: chatDoc

        val messages = mutableListOf<FirestoreMessageDoc>()
        if (targetLocalChatId > 0L) {
            val userMessagesPath = messagesPath(userId)
            val queriedMessages = try {
                firestore.collection(userMessagesPath)
                    .whereEqualTo("localChatId", targetLocalChatId)
                    .get()
                    .await()
                    .toObjects<FirestoreMessageDoc>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.LIST, userMessagesPath)
                emptyList()
            }
            messages.addAll(queriedMessages)
        }

        val wakeWords = mutableListOf<FirestoreWakeWordDoc>()
        if (targetLocalChatId > 0L) {
            val userWakePath = wakeWordsPath(userId)
            val queriedWakeWords = try {
                firestore.collection(userWakePath)
                    .whereEqualTo("chatId", targetLocalChatId)
                    .get()
                    .await()
                    .toObjects<FirestoreWakeWordDoc>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.LIST, userWakePath)
                emptyList()
            }
            wakeWords.addAll(queriedWakeWords)
        }

        return CloudChatBackupBundle(
            chatDoc = latestChatDoc,
            messages = messages.distinctBy { it.id.ifBlank { "${it.localMessageId}_${it.messageTimestamp}" } }
                .sortedBy { it.messageTimestamp },
            wakeWords = wakeWords.distinctBy { it.id.ifBlank { it.phrase.lowercase() } }
        )
    }

    /**
     * Permanently deletes the user-scoped chat document, its associated messages,
     * and associated wake words from Cloud Firestore when the user confirms "Delete from Cloud".
     */
    suspend fun deleteChatBackupFromCloud(
        userId: String,
        chatDoc: FirestoreChatDoc,
        cachedMessagesForChat: List<FirestoreMessageDoc> = emptyList(),
        cachedWakeWordsForChat: List<FirestoreWakeWordDoc> = emptyList()
    ) {
        val resolvedSession = chatDoc.toChatSession()
        val targetLocalChatId = if (chatDoc.localChatId > 0L) chatDoc.localChatId else resolvedSession.id
        val primaryDocId = chatDoc.id.ifBlank { FirestoreChatDoc.docIdForChat(targetLocalChatId) }
        val fallbackDocId = if (targetLocalChatId > 0L) FirestoreChatDoc.docIdForChat(targetLocalChatId) else ""

        // 1. Delete associated messages from /users/{userId}/messages
        val messageDocIdsToDelete = mutableSetOf<String>()
        cachedMessagesForChat.mapNotNullTo(messageDocIdsToDelete) {
            it.id.ifBlank { FirestoreMessageDoc.docIdForMessage(it.localMessageId) }.takeIf { id -> id.isNotBlank() }
        }
        if (targetLocalChatId > 0L) {
            runCatching {
                val msgSnapshot = firestore.collection(messagesPath(userId))
                    .whereEqualTo("localChatId", targetLocalChatId)
                    .get()
                    .await()
                for (doc in msgSnapshot.documents) {
                    messageDocIdsToDelete.add(doc.id)
                }
            }
        }
        for (msgDocId in messageDocIdsToDelete) {
            runCatching { deleteMessageByDocId(userId, msgDocId) }
        }

        // 2. Delete associated wake words from /users/{userId}/wake_words
        val wakeDocIdsToDelete = mutableSetOf<String>()
        cachedWakeWordsForChat.mapNotNullTo(wakeDocIdsToDelete) {
            it.id.ifBlank { FirestoreWakeWordDoc.docIdForWakeWord(it.localWakeWordId) }.takeIf { id -> id.isNotBlank() }
        }
        if (targetLocalChatId > 0L) {
            runCatching {
                val wakeSnapshot = firestore.collection(wakeWordsPath(userId))
                    .whereEqualTo("chatId", targetLocalChatId)
                    .get()
                    .await()
                for (doc in wakeSnapshot.documents) {
                    wakeDocIdsToDelete.add(doc.id)
                }
            }
        }
        for (wakeDocId in wakeDocIdsToDelete) {
            runCatching { deleteWakeWordByDocId(userId, wakeDocId) }
        }

        // 3. Delete the user-scoped chat document itself (/users/{userId}/chats/{docId})
        if (primaryDocId.isNotBlank()) {
            deleteChatByDocId(userId, primaryDocId)
        }
        if (fallbackDocId.isNotBlank() && fallbackDocId != primaryDocId) {
            runCatching { deleteChatByDocId(userId, fallbackDocId) }
        }
    }
}
