package com.gemini.summarizer

import com.gemini.summarizer.domain.model.SummaryConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryConfigTest {

    @Test
    fun testBuildSystemInstructionPersianLanguageAndDefaultPrompt() {
        val config = SummaryConfig(
            targetLanguage = "Persian"
        )
        val instruction = config.buildSystemInstruction()
        assertTrue(instruction.contains("Persian (Farsi)"))
        assertTrue(instruction.contains("Organize the content into a clear, well-structured format."))
        assertTrue(instruction.contains("**Bold** key details"))
    }

    @Test
    fun testBuildSystemInstructionConstraints() {
        val config = SummaryConfig(
            targetLanguage = "English",
            preserveNumbersAndDates = true,
            preserveTechnicalTerms = true
        )
        val instruction = config.buildSystemInstruction()
        assertTrue(instruction.contains("Accurately preserve all specific numbers"))
        assertTrue(instruction.contains("Retain precise technical terms"))
    }

    @Test
    fun testBuildSystemInstructionWithCustomPrompt() {
        val config = SummaryConfig(
            targetLanguage = "English",
            customPrompt = "Focus exclusively on financial statistics and revenue."
        )
        val instruction = config.buildSystemInstruction()
        assertTrue(instruction.contains("Focus exclusively on financial statistics and revenue."))
    }

    @Test
    fun testBlankCustomPromptFallsBackToDefault() {
        val config = SummaryConfig(
            targetLanguage = "English",
            customPrompt = "   "
        )
        val instruction = config.buildSystemInstruction()
        assertTrue(instruction.contains(SummaryConfig.DEFAULT_PROMPT))
    }
}

