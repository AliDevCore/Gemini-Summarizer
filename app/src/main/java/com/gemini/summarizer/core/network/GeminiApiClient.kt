package com.gemini.summarizer.core.network

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
import kotlin.coroutines.resumeWithException

sealed class GeminiApiResult<out T> {
    open val message: String get() = ""
    data class Success<out T>(val data: T) : GeminiApiResult<T>()
    data class RateLimited(override val message: String, val retryAfterSeconds: Long? = null) : GeminiApiResult<Nothing>()
    data class InvalidKey(override val message: String) : GeminiApiResult<Nothing>()
    data class ModelUnavailable(override val message: String) : GeminiApiResult<Nothing>()
    data class TransientError(override val message: String, val statusCode: Int? = null) : GeminiApiResult<Nothing>()
    data class RequestError(override val message: String) : GeminiApiResult<Nothing>()
}

data class GeminiFileData(
    val mimeType: String,
    val base64Data: String
)

data class DiscoveredModel(
    val id: String,
    val displayName: String,
    val description: String,
    val inputTokenLimit: Int,
    val outputTokenLimit: Int,
    val supportsTextGeneration: Boolean
)

class GeminiApiClient {

    companion object {
        const val DEFAULT_MODEL = "@cf/google/gemma-4-26b-a4b-it"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun generateContent(
        modelId: String = DEFAULT_MODEL,
        accountId: String,
        apiToken: String,
        prompt: String,
        systemInstruction: String? = null
    ): GeminiApiResult<String> = withContext(Dispatchers.IO) {
        val targetModel = if (modelId.startsWith("@cf/")) modelId else DEFAULT_MODEL
        val cleanAccountId = accountId.trim()
        val cleanToken = apiToken.trim()

        if (cleanAccountId.isBlank()) {
            return@withContext GeminiApiResult.RequestError("Cloudflare Account ID is missing. Please edit your key in Accounts.")
        }
        if (cleanToken.isBlank()) {
            return@withContext GeminiApiResult.InvalidKey("Cloudflare API Token is missing or empty.")
        }

        val url = "https://api.cloudflare.com/client/v4/accounts/$cleanAccountId/ai/run/$targetModel"

        val rootJson = JsonObject()
        val messagesArray = com.google.gson.JsonArray()

        if (!systemInstruction.isNullOrBlank()) {
            val systemMsg = JsonObject()
            systemMsg.addProperty("role", "system")
            systemMsg.addProperty("content", systemInstruction)
            messagesArray.add(systemMsg)
        }

        val userMsg = JsonObject()
        userMsg.addProperty("role", "user")
        userMsg.addProperty("content", prompt)
        messagesArray.add(userMsg)

        rootJson.add("messages", messagesArray)
        rootJson.addProperty("max_tokens", 4096)

        val requestBody = rootJson.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $cleanToken")
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
                            val resultElem = parsed.get("result")
                            val textResult = when {
                                resultElem.isJsonObject && resultElem.asJsonObject.has("response") -> {
                                    resultElem.asJsonObject.get("response")?.asString ?: ""
                                }
                                resultElem.isJsonObject && resultElem.asJsonObject.has("choices") -> {
                                    val choices = resultElem.asJsonObject.getAsJsonArray("choices")
                                    if (choices != null && choices.size() > 0) {
                                        choices[0].asJsonObject.getAsJsonObject("message")?.get("content")?.asString ?: ""
                                    } else ""
                                }
                                resultElem.isJsonPrimitive -> resultElem.asString
                                else -> resultElem.toString()
                            }

                            if (textResult.isNotBlank()) {
                                return@withContext GeminiApiResult.Success(textResult.trim())
                            }
                        }
                        val errorMsg = parseErrorMessage(responseBody) ?: "Empty or unrecognized response structure from Cloudflare Workers AI"
                        return@withContext GeminiApiResult.RequestError(errorMsg)
                    }
                    401, 403 -> {
                        val errorMessage = parseErrorMessage(responseBody)
                            ?: "Cloudflare API Token is invalid or unauthorized (HTTP $code). Verify Workers AI permissions."
                        return@withContext GeminiApiResult.InvalidKey(errorMessage)
                    }
                    404 -> {
                        val errorMessage = parseErrorMessage(responseBody)
                            ?: "Cloudflare Account ID ($cleanAccountId) or model '$targetModel' not found (HTTP 404)."
                        return@withContext GeminiApiResult.ModelUnavailable(errorMessage)
                    }
                    429 -> {
                        val retryAfterHeader = response.header("Retry-After")?.toLongOrNull()
                        val errorMessage = parseErrorMessage(responseBody) ?: "Cloudflare rate limit or quota exceeded (HTTP 429)"
                        return@withContext GeminiApiResult.RateLimited(errorMessage, retryAfterHeader ?: 60L)
                    }
                    500, 502, 503, 504 -> {
                        val errorMessage = parseErrorMessage(responseBody) ?: "Cloudflare temporary service error (HTTP $code)"
                        return@withContext GeminiApiResult.TransientError(errorMessage, code)
                    }
                    else -> {
                        val errorMessage = parseErrorMessage(responseBody) ?: "Cloudflare request error (HTTP $code)"
                        return@withContext GeminiApiResult.RequestError(errorMessage)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return@withContext GeminiApiResult.TransientError("Network error: ${e.localizedMessage ?: "Connection failed"}")
        } catch (e: Exception) {
            return@withContext GeminiApiResult.RequestError("Unexpected error: ${e.localizedMessage ?: "Unknown"}")
        }
    }

    suspend fun generateContent(
        modelId: String,
        apiKey: String,
        prompt: String,
        systemInstruction: String? = null,
        fileData: GeminiFileData? = null
    ): GeminiApiResult<String> {
        val parts = apiKey.split(":", limit = 2)
        val (accountId, token) = if (parts.size == 2) {
            parts[0] to parts[1]
        } else {
            "" to apiKey
        }
        return generateContent(
            modelId = modelId,
            accountId = accountId,
            apiToken = token,
            prompt = prompt,
            systemInstruction = systemInstruction
        )
    }

    suspend fun testConnection(apiToken: String, accountId: String): GeminiApiResult<Boolean> = withContext(Dispatchers.IO) {
        val cleanAccountId = accountId.trim()
        val cleanToken = apiToken.trim()

        if (cleanAccountId.isBlank()) {
            return@withContext GeminiApiResult.RequestError("Cloudflare Account ID is required.")
        }
        if (cleanToken.isBlank()) {
            return@withContext GeminiApiResult.InvalidKey("Cloudflare API Token is required.")
        }

        val url = "https://api.cloudflare.com/client/v4/accounts/$cleanAccountId/ai/run/$DEFAULT_MODEL"
        val rootJson = JsonObject()
        val messagesArray = com.google.gson.JsonArray()
        val userMsg = JsonObject()
        userMsg.addProperty("role", "user")
        userMsg.addProperty("content", "Hi")
        messagesArray.add(userMsg)
        rootJson.add("messages", messagesArray)
        rootJson.addProperty("max_tokens", 5)

        val requestBody = rootJson.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $cleanToken")
            .build()

        try {
            executeCancellable(request).use { response ->
                val body = response.body?.string() ?: ""
                when (response.code) {
                    200 -> GeminiApiResult.Success(true)
                    401, 403 -> GeminiApiResult.InvalidKey(parseErrorMessage(body) ?: "Invalid Cloudflare API Token or permission denied")
                    404 -> GeminiApiResult.ModelUnavailable(parseErrorMessage(body) ?: "Cloudflare Account ID not found (404)")
                    429 -> GeminiApiResult.RateLimited(parseErrorMessage(body) ?: "Rate limit reached (429)")
                    else -> GeminiApiResult.RequestError(parseErrorMessage(body) ?: "HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            GeminiApiResult.TransientError("Connection failed: ${e.localizedMessage}")
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
                    if (continuation.isCancelled) return
                    continuation.resumeWithException(e)
                }
            })
        }

    suspend fun listModels(apiKey: String): GeminiApiResult<List<DiscoveredModel>> = withContext(Dispatchers.IO) {
        val singleModel = DiscoveredModel(
            id = DEFAULT_MODEL,
            displayName = "Gemma 4 26B (Cloudflare)",
            description = "Google Gemma 4 26B Instruct on Cloudflare Workers AI with 256K Context Window",
            inputTokenLimit = 262144,
            outputTokenLimit = 4096,
            supportsTextGeneration = true
        )
        GeminiApiResult.Success(listOf(singleModel))
    }

    private fun parseErrorMessage(jsonBody: String): String? {
        return try {
            val root = gson.fromJson(jsonBody, JsonObject::class.java)
            if (root.has("errors") && root.get("errors").isJsonArray) {
                val errors = root.getAsJsonArray("errors")
                if (errors.size() > 0) {
                    val firstErr = errors[0]
                    if (firstErr.isJsonObject && firstErr.asJsonObject.has("message")) {
                        return firstErr.asJsonObject.get("message").asString
                    } else if (firstErr.isJsonPrimitive) {
                        return firstErr.asString
                    }
                }
            }
            if (root.has("error")) {
                val err = root.get("error")
                if (err.isJsonObject && err.asJsonObject.has("message")) {
                    return err.asJsonObject.get("message").asString
                } else if (err.isJsonPrimitive) {
                    return err.asString
                }
            }
            if (root.has("messages") && root.get("messages").isJsonArray) {
                val msgs = root.getAsJsonArray("messages")
                if (msgs.size() > 0) return msgs[0].asString
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
