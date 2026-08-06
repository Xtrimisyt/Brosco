package com.brosco.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Brosco's hands: reads what's on screen and acts on it - tapping, typing,
 * scrolling, swiping and long-pressing. Two ways to use it:
 *
 * 1. One-off "pending" actions (legacy, still used by simple voice commands
 *    like "scroll down" or "click ok").
 * 2. A step queue (see AutomationStep) for whole flows like "search burger,
 *    add to cart, open cart" that need several screens in sequence.
 */
class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {
        // Existing WhatsApp support
        var pendingMessage: String? = null

        // Universal one-off actions (simple voice commands)
        var pendingClickText: String? = null
        var pendingClickId: String? = null
        var pendingTypeText: String? = null

        var pendingScrollForward = false
        var pendingScrollBackward = false

        var pendingBack = false
        var pendingHome = false

        var pendingLongPressText: String? = null
        var pendingSwipe: SwipeDirection? = null

        // ---- Multi-step task queue (Section 5: Planning) ----
        private val taskQueue = ArrayDeque<AutomationStep>()
        private var onTaskUpdate: ((String) -> Unit)? = null
        private var onTaskDone: (() -> Unit)? = null

        private var currentStepStartedAt = 0L
        private var currentStepAttempts = 0

        private const val STEP_POLL_MS = 350L
        private const val STEP_TIMEOUT_MS = 7000L // give up on a stuck step after this long
        private const val MAX_STALL_TICKS = 40    // safety cap regardless of timeout math

        @Volatile
        private var instance: WhatsAppAccessibilityService? = null

        /**
         * Queue up a whole task ("open zomato -> search burger -> add to cart
         * -> open cart"). Retries each step until it succeeds or times out,
         * then moves to the next one. [onUpdate] is called with a short
         * spoken status after each step; [onDone] fires when the queue drains.
         */
        fun runTask(steps: List<AutomationStep>, onUpdate: (String) -> Unit, onDone: () -> Unit = {}) {
            taskQueue.clear()
            taskQueue.addAll(steps)
            onTaskUpdate = onUpdate
            onTaskDone = onDone
            currentStepAttempts = 0
            currentStepStartedAt = System.currentTimeMillis()
            instance?.startTicking()
        }

        fun cancelTask() {
            taskQueue.clear()
        }

