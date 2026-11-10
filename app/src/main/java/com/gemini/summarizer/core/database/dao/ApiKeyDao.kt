package com.gemini.summarizer.core.database.dao

import androidx.room.*
import com.gemini.summarizer.core.database.entity.ApiKeyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ApiKeyDao {
    @Query("SELECT * FROM api_keys ORDER BY id ASC")
    fun getAllApiKeysFlow(): Flow<List<ApiKeyEntity>>

    @Query("SELECT * FROM api_keys WHERE accountId = :accountId")
    fun getKeysForAccountFlow(accountId: Long): Flow<List<ApiKeyEntity>>

    @Query("SELECT * FROM api_keys WHERE accountId = :accountId")
    suspend fun getKeysForAccount(accountId: Long): List<ApiKeyEntity>

    @Query("SELECT * FROM api_keys WHERE isActive = 1 AND isInvalid = 0")
    suspend fun getAllValidKeys(): List<ApiKeyEntity>

    @Query("SELECT * FROM api_keys WHERE accountId = :accountId AND isActive = 1 AND isInvalid = 0")
    suspend fun getValidKeysForAccount(accountId: Long): List<ApiKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApiKey(key: ApiKeyEntity): Long

    @Update
    suspend fun updateApiKey(key: ApiKeyEntity)

    @Delete
    suspend fun deleteApiKey(key: ApiKeyEntity)

    @Query("DELETE FROM api_keys")
    suspend fun deleteAllKeys()

    @Query("UPDATE api_keys SET isInvalid = :isInvalid, statusMessage = :message WHERE id = :keyId")
    suspend fun setKeyStatus(keyId: Long, isInvalid: Boolean, message: String)

    @Query("UPDATE api_keys SET lastUsedTimestamp = :timestamp WHERE id = :keyId")
    suspend fun updateLastUsed(keyId: Long, timestamp: Long)
}

