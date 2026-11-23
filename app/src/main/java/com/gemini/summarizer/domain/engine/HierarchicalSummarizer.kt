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
        // Optimized for Cloudflare Workers AI edge model context & latency budgets.
        // Single pass for documents up to 3,500 words (~10-14 standard pages).
        // Larger documents transition seamlessly into rolling-context hierarchical summarization.
        const val MAX_WORDS_PER_CHUNK = 3500
        const val CHUNK_OVERLAP_WORDS = 250
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
            onProgressUpdate("Executing single-pass comprehensive summarization...")
            val prompt = """
                Source text:
                $trimmed
                
                Please produce a comprehensive, beautifully structured, and cohesive summary of the text above following the specified instructions. Preserve all critical arguments, key facts, data points, and takeaways with clear sectioning.
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

        // 2. Multi-stage hierarchical summarization with rolling context & semantic overlap
        val chunks = splitTextIntoSemanticChunks(trimmed, MAX_WORDS_PER_CHUNK, CHUNK_OVERLAP_WORDS)
        val intermediateSummaries = mutableListOf<String>()
        var rollingContext = ""
        var lastModelUsed = ""
        var lastAccountUsed = ""
        var lastProjectId = ""

        for ((index, chunk) in chunks.withIndex()) {
            val sectionNum = index + 1
            val totalSections = chunks.size
            onProgressUpdate("Analyzing section $sectionNum of $totalSections with contextual continuity...")

            val sectionPrompt = if (rollingContext.isBlank()) {
                """
                Section $sectionNum of $totalSections (Initial Section):
                
                $chunk
                
                Extract all essential arguments, critical facts, data points, and context from this section.
                Highlight key themes and conclusions clearly.
                """.trimIndent()
            } else {
                """
                Section $sectionNum of $totalSections:
                
                [Preceding Context / پیش‌زمینه و خلاصه بخش‌های پیشین]:
                $rollingContext
                
                [Current Section Content / متن بخش فعلی]:
                $chunk
                
                Analyze and summarize this current section in direct continuity with the preceding context.
                Detail how this section advances the earlier arguments, identify new data points or conclusions, and maintain the narrative thread.
                """.trimIndent()
            }

            val chunkResult = rotationEngine.executeWithAutoRotation(sectionPrompt, systemInstruction, null, onProgressUpdate)
            when (chunkResult) {
                is RotationExecutionResult.Success -> {
                    val sectionSummary = chunkResult.outputText.trim()
                    intermediateSummaries.add("### Section $sectionNum Summary\n$sectionSummary")
                    lastModelUsed = chunkResult.modelUsed
                    lastAccountUsed = chunkResult.accountName
                    lastProjectId = chunkResult.projectId

                    // Update rolling context for subsequent chunks (condensed to ~150-200 words to avoid token bloat)
                    rollingContext = extractKeyTakeaways(sectionSummary, maxWords = 180)
                }
                is RotationExecutionResult.AllExhausted -> return Result.failure(Exception(chunkResult.message))
                is RotationExecutionResult.Failure -> return Result.failure(Exception(chunkResult.message))
            }
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
            
            Synthesize these into one cohesive, beautifully organized, and flowing comprehensive final summary.
            
            CRITICAL REQUIREMENTS:
            1. Maintain an unbroken logical thread (نخ تسبیح و پیوستگی یکپارچه) from introduction, through structured deep analysis, to final conclusions.
            2. Do NOT simply concatenate section bullet points. Eliminate cross-section redundancies, resolve narrative references, and produce a harmonious reading flow.
            3. Structure with clear Markdown headers:
               - Executive Overview / پیام اصلی و خلاصه اجرایی
               - Core Analysis & Detailed Insights / تحلیل جامع بخش‌ها و استدلال‌های کلیدی
               - Evidence, Data & Specific Findings / شواهد، داده‌ها و مصادیق مهم
               - Final Takeaways & Synthesis / جمع‌بندی راهبردی و نتیجه‌گیری پایانی
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

    private fun extractKeyTakeaways(text: String, maxWords: Int): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val sb = StringBuilder()
        var words = 0

        for (line in lines) {
            val lineWords = line.split("\\s+".toRegex()).size
            if (words + lineWords > maxWords && sb.isNotEmpty()) break
            sb.append(line).append("\n")
            words += lineWords
        }
        return sb.toString().trim()
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

            // If adding this paragraph exceeds chunk size and we already have content
            if (currentWords + pWords > maxWordsPerChunk && currentChunkParas.isNotEmpty()) {
                val chunkContent = currentChunkParas.joinToString("\n\n")
                chunks.add(chunkContent)

                // Compute overlap paragraphs from the tail of current chunk
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

            // If a single paragraph is exceptionally huge, split it on sentence boundaries
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
