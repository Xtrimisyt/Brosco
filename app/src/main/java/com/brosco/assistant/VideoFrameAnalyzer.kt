package com.brosco.assistant

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * "Video analysis" on this stack, honestly stated: Groq's API (like most
 * hosted chat APIs) doesn't accept a video file as input, only text and
 * still images. There's no way around that without switching providers.
 * What this DOES do, and does for real: pull a handful of evenly-spaced
 * frames out of the video using Android's built-in MediaMetadataRetriever
 * (no extra libraries, no ffmpeg), then send those frames to Groq's vision
 * model (qwen/qwen3.6-27b) as images with a prompt describing that they're
 * sampled frames, not the full video.
 *
 * That gets you a real answer to "what's in this video" / "what's
 * happening here" for anything visual, on-screen text, or scene changes.
 * It will NOT catch anything audio-only (dialogue, music, narration) since
 * no speech-to-text step runs here - the prompt in GroqApiClient tells the
 * model not to guess at that rather than making something up.
 */
object VideoFrameAnalyzer {

    private const val MAX_FRAMES = 5
    private const val MAX_DIMENSION = 768 // keep the request small/fast; detail beyond this doesn't help the model much anyway

    /**
     * Extracts up to [MAX_FRAMES] evenly-spaced frames from the video at
     * [uri] and asks the vision model about them. Returns a plain-language
     * answer, or a plain-language explanation of what went wrong - never
     * throws.
     */
    fun analyze(context: Context, uri: Uri, question: String): String {
        val frames = try {
            extractFrames(context, uri)
        } catch (e: Exception) {
            Log.w("Brosco", "Frame extraction failed: ${e.message}", e)
            emptyList()
        }

        if (frames.isEmpty()) {
            return "I couldn't read that video file - it might be an unsupported format, " +
                "corrupted, or too large."
        }

        val prompt = if (question.isBlank()) {
            "Describe what's happening in this video based on these sampled frames."
        } else {
            question
        }

        return try {
            GroqApiClient.analyzeImages(frames, prompt)
        } catch (e: Exception) {
            Log.w("Brosco", "Vision call failed: ${e.message}", e)
            "I pulled frames from the video but couldn't reach the vision model just now - mind trying again?"
        }
    }

    /** Returns base64-encoded JPEG strings (no data: prefix), oldest frame first. */
    private fun extractFrames(context: Context, uri: Uri): List<String> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)

            val durationMs = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L

            if (durationMs <= 0L) {
                // No duration metadata (some odd containers) - just grab the
                // frame at time zero rather than giving up entirely.
                val frame = retriever.getFrameAtTime(0)
                return listOfNotNull(frame?.let { toBase64Jpeg(it) })
            }

            val frameCount = MAX_FRAMES
            val stepMs = durationMs / (frameCount + 1)

            (1..frameCount).mapNotNull { i ->
                val timeUs = (stepMs * i) * 1000L
                val bitmap = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                bitmap?.let { toBase64Jpeg(it) }
            }
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun toBase64Jpeg(bitmap: Bitmap): String {
        val scaled = downscale(bitmap)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 80, stream)
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
