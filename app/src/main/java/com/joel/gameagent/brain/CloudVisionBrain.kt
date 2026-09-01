package com.joel.gameagent.brain

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.joel.gameagent.model.GameAction
import com.joel.gameagent.model.ScreenState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Sends the actual screenshot to a cloud vision-language model and asks
 * it to pick the sensible action with a one-line reason. This is what
 * makes the agent genuinely reason about a screen - what the text means,
 * what an icon probably does, what's obviously an ad vs the real game -
 * instead of blindly averaging past rewards over a grid.
 *
 * Needs a free Gemini API key from https://aistudio.google.com/apikey
 * (paste it in the app - Settings section). No key, no network, or any
 * error and this just returns null - the caller falls back to
 * HeuristicFallbackBrain automatically, so nothing breaks if you never
 * set a key. Without a key the app behaves exactly as it did before:
 * fully local, fully private, dumber.
 *
 * PRIVACY NOTE: when a key IS set, screenshots leave the device and go
 * to Google's API to get analyzed. That's the tradeoff for real
 * reasoning. Excluded apps are still never captured at all (checked
 * before this class is ever called), so this doesn't change what's
 * protected - it only changes how the non-excluded screens get decided.
 *
 * NOTE: the endpoint/model name below is Gemini's REST API shape as of
 * when this was written. If Google has changed it, check
 * https://ai.google.dev/api and update MODEL_ENDPOINT - nothing else in
 * the app needs to change, since everything else talks to this class
 * through the choose() function only.
 */
class CloudVisionBrain(private val apiKey: String) {

    companion object {
        private const val TAG = "CloudVisionBrain"
        private const val MODEL_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent"
        private const val MAX_DIMENSION = 768
    }

    data class Decision(val index: Int, val reason: String)

    /**
     * Set when the API returns 429. Until this timestamp passes there is
     * no point calling at all - the caller checks it and stays on the
     * local brain instead.
     */
    @Volatile
    var backoffUntilMs: Long = 0L
        private set

    suspend fun choose(
        bitmap: Bitmap,
        state: ScreenState,
        candidates: List<GameAction>,
        instruction: String
    ): Decision? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || candidates.isEmpty()) return@withContext null

        try {
            val resized = downscale(bitmap, MAX_DIMENSION)
            val imageB64 = bitmapToBase64Jpeg(resized)
            val prompt = buildPrompt(state, candidates, instruction)
            val requestBody = buildRequestJson(prompt, imageB64)

            val url = URL("$MODEL_ENDPOINT?key=$apiKey")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = TimeUnit.SECONDS.toMillis(10).toInt()
                readTimeout = TimeUnit.SECONDS.toMillis(15).toInt()
            }
            OutputStreamWriter(conn.outputStream).use { it.write(requestBody.toString()) }

            if (conn.responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText()
                // 429 means we've spent the minute's quota. Google tells
                // us exactly how long to wait in retryDelay - honour it
                // rather than hammering the endpoint, since every extra
                // call while throttled just burns battery for a
                // guaranteed error.
                if (conn.responseCode == 429) {
                    val wait = Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)s\"")
                        .find(err ?: "")?.groupValues?.get(1)?.toLongOrNull() ?: 30L
                    backoffUntilMs = System.currentTimeMillis() + (wait + 1) * 1000L
                    Log.w(TAG, "Rate limited - pausing cloud calls for ${wait}s")
                } else {
                    Log.w(TAG, "API error ${conn.responseCode}: $err")
                }
                return@withContext null
            }

            parseDecision(conn.inputStream.bufferedReader().readText(), candidates.size)
        } catch (e: Exception) {
            Log.w(TAG, "Cloud brain call failed, falling back", e)
            null
        }
    }

    private fun buildPrompt(state: ScreenState, candidates: List<GameAction>, instruction: String): String {
        return buildString {
            appendLine("You are controlling an Android phone by choosing ONE action per turn.")
            appendLine("Current app: ${state.packageName}")
            if (instruction.isNotBlank()) appendLine("User's instruction right now: $instruction")
            appendLine("A screenshot of the current screen is attached.")
            appendLine("Avoid anything that looks like an ad, a paid offer, or a store/rating page.")
            appendLine("Candidate actions - pick the index of the single best one:")
            candidates.forEachIndexed { i, a -> appendLine("$i: ${a.describe()}") }
            appendLine("Reply with ONLY compact JSON, nothing else: {\"choice\": <index>, \"reason\": \"<max 8 words>\"}")
        }
    }

    private fun buildRequestJson(prompt: String, imageB64: String): JSONObject {
        val textPart = JSONObject().put("text", prompt)
        val imagePart = JSONObject().put(
            "inline_data",
            JSONObject().put("mime_type", "image/jpeg").put("data", imageB64)
        )
        val content = JSONObject().put("parts", JSONArray().put(textPart).put(imagePart))
        return JSONObject().put("contents", JSONArray().put(content))
    }

    private fun parseDecision(responseJson: String, candidateCount: Int): Decision? {
        return try {
            val text = JSONObject(responseJson)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
            val cleaned = text.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            val parsed = JSONObject(cleaned)
            val idx = parsed.getInt("choice")
            val reason = parsed.optString("reason", "")
            if (idx in 0 until candidateCount) Decision(idx, reason) else null
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't parse model response: $responseJson", e)
            null
        }
    }

    private fun downscale(bitmap: Bitmap, maxDim: Int): Bitmap {
        val scale = maxDim.toFloat() / maxOf(bitmap.width, bitmap.height)
        if (scale >= 1f) return bitmap
        return Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true
        )
    }

    private fun bitmapToBase64Jpeg(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
        return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
    }
}
