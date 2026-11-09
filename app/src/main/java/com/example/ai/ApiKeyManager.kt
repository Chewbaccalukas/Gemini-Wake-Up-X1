package com.example.ai

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ActiveKeyType(val label: String, val shortLabel: String) {
    PRIMARY("Primary Gemini API Key", "Primary 🔑"),
    BACKUP("Backup Gemini API Key", "Backup ⚡")
}

/**
 * ApiKeyManager provides secure dual-key storage and dynamic 429 / RESOURCE_EXHAUSTED / spend cap failover.
 * Manages Primary vs Backup API Keys and supports haptic feedback upon key switching.
 */
class ApiKeyManager private constructor(private val context: Context) {

    private val prefs = context.getSharedPreferences("gemini_api_keys_pref", Context.MODE_PRIVATE)

    private val _activeKeyType = MutableStateFlow(
        try {
            val saved = prefs.getString("active_key_type", ActiveKeyType.PRIMARY.name)
            ActiveKeyType.valueOf(saved ?: ActiveKeyType.PRIMARY.name)
        } catch (_: Exception) {
            ActiveKeyType.PRIMARY
        }
    )
    val activeKeyType: StateFlow<ActiveKeyType> = _activeKeyType.asStateFlow()

    private val _primaryKeyFlow = MutableStateFlow(getPrimaryApiKey())
    val primaryKeyFlow: StateFlow<String> = _primaryKeyFlow.asStateFlow()

    private val _backupKeyFlow = MutableStateFlow(getBackupApiKey())
    val backupKeyFlow: StateFlow<String> = _backupKeyFlow.asStateFlow()

    companion object {
        @Volatile
        private var instance: ApiKeyManager? = null

        fun getInstance(context: Context): ApiKeyManager {
            return instance ?: synchronized(this) {
                instance ?: ApiKeyManager(context.applicationContext).also { instance = it }
            }
        }
    }

    fun getPrimaryApiKey(): String {
        return prefs.getString("primary_gemini_api_key", "") ?: ""
    }

    fun setPrimaryApiKey(key: String) {
        val trimmed = key.trim()
        prefs.edit().putString("primary_gemini_api_key", trimmed).apply()
        _primaryKeyFlow.value = trimmed
    }

    fun getBackupApiKey(): String {
        return prefs.getString("backup_gemini_api_key", "") ?: ""
    }

    fun setBackupApiKey(key: String) {
        val trimmed = key.trim()
        prefs.edit().putString("backup_gemini_api_key", trimmed).apply()
        _backupKeyFlow.value = trimmed
    }

    fun getActiveApiKey(): String {
        val primary = getPrimaryApiKey()
        val backup = getBackupApiKey()
        return if (_activeKeyType.value == ActiveKeyType.PRIMARY) {
            primary.ifBlank { backup }
        } else {
            backup.ifBlank { primary }
        }
    }

    /**
     * Inspects throwable to determine if it is HTTP 429, RESOURCE_EXHAUSTED, spend cap, or rate limit error.
     */
    fun isQuotaOr429Error(e: Throwable): Boolean {
        val msg = (e.message ?: "") + " " + (e.cause?.message ?: "") + " " + e.toString()
        val lower = msg.lowercase()
        return lower.contains("429") ||
                lower.contains("resource_exhausted") ||
                lower.contains("quota") ||
                lower.contains("spend cap") ||
                lower.contains("rate limit") ||
                lower.contains("too many requests") ||
                lower.contains("limit_exceeded") ||
                lower.contains("statuscode: 429")
    }

    /**
     * Automatically triggers failover when primary key is exhausted.
     * Toggles active key to Backup, triggers a brief haptic vibration, and shows Toast.
     * Returns true if failover to backup key was activated.
     */
    fun handleQuotaExhaustionAndFailover(): Boolean {
        val currentType = _activeKeyType.value
        val backupKey = getBackupApiKey()

        if (currentType == ActiveKeyType.PRIMARY && backupKey.isNotBlank()) {
            _activeKeyType.value = ActiveKeyType.BACKUP
            prefs.edit().putString("active_key_type", ActiveKeyType.BACKUP.name).apply()
            triggerHapticFeedback()
            try {
                Toast.makeText(context, "HTTP 429 Quota Exhausted! Switched to Backup API Key", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
            Log.w("ApiKeyManager", "HTTP 429 / RESOURCE_EXHAUSTED: Switched active key to Backup API Key!")
            return true
        } else if (currentType == ActiveKeyType.BACKUP && getPrimaryApiKey().isNotBlank()) {
            _activeKeyType.value = ActiveKeyType.PRIMARY
            prefs.edit().putString("active_key_type", ActiveKeyType.PRIMARY.name).apply()
            triggerHapticFeedback()
            try {
                Toast.makeText(context, "Backup Key Exhausted! Switched back to Primary API Key", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
            return true
        }
        return false
    }

    fun resetToPrimary() {
        _activeKeyType.value = ActiveKeyType.PRIMARY
        prefs.edit().putString("active_key_type", ActiveKeyType.PRIMARY.name).apply()
        triggerHapticFeedback()
        try {
            Toast.makeText(context, "Reset to Primary Gemini API Key 🔑", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    fun toggleActiveKey() {
        val newType = if (_activeKeyType.value == ActiveKeyType.PRIMARY) ActiveKeyType.BACKUP else ActiveKeyType.PRIMARY
        _activeKeyType.value = newType
        prefs.edit().putString("active_key_type", newType.name).apply()
        triggerHapticFeedback()
        try {
            val label = if (newType == ActiveKeyType.PRIMARY) "Primary API Key 🔑" else "Backup API Key ⚡"
            Toast.makeText(context, "Switched to $label", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    fun triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                val vibrator = vibratorManager?.defaultVibrator
                vibrator?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(120)
            }
        } catch (_: Exception) {}
    }
}
