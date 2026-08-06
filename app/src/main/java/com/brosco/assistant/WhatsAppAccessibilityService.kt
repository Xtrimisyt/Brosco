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
 * Brosco's hands: reads what's on screen and acts on it.
 *
 * IMPORTANT DESIGN NOTE: everything runs off a self-scheduled Handler loop
 * (the "ticker"), NOT off onAccessibilityEvent. Static screens (a chat
 * that isn't receiving new messages, a home feed that already finished
 * loading) don't generate accessibility events, so anything gated behind
 * "wait for the next event" can sit there forever. The ticker polls on a
 * fixed interval regardless of whether the OS sends us anything, so scroll/
 * swipe/long-press/click all fire promptly no matter what's on screen.
 * It also keeps onAccessibilityEvent itself doing effectively nothing,
 * which avoids blocking the OS's event-dispatch callback with heavy tree
 * walks - that was almost certainly what triggered the accessibility
 * service being flagged as "malfunctioning" before.
 */
class WhatsAppAccessibilityService : AccessibilityService() {

    companion object {

        private val taskQueue = ArrayDeque<AutomationStep>()
        private var onTaskUpdate: ((String) -> Unit)? = null
        private var onTaskDone: (() -> Unit)? = null

        private var currentStepStartedAt = 0L
        private var currentStepAttempts = 0

        private const val TICK_MS = 150L
        private const val STEP_TIMEOUT_MS = 5000L
        private const val MAX_STALL_TICKS = 30

        @Volatile
        private var instance: WhatsAppAccessibilityService? = null

        /**
         * Queue up one or more steps. A single command ("scroll down") is
         * just a one-item list; a whole flow ("search burger -> add to cart
         * -> open cart") is a longer one. Calling this again while a task is
         * still running replaces it - the newest command always wins.
         */
        fun runTask(steps: List<AutomationStep>, onUpdate: (String) -> Unit, onDone: () -> Unit = {}) {
            taskQueue.clear()
            taskQueue.addAll(steps)
            onTaskUpdate = onUpdate
            onTaskDone = onDone
            currentStepAttempts = 0
            currentStepStartedAt = System.currentTimeMillis()
        }

        fun cancelTask() {
            taskQueue.clear()
        }

        fun isTaskRunning(): Boolean = taskQueue.isNotEmpty()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var alive = false

    // Bounded-search budget shared by the tree-walking helpers below, reset
    // before each top-level search so one slow tick can't stall the ticker
    // on a huge/deep node tree (e.g. a long Instagram feed).
    private var visitBudget = 0
    private val maxVisits = 2500

    private val ticker = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (e: Exception) {
                Log.e("Brosco", "Tick failed: ${e.message}")
            }
            if (alive) mainHandler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        alive = true
        mainHandler.removeCallbacks(ticker)
        mainHandler.post(ticker)
    }

    override fun onDestroy() {
        super.onDestroy()
        alive = false
        if (instance === this) instance = null
        mainHandler.removeCallbacks(ticker)
    }

