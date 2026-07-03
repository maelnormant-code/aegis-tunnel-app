package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutingRuleDao {
    @Query("SELECT * FROM routing_rules ORDER BY appName ASC")
    fun getAllRules(): Flow<List<RoutingRule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: RoutingRule)

    @Query("DELETE FROM routing_rules WHERE packageName = :packageName")
    suspend fun deleteRule(packageName: String)

    @Query("UPDATE routing_rules SET isEnabled = :enabled WHERE packageName = :packageName")
    suspend fun updateRuleEnabled(packageName: String, enabled: Boolean)
}
