package com.brosco.assistant

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Handles a photo shared or attached to Brosco - same idea as
 * VideoFrameAnalyzer but for a single still image instead of frames pulled
 * out of a video, reusing the same vision model call.
 */
object ImageAnalyzer {

    private const val MAX_DIMENSION = 1024

    fun analyze(context: Context, uri: Uri, question: String): String {
        val base64 = try {
            loadAsBase64Jpeg(context, uri)
        } catch (e: Exception) {
            Log.w("Brosco", "Image decode failed: ${e.message}", e)
            null
        }

        if (base64 == null) {
            return "I couldn't read that image - it might be an unsupported format or corrupted."
        }

        val prompt = if (question.isBlank()) "Describe what's in this photo." else question

        return try {
            GroqApiClient.analyzeImages(listOf(base64), prompt)
        } catch (e: Exception) {
            Log.w("Brosco", "Vision call failed: ${e.message}", e)
            "I've got the photo but couldn't reach the vision model just now - mind trying again?"
        }
    }

    private fun loadAsBase64Jpeg(context: Context, uri: Uri): String? {
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        } ?: return null

        val scaled = downscale(bitmap)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }

    private fun downscale(bitmap: Bitmap): Bitmap {
        val largestSide = maxOf(bitmap.width, bitmap.height)
        if (largestSide <= MAX_DIMENSION) return bitmap
        val scale = MAX_DIMENSION.toFloat() / largestSide
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }
}

