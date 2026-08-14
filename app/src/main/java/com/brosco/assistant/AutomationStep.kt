package com.brosco.assistant

/**
 * A single unit of work the accessibility service can carry out on screen.
 * A whole task ("search burger -> add to cart -> open cart") is just a list
 * of these, executed in order by WhatsAppAccessibilityService.
 */
sealed class AutomationStep {

    /** Launch an app by package name (first step of almost every flow). */
    data class OpenApp(val packageName: String) : AutomationStep()

    /** Find an editable field (search bar, chat box, etc.) and type into it. */
    data class TypeText(val text: String) : AutomationStep()

    /**
     * Tap the first node whose text/content-description contains this string.
     * [optional] steps give up quickly (short timeout) and just move on to
     * the next step if nothing matches, instead of eating the full 5s step
     * timeout - for "tap this if it's there" cases like a Play button that
     * may or may not be showing depending on app state.
     */
    data class ClickText(val text: String, val exactMatch: Boolean = false, val optional: Boolean = false) : AutomationStep()

    /** Tap a node by its Android view id, e.g. "com.whatsapp:id/send". */
    data class ClickId(val viewId: String) : AutomationStep()

    /** Long-press the first node whose text/content-description contains this string. */
    data class LongPressText(val text: String) : AutomationStep()

    /** WhatsApp's send button has no reliable text, only a content-description/id - special-cased. */
    object ClickWhatsAppSend : AutomationStep()

    /** Find a label on screen (e.g. a dish name) and tap the nearest "Add" affordance next to it. */
    data class AddItemNear(val label: String) : AutomationStep()

    /** Tap a raw screen coordinate directly (used by the AI-assisted "smart click" resolver). */
    data class TapAt(val x: Float, val y: Float) : AutomationStep()

    /**
     * Waits until the foreground window actually belongs to [packageName]
     * before letting the rest of the flow proceed. Fixes the classic race
     * condition where a fixed `Wait(ms)` isn't long enough for a cold app
     * launch, so the next step (tapping "Search") fires while we're still
     * looking at the launcher or Brosco's own window and finds nothing.
     * Best-effort: if the package never matches, the normal per-step
     * timeout in the ticker still kicks in and the flow moves on rather
     * than hanging forever.
     */
    data class WaitForPackage(val packageName: String) : AutomationStep()

    /**
     * After a search, taps the best-matching result in the list rather than
     * blindly the exact text that was typed. Matching the typed query
     * verbatim against on-screen text is unreliable (a video/song title is
     * rarely identical to the search phrase, and the still-visible search
     * suggestion that echoes the query gets tapped instead of an actual
     * result). This scans top-to-bottom/left-to-right, skips the search
     * bar/header area (roughly the top [minYFraction] of the screen), skips
     * anything whose label matches [excludeText] (the suggestion row), then
     * - if [matchQuery] is given - scores the remaining candidates by word
     * overlap with it and taps the best-scoring one instead of just the
     * first. Falls back to the old "first plausible result" behavior if
     * nothing scores a match (e.g. a video title that shares no words with
     * the search phrase), so this never regresses the flows that relied on
     * plain positional first-result tapping.
     */
    data class ClickFirstResult(
        val excludeText: String = "",
        val minYFraction: Float = 0.14f,
        val matchQuery: String = ""
    ) : AutomationStep()

    /** Swipe across the screen in a direction. */
    data class Swipe(val direction: SwipeDirection) : AutomationStep()

    object ScrollForward : AutomationStep()
    object ScrollBackward : AutomationStep()
    object GoBack : AutomationStep()
    object GoHome : AutomationStep()

    /** Pause before trying the next step (screens need time to load). */
    data class Wait(val ms: Long) : AutomationStep()

    /** Say something out loud without touching the screen. */
    data class Speak(val text: String) : AutomationStep()
}

enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

/** A tappable element currently on screen, captured for the AI-assisted "smart click" resolver. */
data class ScreenElement(
    val index: Int,
    val label: String,
    val x: Float,
    val y: Float,
    val width: Float = 0f,
    val height: Float = 0f
)

/**
 * The most recent message bubble found in an open WhatsApp chat, captured
 * for the smart-reply feature. [isOutgoing] is a best-effort guess based on
 * which side of the screen the bubble sits on (WhatsApp aligns received
 * messages left, sent messages right) - there's no reliable text signal for
 * this, so it's a heuristic, not a guarantee.
 */
data class WhatsAppMessageSnapshot(val text: String, val isOutgoing: Boolean)
