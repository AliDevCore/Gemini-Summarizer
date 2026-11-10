package com.gemini.summarizer

import com.gemini.summarizer.core.util.MarkdownRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {

    @Test
    fun testBulletPointConversion() {
        val input = "* Item one\n- Item two\nNormal text"
        val parsed = MarkdownRenderer.parse(input)
        assertTrue(parsed.text.contains("• Item one"))
        assertTrue(parsed.text.contains("• Item two"))
        assertTrue(parsed.text.contains("Normal text"))
    }

    @Test
    fun testHeaderStripping() {
        val input = "# Main Title\n## Sub Title\n### Section"
        val parsed = MarkdownRenderer.parse(input)
        assertEquals("Main Title\nSub Title\nSection", parsed.text)
    }

    @Test
    fun testBoldAndItalicStrippingInText() {
        val input = "This is **bold** and this is *italic* and `code`"
        val parsed = MarkdownRenderer.parse(input)
        assertEquals("This is bold and this is italic and code", parsed.text)
    }
}