    // Deliberately near-empty: all real work happens in the ticker so this
    // callback returns immediately and never blocks the OS's event dispatch.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // ------------------------------------------------------------------
    // Ticker: one step of the queue per tick, retried until it works or
    // times out, then moves on ("Retry if a step fails").
    // ------------------------------------------------------------------
    private fun tick() {
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
            is AutomationStep.ClickWhatsAppSend -> {
                success = root != null && clickWhatsAppSend(root)
            }
            is AutomationStep.AddItemNear -> {
                success = root != null && clickAddNear(root, step.label)
            }
            is AutomationStep.LongPressText -> {
                success = root != null && longPressByText(root, step.text)
            }
            is AutomationStep.Swipe -> {
                success = performSwipe(step.direction)
            }
            AutomationStep.ScrollForward -> {
                success = root?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) == true
            }
            AutomationStep.ScrollBackward -> {
                success = root?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) == true
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
            if (taskQueue.isEmpty()) onTaskDone?.invoke()
        } else if (abandon || elapsed > STEP_TIMEOUT_MS || currentStepAttempts > MAX_STALL_TICKS) {
            Log.w("Brosco", "Giving up on step $step after $currentStepAttempts attempts")
            taskQueue.removeFirstOrNull()
            currentStepAttempts = 0
            currentStepStartedAt = System.currentTimeMillis()
            if (taskQueue.isEmpty()) onTaskDone?.invoke()
        }
        // else: leave it at the front, next tick (150ms) retries automatically
    }

    // ------------------------------------------------------------------
    // WhatsApp send button (id first, content-description fallback)
    // ------------------------------------------------------------------
    private fun clickWhatsAppSend(root: AccessibilityNodeInfo): Boolean {
        val byId = try { root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send") } catch (e: Exception) { emptyList() }
        if (byId.isNotEmpty()) {
            return byId[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        visitBudget = maxVisits
        val node = findByPredicate(root, depth = 0) {
            it.contentDescription?.toString()?.equals("Send", ignoreCase = true) == true
        }
        return node?.let { clickNodeOrAncestor(it) } ?: false
    }

    // ------------------------------------------------------------------
    // Click by text ("contains", forgiving of extra words on real UIs)
    // ------------------------------------------------------------------
    private fun clickByTextFuzzy(root: AccessibilityNodeInfo, target: String, exact: Boolean): Boolean {
        visitBudget = maxVisits
        val match = findNodeByText(root, target, exact, depth = 0) ?: return false
        return clickNodeOrAncestor(match)
    }

    private fun clickById(root: AccessibilityNodeInfo, id: String): Boolean {
        return try {
            val list = root.findAccessibilityNodeInfosByViewId(id)
            if (list.isNotEmpty()) clickNodeOrAncestor(list[0]) else false
        } catch (e: Exception) {
            false
        }
    }

    private fun findNodeByText(
        node: AccessibilityNodeInfo?,
        target: String,
        exact: Boolean,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 40 || visitBudget <= 0) return null
        visitBudget--

        val nodeText = node.text?.toString()
        val nodeDesc = node.contentDescription?.toString()

        val hit = if (exact) {
            nodeText.equals(target, ignoreCase = true) || nodeDesc.equals(target, ignoreCase = true)
        } else {
            fuzzyMatch(nodeText, nodeDesc, target)
        }
        if (hit) return node

        for (i in 0 until node.childCount) {
            val found = findNodeByText(node.getChild(i), target, exact, depth + 1)
            if (found != null) return found
        }
        return null
    }

    /** Contains-match first; falls back to "all significant words present" so
     *  slightly different on-screen wording still matches what was said. */
    private fun fuzzyMatch(nodeText: String?, nodeDesc: String?, target: String): Boolean {
        val hay1 = nodeText ?: ""
        val hay2 = nodeDesc ?: ""
        if (hay1.contains(target, ignoreCase = true) || hay2.contains(target, ignoreCase = true)) return true

        val words = target.split(Regex("\\s+")).filter { it.length > 2 }
        if (words.isEmpty()) return false
        return words.all { w -> hay1.contains(w, ignoreCase = true) || hay2.contains(w, ignoreCase = true) }
    }

    private fun findByPredicate(
        node: AccessibilityNodeInfo?,
        depth: Int,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 40 || visitBudget <= 0) return null
        visitBudget--
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val found = findByPredicate(node.getChild(i), depth + 1, predicate)
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
        return tapNodeCenter(node)
    }

    // ------------------------------------------------------------------
    // "Add [item]" - find the item's label, then the nearest Add button in
    // the same row/card (searches the item's own subtree, then walks up
    // through ancestor containers looking for a sibling "Add" affordance).
    // ------------------------------------------------------------------
    private fun clickAddNear(root: AccessibilityNodeInfo, label: String): Boolean {
        visitBudget = maxVisits
        val itemNode = findNodeByText(root, label, exact = false, depth = 0) ?: return false

        visitBudget = maxVisits
        findAddButton(itemNode, depth = 0)?.let { if (clickNodeOrAncestor(it)) return true }

        var container: AccessibilityNodeInfo? = itemNode.parent
        var hops = 0
        while (container != null && hops < 6) {
            visitBudget = maxVisits
            val addNode = findAddButton(container, depth = 0)
            if (addNode != null) return clickNodeOrAncestor(addNode)
            container = container.parent
            hops++
        }
        return false
    }

    private fun findAddButton(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 30 || visitBudget <= 0) return null
        visitBudget--

        val t = node.text?.toString()?.trim()
        val d = node.contentDescription?.toString()?.trim()
        val looksLikeAdd = t.equals("add", ignoreCase = true) || d.equals("add", ignoreCase = true) ||
            (t != null && t.length <= 6 && t.startsWith("add", ignoreCase = true)) ||
            (d != null && d.length <= 6 && d.startsWith("add", ignoreCase = true))

        if (looksLikeAdd) return node

        for (i in 0 until node.childCount) {
            val found = findAddButton(node.getChild(i), depth + 1)
            if (found != null) return found
        }
        return null
    }

    // ------------------------------------------------------------------
    // Type text into search bars / chat boxes / any input field
    // ------------------------------------------------------------------
    private fun typeIntoEditableField(root: AccessibilityNodeInfo, text: String): Boolean {
        visitBudget = maxVisits
        val field = findEditableNode(root, depth = 0) ?: return false

        // Tap first - some search bars won't accept ACTION_SET_TEXT until
        // they've actually been focused by a "real" interaction.
        field.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!field.isFocused) {
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        }

        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 40 || visitBudget <= 0) return null
        visitBudget--

        val editableByAction = node.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT.id } == true
        if (node.isEditable || editableByAction || node.className?.toString()?.contains("EditText") == true) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = findEditableNode(node.getChild(i), depth + 1)
            if (found != null) return found
        }
        return null
    }

    // ------------------------------------------------------------------
    // Long press
    // ------------------------------------------------------------------
    private fun longPressByText(root: AccessibilityNodeInfo, text: String): Boolean {
        visitBudget = maxVisits
        val node = findNodeByText(root, text, exact = false, depth = 0) ?: return false

        if (node.actionList?.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK.id } == true) {
            return node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        }
        return longPressNodeCenter(node)
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
    // Swipe gestures
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
        val stroke = GestureDescription.StrokeDescription(path, 0, 200)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }
}
