package com.brosco.assistant

import android.content.Context
import java.io.File

/**
 * Cheap, local companion to LearnedFacts for things that don't need an LLM
 * call to notice: how many times has this exact song/pizza/whatever come up
 * before. LearnedFacts.add already goes through GroqApiClient.extractFact,
 * which is the right tool for "did something meaningful get said just now"
 * but the wrong one to fire on every single "play X" - that's an API round
 * trip added to the critical path of a flow that's supposed to feel fast,
 * for something a plain counter answers just as well.
 *
 * Instead: bump a local count per (category, term) on every successful play/
 * order, and once a term crosses a repeat threshold, write ONE plain-English
 * fact into LearnedFacts (which already injects into future prompts) and
 * never fire again for that same term. No network call, no added latency on
 * the flow it's attached to - recordAndMaybeLearn is meant to be called from
 * inside onDone, after the thing already succeeded.
 */
object UsageStats {

    private const val FILE_NAME = "brosco_usage.txt"
    private const val REPEAT_THRESHOLD = 3

    // category -> (term -> count). Loaded once, written back to disk as a
    // flat "category|term|count" file per line - deliberately not JSON, to
    // keep this dependency-free like LearnedFacts/MemoryStore.
    private val counts = mutableMapOf<String, MutableMap<String, Int>>()
    private val alreadyLearned = mutableSetOf<String>()
    private var loaded = false

    private fun file(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = file(context)
            if (f.exists()) {
                f.forEachLine { line ->
                    val parts = line.split("|")
                    if (parts.size == 3) {
                        val (category, term, countStr) = parts
                        val n = countStr.toIntOrNull() ?: return@forEachLine
                        counts.getOrPut(category) { mutableMapOf() }[term] = n
                        if (n >= REPEAT_THRESHOLD) alreadyLearned.add("$category|$term")
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    private fun persist(context: Context) {
        try {
            val lines = counts.flatMap { (category, terms) ->
                terms.map { (term, n) -> "$category|$term|$n" }
            }
            file(context).writeText(lines.joinToString("\n"))
        } catch (_: Exception) {
        }
    }

    /**
     * Call after a flow's onDone. [category] is a short fixed label
     * ("jiosaavn", "spotify", "dominos", "zomato"); [term] is whatever the
     * user asked for (song name, pizza name, etc), lowercased/trimmed by the
     * caller's normal flow already. [factTemplate] takes the term and
     * returns the sentence to remember, e.g. { t -> "Frequently plays \"$t\"
     * on JioSaavn." } - kept caller-supplied so the phrasing fits each flow
     * naturally instead of one generic sentence for everything.
     */
    @Synchronized
    fun recordAndMaybeLearn(
        context: Context,
        category: String,
        term: String,
        factTemplate: (String) -> String
    ) {
        val cleaned = term.trim().lowercase()
        if (cleaned.isBlank()) return
        ensureLoaded(context)

        val bucket = counts.getOrPut(category) { mutableMapOf() }
        val newCount = (bucket[cleaned] ?: 0) + 1
        bucket[cleaned] = newCount
        persist(context)

        val key = "$category|$cleaned"
        if (newCount >= REPEAT_THRESHOLD && key !in alreadyLearned) {
            alreadyLearned.add(key)
            LearnedFacts.add(context, factTemplate(term.trim()))
        }
    }

    @Synchronized
    fun clear(context: Context) {
        counts.clear()
        alreadyLearned.clear()
        try {
            file(context).delete()
        } catch (_: Exception) {
        }
        loaded = true
    }
}

