package com.joel.gameagent.vision

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.joel.gameagent.GameAgentAccessibilityService
import com.joel.gameagent.brain.DecisionEngine
import com.joel.gameagent.brain.GeminiNanoBrain
import com.joel.gameagent.brain.HeuristicFallbackBrain
import com.joel.gameagent.memory.MemoryStore
import com.joel.gameagent.model.GameAction
import com.joel.gameagent.model.ScreenElement
import com.joel.gameagent.model.ScreenState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The "eyes" of the agent. Instead of asking Android "what UI elements
 * exist" (which only works for native apps), this grabs the actual
 * pixels on screen - works identically for a settings menu, a Unity
 * game, or a live video stream, since a screenshot doesn't care what
 * drew it.
 */
class VisionCaptureService : Service() {

    companion object {
        private const val TAG = "VisionCapture"
        const val NOTIF_CHANNEL = "gameagent_vision"
        const val NOTIF_ID = 42
        private const val CAPTURE_INTERVAL_MS = 900L
        private const val GRID_COLS = 8
        private const val GRID_ROWS = 14
        /** How much of the screen top/bottom to treat as "system UI, never tap here". */
        private const val SAFE_MARGIN_FRACTION = 0.06

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_EXCLUDED_PACKAGES = "excluded_packages"

        @Volatile var isRunning: Boolean = false

        /**
         * Live-editable instruction from the user, e.g. "collect coins,
         * avoid the shop". Read fresh every frame - MainActivity writes
         * to this directly (same process), no binding needed.
         */
        @Volatile var currentInstruction: String = ""

        /**
         * Rolling log of what the agent just did and why, newest first.
         * MainActivity polls this to show a live "thought process" feed.
         */
        private const val LOG_CAPACITY = 60
        private val _thoughtLog = ArrayDeque<String>()
        val thoughtLog: List<String>
            get() = synchronized(_thoughtLog) { _thoughtLog.toList() }

        private fun logThought(message: String) {
            synchronized(_thoughtLog) {
                _thoughtLog.addFirst(message)
                while (_thoughtLog.size > LOG_CAPACITY) _thoughtLog.removeLast()
            }
            Log.i(TAG, message)
        }

        /**
         * Builds the "Instruct" notification action with an inline reply
         * field - lets the user type an instruction straight from the
         * notification shade without opening the app at all.
         */
        fun buildInstructAction(context: android.content.Context): NotificationCompat.Action {
            val remoteInput = androidx.core.app.RemoteInput.Builder(InstructionReceiver.KEY_INSTRUCTION_REPLY)
                .setLabel("e.g. collect coins, avoid the shop")
                .build()

            val intent = Intent(context, InstructionReceiver::class.java).apply {
                action = InstructionReceiver.ACTION_SEND_INSTRUCTION
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context, 0, intent,
                android.app.PendingIntent.FLAG_MUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )

            return NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_edit, "Instruct", pendingIntent
            ).addRemoteInput(remoteInput).build()
        }
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private lateinit var memory: MemoryStore
    private lateinit var brain: DecisionEngine
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var tts: TextToSpeech? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var excludedPackages: Set<String> = emptySet()
    private var screenHeightPx = 0

    private var lastActionKey: String? = null
    private var lastScreenHash: String? = null
    private var lastMetric: Long = 0L
    private var actionsSinceLastSpeech = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.UK
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        excludedPackages = intent?.getStringExtra(EXTRA_EXCLUDED_PACKAGES)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()

