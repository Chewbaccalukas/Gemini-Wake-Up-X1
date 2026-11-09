package com.example.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.roundToInt

data class ScaledImageResult(
    val file: File,
    val uri: Uri,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val bytes: ByteArray
)

object ImageScaler {
    private const val TAG = "ImageScaler"
    const val MAX_MODEL_DIMENSION = 1568 // Maximum dimension for Gemini vision models

    /**
     * Downscales an image from a content Uri to a maximum dimension of 1568px,
     * compresses to JPEG (quality 85), recycles intermediate Bitmaps to prevent OOM,
     * and saves to a staging file in the cache directory.
     */
    fun processAndScaleImage(context: Context, sourceUri: Uri): ScaledImageResult? {
        return try {
            // Step 1: Decode image bounds without allocating pixel memory
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            openInputStream(context, sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return null

            val originalWidth = options.outWidth
            val originalHeight = options.outHeight
            if (originalWidth <= 0 || originalHeight <= 0) {
                Log.e(TAG, "Invalid image bounds: ${originalWidth}x$originalHeight")
                return null
            }

            // Step 2: Compute inSampleSize (power of 2) to reduce decoding heap usage
            var inSampleSize = 1
            var maxDim = maxOf(originalWidth, originalHeight)
            while (maxDim / (inSampleSize * 2) >= MAX_MODEL_DIMENSION) {
                inSampleSize *= 2
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            // Step 3: Decode sub-sampled bitmap
            val subSampledBitmap: Bitmap? = openInputStream(context, sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
            if (subSampledBitmap == null) {
                Log.e(TAG, "Failed to decode sub-sampled bitmap from $sourceUri")
                return null
            }

            // Step 4: Scale precisely if dimensions still exceed MAX_MODEL_DIMENSION
            val curWidth = subSampledBitmap.width
            val curHeight = subSampledBitmap.height
            val finalBitmap = if (curWidth > MAX_MODEL_DIMENSION || curHeight > MAX_MODEL_DIMENSION) {
                val scale = MAX_MODEL_DIMENSION.toFloat() / maxOf(curWidth, curHeight).toFloat()
                val targetWidth = (curWidth * scale).roundToInt().coerceAtLeast(1)
                val targetHeight = (curHeight * scale).roundToInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(subSampledBitmap, targetWidth, targetHeight, true)
                if (scaled != subSampledBitmap) {
                    subSampledBitmap.recycle()
                }
                scaled
            } else {
                subSampledBitmap
            }

            val finalWidth = finalBitmap.width
            val finalHeight = finalBitmap.height

            // Step 5: Compress to JPEG bytes (Quality 85 provides optimal fidelity / size ratio)
            val byteOutputStream = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, byteOutputStream)
            val jpegBytes = byteOutputStream.toByteArray()

            // Memory Safety: recycle bitmap immediately
            finalBitmap.recycle()

            // Step 6: Save to isolated staging file
            val stagingDir = File(context.cacheDir, "screenshot_staging").apply { mkdirs() }
            val stagingFile = File(stagingDir, "staged_screenshot_${System.currentTimeMillis()}.jpg")
            FileOutputStream(stagingFile).use { fos ->
                fos.write(jpegBytes)
                fos.flush()
            }

            val fileUri = Uri.fromFile(stagingFile)

            ScaledImageResult(
                file = stagingFile,
                uri = fileUri,
                width = finalWidth,
                height = finalHeight,
                sizeBytes = jpegBytes.size.toLong(),
                bytes = jpegBytes
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error scaling and staging image: ${e.message}", e)
            null
        }
    }

    /**
     * Processes raw bytes (e.g. from Chrome Socket binary frame / upload).
     */
    fun processAndScaleBytes(context: Context, rawBytes: ByteArray): ScaledImageResult? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options)

            val originalWidth = options.outWidth
            val originalHeight = options.outHeight
            if (originalWidth <= 0 || originalHeight <= 0) return null

            var inSampleSize = 1
            var maxDim = maxOf(originalWidth, originalHeight)
            while (maxDim / (inSampleSize * 2) >= MAX_MODEL_DIMENSION) {
                inSampleSize *= 2
            }

            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            val subSampledBitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, options) ?: return null

            val curWidth = subSampledBitmap.width
            val curHeight = subSampledBitmap.height
            val finalBitmap = if (curWidth > MAX_MODEL_DIMENSION || curHeight > MAX_MODEL_DIMENSION) {
                val scale = MAX_MODEL_DIMENSION.toFloat() / maxOf(curWidth, curHeight).toFloat()
                val targetWidth = (curWidth * scale).roundToInt().coerceAtLeast(1)
                val targetHeight = (curHeight * scale).roundToInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(subSampledBitmap, targetWidth, targetHeight, true)
                if (scaled != subSampledBitmap) {
                    subSampledBitmap.recycle()
                }
                scaled
            } else {
                subSampledBitmap
            }

            val finalWidth = finalBitmap.width
            val finalHeight = finalBitmap.height

            val byteOutputStream = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, byteOutputStream)
            val jpegBytes = byteOutputStream.toByteArray()
            finalBitmap.recycle()

            val stagingDir = File(context.cacheDir, "screenshot_staging").apply { mkdirs() }
            val stagingFile = File(stagingDir, "staged_screenshot_${System.currentTimeMillis()}.jpg")
            FileOutputStream(stagingFile).use { fos ->
                fos.write(jpegBytes)
                fos.flush()
            }

            ScaledImageResult(
                file = stagingFile,
                uri = Uri.fromFile(stagingFile),
                width = finalWidth,
                height = finalHeight,
                sizeBytes = jpegBytes.size.toLong(),
                bytes = jpegBytes
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error scaling raw bytes: ${e.message}", e)
            null
        }
    }

    /**
     * Clears all staged screenshots in the cache directory.
     */
    fun clearStagingDirectory(context: Context) {
        try {
            val stagingDir = File(context.cacheDir, "screenshot_staging")
            if (stagingDir.exists()) {
                stagingDir.listFiles()?.forEach { file ->
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing screenshot staging dir: ${e.message}")
        }
    }

    private fun openInputStream(context: Context, uri: Uri): InputStream? {
        return try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open stream for $uri: ${e.message}")
            null
        }
    }
}
