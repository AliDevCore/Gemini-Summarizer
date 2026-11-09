package com.gemini.summarizer.domain.engine

import com.gemini.summarizer.core.database.AppDatabase
import com.gemini.summarizer.core.database.entity.QuotaStateEntity
import com.gemini.summarizer.core.network.GeminiApiClient
import com.gemini.summarizer.core.network.GeminiApiResult
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
        onProgressUpdate: ((String) -> Unit)? = null
    ): RotationExecutionResult {
        val now = System.currentTimeMillis()
        database.quotaStateDao().clearExpiredCooldowns(now)

        val enabledModels = database.modelDao().getEnabledTextModels()
        if (enabledModels.isEmpty()) {
            return RotationExecutionResult.Failure("No active Gemini models configured. Please enable at least one model in Models.")
        }

        val enabledAccounts = database.accountDao().getEnabledAccounts()
        if (enabledAccounts.isEmpty()) {
            return RotationExecutionResult.Failure("No active Google accounts configured. Please add an account with valid API Keys.")
        }

        var earliestCooldown: Long? = null
        var totalAttempts = 0

        // 1. Iterate through models in priority order (strongest first)
        for (model in enabledModels) {
            onProgressUpdate?.invoke("Trying model: ${model.displayName}...")

            // 2. Iterate through accounts in priority order
            for (account in enabledAccounts) {
                val validKeys = database.apiKeyDao().getValidKeysForAccount(account.id)
                if (validKeys.isEmpty()) continue

                // 3. Group keys by Project ID so keys from the same project share quota awareness
                val keysByProject = validKeys.groupBy { it.projectId }

                for ((projectId, projectKeys) in keysByProject) {
                    val quotaState = database.quotaStateDao().getQuotaState(projectId, model.id)
                    if (quotaState != null && quotaState.isRateLimited && quotaState.cooldownUntilTimestamp > System.currentTimeMillis()) {
                        // This project's quota for this model is currently exhausted
                        if (earliestCooldown == null || quotaState.cooldownUntilTimestamp < earliestCooldown) {
                            earliestCooldown = quotaState.cooldownUntilTimestamp
                        }
                        continue
                    }

                    // Try each key in this project
                    for (keyEntity in projectKeys) {
                        val rawApiKey = securityManager.getApiKey(keyEntity.keyIdentifier)
                        if (rawApiKey.isNullOrBlank()) {
                            database.apiKeyDao().setKeyStatus(keyEntity.id, isInvalid = true, "Key missing in secure storage")
                            continue
                        }

                        totalAttempts++
                        onProgressUpdate?.invoke("Requesting via ${account.name} (Project: $projectId) on ${model.displayName}...")

                        // Transient error retry loop (up to 2 retries)
                        var retryCount = 0
                        val maxRetries = 2
                        var callResult: GeminiApiResult<String>

                        while (true) {
                            callResult = apiClient.generateContent(
                                modelId = model.id,
                                apiKey = rawApiKey,
                                prompt = prompt,
                                systemInstruction = systemInstruction
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
                                    accountName = account.name,
                                    projectId = projectId
                                )
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
                                // Break out of this project's keys and try next independent project
                                break
                            }
                            is GeminiApiResult.InvalidKey -> {
                                database.apiKeyDao().setKeyStatus(
                                    keyId = keyEntity.id,
                                    isInvalid = true,
                                    message = "Key invalid or unauthorized (HTTP 401/403)"
                                )
                                onProgressUpdate?.invoke("Key in ${account.name} invalid. Trying next key...")
                                // Continue to next key
                            }
                            is GeminiApiResult.RequestError -> {
                                // Format or prompt error (HTTP 400). Do not rotate blindly.
                                return RotationExecutionResult.Failure(callResult.message)
                            }
                            is GeminiApiResult.TransientError -> {
                                onProgressUpdate?.invoke("Temporary network/service glitch. Trying next option...")
                                // Continue to next option
                            }
                        }
                    }
                }
            }
        }

        val cooldownMessage = if (earliestCooldown != null) {
            val secondsRemaining = ((earliestCooldown - System.currentTimeMillis()) / 1000L).coerceAtLeast(0)
            "All available Gemini quotas across all registered accounts have been exhausted. Earliest quota reset in approximately $secondsRemaining seconds."
        } else {
            "All available combinations of accounts and models failed. Please verify your API keys and project settings."
        }

        return RotationExecutionResult.AllExhausted(cooldownMessage, earliestCooldown)
    }
}
