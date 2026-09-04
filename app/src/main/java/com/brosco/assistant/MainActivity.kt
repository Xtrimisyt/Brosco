package com.brosco.assistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var statusText: TextView
    private lateinit var micButton: Button
    private lateinit var pulseRing1: View
    private lateinit var pulseRing2: View
    private lateinit var galaxyBackground: GalaxyBackgroundView
    private var ringAnimSet: android.animation.AnimatorSet? = null
    private lateinit var textInput: EditText
    private lateinit var sendTextButton: Button
    private lateinit var alwaysListenButton: Button
    private lateinit var backgroundServiceButton: Button
    private lateinit var attachButton: Button
    private var pulseAnimator: ObjectAnimator? = null

    // Single picker for "📎 attach" - covers photos, videos, and text/code
    // files to fix overnight. Registered as a field (not inside onCreate)
    // since registerForActivityResult must run before the activity reaches
    // STARTED; routing on the picked Uri's actual mime type below means one
    // button and one launcher cover all three cases from the same tap.
    private val attachLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) safely { handleAttachedContent(uri, contentResolver.getType(uri) ?: "") }
    }

    // Claude-style chat transcript: chatScroll holds chatContainer, and
    // every user/assistant turn becomes a bubble row appended to it.
    private lateinit var chatScroll: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var emptyStateText: TextView

    private var continuousRecognizer: SpeechRecognizer? = null
    private var alwaysListening = false
    private var expectingCommand = false

    private val SPEECH_REQUEST_CODE = 100
    private val PERMISSIONS_REQUEST_CODE = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        micButton = findViewById(R.id.micButton)
        pulseRing1 = findViewById(R.id.pulseRing1)
        pulseRing2 = findViewById(R.id.pulseRing2)
        textInput = findViewById(R.id.textInput)
        sendTextButton = findViewById(R.id.sendTextButton)
        alwaysListenButton = findViewById(R.id.alwaysListenButton)
        backgroundServiceButton = findViewById(R.id.backgroundServiceButton)
        attachButton = findViewById(R.id.attachButton)
        chatScroll = findViewById(R.id.chatScroll)
        chatContainer = findViewById(R.id.chatContainer)
        emptyStateText = findViewById(R.id.emptyStateText)
        tts = TextToSpeech(this, this)

        statusText.post { applyTitleGradient() }

        galaxyBackground = findViewById(R.id.galaxyBackground)
        galaxyBackground.start()

        ensurePermissions()
        updateBackgroundButtonLabel()
        handleIncomingIntent(intent)

        micButton.setOnClickListener {
            safely {
                ensurePermissions()
                startListening()
            }
        }

        sendTextButton.setOnClickListener {
            safely {
                val text = textInput.text.toString().trim()
                if (text.isNotEmpty()) {
                    addUserMessage(text)
                    runCommand(text)
                    textInput.setText("")
                }
            }
        }

        alwaysListenButton.setOnClickListener {
            safely {
                if (alwaysListening) stopAlwaysListening() else startAlwaysListening()
            }
        }

        attachButton.setOnClickListener {
            safely { attachLauncher.launch("*/*") }
        }

        backgroundServiceButton.setOnClickListener {
            safely {
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
                Handler(Looper.getMainLooper()).postDelayed({ safely { updateBackgroundButtonLabel() } }, 500)
            }
        }
    }

    /**
     * Runs a UI action and swallows anything unexpected instead of letting it
     * crash the whole process. A crash here would take the accessibility
     * service down with it (same process), which is what forces a manual
     * disable/re-enable toggle in Settings to get Brosco working again.
     */
    private inline fun safely(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            Log.e("Brosco", "UI action crashed: ${e.message}", e)
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
            "STOP BACKGROUND BROSCO" else "BACKGROUND BROSCO"
        galaxyBackground.setBackgroundActive(BrocoBackgroundService.isRunning)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            selectBestVoice()
        }
    }

    /**
     * Shrey asked for "a man, English accent, conversational" for Brosco's
     * voice. Android's TextToSpeech API has no actual gender field on
     * Voice - that's an engine-level detail Google's on-device TTS engine
     * doesn't expose consistently across devices/versions - so this is
     * necessarily a best-effort pick, in order:
     *   1. Narrow to locale en-GB for the British-English accent.
     *   2. Among those, prefer voices whose internal name matches a short
     *      list of identifiers that are commonly male on Google's TTS
     *      engine on stock/Pixel-style installs. NOT a documented
     *      guarantee - Google can and does change these per device/engine
     *      version, which is exactly why this falls back gracefully
     *      instead of crashing or silently doing nothing if none match.
     *   3. Within whatever's left, pick the highest Voice.quality - the
     *      network/WaveNet-style voices sound far more natural and
     *      "conversational" than the default offline robotic one, which
     *      matters as much for the ask as the accent does.
     * If the automatic pick doesn't sound right on your specific phone,
     * the reliable manual override is Settings -> System -> Languages &
     * input -> Text-to-speech -> [engine] -> install/pick an English (UK)
     * male voice there directly - Brosco picks up whatever's set as the
     * highest-quality installed en-GB voice automatically, no code change
     * needed.
     */
    private fun selectBestVoice() {
        val voices = try {
            tts.voices
        } catch (e: Exception) {
            Log.w("Brosco", "Couldn't read TTS voice list: ${e.message}")
            null
        }
        if (voices.isNullOrEmpty()) {
            tts.language = Locale.UK
            return
        }

        val britishVoices = voices.filter {
            it.locale.language.equals("en", ignoreCase = true) &&
                it.locale.country.equals("GB", ignoreCase = true) &&
                !it.isNetworkConnectionRequired.let { needsNetwork -> needsNetwork && !hasNetworkConnection() }
        }

        if (britishVoices.isEmpty()) {
            // No English (UK) voice data installed on this device at all -
            // most phones ship only en-US by default and need the UK pack
            // downloaded separately. Fall back to whatever English is
            // available rather than silently keeping a random previous
            // voice or crashing.
            Log.i("Brosco", "No en-GB voice installed - falling back to default English. " +
                "Install an English (UK) voice under Settings > Text-to-speech for the accent to apply.")
            tts.language = Locale.UK
            tts.setPitch(0.92f)
            tts.setSpeechRate(1.02f)
            return
        }

        val maleHints = listOf("gpg", "rjs", "gbd", "male", "d-network", "d-local")
        val preferredByName = britishVoices.filter { voice ->
            maleHints.any { hint -> voice.name.contains(hint, ignoreCase = true) }
        }
        val candidates = preferredByName.ifEmpty { britishVoices }
        val best = candidates.maxByOrNull { it.quality }

        if (best != null) {
            tts.voice = best
            Log.i("Brosco", "Selected TTS voice: ${best.name} (quality=${best.quality}, " +
                "networkRequired=${best.isNetworkConnectionRequired})")
        } else {
            tts.language = Locale.UK
        }

        // A touch lower pitch reads as more natural/masculine for a
        // conversational assistant tone; rate stays close to 1x (slightly
        // above, not below) so it sounds like it's actually talking to you
        // rather than either rushing through lines or sounding robotic/slow.
        tts.setPitch(0.92f)
        tts.setSpeechRate(1.02f)
    }

    private fun hasNetworkConnection(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Handles anything shared into Brosco via Android's share sheet
     * ("Share via -> Brosco" from Gallery, WhatsApp, a file manager, etc) -
     * photos, videos, and text/code files all arrive this same way, routed
     * by mime type below. See VideoFrameAnalyzer/ImageAnalyzer for what
     * "video/photo analysis" can and can't actually tell you.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return
        val mimeType = intent.type ?: return

        val uri = if (android.os.Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        } ?: return

        // Consume it so rotating the screen or returning to the app later
        // doesn't re-trigger the same analysis.
        intent.action = null

        val question = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        handleAttachedContent(uri, mimeType, question)
    }

    /**
     * Single routing point for anything Brosco receives as a Uri - whether
     * from the share sheet (handleIncomingIntent) or the in-app 📎 attach
     * button (attachLauncher) - dispatched by mime type: photo -> vision
     * model on the still image, video -> frame-sampled vision analysis,
     * anything else assumed to be a text/code file Shrey wants fixed.
     */
    private fun handleAttachedContent(uri: android.net.Uri, mimeType: String, question: String = "") {
        when {
            mimeType.startsWith("video/") -> {
                addUserMessage(
                    if (question.isNotBlank()) "[shared a video] $question" else "[shared a video] What's in this?"
                )
                galaxyBackground.setActive(true)
                safely {
                    CommandProcessor.analyzeVideo(
                        context = this,
                        uri = uri,
                        question = question,
                        scope = CoroutineScope(Dispatchers.Main)
                    ) { response -> runOnUiThread { safely { stopPulse(); galaxyBackground.setActive(alwaysListening); speak(response) } } }
                }
            }
            mimeType.startsWith("image/") -> {
                addUserMessage(
                    if (question.isNotBlank()) "[shared a photo] $question" else "[shared a photo] What's this?"
                )
                galaxyBackground.setActive(true)
                safely {
                    CommandProcessor.analyzeImage(
                        context = this,
                        uri = uri,
                        question = question,
                        scope = CoroutineScope(Dispatchers.Main)
                    ) { response -> runOnUiThread { safely { stopPulse(); galaxyBackground.setActive(alwaysListening); speak(response) } } }
                }
            }
            else -> {
                // Anything that isn't obviously a photo or video - treat as
                // a text/code file to fix. Reading it and hitting the API
                // key-off require IO, so this hops onto a background
                // dispatcher rather than blocking the UI thread here.
                //
                // Default is now the IMMEDIATE fix ("fix this file") -
                // this used to always defer to the once-a-night WorkManager
                // path regardless of what was actually asked, which is why
                // saying "fix this file" felt like nothing happened for a
                // full minute. Only an explicit "overnight" in the shared
                // text still takes the old slower, WorkManager-backed path.
                val fileName = queryDisplayName(uri) ?: "shared_file.txt"
                val wantsOvernight = question.contains("overnight", ignoreCase = true)
                addUserMessage(
                    if (question.isNotBlank()) "[shared $fileName] $question"
                    else "[shared $fileName] fix this file"
                )
                CoroutineScope(Dispatchers.Main).launch {
                    val content = withContext(Dispatchers.IO) { readTextFromUri(uri) }
                    safely {
                        if (wantsOvernight) {
                            CommandProcessor.queueFileFix(this@MainActivity, fileName, content) { response ->
                                runOnUiThread { safely { speak(response) } }
                            }
                        } else {
                            CommandProcessor.fixFileNow(
                                context = this@MainActivity,
                                fileName = fileName,
                                content = content,
                                scope = CoroutineScope(Dispatchers.Main)
                            ) { response ->
                                runOnUiThread { safely { speak(response) } }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Best-effort file name for a content Uri, via the standard OpenableColumns query. */
    private fun queryDisplayName(uri: android.net.Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // Cap how much of a shared file gets read/sent to the model - a file
    // fix job isn't meant for entire large codebases, and this keeps one
    // oversized share from blowing the request past the model's limits.
    private val MAX_FILE_FIX_BYTES = 150_000

    /** Reads a shared/attached Uri as UTF-8 text, truncated to a sane size. Call off the main thread. */
    private fun readTextFromUri(uri: android.net.Uri): String {
        return try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                String(bytes.take(MAX_FILE_FIX_BYTES).toByteArray(), Charsets.UTF_8)
            } ?: ""
        } catch (e: Exception) {
            Log.w("Brosco", "Couldn't read shared file: ${e.message}")
            ""
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
                    safely {
                        // Once a song/video is actually playing, stop
                        // always-listen so it doesn't keep grabbing audio focus
                        // every recognition cycle and cutting the playback
                        // right back off.
                        if (alwaysListening) stopAlwaysListening()
                    }
                }
            }
        ) { response ->
            runOnUiThread {
                safely {
                    stopPulse()
                    galaxyBackground.setActive(alwaysListening)
                    statusText.text = if (alwaysListening) "Always listening..." else "Brosco"
                    speak(response)
                }
            }
        }
    }

    private fun startAlwaysListening() {
        ensurePermissions()
        alwaysListening = true
        alwaysListenButton.text = "STOP ALWAYS-LISTEN"
        statusText.text = "Always listening..."
        startPulse()
        galaxyBackground.setActive(true)

        continuousRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        continuousRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                safely {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val heard = matches?.get(0)?.trim()
                    if (!heard.isNullOrEmpty()) handleWakeAndCommand(heard)
                    if (alwaysListening) restartContinuousListening()
                }
            }
            override fun onError(error: Int) {
                safely { if (alwaysListening) restartContinuousListening() }
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
            safely { if (alwaysListening) continuousRecognizer?.startListening(intent) }
        }, 300)
    }

    private fun stopAlwaysListening() {
        alwaysListening = false
        alwaysListenButton.text = "ALWAYS-LISTEN"
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
            addUserMessage(heard)
            runCommand(heard)
            return
        }
        if (lower.contains("brosco")) {
            val afterWake = lower.substringAfter("brosco").trim()
            if (afterWake.isNotEmpty()) {
                addUserMessage(heard)
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
                Color.parseColor("#7F5AF0"),
                Color.parseColor("#2CB1FF"),
                Color.parseColor("#00E5C7")
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
        addAssistantMessage(text)
        // Strip the handful of markdown characters the model might still
        // slip in, purely so TTS doesn't read out literal asterisks/hashes -
        // the chat bubble above still shows the full original text.
        val ttsText = text.replace(Regex("[*_#`]"), "")
        tts.speak(ttsText, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    // ------------------------------------------------------------------
    // Claude-style chat transcript
    // ------------------------------------------------------------------
    private fun addUserMessage(text: String) {
        hideEmptyState()
        chatContainer.addView(buildBubbleRow(text, isUser = true))
        scrollToBottom()
    }

    private fun addAssistantMessage(text: String) {
        hideEmptyState()
        chatContainer.addView(buildBubbleRow(text, isUser = false))
        scrollToBottom()
    }

    private fun hideEmptyState() {
        if (emptyStateText.visibility != View.GONE) emptyStateText.visibility = View.GONE
    }

    private fun buildBubbleRow(text: String, isUser: Boolean): LinearLayout {
        val bubble = TextView(this).apply {
            this.text = text
            setTextColor(if (isUser) Color.WHITE else Color.parseColor("#E8E5F7"))
            textSize = 15.5f
            setBackgroundResource(if (isUser) R.drawable.bubble_user else R.drawable.bubble_assistant)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.86f)
        }

        val spacer = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 0.14f)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(5)
                bottomMargin = dp(5)
            }
            gravity = if (isUser) Gravity.END else Gravity.START
            if (isUser) {
                addView(spacer)
                addView(bubble)
            } else {
                addView(bubble)
                addView(spacer)
            }
        }
    }

    private fun scrollToBottom() {
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(value: Int): Int {
        val density = resources.displayMetrics.density
        return (value * density).toInt()
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
        safely {
            if (requestCode == SPEECH_REQUEST_CODE && data != null) {
                val results = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val heard = results?.get(0) ?: run {
                    statusText.text = "Brosco"
                    if (!alwaysListening) galaxyBackground.setActive(false)
                    return@safely
                }
                addUserMessage(heard)
                startPulse()
                runCommand(heard)
            } else {
                statusText.text = "Brosco"
                if (!alwaysListening) galaxyBackground.setActive(false)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        continuousRecognizer?.destroy()
    }
}
