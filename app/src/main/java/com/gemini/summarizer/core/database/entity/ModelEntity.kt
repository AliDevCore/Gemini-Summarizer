package com.gemini.summarizer.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "gemini_models")
data class ModelEntity(
    @PrimaryKey
    val id: String, // e.g. "@cf/google/gemma-4-26b-a4b-it"
    val displayName: String,
    val description: String = "",
    val inputTokenLimit: Int = 262144,
    val outputTokenLimit: Int = 4096,
    val isPreview: Boolean = false,
    val isStable: Boolean = true,
    val supportsTextGeneration: Boolean = true,
    val priority: Int = 0, // Lower number = higher priority
    val isEnabled: Boolean = true,
    val lastDiscoveredAt: Long = System.currentTimeMillis()
)

object ModelFilter {
    const val DEFAULT_CLOUDFLARE_MODEL = "@cf/google/gemma-4-26b-a4b-it"

    fun isAllowedTextModel(id: String, displayName: String = ""): Boolean {
        val cleanId = id.trim().lowercase().removePrefix("models/")
        val cleanName = displayName.trim().lowercase()

        // 1. Check for supported Cloudflare Gemma 4 model
        if (cleanId == DEFAULT_CLOUDFLARE_MODEL || cleanId.contains("gemma-4-26b-a4b-it") || cleanId.startsWith("@cf/")) {
            return true
        }

        // 2. Reject legacy or irrelevant models
        return false
    }
}
