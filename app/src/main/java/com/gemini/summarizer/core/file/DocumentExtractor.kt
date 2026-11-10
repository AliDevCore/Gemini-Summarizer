package com.gemini.summarizer.core.file

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
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
                    // PDF files are ingested directly into Gemini via native inlineData (Base64)
                    Result.success(
                        ExtractedDocument(
                            fileName = fileName,
                            text = "[Native PDF Document]",
                            isScannedOrEmpty = false
                        )
                    )
                }
                else -> {
                    // Fallback to plain text attempt
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
}

