package com.joel.gameagent.brain

import com.joel.gameagent.model.GameAction
import com.joel.gameagent.model.ScreenState

/**
 * Anything that can look at a ScreenState and a list of candidate actions
 * and pick one. The rest of the app doesn't care whether the pick came
 * from Gemini Nano, learned memory, or a coin flip - which is what lets
 * us fall back gracefully on devices that don't support Nano.
 */
interface DecisionEngine {
    suspend fun choose(state: ScreenState, candidates: List<GameAction>): GameAction
}
