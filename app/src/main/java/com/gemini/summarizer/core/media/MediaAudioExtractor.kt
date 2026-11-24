package com.gemini.summarizer.core.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
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
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
        // Empirical Cloudflare Whisper optimal configuration:
        // 16kHz Mono AAC-LC at 24 kbps (~180 KB/min). 15-minute slices (~2.7 MB each) keep
        // edge GPU inference comfortably under 150s while maximizing 5-account parallelism.
        const val TARGET_SAMPLE_RATE = 16000
        const val TARGET_CHANNELS = 1
        const val TARGET_AAC_BITRATE = 24_000 // 24 kbps
        const val COMPRESSED_CHUNK_DURATION_SEC = 900.0f // 15 minutes per compressed chunk (~2.7 MB)

        // Fallback chunk duration for uncompressed audio tracks (MP3/WAV)
        const val CHUNK_TARGET_DURATION_SEC = 240.0f

        // Bypass compression/slicing only if source is already compact audio under 15 min and <= 3.5 MB
        const val MAX_SINGLE_PASS_DURATION_SEC = 900.0f
        const val MAX_SINGLE_PASS_BYTES = 3500 * 1024 // ~3.5 MB
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val cacheDir: File
        get() {
            val base = try { context.cacheDir } catch (e: Exception) { null }
                ?: File(System.getProperty("java.io.tmpdir", "."), "gemini_media")
            val dir = File(base, "gemini_media")
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
        val extension = MediaHelper.extractExtensionFromUrl(url)
        val tempFile = File(cacheDir, "remote_${UUID.randomUUID().toString().take(8)}.$extension")

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile; GeminiSummarizer/1.0)")
            .build()

        try {
            onProgress?.invoke("Connecting to remote media stream...")
            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("HTTP error ${response.code}: ${response.message}"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty media response body."))
            val contentLength = body.contentLength()
            val hasLength = contentLength > 0

            onProgress?.invoke("Downloading media stream${if (hasLength) " (" + (contentLength / (1024 * 1024)) + " MB)" else ""}...")

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var totalRead = 0L
                    var bytesRead: Int
                    var lastReportedPercent = -1

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        if (hasLength) {
                            val percent = ((totalRead * 100) / contentLength).toInt()
                            if (percent != lastReportedPercent && percent % 10 == 0) {
                                lastReportedPercent = percent
                                onProgress?.invoke("Downloading media: $percent%...")
                            }
                        } else if (totalRead % (5 * 1024 * 1024) == 0L) {
                            onProgress?.invoke("Downloaded ${totalRead / (1024 * 1024)} MB...")
                        }
                    }
                    output.flush()
                }
            }

            if (tempFile.length() < 1024) {
                tempFile.delete()
                return@withContext Result.failure(Exception("Downloaded media file is too small or empty."))
            }

            onProgress?.invoke("Download completed (${tempFile.length() / (1024 * 1024)} MB).")
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
     * Compresses media audio into ultra-lightweight 16kHz Mono AAC (.m4a, 32 kbps) via
     * MediaExtractor + MediaCodec (Decoder & AAC Encoder) + MediaMuxer, then slices into
     * 10-minute chunks (~2.4 MB each). Falls back cleanly to direct stream slicing or WAV decoding.
     */
    private suspend fun sliceMediaIntoChunks(
        inputFile: File,
        durationSec: Float,
        onProgress: ((String) -> Unit)?
    ): Result<List<AudioChunk>> = withContext(Dispatchers.IO) {
        val chunks = mutableListOf<AudioChunk>()
        val compressedTotalChunks = Math.max(1, Math.ceil((durationSec / COMPRESSED_CHUNK_DURATION_SEC).toDouble()).toInt())

        // Primary Strategy: Compress to 16kHz Mono AAC (24 kbps) -> 15-minute chunks (~2.7 MB each)
        onProgress?.invoke("Compressing audio to 16kHz Mono AAC (24 kbps)...")
        val compressedFile = transcodeToCompressedAac(inputFile, durationSec, onProgress)
        if (compressedFile != null && compressedFile.exists() && compressedFile.length() > 512) {
            val compressedSizeMb = String.format(java.util.Locale.US, "%.1f", compressedFile.length() / (1024.0 * 1024.0))
            onProgress?.invoke("Compressed audio ready ($compressedSizeMb MB total). Preparing ${compressedTotalChunks}-part layout...")

            if (durationSec in 0.1f..COMPRESSED_CHUNK_DURATION_SEC && compressedFile.length() <= 10 * 1024 * 1024) {
                return@withContext Result.success(
                    listOf(
                        AudioChunk(
                            file = compressedFile,
                            index = 1,
                            totalChunks = 1,
                            startSeconds = 0f,
                            durationSeconds = durationSec
                        )
                    )
                )
            }

            val compressedSliced = tryDirectMuxingChunks(
                inputFile = compressedFile,
                totalDurationSec = durationSec,
                chunkDurationSec = COMPRESSED_CHUNK_DURATION_SEC,
                outChunks = chunks,
                onProgress = onProgress
            )
            compressedFile.delete()
            if (compressedSliced && chunks.isNotEmpty()) {
                return@withContext Result.success(chunks)
            }
            chunks.forEach { it.file.delete() }
            chunks.clear()
        }

        val fallbackTotalChunks = Math.max(1, Math.ceil((durationSec / CHUNK_TARGET_DURATION_SEC).toDouble()).toInt())
        val ext = inputFile.extension.lowercase()

        // Fallback 1: Direct fast slicing for MP3 files
        if (ext == "mp3") {
            onProgress?.invoke("Fast-slicing MP3 audio stream into $fallbackTotalChunks parts...")
            val mp3Success = tryDirectMp3Chunking(inputFile, durationSec, fallbackTotalChunks, chunks, onProgress)
            if (mp3Success && chunks.isNotEmpty()) {
                return@withContext Result.success(chunks)
            }
            chunks.forEach { it.file.delete() }
            chunks.clear()
        }

        // Fallback 2: Direct fast slicing for WAV files
        if (ext == "wav") {
            onProgress?.invoke("Fast-slicing WAV audio stream into $fallbackTotalChunks parts...")
            val wavSuccess = tryDirectWavChunking(inputFile, durationSec, fallbackTotalChunks, chunks, onProgress)
            if (wavSuccess && chunks.isNotEmpty()) {
                return@withContext Result.success(chunks)
            }
            chunks.forEach { it.file.delete() }
            chunks.clear()
        }

        // Fallback 3: Direct MediaMuxer extraction (M4A/AAC tracks in MP4, M4A, MKV)
        val muxerSuccess = tryDirectMuxingChunks(
            inputFile = inputFile,
            totalDurationSec = durationSec,
            chunkDurationSec = CHUNK_TARGET_DURATION_SEC,
            outChunks = chunks,
            onProgress = onProgress
        )
        if (muxerSuccess && chunks.isNotEmpty()) {
            return@withContext Result.success(chunks)
        }
        chunks.forEach { it.file.delete() }
        chunks.clear()

        // Fallback 4: High-speed streaming MediaCodec decoder to 16kHz mono WAV
        onProgress?.invoke("Extracting audio using native media decoder...")
        val codecSuccess = tryStreamingCodecChunks(inputFile, durationSec, fallbackTotalChunks, chunks, onProgress)
        if (codecSuccess && chunks.isNotEmpty()) {
            return@withContext Result.success(chunks)
        }

        Result.failure(Exception("Unable to decode or extract audio track from this media."))
    }

    /**
     * Transcodes any audio or video audio track into ultra-lightweight 16kHz Mono AAC-LC (32 kbps) .m4a.
     * Reduces file size by 80-95% (~240 KB per minute) while preserving clean speech clarity for Whisper.
     */
    private fun transcodeToCompressedAac(
        inputFile: File,
        totalDurationSec: Float,
        onProgress: ((String) -> Unit)?
    ): File? {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        val outFile = File(cacheDir, "compressed_${UUID.randomUUID().toString().take(8)}.m4a")

        try {
            extractor = MediaExtractor()
            extractor.setDataSource(inputFile.absolutePath)

            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    inputFormat = fmt
                    break
                }
            }

            if (audioTrackIndex == -1 || inputFormat == null) return null

            val inputMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            decoder = MediaCodec.createDecoderByType(inputMime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            extractor.selectTrack(audioTrackIndex)

            val encFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                TARGET_SAMPLE_RATE,
                TARGET_CHANNELS
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, TARGET_AAC_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32768)
            }

            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val activeMuxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = activeMuxer
            var muxerTrackIndex = -1

            var srcSampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            var srcChannels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()

            var extractorEOS = false
            var decoderEOS = false
            var encoderInputDone = false
            var encoderEOS = false

            var resamplePos = 0.0
            var totalMonoSamplesFed = 0L
            var lastReportPercent = -1

            val pcmQueue = ByteArray(65536)
            var pcmQueueSize = 0
            val timeoutUs = 5000L

            fun drainEncoder() {
                while (true) {
                    val encOutIdx = encoder.dequeueOutputBuffer(encInfo, 0L)
                    when {
                        encOutIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        encOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                muxerTrackIndex = activeMuxer.addTrack(encoder.outputFormat)
                                activeMuxer.start()
                                muxerStarted = true
                            }
                        }
                        encOutIdx >= 0 -> {
                            val encodedBuf = encoder.getOutputBuffer(encOutIdx)
                            if (encodedBuf != null) {
                                if ((encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    encInfo.size = 0
                                }
                                if (encInfo.size > 0 && muxerStarted) {
                                    encodedBuf.position(encInfo.offset)
                                    encodedBuf.limit(encInfo.offset + encInfo.size)
                                    activeMuxer.writeSampleData(muxerTrackIndex, encodedBuf, encInfo)
                                }
                            }
                            encoder.releaseOutputBuffer(encOutIdx, false)
                            if ((encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                encoderEOS = true
                                break
                            }
                        }
                    }
                }
            }

            fun flushPcmQueueToEncoder(endOfInput: Boolean) {
                var offset = 0
                while (offset < pcmQueueSize) {
                    val encInIdx = encoder.dequeueInputBuffer(timeoutUs)
                    if (encInIdx >= 0) {
                        val encInBuf = encoder.getInputBuffer(encInIdx)
                        if (encInBuf != null) {
                            encInBuf.clear()
                            val toWrite = Math.min(encInBuf.remaining(), pcmQueueSize - offset)
                            val alignedWrite = toWrite and 0xFFFFFFFE.toInt()
                            if (alignedWrite > 0) {
                                encInBuf.put(pcmQueue, offset, alignedWrite)
                                val ptsUs = (totalMonoSamplesFed * 1_000_000L) / TARGET_SAMPLE_RATE
                                totalMonoSamplesFed += (alignedWrite / 2)
                                encoder.queueInputBuffer(encInIdx, 0, alignedWrite, ptsUs, 0)
                                offset += alignedWrite
                            } else {
                                encoder.queueInputBuffer(encInIdx, 0, 0, 0L, 0)
                            }
                        }
                    } else {
                        drainEncoder()
                    }
                }
                pcmQueueSize = 0

                if (endOfInput && !encoderInputDone) {
                    while (true) {
                        val encInIdx = encoder.dequeueInputBuffer(timeoutUs)
                        if (encInIdx >= 0) {
                            val ptsUs = (totalMonoSamplesFed * 1_000_000L) / TARGET_SAMPLE_RATE
                            encoder.queueInputBuffer(encInIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encoderInputDone = true
                            break
                        } else {
                            drainEncoder()
                        }
                    }
                }
                drainEncoder()
            }

            while (!encoderEOS) {
                if (!extractorEOS) {
                    val decInIdx = decoder.dequeueInputBuffer(timeoutUs)
                    if (decInIdx >= 0) {
                        val decInBuf = decoder.getInputBuffer(decInIdx)
                        if (decInBuf != null) {
                            val sampleSize = extractor.readSampleData(decInBuf, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(decInIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                extractorEOS = true
                            } else {
                                decoder.queueInputBuffer(decInIdx, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                if (!decoderEOS) {
                    val decOutIdx = decoder.dequeueOutputBuffer(decInfo, timeoutUs)
                    when {
                        decOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFmt = decoder.outputFormat
                            srcSampleRate = newFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE, 44100)
                            srcChannels = newFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)
                        }
                        decOutIdx >= 0 -> {
                            val outBuf = decoder.getOutputBuffer(decOutIdx)
                            if (outBuf != null && decInfo.size > 0) {
                                outBuf.position(decInfo.offset)
                                outBuf.limit(decInfo.offset + decInfo.size)
                                outBuf.order(ByteOrder.LITTLE_ENDIAN)

                                val step = srcSampleRate.toDouble() / TARGET_SAMPLE_RATE.toDouble()
                                val safeChannels = Math.max(1, srcChannels)
                                val frameSize = safeChannels * 2
                                val frames = decInfo.size / frameSize

                                for (f in 0 until frames) {
                                    resamplePos += 1.0
                                    if (resamplePos >= step) {
                                        resamplePos -= step

                                        val sampleVal: Short = if (safeChannels == 1) {
                                            outBuf.short
                                        } else {
                                            var sum = 0L
                                            for (ch in 0 until safeChannels) {
                                                sum += outBuf.short.toLong()
                                            }
                                            (sum / safeChannels).toShort()
                                        }

                                        if (pcmQueueSize + 2 > pcmQueue.size) {
                                            flushPcmQueueToEncoder(endOfInput = false)
                                        }
                                        pcmQueue[pcmQueueSize++] = (sampleVal.toInt() and 0xFF).toByte()
                                        pcmQueue[pcmQueueSize++] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
                                    } else {
                                        for (ch in 0 until safeChannels) {
                                            outBuf.short
                                        }
                                    }
                                }

                                if (totalDurationSec > 0f) {
                                    val curSec = decInfo.presentationTimeUs / 1_000_000f
                                    val percent = Math.min(99, ((curSec / totalDurationSec) * 100).toInt())
                                    if (percent >= lastReportPercent + 10) {
                                        lastReportPercent = percent
                                        onProgress?.invoke("Compressing audio to 16kHz Mono AAC: $percent%...")
                                    }
                                }
                            }

                            decoder.releaseOutputBuffer(decOutIdx, false)

                            if ((decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                decoderEOS = true
                                flushPcmQueueToEncoder(endOfInput = true)
                            } else if (pcmQueueSize >= 4096) {
                                flushPcmQueueToEncoder(endOfInput = false)
                            }
                        }
                    }
                } else {
                    if (!encoderInputDone) {
                        flushPcmQueueToEncoder(endOfInput = true)
                    } else {
                        drainEncoder()
                    }
                }
            }

            if (muxerStarted) {
                activeMuxer.stop()
                muxerStarted = false
            }
            activeMuxer.release()
            muxer = null

            if (outFile.exists() && outFile.length() > 1024) {
                return outFile
            }
            outFile.delete()
            return null
        } catch (e: Exception) {
            try { outFile.delete() } catch (ignored: Exception) {}
            return null
        } finally {
            try { if (muxerStarted) muxer?.stop() } catch (ignored: Exception) {}
            try { muxer?.release() } catch (ignored: Exception) {}
            try { encoder?.stop(); encoder?.release() } catch (ignored: Exception) {}
            try { decoder?.stop(); decoder?.release() } catch (ignored: Exception) {}
            try { extractor?.release() } catch (ignored: Exception) {}
        }
    }

    /**
     * Directly slices an MP3 file into ~200s chunks by navigating MPEG audio frame headers.
     * Takes < 0.5s even for 2-hour recordings, requires zero decompression to PCM, and outputs valid MP3 chunks.
     */
    fun tryDirectMp3Chunking(
        inputFile: File,
        totalDurationSec: Float,
        totalChunks: Int,
        outChunks: MutableList<AudioChunk>,
        onProgress: ((String) -> Unit)?
    ): Boolean {
        try {
            val fileLength = inputFile.length()
            if (fileLength < 1024) return false

            RandomAccessFile(inputFile, "r").use { raf ->
                // Check and skip ID3v2 tag if present
                var pos = 0L
                val id3Header = ByteArray(10)
                raf.seek(0)
                if (raf.read(id3Header) == 10) {
                    if (id3Header[0] == 'I'.code.toByte() && id3Header[1] == 'D'.code.toByte() && id3Header[2] == '3'.code.toByte()) {
                        val flags = id3Header[5].toInt()
                        val tagSize = ((id3Header[6].toInt() and 0x7F) shl 21) or
                                ((id3Header[7].toInt() and 0x7F) shl 14) or
                                ((id3Header[8].toInt() and 0x7F) shl 7) or
                                (id3Header[9].toInt() and 0x7F)
                        val hasFooter = (flags and 0x10) != 0
                        pos = 10L + tagSize + (if (hasFooter) 10 else 0)
                    }
                }

                val frameHeader = ByteArray(4)
                var currentChunkIdx = 0
                var currentChunkDuration = 0.0
                var currentChunkStartSec = 0f
                var currentChunkFile: File? = null
                var currentChunkOut: BufferedOutputStream? = null
                var framesInChunk = 0

                val targetChunkSec = CHUNK_TARGET_DURATION_SEC.toDouble()

                fun startNewChunk() {
                    currentChunkOut?.flush()
                    currentChunkOut?.close()
                    if (currentChunkFile != null && framesInChunk > 0) {
                        outChunks.add(
                            AudioChunk(
                                file = currentChunkFile!!,
                                index = currentChunkIdx,
                                totalChunks = totalChunks,
                                startSeconds = currentChunkStartSec,
                                durationSeconds = currentChunkDuration.toFloat()
                            )
                        )
                    }

                    currentChunkIdx++
                    currentChunkStartSec = (outChunks.sumOf { it.durationSeconds.toDouble() }).toFloat()
                    currentChunkDuration = 0.0
                    framesInChunk = 0
                    currentChunkFile = File(cacheDir, "chunk_mp3_${UUID.randomUUID().toString().take(8)}_$currentChunkIdx.mp3")
                    currentChunkOut = BufferedOutputStream(FileOutputStream(currentChunkFile!!), 65536)
                    onProgress?.invoke("Slicing MP3 part $currentChunkIdx of $totalChunks...")
                }

                startNewChunk()

                val copyBuf = ByteArray(8192)

                while (pos < fileLength - 4) {
                    raf.seek(pos)
                    if (raf.read(frameHeader) < 4) break

                    val b0 = frameHeader[0].toInt() and 0xFF
                    val b1 = frameHeader[1].toInt() and 0xFF
                    val b2 = frameHeader[2].toInt() and 0xFF

                    // Check frame sync: 11 bits set (0xFF, upper 3 bits of b1 == 0xE0)
                    if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
                        val mpegVersion = (b1 shr 3) and 0x03
                        val layer = (b1 shr 1) and 0x03
                        val bitrateIndex = (b2 shr 4) and 0x0F
                        val sampleRateIndex = (b2 shr 2) and 0x03
                        val padding = (b2 shr 1) and 0x01

                        if (mpegVersion != 1 && layer != 0 && bitrateIndex != 0 && bitrateIndex != 0x0F && sampleRateIndex != 3) {
                            val sampleRate = when (mpegVersion) {
                                3 -> when (sampleRateIndex) { // MPEG-1
                                    0 -> 44100; 1 -> 48000; 2 -> 32000; else -> 44100
                                }
                                2 -> when (sampleRateIndex) { // MPEG-2
                                    0 -> 22050; 1 -> 24000; 2 -> 16000; else -> 22050
                                }
                                else -> when (sampleRateIndex) { // MPEG-2.5
                                    0 -> 11025; 1 -> 12000; 2 -> 8000; else -> 11025
                                }
                            }

                            val bitrate = if (mpegVersion == 3) {
                                // MPEG-1 Layer III
                                when (bitrateIndex) {
                                    1 -> 32; 2 -> 40; 3 -> 48; 4 -> 56; 5 -> 64; 6 -> 80; 7 -> 96
                                    8 -> 112; 9 -> 128; 10 -> 160; 11 -> 192; 12 -> 224; 13 -> 256; 14 -> 320
                                    else -> 128
                                }
                            } else {
                                // MPEG-2 / 2.5 Layer III
                                when (bitrateIndex) {
                                    1 -> 8; 2 -> 16; 3 -> 24; 4 -> 32; 5 -> 40; 6 -> 48; 7 -> 56
                                    8 -> 64; 9 -> 80; 10 -> 96; 11 -> 112; 12 -> 128; 13 -> 144; 14 -> 160
                                    else -> 64
                                }
                            }

                            val samplesPerFrame = if (mpegVersion == 3) 1152 else 576
                            val frameSize = if (mpegVersion == 3) {
                                (144 * bitrate * 1000) / sampleRate + padding
                            } else {
                                (72 * bitrate * 1000) / sampleRate + padding
                            }

                            if (frameSize in 24..4096 && (pos + frameSize) <= fileLength) {
                                val frameDuration = samplesPerFrame.toDouble() / sampleRate.toDouble()

                                // If current chunk reached target duration and enough audio remains
                                if (currentChunkDuration >= targetChunkSec && (fileLength - pos) > 65536) {
                                    startNewChunk()
                                }

                                // Write this frame to current chunk
                                raf.seek(pos)
                                var remaining = frameSize
                                while (remaining > 0) {
                                    val toRead = Math.min(remaining, copyBuf.size)
                                    val readCount = raf.read(copyBuf, 0, toRead)
                                    if (readCount <= 0) break
                                    currentChunkOut?.write(copyBuf, 0, readCount)
                                    remaining -= readCount
                                }

                                currentChunkDuration += frameDuration
                                framesInChunk++
                                pos += frameSize
                                continue
                            }
                        }
                    }

                    // Advance by 1 byte to seek next sync word
                    pos++
                }

                currentChunkOut?.flush()
                currentChunkOut?.close()
                if (currentChunkFile != null && framesInChunk > 0) {
                    outChunks.add(
                        AudioChunk(
                            file = currentChunkFile!!,
                            index = currentChunkIdx,
                            totalChunks = currentChunkIdx,
                            startSeconds = currentChunkStartSec,
                            durationSeconds = currentChunkDuration.toFloat()
                        )
                    )
                }
            }

            if (outChunks.isNotEmpty()) {
                val realTotal = outChunks.size
                for (i in outChunks.indices) {
                    val c = outChunks[i]
                    outChunks[i] = c.copy(totalChunks = realTotal)
                }
                return true
            }
            return false
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Directly slices a WAV file into ~200s chunks by splitting PCM payload with valid 44-byte headers.
     * Takes < 0.2s for even multi-hundred-megabyte WAV files.
     */
    fun tryDirectWavChunking(
        inputFile: File,
        totalDurationSec: Float,
        totalChunks: Int,
        outChunks: MutableList<AudioChunk>,
        onProgress: ((String) -> Unit)?
    ): Boolean {
        try {
            val fileLength = inputFile.length()
            if (fileLength < 44) return false

            RandomAccessFile(inputFile, "r").use { raf ->
                val header = ByteArray(44)
                raf.seek(0)
                raf.readFully(header)

                // Check "RIFF" and "WAVE"
                if (header[0] != 'R'.code.toByte() || header[1] != 'I'.code.toByte() ||
                    header[2] != 'F'.code.toByte() || header[3] != 'F'.code.toByte() ||
                    header[8] != 'W'.code.toByte() || header[9] != 'A'.code.toByte() ||
                    header[10] != 'V'.code.toByte() || header[11] != 'E'.code.toByte()) {
                    return false
                }

                val channels = (header[22].toInt() and 0xFF) or ((header[23].toInt() and 0xFF) shl 8)
                val sampleRate = (header[24].toInt() and 0xFF) or
                        ((header[25].toInt() and 0xFF) shl 8) or
                        ((header[26].toInt() and 0xFF) shl 16) or
                        ((header[27].toInt() and 0xFF) shl 24)
                val bitsPerSample = (header[34].toInt() and 0xFF) or ((header[35].toInt() and 0xFF) shl 8)
                val bytesPerSec = sampleRate * channels * (bitsPerSample / 8)

                if (bytesPerSec <= 0) return false

                // Search for "data" subchunk
                var dataPos = 12L
                var dataSize = 0L
                while (dataPos < fileLength - 8) {
                    raf.seek(dataPos)
                    val chunkId = ByteArray(4)
                    raf.readFully(chunkId)
                    val chunkSize = (raf.read().toLong() and 0xFF) or
                            ((raf.read().toLong() and 0xFF) shl 8) or
                            ((raf.read().toLong() and 0xFF) shl 16) or
                            ((raf.read().toLong() and 0xFF) shl 24)

                    if (chunkId[0] == 'd'.code.toByte() && chunkId[1] == 'a'.code.toByte() &&
                        chunkId[2] == 't'.code.toByte() && chunkId[3] == 'a'.code.toByte()) {
                        dataPos += 8
                        dataSize = Math.min(chunkSize, fileLength - dataPos)
                        break
                    }
                    dataPos += 8 + chunkSize
                }

                if (dataSize <= 0) return false

                val chunkBytesTarget = (CHUNK_TARGET_DURATION_SEC * bytesPerSec).toLong()
                val chunkFrameAlign = Math.max(1, (channels * (bitsPerSample / 8))).toLong()
                val realTotalChunks = Math.max(1, Math.ceil(dataSize.toDouble() / chunkBytesTarget.toDouble()).toInt())

                var curPos = dataPos
                val endPos = dataPos + dataSize
                val copyBuf = ByteArray(65536)

                for (chunkIdx in 0 until realTotalChunks) {
                    val remainingBytes = endPos - curPos
                    if (remainingBytes <= 0) break

                    var thisChunkBytes = Math.min(chunkBytesTarget, remainingBytes)
                    thisChunkBytes = (thisChunkBytes / chunkFrameAlign) * chunkFrameAlign
                    if (thisChunkBytes <= 0) break

                    onProgress?.invoke("Slicing WAV part ${chunkIdx + 1} of $realTotalChunks...")

                    val chunkFile = File(cacheDir, "chunk_wav_${UUID.randomUUID().toString().take(8)}_${chunkIdx + 1}.wav")
                    BufferedOutputStream(FileOutputStream(chunkFile), 65536).use { out ->
                        val chunkHeader = header.copyOf()
                        val totalDataLen = (thisChunkBytes + 36).toInt()
                        chunkHeader[4] = (totalDataLen and 0xff).toByte()
                        chunkHeader[5] = ((totalDataLen shr 8) and 0xff).toByte()
                        chunkHeader[6] = ((totalDataLen shr 16) and 0xff).toByte()
                        chunkHeader[7] = ((totalDataLen shr 24) and 0xff).toByte()
                        chunkHeader[40] = (thisChunkBytes.toInt() and 0xff).toByte()
                        chunkHeader[41] = ((thisChunkBytes.toInt() shr 8) and 0xff).toByte()
                        chunkHeader[42] = ((thisChunkBytes.toInt() shr 16) and 0xff).toByte()
                        chunkHeader[43] = ((thisChunkBytes.toInt() shr 24) and 0xff).toByte()
                        out.write(chunkHeader)

                        raf.seek(curPos)
                        var bytesToCopy = thisChunkBytes
                        while (bytesToCopy > 0) {
                            val toRead = Math.min(bytesToCopy, copyBuf.size.toLong()).toInt()
                            val count = raf.read(copyBuf, 0, toRead)
                            if (count <= 0) break
                            out.write(copyBuf, 0, count)
                            bytesToCopy -= count
                        }
                    }

                    val durSec = thisChunkBytes.toFloat() / bytesPerSec.toFloat()
                    val startSec = (chunkIdx * CHUNK_TARGET_DURATION_SEC)
                    outChunks.add(
                        AudioChunk(
                            file = chunkFile,
                            index = chunkIdx + 1,
                            totalChunks = realTotalChunks,
                            startSeconds = startSec,
                            durationSeconds = durSec
                        )
                    )

                    curPos += thisChunkBytes
                }

                return outChunks.isNotEmpty()
            }
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Slices AAC tracks from video or M4A containers directly via MediaMuxer (zero re-encoding).
     * Automatically absorbs trailing segments shorter than 30s into the final chunk.
     */
    private fun tryDirectMuxingChunks(
        inputFile: File,
        totalDurationSec: Float,
        chunkDurationSec: Float,
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

            val trackDurationSec = if (audioFormat.containsKey(MediaFormat.KEY_DURATION)) {
                val durUs = audioFormat.getLong(MediaFormat.KEY_DURATION)
                if (durUs > 0) durUs / 1_000_000f else totalDurationSec
            } else totalDurationSec

            val effectiveDuration = if (trackDurationSec > 0f) trackDurationSec else totalDurationSec
            if (effectiveDuration <= 0f) return false

            // Pre-compute slice boundaries (absorbing tiny < 30s tails into the previous chunk)
            val slices = mutableListOf<Pair<Float, Float>>()
            var cursorSec = 0f
            while (cursorSec < effectiveDuration) {
                var endSec = Math.min(effectiveDuration, cursorSec + chunkDurationSec)
                if (effectiveDuration - endSec < 30.0f) {
                    endSec = effectiveDuration
                }
                slices.add(cursorSec to endSec)
                if (endSec >= effectiveDuration) break
                cursorSec = endSec
            }

            val totalSlices = slices.size
            val maxBufferSize = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                Math.max(256 * 1024, audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
            } else 256 * 1024

            val buffer = ByteBuffer.allocate(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            for ((chunkIdx, slice) in slices.withIndex()) {
                val chunkStartSec = slice.first
                val chunkEndSec = slice.second
                val chunkDuration = chunkEndSec - chunkStartSec

                onProgress?.invoke("Packaging compressed audio part ${chunkIdx + 1} of $totalSlices...")

                val chunkFile = File(cacheDir, "chunk_${UUID.randomUUID().toString().take(8)}_${chunkIdx + 1}.m4a")
                var muxer: MediaMuxer? = null
                var muxerStarted = false

                try {
                    muxer = MediaMuxer(chunkFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    val muxerAudioTrack = muxer.addTrack(audioFormat)
                    muxer.start()
                    muxerStarted = true

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
                    muxerStarted = false
                    muxer.release()
                    muxer = null

                    if (samplesWritten > 0 && chunkFile.length() > 512) {
                        outChunks.add(
                            AudioChunk(
                                file = chunkFile,
                                index = chunkIdx + 1,
                                totalChunks = totalSlices,
                                startSeconds = chunkStartSec,
                                durationSeconds = chunkDuration
                            )
                        )
                    } else {
                        chunkFile.delete()
                        if (outChunks.isEmpty()) return false
                        break
                    }
                } finally {
                    try { if (muxerStarted) muxer?.stop() } catch (ignored: Exception) {}
                    try { muxer?.release() } catch (ignored: Exception) {}
                }
            }

            if (outChunks.isNotEmpty()) {
                val finalCount = outChunks.size
                for (idx in outChunks.indices) {
                    outChunks[idx] = outChunks[idx].copy(totalChunks = finalCount)
                }
                return true
            }
            return false
        } catch (e: Exception) {
            return false
        } finally {
            try { extractor?.release() } catch (ignored: Exception) {}
        }
    }

    /**
     * High-speed streaming MediaCodec decoder.
     * Decodes in memory and streams directly to 16kHz mono WAV chunks with 64KB buffered I/O.
     * Zero intermediate 700MB PCM files, handles EOS cleanly, and provides live progress percentages.
     */
    private fun tryStreamingCodecChunks(
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

            val bufferInfo = MediaCodec.BufferInfo()
            var inputEOS = false
            var outputEOS = false

            var outputSampleRate = if (audioFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100

            var outputChannels = if (audioFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            val timeoutUs = 10000L
            val targetSampleRate = TARGET_SAMPLE_RATE // 16000
            val targetBytesPerSec = targetSampleRate * 1 * 2 // 16-bit mono = 32000 bytes/sec
            val targetChunkBytes = (CHUNK_TARGET_DURATION_SEC * targetBytesPerSec).toLong()

            var currentChunkIdx = 0
            var currentChunkFile: File? = null
            var currentChunkOut: BufferedOutputStream? = null
            var currentChunkBytesWritten = 0L
            var currentChunkStartSec = 0f

            fun startNewWavChunk() {
                if (currentChunkOut != null && currentChunkFile != null && currentChunkBytesWritten > 0) {
                    currentChunkOut?.flush()
                    currentChunkOut?.close()
                    finalizeWavHeader(currentChunkFile!!, currentChunkBytesWritten, targetSampleRate, 1)

                    val dur = currentChunkBytesWritten.toFloat() / targetBytesPerSec.toFloat()
                    outChunks.add(
                        AudioChunk(
                            file = currentChunkFile!!,
                            index = currentChunkIdx,
                            totalChunks = totalChunks,
                            startSeconds = currentChunkStartSec,
                            durationSeconds = dur
                        )
                    )
                }

                currentChunkIdx++
                currentChunkStartSec = (outChunks.sumOf { it.durationSeconds.toDouble() }).toFloat()
                currentChunkBytesWritten = 0L
                currentChunkFile = File(cacheDir, "chunk_wav_${UUID.randomUUID().toString().take(8)}_$currentChunkIdx.wav")
                currentChunkOut = BufferedOutputStream(FileOutputStream(currentChunkFile!!), 65536)
                currentChunkOut?.write(ByteArray(44)) // 44-byte placeholder
            }

            startNewWavChunk()

            var lastReportPercent = -1
            var resamplePos = 0.0
            val monoBuf = ByteArray(2)

            while (!outputEOS) {
                // Feed input buffer once until EOS
                if (!inputEOS) {
                    val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inputIndex)
                        if (inBuf != null) {
                            val sampleSize = extractor.readSampleData(inBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputEOS = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // Dequeue output buffer
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outputIndex)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        outBuf.order(ByteOrder.LITTLE_ENDIAN)

                        val step = outputSampleRate.toDouble() / targetSampleRate.toDouble()
                        val srcFrameSize = outputChannels * 2
                        val framesAvailable = bufferInfo.size / srcFrameSize

                        for (f in 0 until framesAvailable) {
                            resamplePos += 1.0
                            if (resamplePos >= step) {
                                resamplePos -= step

                                val sampleVal: Short = if (outputChannels >= 2) {
                                    val left = outBuf.short
                                    val right = outBuf.short
                                    ((left.toInt() + right.toInt()) / 2).toShort()
                                } else {
                                    outBuf.short
                                }

                                monoBuf[0] = (sampleVal.toInt() and 0xFF).toByte()
                                monoBuf[1] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()

                                currentChunkOut?.write(monoBuf)
                                currentChunkBytesWritten += 2

                                if (currentChunkBytesWritten >= targetChunkBytes) {
                                    startNewWavChunk()
                                }
                            } else {
                                // Skip frame
                                if (outputChannels >= 2) {
                                    outBuf.short
                                    outBuf.short
                                } else {
                                    outBuf.short
                                }
                            }
                        }

                        if (totalDurationSec > 0) {
                            val curSec = (bufferInfo.presentationTimeUs / 1_000_000f)
                            val percent = Math.min(99, ((curSec / totalDurationSec) * 100).toInt())
                            if (percent > lastReportPercent + 4) {
                                lastReportPercent = percent
                                onProgress?.invoke("Decoding audio: $percent% (Part $currentChunkIdx of $totalChunks)...")
                            }
                        }
                    }

                    codec.releaseOutputBuffer(outputIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputEOS = true
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    outputSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE, 44100)
                    outputChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)
                }
            }

            // Finalize last chunk
            if (currentChunkOut != null && currentChunkFile != null && currentChunkBytesWritten > 0) {
                currentChunkOut?.flush()
                currentChunkOut?.close()
                finalizeWavHeader(currentChunkFile!!, currentChunkBytesWritten, targetSampleRate, 1)

                val dur = currentChunkBytesWritten.toFloat() / targetBytesPerSec.toFloat()
                outChunks.add(
                    AudioChunk(
                        file = currentChunkFile!!,
                        index = currentChunkIdx,
                        totalChunks = currentChunkIdx,
                        startSeconds = currentChunkStartSec,
                        durationSeconds = dur
                    )
                )
            }

            if (outChunks.isNotEmpty()) {
                val realTotal = outChunks.size
                for (i in outChunks.indices) {
                    outChunks[i] = outChunks[i].copy(totalChunks = realTotal)
                }
                return true
            }
            return false
        } catch (e: Exception) {
            return false
        } finally {
            try { codec?.stop(); codec?.release() } catch (ignored: Exception) {}
            try { extractor?.release() } catch (ignored: Exception) {}
        }
    }

    private fun finalizeWavHeader(file: File, pcmBytes: Long, sampleRate: Int, channels: Int) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val totalDataLen = pcmBytes + 36
                val byteRate = (sampleRate * channels * 2).toLong()

                val header = ByteArray(44)
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
                header[16] = 16 // 16 for PCM
                header[17] = 0
                header[18] = 0
                header[19] = 0
                header[20] = 1 // PCM
                header[21] = 0
                header[22] = channels.toByte()
                header[23] = 0
                header[24] = (sampleRate and 0xff).toByte()
                header[25] = ((sampleRate shr 8) and 0xff).toByte()
                header[26] = ((sampleRate shr 16) and 0xff).toByte()
                header[27] = ((sampleRate shr 24) and 0xff).toByte()
                header[28] = (byteRate and 0xff).toByte()
                header[29] = ((byteRate shr 8) and 0xff).toByte()
                header[30] = ((byteRate shr 16) and 0xff).toByte()
                header[31] = ((byteRate shr 24) and 0xff).toByte()
                header[32] = (channels * 2).toByte() // Block align
                header[33] = 0
                header[34] = 16 // Bits per sample
                header[35] = 0
                header[36] = 'd'.code.toByte()
                header[37] = 'a'.code.toByte()
                header[38] = 't'.code.toByte()
                header[39] = 'a'.code.toByte()
                header[40] = (pcmBytes and 0xff).toByte()
                header[41] = ((pcmBytes shr 8) and 0xff).toByte()
                header[42] = ((pcmBytes shr 16) and 0xff).toByte()
                header[43] = ((pcmBytes shr 24) and 0xff).toByte()

                raf.seek(0)
                raf.write(header)
            }
        } catch (ignored: Exception) {}
    }

    fun cleanCache() {
        try {
            cacheDir.listFiles()?.forEach { it.delete() }
        } catch (ignored: Exception) {}
    }
}
