package com.example.ui.auth

import android.content.Context
import android.os.CancellationSignal
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CreateCredentialRequest
import androidx.credentials.CreateCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.PrepareGetCredentialResponse
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.db.AppDatabase
import com.example.data.model.ChatMessage
import com.example.data.model.ChatSession
import com.example.data.model.SyncStatus
import com.example.data.model.TriggerType
import com.example.data.model.WakeWord
import com.example.data.repository.ChatRepository
import com.example.data.repository.WakeWordRepository
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleAuthManagerTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var chatRepository: ChatRepository
    private lateinit var wakeWordRepository: WakeWordRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        chatRepository = ChatRepository(database.chatDao())
        wakeWordRepository = WakeWordRepository(database.wakeWordDao(), database.chatDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `silent and interactive GetCredentialRequest builders each contain exactly one option`() {
        val webClientId = GoogleAuthManager.resolveWebClientId(context)
        assertNotNull("default_web_client_id should be resolved from resources", webClientId)

        val silentRequest = GoogleAuthManager.buildSilentAutoSignInRequest(webClientId!!)
        assertEquals(1, silentRequest.credentialOptions.size)
        val silentOption = silentRequest.credentialOptions.first()
        assertTrue(silentOption is GetGoogleIdOption)
        val googleIdOption = silentOption as GetGoogleIdOption
        assertEquals(webClientId, googleIdOption.serverClientId)
        assertTrue(googleIdOption.filterByAuthorizedAccounts)
        assertTrue(googleIdOption.autoSelectEnabled)

        val interactiveRequest = GoogleAuthManager.buildInteractiveSignInRequest(webClientId)
        assertEquals(1, interactiveRequest.credentialOptions.size)
        val interactiveOption = interactiveRequest.credentialOptions.first()
        assertTrue(interactiveOption is GetSignInWithGoogleOption)
        val signInWithGoogleOption = interactiveOption as GetSignInWithGoogleOption
        assertEquals(webClientId, signInWithGoogleOption.serverClientId)
    }

    @Test
    fun `attemptAutoSignIn returns Cancelled on NoCredentialException and interactive sign-in handles cancellation vs error`() = runTest {
        val noCredentialManager = FakeCredentialManager(
            getExceptionToThrow = NoCredentialException("No saved Google account")
        )
        val autoResult = GoogleAuthManager.attemptAutoSignIn(
            activityContext = context,
            credentialManager = noCredentialManager
        )
        assertTrue(autoResult is AuthResult.Cancelled)
        assertFalse(GoogleAuthManager.authUiState.value.isLoading)

        val cancelledManager = FakeCredentialManager(
            getExceptionToThrow = GetCredentialCancellationException("User dismissed bottom sheet")
        )
        val cancelledInteractive = GoogleAuthManager.onGoogleSignInClicked(
            activityContext = context,
            credentialManager = cancelledManager
        )
        assertTrue(cancelledInteractive is AuthResult.Cancelled)
        assertFalse(GoogleAuthManager.authUiState.value.isLoading)

        val errorManager = FakeCredentialManager(
            getExceptionToThrow = GetCredentialUnknownException("Network unreachable")
        )
        val errorInteractive = GoogleAuthManager.onGoogleSignInClicked(
            activityContext = context,
            credentialManager = errorManager
        )
        assertTrue(errorInteractive is AuthResult.Error)
        assertEquals("Network unreachable", (errorInteractive as AuthResult.Error).message)
    }

    @Test
    fun `signOut clears CredentialManager state and preserves Room cache for account switch re-sync`() = runTest {
        // 1. User A creates and syncs chat, message, and wake word in local Room cache
        val chatId = chatRepository.saveChat(
            ChatSession(
                title = "Voice Research",
                wakeWord = "Hey Research",
                isDefaultWakeChat = true,
                isPersistent = true
            )
        )
        val msgId = chatRepository.insertMessage(
            ChatMessage(
                chatId = chatId,
                sender = "user",
                content = "Summarize my voice notes",
                timestamp = 1_700_000_000_000L,
                updatedAt = 1_700_000_000_000L
            )
        )
        val wakeId = wakeWordRepository.addCustomChatWakeWord(
            chatId = chatId,
            chatTitle = "Voice Research",
            phrase = "Hey Research",
            sensitivity = 0.80f
        )

        chatRepository.markChatSynced(chatId, "chat_$chatId", "user_A", 1_700_000_001_000L)
        chatRepository.markMessageSynced(msgId, "msg_$msgId", "user_A", 1_700_000_001_000L)
        wakeWordRepository.markWakeWordSynced(wakeId, "wake_$wakeId", "user_A", 1_700_000_001_000L)

        assertTrue(chatRepository.getPendingSyncChats().isEmpty())
        assertTrue(chatRepository.getPendingSyncMessages().isEmpty())
        assertTrue(wakeWordRepository.getPendingSyncWakeWords().isEmpty())

        // 2. Sign out of User A
        val fakeManager = FakeCredentialManager()
        GoogleAuthManager.signOut(context, fakeManager)
        assertTrue("CredentialManager.clearCredentialState should be invoked on sign-out", fakeManager.clearCredentialCalled)

        // Local Room cache must still be preserved after sign-out
        val cachedChatsAfterSignOut = chatRepository.getAllChatsSync()
        assertEquals(1, cachedChatsAfterSignOut.size)
        assertEquals("Voice Research", cachedChatsAfterSignOut.first().title)

        // 3. User B signs in -> rebindLocalRecordsForUser marks cached records PENDING for User B
        chatRepository.rebindLocalRecordsForUser("user_B")
        wakeWordRepository.rebindLocalRecordsForUser("user_B")

        val pendingChatsForB = chatRepository.getPendingSyncChats()
        val pendingMessagesForB = chatRepository.getPendingSyncMessages()
        val pendingWakeWordsForB = wakeWordRepository.getPendingSyncWakeWords()

        assertEquals(1, pendingChatsForB.size)
        assertEquals("user_B", pendingChatsForB.first().userId)
        assertEquals(SyncStatus.PENDING, pendingChatsForB.first().syncStatus)

        assertEquals(1, pendingMessagesForB.size)
        assertEquals("user_B", pendingMessagesForB.first().userId)
        assertEquals(SyncStatus.PENDING, pendingMessagesForB.first().syncStatus)

        assertEquals(1, pendingWakeWordsForB.size)
        assertEquals("user_B", pendingWakeWordsForB.first().userId)
        assertEquals(SyncStatus.PENDING, pendingWakeWordsForB.first().syncStatus)
    }

    private class FakeCredentialManager(
        private val getExceptionToThrow: GetCredentialException? = null
    ) : CredentialManager {
        var clearCredentialCalled: Boolean = false

        override fun getCredentialAsync(
            context: Context,
            request: GetCredentialRequest,
            cancellationSignal: CancellationSignal?,
            executor: Executor,
            callback: CredentialManagerCallback<GetCredentialResponse, GetCredentialException>
        ) {
            executor.execute {
                val ex = getExceptionToThrow ?: NoCredentialException("No credential configured in FakeCredentialManager")
                callback.onError(ex)
            }
        }

        override fun getCredentialAsync(
            context: Context,
            pendingGetCredentialHandle: PrepareGetCredentialResponse.PendingGetCredentialHandle,
            cancellationSignal: CancellationSignal?,
            executor: Executor,
            callback: CredentialManagerCallback<GetCredentialResponse, GetCredentialException>
        ) {
            executor.execute {
                val ex = getExceptionToThrow ?: NoCredentialException("No credential configured in FakeCredentialManager")
                callback.onError(ex)
            }
        }

        override fun clearCredentialStateAsync(
            request: ClearCredentialStateRequest,
            cancellationSignal: CancellationSignal?,
            executor: Executor,
            callback: CredentialManagerCallback<Void?, ClearCredentialException>
        ) {
            executor.execute {
                clearCredentialCalled = true
                callback.onResult(null)
            }
        }

        override fun createCredentialAsync(
            context: Context,
            request: CreateCredentialRequest,
            cancellationSignal: CancellationSignal?,
            executor: Executor,
            callback: CredentialManagerCallback<CreateCredentialResponse, CreateCredentialException>
        ) {
            throw UnsupportedOperationException()
        }

        override fun prepareGetCredentialAsync(
            request: GetCredentialRequest,
            cancellationSignal: CancellationSignal?,
            executor: Executor,
            callback: CredentialManagerCallback<PrepareGetCredentialResponse, GetCredentialException>
        ) {
            throw UnsupportedOperationException()
        }

        override fun createSettingsPendingIntent(): android.app.PendingIntent {
            throw UnsupportedOperationException()
        }
    }
}
