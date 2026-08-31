package com.brosco.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Runs once in the background - possibly hours after "fix this overnight"
 * was said, possibly while the app is fully closed - to actually send the
 * queued file to the model, save the corrected version to Downloads, and
 * leave a notification + FileFixStore entry waiting for the morning.
 * WorkManager (not a plain coroutine) is what lets this survive the app
 * process being killed overnight, same reasoning as OvernightBriefingWorker.
 */
class FileFixWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val CHANNEL_ID = "brosco_file_fix"
        private const val NOTIFICATION_ID = 3
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val (fileName, originalContent) = FileFixStore.pendingFile(context) ?: return Result.success()

        try {
            val raw = GroqApiClient.fixFile(fileName, originalContent)
            val marker = "---FIXED FILE---"
            val markerIndex = raw.indexOf(marker)

            val summary: String
            val fixedContent: String
            if (markerIndex >= 0) {
                summary = raw.substring(0, markerIndex).trim()
                fixedContent = raw.substring(markerIndex + marker.length).trim()
            } else {
                // Model didn't follow the format exactly - fall back to
                // treating the whole reply as the summary and keep the
                // original file untouched rather than risking corrupting it
                // with a guess at where the code starts.
                summary = raw.trim()
                fixedContent = originalContent
            }

            FileFixStore.markDone(context, summary, fixedContent)
            saveToDownloads(context, fileName, fixedContent)
            notifyFixReady(context, fileName)
        } catch (e: Exception) {
            Log.w("Brosco", "Overnight file fix failed: ${e.message}", e)
            FileFixStore.markFailed(context)
        }

        return Result.success()
    }

    /**
     * Writes the corrected file to the public Downloads folder as
     * "<original name>-fixed.<ext>" so it's waiting there in the morning
     * even if Shrey never asks Brosco for it out loud.
     */
    private fun saveToDownloads(context: Context, originalName: String, content: String) {
        try {
            val dotIndex = originalName.lastIndexOf('.')
            val outputName = if (dotIndex > 0) {
                originalName.substring(0, dotIndex) + "-fixed" + originalName.substring(dotIndex)
            } else {
                "$originalName-fixed"
            }

            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, outputName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                resolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                java.io.File(downloadsDir, outputName).writeText(content)
            }
        } catch (e: Exception) {
            // Not fatal - the fixed content is still readable via
            // FileFixStore/"give me my fixed file" even if the Downloads
            // write failed on this device.
            Log.w("Brosco", "Couldn't save fixed file to Downloads: ${e.message}")
        }
    }

    private fun notifyFixReady(context: Context, fileName: String) {
        try {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Brosco File Fix",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Brosco fixed $fileName")
                .setContentText("Say \"Brosco, give me my fixed file\" or check Downloads.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setAutoCancel(true)
                .build()

            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w("Brosco", "Couldn't post file-fix-ready notification: ${e.message}")
        }
    }
}

