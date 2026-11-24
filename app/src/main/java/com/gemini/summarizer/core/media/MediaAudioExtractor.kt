package com.gemini.summarizer.core.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit

data class AudioChunk(
    val file: File,
    val index: Int,
    val totalChunks: Int,
    val startSeconds: Float,
    val durationSeconds: Float
)

data class PreparedMedia(
    val displayName: String,
    val mediaType: AppMediaType,
    val totalDurationSeconds: Float,
    val chunks: List<AudioChunk>,
    val tempFilesToClean: List<File>
)

class MediaAudioExtractor(private val context: Context) {

    companion object {
        // Cloudflare Whisper maximum recommended duration per chunk is 300s (5 minutes).
        // 200s (3m 20s) ensures high precision, safe upload payload size, and fast edge processing.
        const val CHUNK_TARGET_DURATION_SEC = 200.0f
        const val MAX_SINGLE_PASS_DURATION_SEC = 260.0f
        const val MAX_SINGLE_PASS_BYTES = 20 * 1024 * 1024 // 20 MB

        const val TARGET_SAMPLE_RATE = 16000 // Whisper native rate
        const val TARGET_CHANNELS = 1        // Mono
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val cacheDir: File
        get() {
            val dir = File(context.cacheDir, "gemini_media")
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    /**
     * Download remote media URL to a local cache file with progress feedback.
     */
    suspend fun downloadMediaUrl(
        url: String,
        onProgress: ((String) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            onProgress?.invoke("Connecting to media stream...")
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Failed to download media (HTTP ${response.code}): ${response.message}")
                )
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty response body from media URL."))
            val contentLength = body.contentLength()

            val cleanUrl = url.substringBefore("?").substringBefore("#")
            val ext = cleanUrl.substringAfterLast(".", "mp4").lowercase()
            val tempFile = File(cacheDir, "download_${UUID.randomUUID().toString().take(8)}.$ext")

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    var totalRead = 0L
                    var lastReportTime = 0L

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastReportTime > 500) {
                            lastReportTime = now
                            val progressStr = if (contentLength > 0) {
                                val percent = (totalRead * 100 / contentLength).toInt()
                                val mbRead = totalRead / (1024f * 1024f)
                                val mbTotal = contentLength / (1024f * 1024f)
                                "Downloading media: %.1f MB / %.1f MB (%d%%)...".format(mbRead, mbTotal, percent)
                            } else {
                                val mbRead = totalRead / (1024f * 1024f)
                                "Downloading media: %.1f MB...".format(mbRead)
                            }
                            onProgress?.invoke(progressStr)
                        }
                    }
                }
            }

            onProgress?.invoke("Download completed (%.1f MB).".format(tempFile.length() / (1024f * 1024f)))
            Result.success(tempFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception("Download failed: ${e.localizedMessage ?: "Unknown network error"}"))
        }
    }

    /**
     * Copy content URI to local cache file so MediaExtractor can open a direct file descriptor.
     */
    suspend fun copyUriToCache(uri: Uri, fileName: String): File = withContext(Dispatchers.IO) {
        val ext = fileName.substringAfterLast(".", "media").lowercase()
        val tempFile = File(cacheDir, "input_${UUID.randomUUID().toString().take(8)}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Could not open input stream from file.")
        tempFile
    }

    /**
     * Inspect and prepare audio chunks from any audio or video file.
     */
    suspend fun prepareMedia(
        inputFile: File,
        originalName: String,
        mediaType: AppMediaType,
        onProgress: ((String) -> Unit)? = null
    ): Result<PreparedMedia> = withContext(Dispatchers.IO) {
        val tempFiles = mutableListOf<File>()
        try {
            onProgress?.invoke("Probing media structure & audio tracks...")

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(inputFile.absolutePath)
            } catch (e: Exception) {
                return@withContext Result.failure(Exception("Cannot probe media file. Format might be corrupted or unsupported."))
            }

            val durationMsStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationMsStr?.toLongOrNull() ?: 0L
            val durationSec = durationMs / 1000f

            val hasAudioStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val hasAudio = hasAudioStr != null && hasAudioStr.equals("yes", ignoreCase = true)
            retriever.release()

            if (durationSec <= 0f && mediaType == AppMediaType.VIDEO && !hasAudio) {
                return@withContext Result.failure(Exception("No readable audio stream found in this video."))
            }

            val isPureAudio = mediaType == AppMediaType.AUDIO || MediaHelper.isAudioExtension(inputFile.extension)

            // Case A: Audio file that is already small & short enough for single-pass Whisper
            if (isPureAudio && durationSec in 0.1f..MAX_SINGLE_PASS_DURATION_SEC && inputFile.length() <= MAX_SINGLE_PASS_BYTES) {
                onProgress?.invoke("Audio duration is ${MediaHelper.formatDuration(durationSec)}. Preparing single-pass STT...")
                val chunk = AudioChunk(
                    file = inputFile,
                    index = 1,
                    totalChunks = 1,
                    startSeconds = 0f,
                    durationSeconds = durationSec
                )
                return@withContext Result.success(
                    PreparedMedia(
                        displayName = originalName,
                        mediaType = mediaType,
                        totalDurationSeconds = durationSec,
                        chunks = listOf(chunk),
                        tempFilesToClean = tempFiles
                    )
                )
            }

            // Case B: Video file or long audio file -> Extract audio & slice into manageable chunks
            onProgress?.invoke("Extracting audio and slicing into chunks (${MediaHelper.formatDuration(durationSec)} total)...")
            val chunksResult = sliceMediaIntoChunks(inputFile, durationSec, onProgress)
            if (chunksResult.isFailure) {
                return@withContext Result.failure(chunksResult.exceptionOrNull() ?: Exception("Audio slicing failed."))
            }

            val chunks = chunksResult.getOrThrow()
            tempFiles.addAll(chunks.map { it.file })

            Result.success(
                PreparedMedia(
                    displayName = originalName,
                    mediaType = mediaType,
                    totalDurationSeconds = durationSec,
                    chunks = chunks,
                    tempFilesToClean = tempFiles
                )
            )
        } catch (e: CancellationException) {
            tempFiles.forEach { it.delete() }
            throw e
        } catch (e: Exception) {
            tempFiles.forEach { it.delete() }
            Result.failure(Exception("Failed to prepare media: ${e.localizedMessage ?: "Unknown error"}"))
        }
    }

    /**
     * Slice media file into 200s audio chunks.
     * Tries MediaExtractor + MediaMuxer direct extraction first (fastest for AAC tracks in MP4/M4A).
     * If direct muxing fails or codec is unsupported, falls back to MediaCodec PCM decoding to 16kHz WAV.
     */
    private suspend fun sliceMediaIntoChunks(
        inputFile: File,
        durationSec: Float,
        onProgress: ((String) -> Unit)?
    ): Result<List<AudioChunk>> = withContext(Dispatchers.IO) {
        val chunks = mutableListOf<AudioChunk>()
        val totalChunks = Math.max(1, Math.ceil((durationSec / CHUNK_TARGET_DURATION_SEC).toDouble()).toInt())

        // Try direct MediaMuxer extraction (M4A)
        val muxerSuccess = tryDirectMuxingChunks(inputFile, durationSec, totalChunks, chunks, onProgress)
        if (muxerSuccess && chunks.isNotEmpty()) {
            return@withContext Result.success(chunks)
        }

        // Fallback: MediaCodec decoding to 16kHz mono WAV chunks
        chunks.forEach { it.file.delete() }
        chunks.clear()

        onProgress?.invoke("Extracting audio using native media decoder...")
        val codecSuccess = tryCodecDecodingChunks(inputFile, durationSec, totalChunks, chunks, onProgress)
        if (codecSuccess && chunks.isNotEmpty()) {
            return@withContext Result.success(chunks)
        }

        Result.failure(Exception("Unable to decode or extract audio track from this media."))
    }

    private fun tryDirectMuxingChunks(
        inputFile: File,
        totalDurationSec: Float,
        totalChunks: Int,
        outChunks: MutableList<AudioChunk>,
        onProgress: ((String) -> Unit)?
    ): Boolean {
        var extractor: MediaExtractor? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(inputFile.absolutePath)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) return false

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
            // MediaMuxer OutputFormat.MUXER_OUTPUT_MPEG_4 supports mp4a-latm (AAC)
            if (!mime.contains("mp4a", ignoreCase = true) && !mime.contains("aac", ignoreCase = true)) {
                return false
            }

            val maxBufferSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else 256 * 1024

            val buffer = ByteBuffer.allocate(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            for (chunkIdx in 0 until totalChunks) {
                val chunkStartSec = chunkIdx * CHUNK_TARGET_DURATION_SEC
                val chunkEndSec = Math.min(totalDurationSec, (chunkIdx + 1) * CHUNK_TARGET_DURATION_SEC)
                val chunkDuration = chunkEndSec - chunkStartSec

                onProgress?.invoke("Extracting audio chunk ${chunkIdx + 1} of $totalChunks...")

                val chunkFile = File(cacheDir, "chunk_${UUID.randomUUID().toString().take(8)}_${chunkIdx + 1}.m4a")
                val muxer = MediaMuxer(chunkFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val muxerAudioTrack = muxer.addTrack(audioFormat)
                muxer.start()

                extractor.unselectTrack(audioTrackIndex)
                extractor.selectTrack(audioTrackIndex)
                extractor.seekTo((chunkStartSec * 1_000_000).toLong(), MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                val startUs = (chunkStartSec * 1_000_000).toLong()
                val endUs = (chunkEndSec * 1_000_000).toLong()

                var samplesWritten = 0
                var firstSampleUs = -1L

                while (true) {
                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0 || sampleTime > endUs) break

                    if (sampleTime >= startUs) {
                        if (firstSampleUs < 0) firstSampleUs = sampleTime

                        bufferInfo.offset = 0
                        bufferInfo.size = extractor.readSampleData(buffer, 0)
                        if (bufferInfo.size < 0) break

                        bufferInfo.presentationTimeUs = Math.max(0L, sampleTime - firstSampleUs)
                        bufferInfo.flags = extractor.sampleFlags
                        muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                        samplesWritten++
                    }

                    if (!extractor.advance()) break
                }

                muxer.stop()
                muxer.release()

                if (samplesWritten > 0 && chunkFile.length() > 512) {
                    outChunks.add(
                        AudioChunk(
                            file = chunkFile,
                            index = chunkIdx + 1,
                            totalChunks = totalChunks,
                            startSeconds = chunkStartSec,
                            durationSeconds = chunkDuration
                        )
                    )
                } else {
                    chunkFile.delete()
                    return false
                }
            }

            return outChunks.size == totalChunks
        } catch (e: Exception) {
            return false
        } finally {
            try { extractor?.release() } catch (ignored: Exception) {}
        }
    }

    /**
     * Decodes any audio track supported by the device into 16kHz mono WAV chunks via MediaCodec.
     */
    private fun tryCodecDecodingChunks(
        inputFile: File,
        totalDurationSec: Float,
        totalChunks: Int,
        outChunks: MutableList<AudioChunk>,
        onProgress: ((String) -> Unit)?
    ): Boolean {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(inputFile.absolutePath)

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex == -1 || audioFormat == null) return false

            val mime = audioFormat.getString(MediaFormat.KEY_MIME) ?: return false
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(audioFormat, null, null, 0)
            codec.start()

            extractor.selectTrack(audioTrackIndex)

            val rawPcmFile = File(cacheDir, "decoded_${UUID.randomUUID().toString().take(8)}.pcm")
            val pcmOut = FileOutputStream(rawPcmFile)

            val bufferInfo = MediaCodec.BufferInfo()
            var isEOS = false
            var outputSampleRate = 44100
            var outputChannels = 2

            val timeoutUs = 10000L

            while (!isEOS) {
                val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                var outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                while (outputIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outputIndex)
                    if (outBuf != null && bufferInfo.size > 0) {
                        val chunkBytes = ByteArray(bufferInfo.size)
                        outBuf.position(bufferInfo.offset)
                        outBuf.get(chunkBytes)
                        pcmOut.write(chunkBytes)
                    }

                    codec.releaseOutputBuffer(outputIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEOS = true
                        break
                    }
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }

                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    outputSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, 44100)
                    outputChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)
                }
            }

            pcmOut.close()

            if (rawPcmFile.length() < 1024) {
                rawPcmFile.delete()
                return false
            }

            // Downsample and slice PCM into 16kHz mono WAV chunks
            val bytesPerSec = outputSampleRate * outputChannels * 2
            val chunkBytesLength = (CHUNK_TARGET_DURATION_SEC * bytesPerSec).toLong()

            val pcmIn = FileInputStream(rawPcmFile)

            for (chunkIdx in 0 until totalChunks) {
                val chunkStartSec = chunkIdx * CHUNK_TARGET_DURATION_SEC
                val chunkDuration = Math.min(CHUNK_TARGET_DURATION_SEC, totalDurationSec - chunkStartSec)

                val chunkWavFile = File(cacheDir, "chunk_wav_${UUID.randomUUID().toString().take(8)}_${chunkIdx + 1}.wav")
                val wavOut = FileOutputStream(chunkWavFile)

                // Downsample to 16kHz mono while writing WAV
                writeResampledWavChunk(
                    input = pcmIn,
                    output = wavOut,
                    srcSampleRate = outputSampleRate,
                    srcChannels = outputChannels,
                    targetSampleRate = TARGET_SAMPLE_RATE,
                    durationSeconds = chunkDuration
                )
                wavOut.close()

                if (chunkWavFile.length() > 512) {
                    outChunks.add(
                        AudioChunk(
                            file = chunkWavFile,
                            index = chunkIdx + 1,
                            totalChunks = totalChunks,
                            startSeconds = chunkStartSec,
                            durationSeconds = chunkDuration
                        )
                    )
                }
            }

            pcmIn.close()
            rawPcmFile.delete()

            return outChunks.isNotEmpty()
        } catch (e: Exception) {
            return false
        } finally {
            try { codec?.stop(); codec?.release() } catch (ignored: Exception) {}
            try { extractor?.release() } catch (ignored: Exception) {}
        }
    }

    private fun writeResampledWavChunk(
        input: FileInputStream,
        output: FileOutputStream,
        srcSampleRate: Int,
        srcChannels: Int,
        targetSampleRate: Int,
        durationSeconds: Float
    ) {
        // Write placeholder 44-byte WAV header
        val header = ByteArray(44)
        output.write(header)

        var totalPcmBytesWritten = 0L

        val srcFrameSize = srcChannels * 2 // 16-bit PCM
        val maxFramesToRead = (durationSeconds * srcSampleRate).toLong()
        var framesRead = 0L

        // Resample ratio step
        val step = srcSampleRate.toDouble() / targetSampleRate.toDouble()
        var srcPos = 0.0

        val frameBuf = ByteArray(srcFrameSize)
        val shortBuf = ByteArray(2)

        while (framesRead < maxFramesToRead) {
            val bytes = input.read(frameBuf)
            if (bytes < srcFrameSize) break
            framesRead++

            // When downsampling, check if current source sample falls on target interval
            srcPos += 1.0
            if (srcPos >= step) {
                srcPos -= step

                // Average stereo to mono or take mono
                val sampleValue: Short = if (srcChannels >= 2) {
                    val left = (frameBuf[0].toInt() and 0xFF or (frameBuf[1].toInt() shl 8)).toShort()
                    val right = (frameBuf[2].toInt() and 0xFF or (frameBuf[3].toInt() shl 8)).toShort()
                    ((left.toInt() + right.toInt()) / 2).toShort()
                } else {
                    (frameBuf[0].toInt() and 0xFF or (frameBuf[1].toInt() shl 8)).toShort()
                }

                shortBuf[0] = (sampleValue.toInt() and 0xFF).toByte()
                shortBuf[1] = ((sampleValue.toInt() shr 8) and 0xFF).toByte()
                output.write(shortBuf)
                totalPcmBytesWritten += 2
            }
        }

        // Fill in real WAV header
        val totalDataLen = totalPcmBytesWritten + 36
        val byteRate = (targetSampleRate * 1 * 2).toLong() // 16-bit mono

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = ((totalDataLen shr 8) and 0xff).toByte()
        header[6] = ((totalDataLen shr 16) and 0xff).toByte()
        header[7] = ((totalDataLen shr 24) and 0xff).toByte()
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        header[20] = 1 // PCM
        header[21] = 0
        header[22] = 1 // Mono
        header[23] = 0
        header[24] = (targetSampleRate and 0xff).toByte()
        header[25] = ((targetSampleRate shr 8) and 0xff).toByte()
        header[26] = ((targetSampleRate shr 16) and 0xff).toByte()
        header[27] = ((targetSampleRate shr 24) and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        header[32] = 2 // Block align (16-bit mono)
        header[33] = 0
        header[34] = 16 // Bits per sample
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalPcmBytesWritten and 0xff).toByte()
        header[41] = ((totalPcmBytesWritten shr 8) and 0xff).toByte()
        header[42] = ((totalPcmBytesWritten shr 16) and 0xff).toByte()
        header[43] = ((totalPcmBytesWritten shr 24) and 0xff).toByte()

        // Overwrite header at position 0
        output.channel.position(0)
        output.write(header)
    }

    fun cleanCache() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
        } catch (ignored: Exception) {}
    }
}
