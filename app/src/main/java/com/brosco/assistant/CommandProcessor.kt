package com.brosco.assistant

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

object CommandProcessor {

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

    fun process(
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

        val whatsappRegex = Regex("(?:message|whatsapp)\\s+(.+?)\\s+(?:on whatsapp\\s+)?saying\\s+(.+)")
        val whatsappMatch = whatsappRegex.find(text)

        when {
            // Memory
            detectedIntent.type == IntentType.CLEAR_MEMORY -> {
                MemoryStore.clear(context)
                LearnedFacts.clear(context)
                speak("Done - I've forgotten everything up to now.")
            }

            // Section 5: chained commands - "open zomato then search burger then add to cart"
            detectedIntent.type == IntentType.MULTI_STEP -> {
                runChain(context, detectedIntent.target, scope, onPlaybackStarted, speak)
            }

            // Section 3: app-automation flows
            detectedIntent.type == IntentType.ZOMATO_ORDER -> {
                runZomatoFlow(context, detectedIntent.target, speak)
            }
            detectedIntent.type == IntentType.DOMINOS_ORDER -> {
                runDominosFlow(context, detectedIntent.target, speak)
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
                openZomatoSearch(context, detectedIntent.target, speak)
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
            whatsappMatch != null && text.contains("whatsapp") -> {
                val name = whatsappMatch.groupValues[1].trim()
                val message = whatsappMatch.groupValues[2].trim()
                sendWhatsAppMessage(context, name, message, speak)
            }
            text.startsWith("call ") -> {
                val name = text.removePrefix("call ").trim()
                callContact(context, name, speak)
            }
            text.startsWith("text ") || text.startsWith("message ") -> {
                val body = text.substringAfter(" ").trim()
                val parts = body.split(" saying ", limit = 2)
                if (parts.size == 2) {
                    smsContact(context, parts[0].trim(), parts[1].trim(), speak)
                } else {
                    speak("Say it like: text John saying I'm on my way")
                }
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
                scope.launch {
                    val answer = withContext(Dispatchers.IO) {
                        GroqApiClient.ask(context, rawText)
                    }
                    speak(answer)

                    // "Learning": pull out anything durable worth remembering
                    // from this exchange, in the background, after the fact -
                    // never blocks or delays the spoken reply itself.
                    launch {
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
        scope.launch {
            val elements = WhatsAppAccessibilityService.snapshotScreen()
            if (elements.isEmpty()) {
                speak("I can't see anything tappable right now.")
                return@launch
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
    // Section 5: Planning - chained multi-step voice commands
    // ------------------------------------------------------------------
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

        speak("On it - ${pieces.size} steps.")

        scope.launch {
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
            // Coming back from a previous item's cart screen - back out to
            // the menu/search screen before searching for the next one.
            steps += AutomationStep.GoBack
            steps += AutomationStep.Wait(600)
        } else {
            openApp(context, "zomato", speak)
            steps += AutomationStep.WaitForPackage("com.application.zomato")
            steps += AutomationStep.Wait(800)
        }
        steps += listOf(
            AutomationStep.ClickText("Search"),
            AutomationStep.Wait(500),
            AutomationStep.TypeText(query),
            AutomationStep.Wait(1600),
            AutomationStep.ClickFirstResult(excludeText = query),
            AutomationStep.Wait(1600),
            AutomationStep.ClickText("Add"),
            AutomationStep.Wait(700),
            AutomationStep.ClickText("View Cart")
        )

        WhatsAppAccessibilityService.runTask(
            steps = steps,
            onUpdate = { speak(it) },
            onDone = { speak("$query is in your cart - take a look and confirm the order.") }
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
            // We're most likely sitting on the cart screen from the item
            // just added - back out to the menu before searching again so
            // "then add X" lands a second item instead of hunting for it
            // on the cart screen where it can't possibly be.
            steps += AutomationStep.GoBack
            steps += AutomationStep.Wait(600)
        } else {
            openApp(context, "dominos", speak)
            steps += AutomationStep.WaitForPackage("com.Dominos")
            steps += AutomationStep.Wait(800)
        }
        steps += listOf(
            AutomationStep.ClickText("Search"),
            AutomationStep.Wait(500),
            AutomationStep.TypeText(query),
            AutomationStep.Wait(1600),
            AutomationStep.ClickFirstResult(excludeText = query),
            AutomationStep.Wait(1400),
            AutomationStep.ClickText("Medium"),
            AutomationStep.Wait(700),
            AutomationStep.ClickText("Add"),
            AutomationStep.Wait(700),
            AutomationStep.ClickText("Cart")
        )

        WhatsAppAccessibilityService.runTask(
            steps = steps,
            onUpdate = { speak(it) },
            onDone = { speak("$query is in the cart - medium size, change it there if you want something else.") }
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
                AutomationStep.Wait(700),
                AutomationStep.ClickText("Search"),
                AutomationStep.Wait(500),
                // TypeText already submits via IME "search"/enter, so by the
                // time this wait elapses we should be on the results screen,
                // not still showing search suggestions.
                AutomationStep.TypeText(term),
                AutomationStep.Wait(2000),
                // Tap the first actual result thumbnail/row rather than
                // matching the typed text - a video's title is almost never
                // identical to the search phrase, so the old exact-ish text
                // match usually found nothing (or re-tapped the search
                // suggestion echoing the query) and the flow silently
                // stalled without ever pressing play.
                AutomationStep.ClickFirstResult(excludeText = term, minYFraction = 0.16f)
            ),
            onUpdate = { speak(it) },
            onDone = {
                speak("Playing $term.")
                onPlaybackStarted()
            }
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
                AutomationStep.Wait(700),
                AutomationStep.ClickText("Search"),
                AutomationStep.Wait(500),
                AutomationStep.TypeText(term),
                AutomationStep.Wait(1800),
                AutomationStep.ClickFirstResult(excludeText = term)
            ),
            onUpdate = { speak(it) },
            onDone = {
                speak("Playing $term.")
                onPlaybackStarted()
            }
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
                AutomationStep.Wait(700),
                AutomationStep.ClickText("Search"),
                AutomationStep.Wait(500),
                AutomationStep.TypeText(term),
                AutomationStep.Wait(1800),
                AutomationStep.ClickFirstResult(excludeText = term)
            ),
            onUpdate = { speak(it) },
            onDone = {
                speak("Playing $term.")
                // "Turn itself off" once the song is actually playing -
                // both always-listen (foreground) and background Brosco
                // repeatedly grab audio focus every listening cycle, which
                // was pausing/cutting the song right back off after it
                // started. Stopping the listener once playback begins is
                // what lets it actually keep playing.
                onPlaybackStarted()
            }
        )
        speak("Searching JioSaavn for $term.")
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
