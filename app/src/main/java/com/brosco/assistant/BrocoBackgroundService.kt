package com.brosco.assistant

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

    private val mainHandler = Handler(Looper.getMainLooper())

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

                val matches =
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)

                val heard = matches?.firstOrNull()?.trim()

                if (!heard.isNullOrEmpty()) {
                    handleHeard(heard)
                }

                restart()
            }

            override fun onError(error: Int) {
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
        if (isSpeaking) return

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

        mainHandler.postDelayed({

            if (!isRunning || isSpeaking) return@postDelayed

            try {
                recognizer?.cancel()
                recognizer?.startListening(intent)
            } catch (_: Exception) {
            }

        }, 500)
    }

    private fun handleHeard(heard: String) {

        val lower = heard.lowercase()

        if (expectingCommand) {

            expectingCommand = false
            runCommand(heard)
            return
        }

        if (lower.contains("brosco")) {

            val afterWake = lower.substringAfter("brosco").trim()

            if (afterWake.isNotEmpty()) {

                runCommand(afterWake)

            } else {

                speak("Yes Shrey.")

                expectingCommand = true

                mainHandler.postDelayed({

                    expectingCommand = false

                }, 8000)
            }
        }
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
        