        fun isTaskRunning(): Boolean = taskQueue.isNotEmpty()
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            try {
                processQueue()
            } catch (e: Exception) {
                Log.e("Brosco", "Task tick failed: ${e.message}")
            }
            if (taskQueue.isNotEmpty()) {
                mainHandler.postDelayed(this, STEP_POLL_MS)
            }
        }
    }

    private fun startTicking() {
        mainHandler.removeCallbacks(ticker)
        mainHandler.post(ticker)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance === this) instance = null
        mainHandler.removeCallbacks(ticker)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event == null) return

            // Existing WhatsApp automation handling
            if (pendingMessage != null) {
                mainHandler.postDelayed({
                    try {
                        val root = rootInActiveWindow ?: return@postDelayed
                        val sendButton = findSendButton(root, depth = 0)
                        if (sendButton != null) {
                            sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            pendingMessage = null
                        }
                    } catch (e: Exception) {
                        Log.e("Brosco", "Send-tap failed: ${e.message}")
                    }
                }, 900)
            }

            // Universal one-off action dispatcher
            val root = rootInActiveWindow ?: return

            when {
                pendingClickText != null -> {
                    clickByTextFuzzy(root, pendingClickText!!)
                    pendingClickText = null
                }

                pendingClickId != null -> {
                    clickById(root, pendingClickId!!)
                    pendingClickId = null
                }

                pendingTypeText != null -> {
                    typeIntoEditableField(root, pendingTypeText!!)
                    pendingTypeText = null
                }

                pendingLongPressText != null -> {
                    longPressByText(root, pendingLongPressText!!)
                    pendingLongPressText = null
                }

                pendingSwipe != null -> {
                    performSwipe(pendingSwipe!!)
                    pendingSwipe = null
                }

                pendingScrollForward -> {
                    root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    pendingScrollForward = false
                }

                pendingScrollBackward -> {
                    root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                    pendingScrollBackward = false
                }

                pendingBack -> {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    pendingBack = false
                }

                pendingHome -> {
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    pendingHome = false
                }
            }
        } catch (e: Exception) {
            Log.e("Brosco", "Accessibility event failed: ${e.message}")
        }
    }

    // ------------------------------------------------------------------
    // Section 5: Planning - task queue executor
    // ------------------------------------------------------------------

    private fun processQueue() {
        val step = taskQueue.firstOrNull() ?: return
        val elapsed = System.currentTimeMillis() - currentStepStartedAt
        var success: Boolean
        var abandon = false

        val root = rootInActiveWindow

        when (step) {
            is AutomationStep.Wait -> {
                success = elapsed >= step.ms
            }
            is AutomationStep.OpenApp -> {
                val launch = packageManager.getLaunchIntentForPackage(step.packageName)
                if (launch != null) {
                    launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launch)
                    success = true
                } else {
                    onTaskUpdate?.invoke("That app doesn't seem to be installed.")
                    success = false
                    abandon = true
                }
            }
            is AutomationStep.TypeText -> {
                success = root != null && typeIntoEditableField(root, step.text)
            }
            is AutomationStep.ClickText -> {
                success = root != null && clickByTextFuzzy(root, step.text, step.exactMatch)
            }
            is AutomationStep.ClickId -> {
                success = root != null && clickById(root, step.viewId)
            }
            is AutomationStep.LongPressText -> {
                success = root != null && longPressByText(root, step.text)
            }
            is AutomationStep.Swipe -> {
                success = performSwipe(step.direction)
            }
            AutomationStep.ScrollForward -> {
                root?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                success = root != null
            }
            AutomationStep.ScrollBackward -> {
                root?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                success = root != null
            }
            AutomationStep.GoBack -> {
                success = performGlobalAction(GLOBAL_ACTION_BACK)
            }
            AutomationStep.GoHome -> {
                success = performGlobalAction(GLOBAL_ACTION_HOME)
            }
            is AutomationStep.Speak -> {
                onTaskUpdate?.invoke(step.text)
                success = true
            }
        }

        currentStepAttempts++

        if (success) {
            taskQueue.removeFirstOrNull()
            currentStepAttempts = 0
            currentStepStartedAt = System.currentTimeMillis()
            if (taskQueue.isEmpty()) {
                onTaskDone?.invoke()
            }
        } else if (abandon || elapsed > STEP_TIMEOUT_MS || currentStepAttempts > MAX_STALL_TICKS) {
            // Retry step (Section 5: "Retry if a step fails") already happened via polling;
            // if it still hasn't worked after the timeout, skip it and keep the rest going.
            Log.w("Brosco", "Giving up on step $step after $currentStepAttempts attempts")
            taskQueue.removeFirstOrNull()
            currentStepAttempts = 0
            currentStepStartedAt = System.currentTimeMillis()
            if (taskQueue.isEmpty()) {
                onTaskDone?.invoke()
            }
        }
        // else: leave the step at the front of the queue, next tick will retry it
    }

    // ------------------------------------------------------------------
    // Helper: find + press the WhatsApp send button (legacy)
    // ------------------------------------------------------------------
    private fun findSendButton(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 25) return null
        return try {
            val byId = node.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send")
            if (byId.isNotEmpty()) return byId[0]

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (child.contentDescription?.toString()?.equals("Send", ignoreCase = true) == true) {
                    return child
                }
                val found = findSendButton(child, depth + 1)
                if (found != null) return found
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Section 2: Click by exact text/id (kept for simple commands)
    // ------------------------------------------------------------------
    private fun clickByText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false

        if (node.text?.toString()?.equals(text, true) == true ||
            node.contentDescription?.toString()?.equals(text, true) == true) {

            if (node.isClickable) {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }

            node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            return true
        }

        for (i in 0 until node.childCount) {
            if (clickByText(node.getChild(i), text))
                return true
        }

        return false
    }

    /**
     * More forgiving click: matches on "contains" (case-insensitive) instead
     * of exact equality, and walks up to the nearest clickable ancestor if
     * the matched node itself can't be tapped. This is what app-automation
     * flows use, since real app UIs rarely have exact-string matches.
     */
    private fun clickByTextFuzzy(root: AccessibilityNodeInfo, target: String, exact: Boolean = false): Boolean {
        val match = findNodeByText(root, target, exact, depth = 0) ?: return false
        return clickNodeOrAncestor(match)
    }

    private fun findNodeByText(
        node: AccessibilityNodeInfo?,
        target: String,
        exact: Boolean,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null

        val nodeText = node.text?.toString()
        val nodeDesc = node.contentDescription?.toString()
        val hit = if (exact) {
            nodeText.equals(target, ignoreCase = true) || nodeDesc.equals(target, ignoreCase = true)
        } else {
            (nodeText?.contains(target, ignoreCase = true) == true) ||
                (nodeDesc?.contains(target, ignoreCase = true) == true)
        }
        if (hit) return node

        for (i in 0 until node.childCount) {
            val found = findNodeByText(node.getChild(i), target, exact, depth + 1)
            if (found != null) return found
        }
        return null
    }

    private fun clickNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var hops = 0
        while (current != null && hops < 8) {
            if (current.isClickable) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current = current.parent
            hops++
        }
        // fall back to tapping the original node's centre via a gesture
        return tapNodeCenter(node)
    }

    private fun clickById(node: AccessibilityNodeInfo?, id: String): Boolean {
        if (node == null) return false

        return try {
            val list = node.findAccessibilityNodeInfosByViewId(id)

            if (list.isNotEmpty()) {
                clickNodeOrAncestor(list[0])
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Section 2: Type text into search bars / chat boxes / any input
    // ------------------------------------------------------------------
    private fun typeIntoEditableField(root: AccessibilityNodeInfo, text: String): Boolean {
        val field = findEditableNode(root, depth = 0) ?: return false

        // Make sure it's focused first - some apps ignore ACTION_SET_TEXT otherwise.
        if (!field.isFocused) {
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }

        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 40) return null
        if (node.isEditable || node.className?.toString()?.contains("EditText") == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = findEditableNode(node.getChild(i), depth + 1)
            if (found != null) return found
        }
        return null
    }

    // ------------------------------------------------------------------
    // Section 2: Long press
    // ------------------------------------------------------------------
    private fun longPressByText(root: AccessibilityNodeInfo, text: String): Boolean {
        val node = findNodeByText(root, text, exact = false, depth = 0) ?: return false

        // Prefer the accessibility long-click action when available - more reliable
        // than a synthetic gesture and doesn't depend on the node's exact bounds.
        return if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
    true
} else {
    longPressNodeCenter(node)
        }
    }

    private fun longPressNodeCenter(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        return dispatchTap(bounds.centerX().toFloat(), bounds.centerY().toFloat(), holdMs = 600)
    }

    private fun tapNodeCenter(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        return dispatchTap(bounds.centerX().toFloat(), bounds.centerY().toFloat(), holdMs = 60)
    }

    private fun dispatchTap(x: Float, y: Float, holdMs: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, holdMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    // ------------------------------------------------------------------
    // Section 2: Swipe gestures
    // ------------------------------------------------------------------
    private fun performSwipe(direction: SwipeDirection): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val centerX = width / 2f
        val centerY = height / 2f

        val (startX, startY, endX, endY) = when (direction) {
            SwipeDirection.UP -> listOf(centerX, height * 0.75f, centerX, height * 0.25f)
            SwipeDirection.DOWN -> listOf(centerX, height * 0.25f, centerX, height * 0.75f)
            SwipeDirection.LEFT -> listOf(width * 0.85f, centerY, width * 0.15f, centerY)
            SwipeDirection.RIGHT -> listOf(width * 0.15f, centerY, width * 0.85f, centerY)
        }

        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 250)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    override fun onInterrupt() {}
}
