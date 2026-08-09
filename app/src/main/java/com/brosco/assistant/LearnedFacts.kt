package com.brosco.assistant

import android.content.Context
import java.io.File

/**
 * Brosco's "learning" - a small, growing list of durable facts/preferences
 * he's picked up from conversations (e.g. "prefers Spotify over JioSaavn",
 * "usually orders Margherita pizza"). Stored on-device alongside MemoryStore,
 * and injected into future AI prompts so his understanding of Shrey actually
 * accumulates instead of resetting every conversation.
 */
object LearnedFacts {

    private val facts = mutableListOf<String>()
    private var loaded = false

    private const val MAX_FACTS = 60
    private const val FILE_NAME = "brosco_facts.txt"

    private fun factsFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val file = factsFile(context)
            if (file.exists()) {
                file.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) facts.add(trimmed)
                }
            }
        } catch (_: Exception) {
        }
    }

    /** Adds a newly-learned fact, skipping near-duplicates of what's already known. */
    @Synchronized
    fun add(context: Context, fact: String) {
        ensureLoaded(context)
        val cleaned = fact.trim().trim('"')
        if (cleaned.isEmpty() || cleaned.equals("NONE", ignoreCase = true)) return

        // Cheap dedupe: skip if a very similar fact is already stored, so the
        // list doesn't fill up with near-identical restatements over time.
        val alreadyKnown = facts.any { existing ->
            val a = existing.lowercase().split(Regex("\\s+")).toSet()
            val b = cleaned.lowercase().split(Regex("\\s+")).toSet()
            val overlap = a.intersect(b).size
            overlap.toFloat() / maxOf(a.size, b.size, 1) > 0.6f
        }
        if (alreadyKnown) return

        facts.add(cleaned)
        while (facts.size > MAX_FACTS) facts.removeAt(0)

        try {
            factsFile(context).writeText(facts.joinToString("\n"))
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun all(context: Context): List<String> {
        ensureLoaded(context)
        return facts.toList()
    }

    @Synchronized
    fun clear(context: Context) {
        facts.clear()
        try {
            factsFile(context).delete()
        } catch (_: Exception) {
        }
        loaded = true
    }
}
