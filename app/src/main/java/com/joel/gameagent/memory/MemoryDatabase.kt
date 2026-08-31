package com.joel.gameagent.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ActionValue::class], version = 1, exportSchema = false)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun actionMemoryDao(): ActionMemoryDao

    companion object {
        @Volatile private var instance: MemoryDatabase? = null

        fun get(context: Context): MemoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    "gameagent-memory.db"
                ).build().also { instance = it }
            }
    }
}

/**
 * Thin wrapper the rest of the app talks to. Implements a running-average
 * update, which is the same idea as Q-learning's update rule but without
 * needing a discount factor since we're treating each action as a
 * one-step bandit choice conditioned on the current screen.
 */
class MemoryStore(context: Context) {
    private val dao = MemoryDatabase.get(context).actionMemoryDao()

    suspend fun valuesForScreen(screenHash: String) = dao.forScreen(screenHash)

    suspend fun recordOutcome(screenHash: String, actionKey: String, reward: Double) {
        val existing = dao.forScreen(screenHash).find { it.actionKey == actionKey }
        val updated = if (existing == null) {
            ActionValue(screenHash, actionKey, reward, 1, System.currentTimeMillis())
        } else {
            val n = existing.timesTried + 1
            // running average: new average moves toward the latest reward,
            // weighted so early experience isn't drowned out instantly
            // but repeated outcomes do shift the estimate.
            val newAvg = existing.averageReward + (reward - existing.averageReward) / n
            existing.copy(averageReward = newAvg, timesTried = n, lastTriedAt = System.currentTimeMillis())
        }
        dao.upsert(updated)
    }

    suspend fun learnedEntryCount(): Int = dao.totalLearnedEntries()
}
