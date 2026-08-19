package com.brosco.assistant

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * One overnight gather cycle: asks the search-capable model for a compact
 * markets + news update, appends it to NightDigestStore, then either
 * schedules the next cycle or - if this was the last one - posts a
 * notification so a "briefing ready" alert is waiting on wake, in case
 * Shrey doesn't think to ask right away.
 */
class OvernightBriefingWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val CHANNEL_ID = "brosco_briefing"
        private const val NOTIFICATION_ID = 2
    }

    override suspend fun doWork(): Result {
        val context = applicationContext

        if (!OvernightScheduler.shouldContinue(context)) {
            return Result.success()
        }

        try {
            val prompt = "It's the middle of the night and Shrey is asleep - you're gathering an " +
                "overnight update for him to read when he wakes up, not talking to him live. Give a " +
                "tight, factual update in two short parts: 1) any notable stock market or crypto moves " +
                "in the last few hours (major US/global indices, and anything unusually large in " +
                "individual stocks or crypto) - if markets are closed right now, say so briefly instead " +
                "of inventing movement, 2) three to five top world/tech news headlines from the last " +
                "few hours, each in one plain sentence. No greetings, no sign-off, no filler - this is " +
                "one entry in a running log, not a full conversation."

            val summary = GroqApiClient.ask(
                context = context,
                query = prompt,
                forceSearch = true
            )
            NightDigestStore.append(context, summary)
        } catch (e: Exception) {
            Log.w("Brosco", "Overnight gather cycle failed: ${e.message}", e)
            // Don't record a run on failure - retry the same slot next cycle
            // instead of quietly losing one of the MAX_RUNS attempts to a
            // transient network blip.
            if (OvernightScheduler.shouldContinue(context)) {
                OvernightScheduler.enqueueNext(context, OvernightScheduler.INTERVAL_MINUTES)
            }
            return Result.success()
        }

        OvernightScheduler.recordRun(context)

        if (OvernightScheduler.shouldContinue(context)) {
            OvernightScheduler.enqueueNext(context, OvernightScheduler.INTERVAL_MINUTES)
        } else {
            notifyBriefingReady(context)
        }

        return Result.success()
    }

    private fun notifyBriefingReady(context: Context) {
        try {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Brosco Overnight Briefing",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Brosco's overnight briefing is ready")
                .setContentText("Say \"Brosco, give me my briefing\" or open the app to read it.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setAutoCancel(true)
                .build()

            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w("Brosco", "Couldn't post briefing-ready notification: ${e.message}")
        }
    }
}
