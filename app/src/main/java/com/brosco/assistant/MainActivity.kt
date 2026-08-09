package com.brosco.assistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
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
    private lateinit var pulseRing1: View
    private lateinit var pulseRing2: View
    private lateinit var galaxyBackground: GalaxyBackgroundView
    private var ringAnimSet: android.animation.AnimatorSet? = null
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
        pulseRing1 = findViewById(R.id.pulseRing1)
        pulseRing2 = findViewById(R.id.pulseRing2)
        textInput = findViewById(R.id.textInput)
        sendTextButton = findViewById(R.id.sendTextButton)
        alwaysListenButton = findViewById(R.id.alwaysListenButton)
        backgroundServiceButton = findViewById(R.id.backgroundServiceButton)
        tts = TextToSpeech(this, this)

        statusText.post { applyTitleGradient() }

        galaxyBackground = findViewById(R.id.galaxyBackground)
        galaxyBackground.start()

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
                galaxyBackground.setBackgroundActive(false)
            } else {
                ensurePermissions()
                requestBatteryOptimizationExemption()
                val intent = Intent(this, BrocoBackgroundService::class.java)
                ContextCompat.startForegroundService(this, intent)
                // Flip the animation the instant you tap, don't wait for
                // the 500ms label-refresh poll below - it should feel
                // immediate.
                galaxyBackground.setBackgroundActive(true)
            }
            Handler(Looper.getMainLooper()).postDelayed({ updateBackgroundButtonLabel() }, 500)
        }
    }

    /**
     * Background Brosco holds the mic via a foreground service, but several
     * OEMs (Xiaomi/Samsung/OnePlus/etc.) still aggressively kill
     * foreground services once the screen's been off for a while unless
     * the app is exempted from battery optimization. This is the one piece
     * of that we can actually trigger from code - the rest (OEM-specific
     * "autostart"/"no restrictions" toggles) has to be set manually per
     * device and can't be requested programmatically.
     */
    private fun requestBatteryOptimizationExemption() {
        val powerManager = getSystemService(android.os.PowerManager::class.java)
        if (powerManager != null && !powerManager.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                }
                startActivity(intent)
                Toast.makeText(
                    this,
                    "Allow Brosco to run unrestricted so background listening doesn't get killed.",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                // Some OEM ROMs strip this action out entirely - nothing
                // more to do from code if so.
            }
        }
    }

    override fun onResume() {
        super.onResume()
        galaxyBackground.start()
        galaxyBackground.setBackgroundActive(BrocoBackgroundService.isRunning)
        updateBackgroundButtonLabel()
    }

    override fun onPause() {
        super.onPause()
        galaxyBackground.stop()
    }

    private fun updateBackgroundButtonLabel() {
        backgroundServiceButton.text = if (BrocoBackgroundService.isRunning)
            "Stop Background Brosco" else "Start Background Brosco"
        galaxyBackground.setBackgroundActive(BrocoBackgroundService.isRunning)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }

    private fun runCommand(text: String) {
        galaxyBackground.setActive(true)
        CommandProcessor.process(
            context = this,
            rawText = text,
            scope = CoroutineScope(Dispatchers.Main),
            onPlaybackStarted = {
                runOnUiThread {
                    // Once a song/video is actually playing, stop
                    // always-listen so it doesn't keep grabbing audio focus
                    // every recognition cycle and cutting the playback
                    // right back off.
                    if (alwaysListening) stopAlwaysListening()
                }
            }
        ) { response ->
            runOnUiThread {
                stopPulse()
                galaxyBackground.setActive(alwaysListening)
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
        galaxyBackground.setActive(true)

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
        galaxyBackground.setActive(false)
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

    private fun applyTitleGradient() {
        val width = statusText.width.toFloat()
        if (width <= 0f) return
        val shader = LinearGradient(
            0f, 0f, width, 0f,
            intArrayOf(
                android.graphics.Color.parseColor("#7F5AF0"),
                android.graphics.Color.parseColor("#2CB1FF"),
                android.graphics.Color.parseColor("#00E5C7")
            ),
            null,
            Shader.TileMode.CLAMP
        )
        statusText.paint.shader = shader
        statusText.invalidate()
    }

    private fun startPulse() {
        pulseAnimator?.cancel()
        val animator = ObjectAnimator.ofFloat(micButton, "scaleX", 1f, 1.1f, 1f)
        val animatorY = ObjectAnimator.ofFloat(micButton, "scaleY", 1f, 1.1f, 1f)
        animator.duration = 700
        animatorY.duration = 700
        animator.repeatCount = ValueAnimator.INFINITE
        animatorY.repeatCount = ValueAnimator.INFINITE
        animator.interpolator = LinearInterpolator()
        animatorY.interpolator = LinearInterpolator()
        animator.start()
        animatorY.start()
        pulseAnimator = animator

        startRingPulse()
    }

    private fun startRingPulse() {
        ringAnimSet?.cancel()
        val set1 = buildRingAnimator(pulseRing1, 0L)
        val set2 = buildRingAnimator(pulseRing2, 550L)
        val combined = android.animation.AnimatorSet()
        combined.playTogether(set1, set2)
        combined.start()
        ringAnimSet = combined
    }

    private fun buildRingAnimator(ring: View, startDelay: Long): android.animation.AnimatorSet {
        ring.scaleX = 1f
        ring.scaleY = 1f
        ring.alpha = 0.7f

        val scaleX = ObjectAnimator.ofFloat(ring, "scaleX", 1f, 1.9f)
        val scaleY = ObjectAnimator.ofFloat(ring, "scaleY", 1f, 1.9f)
        val alpha = ObjectAnimator.ofFloat(ring, "alpha", 0.7f, 0f)

        listOf(scaleX, scaleY, alpha).forEach {
            it.duration = 1400
            it.repeatCount = ValueAnimator.INFINITE
            it.startDelay = startDelay
        }

        val set = android.animation.AnimatorSet()
        set.playTogether(scaleX, scaleY, alpha)
        return set
    }

    private fun stopPulse() {
        if (!alwaysListening) {
            pulseAnimator?.cancel()
            micButton.scaleX = 1f
            micButton.scaleY = 1f
            ringAnimSet?.cancel()
            pulseRing1.alpha = 0f
            pulseRing2.alpha = 0f
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
        galaxyBackground.setActive(true)
        startPulse()
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
                if (!alwaysListening) galaxyBackground.setActive(false)
                return
            }
            statusText.text = "\"$heard\""
            startPulse()
            runCommand(heard)
        } else {
            statusText.text = "Brosco"
            if (!alwaysListening) galaxyBackground.setActive(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        continuousRecognizer?.destroy()
    }
}
