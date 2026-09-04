package com.brosco.assistant

import android.content.Context
import android.util.Log
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
            val savedUri = FileFixNotifier.saveToDownloads(context, fileName, fixedContent)
            FileFixNotifier.notifyFixReady(context, fileName, savedUri)
        } catch (e: Exception) {
            Log.w("Brosco", "Overnight file fix failed: ${e.message}", e)
            FileFixStore.markFailed(context)
        }

        return Result.success()
    }
}

