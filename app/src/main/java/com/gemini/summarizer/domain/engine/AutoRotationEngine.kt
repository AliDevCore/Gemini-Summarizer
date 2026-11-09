package com.gemini.summarizer.domain.engine

import com.gemini.summarizer.core.database.AppDatabase
import com.gemini.summarizer.core.database.entity.QuotaStateEntity
import com.gemini.summarizer.core.network.GeminiApiClient
import com.gemini.summarizer.core.network.GeminiApiResult
import com.gemini.summarizer.core.network.GeminiFileData
import com.gemini.summarizer.core.security.SecurityManager
import kotlinx.coroutines.delay

sealed class RotationExecutionResult {
    data class Success(
        val outputText: String,
        val modelUsed: String,
        val accountName: String,
        val projectId: String
    ) : RotationExecutionResult()

    data class Failure(val message: String) : RotationExecutionResult()
    data class AllExhausted(val message: String, val earliestCooldownTimestamp: Long?) : RotationExecutionResult()
}

class AutoRotationEngine(
    private val database: AppDatabase,
    private val securityManager: SecurityManager,
    private val apiClient: GeminiApiClient = GeminiApiClient()
) {

    suspend fun executeWithAutoRotation(
        prompt: String,
        systemInstruction: String? = null,
        fileData: GeminiFileData? = null,
        onProgressUpdate: ((String) -> Unit)? = null
    ): RotationExecutionResult {
        val now = System.currentTimeMillis()
        database.quotaStateDao().clearExpiredCooldowns(now)

        val enabledModels = database.modelDao().getEnabledTextModels()
        if (enabledModels.isEmpty()) {
            return RotationExecutionResult.Failure("No active Gemini models configured. Please enable at least one model in Models.")
        }

        // Get all valid active API keys directly across all accounts
        val validKeys = database.apiKeyDao().getAllValidKeys()
        if (validKeys.isEmpty()) {
            return RotationExecutionResult.Failure("No active API Keys found. Please add at least one Gemini API Key.")
        }

        var earliestCooldown: Long? = null
        var totalAttempts = 0

        // 1. Iterate through models in priority order (strongest first)
        for (model in enabledModels) {
            onProgressUpdate?.invoke("Trying model: ${model.displayName}...")

            // Group keys by projectId
            val keysByProject = validKeys.groupBy { it.projectId }
            var modelUnavailableForAccount = false

            for ((projectId, projectKeys) in keysByProject) {
                val quotaState = database.quotaStateDao().getQuotaState(projectId, model.id)
                if (quotaState != null && quotaState.isRateLimited && quotaState.cooldownUntilTimestamp > System.currentTimeMillis()) {
                    if (earliestCooldown == null || quotaState.cooldownUntilTimestamp < earliestCooldown) {
                        earliestCooldown = quotaState.cooldownUntilTimestamp
                    }
                    continue
                }

                for (keyEntity in projectKeys) {
                    val rawApiKey = securityManager.getApiKey(keyEntity.keyIdentifier)
                    if (rawApiKey.isNullOrBlank()) {
                        database.apiKeyDao().setKeyStatus(keyEntity.id, isInvalid = true, "Key missing in secure storage")
                        continue
                    }

                    // Look up account label for user display
                    val account = database.accountDao().getAccountById(keyEntity.accountId)
                    val accountDisplayName = account?.name ?: "Key #${keyEntity.id}"

                    totalAttempts++
                    val targetMsg = if (fileData != null) "Uploading & analyzing document" else "Connecting"
                    onProgressUpdate?.invoke("$targetMsg with ${accountDisplayName} on ${model.displayName}...")

                    var retryCount = 0
                    val maxRetries = 2
                    var callResult: GeminiApiResult<String>

                    while (true) {
                        callResult = apiClient.generateContent(
                            modelId = model.id,
                            apiKey = rawApiKey,
                            prompt = prompt,
                            systemInstruction = systemInstruction,
                            fileData = fileData
                        )

                        if (callResult is GeminiApiResult.TransientError && retryCount < maxRetries) {
                            retryCount++
                            delay((1000L * retryCount)) // Exponential backoff (1s, 2s)
                        } else {
                            break
                        }
                    }

                    when (callResult) {
                        is GeminiApiResult.Success -> {
                            database.apiKeyDao().updateLastUsed(keyEntity.id, System.currentTimeMillis())
                            return RotationExecutionResult.Success(
                                outputText = callResult.data,
                                modelUsed = model.id,
                                accountName = accountDisplayName,
                                projectId = projectId
                            )
                        }
                        is GeminiApiResult.ModelUnavailable -> {
                            onProgressUpdate?.invoke("Model ${model.id} is deprecated or unavailable. Rotating to next model...")
                            database.modelDao().setModelEnabled(model.id, false)
                            modelUnavailableForAccount = true
                            break
                        }
                        is GeminiApiResult.RateLimited -> {
                            val cooldownSec = callResult.retryAfterSeconds ?: 60L
                            val cooldownUntil = System.currentTimeMillis() + (cooldownSec * 1000L)
                            database.quotaStateDao().insertOrUpdate(
                                QuotaStateEntity(
                                    projectId = projectId,
                                    modelId = model.id,
                                    isRateLimited = true,
                                    cooldownUntilTimestamp = cooldownUntil,
                                    failureReason = callResult.message
                                )
                            )
                            onProgressUpdate?.invoke("Rate limit (429) hit for Project $projectId on ${model.id}. Rotating...")
                            break // Try next project
                        }
                        is GeminiApiResult.InvalidKey -> {
                            database.apiKeyDao().setKeyStatus(
                                keyId = keyEntity.id,
                                isInvalid = true,
                                message = "Key invalid or unauthorized (HTTP 401/403)"
                            )
                            onProgressUpdate?.invoke("Key ${keyEntity.id} is invalid. Trying next key...")
                        }
                        is GeminiApiResult.RequestError -> {
                            return RotationExecutionResult.Failure(callResult.message)
                        }
                        is GeminiApiResult.TransientError -> {
                            onProgressUpdate?.invoke("Temporary network glitch. Trying next option...")
                        }
                    }
                }

                if (modelUnavailableForAccount) {
                    break // Break out of this model's projects and move to next model
                }
            }
        }

        val cooldownMessage = if (earliestCooldown != null) {
            val secondsRemaining = ((earliestCooldown - System.currentTimeMillis()) / 1000L).coerceAtLeast(0)
            "All available Gemini quotas across all active accounts have been exhausted. Earliest quota reset in approximately $secondsRemaining seconds."
        } else {
            "All available combinations of accounts and models failed. Please verify your API keys."
        }

        return RotationExecutionResult.AllExhausted(cooldownMessage, earliestCooldown)
    }
}
