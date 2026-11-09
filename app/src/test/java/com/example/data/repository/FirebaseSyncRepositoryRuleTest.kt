package com.example.data.repository

import com.example.base.FirestoreEmulatorTestBase
import com.example.data.firebase.FirestoreChatDoc
import com.example.data.firebase.FirestoreMessageDoc
import com.example.data.firebase.FirestoreWakeWordDoc
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.TriggerType
import com.example.data.model.WakeWord
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class FirebaseSyncRepositoryRuleTest : FirestoreEmulatorTestBase() {

    private lateinit var repository: FirebaseSyncRepository

    @Before
    override fun setUp() {
        super.setUp()
        repository = FirebaseSyncRepository(firestore)
    }

    @Test
    fun `authenticated owner can create, observe, update, and delete chats, messages, and wake words`() = runBlocking {
        withTimeout(15_000L) {
            val uid = signInAndGetUid()
            val now = 1730000000000L

            // 1. ChatSession CRUD + observe
            val chat = ChatSession(
                id = 10L,
                title = "Architecture Review",
                wakeWord = "Hey Architect",
                isDefaultWakeChat = true,
                isPersistent = true,
                systemInstruction = "Help review distributed systems.",
                iconName = "Code",
                themeColorHex = "#38BDF8",
                createdAt = now,
                lastUpdatedAt = now,
                totalTrackedSeconds = 42L
            )
            val chatDocId = repository.upsertChat(uid, chat)
            assertEquals("chat_10", chatDocId)

            val observedChats = repository.observeChats(uid)
                .first { list -> list.any { it.id == chatDocId } }
            val loadedChatDoc = observedChats.first { it.id == chatDocId }
            assertEquals("Architecture Review", loadedChatDoc.title)
            assertEquals("Hey Architect", loadedChatDoc.wakeWord)

            repository.updateChatTitleAndWakeWord(
                userId = uid,
                docId = chatDocId,
                title = "Updated Architecture Review",
                wakeWord = "Code Mode"
            )
            val updatedChatDoc = repository.getChat(uid, chatDocId)
            assertNotNull(updatedChatDoc)
            assertEquals("Updated Architecture Review", updatedChatDoc?.title)
            assertEquals("Code Mode", updatedChatDoc?.wakeWord)

            // 2. ChatMessage CRUD + observe
            val message = ChatMessage(
                id = 201L,
                chatId = 10L,
                sender = "user",
                content = "Check my security rules and Room cache sync.",
                timestamp = now + 1000L,
                triggeredByWakeWord = true
            )
            val msgDocId = repository.upsertMessage(uid, message)
            assertEquals("msg_201", msgDocId)

            val observedMessages = repository.observeMessages(uid)
                .first { list -> list.any { it.id == msgDocId } }
            val loadedMsgDoc = observedMessages.first { it.id == msgDocId }
            assertEquals("Check my security rules and Room cache sync.", loadedMsgDoc.content)

            repository.updateMessageContent(uid, msgDocId, "Edited message text")
            val updatedMsgDoc = repository.getMessage(uid, msgDocId)
            assertEquals("Edited message text", updatedMsgDoc?.content)

            // 3. WakeWord CRUD + observe
            val wakeWord = WakeWord(
                id = 301L,
                phrase = "Code Mode",
                triggerType = TriggerType.CHAT,
                metadata = "Updated Architecture Review",
                commandRole = WakeWord.ROLE_CHAT_WAKE,
                chatId = 10L,
                chatTitle = "Updated Architecture Review",
                isEnabled = true,
                sensitivity = 0.85f,
                createdAt = now,
                updatedAt = now
            )
            val wakeDocId = repository.upsertWakeWord(uid, wakeWord)
            assertEquals("wake_301", wakeDocId)

            val observedWakeWords = repository.observeWakeWords(uid)
                .first { list -> list.any { it.id == wakeDocId } }
            val loadedWakeDoc = observedWakeWords.first { it.id == wakeDocId }
            assertEquals("Code Mode", loadedWakeDoc.phrase)

            repository.updateWakeWordSensitivityAndState(
                userId = uid,
                docId = wakeDocId,
                phrase = "Deep Code Mode",
                isEnabled = false,
                sensitivity = 0.90
            )
            val updatedWakeDoc = repository.getWakeWord(uid, wakeDocId)
            assertEquals("Deep Code Mode", updatedWakeDoc?.phrase)
            assertEquals(false, updatedWakeDoc?.isEnabled)

            // 4. Delete all three
            repository.deleteMessageByDocId(uid, msgDocId)
            assertNull(repository.getMessage(uid, msgDocId))

            repository.deleteWakeWordByDocId(uid, wakeDocId)
            assertNull(repository.getWakeWord(uid, wakeDocId))

            repository.deleteChatByDocId(uid, chatDocId)
            assertNull(repository.getChat(uid, chatDocId))
        }
    }

    @Test
    fun `cross-user access is denied by security rules`() = runBlocking {
        withTimeout(15_000L) {
            val aliceUid = signInAndGetUid()
            val chat = ChatSession(
                id = 55L,
                title = "Alice Private Chat",
                wakeWord = "Hey Alice"
            )
            val docId = repository.upsertChat(aliceUid, chat)

            // Switch to Bob
            val bobUid = signInAndGetUid()
            try {
                repository.getChat(aliceUid, docId)
                fail("Expected PERMISSION_DENIED when Bob reads Alice's chat")
            } catch (t: Throwable) {
                val fsEx = unwrapFirestoreException(t)
                assertNotNull("Expected FirebaseFirestoreException in cause chain, got $t", fsEx)
                assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
            }

            try {
                repository.deleteChatByDocId(aliceUid, docId)
                fail("Expected PERMISSION_DENIED when Bob deletes Alice's chat")
            } catch (t: Throwable) {
                val fsEx = unwrapFirestoreException(t)
                assertNotNull("Expected FirebaseFirestoreException in cause chain, got $t", fsEx)
                assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
            }

            // Bob also cannot create a document in his own path with spoofed userId = aliceUid
            try {
                val spoofedDoc = FirestoreChatDoc.fromChatSession(aliceUid, chat).copy(userId = aliceUid)
                firestore.document("users/$bobUid/chats/spoofed_chat").set(spoofedDoc).await()
                fail("Expected PERMISSION_DENIED when Bob spoofs userId")
            } catch (t: Throwable) {
                val fsEx = unwrapFirestoreException(t)
                assertNotNull("Expected FirebaseFirestoreException in cause chain, got $t", fsEx)
                assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
            }
        }
    }

    @Test
    fun `unauthenticated user is denied by security rules`() = runBlocking {
        withTimeout(15_000L) {
            auth.signOut()
            val chatDoc = FirestoreChatDoc.fromChatSession(
                userId = "unauth_user",
                chat = ChatSession(id = 1L, title = "Unauth Chat", wakeWord = "Hey Gemini")
            )
            try {
                repository.createChatDoc("unauth_user", chatDoc)
                fail("Expected PERMISSION_DENIED for unauthenticated createChatDoc")
            } catch (t: Throwable) {
                val fsEx = unwrapFirestoreException(t)
                assertNotNull("Expected FirebaseFirestoreException in cause chain, got $t", fsEx)
                assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
            }

            val msgDoc = FirestoreMessageDoc.fromChatMessage(
                userId = "unauth_user",
                msg = ChatMessage(id = 1L, chatId = 1L, sender = "user", content = "Hello")
            )
            try {
                repository.createMessageDoc("unauth_user", msgDoc)
                fail("Expected PERMISSION_DENIED for unauthenticated createMessageDoc")
            } catch (t: Throwable) {
                val fsEx = unwrapFirestoreException(t)
                assertNotNull("Expected FirebaseFirestoreException in cause chain, got $t", fsEx)
                assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
            }

            val wakeDoc = FirestoreWakeWordDoc.fromWakeWord(
                userId = "unauth_user",
                wakeWord = WakeWord(id = 1L, phrase = "Hey Gemini")
            )
            try {
                repository.createWakeWordDoc("unauth_user", wakeDoc)
                fail("Expected PERMISSION_DENIED for unauthenticated createWakeWordDoc")
            } catch (t: Throwable) {
                val fsEx = unwrapFirestoreException(t)
                assertNotNull("Expected FirebaseFirestoreException in cause chain, got $t", fsEx)
                assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
            }
        }
    }
}
