package com.gemini.summarizer

import com.gemini.summarizer.core.database.entity.ModelFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFilterTest {

    @Test
    fun testValidGeminiTextModelsAllowed() {
        assertTrue(ModelFilter.isAllowedTextModel("gemini-3.8-flash", "Gemini 3.8 Flash"))
        assertTrue(ModelFilter.isAllowedTextModel("gemini-3.7-flash", "Gemini 3.7 Flash"))
        assertTrue(ModelFilter.isAllowedTextModel("gemini-3.1-pro-preview", "Gemini 3.1 Pro Preview"))
        assertTrue(ModelFilter.isAllowedTextModel("gemini-3-flash-preview", "Gemini 3 Flash Preview"))
        assertTrue(ModelFilter.isAllowedTextModel("gemini-flash-lite-latest", "Gemini Flash-Lite Latest"))
        assertTrue(ModelFilter.isAllowedTextModel("gemini-3.5-flash-lite", "Gemini 3.5 Flash-Lite"))
        assertTrue(ModelFilter.isAllowedTextModel("gemini-4-argon", "Gemini 4 Argon"))
    }

    @Test
    fun testNonTextAndIrrelevantModelsExcluded() {
        // Robotics / Omni / Nano / Banana / Coding agent
        assertFalse(ModelFilter.isAllowedTextModel("antigravity-preview-09-2026", "Antigravity Agent"))
        assertFalse(ModelFilter.isAllowedTextModel("deep-research-pro-preview", "Deep Research Pro"))
        assertFalse(ModelFilter.isAllowedTextModel("gemma-4-26b-a4b-it", "Gemma 4 26B"))
        assertFalse(ModelFilter.isAllowedTextModel("gemini-robotics-er", "Gemini Robotics"))
        assertFalse(ModelFilter.isAllowedTextModel("nano-banana-exp", "Nano Banana"))
        assertFalse(ModelFilter.isAllowedTextModel("gemini-omni-live", "Gemini Omni Live"))
        assertFalse(ModelFilter.isAllowedTextModel("tts-1", "Text To Speech"))
        assertFalse(ModelFilter.isAllowedTextModel("whisper-large", "Whisper"))
        assertFalse(ModelFilter.isAllowedTextModel("imagen-3", "Imagen 3"))
        assertFalse(ModelFilter.isAllowedTextModel("text-embedding-004", "Embedding"))
    }

    @Test
    fun testDeprecatedModelsExcluded() {
        assertFalse(ModelFilter.isAllowedTextModel("gemini-2.5-flash", "Gemini 2.5 Flash"))
        assertFalse(ModelFilter.isAllowedTextModel("gemini-2.5-pro", "Gemini 2.5 Pro"))
        assertFalse(ModelFilter.isAllowedTextModel("gemini-1.0-pro", "Gemini 1.0 Pro"))
        assertFalse(ModelFilter.isAllowedTextModel("gemini-1.5-flash-001", "Gemini 1.5 Flash 001"))
        assertFalse(ModelFilter.isAllowedTextModel("gemini-1.5-pro-002", "Gemini 1.5 Pro 002"))
    }
}

