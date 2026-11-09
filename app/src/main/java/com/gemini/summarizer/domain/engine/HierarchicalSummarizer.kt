package com.gemini.summarizer.domain.engine

import com.gemini.summarizer.core.network.GeminiFileData
import com.gemini.summarizer.domain.model.SummaryConfig

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
        private const val MAX_WORDS_PER_CHUNK = 2500
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

        onProgressUpdate("Transmitting document to Gemini multimodal engine...")

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
                        inputWords = 0, // Multimodal document
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

        // Single pass if within chunk threshold
        if (inputWordCount <= MAX_WORDS_PER_CHUNK) {
            onProgressUpdate("Executing single-pass summarization...")
            val prompt = """
                Source text:
                $trimmed
                
                Please summarize the above text according to instructions.
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

        // Multi-stage hierarchical summarization
        val chunks = splitTextIntoChunks(trimmed, MAX_WORDS_PER_CHUNK)
        val intermediateSummaries = mutableListOf<String>()
        var lastModelUsed = ""
        var lastAccountUsed = ""
        var lastProjectId = ""

        for ((index, chunk) in chunks.withIndex()) {
            onProgressUpdate("Summarizing section ${index + 1} of ${chunks.size}...")
            val sectionPrompt = """
                Section ${index + 1} of ${chunks.size}:
                $chunk
                
                Extract all essential arguments, critical facts, data points, and context from this section.
            """.trimIndent()

            val chunkResult = rotationEngine.executeWithAutoRotation(sectionPrompt, systemInstruction, null, onProgressUpdate)
            when (chunkResult) {
                is RotationExecutionResult.Success -> {
                    intermediateSummaries.add("--- Section ${index + 1} Summary ---\n" + chunkResult.outputText)
                    lastModelUsed = chunkResult.modelUsed
                    lastAccountUsed = chunkResult.accountName
                    lastProjectId = chunkResult.projectId
                }
                is RotationExecutionResult.AllExhausted -> return Result.failure(Exception(chunkResult.message))
                is RotationExecutionResult.Failure -> return Result.failure(Exception(chunkResult.message))
            }
        }

        // Final hierarchical synthesis
        onProgressUpdate("Synthesizing final comprehensive summary from all sections...")
        val combinedSections = intermediateSummaries.joinToString("\n\n")
        val finalSynthesisPrompt = """
            Below are structured summaries of all consecutive sections from a large document:
            
            $combinedSections
            
            Synthesize these section summaries into one cohesive, beautifully organized, comprehensive final summary that captures the full narrative, all critical data, and key conclusions without repetition.
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
                        stepsCount = chunks.size + 1
                    )
                )
            }
            is RotationExecutionResult.AllExhausted -> Result.failure(Exception(finalResult.message))
            is RotationExecutionResult.Failure -> Result.failure(Exception(finalResult.message))
        }
    }

    private fun splitTextIntoChunks(text: String, maxWordsPerChunk: Int): List<String> {
        val paragraphs = text.split("\n\n")
        val chunks = mutableListOf<String>()
        var currentChunk = StringBuilder()
        var currentWords = 0

        for (paragraph in paragraphs) {
            val pWords = paragraph.trim().split("\\s+".toRegex()).size
            if (currentWords + pWords > maxWordsPerChunk && currentChunk.isNotEmpty()) {
                chunks.add(currentChunk.toString().trim())
                currentChunk = StringBuilder()
                currentWords = 0
            }

            if (pWords > maxWordsPerChunk) {
                val sentences = paragraph.split(Regex("(?<=[.!?\\n])\\s+"))
                for (sentence in sentences) {
                    val sWords = sentence.trim().split("\\s+".toRegex()).size
                    if (currentWords + sWords > maxWordsPerChunk && currentChunk.isNotEmpty()) {
                        chunks.add(currentChunk.toString().trim())
                        currentChunk = StringBuilder()
                        currentWords = 0
                    }
                    currentChunk.append(sentence).append(" ")
                    currentWords += sWords
                }
            } else {
                currentChunk.append(paragraph).append("\n\n")
                currentWords += pWords
            }
        }

        if (currentChunk.isNotBlank()) {
            chunks.add(currentChunk.toString().trim())
        }

        return if (chunks.isEmpty()) listOf(text) else chunks
    }
}
