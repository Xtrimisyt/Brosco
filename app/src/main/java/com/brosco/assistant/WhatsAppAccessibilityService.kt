package com.brosco.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
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
        private var onTaskFail: (() -> Unit)? = null

        // Set the moment ANY non-optional step gets abandoned (times out /
        // exhausts its retries) during the current task. Used to tell a
        // flow's real outcome apart from a hollow "done" - without this,
        // runTask() called onTaskDone unconditionally once the queue drained,
        // whether every step actually landed or every single one silently
        // failed and just got skipped. That's the core of "it fails
        // silently": the user would hear "Farmhouse is in the cart" (or
        // nothing at all, if even the FIRST speak() never got scheduled)
        // with nothing having actually happened on screen.
        private var hadStepFailure = false

        private var currentStepStartedAt = 0L
        private var currentStepAttempts = 0

        // Was 150ms. ClickText/ClickFirstResult/TypeText all already retry
        // every tick until they land or their own timeout expires - so this
        // interval is the main thing standing between "found it" and "tapped
        // it" on every step of every flow. Halving it makes every automation
        // (Saavn, Spotify, YouTube, WhatsApp, food orders) visibly snappier
        // for free, since 90ms is still well inside a comfortable per-tick
        // budget (tickBudgetMs below caps each tick's own work at 120ms).
        private const val TICK_MS = 90L
        private const val STEP_TIMEOUT_MS = 5000L
        private const val MAX_STALL_TICKS = 30

        @Volatile
        private var instance: WhatsAppAccessibilityService? = null

        /**
         * Queue up one or more steps. A single command ("scroll down") is
         * just a one-item list; a whole flow ("search burger -> add to cart
         * -> open cart") is a longer one. Calling this again while a task is
         * still running replaces it - the newest command always wins.
         *
         * [onFail] fires instead of [onDone] if any required (non-optional)
         * step in the flow had to be abandoned - e.g. "Search" was never
         * found because the app hadn't finished loading, or the item never
         * matched anything on screen. Callers should use this to speak an
         * honest "that didn't work" instead of only ever having a single
         * "success" message that fires no matter what actually happened.
         *
         * If the Accessibility Service itself isn't connected (toggled off
         * in Settings, or killed and not yet reconnected), the task is
         * refused outright and [onFail] fires immediately via [onUpdate] -
         * previously this silently queued steps nothing was ever going to
         * process, since the ticker that drains the queue only runs on a
         * live service instance.
         */
        fun runTask(
            steps: List<AutomationStep>,
            onUpdate: (String) -> Unit,
            onDone: () -> Unit = {},
            onFail: () -> Unit = {}
        ) {
            if (instance == null) {
                Log.w("Brosco", "runTask called with no connected accessibility service")
                onUpdate("I can't tap anything on screen right now - Brosco's Accessibility Service looks like it's off. Turn it back on in Settings and try again.")
                onFail()
                return
            }
            taskQueue.clear()
            taskQueue.addAll(steps)
            onTaskUpdate = onUpdate
            onTaskDone = onDone
            onTaskFail = onFail
            hadStepFailure = false
            currentStepAttempts = 0
            currentStepStartedAt = System.currentTimeMillis()
            instance?.acquireTaskWakeLock()
        }

        fun cancelTask() {
            taskQueue.clear()
            instance?.releaseTaskWakeLock()
        }

        fun isTaskRunning(): Boolean = taskQueue.isNotEmpty()

        /**
         * Section 4 (Vision, lightweight version): instead of analysing pixels,
         * we list every currently-tappable element with its label and screen
         * position. CommandProcessor sends this list + the spoken phrase to the
         * AI to resolve ordinals ("the first video") and vague references
         * ("that", "the one next to it") that plain text matching can't handle.
         */
        fun snapshotScreen(): List<ScreenElement> =
            try {
                instance?.captureScreenSnapshot() ?: emptyList()
            } catch (e: Exception) {
                // A node can go stale mid-walk if the window changes under us;
                // never let that escape as an uncaught exception on whatever
                // thread called this (a coroutine crash here can take the
                // whole app process down, which is what the OS then reports
                // as the accessibility service "malfunctioning").
                Log.e("Brosco", "snapshotScreen failed: ${e.message}")
                emptyList()
            }

        /**
         * "Vision" for general Q&A rather than tap-resolution: a best-effort
         * plain-text reading of whatever's currently on screen (which app is
         * foreground + the visible text/labels on it), so the AI chat path
         * can actually answer things like "what does this say" or "reply to
         * this" instead of having zero idea what Shrey is looking at. Capped
         * hard so it never balloons a chat prompt.
         */
        fun captureScreenText(maxChars: Int = 1500): String =
            try {
                instance?.captureScreenTextSummary(maxChars) ?: ""
            } catch (e: Exception) {
                Log.e("Brosco", "captureScreenText failed: ${e.message}")
                ""
            }

        /**
         * Section 6 (smart replies): best-effort read of the most recent
         * message bubble in an open WhatsApp chat, plus a guess at whether
         * it's incoming or outgoing. Returns null if WhatsApp isn't the
         * foreground app or nothing readable was found.
         */
        fun captureLatestWhatsAppMessage(): WhatsAppMessageSnapshot? =
            try {
                instance?.captureLatestWhatsAppMessageInternal()
            } catch (e: Exception) {
                Log.e("Brosco", "captureLatestWhatsAppMessage failed: ${e.message}")
                null
            }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    // The ticker used to run on mainHandler. That's the piece the earlier
    // fixes (global exception handler, tickBudgetMs, wake lock timeout)
    // didn't cover: node-tree calls like node.getChild()/node.text/
    // findAccessibilityNodeInfosByViewId are Binder IPC calls into ANOTHER
    // app's process. tickBudgetMs only checks the clock BETWEEN calls - it
    // can't preempt one already in flight. If the foreground app is slow,
    // busy, or under memory pressure, a single call can block far past
    // 120ms with nothing on our side able to interrupt it. Do that on the
    // main thread and Android's accessibility-responsiveness watchdog (this
    // is tracked separately from - and doesn't require - a full app ANR)
    // can flag the service as unresponsive and disable it: exactly the
    // silent, no-crash, "have to toggle it back on" pattern being reported,
    // as opposed to a visible crash/force-close.
    //
    // AccessibilityNodeInfo access and service calls like performAction /
    // dispatchGesture are safe to make from a background thread, so the fix
    // is simply: run the ticker there instead. Worst case a node query now
    // stalls a throwaway background thread instead of the one thread the
    // whole system depends on to consider the app "responding".
    // var, not val: a watchdog below can tear this down and rebuild it if
    // the thread itself ever dies (see ticker/watchdog comments further
    // down) - a val HandlerThread that dies has no way back without
    // recreating the whole service.
    private var tickerThread = HandlerThread("BrocoTicker").apply { start() }
    private var tickerHandler = Handler(tickerThread.looper)
    private var alive = false
    private var wakeLock: PowerManager.WakeLock? = null

    // Bumped by the ticker on every tick, checked by the watchdog below.
    // This is how the watchdog tells "ticker thread quietly died" apart
    // from "nothing to do right now" - the ticker reschedules itself
    // unconditionally every TICK_MS regardless of whether there's a task
    // in the queue, so a stalled timestamp always means the thread itself
    // stopped, not just an idle queue.
    @Volatile
    private var lastTickAt = 0L

    private val watchdogHandler = Handler(Looper.getMainLooper())
    // Checked every 8s, and a ticker is only considered stalled after 8s of
    // silence - comfortably above TICK_MS (90ms) so a healthy ticker never
    // trips this, but still well clear of even a slow multi-second Binder
    // call into another app's process, so a restart never fires mid-tick.
    private val watchdogIntervalMs = 8000L

    // Automations that open a fresh app (order flows, music search) take
    // several seconds of typing/waiting/tapping - long enough that if the
    // screen is off or the device has gone into Doze, the OS can throttle
    // the Handler ticks driving this ticker and the flow just stalls
    // partway through with nothing to show for it. That's the "background
    // Brosco fails silently" report: it's most visible on exactly these
    // longer flows, not quick single taps. Holding a short partial wake
    // lock for the duration of a task keeps the CPU awake so the ticker
    // actually keeps firing. Capped hard at 30s (not tied to task length)
    // so a stuck/never-finishing task can never hold it indefinitely and
    // drain the battery - it always self-releases even if release() is
    // somehow never called.
    private fun acquireTaskWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
            val lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Brosco:AutomationTask")
            lock.setReferenceCounted(false)
            lock.acquire(30_000L)
            wakeLock = lock
        } catch (e: Exception) {
            Log.e("Brosco", "acquireTaskWakeLock failed: ${e.message}")
        }
    }

    private fun releaseTaskWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) {
            Log.e("Brosco", "releaseTaskWakeLock failed: ${e.message}")
        }
    }

    // Bounded-search budget shared by the tree-walking helpers below, reset
    // before each top-level search so one slow tick can't stall the ticker
    // on a huge/deep node tree (e.g. a long Instagram feed).
    private var visitBudget = 0
    private val maxVisits = 800

    // Hard wall-clock cap per tick, IN ADDITION to the node-count budget.
    // Each node access can be a Binder IPC call into another app's process,
    // so visit count alone doesn't bound worst-case time if that app is
    // slow to respond - this does. This is almost certainly what was
    // tripping Android's "service is malfunctioning" detector: without it,
    // a single tick (e.g. the multi-hop "add item near" search) could block
    // the main thread long enough to get flagged as unresponsive.
    private var tickDeadline = 0L
    private val tickBudgetMs = 120L

    private fun withinBudget(): Boolean = visitBudget > 0 && System.currentTimeMillis() < tickDeadline

    // "Optional" ClickText steps (e.g. a Play button that may already be
    // gone because playback auto-started) give up fast instead of eating
    // the full step timeout, so a flow doesn't stall for 5s over a tap that
    // was never going to land.
    private fun effectiveTimeoutMs(step: AutomationStep): Long =
        if (step is AutomationStep.ClickText && step.optional) 900L else STEP_TIMEOUT_MS

    private val ticker = object : Runnable {
        override fun run() {
            try {
                tick()
            } catch (e: Throwable) {
                // Throwable, not Exception: an Error here (e.g. from a
                // pathological node tree) must not be allowed to unwind
                // past this catch. If it did, it would hit tickerThread's
                // own uncaught-exception handler and quietly end that
                // thread's Looper - after which tickerHandler.postDelayed
                // below would simply never run again. That's the exact
                // "stops responding, have to toggle it off and on" report:
                // no crash, no "malfunctioning" banner, just a ticker that
                // silently stopped ticking forever. The watchdog below is
                // the second line of defense in case this one is somehow
                // bypassed (e.g. a native crash inside a Binder call).
                Log.e("Brosco", "Tick failed: ${e.message}", e)
            }
            lastTickAt = System.currentTimeMillis()
            if (alive) tickerHandler.postDelayed(this, TICK_MS)
        }
    }

    // Watches the ticker from the MAIN thread (deliberately a different
    // thread than the one being watched) and rebuilds tickerThread from
    // scratch if it ever goes quiet. Covers every way the ticker could stop
    // ticking that isn't a clean onDestroy: the HandlerThread dying from an
    // uncaught Throwable, the OS aggressively freezing/killing a background
    // thread under memory pressure or Doze, or anything else nobody thought
    // to guard against explicitly. This is what turns "stuck until you
    // manually flip Accessibility off and on" into "recovers on its own
    // within ~5 seconds" - the whole point of the fix.
    private val watchdog = object : Runnable {
        override fun run() {
            if (!alive) return
            val staleFor = System.currentTimeMillis() - lastTickAt
            if (lastTickAt != 0L && staleFor > watchdogIntervalMs) {
                Log.w("Brosco", "Ticker stalled for ${staleFor}ms - restarting ticker thread")
                restartTicker()
            }
            watchdogHandler.postDelayed(this, watchdogIntervalMs)
        }
    }

    private fun restartTicker() {
        try {
            tickerHandler.removeCallbacks(ticker)
            if (tickerThread.isAlive) tickerThread.quitSafely()
        } catch (e: Exception) {
            Log.e("Brosco", "restartTicker cleanup failed: ${e.message}", e)
        }
        tickerThread = HandlerThread("BrocoTicker").apply { start() }
        tickerHandler = Handler(tickerThread.looper)
        lastTickAt = System.currentTimeMillis()
        tickerHandler.post(ticker)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        alive = true
        lastTickAt = System.currentTimeMillis()
        tickerHandler.removeCallbacks(ticker)
        tickerHandler.post(ticker)
        watchdogHandler.removeCallbacks(watchdog)
        watchdogHandler.postDelayed(watchdog, watchdogIntervalMs)
    }

    override fun onDestroy() {
        super.onDestroy()
        alive = false
        if (instance === this) instance = null
        watchdogHandler.removeCallbacks(watchdog)
        tickerHandler.removeCallbacks(ticker)
        tickerThread.quitSafely()
        releaseTaskWakeLock()
    }

    // Deliberately near-empty: all real work happens in the ticker so this
    // callback returns immediately and never blocks the OS's event dispatch.
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    // Callers of runTask() (CommandProcessor's speak/UI-update closures)
    // were written assuming they'd fire on the main thread, same as
    // everything else in the app. Now that the ticker itself runs on
    // tickerHandler's background thread, hop back to the main thread before
    // invoking them rather than assuming TTS/UI code downstream is safe to
    // call from anywhere.
    private fun notifyMain(block: () -> Unit) {
        mainHandler.post {
            try {
                block()
            } catch (e: Exception) {
                Log.e("Brosco", "Task callback failed: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------
    // Ticker: one step of the queue per tick, retried until it works or
    // times out, then moves on ("Retry if a step fails").
    // ------------------------------------------------------------------
    private fun tick() {
        val step = taskQueue.firstOrNull() ?: return
        tickDeadline = System.currentTimeMillis() + tickBudgetMs
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
                    onTaskUpdate?.let { cb -> notifyMain { cb("That app doesn't seem to be installed.") } }
                    success = false
                    abandon = true
                }
            }
            is AutomationStep.TypeText -> {
                success = root != null && typeIntoEditableField(root, step.text)
            }
            is AutomationStep.ClickText -> {
                success = root != null && clickByTextFuzzy(root, step.text, step.exactMatch, step.minYFraction)
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
            is AutomationStep.TapAt -> {
                success = dispatchTap(step.x, step.y, holdMs = 60)
            }
            is AutomationStep.WaitForPackage -> {
                success = root?.packageName?.toString() == step.packageName
            }
            is AutomationStep.ClickFirstResult -> {
                success = root != null && clickFirstResult(root, step.excludeText, step.minYFraction, step.matchQuery)
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
                onTaskUpdate?.let { cb -> notifyMain { cb(step.text) } }
                success = true
            }
        }

        currentStepAttempts++

        if (success) {
            taskQueue.removeFirstOrNull()
            currentStepAttempts = 0
            currentStepStartedAt = System.currentTimeMillis()
            if (taskQueue.isEmpty()) finishTask()
        } else if (abandon || elapsed > effectiveTimeoutMs(step) || currentStepAttempts > MAX_STALL_TICKS) {
            Log.w("Brosco", "Giving up on step $step after $currentStepAttempts attempts")
            // Optional steps (a "Play" button that may already be gone
            // because playback auto-started, a "Playlists" filter chip that
            // might not exist on this layout) are EXPECTED to sometimes
            // find nothing - that's not a broken flow, so only REQUIRED
            // steps failing counts as a real failure worth reporting.
            val isOptionalStep = step is AutomationStep.ClickText && step.optional
            if (!isOptionalStep) hadStepFailure = true
            taskQueue.removeFirstOrNull()
            currentStepAttempts = 0
            currentStepStartedAt = System.currentTimeMillis()
            if (taskQueue.isEmpty()) finishTask()
        }
        // else: leave it at the front, next tick (150ms) retries automatically
    }

    private fun finishTask() {
        releaseTaskWakeLock()
        if (hadStepFailure) {
            onTaskFail?.let { cb -> notifyMain { cb() } }
        } else {
            onTaskDone?.let { cb -> notifyMain { cb() } }
        }
    }

    // ------------------------------------------------------------------
    // Screen snapshot for AI-assisted "smart click" (ordinals, "this"/"that")
    // ------------------------------------------------------------------
    private fun captureScreenSnapshot(): List<ScreenElement> {
        val root = rootInActiveWindow ?: return emptyList()
        visitBudget = maxVisits
        val raw = mutableListOf<ScreenElement>()
        collectClickables(root, depth = 0, raw)

        // Reading order: top-to-bottom, then left-to-right - matches how a
        // person would say "the first one" while looking at the screen.
        return raw.sortedWith(compareBy({ it.y }, { it.x }))
            .take(50)
            .mapIndexed { i, e -> e.copy(index = i + 1) }
    }

    private fun collectClickables(node: AccessibilityNodeInfo?, depth: Int, out: MutableList<ScreenElement>) {
        if (node == null || depth > 40 || !withinBudget()) return
        visitBudget--

        if ((node.isClickable || node.isLongClickable) && node.isVisibleToUser) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                val label = node.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.className?.toString()?.substringAfterLast(".") ?: ""

                if (label.isNotBlank()) {
                    out.add(
                        ScreenElement(
                            0,
                            label.take(60),
                            bounds.centerX().toFloat(),
                            bounds.centerY().toFloat(),
                            width = bounds.width().toFloat(),
                            height = bounds.height().toFloat()
                        )
                    )
                }
            }
        }

        for (i in 0 until node.childCount) {
            collectClickables(node.getChild(i), depth + 1, out)
        }
    }

    // ------------------------------------------------------------------
    // Every node carrying its own visible text, clickable or not - used to
    // find a card's TITLE for matching purposes. collectClickables alone
    // isn't enough for this: a product card is very often a plain TextView
    // ("Farmhouse") sitting next to a separately-clickable "Add" button or
    // wrapped by a row container, and NEITHER of those clickable nodes
    // reliably carries the item's own name as its accessibility label (the
    // button just says "Add", the row container frequently has no
    // text/contentDescription of its own at all). Matching against titles
    // like this and then walking up to whatever's actually clickable (see
    // clickNodeOrAncestor) is what makes "add farmhouse" land on the
    // Farmhouse card instead of the first result / whichever other card
    // happens to mention "farmhouse" in its own description text.
    // ------------------------------------------------------------------
    private fun collectTextElements(node: AccessibilityNodeInfo?, depth: Int, out: MutableList<ScreenElement>) {
        if (node == null || depth > 40 || !withinBudget()) return
        visitBudget--

        val text = node.text?.toString()?.trim()
        if (!text.isNullOrBlank() && node.isVisibleToUser) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                out.add(
                    ScreenElement(
                        0,
                        text.take(120),
                        bounds.centerX().toFloat(),
                        bounds.centerY().toFloat(),
                        width = bounds.width().toFloat(),
                        height = bounds.height().toFloat()
                    )
                )
            }
        }

        for (i in 0 until node.childCount) {
            collectTextElements(node.getChild(i), depth + 1, out)
        }
    }

    // ------------------------------------------------------------------
    // Plain-text screen reading for general AI Q&A (see captureScreenText).
    // Unlike collectClickables this walks EVERY node with visible text, not
    // just clickable ones - reading a chat bubble or an article needs the
    // static text nodes, not the buttons around them.
    // ------------------------------------------------------------------
    private fun captureScreenTextSummary(maxChars: Int): String {
        val root = rootInActiveWindow ?: return ""
        visitBudget = maxVisits
        val lines = mutableListOf<String>()
        collectVisibleText(root, depth = 0, lines)

        val appLabel = try {
            val pkg = root.packageName?.toString()
            val appInfo = pkg?.let { packageManager.getApplicationInfo(it, 0) }
            appInfo?.let { packageManager.getApplicationLabel(it).toString() } ?: pkg
        } catch (e: Exception) {
            root.packageName?.toString()
        }

        val header = if (appLabel != null) "Currently open: $appLabel\n" else ""
        val body = lines.distinct().joinToString("\n")
        val combined = header + body
        return if (combined.length > maxChars) combined.take(maxChars) else combined
    }

    private fun collectVisibleText(node: AccessibilityNodeInfo?, depth: Int, out: MutableList<String>) {
        if (node == null || depth > 40 || !withinBudget() || out.size >= 200) return
        visitBudget--

        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrBlank() && !node.isEditable) {
                out.add(text.take(140))
            }
        }

        for (i in 0 until node.childCount) {
            collectVisibleText(node.getChild(i), depth + 1, out)
        }
    }

    // ------------------------------------------------------------------
    // Section 6: latest WhatsApp message + rough incoming/outgoing guess.
    // WhatsApp tags its message bubble text with a stable view id
    // ("com.whatsapp:id/message_text") which we prefer - if a future
    // WhatsApp version changes that id, fall back to any non-editable text
    // node that doesn't look like chrome (timestamps, "Online", the empty
    // compose-box hint, etc). Whichever pool has hits, the bubble with the
    // lowest bottom edge (furthest down the screen) is the most recent
    // message, since the conversation flows top-to-bottom with newest last.
    // ------------------------------------------------------------------
    private fun captureLatestWhatsAppMessageInternal(): WhatsAppMessageSnapshot? {
        val root = rootInActiveWindow ?: return null
        val pkg = root.packageName?.toString()
        if (pkg != "com.whatsapp" && pkg != "com.whatsapp.w4b") return null

        visitBudget = maxVisits
        val idMatches = mutableListOf<Pair<String, Rect>>()
        val fallback = mutableListOf<Pair<String, Rect>>()
        collectMessageCandidates(root, depth = 0, idMatches, fallback)

        val pool = idMatches.ifEmpty { fallback }
        val (text, bounds) = pool.maxByOrNull { it.second.bottom } ?: return null

        val screenWidth = resources.displayMetrics.widthPixels
        // WhatsApp aligns received bubbles left, sent bubbles right - not a
        // perfect signal (e.g. system messages centered) but good enough as
        // a best-effort guess.
        val isOutgoing = bounds.centerX() > screenWidth / 2
        return WhatsAppMessageSnapshot(text, isOutgoing)
    }

    private fun collectMessageCandidates(
        node: AccessibilityNodeInfo?,
        depth: Int,
        idMatches: MutableList<Pair<String, Rect>>,
        fallback: MutableList<Pair<String, Rect>>
    ) {
        if (node == null || depth > 40 || !withinBudget()) return
        visitBudget--

        if (node.isVisibleToUser && !node.isEditable) {
            val text = node.text?.toString()?.trim()
            if (!text.isNullOrBlank()) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    val idName = try { node.viewIdResourceName } catch (e: Exception) { null }
                    val entry = text.take(500) to bounds
                    if (idName == "com.whatsapp:id/message_text") {
                        idMatches.add(entry)
                    } else if (!looksLikeChatChrome(text)) {
                        fallback.add(entry)
                    }
                }
            }
        }

        for (i in 0 until node.childCount) {
            collectMessageCandidates(node.getChild(i), depth + 1, idMatches, fallback)
        }
    }

    private val timestampPattern = Regex("^\\d{1,2}:\\d{2}( ?[APap][Mm])?$")
    private val chatChromeWords = setOf(
        "type a message", "online", "typing...", "tap to add a caption",
        "today", "yesterday", "message", "search"
    )

    private fun looksLikeChatChrome(text: String): Boolean {
        val t = text.trim()
        if (t.length <= 1) return true
        if (timestampPattern.matches(t)) return true
        return chatChromeWords.contains(t.lowercase())
    }

    // Generic words that show up in almost every item name in a food-app
    // search ("pizza", "medium", "the") and so carry no distinguishing
    // signal - stripped before scoring so e.g. "peppy paneer" doesn't just
    // match on "pizza" being present in literally every row.
    private val matchStopWords = setOf(
        "pizza", "the", "a", "an", "and", "with", "medium", "large", "small",
        "regular", "of", "on", "for", "please"
    )

    private fun matchTokens(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 1 && it !in matchStopWords }
            .toSet()

    /**
     * Scores each candidate by how well it matches [matchQuery] and returns
     * the highest-scoring one. Returns null if [matchQuery] is blank or
     * nothing scores above zero, so callers can fall back to plain
     * positional selection.
     *
     * Raw shared-word count alone isn't enough: a family/combo item's
     * description often lists every other item on the menu by name (e.g. a
     * "Big Big 6in1 Pizza" describing itself as containing "...Peppy Paneer,
     * Farmhouse, Mexican Green Wave...") so it can share a word with the
     * query by pure coincidence, tying - or even beating - the item actually
     * being asked for. What actually distinguishes the right item is that
     * the match shows up EARLY in its label, since a card's own name is
     * always the first thing in its accessibility label, before any
     * description/price/combo listing. So the position of the first
     * matching word is weighted far more heavily than the raw count.
     */
    private fun bestScoringCandidate(candidates: List<ScreenElement>, matchQuery: String): ScreenElement? {
        val queryTokens = matchTokens(matchQuery)
        if (queryTokens.isEmpty()) return null

        var best: ScreenElement? = null
        var bestScore = 0.0
        for (candidate in candidates) {
            val labelWords = candidate.label.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
            val labelTokens = labelWords.filter { it.length > 1 && it !in matchStopWords }.toSet()
            val overlap = queryTokens.count { it in labelTokens }
            if (overlap == 0) continue

            val firstMatchIndex = labelWords.indexOfFirst { it in queryTokens }
            val prominence = if (firstMatchIndex >= 0) 1.0 / (firstMatchIndex + 1) else 0.0
            val score = overlap + prominence * 20.0

            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    // Overflow/kebab ("⋮") menu buttons almost always carry an accessibility
    // label that either names the action itself ("More options", "Menu") or -
    // even more treacherously - includes the row's own title so a screen
    // reader can announce *which* row's menu it is ("More options for What
    // Makes You Beautiful"). That second pattern is exactly what was making
    // ClickFirstResult's word-overlap scoring pick the three-dot button over
    // the actual song row: the button's label shares every word with the
    // query, while the row itself may have no text/description of its own.
    // These controls never represent something you'd actually want tapped
    // as a "result", so they're excluded outright rather than merely scored.
    private val overflowControlPhrases = listOf(
        "more options", "options for", "song options", "overflow menu",
        "context menu", "open menu", "show menu"
    )

    private fun isOverflowControlLabel(label: String): Boolean {
        val l = label.trim().lowercase()
        if (l.isBlank()) return false
        if (l == "menu" || l == "options" || l == "more" || l == "…" || l == "⋮" || l == "...") return true
        return overflowControlPhrases.any { l.contains(it) }
    }

    // Screen furniture that sits ABOVE the real results/list on a library or
    // search screen and can otherwise get scooped up by position-based
    // fallback matching - e.g. "Create Playlist" on JioSaavn's Library tab,
    // which sits right above the user's actual playlists and was winning
    // the tap instead of the playlist itself. minYFraction is the first line
    // of defence against this; this is the second, name-based one, since
    // relying on position alone assumes the control's actual touch target
    // never extends lower than its visible text/icon - not guaranteed.
    private val nonResultControlPhrases = listOf(
        "create playlist", "create a playlist", "new playlist"
    )

    private fun isNonResultControlLabel(label: String): Boolean {
        val l = label.trim().lowercase()
        if (l.isBlank()) return false
        return nonResultControlPhrases.any { l.contains(it) }
    }

    // Icon-only controls (overflow dots, share/heart/download glyphs) are
    // small, roughly square touch targets - very different from a search
    // result row, which spans most of the screen width. When at least one
    // wider, row-shaped candidate exists, small square ones are dropped so
    // an unlabeled icon can't outrank the actual content row it sits next to.
    private fun isIconShaped(element: ScreenElement): Boolean {
        val density = resources.displayMetrics.density
        val iconMaxPx = 64 * density // ~64dp, generous for a tap target
        return element.width in 1f..iconMaxPx && element.height in 1f..iconMaxPx
    }

    // ------------------------------------------------------------------
    // First real result after a search - see AutomationStep.ClickFirstResult
    // for why this exists instead of matching the typed query verbatim.
    // ------------------------------------------------------------------
    private fun clickFirstResult(
        root: AccessibilityNodeInfo,
        excludeText: String,
        minYFraction: Float,
        matchQuery: String = ""
    ): Boolean {
        visitBudget = maxVisits
        val raw = mutableListOf<ScreenElement>()
        collectClickables(root, depth = 0, raw)
        if (raw.isEmpty()) return false

        val screenHeight = resources.displayMetrics.heightPixels.toFloat()
        val cutoff = screenHeight * minYFraction
        val needle = excludeText.trim()

        // ---- Title-first pass ----
        // Try matching the query against every visible piece of TEXT on
        // screen (not just clickable nodes) before falling back to
        // clickable-only scoring below. This is what actually finds "the
        // Farmhouse card" rather than "whatever's clickable and happens to
        // score non-zero" - see collectTextElements for why the clickable
        // nodes alone (bare "Add" buttons, unlabeled row containers) can't
        // be trusted to carry the item's own name.
        if (matchQuery.isNotBlank()) {
            visitBudget = maxVisits
            val textRaw = mutableListOf<ScreenElement>()
            collectTextElements(root, depth = 0, textRaw)
            val titleCandidates = textRaw
                .filter { it.y >= cutoff }
                .filter { !isOverflowControlLabel(it.label) }
                .filter { !isNonResultControlLabel(it.label) }
                .filter { !(needle.isNotBlank() && it.label.equals(needle, ignoreCase = true)) }
            val titleMatch = bestScoringCandidate(titleCandidates, matchQuery)
            if (titleMatch != null) {
                visitBudget = maxVisits
                val titleNode = findByPredicate(root, depth = 0) {
                    val bounds = Rect()
                    it.getBoundsInScreen(bounds)
                    !bounds.isEmpty &&
                        bounds.centerX().toFloat() == titleMatch.x &&
                        bounds.centerY().toFloat() == titleMatch.y &&
                        it.text?.toString()?.trim() == titleMatch.label
                }
                val tapped = if (titleNode != null) clickNodeOrAncestor(titleNode)
                             else dispatchTap(titleMatch.x, titleMatch.y, holdMs = 60)
                if (tapped) return true
                // fall through to the old logic below if the tap itself failed
            }
        }

        val belowCutoff = raw
            .sortedWith(compareBy({ it.y }, { it.x }))
            .filter { it.y >= cutoff }
            .filter { !isOverflowControlLabel(it.label) }
            .filter { !isNonResultControlLabel(it.label) }

        // Icon-shaped controls (overflow dots, share/heart glyphs) are
        // dropped when a wider, row-shaped candidate exists - but if that
        // leaves nothing at all, fall back to the unfiltered set rather than
        // stalling the flow.
        val hasWideCandidate = belowCutoff.any { !isIconShaped(it) }
        val shaped = if (hasWideCandidate) belowCutoff.filterNot { isIconShaped(it) } else belowCutoff

        // Score BEFORE stripping the "echo" of the typed query - a result
        // whose displayed name matches the query almost verbatim (e.g.
        // ordering "margherita pizza" and the menu literally has a
        // "Margherita Pizza" item) is the *strongest* possible signal that
        // it's the right one, not a reason to exclude it. The exact-text
        // exclusion below only exists to skip a genuine search-suggestion
        // echo, so it's now only applied as a last resort when content
        // scoring couldn't find anything to prefer.
        val bestMatch = bestScoringCandidate(shaped, matchQuery)

        val candidates = shaped
            .filter { !(needle.isNotBlank() && it.label.equals(needle, ignoreCase = true)) }

        // Fall back to the excluded/near-top elements rather than doing
        // nothing if filtering left us with no candidates at all - a
        // slightly-wrong tap beats a flow that silently stalls.
        val chosen = bestMatch
            ?: candidates.firstOrNull()
            ?: shaped.firstOrNull()
            ?: raw.sortedWith(compareBy({ it.y }, { it.x })).firstOrNull { it.y >= cutoff }
            ?: raw.firstOrNull()
            ?: return false

        visitBudget = maxVisits
        val node = findByPredicate(root, depth = 0) {
            val bounds = Rect()
            it.getBoundsInScreen(bounds)
            !bounds.isEmpty && bounds.centerX().toFloat() == chosen.x && bounds.centerY().toFloat() == chosen.y
        }
        return if (node != null) clickNodeOrAncestor(node) else dispatchTap(chosen.x, chosen.y, holdMs = 60)
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
    private fun clickByTextFuzzy(
        root: AccessibilityNodeInfo,
        target: String,
        exact: Boolean,
        minYFraction: Float = 0f
    ): Boolean {
        visitBudget = maxVisits
        val match = findNodeByText(root, target, exact, depth = 0, minYFraction = minYFraction) ?: return false
        return clickNodeOrAncestor(match)
    }

    private fun screenHeight(): Int {
        val bounds = Rect()
        (rootInActiveWindow ?: return Int.MAX_VALUE).getBoundsInScreen(bounds)
        return if (bounds.height() > 0) bounds.height() else Int.MAX_VALUE
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
        depth: Int,
        minYFraction: Float = 0f
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 40 || !withinBudget()) return null
        visitBudget--

        val nodeText = node.text?.toString()
        val nodeDesc = node.contentDescription?.toString()

        var hit = if (exact) {
            (nodeText.equals(target, ignoreCase = true) || nodeDesc.equals(target, ignoreCase = true)) && !node.isEditable
        } else {
            fuzzyMatch(nodeText, nodeDesc, target) && !node.isEditable
        }

        if (hit && minYFraction > 0f) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val height = screenHeight()
            if (height > 0 && height != Int.MAX_VALUE && bounds.centerY().toFloat() / height < minYFraction) {
                hit = false
            }
        }
        if (hit) return node

        for (i in 0 until node.childCount) {
            val found = findNodeByText(node.getChild(i), target, exact, depth + 1, minYFraction)
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
        if (node == null || depth > 40 || !withinBudget()) return null
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

        // Deliberately NOT resetting visitBudget again here - the whole
        // operation (item search + subtree check + up to 6 ancestor hops)
        // shares one budget so a single tick can't blow past its time cap.
        findAddButton(itemNode, depth = 0)?.let { if (clickNodeOrAncestor(it)) return true }

        var container: AccessibilityNodeInfo? = itemNode.parent
        var hops = 0
        while (container != null && hops < 6 && withinBudget()) {
            val addNode = findAddButton(container, depth = 0)
            if (addNode != null) return clickNodeOrAncestor(addNode)
            container = container.parent
            hops++
        }
        return false
    }

    private fun findAddButton(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 30 || !withinBudget()) return null
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
        val set = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

        // Best-effort "press search/enter" so live-search apps actually
        // populate results instead of leaving the query sitting in the box.
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                field.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } catch (e: Exception) {
                // not all fields support it - harmless if this fails
            }
        }

        return set
    }

    private fun findEditableNode(node: AccessibilityNodeInfo?, depth: Int): AccessibilityNodeInfo? {
        if (node == null || depth > 40 || !withinBudget()) return null
        visitBudget--

        val editableByAction = node.actionList?.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT) == true
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

