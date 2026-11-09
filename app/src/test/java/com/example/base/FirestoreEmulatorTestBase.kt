package com.example.base

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.INSTRUMENTATION_TEST)
abstract class FirestoreEmulatorTestBase {

    protected lateinit var firestore: FirebaseFirestore
    protected lateinit var auth: FirebaseAuth
    protected lateinit var databaseId: String

    companion object {
        const val PROJECT_ID = "demo-no-project"
        const val FIRESTORE_HOST = "127.0.0.1"
        const val FIRESTORE_PORT = 8085
        const val AUTH_HOST = "127.0.0.1"
        const val AUTH_PORT = 9099
    }

    @Before
    open fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        if (FirebaseApp.getApps(context).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setProjectId(PROJECT_ID)
                .setApplicationId("1:1234567890:android:abcdef")
                .setApiKey("fake-api-key-for-emulator")
                .build()
            FirebaseApp.initializeApp(context, options)
        }

        val resId = context.resources.getIdentifier("firestore_database_id", "string", context.packageName)
        databaseId = if (resId != 0) context.getString(resId) else "(default)"

        clearFirestoreEmulatorData()

        firestore = if (resId != 0) {
            FirebaseFirestore.getInstance(databaseId)
        } else {
            FirebaseFirestore.getInstance()
        }

        try {
            firestore.useEmulator(FIRESTORE_HOST, FIRESTORE_PORT)
            val settings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
                .build()
            firestore.firestoreSettings = settings
        } catch (_: IllegalStateException) {
            // Already configured in this process
        }

        auth = FirebaseAuth.getInstance()
        try {
            auth.useEmulator(AUTH_HOST, AUTH_PORT)
        } catch (_: IllegalStateException) {
            // Already configured in this process
        }
        auth.signOut()
    }

    @After
    open fun tearDown() {
        auth.signOut()
    }

    protected suspend fun signInAndGetUid(): String = withTimeout(10_000L) {
        val email = "test-${UUID.randomUUID()}@example.com"
        val result = auth.createUserWithEmailAndPassword(email, "password123").await()
        requireNotNull(result.user?.uid) { "Emulator Auth failed to return a UID" }
    }

    protected fun unwrapFirestoreException(t: Throwable): FirebaseFirestoreException? {
        var current: Throwable? = t
        while (current != null) {
            if (current is FirebaseFirestoreException) return current
            current = current.cause
        }
        return null
    }

    private fun clearFirestoreEmulatorData() {
        val url = URL("http://$FIRESTORE_HOST:$FIRESTORE_PORT/emulator/v1/projects/$PROJECT_ID/databases/$databaseId/documents")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = 2000
            readTimeout = 2000
        }
        try {
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }
}
