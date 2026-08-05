package com.brosco.assistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var statusText: TextView
    private lateinit var responseText: TextView
    private lateinit var micButton: Button
    private lateinit var textInput: EditText
    private lateinit var sendTextButton: Button
    private lateinit var alwaysListenButton: Button
    private lateinit var backgroundServiceButton: Button
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
        backgroundServiceButton = findViewById(R.id.backgroundServiceButton)
        tts = TextToSpeech(this, this)

        ensurePermissions()
        updateBackgroundButtonLabel()

        micButton.setOnClickListener {
            ensurePermissions()
            startListening()
        }

        sendTextButton.setOnClickListener {
            val text = textInput.text.toString().trim()
            if (text.isNotEmpty()) {
                statusText.text = "\"$text\""
                runCommand(text)
                textInput.setText("")
            }
        }

        alwaysListenButton.setOnClickListener {
            if (alwaysListening) stopAlwaysListening() else startAlwaysListening()
        }

        backgroundServiceButton.setOnClickListener {
            if (BrocoBackgroundService.isRunning) {
                stopService(Intent(this, BrocoBackgroundService::class.java))
            } else {
                ensurePermissions()
                val intent = Intent(this, BrocoBackgroundService::class.java)
                ContextCompat.startForegroundService(this, intent)
            }
            Handler(Looper.getMainLooper()).postDelayed({ updateBackgroundButtonLabel() }, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        updateBackgroundButtonLabel()
    }

    private fun updateBackgroundButtonLabel() {
        backgroundServiceButton.text = if (BrocoBackgroundService.isRunning)
            "Stop Background Brosco" else "Start Background Brosco"
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }

    private fun runCommand(text: String) {
        CommandProcessor.process(this, text, CoroutineScope(Dispatchers.Main)) { response ->
            runOnUiThread {
                stopPulse()
                statusText.text = if (alwaysListening) "Always listening..." else "Brosco"
                speak(response)
            }
        }
    }

    private fun startAlwaysListening() {
        ensurePermissions()
        alwaysListening = true
        alwaysListenButton.text = "Stop Always-Listen (app open)"
        statusText.text = "Always listening..."
        startPulse()

        continuousRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        continuousRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val heard = matches?.get(0)?.trim()
                if (!heard.isNullOrEmpty()) handleWakeAndCommand(heard)
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
        alwaysListenButton.text = "Enable Always-Listen (app open)"
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
            runCommand(heard)
            return
        }
        if (lower.contains("brosco")) {
            val afterWake = lower.substringAfter("brosco").trim()
            if (afterWake.isNotEmpty()) {
                statusText.text = "\"$heard\""
                runCommand(afterWake)
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
        responseText.text = text
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun ensurePermissions() {
        val needed = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toMutableList()

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
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
            startPulse()
            runCommand(heard)
        } else {
            statusText.text = "Brosco"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        continuousRecognizer?.destroy()
    }
}
