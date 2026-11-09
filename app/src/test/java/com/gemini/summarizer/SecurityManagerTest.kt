package com.gemini.summarizer

import com.gemini.summarizer.core.security.SecurityManager
import org.junit.Assert.assertEquals
import org.junit.Test

class SecurityManagerTest {

    @Test
    fun testMaskApiKey() {
        val fullKey = "AIzaSyD-sample1234567890abcdef"
        val masked = SecurityManager.maskApiKey(fullKey)
        assertEquals("AIzaSy...cdef", masked)
    }

    @Test
    fun testMaskShortApiKey() {
        val shortKey = "short"
        val masked = SecurityManager.maskApiKey(shortKey)
        assertEquals("********", masked)
    }
}