        if (resultData == null) {
            Log.e(TAG, "Missing projection permission - stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIF_ID, buildNotification())
        memory = MemoryStore(applicationContext)
        val fallback = HeuristicFallbackBrain(memory)
        brain = GeminiNanoBrain(applicationContext, memory, fallback)

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                logThought("Screen sharing was stopped - shutting down.")
                isRunning = false
                stopSelf()
            }
        }, android.os.Handler(mainLooper))
        setUpVirtualDisplay()

        isRunning = true
        logThought("Started. Excluding ${excludedPackages.size} app(s).")
        speak("Ready. I'm watching now.")
        scope.launch { captureLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        tts?.shutdown()
    }

    private fun setUpVirtualDisplay() {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenHeightPx = metrics.heightPixels

        imageReader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "GameAgentCapture",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private suspend fun captureLoop() {
        while (isRunning) {
            try {
                actOnce()
            } catch (e: Exception) {
                Log.w(TAG, "Frame skipped", e)
            }
            kotlinx.coroutines.delay(CAPTURE_INTERVAL_MS)
        }
    }

    private suspend fun actOnce() {
        val foreground = GameAgentAccessibilityService.currentForegroundPackage

        // Never, ever act on ourselves. Without this, sitting on
        // GameAgent's own screen typing an instruction turns into the
        // agent tapping its own text fields and keyboard, corrupting
        // whatever you just typed - this bit us once, never again.
        if (foreground == packageName) return

        if (foreground != null && foreground in excludedPackages) {
            logThought("$foreground is excluded - going home, not looking at it.")
            perform(GameAction.GoHome)
            return
        }

        val bitmap = captureFrame() ?: return
        val state = buildScreenState(bitmap, foreground.orEmpty())

        findAdCloseElement(state)?.let { closeButton ->
            logThought("Looks like an ad/popup - closing it.")
            perform(GameAction.Tap(closeButton))
            return
        }

        if (lastActionKey != null && lastScreenHash != null) {
            val rawReward = (state.primaryMetric() - lastMetric).toDouble()
            // Clamp - a jackpot/bonus coin spike (thousands at once)
            // shouldn't outweigh normal small in-game progress by 100x
            // in the learned table. Ordinary score increments stay
            // exactly as they were; only extreme spikes get capped.
            val reward = rawReward.coerceIn(-200.0, 200.0)
            memory.recordOutcome(lastScreenHash!!, lastActionKey!!, reward)
        }

        val candidates = buildCandidateActions(state)

        // If the user typed/spoke an instruction, and any candidate's
        // visible text matches a word from it, strongly favour that
        // candidate instead of the normal learned/exploratory pick. This
        // is the "chat box" steering mechanism - simple keyword bias
        // rather than true language understanding, since this phone has
        // no Nano to reason about it properly.
        val instruction = currentInstruction.trim().lowercase()
        val stopwords = setOf(
            "the", "and", "for", "with", "until", "aim", "game", "open", "okay",
            "your", "that", "this", "avoiding", "counter", "bottom", "empty"
        )
        val instructed = if (instruction.isNotEmpty()) {
            val words = instruction.split(Regex("[,.]?\\s+"))
                .filter { it.length > 4 && it !in stopwords }
            if (words.isEmpty()) null else {
                candidates.filterIsInstance<GameAction.Tap>().firstOrNull { tap ->
                    val label = tap.element.text.lowercase()
                    label.length in 3..60 && words.any { w -> label.contains(w) }
                }
            }
        } else null

        val chosen = instructed ?: brain.choose(state, candidates)

        val reasoning = when {
            instructed != null -> "instruction match ('$instruction')"
            chosen is GameAction.Tap && chosen.element.className == "ocr_text" -> "read text \"${chosen.element.text.take(30)}\""
            else -> "learned/explored"
        }
        logThought("[${state.packageName}] ${chosen.describe()} - $reasoning")

        actionsSinceLastSpeech++
        if (actionsSinceLastSpeech >= 8) {
            actionsSinceLastSpeech = 0
            speak(shortSpokenSummary(chosen, state.packageName))
        }

        lastActionKey = chosen.describe()
        lastScreenHash = state.layoutHash()
        lastMetric = state.primaryMetric()

        perform(chosen)
    }

    private fun shortSpokenSummary(action: GameAction, pkg: String): String {
        val appName = pkg.substringAfterLast(".").ifBlank { "the app" }
        return when (action) {
            is GameAction.Tap -> "Still exploring $appName."
            GameAction.GoBack -> "Backing out of a screen."
            GameAction.GoHome -> "Heading home."
            is GameAction.LaunchApp -> "Opening ${action.packageName.substringAfterLast(".")}."
            else -> "Working on it."
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, null)
    }

    private suspend fun captureFrame(): Bitmap? = suspendCoroutine { cont ->
        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            cont.resume(null)
            return@suspendCoroutine
        }
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * image.width

            val bitmap = Bitmap.createBitmap(
                image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            cont.resume(bitmap)
        } finally {
            image.close()
        }
    }

    /** True if a y-coordinate falls in the reserved status-bar / nav-bar strip - never tap there. */
    private fun isInSystemUiZone(y: Int): Boolean {
        if (screenHeightPx == 0) return false
        val margin = (screenHeightPx * SAFE_MARGIN_FRACTION).toInt()
        return y < margin || y > screenHeightPx - margin
    }

    private suspend fun buildScreenState(bitmap: Bitmap, foregroundPackage: String): ScreenState {
        val elements = mutableListOf<ScreenElement>()
        val numbers = mutableListOf<Long>()

        try {
            val input = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(input).await()
            for (block in result.textBlocks) {
                val box = block.boundingBox ?: continue
                if (isInSystemUiZone(box.centerY())) continue
                val text = block.text
                Regex("\\d+").findAll(text).forEach { m -> m.value.toLongOrNull()?.let(numbers::add) }
                elements += ScreenElement(
                    text = text,
                    contentDescription = "",
                    className = "ocr_text",
                    centerX = box.centerX(),
                    centerY = box.centerY(),
                    clickable = true
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "OCR failed on this frame", e)
        }

        val cellW = bitmap.width / GRID_COLS
        val cellH = bitmap.height / GRID_ROWS
        for (row in 0 until GRID_ROWS) {
            for (col in 0 until GRID_COLS) {
                val cy = row * cellH + cellH / 2
                if (isInSystemUiZone(cy)) continue
                elements += ScreenElement(
                    text = "",
                    contentDescription = "",
                    className = "grid_${row}_${col}",
                    centerX = col * cellW + cellW / 2,
                    centerY = cy,
                    clickable = true
                )
            }
        }

        return ScreenState(
            packageName = foregroundPackage,
            elements = elements,
            numbersOnScreen = numbers
        )
    }

    private val adCloseSignals = listOf(
        "skip ad", "skip", "close ad", "close", "no thanks", "x", "dismiss"
    )

    /**
     * Phrases that mean "tapping this leads into a rewarded ad." Left
     * unfiltered, the agent will happily chase these forever - a jump
     * from a few hundred coins to +10,000 for watching an ad is by far
     * the best "reward" available on any screen, so a reward signal
     * based on "did the number go up" will train it straight into an ad
     * habit. These are excluded from candidates entirely - not treated
     * as things to close (they're not popups blocking the game), just
     * never offered as a choice.
     */
    private val adBaitSignals = listOf(
        "claim now", "watch ad", "watch video", "free coins", "free gems",
        "bonus coins", "double your", "x2 coins", "continue?", "get free"
    )

    private fun findAdCloseElement(state: ScreenState): ScreenElement? {
        return state.elements.firstOrNull { el ->
            val label = el.text.trim().lowercase()
            label.isNotEmpty() && adCloseSignals.any { it == label || label.endsWith(" $it") }
        }
    }

    private fun isAdBait(el: ScreenElement): Boolean {
        val label = el.text.trim().lowercase()
        return label.isNotEmpty() && adBaitSignals.any { label.contains(it) }
    }

    private fun buildCandidateActions(state: ScreenState): List<GameAction> {
        val baitElements = state.elements.filter { it.className == "ocr_text" && isAdBait(it) }
        val ocrTaps = state.elements
            .filter { it.className == "ocr_text" && !isAdBait(it) }
            .map { GameAction.Tap(it) }

        // Grid taps are coordinate-based, so filtering bait out of OCR
        // isn't enough on its own - a random grid cell could still land
        // right on the "Claim Now" button by chance. Drop any grid cell
        // that falls near a bait element's position too.
        val baitRadius = 140
        val gridTaps = state.elements
            .filter { it.className.startsWith("grid_") }
            .filterNot { cell ->
                baitElements.any { bait ->
                    val dx = cell.centerX - bait.centerX
                    val dy = cell.centerY - bait.centerY
                    dx * dx + dy * dy < baitRadius * baitRadius
                }
            }
            .shuffled().take(20).map { GameAction.Tap(it) }

        val swipe = GameAction.Swipe(fromX = 540, fromY = 1600, toX = 540, toY = 800)
        return ocrTaps + gridTaps + swipe + GameAction.GoBack
    }

    private fun perform(action: GameAction) {
        val hands = GameAgentAccessibilityService.instance ?: run {
            Log.w(TAG, "Accessibility service not enabled - can't act")
            return
        }
        when (action) {
            is GameAction.Tap -> hands.performTap(action.element.centerX, action.element.centerY)
            is GameAction.Swipe -> hands.performSwipe(action.fromX, action.fromY, action.toX, action.toY)
            GameAction.GoBack -> hands.goBack()
            GameAction.GoHome -> hands.goHome()
            is GameAction.LaunchApp -> hands.launchApp(action.packageName)
            GameAction.WaitAndRecheck -> { /* no-op */ }
        }
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIF_CHANNEL, "GameAgent vision mode", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setContentTitle("GameAgent is watching and playing")
            .setContentText(
                if (excludedPackages.isEmpty()) "free-roaming the phone"
                else "roaming, excluding ${excludedPackages.size} app(s)"
            )
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(buildInstructAction(this))
            .build()
    }
}
