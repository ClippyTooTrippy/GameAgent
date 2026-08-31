package com.joel.gameagent.brain

import android.content.Context
import android.util.Log
import com.joel.gameagent.memory.MemoryStore
import com.joel.gameagent.model.GameAction
import com.joel.gameagent.model.ScreenState

/**
 * Uses on-device Gemini Nano (via AICore / ML Kit GenAI) to pick the most
 * sensible action, then blends that with what the HeuristicFallbackBrain
 * has learned so far. Nano supplies "common sense" about what a button
 * probably does; the memory table supplies "what actually worked before
 * on this exact screen". Neither alone is as good as both together.
 *
 * IMPORTANT: the on-device GenAI API surface is new and still moving as
 * of mid-2026, and it's only available on a handful of devices (Pixel 9/10,
 * Galaxy S24+ with the right OS build). The exact class/package below
 * (com.google.mlkit.genai...) may have changed by the time you build this -
 * check https://developer.android.com/ai/aicore for the current API and
 * swap out the body of `askNano()`. Everything else in the app is written
 * against the DecisionEngine interface and will keep working either way -
 * if Nano isn't available on your phone, catch the exception below and
 * MainActivity will silently use HeuristicFallbackBrain instead.
 */
class GeminiNanoBrain(
    private val context: Context,
    private val memory: MemoryStore,
    private val fallback: HeuristicFallbackBrain
) : DecisionEngine {

    override suspend fun choose(state: ScreenState, candidates: List<GameAction>): GameAction {
        if (candidates.isEmpty()) return GameAction.WaitAndRecheck

        val nanoPick = try {
            askNano(state, candidates)
        } catch (e: Exception) {
            Log.w("GeminiNanoBrain", "Nano unavailable, falling back", e)
            null
        } ?: return fallback.choose(state, candidates)

        // If memory has a strongly negative signal for Nano's pick (we've
        // tried it before here and it clearly made things worse), defer to
        // the learned table instead of repeating a known mistake.
        val hash = state.layoutHash()
        val learnedForPick = memory.valuesForScreen(hash)
            .find { it.actionKey == nanoPick.describe() }

        return if (learnedForPick != null && learnedForPick.timesTried >= 3 && learnedForPick.averageReward < 0) {
            fallback.choose(state, candidates)
        } else {
            nanoPick
        }
    }

    /**
     * Builds a short natural-language description of the screen and asks
     * Nano to pick which candidate action makes most sense to try next.
     * This is the part most likely to need updating against the current
     * ML Kit GenAI Prompt API - see class doc above.
     */
    private suspend fun askNano(state: ScreenState, candidates: List<GameAction>): GameAction? {
        val prompt = buildString {
            appendLine("You are playing a mobile app (${state.packageName}).")
            appendLine("Here are the numbered actions you could take right now:")
            candidates.forEachIndexed { i, action -> appendLine("$i: ${action.describe()}") }
            appendLine("Reply with ONLY the number of the action most likely to progress the game or claim a reward.")
        }

        // TODO: replace with the current ML Kit GenAI / AICore Prompt API call, e.g.:
        //   val session = GenerativeModel.getInstance(context)
        //   val response = session.generateContent(prompt)
        //   val index = response.text.trim().toIntOrNull()
        // Left unimplemented here since the API is still in flux - this
        // is the one function you need to fill in once you've checked
        // the current docs.
        val index: Int? = null

        return index?.let { candidates.getOrNull(it) }
    }
}
