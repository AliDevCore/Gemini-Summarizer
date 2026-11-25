package com.gemini.summarizer.domain.engine

import com.gemini.summarizer.core.network.GeminiFileData
import com.gemini.summarizer.domain.model.SummaryConfig
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

data class SummarizationFinalResult(
    val summaryText: String,
    val modelUsed: String,
    val accountUsed: String,
    val projectId: String,
    val inputWords: Int,
    val outputWords: Int,
    val stepsCount: Int
)

class HierarchicalSummarizer(
    private val rotationEngine: AutoRotationEngine
) {

    companion object {
        // Single pass for documents up to 2,200 words (~8-10 standard pages) for fast 15s edge execution.
        // Larger documents transition seamlessly into dynamic multi-account parallel chunk summarization.
        const val MAX_WORDS_PER_CHUNK = 2200
        const val CHUNK_OVERLAP_WORDS = 200
        const val MAX_SYNTHESIS_INPUT_WORDS = 4000
    }

    suspend fun summarizeFile(
        fileData: GeminiFileData,
        fileName: String,
        userInstructions: String?,
        config: SummaryConfig,
        onProgressUpdate: (String) -> Unit
    ): Result<SummarizationFinalResult> {
        val systemInstruction = config.buildSystemInstruction()
        val customNotes = if (!userInstructions.isNullOrBlank()) "\nAdditional User Instructions: $userInstructions" else ""

        val prompt = """
            Document Name: $fileName
            Please read and comprehensively summarize the attached document following the formatting and style rules.$customNotes
        """.trimIndent()

        onProgressUpdate("Transmitting document to AI engine...")

        val rotationResult = rotationEngine.executeWithAutoRotation(
            prompt = prompt,
            systemInstruction = systemInstruction,
            fileData = fileData,
            onProgressUpdate = onProgressUpdate
        )

        return when (rotationResult) {
            is RotationExecutionResult.Success -> {
                val outWords = rotationResult.outputText.trim().split("\\s+".toRegex()).size
                Result.success(
                    SummarizationFinalResult(
                        summaryText = rotationResult.outputText,
                        modelUsed = rotationResult.modelUsed,
                        accountUsed = rotationResult.accountName,
                        projectId = rotationResult.projectId,
                        inputWords = 0,
                        outputWords = outWords,
                        stepsCount = 1
                    )
                )
            }
            is RotationExecutionResult.AllExhausted -> Result.failure(Exception(rotationResult.message))
            is RotationExecutionResult.Failure -> Result.failure(Exception(rotationResult.message))
        }
    }

    suspend fun summarizeText(
        inputText: String,
        config: SummaryConfig,
        onProgressUpdate: (String) -> Unit
    ): Result<SummarizationFinalResult> {
        val trimmed = inputText.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("Input text cannot be empty"))
        }

        val inputWordList = trimmed.split("\\s+".toRegex())
        val inputWordCount = inputWordList.size
        val systemInstruction = config.buildSystemInstruction()

        // 1. Single pass for texts within the safe Cloudflare edge window
        if (inputWordCount <= MAX_WORDS_PER_CHUNK) {
            onProgressUpdate("Executing single-pass comprehensive summarization ($inputWordCount words)...")
            val prompt = """
                Source text:
                $trimmed
                
                Process and rewrite the text above strictly following the language, instructions, and constraints specified in the system prompt.
            """.trimIndent()

            return when (val rotationResult = rotationEngine.executeWithAutoRotation(prompt, systemInstruction, null, onProgressUpdate)) {
                is RotationExecutionResult.Success -> {
                    val outWords = rotationResult.outputText.trim().split("\\s+".toRegex()).size
                    Result.success(
                        SummarizationFinalResult(
                            summaryText = rotationResult.outputText,
                            modelUsed = rotationResult.modelUsed,
                            accountUsed = rotationResult.accountName,
                            projectId = rotationResult.projectId,
                            inputWords = inputWordCount,
                            outputWords = outWords,
                            stepsCount = 1
                        )
                    )
                }
                is RotationExecutionResult.AllExhausted -> Result.failure(Exception(rotationResult.message))
                is RotationExecutionResult.Failure -> Result.failure(Exception(rotationResult.message))
            }
        }

        // 2. High-speed parallel chunk processing for long documents
        val chunks = splitTextIntoSemanticChunks(trimmed, MAX_WORDS_PER_CHUNK, CHUNK_OVERLAP_WORDS)
        val totalSections = chunks.size
        val parallelLimit = rotationEngine.getActiveKeyCount().coerceAtMost(totalSections)
        onProgressUpdate("Distributing $totalSections sections concurrently across $parallelLimit active Cloudflare accounts...")

        val completedCounter = AtomicInteger(0)
        val semaphore = Semaphore(parallelLimit)
        var lastModelUsed = ""
        var lastAccountUsed = ""
        var lastProjectId = ""

        val chunkResults = try {
            coroutineScope {
                chunks.mapIndexed { index, chunk ->
                    async {
                        semaphore.withPermit {
                            val sectionNum = index + 1
                            val sectionPlacement = when (sectionNum) {
                                1 -> "Introduction & Contextual Opening"
                                totalSections -> "Final Conclusion & Strategic Summary"
                                else -> "Core Analysis & Thematic Progression"
                            }

                            val sectionPrompt = """
                                Document Section $sectionNum of $totalSections ($sectionPlacement):
                                
                                $chunk
                                
                                Instructions:
                                Extract all essential arguments, critical facts, quantitative data, and logical progression from this section.
                                Highlight core themes and findings clearly so they can be synthesized seamlessly into a whole document summary.
                            """.trimIndent()

                            val result = rotationEngine.executeWithAutoRotation(
                                prompt = sectionPrompt,
                                systemInstruction = systemInstruction,
                                fileData = null,
                                onProgressUpdate = onProgressUpdate
                            )

                            when (result) {
                                is RotationExecutionResult.Success -> {
                                    val currentDone = completedCounter.incrementAndGet()
                                    onProgressUpdate("Processed section $currentDone of $totalSections in parallel...")
                                    Triple(sectionNum, result.outputText.trim(), result)
                                }
                                is RotationExecutionResult.AllExhausted -> throw Exception(result.message)
                                is RotationExecutionResult.Failure -> throw Exception(result.message)
                            }
                        }
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }

        // Sort chunks chronologically by their original sequence
        val sortedResults = chunkResults.sortedBy { it.first }
        val intermediateSummaries = sortedResults.map { "### Section ${it.first} Summary\n${it.second}" }
        val lastSuccess = sortedResults.lastOrNull()?.third
        if (lastSuccess != null) {
            lastModelUsed = lastSuccess.modelUsed
            lastAccountUsed = lastSuccess.accountName
            lastProjectId = lastSuccess.projectId
        }

        // 3. Recursive reduction if intermediate summaries exceed the synthesis limit
        var workingSummaries = intermediateSummaries
        var reductionStage = 1
        while (countWordsInList(workingSummaries) > MAX_SYNTHESIS_INPUT_WORDS && workingSummaries.size > 2) {
            onProgressUpdate("Condensing intermediate sections (Stage $reductionStage reduction)...")
            val condensedList = mutableListOf<String>()
            val groupedChunks = workingSummaries.chunked(3)

            for ((grpIdx, group) in groupedChunks.withIndex()) {
                val groupText = group.joinToString("\n\n")
                val groupPrompt = """
                    Consolidate and condense the following sequential section summaries into a concise, unified thematic summary:
                    
                    $groupText
                    
                    Preserve all crucial facts, progression of ideas, and core conclusions without redundancy.
                """.trimIndent()

                when (val grpResult = rotationEngine.executeWithAutoRotation(groupPrompt, systemInstruction, null, onProgressUpdate)) {
                    is RotationExecutionResult.Success -> {
                        condensedList.add("### Thematic Block ${grpIdx + 1}\n${grpResult.outputText.trim()}")
                        lastModelUsed = grpResult.modelUsed
                    }
                    is RotationExecutionResult.AllExhausted -> return Result.failure(Exception(grpResult.message))
                    is RotationExecutionResult.Failure -> return Result.failure(Exception(grpResult.message))
                }
            }
            workingSummaries = condensedList
            reductionStage++
        }

        // 4. Final Holistic Synthesis
        onProgressUpdate("Synthesizing unified, seamless final summary from all sections...")
        val combinedSections = workingSummaries.joinToString("\n\n")
        val finalSynthesisPrompt = """
            Below are structured, chronological summaries covering all consecutive parts of a comprehensive document:
            
            $combinedSections
            
            Synthesize these into one cohesive, beautifully organized final output that strictly follows the instructions in the system prompt.
            
            CRITICAL REQUIREMENTS:
            1. Maintain an unbroken logical thread and seamless coherence across all parts without cross-section redundancies.
            2. Do NOT simply concatenate section summaries; merge related points and resolve narrative references cleanly.
            3. Strictly follow every formatting, structural, and content guideline provided in the Instructions section of the system prompt.
            4. Strictly adhere to the requested language and tone throughout.
        """.trimIndent()

        return when (val finalResult = rotationEngine.executeWithAutoRotation(finalSynthesisPrompt, systemInstruction, null, onProgressUpdate)) {
            is RotationExecutionResult.Success -> {
                val outWords = finalResult.outputText.trim().split("\\s+".toRegex()).size
                Result.success(
                    SummarizationFinalResult(
                        summaryText = finalResult.outputText,
                        modelUsed = finalResult.modelUsed,
                        accountUsed = finalResult.accountName,
                        projectId = finalResult.projectId,
                        inputWords = inputWordCount,
                        outputWords = outWords,
                        stepsCount = chunks.size + reductionStage
                    )
                )
            }
            is RotationExecutionResult.AllExhausted -> Result.failure(Exception(finalResult.message))
            is RotationExecutionResult.Failure -> Result.failure(Exception(finalResult.message))
        }
    }

    private fun countWordsInList(items: List<String>): Int {
        return items.sumOf { it.split("\\s+".toRegex()).size }
    }

    /**
     * Splits text into semantic chunks respecting paragraphs and headings,
     * while maintaining an overlap buffer between consecutive chunks.
     */
    private fun splitTextIntoSemanticChunks(
        text: String,
        maxWordsPerChunk: Int,
        overlapWords: Int
    ): List<String> {
        val paragraphs = text.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (paragraphs.isEmpty()) return listOf(text)

        val chunks = mutableListOf<String>()
        var currentChunkParas = mutableListOf<String>()
        var currentWords = 0

        for (paragraph in paragraphs) {
            val pWords = paragraph.split("\\s+".toRegex()).size

            if (currentWords + pWords > maxWordsPerChunk && currentChunkParas.isNotEmpty()) {
                val chunkContent = currentChunkParas.joinToString("\n\n")
                chunks.add(chunkContent)

                val overlapParas = mutableListOf<String>()
                var collectedOverlapWords = 0
                for (para in currentChunkParas.reversed()) {
                    val wordsInPara = para.split("\\s+".toRegex()).size
                    if (collectedOverlapWords + wordsInPara <= overlapWords || overlapParas.isEmpty()) {
                        overlapParas.add(0, para)
                        collectedOverlapWords += wordsInPara
                    } else {
                        break
                    }
                }

                currentChunkParas = overlapParas.toMutableList()
                currentWords = collectedOverlapWords
            }

            if (pWords > maxWordsPerChunk) {
                val sentences = paragraph.split(Regex("(?<=[.!?\\n])\\s+"))
                for (sentence in sentences) {
                    val sWords = sentence.split("\\s+".toRegex()).size
                    if (currentWords + sWords > maxWordsPerChunk && currentChunkParas.isNotEmpty()) {
                        chunks.add(currentChunkParas.joinToString("\n\n"))
                        currentChunkParas = mutableListOf()
                        currentWords = 0
                    }
                    currentChunkParas.add(sentence)
                    currentWords += sWords
                }
            } else {
                currentChunkParas.add(paragraph)
                currentWords += pWords
            }
        }

        if (currentChunkParas.isNotEmpty()) {
            val lastChunk = currentChunkParas.joinToString("\n\n").trim()
            if (lastChunk.isNotBlank()) {
                chunks.add(lastChunk)
            }
        }

        return if (chunks.isEmpty()) listOf(text) else chunks
    }
}
