package com.gemini.summarizer.domain.engine

import android.content.Context
import android.net.Uri
import com.gemini.summarizer.core.database.AppDatabase
import com.gemini.summarizer.core.media.AppMediaType
import com.gemini.summarizer.core.media.MediaAudioExtractor
import com.gemini.summarizer.core.media.MediaHelper
import com.gemini.summarizer.core.media.PreparedMedia
import com.gemini.summarizer.core.network.CloudflareWhisperClient
import com.gemini.summarizer.core.security.SecurityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class MediaTranscriptionResult(
    val transcriptText: String,
    val totalWords: Int,
    val durationSeconds: Float,
    val mediaType: AppMediaType,
    val displayName: String,
    val chunksCount: Int,
    val accountsUsed: List<String>
)

class MediaTranscriptionEngine(
    private val context: Context,
    private val database: AppDatabase,
    private val securityManager: SecurityManager
) {
    private val audioExtractor = MediaAudioExtractor(context)
    private val whisperClient = CloudflareWhisperClient(database, securityManager)

    /**
     * Transcribe speech from a local media Uri (Audio or Video).
     */
    suspend fun transcribeMediaUri(
        uri: Uri,
        targetLanguage: String = "auto",
        whisperModel: String = CloudflareWhisperClient.MODEL_WHISPER_TURBO,
        onProgressUpdate: (String) -> Unit
    ): Result<MediaTranscriptionResult> = withContext(Dispatchers.IO) {
        val fileName = MediaHelper.getFileNameFromUri(context, uri)
        val mediaType = MediaHelper.getMediaTypeFromUri(context, uri)
        var tempSourceFile: File? = null

        try {
            onProgressUpdate("Copying media file for audio processing...")
            tempSourceFile = audioExtractor.copyUriToCache(uri, fileName)

            transcribeFileInternal(
                inputFile = tempSourceFile,
                displayName = fileName,
                mediaType = mediaType,
                targetLanguage = targetLanguage,
                whisperModel = whisperModel,
                onProgressUpdate = onProgressUpdate
            )
        } finally {
            tempSourceFile?.delete()
        }
    }

    /**
     * Transcribe speech from a remote media URL (Audio or Video).
     */
    suspend fun transcribeMediaUrl(
        url: String,
        targetLanguage: String = "auto",
        whisperModel: String = CloudflareWhisperClient.MODEL_WHISPER_TURBO,
        onProgressUpdate: (String) -> Unit
    ): Result<MediaTranscriptionResult> = withContext(Dispatchers.IO) {
        val mediaType = MediaHelper.probeUrlContentType(url)
        val cleanName = url.substringBefore("?").substringBefore("#").substringAfterLast("/")
        val displayName = if (cleanName.isNotBlank() && cleanName.contains(".")) cleanName else "remote_media"
        var downloadedFile: File? = null

        try {
            onProgressUpdate("Starting download of media from link: $displayName...")
            val downloadResult = audioExtractor.downloadMediaUrl(url, onProgressUpdate)
            if (downloadResult.isFailure) {
                return@withContext Result.failure(
                    downloadResult.exceptionOrNull() ?: Exception("Failed to download media file.")
                )
            }

            downloadedFile = downloadResult.getOrThrow()

            transcribeFileInternal(
                inputFile = downloadedFile,
                displayName = displayName,
                mediaType = mediaType,
                targetLanguage = targetLanguage,
                whisperModel = whisperModel,
                onProgressUpdate = onProgressUpdate
            )
        } finally {
            downloadedFile?.delete()
        }
    }

    private suspend fun transcribeFileInternal(
        inputFile: File,
        displayName: String,
        mediaType: AppMediaType,
        targetLanguage: String,
        whisperModel: String,
        onProgressUpdate: (String) -> Unit
    ): Result<MediaTranscriptionResult> {
        val prepareResult = audioExtractor.prepareMedia(
            inputFile = inputFile,
            originalName = displayName,
            mediaType = mediaType,
            onProgress = onProgressUpdate
        )

        if (prepareResult.isFailure) {
            return Result.failure(prepareResult.exceptionOrNull() ?: Exception("Media preparation failed."))
        }

        val preparedMedia = prepareResult.getOrThrow()
        val accountsUsed = mutableListOf<String>()
        val chunkTranscripts = mutableListOf<String>()

        return try {
            val totalChunks = preparedMedia.chunks.size

            for ((i, chunk) in preparedMedia.chunks.withIndex()) {
                val chunkLabel = if (totalChunks > 1) " (Part ${i + 1}/$totalChunks)" else ""
                onProgressUpdate("Transcribing speech with Cloudflare Whisper$chunkLabel...")

                val audioBytes = chunk.file.readBytes()
                val whisperResult = whisperClient.transcribeWithRotation(
                    audioBytes = audioBytes,
                    language = targetLanguage,
                    modelId = whisperModel,
                    onProgressUpdate = onProgressUpdate
                )

                if (whisperResult.isFailure) {
                    return Result.failure(
                        whisperResult.exceptionOrNull() ?: Exception("Transcription failed on chunk ${i + 1}.")
                    )
                }

                val data = whisperResult.getOrThrow()
                if (!accountsUsed.contains(data.accountUsed)) {
                    accountsUsed.add(data.accountUsed)
                }

                val text = data.text.trim()
                if (text.isNotBlank()) {
                    chunkTranscripts.add(text)
                }
            }

            val fullTranscript = chunkTranscripts.joinToString("\n\n")
            val totalWords = if (fullTranscript.isBlank()) 0 else fullTranscript.trim().split("\\s+".toRegex()).size

            if (fullTranscript.isBlank()) {
                return Result.failure(
                    Exception("Whisper did not detect any spoken dialogue in the provided media.")
                )
            }

            onProgressUpdate(
                "Speech transcription completed ($totalWords words extracted from ${MediaHelper.formatDuration(preparedMedia.totalDurationSeconds)})."
            )

            Result.success(
                MediaTranscriptionResult(
                    transcriptText = fullTranscript,
                    totalWords = totalWords,
                    durationSeconds = preparedMedia.totalDurationSeconds,
                    mediaType = preparedMedia.mediaType,
                    displayName = preparedMedia.displayName,
                    chunksCount = totalChunks,
                    accountsUsed = accountsUsed
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("Speech transcription failed: ${e.localizedMessage ?: "Unknown error"}"))
        } finally {
            preparedMedia.tempFilesToClean.forEach { it.delete() }
        }
    }
}
