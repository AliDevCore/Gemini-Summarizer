package com.gemini.summarizer.core.database.dao

import androidx.room.*
import com.gemini.summarizer.core.database.entity.ModelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM gemini_models ORDER BY priority ASC, id ASC")
    fun getAllModelsFlow(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM gemini_models ORDER BY priority ASC, id ASC")
    suspend fun getAllModelsList(): List<ModelEntity>

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

    @Query("UPDATE gemini_models SET isEnabled = 1 WHERE supportsTextGeneration = 1")
    suspend fun enableAllModels()

    @Query("UPDATE gemini_models SET isEnabled = 0")
    suspend fun disableAllModels()

    @Query("DELETE FROM gemini_models WHERE id NOT LIKE 'gemini-%' OR (id NOT LIKE '%flash%' AND id NOT LIKE '%pro%') OR id LIKE '%robotics%' OR id LIKE '%omni%' OR id LIKE '%nano%' OR id LIKE '%banana%' OR id LIKE '%antigravity%' OR id LIKE '%deep-research%' OR id LIKE '%gemma%' OR id LIKE '%embedding%' OR id LIKE '%aqa%' OR id LIKE '%imagen%' OR id LIKE '%learnlm%' OR id LIKE '%tts%' OR id LIKE '%whisper%' OR id LIKE '%2.5%' OR id LIKE '%1.0%' OR id LIKE '%-001' OR id LIKE '%-002'")
    suspend fun deleteDeprecatedModels()

    @Query("UPDATE gemini_models SET priority = :priority WHERE id = :modelId")
    suspend fun setModelPriority(modelId: String, priority: Int)

    @Query("SELECT COUNT(*) FROM gemini_models")
    suspend fun getModelsCount(): Int
}

