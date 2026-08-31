package com.brosco.assistant

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Kicks off FileFixWorker once a file has been queued in FileFixStore.
 * Runs on WorkManager (survives the app being killed) with a short delay
 * rather than a fixed "wait until it's actually night" schedule - it
 * processes as soon as it reasonably can in the background, so whether
 * Shrey hands it over at 11pm or 6pm, the result is sitting there waiting
 * well before he next opens the app, without him needing to keep it open.
 */
object FileFixScheduler {

    private const val WORK_NAME = "brosco_file_fix"
    private const val START_DELAY_MINUTES = 1L

    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<FileFixWorker>()
            .setInitialDelay(START_DELAY_MINUTES, TimeUnit.MINUTES)
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

