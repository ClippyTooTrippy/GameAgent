package com.joel.gameagent.vision

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

/**
 * Handles the inline "Instruct" reply typed directly from the persistent
 * notification - no need to open the app at all. This is the practical
 * alternative to the in-app instruction box: pull down the shade, tap
 * Instruct, type, send, done.
 */
class InstructionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_SEND_INSTRUCTION = "com.joel.gameagent.SEND_INSTRUCTION"
        const val KEY_INSTRUCTION_REPLY = "instruction_reply"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SEND_INSTRUCTION) return

        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_INSTRUCTION_REPLY)
            ?.toString()
            ?.trim()
            ?: return

        if (text.isEmpty()) return

        VisionCaptureService.currentInstruction = text

        // Update the notification to confirm it landed, since the reply
        // UI otherwise just closes with no feedback that it worked.
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val confirmation = NotificationCompat.Builder(context, VisionCaptureService.NOTIF_CHANNEL)
            .setContentTitle("GameAgent is watching and playing")
            .setContentText("Got it: \"$text\"")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(VisionCaptureService.buildInstructAction(context))
            .build()
        manager.notify(VisionCaptureService.NOTIF_ID, confirmation)
    }
}
