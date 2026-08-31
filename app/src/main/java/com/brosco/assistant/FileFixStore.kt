package com.brosco.assistant

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Backs "share a file to Brosco to have it fixed overnight". Holds exactly
 * one job at a time (Shrey hands over one file, gets one result back - a
 * second share before the first is collected just replaces it, same as
 * NightDigestStore resetting on a new overnight session).
 *
 * Stored under Android/data/com.brosco.assistant/files/brosco_file_fix.json
 * so it survives the app process being killed while FileFixWorker runs in
 * the background.
 */
object FileFixStore {

    private const val FILE_NAME = "brosco_file_fix.json"

    private const val STATUS_PENDING = "pending"
    private const val STATUS_DONE = "done"
    private const val STATUS_FAILED = "failed"

    data class Result(val fileName: String, val summary: String, val fixedContent: String)

    private fun file(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    /** Called the moment a file is shared in - overwrites any previous job. */
    @Synchronized
    fun queue(context: Context, fileName: String, originalContent: String) {
        try {
            val obj = JSONObject().apply {
                put("fileName", fileName)
                put("original", originalContent)
                put("status", STATUS_PENDING)
                put("queuedAt", System.currentTimeMillis())
            }
            file(context).writeText(obj.toString())
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun markDone(context: Context, summary: String, fixedContent: String) {
        try {
            val f = file(context)
            val existing = if (f.exists()) JSONObject(f.readText()) else JSONObject()
            existing.put("status", STATUS_DONE)
            existing.put("summary", summary)
            existing.put("fixed", fixedContent)
            f.writeText(existing.toString())
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun markFailed(context: Context) {
        try {
            val f = file(context)
            if (!f.exists()) return
            val existing = JSONObject(f.readText())
            existing.put("status", STATUS_FAILED)
            f.writeText(existing.toString())
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun pendingFile(context: Context): Pair<String, String>? {
        return try {
            val f = file(context)
            if (!f.exists()) return null
            val obj = JSONObject(f.readText())
            if (obj.optString("status") != STATUS_PENDING) return null
            Pair(obj.optString("fileName"), obj.optString("original"))
        } catch (_: Exception) {
            null
        }
    }

    /** null = nothing has ever been queued. */
    @Synchronized
    fun status(context: Context): String? {
        return try {
            val f = file(context)
            if (!f.exists()) return null
            JSONObject(f.readText()).optString("status").ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun result(context: Context): Result? {
        return try {
            val f = file(context)
            if (!f.exists()) return null
            val obj = JSONObject(f.readText())
            if (obj.optString("status") != STATUS_DONE) return null
            Result(
                fileName = obj.optString("fileName"),
                summary = obj.optString("summary"),
                fixedContent = obj.optString("fixed")
            )
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (_: Exception) {
        }
    }
}

