package com.brosco.assistant

/**
 * Holds the last set of AI-suggested WhatsApp quick replies, in memory only.
 * Deliberately not persisted to disk like MemoryStore/LearnedFacts - these
 * options are only ever meaningful for the next few seconds, right after
 * Brosco reads a message out loud and offers them, so a follow-up like
 * "reply with the second one" knows what "the second one" refers to.
 * Expires on its own so a stale "reply with the second one" said minutes
 * later (after the conversation moved on) doesn't fire off the wrong text.
 */
object SmartReplyStore {

    private const val EXPIRY_MS = 3 * 60 * 1000L

    private var options: List<String> = emptyList()
    private var setAt: Long = 0L

    fun set(newOptions: List<String>) {
        options = newOptions
        setAt = System.currentTimeMillis()
    }

    /** Returns the current options, or null if none are set or they've expired. */
    fun get(): List<String>? {
        if (options.isEmpty()) return null
        if (System.currentTimeMillis() - setAt > EXPIRY_MS) {
            clear()
            return null
        }
        return options
    }

    fun clear() {
        options = emptyList()
        setAt = 0L
    }
}

