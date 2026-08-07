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

    /** Tap the first node whose text/content-description contains this string. */
    data class ClickText(val text: String, val exactMatch: Boolean = false) : AutomationStep()

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
data class ScreenElement(val index: Int, val label: String, val x: Float, val y: Float)
