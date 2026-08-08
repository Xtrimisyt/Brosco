package com.brosco.assistant

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Brosco's memory. Every exchange (what you said, what he said back) gets
 * appended to a JSONL file on your phone's storage so it survives app
 * restarts and phone reboots - not just kept in RAM for one session.
 *
 * Stored under Android/data/com.brosco.assistant/files/brosco_memory.jsonl
 * (app-specific external storage - no runtime permission needed, but still
 * visible in a file manager if you ever want to look at or back it up).
 */
object MemoryStore {

    data class Turn(val role: String, val text: String, val timestamp: Long)

    private val turns = mutableListOf<Turn>()
    private var loaded = false

    // Keep the in-memory cache bounded so the app doesn't slowly bloat RAM
    // over months of use - the file on disk keeps the complete history.
    private const val MAX_IN_MEMORY = 400
    private const val FILE_NAME = "brosco_memory.jsonl"

    private fun memoryFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val file = memoryFile(context)
            if (file.exists()) {
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    try {
                        val obj = JSONObject(line)
                        turns.add(Turn(obj.getString("role"), obj.getString("text"), obj.optLong("ts", 0L)))
                    } catch (_: Exception) {
                        // skip a corrupted line rather than losing the whole history
                    }
                }
                if (turns.size > MAX_IN_MEMORY) {
                    val trimmed = turns.takeLast(MAX_IN_MEMORY)
                    turns.clear()
                    turns.addAll(trimmed)
                }
            }
        } catch (_: Exception) {
            // storage unavailable - Brosco just runs without memory for this session
        }
    }

    /** Log one full exchange: what you said, and what Brosco said back. */
    @Synchronized
    fun record(context: Context, userText: String, assistantText: String) {
        ensureLoaded(context)
        val now = System.currentTimeMillis()
        val userTurn = Turn("user", userText, now)
        val assistantTurn = Turn("assistant", assistantText, now)

        turns.add(userTurn)
        turns.add(assistantTurn)
        while (turns.size > MAX_IN_MEMORY) turns.removeAt(0)

        try {
            val file = memoryFile(context)
            file.appendText(toJsonLine(userTurn) + "\n")
            file.appendText(toJsonLine(assistantTurn) + "\n")
        } catch (_: Exception) {
            // couldn't persist this turn - not fatal, keep going
        }
    }

    private fun toJsonLine(turn: Turn): String {
        return JSONObject().apply {
            put("role", turn.role)
            put("text", turn.text)
            put("ts", turn.timestamp)
        }.toString()
    }

    /** Most recent turns, oldest first - used to give the AI real conversational memory. */
    @Synchronized
    fun recentHistory(context: Context, maxTurns: Int = 12): List<Turn> {
        ensureLoaded(context)
        return turns.takeLast(maxTurns)
    }

    /** Wipes memory both in RAM and on disk - used by "forget everything". */
    @Synchronized
    fun clear(context: Context) {
        turns.clear()
        try {
            memoryFile(context).delete()
        } catch (_: Exception) {
        }
        loaded = true
    }
}
