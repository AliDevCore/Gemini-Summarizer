package com.gemini.summarizer.core.database.dao

import androidx.room.*
import com.gemini.summarizer.core.database.entity.ModelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM gemini_models ORDER BY priority ASC, id ASC")
    fun getAllModelsFlow(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM gemini_models WHERE isEnabled = 1 AND supportsTextGeneration = 1 ORDER BY priority ASC")
    suspend fun getEnabledTextModels(): List<ModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModels(models: List<ModelEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertModelIfNotExist(model: ModelEntity)

    @Update
    suspend fun updateModel(model: ModelEntity)

    @Query("UPDATE gemini_models SET isEnabled = :isEnabled WHERE id = :modelId")
    suspend fun setModelEnabled(modelId: String, isEnabled: Boolean)

    @Query("UPDATE gemini_models SET priority = :priority WHERE id = :modelId")
    suspend fun setModelPriority(modelId: String, priority: Int)

    @Query("SELECT COUNT(*) FROM gemini_models")
    suspend fun getModelsCount(): Int
}

