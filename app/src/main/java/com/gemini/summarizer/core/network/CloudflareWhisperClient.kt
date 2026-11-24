package com.gemini.summarizer.core.network

import java.util.Base64
import com.gemini.summarizer.core.database.AppDatabase
import com.gemini.summarizer.core.database.entity.QuotaStateEntity
import com.gemini.summarizer.core.security.SecurityManager
import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resumeWithException

data class WhisperResult(
    val text: String,
    val wordCount: Int,
    val modelUsed: String,
    val accountUsed: String,
    val detectedLanguage: String? = null
)

class CloudflareWhisperClient(
    private val database: AppDatabase,
    private val securityManager: SecurityManager
) {
    companion object {
        const val MODEL_WHISPER_TURBO = "@cf/openai/whisper-large-v3-turbo"
        const val MODEL_WHISPER_BASE = "@cf/openai/whisper"

        private val roundRobinCounter = AtomicInteger(0)

        // Priming prompts for Whisper to encourage clean punctuation and spelling
        // Persian ('fa') is intentionally omitted to avoid Whisper hallucination/decoder repetition loops
        private val LANGUAGE_PROMPTS = mapOf(
            "en" to "Hello, welcome. Please transcribe with clear punctuation, commas, periods, question marks, and dialogue.",
            "ar" to "مرحبا. يرجى كتابة النص المنطوق بدقة مع علامات الترقيم الصحيحة.",
            "tr" to "Merhaba. Lütfen noktalama işaretlerine dikkat ederek doğru bir şekilde yazıya geçirin.",
            "fr" to "Bonjour. Veuillez transcrire avec une ponctuation claire, des virgules et des points.",
            "de" to "Hallo. Bitte mit korrekter Zeichensetzung und Rechtschreibung transkribieren.",
            "es" to "Hola. Por favor, transcribe con puntuación clara, comas y puntos.",
            "it" to "Ciao. Si prega di trascrivere con punteggiatura chiara, virgole e punti.",
            "ru" to "Здравствуйте. Пожалуйста, транскрибируйте с четкой пунктуацией и знаками препинания."
        )

        fun mapLanguageCode(languageName: String): String {
            return when (languageName.trim().lowercase()) {
                "auto" -> "auto"
                "persian", "farsi", "fa" -> "fa"
                "english", "en" -> "en"
                "arabic", "ar" -> "ar"
                "turkish", "tr" -> "tr"
                "french", "fr" -> "fr"
                "german", "de" -> "de"
                "spanish", "es" -> "es"
                "italian", "it" -> "it"
                "russian", "ru" -> "ru"
                else -> languageName.trim().lowercase().take(2)
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun transcribeWithRotation(
        audioBytes: ByteArray,
        language: String = "auto",
        modelId: String = MODEL_WHISPER_TURBO,
        onProgressUpdate: ((String) -> Unit)? = null
    ): Result<WhisperResult> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        database.quotaStateDao().clearExpiredCooldowns(now)

        val validKeys = database.apiKeyDao().getAllValidKeys()
        if (validKeys.isEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("No active Cloudflare API Keys found. Please configure your Account ID and API Token in Accounts.")
            )
        }

        val targetLangCode = mapLanguageCode(language)
        val startIndex = Math.floorMod(roundRobinCounter.getAndIncrement(), validKeys.size)
        val orderedKeys = List(validKeys.size) { i -> validKeys[(startIndex + i) % validKeys.size] }

        var lastErrorMsg: String? = null

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

            // Check if account is in rate-limit cooldown for Whisper
            val quotaState = database.quotaStateDao().getQuotaState(accountId, modelId)
            if (quotaState != null && quotaState.isRateLimited && quotaState.cooldownUntilTimestamp > System.currentTimeMillis()) {
                val remainingSec = (quotaState.cooldownUntilTimestamp - System.currentTimeMillis()) / 1000
                onProgressUpdate?.invoke("Account ${keyEntity.id} in quota cooldown (${remainingSec}s remaining). Trying next account...")
                continue
            }

            val account = database.accountDao().getAccountById(keyEntity.accountId)
            val accountDisplayName = account?.name ?: "Account #${keyEntity.id}"

            val langLabel = if (targetLangCode != "auto") targetLangCode else "auto-detect"
            onProgressUpdate?.invoke("Transcribing with Cloudflare Whisper ($accountDisplayName) [lang=$langLabel]...")

            var retryCount = 0
            val maxRetries = 2
            var callResult: GeminiApiResult<WhisperResult>

            while (true) {
                callResult = executeWhisperCall(
                    audioBytes = audioBytes,
                    accountId = accountId,
                    apiToken = rawApiKey,
                    targetLanguage = targetLangCode,
                    modelId = modelId,
                    accountName = accountDisplayName
                )

                if (callResult is GeminiApiResult.TransientError && retryCount < maxRetries) {
                    retryCount++
                    val backoff = 1000L * (1 shl retryCount)
                    onProgressUpdate?.invoke("Network lag encountered. Retrying in ${backoff}ms...")
                    delay(backoff)
                } else {
                    break
                }
            }

            when (callResult) {
                is GeminiApiResult.Success -> {
                    database.apiKeyDao().updateLastUsed(keyEntity.id, System.currentTimeMillis())
                    return@withContext Result.success(callResult.data)
                }
                is GeminiApiResult.RateLimited -> {
                    val cooldownSec = callResult.retryAfterSeconds ?: 60L
                    val cooldownUntil = System.currentTimeMillis() + (cooldownSec * 1000L)
                    database.quotaStateDao().insertOrUpdate(
                        QuotaStateEntity(
                            projectId = accountId,
                            modelId = modelId,
                            isRateLimited = true,
                            cooldownUntilTimestamp = cooldownUntil,
                            failureReason = callResult.message
                        )
                    )
                    onProgressUpdate?.invoke("Quota/Rate limit hit on $accountDisplayName. Rotating to next Cloudflare account...")
                    lastErrorMsg = callResult.message
                }
                is GeminiApiResult.InvalidKey -> {
                    database.apiKeyDao().setKeyStatus(keyEntity.id, isInvalid = true, callResult.message)
                    onProgressUpdate?.invoke("Account $accountDisplayName authentication failed. Rotating...")
                    lastErrorMsg = callResult.message
                }
                is GeminiApiResult.ModelUnavailable,
                is GeminiApiResult.TransientError,
                is GeminiApiResult.RequestError -> {
                    lastErrorMsg = callResult.message
                    onProgressUpdate?.invoke("Error on $accountDisplayName: ${callResult.message}. Rotating...")
                }
            }
        }

        Result.failure(Exception(lastErrorMsg ?: "All Cloudflare accounts failed to transcribe audio."))
    }

    private suspend fun executeWhisperCall(
        audioBytes: ByteArray,
        accountId: String,
        apiToken: String,
        targetLanguage: String,
        modelId: String,
        accountName: String
    ): GeminiApiResult<WhisperResult> = withContext(Dispatchers.IO) {
        val url = "https://api.cloudflare.com/client/v4/accounts/$accountId/ai/run/$modelId"

        val base64Audio = Base64.getEncoder().encodeToString(audioBytes)
        val payload = JsonObject().apply {
            addProperty("audio", base64Audio)
            addProperty("vad_filter", false)
            addProperty("condition_on_previous_text", false)
            addProperty("no_speech_threshold", 0.85)
            addProperty("log_prob_threshold", -2.0)

            if (targetLanguage != "auto" && targetLanguage.isNotBlank()) {
                addProperty("language", targetLanguage)
                // For Persian ('fa'), DO NOT set initial_prompt to prevent Whisper repetition hallucinations
                if (targetLanguage != "fa" && LANGUAGE_PROMPTS.containsKey(targetLanguage)) {
                    addProperty("initial_prompt", LANGUAGE_PROMPTS[targetLanguage])
                }
            }
        }

        val requestBody = payload.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiToken")
            .build()

        try {
            executeCancellable(request).use { response ->
                val responseBody = response.body?.string() ?: ""
                val code = response.code

                when (code) {
                    200 -> {
                        val parsed = gson.fromJson(responseBody, JsonObject::class.java)
                        val success = parsed.get("success")?.asBoolean ?: true
                        if (success && parsed.has("result") && !parsed.get("result").isJsonNull) {
                            val resultObj = parsed.get("result")
                            val text = when {
                                resultObj.isJsonObject && resultObj.asJsonObject.has("text") -> {
                                    resultObj.asJsonObject.get("text").asString ?: ""
                                }
                                resultObj.isJsonObject && resultObj.asJsonObject.has("vtt") -> {
                                    extractTextFromVtt(resultObj.asJsonObject.get("vtt").asString ?: "")
                                }
                                resultObj.isJsonPrimitive -> resultObj.asString
                                else -> resultObj.toString()
                            }

                            val cleanText = cleanTranscript(text)
                            val words = if (cleanText.isBlank()) 0 else cleanText.trim().split("\\s+".toRegex()).size

                            return@withContext GeminiApiResult.Success(
                                WhisperResult(
                                    text = cleanText,
                                    wordCount = words,
                                    modelUsed = modelId,
                                    accountUsed = accountName
                                )
                            )
                        }

                        val errorMsg = parseErrorMessage(responseBody) ?: "Cloudflare Whisper returned unexpected format."
                        return@withContext GeminiApiResult.RequestError(errorMsg)
                    }
                    401, 403 -> {
                        val msg = parseErrorMessage(responseBody) ?: "Cloudflare Token unauthorized (HTTP $code)"
                        return@withContext GeminiApiResult.InvalidKey(msg)
                    }
                    429 -> {
                        val retryAfter = response.header("Retry-After")?.toLongOrNull() ?: 60L
                        val msg = parseErrorMessage(responseBody) ?: "Cloudflare Whisper quota/rate limit reached (429)"
                        return@withContext GeminiApiResult.RateLimited(msg, retryAfter)
                    }
                    500, 502, 503, 504 -> {
                        val msg = parseErrorMessage(responseBody) ?: "Cloudflare temporary server error (HTTP $code)"
                        return@withContext GeminiApiResult.TransientError(msg, code)
                    }
                    else -> {
                        val msg = parseErrorMessage(responseBody) ?: "Cloudflare error (HTTP $code)"
                        return@withContext GeminiApiResult.RequestError(msg)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return@withContext GeminiApiResult.TransientError("Network error: ${e.localizedMessage ?: "Connection failed"}")
        } catch (e: Exception) {
            return@withContext GeminiApiResult.RequestError("Whisper request failed: ${e.localizedMessage ?: "Unknown error"}")
        }
    }

    private fun extractTextFromVtt(vtt: String): String {
        val lines = vtt.lines()
        val textLines = mutableListOf<String>()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("WEBVTT") || trimmed.startsWith("NOTE") || trimmed.contains("-->")) {
                continue
            }
            textLines.add(trimmed)
        }
        return textLines.joinToString(" ")
    }

    private fun cleanTranscript(raw: String): String {
        return raw.trim()
            .replace("\\n", "\n")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("(\\n\\s*){3,}"), "\n\n")
    }

    private fun parseErrorMessage(json: String): String? {
        return try {
            val obj = gson.fromJson(json, JsonObject::class.java)
            if (obj.has("errors") && obj.getAsJsonArray("errors").size() > 0) {
                obj.getAsJsonArray("errors").mapNotNull { it.asJsonObject.get("message")?.asString }.joinToString("; ")
            } else if (obj.has("message")) {
                obj.get("message").asString
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun executeCancellable(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation {
                call.cancel()
            }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) {
                        response.close()
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }
}
