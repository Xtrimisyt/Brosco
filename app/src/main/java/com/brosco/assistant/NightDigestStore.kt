package com.brosco.assistant

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Holds what Brosco gathered overnight ("work brosco goodnight" mode) so it
 * can be read back as a morning briefing. Same append-only-JSONL pattern as
 * MemoryStore, in its own file so clearing chat memory doesn't wipe the
 * digest and vice versa.
 *
 * Stored under Android/data/com.brosco.assistant/files/brosco_night_digest.jsonl
 */
object NightDigestStore {

    data class Entry(val timestamp: Long, val content: String)

    private const val FILE_NAME = "brosco_night_digest.jsonl"

    private fun file(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    /** Call once when a new overnight session starts, so old digests from a previous night don't bleed into this one. */
    @Synchronized
    fun startNewSession(context: Context) {
        try {
            file(context).writeText("")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun append(context: Context, content: String) {
        if (content.isBlank()) return
        try {
            val entry = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("content", content)
            }
            file(context).appendText(entry.toString() + "\n")
        } catch (_: Exception) {
            // Not fatal - the worker will just retry next cycle.
        }
    }

    @Synchronized
    fun allEntries(context: Context): List<Entry> {
        val result = mutableListOf<Entry>()
        try {
            val f = file(context)
            if (!f.exists()) return result
            f.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                try {
                    val obj = JSONObject(line)
                    result.add(Entry(obj.optLong("ts", 0L), obj.optString("content", "")))
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    /**
     * Builds a single spoken/readable briefing out of everything gathered
     * this session, oldest first, each stamped with the time it was
     * gathered so it's clear this was collected in pieces overnight.
     */
    @Synchronized
    fun formatBriefing(context: Context): String {
        val entries = allEntries(context)
        if (entries.isEmpty()) {
            return "I don't have an overnight briefing - ask me to work overnight before you go to " +
                "sleep and I'll have one ready for you."
        }
        val timeFmt = SimpleDateFormat("h:mm a", Locale.US)
        return entries.joinToString("\n\n") { entry ->
            val stamp = if (entry.timestamp > 0) timeFmt.format(Date(entry.timestamp)) + " - " else ""
            "$stamp${entry.content}"
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
