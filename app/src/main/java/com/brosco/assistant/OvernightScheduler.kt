package com.brosco.assistant

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Drives "work brosco goodnight" mode: a chain of one-off WorkManager jobs
 * (not a fixed PeriodicWorkRequest, so the interval and total run count are
 * easy to tune) that each gather one overnight update and then, if the
 * session is still active, schedule the next one.
 *
 * WorkManager is what actually makes this survive the phone being asleep
 * and the app process being killed to reclaim memory overnight - it's
 * backed by the system JobScheduler, not by anything running inside
 * Brosco's own process. Realistic expectations, stated plainly:
 *  - It WILL run overnight on stock Android as long as the phone has
 *    network access and isn't powered off.
 *  - Some OEM battery managers (especially on Xiaomi/Oppo/Vivo/OnePlus)
 *    are more aggressive than stock Android about killing background work
 *    even with a battery-optimization exemption granted - if runs seem to
 *    stop happening on a given phone, that setting is the first thing to
 *    check.
 *  - This is still bounded by [MAX_RUNS] rather than running forever, so a
 *    forgotten session doesn't quietly burn API credits for days.
 */
object OvernightScheduler {

    private const val PREFS = "brosco_overnight"
    private const val KEY_ACTIVE = "active"
    private const val KEY_RUN_COUNT = "run_count"
    private const val WORK_NAME = "brosco_overnight_briefing"

    // Every 90 minutes, up to 5 times - roughly 7.5 hours of coverage from
    // whenever "goodnight" is said, which comfortably spans a normal
    // night's sleep without running indefinitely if it's left on by accident.
    const val INTERVAL_MINUTES = 90L
    const val MAX_RUNS = 5
    private const val FIRST_RUN_DELAY_MINUTES = 10L

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Starts (or restarts) an overnight session: resets the run counter and the digest, then queues the first gather. */
    fun start(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putInt(KEY_RUN_COUNT, 0)
            .apply()
        NightDigestStore.startNewSession(context)
        enqueueNext(context, FIRST_RUN_DELAY_MINUTES)
    }

    /** Stops the session and cancels any pending run - used by "stop working overnight" / "cancel the overnight thing". */
    fun stop(context: Context) {
        prefs(context).edit().putBoolean(KEY_ACTIVE, false).apply()
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    fun isActive(context: Context): Boolean = prefs(context).getBoolean(KEY_ACTIVE, false)

    fun runCount(context: Context): Int = prefs(context).getInt(KEY_RUN_COUNT, 0)

    /** Whether another gather cycle should happen after the one currently running finishes. */
    fun shouldContinue(context: Context): Boolean =
        isActive(context) && runCount(context) < MAX_RUNS

    fun recordRun(context: Context) {
        val p = prefs(context)
        p.edit().putInt(KEY_RUN_COUNT, p.getInt(KEY_RUN_COUNT, 0) + 1).apply()
    }

    fun enqueueNext(context: Context, delayMinutes: Long) {
        val request = OneTimeWorkRequestBuilder<OvernightBriefingWorker>()
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }
}
