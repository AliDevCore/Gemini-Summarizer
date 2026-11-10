package com.gemini.summarizer.core.database.dao

import androidx.room.*
import com.gemini.summarizer.core.database.entity.QuotaStateEntity

@Dao
interface QuotaStateDao {
    @Query("SELECT * FROM quota_states WHERE projectId = :projectId AND modelId = :modelId LIMIT 1")
    suspend fun getQuotaState(projectId: String, modelId: String): QuotaStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(quotaState: QuotaStateEntity)

    @Query("UPDATE quota_states SET isRateLimited = 0, cooldownUntilTimestamp = 0 WHERE cooldownUntilTimestamp <= :currentTimestamp")
    suspend fun clearExpiredCooldowns(currentTimestamp: Long)

    @Query("SELECT * FROM quota_states")
    suspend fun getAllQuotaStates(): List<QuotaStateEntity>

    @Query("DELETE FROM quota_states")
    suspend fun clearAll()
}

