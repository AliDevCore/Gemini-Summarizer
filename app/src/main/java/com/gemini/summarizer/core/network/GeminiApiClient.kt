package com.gemini.summarizer.core.network

import com.gemini.summarizer.core.database.entity.ModelFilter
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
    data class Success<out T>(val data: T) : GeminiApiResult<T>()
    data class RateLimited(val message: String, val retryAfterSeconds: Long? = null) : GeminiApiResult<Nothing>()
    data class InvalidKey(val message: String) : GeminiApiResult<Nothing>()
    data class ModelUnavailable(val message: String) : GeminiApiResult<Nothing>()
    data class TransientError(val message: String, val statusCode: Int? = null) : GeminiApiResult<Nothing>()
    data class RequestError(val message: String) : GeminiApiResult<Nothing>()
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun generateContent(
        modelId: String,
        apiKey: String,
        prompt: String,
        systemInstruction: String? = null,
        fileData: GeminiFileData? = null
    ): GeminiApiResult<String> = withContext(Dispatchers.IO) {
        val cleanModel = modelId.removePrefix("models/")
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$cleanModel:generateContent"

        val rootJson = JsonObject()
        val contentsArray = com.google.gson.JsonArray()
        val contentObj = JsonObject()
        val partsArray = com.google.gson.JsonArray()

        // 1. If a binary file (PDF/Image/Text) is attached, add it natively as inlineData
        if (fileData != null) {
            val filePart = JsonObject()
            val inlineDataObj = JsonObject()
            inlineDataObj.addProperty("mimeType", fileData.mimeType)
            inlineDataObj.addProperty("data", fileData.base64Data)
            filePart.add("inlineData", inlineDataObj)
            partsArray.add(filePart)
        }

        // 2. Add the text prompt part
        val textPartObj = JsonObject()
        textPartObj.addProperty("text", prompt)
        partsArray.add(textPartObj)

        contentObj.add("parts", partsArray)
        contentsArray.add(contentObj)
        rootJson.add("contents", contentsArray)

        if (!systemInstruction.isNullOrBlank()) {
            val systemInstructionObj = JsonObject()
            val sysPartsArray = com.google.gson.JsonArray()
            val sysPartObj = JsonObject()
            sysPartObj.addProperty("text", systemInstruction)
            sysPartsArray.add(sysPartObj)
            systemInstructionObj.add("parts", sysPartsArray)
            rootJson.add("systemInstruction", systemInstructionObj)
        }

        val configObj = JsonObject()
        configObj.addProperty("maxOutputTokens", 32768)
        rootJson.add("generationConfig", configObj)

        val requestBody = rootJson.toString().toRequestBody(jsonMediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey.trim())
            .build()

        try {
            executeCancellable(request).use { response ->
                val responseBody = response.body?.string() ?: ""
                val code = response.code

                when (code) {
                    200 -> {
                        val parsed = gson.fromJson(responseBody, JsonObject::class.java)
                        val candidates = parsed.getAsJsonArray("candidates")
                        if (candidates != null && candidates.size() > 0) {
                            val firstCandidate = candidates[0].asJsonObject
                            val finishReason = firstCandidate.get("finishReason")?.asString
                            val content = firstCandidate.getAsJsonObject("content")
                            val parts = content?.getAsJsonArray("parts")
                            if (parts != null && parts.size() > 0) {
                                val fullText = StringBuilder()
                                for (p in parts) {
                                    val partObj = p.asJsonObject
                                    val isThought = partObj.get("thought")?.asBoolean ?: false
                                    if (!isThought && partObj.has("text")) {
                                        fullText.append(partObj.get("text").asString)
                                    }
                                }
                                val textResult = fullText.toString().trim()
                                if (textResult.isNotEmpty()) {
                                    if (finishReason == "MAX_TOKENS") {
                                        return@withContext GeminiApiResult.Success(
                                            "$textResult\n\n[Notice: Generation reached maximum token limit (MAX_TOKENS) and may be truncated.]"
                                        )
                                    }
                                    return@withContext GeminiApiResult.Success(textResult)
                                }
                            }
                            if (finishReason == "SAFETY") {
                                return@withContext GeminiApiResult.RequestError("Content blocked by Google Safety Filters.")
                            }
                        }
                        return@withContext GeminiApiResult.RequestError("Empty response returned from model")
                    }
                    404 -> {
                        val errorMessage = parseErrorMessage(responseBody) ?: "Model not found or retired (HTTP 404)"
                        return@withContext GeminiApiResult.ModelUnavailable(errorMessage)
                    }
                    429 -> {
                        val retryAfterHeader = response.header("Retry-After")?.toLongOrNull()
                        val retryDelayFromBody = parseRetryDelay(responseBody)
                        val effectiveCooldown = retryAfterHeader ?: retryDelayFromBody
                        val errorMessage = parseErrorMessage(responseBody) ?: "Rate limit or quota exceeded (HTTP 429)"
                        return@withContext GeminiApiResult.RateLimited(errorMessage, effectiveCooldown)
                    }
                    401, 403 -> {
                        val errorMessage = parseErrorMessage(responseBody) ?: "API Key is invalid or unauthorized"
                        return@withContext GeminiApiResult.InvalidKey(errorMessage)
                    }
                    500, 502, 503, 504 -> {
                        val errorMessage = parseErrorMessage(responseBody) ?: "Google service temporary error (HTTP $code)"
                        return@withContext GeminiApiResult.TransientError(errorMessage, code)
                    }
                    else -> {
                        val errorMessage = parseErrorMessage(responseBody) ?: "Request error (HTTP $code)"
                        if (errorMessage.contains("User location is not supported", ignoreCase = true) ||
                            errorMessage.contains("location is not supported", ignoreCase = true)) {
                            return@withContext GeminiApiResult.RequestError("Google Gemini is not supported in your current IP/region. Please connect to a VPN/proxy.")
                        }
                        if (errorMessage.contains("no longer available", ignoreCase = true) ||
                            errorMessage.contains("not found", ignoreCase = true) ||
                            errorMessage.contains("update your code to use", ignoreCase = true)
                        ) {
                            return@withContext GeminiApiResult.ModelUnavailable(errorMessage)
                        }
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
        val url = "https://generativelanguage.googleapis.com/v1beta/models"
        val request = Request.Builder()
            .url(url)
            .get()
            .header("x-goog-api-key", apiKey.trim())
            .build()

        try {
            executeCancellable(request).use { response ->
                val body = response.body?.string() ?: ""
                if (response.code == 200) {
                    val root = gson.fromJson(body, JsonObject::class.java)
                    val modelsArray = root.getAsJsonArray("models") ?: return@withContext GeminiApiResult.Success(emptyList())
                    val result = mutableListOf<DiscoveredModel>()

                    for (elem in modelsArray) {
                        val mObj = elem.asJsonObject
                        val rawName = mObj.get("name")?.asString ?: ""
                        val cleanId = rawName.removePrefix("models/").trim().lowercase()
                        val displayName = mObj.get("displayName")?.asString ?: cleanId
                        val description = mObj.get("description")?.asString ?: ""
                        val inputTokenLimit = mObj.get("inputTokenLimit")?.asInt ?: 32768
                        val outputTokenLimit = mObj.get("outputTokenLimit")?.asInt ?: 4096
                        val methods = mObj.getAsJsonArray("supportedGenerationMethods")

                        var supportsText = false
                        if (methods != null) {
                            for (method in methods) {
                                if (method.asString == "generateContent") {
                                    supportsText = true
                                    break
                                }
                            }
                        }

                        if (supportsText && ModelFilter.isAllowedTextModel(cleanId, displayName)) {
                            result.add(
                                DiscoveredModel(
                                    id = cleanId,
                                    displayName = displayName,
                                    description = description,
                                    inputTokenLimit = inputTokenLimit,
                                    outputTokenLimit = outputTokenLimit,
                                    supportsTextGeneration = true
                                )
                            )
                        }
                    }
                    return@withContext GeminiApiResult.Success(result)
                } else if (response.code == 401 || response.code == 403) {
                    return@withContext GeminiApiResult.InvalidKey("Invalid API Key when listing models")
                } else if (response.code == 429) {
                    return@withContext GeminiApiResult.RateLimited("Rate limit hit during list models")
                } else {
                    return@withContext GeminiApiResult.TransientError("Failed to list models (HTTP ${response.code})")
                }
            }
        } catch (e: Exception) {
            return@withContext GeminiApiResult.TransientError("Network error listing models: ${e.localizedMessage}")
        }
    }

    private fun parseErrorMessage(jsonBody: String): String? {
        return try {
            val root = gson.fromJson(jsonBody, JsonObject::class.java)
            val errorObj = root.getAsJsonObject("error")
            errorObj?.get("message")?.asString
        } catch (_: Exception) {
            null
        }
    }

    private fun parseRetryDelay(jsonBody: String): Long? {
        return try {
            val root = gson.fromJson(jsonBody, JsonObject::class.java)
            val error = root.getAsJsonObject("error") ?: return null
            val details = error.getAsJsonArray("details") ?: return null
            for (detail in details) {
                val obj = detail.asJsonObject
                if (obj.has("retryDelay")) {
                    val raw = obj.get("retryDelay").asString
                    val clean = raw.removeSuffix("s").trim()
                    return clean.toDoubleOrNull()?.toLong()
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
