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
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.joel.gameagent.GameAgentAccessibilityService
import com.joel.gameagent.MainActivity
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
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The "eyes" of the agent. Instead of asking Android "what UI elements
 * exist" (which only works for native apps), this grabs the actual
 * pixels on screen - works identically for a settings menu, a Unity
 * game, or a live video stream, since a screenshot doesn't care what
 * drew it.
 *
 * Two kinds of candidate actions come out of every frame:
 *  - OCR text blocks (read any visible text and where it is)
 *  - a grid of generic regions covering the whole screen, so it can
 *    still learn to tap things with NO text at all (icons, sprites,
 *    a specific card, a specific pixel button) - it just won't know
 *    what that region "means", only that tapping it worked before.
 */
class VisionCaptureService : Service() {

    companion object {
        private const val TAG = "VisionCapture"
        private const val NOTIF_CHANNEL = "gameagent_vision"
        private const val NOTIF_ID = 42
        private const val CAPTURE_INTERVAL_MS = 900L
        private const val GRID_COLS = 8
        private const val GRID_ROWS = 14

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        /** Comma-separated package names the agent must never act inside. */
        const val EXTRA_EXCLUDED_PACKAGES = "excluded_packages"

        @Volatile var isRunning: Boolean = false
    }

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private lateinit var memory: MemoryStore
    private lateinit var brain: DecisionEngine
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    /** Packages the agent must never tap inside - checked before anything else, every frame. */
    private var excludedPackages: Set<String> = emptySet()

    private var lastActionKey: String? = null
    private var lastScreenHash: String? = null
    private var lastMetric: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

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
        // Required since Android 14 - createVirtualDisplay() throws if no
        // callback is registered first. onStop() fires if the system
        // revokes the projection (e.g. user stops it from the
        // notification/quick-settings), so we clean up and stop the
        // service properly instead of crashing on the next capture.
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped by system - shutting down")
                isRunning = false
                stopSelf()
            }
        }, android.os.Handler(mainLooper))
        setUpVirtualDisplay()

        isRunning = true
        scope.launch { captureLoop() }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
    }

    private fun setUpVirtualDisplay() {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

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

        // Hard safety boundary: if we've landed in an excluded app
        // (banking, messaging, whatever's on the blocklist), don't look
        // at it, don't tap in it, don't even OCR it - just leave
        // immediately. This runs before any capture/OCR/decision work,
        // so an excluded app never gets touched in any way.
        if (foreground != null && foreground in excludedPackages) {
            perform(GameAction.GoHome)
            return
        }

        val bitmap = captureFrame() ?: return
        val state = buildScreenState(bitmap, foreground.orEmpty())

        // Ad-close check takes priority over everything else - same
        // idea as before, just matched against OCR'd text now instead
        // of accessibility node text, so it works on ads drawn as pure
        // video/canvas content too.
        findAdCloseElement(state)?.let { closeButton ->
            perform(GameAction.Tap(closeButton))
            return
        }

        if (lastActionKey != null && lastScreenHash != null) {
            val reward = (state.primaryMetric() - lastMetric).toDouble()
            memory.recordOutcome(lastScreenHash!!, lastActionKey!!, reward)
        }

        val candidates = buildCandidateActions(state)
        val chosen = brain.choose(state, candidates)

        lastActionKey = chosen.describe()
        lastScreenHash = state.layoutHash()
        lastMetric = state.primaryMetric()

        perform(chosen)
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

    /** Builds a ScreenState out of OCR'd text blocks plus a fallback grid of generic regions. */
    private suspend fun buildScreenState(bitmap: Bitmap, foregroundPackage: String): ScreenState {
        val elements = mutableListOf<ScreenElement>()
        val numbers = mutableListOf<Long>()

        try {
            val input = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(input).await()
            for (block in result.textBlocks) {
                val box = block.boundingBox ?: continue
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

        // Generic grid regions - these are the "learn to tap ANYTHING"
        // fallback. Every region is always offered as a candidate action
        // regardless of what OCR found, so icons/sprites with no text
        // can still be learned through trial and error.
        val cellW = bitmap.width / GRID_COLS
        val cellH = bitmap.height / GRID_ROWS
        for (row in 0 until GRID_ROWS) {
            for (col in 0 until GRID_COLS) {
                elements += ScreenElement(
                    text = "",
                    contentDescription = "",
                    className = "grid_${row}_${col}",
                    centerX = col * cellW + cellW / 2,
                    centerY = row * cellH + cellH / 2,
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

    private fun findAdCloseElement(state: ScreenState): ScreenElement? {
        return state.elements.firstOrNull { el ->
            val label = el.text.trim().lowercase()
            label.isNotEmpty() && adCloseSignals.any { it == label || label.endsWith(" $it") }
        }
    }

    private fun buildCandidateActions(state: ScreenState): List<GameAction> {
        // Cap how many we hand to the brain each frame - with a full
        // grid + OCR blocks this can be over 100 regions, which is more
        // than enough noise per frame. Bias toward OCR text blocks
        // (usually more meaningful) plus a random sample of grid cells,
        // so exploration still eventually covers the whole screen.
        val ocrTaps = state.elements.filter { it.className == "ocr_text" }.map { GameAction.Tap(it) }
        val gridTaps = state.elements.filter { it.className.startsWith("grid_") }
            .shuffled().take(20).map { GameAction.Tap(it) }
        val swipe = GameAction.Swipe(fromX = 540, fromY = 1600, toX = 540, toY = 800)
        // Back is always offered as a real candidate (not just forced
        // recovery) - some games use it deliberately (closing a menu,
        // dismissing a dialog), so it should be something the agent can
        // learn is sometimes the right move, not only an emergency escape.
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
            .build()
    }
}
