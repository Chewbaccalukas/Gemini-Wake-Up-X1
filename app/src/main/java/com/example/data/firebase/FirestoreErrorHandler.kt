package com.example.data.firebase

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write")
}

fun handleFirestoreError(
    exception: Exception,
    operationType: OperationType,
    path: String?
): Exception {
    val currentUser = try {
        Firebase.auth.currentUser
    } catch (_: Exception) {
        null
    }

    val providerInfoArray = JSONArray()
    currentUser?.providerData?.forEach { userInfo ->
        val providerObj = JSONObject().apply {
            put("providerId", userInfo.providerId)
            put("displayName", userInfo.displayName)
            put("email", userInfo.email)
            put("photoUrl", userInfo.photoUrl?.toString())
        }
        providerInfoArray.put(providerObj)
    }

    val authInfoObj = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("isAnonymous", currentUser?.isAnonymous)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", providerInfoArray)
    }

    val errInfo = JSONObject().apply {
        put("error", exception.message ?: exception.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoObj)
    }

    val jsonString = errInfo.toString()
    Log.e("FirestoreError", jsonString, exception)

    return RuntimeException(jsonString, exception)
}
