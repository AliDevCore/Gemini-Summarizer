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

object ModelFilter {
    fun isAllowedTextModel(id: String, displayName: String = ""): Boolean {
        val cleanId = id.trim().lowercase().removePrefix("models/")
        val cleanName = displayName.trim().lowercase()

        // 1. Must start with "gemini-" or display name starts with "gemini"
        val isGemini = cleanId.startsWith("gemini-") || cleanName.startsWith("gemini")
        if (!isGemini) return false

        // 2. Exclude non-text, agentic-coding, tts, vision-only, robotics, etc.
        val nonTextKeywords = listOf(
            "robotics", "omni", "nano", "banana", "antigravity",
            "deep-research", "gemma", "embedding", "aqa", "imagen",
            "learnlm", "tts", "whisper", "audio", "live", "image"
        )
        if (nonTextKeywords.any { cleanId.contains(it) || cleanName.contains(it) }) {
            return false
        }

        // 3. Exclude retired/deprecated versions (e.g. 2.5, 1.0) and specific legacy checkpoints (-001, -002)
        if (cleanId.contains("2.5") || cleanId.contains("1.0") || cleanId.endsWith("-001") || cleanId.endsWith("-002")) {
            return false
        }

        return true
    }
}

