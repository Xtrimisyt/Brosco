package com.brosco.assistant

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
            // New IntentDetector checks
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
                WhatsAppAccessibilityService.pendingBack = true
                speak("Going back.")
            }
            detectedIntent.type == IntentType.GO_HOME -> {
                WhatsAppAccessibilityService.pendingHome = true
                speak("Going home.")
            }
            detectedIntent.type == IntentType.SCROLL_DOWN -> {
                WhatsAppAccessibilityService.pendingScrollForward = true
                speak("Scrolling down.")
            }
            detectedIntent.type == IntentType.SCROLL_UP -> {
                WhatsAppAccessibilityService.pendingScrollBackward = true
                speak("Scrolling up.")
            }
            detectedIntent.type == IntentType.CLICK -> {
                if (detectedIntent.target.isNotBlank()) {
                    WhatsAppAccessibilityService.pendingClickText = detectedIntent.target
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

    private fun sendWhatsAppMessage(context: Context, name: String, message: String, speak: (String) -> Unit) {
        val number = lookupContactNumber(context, name)
        if (number == null) {
            speak("I couldn't find $name in your contacts")
            return
        }
        val cleanNumber = number.replace(Regex("[^0-9+]"), "")
        WhatsAppAccessibilityService.pendingMessage = message
        val encodedMsg = URLEncoder.encode(message, "UTF-8")
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=$encodedMsg")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
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
