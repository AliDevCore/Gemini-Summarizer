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
    const val MODEL_GEMMA = "@cf/google/gemma-4-26b-a4b-it"
    const val MODEL_QWEN = "@cf/qwen/qwen3.8-27b"
    const val MODEL_GPT_OSS = "@cf/openai/gpt-oss-120b"
    const val MODEL_LLAMA = "@cf/meta/llama-3.3-70b-instruct-fp8-fast"

    val VERIFIED_MODELS = setOf(
        MODEL_GEMMA,
        MODEL_QWEN,
        MODEL_GPT_OSS,
        MODEL_LLAMA
    )

    fun isAllowedTextModel(id: String, displayName: String = ""): Boolean {
        val cleanId = id.trim().lowercase().removePrefix("models/")
        val cleanName = displayName.trim().lowercase()

        // 1. Allow verified Cloudflare models or any active Cloudflare LLM
        if (cleanId in VERIFIED_MODELS ||
            cleanId.startsWith("@cf/") ||
            cleanId.contains("gemma-4") ||
            cleanId.contains("qwen") ||
            cleanId.contains("gpt-oss") ||
            cleanId.contains("llama-3.3")
        ) {
            return true
        }

        // 2. Reject legacy or non-text models
        return false
    }
}
