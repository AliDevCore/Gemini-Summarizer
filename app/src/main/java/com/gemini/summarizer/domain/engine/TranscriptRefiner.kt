package com.gemini.summarizer.domain.engine

import com.gemini.summarizer.core.database.AppDatabase
import com.gemini.summarizer.core.database.entity.QuotaStateEntity
import com.gemini.summarizer.core.network.CloudflareWhisperClient
import com.gemini.summarizer.core.network.GeminiApiClient
import com.gemini.summarizer.core.network.GeminiApiResult
import com.gemini.summarizer.core.security.SecurityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

data class TranscriptRefineResult(
    val text: String,
    val accountsUsed: List<String>,
    val batchesCount: Int
)

/**
 * Post-processes raw Whisper STT transcripts using Google Gemma 4 26B on Cloudflare Workers AI.
 * Corrects STT spelling/homophone mistakes, fixes broken word boundaries and punctuation,
 * adds half-spaces (for Persian), and structures natural paragraphs without omitting or summarizing content.
 */
class TranscriptRefiner(
    private val database: AppDatabase,
    private val securityManager: SecurityManager,
    private val apiClient: GeminiApiClient = GeminiApiClient()
) {
    companion object {
        const val REFINER_MODEL = "@cf/google/gemma-4-26b-a4b-it"
        private const val MAX_WORDS_PER_BATCH = 1400
        private const val MAX_RETRY_BACKOFF_MS = 10_000L
        private const val TEMPERATURE = 0.1
        private val roundRobinCounter = AtomicInteger(0)

        // Matches Persian-specific letters (pe, che, zhe, gaf, farsi yeh, keheh) in ASCII escape form
        private val PERSIAN_SCRIPT_REGEX = Regex("[\\u067E\\u0686\\u0698\\u06AF\\u06CC\\u06A9]")
        private val ARABIC_SCRIPT_REGEX = Regex("[\\u0600-\\u06FF]")
    }

    suspend fun refineTranscript(
        rawTranscript: String,
        targetLanguage: String = "auto",
        onProgressUpdate: ((String) -> Unit)? = null
    ): TranscriptRefineResult = withContext(Dispatchers.IO) {
        val trimmed = rawTranscript.trim()
        if (trimmed.isBlank()) {
            return@withContext TranscriptRefineResult(trimmed, emptyList(), 0)
        }

        val resolvedLang = resolveLanguageCode(targetLanguage, trimmed)
        val systemInstruction = buildSystemPrompt(resolvedLang)
        val batches = splitIntoWordBatches(trimmed, MAX_WORDS_PER_BATCH)
        val totalBatches = batches.size

        val validKeysCount = database.apiKeyDao().getAllValidKeys().size
        if (validKeysCount == 0) {
            return@withContext TranscriptRefineResult(trimmed, emptyList(), totalBatches)
        }

        val parallelLimit = validKeysCount.coerceAtLeast(1).coerceAtMost(totalBatches)
        onProgressUpdate?.invoke(
            "Refining speech phrasing & spelling with Gemma 4 ($totalBatches ${if (totalBatches == 1) "part" else "parts"})..."
        )

        val semaphore = Semaphore(parallelLimit)
        val completedCounter = AtomicInteger(0)

        val batchResults = coroutineScope {
            batches.mapIndexed { idx, batchText ->
                async {
                    semaphore.withPermit {
                        val refined = refineSingleBatch(
                            batchText = batchText,
                            systemInstruction = systemInstruction,
                            preferredSlot = idx,
                            onProgressUpdate = onProgressUpdate
                        )
                        val done = completedCounter.incrementAndGet()
                        if (totalBatches > 1) {
                            onProgressUpdate?.invoke(
                                "Refined transcript part ${idx + 1}/$totalBatches ($done/$totalBatches complete) with Gemma 4."
                            )
                        }
                        Triple(idx, refined.first, refined.second)
                    }
                }
            }.awaitAll()
        }

        val ordered = batchResults.sortedBy { it.first }
        val finalText = ordered.joinToString("\n\n") { it.second }
        val usedAccounts = ordered.mapNotNull { it.third }.distinct()

        TranscriptRefineResult(
            text = finalText,
            accountsUsed = usedAccounts,
            batchesCount = totalBatches
        )
    }

    private suspend fun refineSingleBatch(
        batchText: String,
        systemInstruction: String,
        preferredSlot: Int,
        onProgressUpdate: ((String) -> Unit)?
    ): Pair<String, String?> {
        val now = System.currentTimeMillis()
        database.quotaStateDao().clearExpiredCooldowns(now)

        val validKeys = database.apiKeyDao().getAllValidKeys()
        if (validKeys.isEmpty()) {
            return Pair(batchText, null)
        }

        val startIdx = Math.floorMod(preferredSlot + roundRobinCounter.get(), validKeys.size)
        val orderedKeys = List(validKeys.size) { i -> validKeys[(startIdx + i) % validKeys.size] }
        val userPrompt = "Proofread and refine the following verbatim speech transcript:\n\n$batchText"

        for (keyEntity in orderedKeys) {
            val rawApiKey = securityManager.getApiKey(keyEntity.keyIdentifier) ?: continue
            val accountId = keyEntity.projectId.trim()
            if (accountId.isBlank()) continue

            val quotaState = database.quotaStateDao().getQuotaState(accountId, REFINER_MODEL)
            if (quotaState != null && quotaState.isRateLimited && quotaState.cooldownUntilTimestamp > System.currentTimeMillis()) {
                continue
            }

            val account = database.accountDao().getAccountById(keyEntity.accountId)
            val accountDisplayName = account?.name ?: "Account #${keyEntity.id}"

            var retryCount = 0
            val maxRetries = if (validKeys.size > 1) 1 else 2
            var callResult: GeminiApiResult<String>

            while (true) {
                callResult = apiClient.generateContent(
                    modelId = REFINER_MODEL,
                    accountId = accountId,
                    apiToken = rawApiKey,
                    prompt = userPrompt,
                    systemInstruction = systemInstruction,
                    temperature = TEMPERATURE
                )

                if (callResult is GeminiApiResult.TransientError && retryCount < maxRetries) {
                    retryCount++
                    val backoff = (1000L * (1 shl retryCount)).coerceAtMost(MAX_RETRY_BACKOFF_MS)
                    val waitSec = String.format(Locale.US, "%.0f", backoff / 1000.0)
                    onProgressUpdate?.invoke("Network lag during Gemma 4 refinement on $accountDisplayName. Retrying in ${waitSec}s...")
                    delay(backoff)
                } else {
                    break
                }
            }

            when (callResult) {
                is GeminiApiResult.Success -> {
                    database.apiKeyDao().updateLastUsed(keyEntity.id, System.currentTimeMillis())
                    val cleaned = cleanRefinedOutput(callResult.data)
                    // Ensure Gemma returned complete faithful text rather than truncating/refusing
                    val origWords = countWords(batchText)
                    val refinedWords = countWords(cleaned)
                    if (cleaned.isNotBlank() && (origWords < 20 || refinedWords >= (origWords * 0.45).toInt())) {
                        return Pair(cleaned, accountDisplayName)
                    }
                    return Pair(batchText, accountDisplayName)
                }
                is GeminiApiResult.RateLimited -> {
                    val cooldownSec = callResult.retryAfterSeconds ?: 60L
                    val cooldownUntil = System.currentTimeMillis() + (cooldownSec * 1000L)
                    database.quotaStateDao().insertOrUpdate(
                        QuotaStateEntity(
                            projectId = accountId,
                            modelId = REFINER_MODEL,
                            isRateLimited = true,
                            cooldownUntilTimestamp = cooldownUntil,
                            failureReason = callResult.message
                        )
                    )
                }
                is GeminiApiResult.InvalidKey -> {
                    database.apiKeyDao().setKeyStatus(keyEntity.id, isInvalid = true, callResult.message)
                }
                is GeminiApiResult.ModelUnavailable,
                is GeminiApiResult.TransientError,
                is GeminiApiResult.RequestError -> {
                    // Rotate cleanly to next available account
                }
            }
        }

        // Fallback gracefully to original Whisper batch text if refinement accounts were unavailable
        onProgressUpdate?.invoke("Skipping refinement on current batch; keeping raw Whisper text.")
        return Pair(batchText, null)
    }

    internal fun resolveLanguageCode(languageSetting: String, sampleText: String): String {
        val mapped = CloudflareWhisperClient.mapLanguageCode(languageSetting)
        if (mapped != "auto") return mapped

        val sample = sampleText.take(600)
        return when {
            PERSIAN_SCRIPT_REGEX.containsMatchIn(sample) -> "fa"
            ARABIC_SCRIPT_REGEX.containsMatchIn(sample) -> "ar"
            else -> "en"
        }
    }

    private fun buildSystemPrompt(langCode: String): String {
        return when (langCode) {
            "fa" -> """
                You are an expert Persian speech-transcript editor and proofreader.
                Clean up and refine the provided raw Persian speech-to-text transcript according to these strict rules:

                1. Spelling & STT Homophones: Correct speech recognition spelling errors, merged words (e.g. broken 'be name' prefixes or joined prepositions), and homophone confusions accurately from context.
                2. Persian Orthography & Half-Spaces: Apply proper Persian zero-width non-joiner half-spaces (ZWNJ) in verb prefixes/suffixes and plural forms, and use proper Persian punctuation (commas, periods, question marks, quotation marks).
                3. Fluent Phrasing Without Altering Tone: Fix broken or run-on Whisper phrasing so sentences read cleanly and logically, while preserving the speaker's original tone (formal or colloquial conversational vocabulary). Never replace natural spoken words with stiff, artificial phrasing, and NEVER insert non-Persian foreign letters.
                4. Paragraph Structure: Organize the refined text into clean, readable paragraphs based on topic shifts and pauses.
                5. Strict Verbatim Fidelity:
                   - Do NOT summarize, condense, or omit any spoken sentences, facts, or details.
                   - Do NOT translate or add your own commentary, greetings, or explanations.
                   - Output ONLY the complete, refined Persian transcript text directly.
            """.trimIndent()

            "ar" -> """
                You are an expert Arabic speech-transcript editor and proofreader.
                Refine the provided raw Arabic speech-to-text transcript:

                1. Fix STT spelling typos, hamza placement, taa marbuta, and grammatical errors from context.
                2. Apply accurate Arabic punctuation and organize the text into logical, readable paragraphs.
                3. Preserve the full spoken content, meaning, and tone strictly without summarizing, omitting details, or adding commentary.
                4. Output ONLY the refined Arabic transcript directly.
            """.trimIndent()

            else -> """
                You are an expert speech-transcript editor and proofreader.
                Clean up and refine the provided raw speech-to-text transcript:

                1. Spelling & Homophones: Fix speech recognition typos, homophone errors, and grammatical slips using context.
                2. Punctuation & Capitalization: Add proper punctuation, sentence boundaries, capitalization, and natural paragraph breaks.
                3. Flow & Fidelity: Smooth out awkward Whisper recognition artifacts while strictly preserving the speaker's tone, phrasing, and complete content.
                4. Strict Constraints: Do NOT summarize, paraphrase away details, omit sentences, or add commentary/headers. Output ONLY the refined transcript directly.
            """.trimIndent()
        }
    }

    internal fun splitIntoWordBatches(text: String, maxWords: Int): List<String> {
        val paragraphs = text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val batches = mutableListOf<String>()
        val current = StringBuilder()
        var currentWords = 0

        fun flushCurrent() {
            val s = current.toString().trim()
            if (s.isNotEmpty()) {
                batches.add(s)
            }
            current.clear()
            currentWords = 0
        }

        for (para in paragraphs) {
            val paraWords = countWords(para)
            if (paraWords <= maxWords) {
                if (currentWords + paraWords > maxWords && currentWords > 0) {
                    flushCurrent()
                }
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(para)
                currentWords += paraWords
            } else {
                // Split oversized single paragraph along sentence/word boundaries
                if (currentWords > 0) flushCurrent()
                val words = para.split(Regex("\\s+"))
                var idx = 0
                while (idx < words.size) {
                    val end = Math.min(idx + maxWords, words.size)
                    batches.add(words.subList(idx, end).joinToString(" "))
                    idx = end
                }
            }
        }
        flushCurrent()

        return if (batches.isEmpty()) listOf(text) else batches
    }

    private fun countWords(text: String): Int {
        val t = text.trim()
        return if (t.isEmpty()) 0 else t.split(Regex("\\s+")).size
    }

    private fun cleanRefinedOutput(raw: String): String {
        return raw.trim()
            .replace(Regex("^```[a-zA-Z]*\\s*\\n"), "")
            .replace(Regex("\\n```\\s*$"), "")
            .trim()
    }
}
