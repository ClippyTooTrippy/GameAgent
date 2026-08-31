package com.joel.gameagent.memory

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * One learned fact: "in this screen layout, taking this action tended to
 * produce this much reward". This IS the learning - it's a tabular
 * action-value estimate (like a simple Q-table), updated after every
 * action with a running average. No screen state + action pair is
 * pre-programmed; it's all filled in through play.
 */
@Entity(tableName = "action_values", primaryKeys = ["screenHash", "actionKey"])
data class ActionValue(
    val screenHash: String,
    val actionKey: String,
    val averageReward: Double,
    val timesTried: Int,
    val lastTriedAt: Long
)

@Dao
interface ActionMemoryDao {
    @Query("SELECT * FROM action_values WHERE screenHash = :screenHash")
    suspend fun forScreen(screenHash: String): List<ActionValue>

    @Upsert
    suspend fun upsert(value: ActionValue)

    @Query("SELECT COUNT(*) FROM action_values")
    suspend fun totalLearnedEntries(): Int
}
