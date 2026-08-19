package com.brosco.assistant

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

object CommandProcessor {

    /**
     * Wraps a coroutine body so an unexpected exception inside it is logged
     * and swallowed instead of propagating to the default uncaught-exception
     * handler. A crash anywhere in the app kills the whole process - and
     * since the accessibility service lives in that same process, that's
     * exactly what makes Android disable it and show "this service is
     * malfunctioning" until it's manually toggled back on. Every coroutine
     * CommandProcessor launches goes through this.
     */
    private fun CoroutineScope.safeLaunch(block: suspend CoroutineScope.() -> Unit) =
        this.launch {
            try {
                block()
            } catch (e: Exception) {
                Log.e("Brosco", "Background task failed: ${e.message}", e)
            }
        }

    private val appPackages = mapOf(
        "whatsapp" to "com.whatsapp",
        "spotify" to "com.spotify.music",
        "jiosaavn" to "com.jio.media.jiobeats",
        "saavn" to "com.jio.media.jiobeats",
        "youtube" to "com.google.android.youtube",
        "instagram" to "com.instagram.android",
        "gmail" to "com.google.android.gm",
        "maps" to "com.google.android.apps.maps",
        "google maps" to "com.google.android.apps.maps",
        "camera" to "com.android.camera",
        "chrome" to "com.android.chrome",
        "google" to "com.google.android.googlequicksearchbox",
        "play store" to "com.android.vending",
        "calculator" to "com.google.android.calculator",
        "clock" to "com.google.android.deskclock",
        "photos" to "com.google.android.apps.photos",
        "gallery" to "com.google.android.apps.photos",
        "settings" to "com.android.settings",
        "telegram" to "org.telegram.messenger",
        "prime video" to "com.amazon.avod.thirdpartyclient",
        "zomato" to "com.application.zomato",
        "dominos" to "com.Dominos",
        "domino's" to "com.Dominos"
    )

    // Remembers which food-ordering app/flow we last drove and when, so a
    // follow-up "add X" right after "order Y on dominos" adds a second item
    // to the same order (search -> select -> add to cart again) instead of
    // blindly poking around whatever happens to be on screen right now.
    private var lastOrderApp: String? = null
    private var lastOrderAt: Long = 0L
    private val ORDER_CONTEXT_WINDOW_MS = 3 * 60 * 1000L

    /**
     * Entry point for "Share via -> Brosco" on a video file (see
     * MainActivity's ACTION_SEND handling). Takes a Uri rather than text
     * since that's how a shared video actually arrives, so it doesn't fit
     * the normal text-command pipeline above.
     */
    fun analyzeVideo(
        context: Context,
        uri: android.net.Uri,
        question: String,
        scope: CoroutineScope,
        rawSpeak: (String) -> Unit
    ) {
        val speak: (String) -> Unit = { response ->
            MemoryStore.record(context, "[shared a video] $question".trim(), response)
            rawSpeak(response)
        }
        speak("Looking at the video now - this can take a moment.")
        scope.safeLaunch {
            val answer = withContext(Dispatchers.IO) {
                VideoFrameAnalyzer.analyze(context, uri, question)
            }
            speak(answer)
        }
    }

    fun process(
        context: Context,
        rawText: String,
        scope: CoroutineScope,
        onPlaybackStarted: () -> Unit = {},
        rawSpeak: (String) -> Unit
    ) {
        // Top-level safety net: everything below this can throw in ways we
        // haven't anticipated (a malformed contact lookup, a null from some
        // OEM's accessibility tree, etc). Catching here means one bad command
        // degrades to a spoken apology instead of crashing the whole process
        // - which is what was silently disabling the accessibility service
        // and forcing a manual re-toggle every time something went wrong.
        try {
            processInternal(context, rawText, scope, onPlaybackStarted, rawSpeak)
        } catch (e: Exception) {
            Log.e("Brosco", "process() crashed on \"$rawText\": ${e.message}", e)
            try {
                rawSpeak("Sorry, that one tripped me up - try again.")
            } catch (_: Exception) {
                // even the fallback speak failed - nothing more we can safely do
            }
        }
    }

