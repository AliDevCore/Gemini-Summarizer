package com.gemini.summarizer.core.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class AppMediaType {
    NONE,
    DOCUMENT,
    AUDIO,
    VIDEO
}

data class MediaFileInfo(
    val fileName: String,
    val mediaType: AppMediaType,
    val mimeType: String,
    val durationSeconds: Float = 0f,
    val fileSize: Long = 0L
)

object MediaHelper {

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "wav", "m4a", "aac", "ogg", "flac", "opus", "wma", "amr", "mka"
    )

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "mov", "3gp", "avi", "flv", "wmv", "m4v", "ts"
    )

    private val DOCUMENT_EXTENSIONS = setOf(
        "pdf", "docx", "doc", "txt", "md", "markdown", "csv", "html", "htm", "json"
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun getMediaTypeFromExtension(extension: String): AppMediaType {
        val ext = extension.lowercase(Locale.ROOT).trim()
        return when {
            AUDIO_EXTENSIONS.contains(ext) -> AppMediaType.AUDIO
            VIDEO_EXTENSIONS.contains(ext) -> AppMediaType.VIDEO
            DOCUMENT_EXTENSIONS.contains(ext) -> AppMediaType.DOCUMENT
            else -> AppMediaType.NONE
        }
    }

    fun isMediaExtension(extension: String): Boolean {
        val ext = extension.lowercase(Locale.ROOT).trim()
        return AUDIO_EXTENSIONS.contains(ext) || VIDEO_EXTENSIONS.contains(ext)
    }

    fun isAudioExtension(extension: String): Boolean {
        return AUDIO_EXTENSIONS.contains(extension.lowercase(Locale.ROOT).trim())
    }

    fun isVideoExtension(extension: String): Boolean {
        return VIDEO_EXTENSIONS.contains(extension.lowercase(Locale.ROOT).trim())
    }

    fun isMediaUrlCandidate(url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
            return false
        }
        val cleanPath = trimmed.substringBefore("?").substringBefore("#")
        val ext = cleanPath.substringAfterLast(".", "").lowercase(Locale.ROOT)
        return isMediaExtension(ext)
    }

    fun extractExtensionFromUrl(url: String): String {
        val cleanPath = url.trim().substringBefore("?").substringBefore("#")
        val ext = cleanPath.substringAfterLast(".", "").lowercase(Locale.ROOT)
        return if (ext.isNotBlank() && ext.length in 2..5) ext else "mp3"
    }

    suspend fun probeUrlContentType(url: String): AppMediaType = withContext(Dispatchers.IO) {
        if (isMediaUrlCandidate(url)) {
            val cleanPath = url.trim().substringBefore("?").substringBefore("#")
            val ext = cleanPath.substringAfterLast(".", "").lowercase(Locale.ROOT)
            return@withContext getMediaTypeFromExtension(ext)
        }

        try {
            val request = Request.Builder().url(url).head().build()
            httpClient.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type", "")?.lowercase(Locale.ROOT) ?: ""
                when {
                    contentType.startsWith("audio/") -> AppMediaType.AUDIO
                    contentType.startsWith("video/") -> AppMediaType.VIDEO
                    contentType.contains("application/ogg") -> AppMediaType.AUDIO
                    contentType.contains("application/pdf") -> AppMediaType.DOCUMENT
                    else -> AppMediaType.NONE
                }
            }
        } catch (e: Exception) {
            AppMediaType.NONE
        }
    }

    fun getFileNameFromUri(context: Context, uri: Uri): String {
        var name = "media_file"
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1) {
                    name = it.getString(index)
                }
            }
        }
        return name
    }

    fun getMediaTypeFromUri(context: Context, uri: Uri): AppMediaType {
        val mime = context.contentResolver.getType(uri)?.lowercase(Locale.ROOT)
        if (mime != null) {
            when {
                mime.startsWith("audio/") -> return AppMediaType.AUDIO
                mime.startsWith("video/") -> return AppMediaType.VIDEO
                mime.contains("pdf") || mime.contains("document") || mime.startsWith("text/") -> return AppMediaType.DOCUMENT
            }
        }
        val fileName = getFileNameFromUri(context, uri)
        val ext = fileName.substringAfterLast(".", "").lowercase(Locale.ROOT)
        return getMediaTypeFromExtension(ext)
    }

    fun formatDuration(seconds: Float): String {
        val totalSec = seconds.toInt()
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val remainingSec = totalSec % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, remainingSec)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, remainingSec)
        }
    }
}
