package com.gemini.summarizer

import com.gemini.summarizer.core.database.entity.ModelFilter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFilterTest {

    @Test
    fun testValidCloudflareGemmaModelAllowed() {
        assertTrue(ModelFilter.isAllowedTextModel("@cf/google/gemma-4-26b-a4b-it", "Gemma 4 26B (Cloudflare)"))
        assertTrue(ModelFilter.isAllowedTextModel("@cf/google/gemma-4-26b-a4b-it"))
    }

    @Test
    fun testNonTextAndIrrelevantModelsExcluded() {
        assertFalse(ModelFilter.isAllowedTextModel("antigravity-preview-09-2026", "Antigravity Agent"))
        assertFalse(ModelFilter.isAllowedTextModel("deep-research-pro-preview", "Deep Research Pro"))
        assertFalse(ModelFilter.isAllowedTextModel("gemini-robotics-er", "Gemini Robotics"))
        assertFalse(ModelFilter.isAllowedTextModel("nano-banana-exp", "Nano Banana"))
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
