package com.example.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

data class ProcessedProofImage(
    val localPath: String,
    val base64: String,
    val sha256: String,
    val byteSize: Int
)

/**
 * OOM-safe proof-of-payment image pipeline:
 * 1. Reads bounds only (inJustDecodeBounds) on Dispatchers.IO
 * 2. Downsamples with power-of-2 inSampleSize to <= 1280px max edge
 * 3. Compresses JPEG iteratively to stay compact for Firestore (< 150 KB)
 * 4. Persists inside app-private filesDir and computes SHA-256 verification digest
 */
object ProofImageProcessor {
    private const val MAX_EDGE_PX = 1280
    private const val TARGET_MAX_BYTES = 145_000

    suspend fun processAndStoreProof(
        context: Context,
        userId: String,
        paymentId: String,
        sourceUri: Uri
    ): Result<ProcessedProofImage> = withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsDecoded = resolver.openInputStream(sourceUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
                true
            } ?: resolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, bounds)
                true
            } ?: false

            if (!boundsDecoded) {
                return@withContext Result.failure(IllegalStateException("Unable to read selected image."))
            }

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@withContext Result.failure(IllegalArgumentException("Invalid image dimensions."))
            }

            var sampleSize = 1
            while ((bounds.outWidth / sampleSize) > MAX_EDGE_PX * 2 ||
                (bounds.outHeight / sampleSize) > MAX_EDGE_PX * 2
            ) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = resolver.openInputStream(sourceUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOpts)
            } ?: resolver.openFileDescriptor(sourceUri, "r")?.use { pfd ->
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, decodeOpts)
            } ?: return@withContext Result.failure(IllegalStateException("Failed to decode image."))

            val scaled = scaleDownIfNeeded(decoded, MAX_EDGE_PX)
            if (scaled !== decoded) {
                decoded.recycle()
            }

            val compressedBytes = compressToTarget(scaled)
            scaled.recycle()

            val sha256 = sha256Hex(compressedBytes)
            val proofDir = File(context.filesDir, "proofs/$userId").apply { mkdirs() }
            val destFile = File(proofDir, "$paymentId.jpg")
            destFile.writeBytes(compressedBytes)

            val b64 = Base64.encodeToString(compressedBytes, Base64.NO_WRAP)
            Result.success(
                ProcessedProofImage(
                    localPath = destFile.absolutePath,
                    base64 = b64,
                    sha256 = sha256,
                    byteSize = compressedBytes.size
                )
            )
        } catch (oom: OutOfMemoryError) {
            Result.failure(IllegalStateException("Selected image is too large to process.", oom))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun restoreFromBase64IfValid(
        context: Context,
        userId: String,
        paymentId: String,
        base64: String,
        expectedSha256: String?
    ): String? = withContext(Dispatchers.IO) {
        try {
            val bytes = Base64.decode(base64, Base64.NO_WRAP)
            val actualHash = sha256Hex(bytes)
            if (!expectedSha256.isNullOrBlank() && !actualHash.equals(expectedSha256, ignoreCase = true)) {
                return@withContext null
            }
            val proofDir = File(context.filesDir, "proofs/$userId").apply { mkdirs() }
            val destFile = File(proofDir, "$paymentId.jpg")
            destFile.writeBytes(bytes)
            destFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    suspend fun verifyFileIntegrity(localPath: String?, expectedSha256: String?): Boolean =
        withContext(Dispatchers.IO) {
            if (localPath.isNullOrBlank()) return@withContext false
            val file = File(localPath)
            if (!file.exists()) return@withContext false
            if (expectedSha256.isNullOrBlank()) return@withContext true
            val actual = sha256Hex(file.readBytes())
            actual.equals(expectedSha256, ignoreCase = true)
        }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val maxDim = maxOf(w, h)
        if (maxDim <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / maxDim.toFloat()
        val targetW = (w * ratio).toInt().coerceAtLeast(1)
        val targetH = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
    }

    private fun compressToTarget(bitmap: Bitmap): ByteArray {
        var quality = 82
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        while (baos.size() > TARGET_MAX_BYTES && quality > 38) {
            baos.reset()
            quality -= 10
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        }
        return baos.toByteArray()
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
