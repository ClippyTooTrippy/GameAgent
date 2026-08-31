package com.joel.gameagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class GameAgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GameAgent"
        /** Set from MainActivity when the user hits Start. Empty = agent is idle. */
        @Volatile var targetPackage: String? = null
        @Volatile var isRunning: Boolean = false
    }

    private lateinit var memory: MemoryStore
    private lateinit var brain: DecisionEngine
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    private var lastActionKey: String? = null
    private var lastScreenHash: String? = null
    private var lastMetric: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        memory = MemoryStore(applicationContext)
        val fallback = HeuristicFallbackBrain(memory)
        brain = GeminiNanoBrain(applicationContext, memory, fallback)
        Log.i(TAG, "Service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isRunning) return
        val target = targetPackage ?: return
        if (event?.packageName?.toString() != target) return

        // Debounce: only act once the UI has settled, not on every
        // micro-event. A short delay then a fresh read of the tree.
        scope.launch {
            delay(400)
            actOnce()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    private val adCloseSignals = listOf(
        "skip ad", "skip", "close ad", "close", "no thanks", "x",
        "dismiss", "continue without watching"
    )

    private fun findAdCloseElement(state: ScreenState): ScreenElement? {
        return state.elements.firstOrNull { el ->
            val label = (el.text + " " + el.contentDescription).trim().lowercase()
            adCloseSignals.any { signal -> label == signal || label.endsWith(" $signal") }
        }
    }

    private suspend fun actOnce() {
        val root = rootInActiveWindow ?: return
        val state = readScreen(root)

        if (state.packageName.isNotEmpty() && state.packageName != targetPackage) {
            return
        }

        findAdCloseElement(state)?.let { closeButton ->
            perform(GameAction.Tap(closeButton))
            return
        }
        // Reward = change in the best number we can read off screen
        // (coins, score, etc.) since our last action. This is the whole
        // learning signal - crude, but it works for the "number goes up"
        // shape most of these games have.
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

    private fun readScreen(root: AccessibilityNodeInfo): ScreenState {
        val elements = mutableListOf<ScreenElement>()
        val numbers = mutableListOf<Long>()

        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()

            Regex("\\d+").findAll(text).forEach { m -> m.value.toLongOrNull()?.let(numbers::add) }

            if (node.isClickable && node.isVisibleToUser) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                elements += ScreenElement(
                    text = text,
                    contentDescription = desc,
                    className = node.className?.toString().orEmpty(),
                    centerX = bounds.centerX(),
                    centerY = bounds.centerY(),
                    clickable = true
                )
            }
            for (i in 0 until node.childCount) visit(node.getChild(i))
        }
        visit(root)

        return ScreenState(
            packageName = root.packageName?.toString().orEmpty(),
            elements = elements,
            numbersOnScreen = numbers
        )
    }

    private fun buildCandidateActions(state: ScreenState): List<GameAction> {
        val taps = state.elements.map { GameAction.Tap(it) }
        // A generic "swipe up" is always offered too, since some games
        // (spin wheels, card collection screens) need a swipe rather than
        // a tap and won't expose that as a clickable element.
        val swipe = GameAction.Swipe(fromX = 540, fromY = 1600, toX = 540, toY = 800)
        return taps + swipe
    }

    private fun perform(action: GameAction) {
        when (action) {
            is GameAction.Tap -> dispatchTap(action.element.centerX, action.element.centerY)
            is GameAction.Swipe -> dispatchSwipe(action.fromX, action.fromY, action.toX, action.toY)
            GameAction.WaitAndRecheck -> { /* no-op, next event will re-trigger */ }
        }
    }

    private fun dispatchTap(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun dispatchSwipe(x1: Int, y1: Int, x2: Int, y2: Int) {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 250))
            .build()
        dispatchGesture(gesture, null, null)
    }
}
