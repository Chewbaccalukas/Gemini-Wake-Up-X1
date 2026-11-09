package com.example.voice

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

/**
 * WorkManager [CoroutineWorker] that monitors Android's Battery Saver mode
 * ([PowerManager.isPowerSaveMode]) and pauses [SpeechRecognitionCoordinator],
 * [AudioRecorder], and [WakeWordVoiceService] microphone hardware when the app
 * is not in active voice use.
 *
 * - Runs on a periodic 15-minute schedule ([schedulePeriodicWork]) and immediately
 *   when the app transitions to the background ([enqueueImmediateCheck]).
 * - Automatically resumes listening when Battery Saver mode turns off.
 */
class BatterySaverMonitorWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val isPowerSaveActive = isBatterySaverActive(applicationContext)
            val inActiveUse = isInActiveVoiceUse()

            Log.d(
                TAG,
                "BatterySaverMonitorWorker check: isPowerSaveActive=$isPowerSaveActive, " +
                    "inActiveUse=$inActiveUse, wasPausedByBatterySaver=${_isPausedByBatterySaver.value}"
            )

            if (isPowerSaveActive && !inActiveUse) {
                applyBatterySaverPause(applicationContext)
                Result.success(
                    Data.Builder()
                        .putBoolean(KEY_BATTERY_SAVER_ACTIVE, true)
                        .putBoolean(KEY_PAUSED_COORDINATOR, true)
                        .build()
                )
            } else if (!isPowerSaveActive && _isPausedByBatterySaver.value) {
                resumeFromBatterySaverPause(applicationContext)
                Result.success(
                    Data.Builder()
                        .putBoolean(KEY_BATTERY_SAVER_ACTIVE, false)
                        .putBoolean(KEY_PAUSED_COORDINATOR, false)
                        .putBoolean(KEY_AUTO_RESUMED, true)
                        .build()
                )
            } else {
                Result.success(
                    Data.Builder()
                        .putBoolean(KEY_BATTERY_SAVER_ACTIVE, isPowerSaveActive)
                        .putBoolean(KEY_PAUSED_COORDINATOR, _isPausedByBatterySaver.value)
                        .build()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "BatterySaverMonitorWorker encountered error: ${e.message}", e)
            Result.success()
        }
    }

    companion object {
        private const val TAG = "BatterySaverWorker"

        const val UNIQUE_PERIODIC_WORK_NAME = "gemini_wake_battery_saver_periodic_monitor"
        const val UNIQUE_IMMEDIATE_WORK_NAME = "gemini_wake_battery_saver_immediate_check"
        const val PERIODIC_INTERVAL_MINUTES = 15L

        const val KEY_BATTERY_SAVER_ACTIVE = "battery_saver_active"
        const val KEY_PAUSED_COORDINATOR = "paused_coordinator"
        const val KEY_AUTO_RESUMED = "auto_resumed"

        private val _isPausedByBatterySaver = MutableStateFlow(false)
        val isPausedByBatterySaver: StateFlow<Boolean> = _isPausedByBatterySaver.asStateFlow()

        @Volatile
        private var coordinatorRef: WeakReference<SpeechRecognitionCoordinator>? = null

        @Volatile
        private var audioRecorderRef: WeakReference<AudioRecorder>? = null

        @Volatile
        var isActiveVoiceUseProvider: (() -> Boolean)? = null

        @Volatile
        var onBatterySaverStateChanged: ((pausedByBatterySaver: Boolean) -> Unit)? = null

        /** Optional test override for [PowerManager.isPowerSaveMode] in unit tests. */
        @Volatile
        var powerSaveModeOverrideForTesting: Boolean? = null

        /**
         * Registers the active [SpeechRecognitionCoordinator] and [AudioRecorder] instances
         * so [BatterySaverMonitorWorker] can directly pause and resume them.
         */
        fun registerVoiceComponents(
            coordinator: SpeechRecognitionCoordinator,
            audioRecorder: AudioRecorder,
            activeUseChecker: () -> Boolean,
            onStateChanged: (Boolean) -> Unit = {}
        ) {
            coordinatorRef = WeakReference(coordinator)
            audioRecorderRef = WeakReference(audioRecorder)
            isActiveVoiceUseProvider = activeUseChecker
            onBatterySaverStateChanged = onStateChanged
        }

        /**
         * Returns `true` if Android Battery Saver ([PowerManager.isPowerSaveMode]) is currently enabled.
         */
        fun isBatterySaverActive(context: Context): Boolean {
            powerSaveModeOverrideForTesting?.let { return it }
            val powerManager = context.applicationContext
                .getSystemService(Context.POWER_SERVICE) as? PowerManager
            return powerManager?.isPowerSaveMode == true
        }

        /**
         * Returns `true` if the user is actively speaking a command, recording a snippet,
         * or receiving a live AI / TTS response (so we never cut off an active turn).
         */
        fun isInActiveVoiceUse(): Boolean {
            val providerResult = isActiveVoiceUseProvider?.invoke() ?: false
            val coordinatorProcessing = coordinatorRef?.get()?.isProcessing == true
            val recorderCapturing = audioRecorderRef?.get()?.isRecording == true
            return providerResult || coordinatorProcessing || recorderCapturing
        }

        /**
         * Pauses [SpeechRecognitionCoordinator], [AudioRecorder], and [WakeWordVoiceService]
         * to conserve battery while Battery Saver is active.
         */
        fun applyBatterySaverPause(context: Context) {
            _isPausedByBatterySaver.value = true
            coordinatorRef?.get()?.setIntentionallyPaused(true)
            audioRecorderRef?.get()?.setIntentionallyPaused(true)
            WakeWordVoiceService.setIntentionallyPaused(true)
            WakeWordVoiceService.setBatterySaverPaused(true)
            WakeWordVoiceService.startServiceSafely(
                context = context.applicationContext,
                action = WakeWordVoiceService.ACTION_BATTERY_SAVER_PAUSE
            )
            onBatterySaverStateChanged?.invoke(true)
        }

        /**
         * Clears the Battery Saver pause flag and resumes [SpeechRecognitionCoordinator]
         * and [WakeWordVoiceService] when Battery Saver turns off or the user manually resumes.
         */
        fun resumeFromBatterySaverPause(context: Context) {
            _isPausedByBatterySaver.value = false
            if (!WakeWordVoiceService.isSleepModeGlobal.value) {
                coordinatorRef?.get()?.setIntentionallyPaused(false)
                audioRecorderRef?.get()?.setIntentionallyPaused(false)
                WakeWordVoiceService.setIntentionallyPaused(false)
            }
            WakeWordVoiceService.setBatterySaverPaused(false)
            WakeWordVoiceService.startServiceSafely(
                context = context.applicationContext,
                action = WakeWordVoiceService.ACTION_BATTERY_SAVER_RESUME
            )
            onBatterySaverStateChanged?.invoke(false)
        }

        /**
         * Schedules the periodic 15-minute WorkManager battery-saver monitor task.
         */
        fun schedulePeriodicWork(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<BatterySaverMonitorWorker>(
                    PERIODIC_INTERVAL_MINUTES,
                    TimeUnit.MINUTES
                )
                    .addTag(UNIQUE_PERIODIC_WORK_NAME)
                    .build()

                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    UNIQUE_PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            } catch (e: Exception) {
                Log.w(TAG, "Unable to schedule periodic BatterySaverMonitorWorker: ${e.message}")
            }
        }

        /**
         * Enqueues an immediate one-time WorkManager battery-saver check (e.g., when the app
         * moves to the background or when power state changes).
         */
        fun enqueueImmediateCheck(context: Context) {
            try {
                val request = OneTimeWorkRequestBuilder<BatterySaverMonitorWorker>()
                    .addTag(UNIQUE_IMMEDIATE_WORK_NAME)
                    .build()

                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    UNIQUE_IMMEDIATE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            } catch (e: Exception) {
                Log.w(TAG, "Unable to enqueue immediate BatterySaverMonitorWorker check: ${e.message}")
            }
        }
    }
}