    private fun processInternal(
        context: Context,
        rawText: String,
        scope: CoroutineScope,
        onPlaybackStarted: () -> Unit = {},
        rawSpeak: (String) -> Unit
    ) {
        val text = rawText.trim().lowercase()
        val detectedIntent = IntentDetector.detect(text)

        // Every response gets logged to persistent memory alongside what
        // triggered it. Named differently from the parameter (not `val speak =
        // speak`) to avoid Kotlin's self-shadowing recursion trap - every
        // `speak(...)` used below this line resolves to this wrapped version.
        val speak: (String) -> Unit = { response ->
            MemoryStore.record(context, rawText, response)
            rawSpeak(response)
        }

        // "message X saying Y" is the normal short form; "whatsapp X saying Y"
        // and "message X on whatsapp saying Y" still work too. Deliberately
        // NOT gated on the word "whatsapp" appearing anywhere in the text -
        // that guard used to force the short form ("message rahul saying
        // hey") past this branch and down into the plain-SMS fallback below,
        // since it starts with "message" but never says the word "whatsapp".
        val whatsappRegex = Regex("(?:message|whatsapp)\\s+(.+?)\\s+(?:on whatsapp\\s+)?saying\\s+(.+)")
        val whatsappMatch = whatsappRegex.find(text)

        when {
            // Memory
            detectedIntent.type == IntentType.CLEAR_MEMORY -> {
                MemoryStore.clear(context)
                LearnedFacts.clear(context)
                UsageStats.clear(context)
                speak("Done - I've forgotten everything up to now.")
            }

            // Overnight work mode
            detectedIntent.type == IntentType.START_OVERNIGHT_WORK -> {
                OvernightScheduler.start(context)
                speak(
                    "Got it - I'll check markets and news every ${OvernightScheduler.INTERVAL_MINUTES.toInt()} " +
                        "minutes through the night and have a briefing ready. Ask me for it whenever you're up, " +
                        "or say \"stop working overnight\" to call it off."
                )
            }
            detectedIntent.type == IntentType.STOP_OVERNIGHT_WORK -> {
                val wasActive = OvernightScheduler.isActive(context)
                OvernightScheduler.stop(context)
                speak(if (wasActive) "Stopped - no more overnight runs." else "Overnight mode wasn't running.")
            }
            detectedIntent.type == IntentType.OVERNIGHT_BRIEFING -> {
                speak(NightDigestStore.formatBriefing(context))
            }

            // Section 6: WhatsApp smart replies
            detectedIntent.type == IntentType.WHATSAPP_SMART_REPLY -> {
                runWhatsAppSmartReply(context, scope, speak)
            }
            detectedIntent.type == IntentType.SELECT_SMART_REPLY -> {
                runSelectSmartReply(detectedIntent.target, speak)
            }

            // Section 7: screen-aware Q&A
            detectedIntent.type == IntentType.SCREEN_QA -> {
                runScreenQa(context, rawText, scope, speak)
            }

            // Section 5: chained commands - "open zomato then search burger then add to cart"
            detectedIntent.type == IntentType.MULTI_STEP -> {
                runChain(context, detectedIntent.target, scope, onPlaybackStarted, speak)
            }

            // Section 3: app-automation flows
            detectedIntent.type == IntentType.ZOMATO_ORDER -> {
                // If we're already mid-order on Zomato (e.g. this is a
                // follow-up like "now get garlic bread on zomato" a few
                // seconds after the last item), go back to the menu instead
                // of relaunching the app from scratch.
                val recentOrderApp = lastOrderApp?.takeIf {
                    System.currentTimeMillis() - lastOrderAt < ORDER_CONTEXT_WINDOW_MS
                }
                runZomatoFlow(context, detectedIntent.target, speak, alreadyOpen = recentOrderApp == "zomato")
            }
            detectedIntent.type == IntentType.DOMINOS_ORDER -> {
                val recentOrderApp = lastOrderApp?.takeIf {
                    System.currentTimeMillis() - lastOrderAt < ORDER_CONTEXT_WINDOW_MS
                }
                runDominosFlow(context, detectedIntent.target, speak, alreadyOpen = recentOrderApp == "dominos")
            }
            detectedIntent.type == IntentType.ADD_ITEM -> {
                if (detectedIntent.target.isBlank()) {
                    speak("Add what?")
                } else {
                    val recentOrderApp = lastOrderApp?.takeIf {
                        System.currentTimeMillis() - lastOrderAt < ORDER_CONTEXT_WINDOW_MS
                    }
                    when (recentOrderApp) {
                        // Mid-order follow-up: "order paneer pizza on dominos"
                        // then "add farmhouse pizza" - runs the same
                        // search -> select -> add-to-cart flow for the new
                        // item instead of hunting for that label on whatever
                        // screen happens to be showing (which is usually the
                        // cart from the first item, where it won't exist).
                        "dominos" -> runDominosFlow(context, detectedIntent.target, speak, alreadyOpen = true)
                        "zomato" -> runZomatoFlow(context, detectedIntent.target, speak, alreadyOpen = true)
                        else -> WhatsAppAccessibilityService.runTask(
                            listOf(AutomationStep.AddItemNear(detectedIntent.target)),
                            onUpdate = { speak(it) },
                            onDone = { speak("Added ${detectedIntent.target}.") }
                        )
                    }
                }
            }
            detectedIntent.type == IntentType.SMART_CLICK -> {
                resolveSmartClick(detectedIntent.target, scope, speak)
            }
            // Real web search / deep research - always forced onto the
            // search-capable model rather than left to silently fail on the
            // plain chat model, which is what used to happen here.
            detectedIntent.type == IntentType.SEARCH -> {
                val query = detectedIntent.target.ifBlank { rawText }
                speak("Let me look that up.")
                scope.safeLaunch {
                    val screenText = withContext(Dispatchers.IO) { WhatsAppAccessibilityService.captureScreenText() }
                    val answer = withContext(Dispatchers.IO) {
                        GroqApiClient.ask(context, query, forceSearch = true, screenText = screenText)
                    }
                    speak(answer)
                    safeLaunch {
                        val fact = withContext(Dispatchers.IO) {
                            GroqApiClient.extractFact(rawText, answer)
                        }
                        if (!fact.equals("NONE", ignoreCase = true)) {
                            LearnedFacts.add(context, fact)
                        }
                    }
                }
            }
            detectedIntent.type == IntentType.YOUTUBE_SEARCH -> {
                runYoutubeSearchFlow(context, detectedIntent.target, speak, onPlaybackStarted)
            }
            detectedIntent.type == IntentType.YOUTUBE_PAUSE -> {
                WhatsAppAccessibilityService.runTask(listOf(AutomationStep.ClickText("pause video")), onUpdate = {})
                speak("Pausing.")
            }
            detectedIntent.type == IntentType.YOUTUBE_NEXT -> {
                WhatsAppAccessibilityService.runTask(listOf(AutomationStep.ClickText("next video")), onUpdate = {})
                speak("Next video.")
            }
            detectedIntent.type == IntentType.SPOTIFY_SEARCH -> {
                runSpotifySearchFlow(context, detectedIntent.target, speak, onPlaybackStarted)
            }
            detectedIntent.type == IntentType.SPOTIFY_PAUSE -> {
                WhatsAppAccessibilityService.runTask(listOf(AutomationStep.ClickText("pause")), onUpdate = {})
                speak("Pausing the music.")
            }
            detectedIntent.type == IntentType.SPOTIFY_NEXT -> {
                WhatsAppAccessibilityService.runTask(listOf(AutomationStep.ClickText("skip to next")), onUpdate = {})
                speak("Skipping.")
            }
            detectedIntent.type == IntentType.JIOSAAVN_SEARCH -> {
                runJioSaavnSearchFlow(context, detectedIntent.target, speak, onPlaybackStarted)
            }
            detectedIntent.type == IntentType.JIOSAAVN_PAUSE -> {
                WhatsAppAccessibilityService.runTask(listOf(AutomationStep.ClickText("pause")), onUpdate = {})
                speak("Pausing.")
            }
            detectedIntent.type == IntentType.JIOSAAVN_NEXT -> {
                WhatsAppAccessibilityService.runTask(listOf(AutomationStep.ClickText("next")), onUpdate = {})
                speak("Skipping.")
            }
            detectedIntent.type == IntentType.JIOSAAVN_MY_PLAYLIST -> {
                runJioSaavnMyPlaylistFlow(context, speak, onPlaybackStarted)
            }
            detectedIntent.type == IntentType.INSTAGRAM_SCROLL_FEED -> {
                openApp(context, "instagram", speak)
                WhatsAppAccessibilityService.runTask(
                    listOf(AutomationStep.Wait(1000), AutomationStep.Swipe(SwipeDirection.UP)),
                    onUpdate = {}
                )
                speak("Scrolling your feed.")
            }
            detectedIntent.type == IntentType.INSTAGRAM_OPEN_REELS -> {
                openApp(context, "instagram", speak)
                WhatsAppAccessibilityService.runTask(
                    listOf(AutomationStep.Wait(1000), AutomationStep.ClickText("Reels")),
                    onUpdate = { speak(it) }
                )
                speak("Opening reels.")
            }
            detectedIntent.type == IntentType.INSTAGRAM_LIKE -> {
                WhatsAppAccessibilityService.runTask(
                    listOf(AutomationStep.ClickId("com.instagram.android:id/row_feed_button_like")),
                    onUpdate = {}
                )
                speak("Liking that.")
            }
            detectedIntent.type == IntentType.INSTAGRAM_FOLLOW -> {
                WhatsAppAccessibilityService.runTask(listOf(AutomationStep.ClickText("follow")), onUpdate = {})
                speak("Following.")
            }

            // Section 2: universal gestures / single actions - all go through
            // runTask now so they don't depend on an accessibility event
            // arriving to get picked up (that was the bug: static screens
            // never fired one, so scroll/swipe/long-press just sat there).
            detectedIntent.type == IntentType.OPEN_APP -> {
                openApp(context, detectedIntent.target, speak)
            }
            detectedIntent.type == IntentType.CALL -> {
                callContact(context, detectedIntent.target, speak)
            }
            detectedIntent.type == IntentType.ORDER_FOOD -> {
                // A bare "order Y" (no app name) only reaches ORDER_FOOD
                // instead of DOMINOS_ORDER/ZOMATO_ORDER because it doesn't
                // repeat "dominos"/"zomato" - exactly what happens for the
                // second half of "order X on dominos then order Y". That
                // used to always fall through to a fresh Zomato web search,
                // silently abandoning the Dominos cart X had just been added
                // to. If we drove an order in the last few minutes, treat
                // this the same as ADD_ITEM: go back to the menu of THAT
                // app and add Y there instead of guessing a new one.
                val recentOrderApp = lastOrderApp?.takeIf {
                    System.currentTimeMillis() - lastOrderAt < ORDER_CONTEXT_WINDOW_MS
                }
                val cleaned = detectedIntent.target
                    .replace(Regex("^order\\s+"), "")
                    .replace(Regex("\\b(zomato|dominos|domino'?s)\\b"), "")
                    .replace(Regex("\\b(on|from|via|please)\\b"), "")
                    .trim()
                when (recentOrderApp) {
                    "dominos" -> runDominosFlow(context, cleaned, speak, alreadyOpen = true)
                    "zomato" -> runZomatoFlow(context, cleaned, speak, alreadyOpen = true)
                    else -> openZomatoSearch(context, detectedIntent.target, speak)
                }
            }
            detectedIntent.type == IntentType.GO_BACK -> {
                WhatsAppAccessibilityService.runTask(listOf(AutomationStep.GoBack), onUpdate = {})
                speak("Going back.")
            }
            detectedIntent.type == IntentType.GO_HOME -> {
                WhatsAppAccessibilityService.runTask(listOf(AutomationStep.GoHome), onUpdate = {})
                speak("Going home.")
            }
            detectedIntent.type == IntentType.SCROLL_DOWN -> {
                WhatsAppAccessibilityService.runTask(listOf(AutomationStep.ScrollForward), onUpdate = {})
                speak("Scrolling down.")
            }
            detectedIntent.type == IntentType.SCROLL_UP -> {
                WhatsAppAccessibilityService.runTask(listOf(AutomationStep.ScrollBackward), onUpdate = {})
                speak("Scrolling up.")
            }
            detectedIntent.type == IntentType.TYPE_TEXT -> {
                if (detectedIntent.target.isNotBlank()) {
                    WhatsAppAccessibilityService.runTask(listOf(AutomationStep.TypeText(detectedIntent.target)), onUpdate = {})
                    speak("Typing ${detectedIntent.target}.")
                } else {
                    speak("What should I type?")
                }
            }
            detectedIntent.type == IntentType.LONG_PRESS -> {
                if (detectedIntent.target.isNotBlank()) {
                    WhatsAppAccessibilityService.runTask(listOf(AutomationStep.LongPressText(detectedIntent.target)), onUpdate = {})
                    speak("Holding ${detectedIntent.target}.")
                } else {
                    speak("What should I long-press?")
                }
            }
            detectedIntent.type == IntentType.SWIPE -> {
                val direction = parseSwipeDirection(detectedIntent.target)
                if (direction != null) {
                    WhatsAppAccessibilityService.runTask(listOf(AutomationStep.Swipe(direction)), onUpdate = {})
                    speak("Swiping ${detectedIntent.target}.")
                } else {
                    speak("Swipe which way - up, down, left or right?")
                }
            }
            detectedIntent.type == IntentType.CLICK_ID -> {
                if (detectedIntent.target.isNotBlank()) {
                    WhatsAppAccessibilityService.runTask(listOf(AutomationStep.ClickId(detectedIntent.target)), onUpdate = {})
                    speak("Clicking that element.")
                } else {
                    speak("Which view id?")
                }
            }
            detectedIntent.type == IntentType.CLICK -> {
                if (detectedIntent.target.isNotBlank()) {
                    WhatsAppAccessibilityService.runTask(listOf(AutomationStep.ClickText(detectedIntent.target)), onUpdate = {})
                    speak("Clicking ${detectedIntent.target}.")
                } else {
                    speak("What should I click?")
                }
            }

            // Legacy fallback checks
            // "message X saying Y" / "whatsapp X saying Y" -> always WhatsApp,
            // regardless of whether the word "whatsapp" shows up anywhere else.
            whatsappMatch != null -> {
                val name = whatsappMatch.groupValues[1].trim()
                val message = whatsappMatch.groupValues[2].trim()
                sendWhatsAppMessage(context, name, message, speak)
            }
            text.startsWith("call ") -> {
                val name = text.removePrefix("call ").trim()
                callContact(context, name, speak)
            }
            // Only plain SMS reaches here now - "message ... saying ..." is
            // caught by whatsappMatch above before this branch is ever checked.
            text.startsWith("text ") -> {
                val body = text.substringAfter(" ").trim()
                val parts = body.split(" saying ", limit = 2)
                if (parts.size == 2) {
                    smsContact(context, parts[0].trim(), parts[1].trim(), speak)
                } else {
                    speak("Say it like: text John saying I'm on my way")
                }
            }
            // "message John" with no "saying <text>" yet - missed the whatsapp
            // regex above because there's nothing to send, not because it
            // wasn't meant for WhatsApp.
            text.startsWith("message ") -> {
                speak("Say it like: message ${text.removePrefix("message ").trim().ifBlank { "John" }} saying I'm on my way")
            }
            text.startsWith("order ") -> {
                val food = text.removePrefix("order ").trim().replace(" from ", " ")
                openZomatoSearch(context, food, speak)
            }
            text.startsWith("find ") && (text.contains("restaurant") || text.contains("food")) -> {
                val query = text.removePrefix("find ").trim()
                openZomatoSearch(context, query, speak)
            }
            text.startsWith("open ") -> {
                val appName = text.removePrefix("open ").trim()
                openApp(context, appName, speak)
            }
            else -> {
                speak("Let me think...")
                scope.safeLaunch {
                    // Best-effort read of whatever's on screen right now, so
                    // "what does this say" / "reply to this" / "summarize
                    // this" etc. actually have something to work with instead
                    // of Brosco having no idea what Shrey's looking at. Works
                    // the same whether this came from the foreground app or
                    // the background listener - both run through here.
                    val screenText = withContext(Dispatchers.IO) { WhatsAppAccessibilityService.captureScreenText() }
                    val answer = withContext(Dispatchers.IO) {
                        GroqApiClient.ask(context, rawText, screenText = screenText)
                    }
                    speak(answer)

                    // "Learning": pull out anything durable worth remembering
                    // from this exchange, in the background, after the fact -
                    // never blocks or delays the spoken reply itself.
                    safeLaunch {
                        val fact = withContext(Dispatchers.IO) {
                            GroqApiClient.extractFact(rawText, answer)
                        }
                        if (!fact.equals("NONE", ignoreCase = true)) {
                            LearnedFacts.add(context, fact)
                        }
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Section 4-lite: "smart click" - resolve ordinals/pronouns against
    // whatever's actually on screen right now, via the AI classifier.
    // ------------------------------------------------------------------
    private fun resolveSmartClick(phrase: String, scope: CoroutineScope, speak: (String) -> Unit) {
        scope.safeLaunch {
            val elements = WhatsAppAccessibilityService.snapshotScreen()
            if (elements.isEmpty()) {
                speak("I can't see anything tappable right now.")
                return@safeLaunch
            }

            val listText = elements.joinToString("\n") { "${it.index}. ${it.label}" }
            val prompt = "On-screen tappable elements:\n$listText\n\n" +
                "User said: \"$phrase\"\n\n" +
                "Which numbered element matches best?"

            val reply = withContext(Dispatchers.IO) { GroqApiClient.classify(prompt) }
            val chosenIndex = Regex("\\d+").find(reply)?.value?.toIntOrNull()
            val chosen = elements.firstOrNull { it.index == chosenIndex }

            if (chosen != null) {
                WhatsAppAccessibilityService.runTask(listOf(AutomationStep.TapAt(chosen.x, chosen.y)), onUpdate = {})
            } else {
                speak("I couldn't find that on screen.")
            }
        }
    }

    // ------------------------------------------------------------------
    // Section 6: WhatsApp smart replies - read the latest incoming message,
    // ask the AI for 2-3 short reply options, speak them, and remember them
    // in SmartReplyStore so "reply with the second one" knows what to send.
    // ------------------------------------------------------------------
    private fun runWhatsAppSmartReply(context: Context, scope: CoroutineScope, speak: (String) -> Unit) {
        scope.safeLaunch {
            val latest = withContext(Dispatchers.IO) { WhatsAppAccessibilityService.captureLatestWhatsAppMessage() }
            if (latest == null) {
                speak("I can't see a WhatsApp chat open right now - open the conversation first.")
                return@safeLaunch
            }
            if (latest.isOutgoing) {
                speak("Looks like the last message there was yours - nothing new to reply to.")
                SmartReplyStore.clear()
                return@safeLaunch
            }
            val options = withContext(Dispatchers.IO) { GroqApiClient.suggestReplies(latest.text) }
            if (options.isEmpty()) {
                speak("I couldn't come up with a reply for that one - try wording your own.")
                return@safeLaunch
            }
            SmartReplyStore.set(options)
            val spokenOptions = options.mapIndexed { i, opt -> "${ordinalWord(i + 1)}: $opt" }.joinToString(". ")
            speak(
                "They said: \"${latest.text}\". You could reply - $spokenOptions. " +
                    "Just say something like \"reply with the second one\"."
            )
        }
    }

    // ------------------------------------------------------------------
    // Section 6: "reply with the second one" - looks up the stored option
    // and actually sends it in whichever WhatsApp chat is currently open.
    // ------------------------------------------------------------------
    private fun runSelectSmartReply(ordinalText: String, speak: (String) -> Unit) {
        val index = ordinalText.toIntOrNull()
        val options = SmartReplyStore.get()
        if (options == null) {
            speak("I don't have any reply suggestions right now - ask me to check WhatsApp first.")
            return
        }
        val chosen = index?.let { options.getOrNull(it - 1) }
        if (chosen == null) {
            speak("I only have ${options.size} option${if (options.size == 1) "" else "s"} - which one did you mean?")
            return
        }
        WhatsAppAccessibilityService.runTask(
            listOf(AutomationStep.TypeText(chosen), AutomationStep.Wait(300), AutomationStep.ClickWhatsAppSend),
            onUpdate = { speak(it) },
            onDone = { speak("Sent.") }
        )
        SmartReplyStore.clear()
    }

    private fun ordinalWord(n: Int): String = when (n) {
        1 -> "first"
        2 -> "second"
        3 -> "third"
        else -> "option $n"
    }

    // ------------------------------------------------------------------
    // Section 7: screen-aware Q&A - "what does this error say", "summarize
    // this article" - reads the current screen's text via the accessibility
    // tree and hands it to the AI as the primary source for the answer.
    // ------------------------------------------------------------------
    private fun runScreenQa(context: Context, rawText: String, scope: CoroutineScope, speak: (String) -> Unit) {
        scope.safeLaunch {
            val screenText = withContext(Dispatchers.IO) { WhatsAppAccessibilityService.captureScreenText(maxChars = 3000) }
            if (screenText.isBlank()) {
                speak("I can't read anything on screen right now - make sure Brosco's accessibility service is on.")
                return@safeLaunch
            }
            speak("Let me check your screen.")
            val answer = withContext(Dispatchers.IO) {
                GroqApiClient.ask(context, rawText, screenText = screenText, forceScreenFocus = true)
            }
            speak(answer)
        }
    }

    // ------------------------------------------------------------------
    // Section 5: Planning - chained multi-step voice commands
    // ------------------------------------------------------------------
    // Words that strip down to a bare food item name: leading verb, the
    // app's own name(s), and the on/from/via connector that ties them
    // together. Shared between the food-chain detector and the per-item
    // cleanup below so both agree on what counts as "just the food name".
    private val orderLeadWordsRegex = Regex("^\\s*(order|get|add|buy|please)\\s+")
    private val appConnectorRegex = Regex("\\b(on|from|via)\\s+(domino'?s|zomato)\\b")
    private val bareAppNameRegex = Regex("\\b(domino'?s|zomato)\\b")

    private fun extractFoodItem(piece: String): String {
        return piece
            .replace(orderLeadWordsRegex, "")
            .replace(appConnectorRegex, "")
            .replace(bareAppNameRegex, "")
            .replace(Regex("\\bplease\\b"), "")
            .trim()
    }

    // Phrases that mean this chain is really a low-level step-by-step
    // automation script ("open zomato then search burger then add to
    // cart") rather than a plain list of food items to order one after
    // another. The food-item fast path below must NOT fire for these -
    // it would mangle a literal instruction like "search burger" by
    // trying to treat "burger" as the whole order.
    private val explicitStepPhrases = listOf(
        " open ", " search ", " click ", " tap ", " scroll", " swipe",
        "view cart", "add to cart", "add more items", " play ", " call ",
        " message ", " go home", " go back"
    )

    private fun runChain(
        context: Context,
        chained: String,
        scope: CoroutineScope,
        onPlaybackStarted: () -> Unit,
        speak: (String) -> Unit
    ) {
        val pieces = chained
            .split(Regex(" then | and then | -> | after that | followed by "))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (pieces.isEmpty()) {
            speak("I didn't catch the steps in that.")
            return
        }

        // ---- Food-order fast path ----
        // "order farmhouse then garlic bread on domino's" only names the
        // app once, attached to the LAST item - splitting the sentence on
        // "then" and running each half through the normal per-piece
        // detector loses that context for every earlier piece, which used
        // to fall through to a hardcoded Zomato fallback regardless of what
        // was actually asked for. Detecting the app from the whole sentence
        // up front and driving every item through the SAME app's flow -
        // with alreadyOpen=true for everything after the first, so it goes
        // back to the menu via the app's own cart screen rather than
        // relaunching from scratch - keeps every item on the right app and
        // reuses the "back to menu" navigation the same way a person would.
        val globalOrderApp = when {
            Regex("\\bdomino").containsMatchIn(chained) -> "dominos"
            Regex("\\bzomato").containsMatchIn(chained) -> "zomato"
            else -> null
        }
        val paddedChain = " $chained "
        val looksLikeExplicitSteps = explicitStepPhrases.any { paddedChain.contains(it) }

        if (globalOrderApp != null && !looksLikeExplicitSteps) {
            val appLabel = if (globalOrderApp == "dominos") "Domino's" else "Zomato"
            speak("On it - ${pieces.size} item${if (pieces.size == 1) "" else "s"} on $appLabel.")
            scope.safeLaunch {
                for ((index, piece) in pieces.withIndex()) {
                    val item = extractFoodItem(piece).ifBlank { piece }
                    if (globalOrderApp == "dominos") {
                        runDominosFlow(context, item, speak, alreadyOpen = index > 0)
                    } else {
                        runZomatoFlow(context, item, speak, alreadyOpen = index > 0)
                    }
                    if (index < pieces.lastIndex) {
                        awaitAutomationIdle()
                        delay(700)
                    }
                }
            }
            return
        }

        speak("On it - ${pieces.size} steps.")

        scope.safeLaunch {
            for ((index, piece) in pieces.withIndex()) {
                process(context, piece, this, onPlaybackStarted, speak)
                if (index < pieces.lastIndex) {
                    // Wait for THIS step's automation queue to actually
                    // finish before starting the next one - a fixed delay
                    // isn't enough for a multi-tap flow like "order X on
                    // dominos", and runTask() replaces the queue on every
                    // call, so firing the next piece too early silently
                    // cancels whatever the previous piece hadn't gotten to
                    // yet (that was the bug behind "add" never landing).
                    awaitAutomationIdle()
                    delay(700)
                }
            }
        }
    }

    /** Polls the accessibility service's task queue until it drains (or we give up waiting). */
    private suspend fun awaitAutomationIdle(maxWaitMs: Long = 20000L) {
        val start = System.currentTimeMillis()
        // Give a step a moment to actually get queued before checking -
        // process() for the first piece may still be a few lines from
        // calling runTask() when we get here.
        delay(300)
        while (WhatsAppAccessibilityService.isTaskRunning() && System.currentTimeMillis() - start < maxWaitMs) {
            delay(250)
        }
    }

    // ------------------------------------------------------------------
    // Section 3: Zomato - search food -> select restaurant -> add to cart -> open cart
    // ------------------------------------------------------------------
    private fun runZomatoFlow(context: Context, food: String, speak: (String) -> Unit, alreadyOpen: Boolean = false) {
        val query = food.ifBlank { "food" }
        lastOrderApp = "zomato"
        lastOrderAt = System.currentTimeMillis()

        val steps = mutableListOf<AutomationStep>()
        if (alreadyOpen) {
            // Coming back from the previous item's cart screen. A raw
            // back-press from a cart/checkout screen in food-delivery apps
            // often triggers an "are you sure you want to leave?" dialog
            // instead of just navigating - which has no "Search" on it, so
            // the whole next-item flow stalled with nothing to tap. Zomato's
            // cart has its own "Add more items" link back to the menu for
            // exactly this, so use that instead.
            steps += AutomationStep.ClickText("Add more items", optional = true)
            steps += AutomationStep.Wait(700)
        } else {
            openApp(context, "zomato", speak)
            steps += AutomationStep.WaitForPackage("com.application.zomato")
            steps += AutomationStep.Wait(800)
        }
        steps += listOf(
            AutomationStep.ClickText("Search"),
            AutomationStep.Wait(200),
            AutomationStep.TypeText(query),
            AutomationStep.ClickFirstResult(excludeText = query, matchQuery = query),
            AutomationStep.Wait(300),
            AutomationStep.ClickText("Add"),
            AutomationStep.Wait(300),
            // Opening the cart view is a nice-to-have, not the point of the
            // flow - the item is already added by the time this runs, so a
            // missing/renamed "View Cart" button shouldn't make the whole
            // command get reported as failed.
            AutomationStep.ClickText("View Cart", optional = true)
        )

        WhatsAppAccessibilityService.runTask(
            steps = steps,
            onUpdate = { speak(it) },
            onDone = {
                speak("$query is in your cart - take a look and confirm the order.")
                UsageStats.recordAndMaybeLearn(context, "zomato", query) { t ->
                    "Frequently orders \"$t\" on Zomato."
                }
            },
            onFail = { speak("I couldn't finish adding $query on Zomato - the app may have changed screens or nothing matched that name. Take a look and try again.") }
        )
        speak("Searching for $query on Zomato.")
    }

    // ------------------------------------------------------------------
    // Section 3: Domino's - search pizza -> choose size -> add to cart -> open cart
    // ------------------------------------------------------------------
    private fun runDominosFlow(context: Context, pizza: String, speak: (String) -> Unit, alreadyOpen: Boolean = false) {
        val query = pizza.ifBlank { "pizza" }
        lastOrderApp = "dominos"
        lastOrderAt = System.currentTimeMillis()

        val steps = mutableListOf<AutomationStep>()
        if (alreadyOpen) {
            // Same reasoning as the Zomato flow above: Domino's cart screen
            // has its own "Add more items" link (confirmed from the actual
            // cart screen - see screenshot) - use that instead of a raw
            // back-press, which risks landing on an exit-confirmation dialog
            // with no "Search" on it for the next item to find.
            steps += AutomationStep.ClickText("Add more items", optional = true)
            steps += AutomationStep.Wait(700)
        } else {
            openApp(context, "dominos", speak)
            steps += AutomationStep.WaitForPackage("com.Dominos")
            steps += AutomationStep.Wait(800)
        }
        steps += listOf(
            AutomationStep.ClickText("Search"),
            AutomationStep.Wait(200),
            AutomationStep.TypeText(query),
            AutomationStep.ClickFirstResult(excludeText = query, matchQuery = query),
            AutomationStep.Wait(300),
            // Not every item has a size picker (sides, garlic bread,
            // beverages don't) - marking this optional stops a 5s timeout +
            // false "failed" report on every non-pizza item.
            AutomationStep.ClickText("Medium", optional = true),
            AutomationStep.Wait(300),
            AutomationStep.ClickText("Add"),
            AutomationStep.Wait(300),
            AutomationStep.ClickText("Cart", optional = true)
        )

        WhatsAppAccessibilityService.runTask(
            steps = steps,
            onUpdate = { speak(it) },
            onDone = {
                speak("$query is in the cart - medium size, change it there if you want something else.")
                UsageStats.recordAndMaybeLearn(context, "dominos", query) { t ->
                    "Frequently orders \"$t\" on Domino's."
                }
            },
            onFail = { speak("I couldn't finish adding $query on Domino's - the app may have changed screens or nothing matched that name. Take a look and try again.") }
        )
        speak("Looking for $query on Domino's.")
    }

    // ------------------------------------------------------------------
    // Section 3: YouTube - search -> play first result
    // ------------------------------------------------------------------
    private fun runYoutubeSearchFlow(
        context: Context,
        query: String,
        speak: (String) -> Unit,
        onPlaybackStarted: () -> Unit = {}
    ) {
        val term = query.ifBlank { "" }
        if (term.isBlank()) {
            speak("What should I search on YouTube?")
            return
        }
        openApp(context, "youtube", speak)
        WhatsAppAccessibilityService.runTask(
            steps = listOf(
                AutomationStep.WaitForPackage("com.google.android.youtube"),
                AutomationStep.Wait(300),
                // NOTE: YouTube's Search is a top app-bar icon, not a
                // bottom-nav tab, so (unlike Saavn/Spotify below) this one
                // deliberately isn't position-gated.
                AutomationStep.ClickText("Search"),
                AutomationStep.Wait(200),
                // TypeText already submits via IME "search"/enter.
                // ClickFirstResult below retries on its own until the
                // results screen actually has something to tap, so no fixed
                // wait is needed here either.
                AutomationStep.TypeText(term),
                // Tap the first actual result thumbnail/row rather than
                // matching the typed text - a video's title is almost never
                // identical to the search phrase, so the old exact-ish text
                // match usually found nothing (or re-tapped the search
                // suggestion echoing the query) and the flow silently
                // stalled without ever pressing play.
                AutomationStep.ClickFirstResult(excludeText = term, minYFraction = 0.16f, matchQuery = term)
            ),
            onUpdate = { speak(it) },
            onDone = {
                speak("Playing $term.")
                onPlaybackStarted()
            },
            onFail = { speak("Couldn't find \"$term\" on YouTube - the app may still be loading, or nothing matched that name.") }
        )
        speak("Searching YouTube for $term.")
    }

    // ------------------------------------------------------------------
    // Section 3: Spotify - search -> play
    // ------------------------------------------------------------------
    private fun runSpotifySearchFlow(
        context: Context,
        query: String,
        speak: (String) -> Unit,
        onPlaybackStarted: () -> Unit = {}
    ) {
        val term = query.ifBlank { "" }
        if (term.isBlank()) {
            speak("What song should I play?")
            return
        }
        openApp(context, "spotify", speak)
        WhatsAppAccessibilityService.runTask(
            steps = listOf(
                AutomationStep.WaitForPackage("com.spotify.music"),
                AutomationStep.Wait(300),
                AutomationStep.ClickText("Search", minYFraction = 0.7f),
                AutomationStep.Wait(200),
                AutomationStep.TypeText(term),
                AutomationStep.ClickFirstResult(excludeText = term, matchQuery = term)
            ),
            onUpdate = { speak(it) },
            onDone = {
                speak("Playing $term.")
                UsageStats.recordAndMaybeLearn(context, "spotify", term) { t ->
                    "Frequently plays \"$t\" on Spotify."
                }
                onPlaybackStarted()
            },
            onFail = { speak("Couldn't find \"$term\" on Spotify - the app may still be loading, or nothing matched that name.") }
        )
        speak("Searching Spotify for $term.")
    }

    // ------------------------------------------------------------------
    // Section 3: JioSaavn - search -> play (same pattern as Spotify)
    // ------------------------------------------------------------------
    private fun runJioSaavnSearchFlow(
        context: Context,
        query: String,
        speak: (String) -> Unit,
        onPlaybackStarted: () -> Unit = {}
    ) {
        val term = query.ifBlank { "" }
        if (term.isBlank()) {
            speak("What song should I play on JioSaavn?")
            return
        }
        openApp(context, "jiosaavn", speak)
        WhatsAppAccessibilityService.runTask(
            steps = listOf(
                // Cold-launching JioSaavn can take longer than a fixed Wait
                // covers, which was the main reason "Search" never got
                // tapped and the whole flow quietly did nothing - wait for
                // its window to actually be the foreground one first.
                AutomationStep.WaitForPackage("com.jio.media.jiobeats"),
                // ClickText/TypeText/ClickFirstResult all retry every tick
                // (now 90ms) until they land or hit their own 5s timeout -
                // that retry loop already covers "the app is still loading",
                // so the fixed Wait()s that used to sit between these steps
                // were pure dead time layered on top of it, not something
                // that made the flow more reliable. Trimmed to small
                // settle-time pauses only.
                //
                // The "Search" tap is now also restricted to the bottom
                // ~30% of the screen (minYFraction) so it can only match the
                // actual bottom-nav Search tab, not a same-labelled element
                // higher up the screen. That distinction didn't matter on
                // the old JioSaavn layout, but the current "PRO" redesign
                // adds extra promo/branding rows above the fold - if any of
                // those happen to carry "Search" text or a content
                // description containing it, the old unrestricted match
                // could land on that instead of the real tab and the whole
                // flow would silently go nowhere, which matches "it can't
                // search now, but it used to work."
                AutomationStep.Wait(300),
                AutomationStep.ClickText("Search", minYFraction = 0.7f),
                AutomationStep.Wait(200),
                AutomationStep.TypeText(term),
                AutomationStep.ClickFirstResult(excludeText = term, matchQuery = term),
                // JioSaavn (unlike YouTube) usually opens the song on the
                // player screen PAUSED rather than auto-playing it - that's
                // the "I have to manually click it" bug. Tap the Play button
                // if one's showing. Marked optional so if it already started
                // playing (no Play button to find, it'll show Pause instead)
                // this just gets skipped quickly instead of stalling the flow.
                AutomationStep.ClickText("Play", exactMatch = true, optional = true)
            ),
            onUpdate = { speak(it) },
            onDone = {
                speak("Playing $term.")
                UsageStats.recordAndMaybeLearn(context, "jiosaavn", term) { t ->
                    "Frequently plays \"$t\" on JioSaavn."
                }
                // "Turn itself off" once the song is actually playing -
                // both always-listen (foreground) and background Brosco
                // repeatedly grab audio focus every listening cycle, which
                // was pausing/cutting the song right back off after it
                // started. Stopping the listener once playback begins is
                // what lets it actually keep playing.
                onPlaybackStarted()
            },
            onFail = { speak("Couldn't find \"$term\" on JioSaavn - the app may still be loading, or nothing matched that name.") }
        )
        speak("Searching JioSaavn for $term.")
    }

    // ------------------------------------------------------------------
    // Section 3: JioSaavn - "play my playlist" -> Library tab -> the
    // playlist Shrey marked as his own -> play it, without needing a
    // search term at all.
    // ------------------------------------------------------------------
    private fun runJioSaavnMyPlaylistFlow(
        context: Context,
        speak: (String) -> Unit,
        onPlaybackStarted: () -> Unit = {}
    ) {
        openApp(context, "jiosaavn", speak)
        WhatsAppAccessibilityService.runTask(
            steps = listOf(
                AutomationStep.WaitForPackage("com.jio.media.jiobeats"),
                AutomationStep.Wait(300),
                // Bottom nav tab - same screen as the search flow's Library
                // shortcut, just a different starting tap. Position-gated
                // for the same reason as the Search tab above.
                AutomationStep.ClickText("Library", minYFraction = 0.7f),
                AutomationStep.Wait(300),
                // Some layouts show a "Playlists" filter chip on the
                // library screen before the actual playlist list; harmless
                // no-op (skipped quickly) if this build goes straight there.
                AutomationStep.ClickText("Playlists", optional = true),
                AutomationStep.Wait(500),
                // "Create Playlist" was slipping through the exclusion-by-
                // label check: its actual clickable node very likely has no
                // text of its own (icon + a separate "Create Playlist"
                // TextView next to it), the exact same shape that was
                // breaking the Domino's/Zomato item matching - so a filter
                // that only looks at the CLICKED node's own label never saw
                // it. Fixed the same way: match POSITIVELY for something
                // only a real playlist card has - every playlist row shows
                // a song count ("15 Songs"), and "Create Playlist" never
                // does. Scoring against "songs"/"tracks" finds that text
                // and taps its nearest clickable ancestor (the actual
                // card), which can't land on Create Playlist even if its
                // hitbox overlaps the cutoff line.
                AutomationStep.ClickFirstResult(minYFraction = 0.30f, matchQuery = "songs tracks"),
                AutomationStep.Wait(1200),
                // Opening a playlist usually lands on its track list rather
                // than auto-playing - tap Play/Shuffle if one's showing,
                // same pattern as the paused-on-open JioSaavn search flow.
                AutomationStep.ClickText("Play", exactMatch = false, optional = true)
            ),
            onUpdate = { speak(it) },
            onDone = {
                speak("Playing your playlist.")
                onPlaybackStarted()
            },
            onFail = { speak("Couldn't open your playlist on JioSaavn - the Library screen may have looked different than expected.") }
        )
        speak("Opening your playlist on JioSaavn.")
    }

    private fun parseSwipeDirection(target: String): SwipeDirection? {
        return when {
            target.contains("up") -> SwipeDirection.UP
            target.contains("down") -> SwipeDirection.DOWN
            target.contains("left") -> SwipeDirection.LEFT
            target.contains("right") -> SwipeDirection.RIGHT
            else -> null
        }
    }

    private fun sendWhatsAppMessage(context: Context, name: String, message: String, speak: (String) -> Unit) {
        val number = lookupContactNumber(context, name)
        if (number == null) {
            speak("I couldn't find $name in your contacts")
            return
        }
        val cleanNumber = number.replace(Regex("[^0-9+]"), "")
        val encodedMsg = URLEncoder.encode(message, "UTF-8")
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=$encodedMsg")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            // Send button click is now driven by the ticker/task queue too,
            // so it no longer depends on an accessibility event arriving.
            WhatsAppAccessibilityService.runTask(
                listOf(AutomationStep.Wait(1000), AutomationStep.ClickWhatsAppSend),
                onUpdate = {}
            )
            speak("Sending to $name on WhatsApp")
        } catch (e: Exception) {
            speak("Couldn't open WhatsApp")
        }
    }

    private fun callContact(context: Context, name: String, speak: (String) -> Unit) {
        val number = lookupContactNumber(context, name)
        if (number == null) {
            speak("I couldn't find $name in your contacts")
            return
        }
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            speak("Calling $name")
        } catch (e: SecurityException) {
            speak("I need call permission to do that")
        }
    }

    private fun smsContact(context: Context, name: String, body: String, speak: (String) -> Unit) {
        val number = lookupContactNumber(context, name)
        if (number == null) {
            speak("I couldn't find $name in your contacts")
            return
        }
        try {
            val smsManager = android.telephony.SmsManager.getDefault()
            smsManager.sendTextMessage(number, null, body, null, null)
            speak("Sent to $name")
        } catch (e: Exception) {
            speak("Couldn't send that text")
        }
    }

    private fun lookupContactNumber(context: Context, name: String): String? {
        val cursor: Cursor? = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                return it.getString(numberIndex)
            }
        }
        return null
    }

    private fun openApp(context: Context, name: String, speak: (String) -> Unit) {
        val pkg = appPackages[name] ?: run {
            speak("I don't have $name mapped yet")
            return
        }
        val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            speak("Opening $name")
        } else {
            speak("$name isn't installed")
        }
    }

    private fun openZomatoSearch(context: Context, query: String, speak: (String) -> Unit) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val uri = Uri.parse("https://www.zomato.com/search?q=$encodedQuery")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val zomatoInstalled = context.packageManager.getLaunchIntentForPackage("com.application.zomato") != null

        if (zomatoInstalled) {
            intent.setPackage("com.application.zomato")
            try {
                context.startActivity(intent)
                speak("Here's what I found for $query on Zomato")
                return
            } catch (e: Exception) {
                // Zomato couldn't handle this exact URL - fall through to plain app launch below
            }
            val launchIntent = context.packageManager.getLaunchIntentForPackage("com.application.zomato")
            launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                speak("Opening Zomato - search for $query yourself, the direct link didn't work")
            } else {
                speak("Couldn't open Zomato")
            }
        } else {
            speak("Zomato isn't installed")
        }
    }
}
