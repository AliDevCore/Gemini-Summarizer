package com.gemini.summarizer.core.export

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentExporter(private val context: Context) {

    suspend fun exportToUri(
        uri: Uri,
        format: String,
        title: String,
        summaryContent: String,
        modelUsed: String,
        accountUsed: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val header = """
                ============================================================
                $title
                ============================================================
                Date: $dateStr
                Model: $modelUsed | Account: $accountUsed
                ============================================================
                
            """.trimIndent()

            when (format.lowercase()) {
                "txt", "text" -> {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                            writer.write(header)
                            writer.write("\n\n")
                            writer.write(summaryContent)
                        }
                    }
                }
                "md", "markdown" -> {
                    val mdHeader = """
                        # $title
                        
                        - **Date:** `$dateStr`
                        - **Model:** `$modelUsed`
                        - **Account:** `$accountUsed`
                        
                        ---
                        
                    """.trimIndent()
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                            writer.write(mdHeader)
                            writer.write(summaryContent)
                        }
                    }
                }
                "docx" -> {
                    exportAsDocx(uri, title, summaryContent, dateStr, modelUsed, accountUsed)
                }
                "pdf" -> {
                    exportAsPdf(uri, title, summaryContent, dateStr, modelUsed, accountUsed)
                }
                else -> {
                    // Default to TXT
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        OutputStreamWriter(stream, Charsets.UTF_8).use { writer ->
                            writer.write(summaryContent)
                        }
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("Failed to export summary: ${e.localizedMessage ?: "File error"}"))
        }
    }

    private fun exportAsPdf(
        uri: Uri,
        title: String,
        content: String,
        date: String,
        model: String,
        account: String
    ) {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4 dimensions
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        val paintTitle = Paint().apply {
            color = Color.BLACK
            textSize = 16f
            isFakeBoldText = true
        }

        val paintSub = Paint().apply {
            color = Color.DKGRAY
            textSize = 10f
        }

        val paintBody = Paint().apply {
            color = Color.BLACK
            textSize = 11f
        }

        var y = 50f
        canvas.drawText(title, 40f, y, paintTitle)
        y += 20f
        canvas.drawText("Generated on $date | Model: $model | Account: $account", 40f, y, paintSub)
        y += 10f
        canvas.drawLine(40f, y, 555f, y, paintSub)
        y += 25f

        val lines = content.split("\n")
        for (rawLine in lines) {
            // Basic line wrapping
            var start = 0
            val lineLen = rawLine.length
            if (lineLen == 0) {
                y += 15f
                continue
            }
            while (start < lineLen) {
                val end = (start + 75).coerceAtMost(lineLen)
                val chunk = rawLine.substring(start, end)
                canvas.drawText(chunk, 40f, y, paintBody)
                y += 18f
                start = end
                if (y > 800f) break
            }
            if (y > 800f) break
        }

        pdfDocument.finishPage(page)
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            pdfDocument.writeTo(stream)
        }
        pdfDocument.close()
    }

    private fun exportAsDocx(
        uri: Uri,
        title: String,
        content: String,
        date: String,
        model: String,
        account: String
    ) {
        // Build minimal valid DOCX OpenXML archive
        val documentXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p>
                  <w:r><w:rPr><w:b/><w:sz w:val="32"/></w:rPr><w:t>${escapeXml(title)}</w:t></w:r>
                </w:p>
                <w:p>
                  <w:r><w:rPr><w:i/><w:sz w:val="18"/><w:color w:val="555555"/></w:rPr><w:t>Date: ${escapeXml(date)} | Model: ${escapeXml(model)} | Account: ${escapeXml(account)}</w:t></w:r>
                </w:p>
                ${content.split("\n").joinToString("") { line ->
                    "<w:p><w:r><w:t>${escapeXml(line)}</w:t></w:r></w:p>"
                }}
              </w:body>
            </w:document>
        """.trimIndent()

        val contentTypesXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
        """.trimIndent()

        val relsXml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>
        """.trimIndent()

        context.contentResolver.openOutputStream(uri)?.use { stream ->
            ZipOutputStream(stream).use { zip ->
                zip.putNextEntry(ZipEntry("[Content_Types].xml"))
                zip.write(contentTypesXml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("_rels/.rels"))
                zip.write(relsXml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write(documentXml.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun escapeXml(input: String): String {
        return input.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
