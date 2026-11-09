package com.gemini.summarizer

import com.gemini.summarizer.domain.model.SummaryConfig
import org.junit.Assert.assertTrue
import org.junit.Test

class SummaryConfigTest {

    @Test
    fun testBuildSystemInstructionPersianLanguage() {
        val config = SummaryConfig(
            targetLanguage = "Persian",
            summaryLength = "Short",
            summaryType = "Bullet Points"
        )
        val instruction = config.buildSystemInstruction()
        assertTrue(instruction.contains("Persian (Farsi)"))
        assertTrue(instruction.contains("bullet points"))
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
}
