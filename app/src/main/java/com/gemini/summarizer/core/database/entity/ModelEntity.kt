package com.gemini.summarizer.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gemini_models")
data class ModelEntity(
    @PrimaryKey
    val id: String, // e.g. "gemini-2.0-flash", "gemini-1.5-pro"
    val displayName: String,
    val description: String = "",
    val inputTokenLimit: Int = 1048576,
    val outputTokenLimit: Int = 8192,
    val isPreview: Boolean = false,
    val isStable: Boolean = true,
    val supportsTextGeneration: Boolean = true,
    val priority: Int = 0, // Lower number = higher priority
    val isEnabled: Boolean = true,
    val lastDiscoveredAt: Long = System.currentTimeMillis()
)
