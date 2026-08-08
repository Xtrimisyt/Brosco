package com.brosco.assistant

import android.util.Log
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.Locale

class BrocoBackgroundService : Service(), TextToSpeech.OnInitListener {

    companion object {
        var isRunning = false
        private const val CHANNEL_ID = "brosco_background"
        private const val NOTIFICATION_ID = 1
    }

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    private var expectingCommand = false
    private var isSpeaking = false
    private var consecutiveErrors = 0
    private var cyclesSinceRecreate = 0

    // Every recognizer session briefly grabs exclusive audio focus, which
    // pauses whatever's playing (YouTube, Spotify...). While actively talking
    // to Brosco we stay tight for responsiveness/barge-in; once you go quiet,
    // we back off more and more so media gets real uninterrupted stretches.
    private var idleCyclesWithNoSpeech = 0
    private val idleBaseDelayMs = 1800L
    private val idleMaxDelayMs = 6000L
    private val idleBackoffStepMs = 500L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val wakeGreetings = listOf(
        "Yes, Shrey?",
        "Go ahead, Shrey.",
        "I'm listening.",
        "At your service."
    )

    // The on-device recognizer often mishears "Brosco" as one of these -
    // matching all of them fixes "I have to say it twice" without touching
    // the recognizer itself.
    private val wakeVariants = listOf("brosco", "brasco", "bosco", "brusco", "broscoe", "rosco")

    // Exact-match only (not "contains") - "sleep" is a common word in normal
    // speech/TV/music, so a loose match here would shut Brosco off by
    // accident constantly. This only fires when the whole heard phrase IS
    // one of these, whether said alone or right after the wake word.
    private val sleepPhrases = setOf(
        "sleep", "go to sleep", "goodnight", "good night",
        "shut down", "power down", "turn yourself off", "stop listening"
    )

    private fun isSleepCommand(text: String): Boolean = sleepPhrases.contains(text.trim().lowercase())

    override fun onCreate() {
        super.onCreate()

        isRunning = true

        tts = TextToSpeech(this, this)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        mainHandler.post {
            startListeningLoop()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListeningLoop() {

        recognizer = SpeechRecognizer.createSpeechRecognizer(this)

        recognizer?.setRecognitionListener(object : RecognitionListener {

            override fun onResults(results: Bundle?) {

                consecutiveErrors = 0

                val matches =
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)

                val heard = matches?.firstOrNull()?.trim()

                if (!heard.isNullOrEmpty()) {
                    idleCyclesWithNoSpeech = 0
                    handleHeard(heard)
                } else {
                    idleCyclesWithNoSpeech++
                }

                restart()
            }

            override fun onError(error: Int) {
                consecutiveErrors++
                idleCyclesWithNoSpeech++
                Log.w("Brosco", "Recognizer error $error, consecutive=$consecutiveErrors")
                restart()
            }

            override fun onReadyForSpeech(params: Bundle?) {}

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {}

            override fun onPartialResults(partialResults: Bundle?) {}

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        restart()
    }

    private fun restart() {

        if (!isRunning) return

        cyclesSinceRecreate++

        // Android's on-device recognizer tends to wedge itself (stops firing
        // callbacks entirely, e.g. after repeated ERROR_RECOGNIZER_BUSY) if the
        // same instance is reused for too many cycles in a row, especially with
        // no foreground Activity. Periodically tear it down and build a fresh one.
        val needsRecreate = consecutiveErrors >= 3 || cyclesSinceRecreate >= 20
        val delayMs = when {
            consecutiveErrors >= 3 -> 2000L
            isSpeaking || expectingCommand -> 300L
            else -> (idleBaseDelayMs + idleCyclesWithNoSpeech * idleBackoffStepMs).coerceAtMost(idleMaxDelayMs)
        }

        mainHandler.postDelayed({

            if (!isRunning) return@postDelayed

            if (needsRecreate) {
                try {
                    recognizer?.destroy()
                } catch (_: Exception) {
                }
                consecutiveErrors = 0
                cyclesSinceRecreate = 0
                startListeningLoop()
                return@postDelayed
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(
                    RecognizerIntent.EXTRA_CALLING_PACKAGE,
                    packageName
                )
            }

            try {
                recognizer?.cancel()
                recognizer?.startListening(intent)
            } catch (_: Exception) {
                consecutiveErrors++
            }

        }, delayMs)
    }

    private fun handleHeard(heard: String) {

        val lower = heard.lowercase()
        val matchedWake = wakeVariants.firstOrNull { lower.contains(it) }

        // Brosco is currently talking. Only a fresh "Brosco" cuts him off -
        // anything else heard mid-sentence is almost certainly his own voice
        // bouncing back through the mic, not a real interruption.
        if (isSpeaking && !expectingCommand) {
            if (matchedWake == null) return
            tts?.stop()
            isSpeaking = false
        }

        if (expectingCommand) {

            expectingCommand = false
            if (isSleepCommand(heard)) {
                goToSleep()
                return
            }
            runCommand(heard)
            return
        }

        if (matchedWake != null) {

            val afterWake = lower.substringAfter(matchedWake).trim()

            if (afterWake.isNotEmpty()) {

                if (isSleepCommand(afterWake)) {
                    goToSleep()
                    return
                }
                runCommand(afterWake)

            } else {

                speak(wakeGreetings.random())

                expectingCommand = true

                mainHandler.postDelayed({

                    expectingCommand = false

                }, 8000)
            }
        } else if (isSleepCommand(lower)) {
            // Bare "sleep" with no wake word - deliberate and low-risk (worst
            // case you just have to reopen the app), so no wake word required.
            goToSleep()
        }
    }

    /** Shuts the background listener down entirely - only a wake word or reopening the app brings it back. */
    private fun goToSleep() {
        isRunning = false
        speak("Going to sleep. Reopen the app or restart background listening when you need me.")
        mainHandler.postDelayed({
            stopSelf()
        }, 2200)
    }

    private fun runCommand(text: String) {

        CommandProcessor.process(
            this,
            text,
            CoroutineScope(Dispatchers.Main)
        ) { response ->

            speak(response)
        }
    }

    private fun speak(text: String) {

        isSpeaking = true

        tts?.setOnUtteranceProgressListener(object :
            UtteranceProgressListener() {

            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {

                isSpeaking = false
                restart()
            }

            override fun onError(utteranceId: String?) {

                isSpeaking = false
                restart()
            }
        })

        val params = Bundle()

        tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            params,
            "brosco_reply"
        )
    }

    private fun createNotificationChannel() {

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Brosco Background Listening",
            NotificationManager.IMPORTANCE_LOW
        )

        val manager =
            getSystemService(NotificationManager::class.java)

        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Brosco is listening")
            .setContentText("Say \"Brosco\" followed by a command")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {

        super.onDestroy()

        isRunning = false

        recognizer?.destroy()
        recognizer = null

        tts?.shutdown()
        tts = null
    }
}
