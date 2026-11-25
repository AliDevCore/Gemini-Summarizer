package com.gemini.summarizer.domain.engine

import com.gemini.summarizer.core.database.AppDatabase
import com.gemini.summarizer.core.database.entity.QuotaStateEntity
import com.gemini.summarizer.core.network.GeminiApiClient
import com.gemini.summarizer.core.network.GeminiApiResult
import com.gemini.summarizer.core.network.GeminiFileData
import com.gemini.summarizer.core.security.SecurityManager
import kotlinx.coroutines.delay
import java.util.Locale

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

    companion object {
        private val keyIndexCounter = java.util.concurrent.atomic.AtomicInteger(0)
        private val THINKING_MODELS = setOf(
            "@cf/google/gemma-4-26b-a4b-it",
            "@cf/qwen/qwen3.8-27b"
        )
    }

    suspend fun getActiveKeyCount(): Int {
        return database.apiKeyDao().getAllValidKeys().size.coerceAtLeast(1)
    }

    suspend fun executeWithAutoRotation(
        prompt: String,
        systemInstruction: String? = null,
        fileData: GeminiFileData? = null,
        onProgressUpdate: ((String) -> Unit)? = null
    ): RotationExecutionResult {
        val now = System.currentTimeMillis()
        database.quotaStateDao().clearExpiredCooldowns(now)

        var enabledModels = database.modelDao().getEnabledTextModels()
        if (enabledModels.isEmpty()) {
            database.modelDao().insertModels(AppDatabase.getDefaultModels())
            enabledModels = database.modelDao().getEnabledTextModels()
        }

        // Get all valid active API keys directly across all accounts
        val validKeys = database.apiKeyDao().getAllValidKeys()
        if (validKeys.isEmpty()) {
            return RotationExecutionResult.Failure(
                "No active Cloudflare API Keys found. Please add your Cloudflare API Token and Account ID in Accounts."
            )
        }

        var earliestCooldown: Long? = null
        var lastErrorMessage: String? = null

        // Iterate through prioritized enabled models
        for ((modelIndex, targetModel) in enabledModels.withIndex()) {
            if (modelIndex > 0) {
                onProgressUpdate?.invoke("Failing over to prioritized model: ${targetModel.displayName}...")
            }

            var modelHadValidAttempt = false

            // Order keys using thread-safe Round-Robin so parallel chunks distribute evenly across different Cloudflare accounts
            val startIndex = Math.floorMod(keyIndexCounter.getAndIncrement(), validKeys.size)
            val orderedKeys = List(validKeys.size) { i -> validKeys[(startIndex + i) % validKeys.size] }

            // Rotate across available Cloudflare keys for current target model
            for (keyEntity in orderedKeys) {
                val rawApiKey = securityManager.getApiKey(keyEntity.keyIdentifier)
                if (rawApiKey.isNullOrBlank()) {
                    database.apiKeyDao().setKeyStatus(keyEntity.id, isInvalid = true, "Token missing in secure storage")
                    continue
                }

                val accountId = keyEntity.projectId.trim()
                if (accountId.isBlank()) {
                    database.apiKeyDao().setKeyStatus(keyEntity.id, isInvalid = true, "Missing Cloudflare Account ID")
                    continue
                }

                // Check if key is currently rate limited on this model
                val quotaState = database.quotaStateDao().getQuotaState(accountId, targetModel.id)
                if (quotaState != null && quotaState.isRateLimited && quotaState.cooldownUntilTimestamp > System.currentTimeMillis()) {
                    if (earliestCooldown == null || quotaState.cooldownUntilTimestamp < earliestCooldown) {
                        earliestCooldown = quotaState.cooldownUntilTimestamp
                    }
                    continue
                }

                modelHadValidAttempt = true

                // Look up account label for user display
                val account = database.accountDao().getAccountById(keyEntity.accountId)
                val accountDisplayName = account?.name ?: "Key #${keyEntity.id}"

                onProgressUpdate?.invoke("Querying ${targetModel.displayName} with $accountDisplayName...")

                var retryCount = 0
                val maxRetries = if (validKeys.size > 1) 1 else 2
                var callResult: GeminiApiResult<String>
                val disableThinking = if (THINKING_MODELS.contains(targetModel.id)) false else null

                while (true) {
                    callResult = apiClient.generateContent(
                        modelId = targetModel.id,
                        accountId = accountId,
                        apiToken = rawApiKey,
                        prompt = prompt,
                        systemInstruction = systemInstruction,
                        enableThinking = disableThinking
                    )

                    if (callResult is GeminiApiResult.TransientError && retryCount < maxRetries) {
                        retryCount++
                        val baseWait = 1000L * (1 shl (retryCount - 1))
                        val jitter = (0..350).random()
                        val totalWait = (baseWait + jitter).coerceAtMost(10_000L)
                        val waitSec = String.format(Locale.US, "%.1f", totalWait / 1000.0)
                        onProgressUpdate?.invoke("Temporary network lag. Retrying in ${waitSec}s...")
                        delay(totalWait)
                    } else {
                        break
                    }
                }

                when (callResult) {
                    is GeminiApiResult.Success -> {
                        onProgressUpdate?.invoke("Success! Generated summary via ${targetModel.displayName}.")
                        database.apiKeyDao().updateLastUsed(keyEntity.id, System.currentTimeMillis())
                        return RotationExecutionResult.Success(
                            outputText = callResult.data,
                            modelUsed = targetModel.displayName,
                            accountName = accountDisplayName,
                            projectId = accountId
                        )
                    }
                    is GeminiApiResult.RateLimited -> {
                        val cooldownSec = callResult.retryAfterSeconds ?: 60L
                        val cooldownUntil = System.currentTimeMillis() + (cooldownSec * 1000L)
                        database.quotaStateDao().insertOrUpdate(
                            QuotaStateEntity(
                                projectId = accountId,
                                modelId = targetModel.id,
                                isRateLimited = true,
                                cooldownUntilTimestamp = cooldownUntil,
                                failureReason = callResult.message
                            )
                        )
                        onProgressUpdate?.invoke("Rate limit (429) hit on $accountDisplayName. Rotating to next Cloudflare key...")
                        lastErrorMessage = callResult.message
                    }
                    is GeminiApiResult.InvalidKey -> {
                        database.apiKeyDao().setKeyStatus(
                            keyId = keyEntity.id,
                            isInvalid = true,
                            message = "Invalid Token (HTTP 401/403)"
                        )
                        onProgressUpdate?.invoke("API Token unauthorized on $accountDisplayName. Rotating to next key...")
                        lastErrorMessage = callResult.message
                    }
                    is GeminiApiResult.ModelUnavailable -> {
                        database.apiKeyDao().setKeyStatus(
                            keyId = keyEntity.id,
                            isInvalid = true,
                            message = "Invalid Account ID or model unavailable (HTTP 404)"
                        )
                        onProgressUpdate?.invoke("Account ID $accountId returned 404. Rotating...")
                        lastErrorMessage = callResult.message
                    }
                    is GeminiApiResult.TransientError -> {
                        onProgressUpdate?.invoke("Temporary error on $accountDisplayName (${callResult.message}). Rotating...")
                        lastErrorMessage = callResult.message
                    }
                    is GeminiApiResult.RequestError -> {
                        onProgressUpdate?.invoke("Request failed on $accountDisplayName (${callResult.message}). Rotating...")
                        lastErrorMessage = callResult.message
                    }
                }
            }

            if (modelHadValidAttempt) {
                onProgressUpdate?.invoke("All accounts failed or rate-limited on ${targetModel.displayName}. Checking next model...")
            }
        }

        val cooldownMessage = if (earliestCooldown != null) {
            val secondsRemaining = ((earliestCooldown - System.currentTimeMillis()) / 1000L).coerceAtLeast(0)
            "All configured Cloudflare API keys are currently rate-limited. Earliest reset in approximately $secondsRemaining seconds."
        } else {
            lastErrorMessage ?: "All available Cloudflare models and keys failed. Please verify your Tokens and Account IDs in Accounts."
        }

        return RotationExecutionResult.AllExhausted(cooldownMessage, earliestCooldown)
    }
}
