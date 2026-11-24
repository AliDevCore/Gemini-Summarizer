package com.gemini.summarizer

import com.gemini.summarizer.core.media.AppMediaType
import com.gemini.summarizer.core.media.MediaHelper
import com.gemini.summarizer.core.network.CloudflareWhisperClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaHelperTest {

    @Test
    fun testAudioExtensionRecognition() {
        assertTrue(MediaHelper.isAudioExtension("mp3"))
        assertTrue(MediaHelper.isAudioExtension("wav"))
        assertTrue(MediaHelper.isAudioExtension("m4a"))
        assertTrue(MediaHelper.isAudioExtension("aac"))
        assertTrue(MediaHelper.isAudioExtension("ogg"))
        assertTrue(MediaHelper.isAudioExtension("flac"))
        assertFalse(MediaHelper.isAudioExtension("pdf"))
        assertFalse(MediaHelper.isAudioExtension("mp4"))
    }

    @Test
    fun testVideoExtensionRecognition() {
        assertTrue(MediaHelper.isVideoExtension("mp4"))
        assertTrue(MediaHelper.isVideoExtension("mkv"))
        assertTrue(MediaHelper.isVideoExtension("webm"))
        assertTrue(MediaHelper.isVideoExtension("mov"))
        assertFalse(MediaHelper.isVideoExtension("mp3"))
        assertFalse(MediaHelper.isVideoExtension("docx"))
    }

    @Test
    fun testMediaTypeFromExtension() {
        assertEquals(AppMediaType.AUDIO, MediaHelper.getMediaTypeFromExtension("mp3"))
        assertEquals(AppMediaType.VIDEO, MediaHelper.getMediaTypeFromExtension("mp4"))
        assertEquals(AppMediaType.DOCUMENT, MediaHelper.getMediaTypeFromExtension("pdf"))
        assertEquals(AppMediaType.DOCUMENT, MediaHelper.getMediaTypeFromExtension("docx"))
        assertEquals(AppMediaType.NONE, MediaHelper.getMediaTypeFromExtension("unknown"))
    }

    @Test
    fun testMediaUrlCandidateDetection() {
        assertTrue(MediaHelper.isMediaUrlCandidate("https://example.com/audio/podcast.mp3"))
        assertTrue(MediaHelper.isMediaUrlCandidate("https://example.com/video/lecture.mp4?token=123"))
        assertTrue(MediaHelper.isMediaUrlCandidate("http://cdn.site.com/track.m4a#t=10"))
        assertFalse(MediaHelper.isMediaUrlCandidate("https://example.com/article.html"))
        assertFalse(MediaHelper.isMediaUrlCandidate("https://example.com/document.pdf"))
        assertFalse(MediaHelper.isMediaUrlCandidate("just plain text"))
    }

    @Test
    fun testWhisperLanguageMapping() {
        assertEquals("fa", CloudflareWhisperClient.mapLanguageCode("Persian"))
        assertEquals("fa", CloudflareWhisperClient.mapLanguageCode("farsi"))
        assertEquals("fa", CloudflareWhisperClient.mapLanguageCode("fa"))
        assertEquals("en", CloudflareWhisperClient.mapLanguageCode("English"))
        assertEquals("ar", CloudflareWhisperClient.mapLanguageCode("Arabic"))
        assertEquals("tr", CloudflareWhisperClient.mapLanguageCode("Turkish"))
        assertEquals("auto", CloudflareWhisperClient.mapLanguageCode("Auto"))
    }

    @Test
    fun testFormatDuration() {
        assertEquals("01:30", MediaHelper.formatDuration(90f))
        assertEquals("05:00", MediaHelper.formatDuration(300f))
        assertEquals("01:02:05", MediaHelper.formatDuration(3725f))
    }

    @Test
    fun testExtractExtensionFromUrl() {
        assertEquals("mp3", MediaHelper.extractExtensionFromUrl("https://example.com/audio/podcast.mp3?token=123"))
        assertEquals("mp4", MediaHelper.extractExtensionFromUrl("https://example.com/video/lecture.mp4#t=10"))
        assertEquals("wav", MediaHelper.extractExtensionFromUrl("http://cdn.site.com/track.wav"))
        assertEquals("mp3", MediaHelper.extractExtensionFromUrl("https://example.com/stream-no-extension"))
    }
}
