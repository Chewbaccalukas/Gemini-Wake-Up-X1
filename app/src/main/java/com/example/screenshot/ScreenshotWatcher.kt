package com.example.screenshot

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log

/**
 * ScreenshotWatcher monitors MediaStore.Images.Media.EXTERNAL_CONTENT_URI
 * via ContentObserver to automatically detect newly captured system screenshots.
 */
class ScreenshotWatcher(
    private val context: Context,
    private val onScreenshotDetected: (Uri) -> Unit
) {

    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private var lastObservedImageId: Long = -1L
    private var isWatching = false

    companion object {
        private const val TAG = "ScreenshotWatcher"
    }

    /**
     * Starts listening for new images in MediaStore.
     */
    fun startWatching() {
        if (isWatching) return
        try {
            observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    super.onChange(selfChange, uri)
                    checkForRecentScreenshot()
                }
            }

            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer!!
            )
            isWatching = true
            Log.d(TAG, "ScreenshotWatcher started on MediaStore.EXTERNAL_CONTENT_URI")
        } catch (e: Exception) {
            Log.w(TAG, "Could not register screenshot ContentObserver: ${e.message}")
        }
    }

    /**
     * Stops listening.
     */
    fun stopWatching() {
        if (!isWatching) return
        try {
            observer?.let {
                context.contentResolver.unregisterContentObserver(it)
            }
            observer = null
            isWatching = false
            Log.d(TAG, "ScreenshotWatcher stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering ContentObserver: ${e.message}")
        }
    }

    /**
     * Inspects MediaStore for recently added screenshot images.
     */
    fun checkForRecentScreenshot() {
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.RELATIVE_PATH
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                    val dateAddedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                    val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val pathCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)

                    if (idCol != -1) {
                        val imageId = cursor.getLong(idCol)
                        val dateAddedSec = if (dateAddedCol != -1) cursor.getLong(dateAddedCol) else 0L
                        val displayName = if (nameCol != -1) cursor.getString(nameCol) ?: "" else ""
                        val relativePath = if (pathCol != -1) cursor.getString(pathCol) ?: "" else ""

                        val isScreenshot = displayName.contains("screenshot", ignoreCase = true) ||
                                relativePath.contains("screenshot", ignoreCase = true) ||
                                displayName.contains("screen_shot", ignoreCase = true)

                        val nowSec = System.currentTimeMillis() / 1000L
                        val isRecent = (nowSec - dateAddedSec) < 60 // Captured within last 60 seconds

                        if (imageId != lastObservedImageId && (isScreenshot || isRecent)) {
                            lastObservedImageId = imageId
                            val contentUri = ContentUris.withAppendedId(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                imageId
                            )
                            Log.i(TAG, "Detected new screenshot: $contentUri ($displayName)")
                            onScreenshotDetected(contentUri)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "MediaStore query skipped: ${e.message}")
        }
    }
}
