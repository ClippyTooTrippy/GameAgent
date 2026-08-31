package com.joel.gameagent.model

import java.security.MessageDigest

/**
 * One clickable/interesting thing the agent found on screen right now.
 * Built from the AccessibilityNodeInfo tree - text, content description,
 * class name, and screen position of every tappable node.
 */
data class ScreenElement(
    val text: String,
    val contentDescription: String,
    val className: String,
    val centerX: Int,
    val centerY: Int,
    val clickable: Boolean
)

/**
 * A snapshot of what's on screen: the package that's foreground, every
 * element the agent could act on, and any numbers it managed to read off
 * the screen (coin counts, scores, etc.) which double as the reward
 * signal for learning.
 */
data class ScreenState(
    val packageName: String,
    val elements: List<ScreenElement>,
    val numbersOnScreen: List<Long>
) {
    /**
     * A short, stable fingerprint for this screen layout. Used as the key
     * for the learned action-value table - two screenshots of "the same
     * screen" (e.g. the main board in a game) should hash the same way
     * even if a coin counter ticked up, so we hash on layout shape
     * (classNames + rough positions), not exact text/numbers.
     */
    fun layoutHash(): String {
        val shape = elements.joinToString("|") {
            "${it.className}:${it.centerX / 40}:${it.centerY / 40}"
        }
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$packageName::$shape".toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(16)
    }

    /** Best-effort "how well are things going" number, e.g. highest coin/score value seen. */
    fun primaryMetric(): Long = numbersOnScreen.maxOrNull() ?: 0L
}

/** An action the agent can take: tap a specific element, or swipe in a direction. */
sealed class GameAction {
    data class Tap(val element: ScreenElement) : GameAction()
    data class Swipe(val fromX: Int, val fromY: Int, val toX: Int, val toY: Int) : GameAction()
    object WaitAndRecheck : GameAction()

    fun describe(): String = when (this) {
        is Tap -> "tap(${element.text.ifBlank { element.contentDescription }.ifBlank { element.className }})"
        is Swipe -> "swipe($fromX,$fromY -> $toX,$toY)"
        WaitAndRecheck -> "wait"
    }
}
