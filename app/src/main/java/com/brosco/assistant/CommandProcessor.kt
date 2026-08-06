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

    fun process(context: Context, rawText: String, scope: CoroutineScope, speak: (String) -> Unit) {
        val text = rawText.trim().lowercase()
        val detectedIntent = IntentDetector.detect(text)

        val whatsappRegex = Regex("(?:message|whatsapp)\\s+(.+?)\\s+(?:on whatsapp\\s+)?saying\\s+(.+)")
        val whatsappMatch = whatsappRegex.find(text)

        when {
            // Section 5: chained commands - "open zomato then search burger then add to cart"
            detectedIntent.type == IntentType.MULTI_STEP -> {
                runChain(context, detectedIntent.target, scope, speak)
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
                    WhatsAppAccessibilityService.runTask(
                        listOf(AutomationStep.AddItemNear(detectedIntent.target)),
                        onUpdate = { speak(it) },
                        onDone = { speak("Added ${detectedIntent.target}.") }
                    )
                }
            }
            detectedIntent.type == IntentType.YOUTUBE_SEARCH -> {
                runYoutubeSearchFlow(context, detectedIntent.target, speak)
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
                runSpotifySearchFlow(context, detectedIntent.target, speak)
            }
            detectedIntent.type == IntentType.SPOTIFY_PAUSE -> {
                WhatsAppAccessibilityService.runTask(listOf(AutomationStep.ClickText("pause")), onUpdate = {})
                speak("Pausing the music.")
            }
            detectedIntent.type == IntentType.SPOTIFY_NEXT -> {
                WhatsAppAccessibilityService.runTask(listOf(AutomationStep.ClickText("skip to next")), onUpdate = {})
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
                        GroqApiClient.ask(rawText)
                    }
                    speak(answer)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Section 5: Planning - chained multi-step voice commands
    // ------------------------------------------------------------------
    private fun runChain(context: Context, chained: String, scope: CoroutineScope, speak: (String) -> Unit) {
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
                process(context, piece, this, speak)
                if (index < pieces.lastIndex) delay(2000)
            }
        }
    }

    // ------------------------------------------------------------------
    // Section 3: Zomato - search food -> select restaurant -> add to cart -> open cart
    // ------------------------------------------------------------------
    private fun runZomatoFlow(context: Context, food: String, speak: (String) -> Unit) {
        val query = food.ifBlank { "food" }
        openApp(context, "zomato", speak)
        WhatsAppAccessibilityService.runTask(
            steps = listOf(
                AutomationStep.Wait(1400),
                AutomationStep.ClickText("Search"),
                AutomationStep.Wait(400),
                AutomationStep.TypeText(query),
                AutomationStep.Wait(1400),
                AutomationStep.ClickText(query, exactMatch = false),
                AutomationStep.Wait(1400),
                AutomationStep.ClickText("Add"),
                AutomationStep.Wait(600),
                AutomationStep.ClickText("View Cart")
            ),
            onUpdate = { speak(it) },
            onDone = { speak("Your cart should be open - take a look and confirm the order.") }
        )
        speak("Searching for $query on Zomato.")
    }

    // ------------------------------------------------------------------
    // Section 3: Domino's - search pizza -> choose size -> add to cart -> open cart
    // ------------------------------------------------------------------
    private fun runDominosFlow(context: Context, pizza: String, speak: (String) -> Unit) {
        val query = pizza.ifBlank { "pizza" }
        openApp(context, "dominos", speak)
        WhatsAppAccessibilityService.runTask(
            steps = listOf(
                AutomationStep.Wait(1400),
                AutomationStep.ClickText("Search"),
                AutomationStep.Wait(400),
                AutomationStep.TypeText(query),
                AutomationStep.Wait(1400),
                AutomationStep.ClickText(query, exactMatch = false),
                AutomationStep.Wait(1200),
                AutomationStep.ClickText("Medium"),
                AutomationStep.Wait(600),
                AutomationStep.ClickText("Add"),
                AutomationStep.Wait(600),
                AutomationStep.ClickText("Cart")
            ),
            onUpdate = { speak(it) },
            onDone = { speak("Cart's open - the size I picked was medium, change it if you'd like something else.") }
        )
        speak("Looking for $query on Domino's.")
    }

    // ------------------------------------------------------------------
    // Section 3: YouTube - search -> play first result
    // ------------------------------------------------------------------
    private fun runYoutubeSearchFlow(context: Context, query: String, speak: (String) -> Unit) {
        val term = query.ifBlank { "" }
        if (term.isBlank()) {
            speak("What should I search on YouTube?")
            return
        }
        openApp(context, "youtube", speak)
        WhatsAppAccessibilityService.runTask(
            steps = listOf(
                AutomationStep.Wait(1200),
                AutomationStep.ClickText("Search"),
                AutomationStep.Wait(400),
                AutomationStep.TypeText(term),
                AutomationStep.Wait(1200),
                AutomationStep.ClickText(term, exactMatch = false)
            ),
            onUpdate = { speak(it) },
            onDone = { speak("Playing $term.") }
        )
        speak("Searching YouTube for $term.")
    }

    // ------------------------------------------------------------------
    // Section 3: Spotify - search -> play
    // ------------------------------------------------------------------
    private fun runSpotifySearchFlow(context: Context, query: String, speak: (String) -> Unit) {
        val term = query.ifBlank { "" }
        if (term.isBlank()) {
            speak("What song should I play?")
            return
        }
        openApp(context, "spotify", speak)
        WhatsAppAccessibilityService.runTask(
            steps = listOf(
                AutomationStep.Wait(1200),
                AutomationStep.ClickText("Search"),
                AutomationStep.Wait(400),
                AutomationStep.TypeText(term),
                AutomationStep.Wait(1200),
                AutomationStep.ClickText(term, exactMatch = false)
            ),
            onUpdate = { speak(it) },
            onDone = { speak("Playing $term.") }
        )
        speak("Searching Spotify for $term.")
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
