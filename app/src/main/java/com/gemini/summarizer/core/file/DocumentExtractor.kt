package com.gemini.summarizer.core.file

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

data class ExtractedDocument(
    val fileName: String,
    val text: String,
    val pageCount: Int = 1,
    val isScannedOrEmpty: Boolean = false
)

class DocumentExtractor(private val context: Context) {

    suspend fun extractFromUri(uri: Uri): Result<ExtractedDocument> = withContext(Dispatchers.IO) {
        try {
            val fileName = getFileName(uri)
            val extension = fileName.substringAfterLast(".", "").lowercase()

            when (extension) {
                "txt", "md", "markdown", "csv", "html", "htm", "json" -> {
                    val text = readPlainText(uri)
                    Result.success(
                        ExtractedDocument(
                            fileName = fileName,
                            text = text,
                            isScannedOrEmpty = text.isBlank()
                        )
                    )
                }
                "docx" -> {
                    val text = extractDocxText(uri)
                    Result.success(
                        ExtractedDocument(
                            fileName = fileName,
                            text = text,
                            isScannedOrEmpty = text.isBlank()
                        )
                    )
                }
                "pdf" -> {
                    val text = extractPdfText(uri)
                    Result.success(
                        ExtractedDocument(
                            fileName = fileName,
                            text = text,
                            isScannedOrEmpty = text.isBlank()
                        )
                    )
                }
                else -> {
                    val text = readPlainText(uri)
                    if (text.isNotBlank()) {
                        Result.success(ExtractedDocument(fileName = fileName, text = text))
                    } else {
                        Result.failure(IllegalArgumentException("Unsupported or unreadable file format: .$extension"))
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(Exception("Failed to read document: ${e.localizedMessage ?: "Unknown file error"}"))
        }
    }

    private fun getFileName(uri: Uri): String {
        var name = "Document"
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

    private fun readPlainText(uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                return reader.readText()
            }
        }
        return ""
    }

    private fun extractDocxText(uri: Uri): String {
        val stringBuilder = StringBuilder()
        context.contentResolver.openInputStream(uri)?.use { stream ->
            ZipInputStream(stream).use { zipStream ->
                var entry = zipStream.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val factory = XmlPullParserFactory.newInstance()
                        factory.isNamespaceAware = true
                        val parser = factory.newPullParser()
                        parser.setInput(zipStream, "UTF-8")

                        var eventType = parser.eventType
                        while (eventType != XmlPullParser.END_DOCUMENT) {
                            if (eventType == XmlPullParser.START_TAG) {
                                val tag = parser.name
                                if (tag == "t") {
                                    parser.next()
                                    if (parser.eventType == XmlPullParser.TEXT) {
                                        stringBuilder.append(parser.text)
                                    }
                                } else if (tag == "p" || tag == "br") {
                                    stringBuilder.append("\n")
                                }
                            }
                            eventType = parser.next()
                        }
                        break
                    }
                    entry = zipStream.nextEntry
                }
            }
        }
        return stringBuilder.toString().trim()
    }

    private fun extractPdfText(uri: Uri): String {
        val sb = StringBuilder()
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                sb.append(parsePdfTextFromBytes(bytes))
            }
        } catch (_: Exception) {
        }
        return sb.toString().trim()
    }

    private fun parsePdfTextFromBytes(bytes: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        val streamTag = "stream".toByteArray(Charsets.US_ASCII)
        val endStreamTag = "endstream".toByteArray(Charsets.US_ASCII)

        while (i < bytes.size) {
            val streamPos = indexOf(bytes, streamTag, i)
            if (streamPos == -1) break

            val dictStart = (streamPos - 500).coerceAtLeast(0)
            val dictHeader = String(bytes, dictStart, streamPos - dictStart, Charsets.US_ASCII)
            val isFlate = dictHeader.contains("/FlateDecode")

            var dataStart = streamPos + streamTag.size
            if (dataStart < bytes.size && bytes[dataStart] == '\r'.code.toByte()) dataStart++
            if (dataStart < bytes.size && bytes[dataStart] == '\n'.code.toByte()) dataStart++

            val endPos = indexOf(bytes, endStreamTag, dataStart)
            if (endPos == -1) break

            val streamBytes = bytes.copyOfRange(dataStart, endPos)
            val content = if (isFlate) {
                decompressFlate(streamBytes)
            } else {
                String(streamBytes, Charsets.ISO_8859_1)
            }

            if (content.isNotBlank()) {
                extractTextTokens(content, sb)
            }

            i = endPos + endStreamTag.size
        }
        return sb.toString()
    }

    private fun extractTextTokens(content: String, out: StringBuilder) {
        var inParen = false
        var escape = false
        val current = StringBuilder()

        var idx = 0
        while (idx < content.length) {
            val ch = content[idx]
            if (escape) {
                when (ch) {
                    'n' -> current.append('\n')
                    'r' -> current.append('\r')
                    't' -> current.append('\t')
                    'b' -> current.append('\b')
                    'f' -> current.append('\u000C')
                    '(' -> current.append('(')
                    ')' -> current.append(')')
                    '\\' -> current.append('\\')
                    else -> current.append(ch)
                }
                escape = false
            } else if (ch == '\\' && inParen) {
                escape = true
            } else if (ch == '(' && !inParen) {
                inParen = true
                current.setLength(0)
            } else if (ch == ')' && inParen) {
                inParen = false
                val s = current.toString().trim()
                if (s.isNotEmpty()) {
                    out.append(s).append(" ")
                }
            } else if (inParen) {
                current.append(ch)
            }
            idx++
        }
        out.append("\n")
    }

    private fun decompressFlate(bytes: ByteArray): String {
        return try {
            val inflater = Inflater(false)
            inflater.setInput(bytes)
            val buffer = ByteArray(4096)
            val out = ByteArrayOutputStream()
            while (!inflater.finished() && !inflater.needsInput()) {
                val count = inflater.inflate(buffer)
                if (count > 0) {
                    out.write(buffer, 0, count)
                } else break
            }
            inflater.end()
            out.toString("UTF-8")
        } catch (_: Exception) {
            try {
                val inflater = Inflater(true)
                inflater.setInput(bytes)
                val buffer = ByteArray(4096)
                val out = ByteArrayOutputStream()
                while (!inflater.finished() && !inflater.needsInput()) {
                    val count = inflater.inflate(buffer)
                    if (count > 0) {
                        out.write(buffer, 0, count)
                    } else break
                }
                inflater.end()
                out.toString("UTF-8")
            } catch (_: Exception) {
                ""
            }
        }
    }

    private fun indexOf(source: ByteArray, target: ByteArray, fromIndex: Int): Int {
        if (fromIndex >= source.size) return -1
        outer@ for (i in fromIndex..(source.size - target.size)) {
            for (j in target.indices) {
                if (source[i + j] != target[j]) {
                    continue@outer
                }
            }
            return i
        }
        return -1
    }
}
