package com.brosco.assistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var statusText: TextView
    private lateinit var responseText: TextView
    private lateinit var micButton: Button
    private lateinit var textInput: EditText
    private lateinit var sendTextButton: Button
    private lateinit var alwaysListenButton: Button
    private var pulseAnimator: ObjectAnimator? = null

    private var continuousRecognizer: SpeechRecognizer? = null
    private var alwaysListening = false
    private var expectingCommand = false

    private val SPEECH_REQUEST_CODE = 100
    private val PERMISSIONS_REQUEST_CODE = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        responseText = findViewById(R.id.responseText)
        micButton = findViewById(R.id.micButton)
        textInput = findViewById(R.id.textInput)
        sendTextButton = findViewById(R.id.sendTextButton)
        alwaysListenButton = findViewById(R.id.alwaysListenButton)
        tts = TextToSpeech(this, this)

        ensurePermissions()

        micButton.setOnClickListener {
            ensurePermissions()
            startListening()
        }

        sendTextButton.setOnClickListener {
            val text = textInput.text.toString().trim()
            if (text.isNotEmpty()) {
                statusText.text = "\"$text\""
                handleCommand(text)
                textInput.setText("")
            }
        }

        alwaysListenButton.setOnClickListener {
            if (alwaysListening) {
                stopAlwaysListening()
            } else {
                startAlwaysListening()
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }

    private fun startAlwaysListening() {
        ensurePermissions()
        alwaysListening = true
        alwaysListenButton.text = "Stop Always-Listen"
        statusText.text = "Always listening..."
        startPulse()

        continuousRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        continuousRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val heard = matches?.get(0)?.trim()
                if (!heard.isNullOrEmpty()) {
                    handleWakeAndCommand(heard)
                }
                if (alwaysListening) restartContinuousListening()
            }
            override fun onError(error: Int) {
                if (alwaysListening) restartContinuousListening()
            }
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        restartContinuousListening()
    }

    private fun restartContinuousListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        Handler(Looper.getMainLooper()).postDelayed({
            if (alwaysListening) continuousRecognizer?.startListening(intent)
        }, 300)
    }

    private fun stopAlwaysListening() {
        alwaysListening = false
        alwaysListenButton.text = "Enable Always-Listen"
        statusText.text = "Brosco"
        stopPulse()
        continuousRecognizer?.destroy()
        continuousRecognizer = null
    }

    private fun handleWakeAndCommand(heard: String) {
        val lower = heard.lowercase()
        if (expectingCommand) {
            expectingCommand = false
            statusText.text = "\"$heard\""
            handleCommand(heard)
            return
        }
        if (lower.contains("brosco")) {
            val afterWake = lower.substringAfter("brosco").trim()
            if (afterWake.isNotEmpty()) {
                statusText.text = "\"$heard\""
                handleCommand(afterWake)
            } else {
                speak("Yes Shrey sir?")
                expectingCommand = true
            }
        }
    }

    private fun startPulse() {
        pulseAnimator?.cancel()
        val animator = ObjectAnimator.ofFloat(micButton, "scaleX", 1f, 1.15f, 1f)
        val animatorY = ObjectAnimator.ofFloat(micButton, "scaleY", 1f, 1.15f, 1f)
        animator.duration = 700
        animatorY.duration = 700
        animator.repeatCount = ValueAnimator.INFINITE
        animatorY.repeatCount = ValueAnimator.INFINITE
        animator.interpolator = LinearInterpolator()
        animatorY.interpolator = LinearInterpolator()
        animator.start()
        animatorY.start()
        pulseAnimator = animator
    }

    private fun stopPulse() {
        if (!alwaysListening) {
            pulseAnimator?.cancel()
            micButton.scaleX = 1f
            micButton.scaleY = 1f
        }
    }

    private fun speak(text: String) {
        runOnUiThread {
            responseText.text = text
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    private fun ensurePermissions() {
        val needed = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        }
    }

    private fun startListening() {
        statusText.text = "Listening..."
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak a command...")
        try {
            startActivityForResult(intent, SPEECH_REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "Speech recognition not available", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SPEECH_REQUEST_CODE && data != null) {
            val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val heard = results?.get(0) ?: run {
                statusText.text = "Brosco"
                return
            }
            statusText.text = "\"$heard\""
            handleCommand(heard)
        } else {
            statusText.text = "Brosco"
        }
    }

    private fun handleCommand(rawText: String) {
        val text = rawText.trim().lowercase()

        val whatsappRegex = Regex("(?:message|whatsapp)\\s+(.+?)\\s+(?:on whatsapp\\s+)?saying\\s+(.+)")
        val whatsappMatch = whatsappRegex.find(text)

        when {
            whatsappMatch != null && text.contains("whatsapp") -> {
                val name = whatsappMatch.groupValues[1].trim()
                val message = whatsappMatch.groupValues[2].trim()
                sendWhatsAppMessage(name, message)
            }
            text.startsWith("call ") -> {
                val name = text.removePrefix("call ").trim()
                callContact(name)
            }
            text.startsWith("text ") || text.startsWith("message ") -> {
                val body = text.substringAfter(" ").trim()
                val parts = body.split(" saying ", limit = 2)
                if (parts.size == 2) {
                    smsContact(parts[0].trim(), parts[1].trim())
                } else {
                    speak("Say it like: text John saying I'm on my way")
                }
            }
            text.startsWith("order ") -> {
                val food = text.removePrefix("order ").trim().replace(" from ", " ")
                openZomatoSearch(food)
            }
            text.startsWith("find ") && (text.contains("restaurant") || text.contains("food")) -> {
                val query = text.removePrefix("find ").trim()
                openZomatoSearch(query)
            }
            text.startsWith("open ") -> {
                val appName = text.removePrefix("open ").trim()
                openApp(appName)
            }
            else -> {
                askBrain(rawText)
            }
        }
    }

    private fun sendWhatsAppMessage(name: String, message: String) {
        val number = lookupContactNumber(name)
        if (number == null) {
            speak("I couldn't find $name in your contacts")
            return
        }
        val cleanNumber = number.replace(Regex("[^0-9+]"), "")
        WhatsAppAccessibilityService.pendingMessage = message
        val encodedMsg = URLEncoder.encode(message, "UTF-8")
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber&text=$encodedMsg")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(intent)
            speak("Sending to $name on WhatsApp")
        } catch (e: Exception) {
            speak("Couldn't open WhatsApp")
        }
    }

    private fun callContact(name: String) {
        val number = lookupContactNumber(name)
        if (number == null) {
            speak("I couldn't find $name in your contacts")
            return
        }
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        try {
            startActivity(intent)
            speak("Calling $name")
        } catch (e: SecurityException) {
            speak("I need call permission to do that")
        }
    }

    private fun smsContact(name: String, body: String) {
        val number = lookupContactNumber(name)
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

    private fun lookupContactNumber(name: String): String? {
        val cursor: Cursor? = contentResolver.query(
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

    private val appPackages = mapOf(
        "whatsapp" to "com.whatsapp",
        "spotify" to "com.spotify.music",
        "maps" to "com.google.android.apps.maps",
        "youtube" to "com.google.android.youtube",
        "gmail" to "com.google.android.gm",
        "instagram" to "com.instagram.android",
        "camera" to "com.android.camera",
        "zomato" to "com.application.zomato"
    )

    private fun openApp(name: String) {
        val pkg = appPackages[name] ?: run {
            speak("I don't have $name mapped yet")
            return
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            startActivity(launchIntent)
            speak("Opening $name")
        } else {
            speak("$name isn't installed")
        }
    }

    private fun openZomatoSearch(query: String) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val uri = Uri.parse("https://www.zomato.com/search?q=$encodedQuery")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(intent)
            speak("Here's what I found for $query on Zomato")
        } catch (e: Exception) {
            speak("Couldn't open Zomato")
        }
    }

    private fun askBrain(query: String) {
        speak("Let me think...")
        startPulse()
        CoroutineScope(Dispatchers.Main).launch {
            val answer = withContext(Dispatchers.IO) {
                GroqApiClient.ask(query)
            }
            stopPulse()
            statusText.text = if (alwaysListening) "Always listening..." else "Brosco"
            speak(answer)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        continuousRecognizer?.destroy()
    }
}
