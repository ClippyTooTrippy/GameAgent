package com.joel.gameagent.brain

import com.joel.gameagent.memory.MemoryStore
import com.joel.gameagent.model.GameAction
import com.joel.gameagent.model.ScreenState
import kotlin.random.Random

/**
 * Works on every device, no Nano required. Picks the action with the best
 * learned average reward for this exact screen layout most of the time,
 * but explores a random candidate ~15% of the time so it doesn't get
 * stuck always doing the first thing that ever worked. This alone will
 * "learn" in a real sense over enough playthroughs - Nano on top of this
 * just makes the exploration smarter than random.
 */
class HeuristicFallbackBrain(private val memory: MemoryStore) : DecisionEngine {

    private val explorationRate = 0.15

    override suspend fun choose(state: ScreenState, candidates: List<GameAction>): GameAction {
        if (candidates.isEmpty()) return GameAction.WaitAndRecheck
        if (Random.nextDouble() < explorationRate) return candidates.random()

        val hash = state.layoutHash()
        val learned = memory.valuesForScreen(hash).associateBy { it.actionKey }

        return candidates.maxByOrNull { learned[it.describe()]?.averageReward ?: 0.0 }
            ?: candidates.random()
    }
}
