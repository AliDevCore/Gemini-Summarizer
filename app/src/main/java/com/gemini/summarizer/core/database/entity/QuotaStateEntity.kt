package com.gemini.summarizer.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "quota_states",
    indices = [Index(value = ["projectId", "modelId"], unique = true)]
)
data class QuotaStateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val projectId: String,
    val modelId: String,
    val isRateLimited: Boolean = false,
    val cooldownUntilTimestamp: Long = 0L,
    val failureReason: String? = null
)

