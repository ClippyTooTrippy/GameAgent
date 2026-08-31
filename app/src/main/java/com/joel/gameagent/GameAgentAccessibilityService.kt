package com.joel.gameagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * This is now just the "hands" of the agent: it dispatches taps/swipes
 * (only an AccessibilityService can inject touch input without root) and
 * tracks which app is currently in the foreground. All the "seeing" and
 * "deciding" moved to VisionCaptureService, which works on any app -
 * native UI, game engines, video - because it reads screen pixels instead
 * of the accessibility tree.
 */
class GameAgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "GameAgent"
        @Volatile var instance: GameAgentAccessibilityService? = null
        /** Updated on every window-state-changed event. */
        @Volatile var currentForegroundPackage: String? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Hands service connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { currentForegroundPackage = it }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Service interrupted")
    }

    fun performTap(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        dispatchGesture(gesture, null, null)
    }

    fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int) {
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
