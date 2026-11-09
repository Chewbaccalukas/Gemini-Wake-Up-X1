package com.example.util

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class CompressionStats(
    val originalSizeBytes: Int,
    val compressedSizeBytes: Int,
    val reductionPercentage: Float
)

object GzipUtil {

    /**
     * Compresses raw string into a GZIP byte array.
     */
    fun compress(input: String): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val byteStream = ByteArrayOutputStream()
        GZIPOutputStream(byteStream).use { gzip ->
            gzip.write(input.toByteArray(StandardCharsets.UTF_8))
        }
        return byteStream.toByteArray()
    }

    /**
     * Decompresses GZIP byte array back into a string.
     */
    fun decompress(compressedBytes: ByteArray): String {
        if (compressedBytes.isEmpty()) return ""
        ByteArrayInputStream(compressedBytes).use { byteStream ->
            GZIPInputStream(byteStream).use { gzip ->
                return gzip.reader(StandardCharsets.UTF_8).readText()
            }
        }
    }

    /**
     * Calculates compression statistics for displaying to the user.
     */
    fun calculateStats(input: String): CompressionStats {
        val originalBytes = input.toByteArray(StandardCharsets.UTF_8).size
        val compressedBytes = compress(input).size
        val reduction = if (originalBytes > 0) {
            ((originalBytes - compressedBytes).toFloat() / originalBytes * 100f).coerceAtLeast(0f)
        } else 0f
        return CompressionStats(
            originalSizeBytes = originalBytes,
            compressedSizeBytes = compressedBytes,
            reductionPercentage = reduction
        )
    }

    /**
     * Formats bytes into human-readable KB/MB.
     */
    fun formatBytes(bytes: Int): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024f)
            else -> String.format(java.util.Locale.US, "%.2f MB", bytes / (1024f * 1024f))
        }
    }
}
