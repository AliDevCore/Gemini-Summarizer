package com.gemini.summarizer.core.export

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
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
        val pageWidth = 595
        val pageHeight = 842
        val marginX = 40f
        val marginY = 50f
        val contentWidth = (pageWidth - 2 * marginX).toInt()
        val maxY = pageHeight - marginY

        var pageIndex = 1
        var pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex).create()
        var page = pdfDocument.startPage(pageInfo)
        var canvas = page.canvas

        val paintTitle = Paint().apply {
            color = Color.BLACK
            textSize = 15f
            isFakeBoldText = true
            isAntiAlias = true
        }

        val paintSub = Paint().apply {
            color = Color.DKGRAY
            textSize = 9f
            isAntiAlias = true
        }

        val bodyPaint = TextPaint().apply {
            color = Color.BLACK
            textSize = 10.5f
            isAntiAlias = true
        }

        var currentY = marginY

        // Header on First Page
        canvas.drawText(title, marginX, currentY, paintTitle)
        currentY += 18f
        canvas.drawText("Generated on $date | Model: $model | Account: $account", marginX, currentY, paintSub)
        currentY += 8f
        canvas.drawLine(marginX, currentY, pageWidth - marginX, currentY, paintSub)
        currentY += 20f

        val rawParagraphs = content.split("\n")
        val paragraphs = mutableListOf<String>()
        for (raw in rawParagraphs) {
            val cleaned = raw
                .replace(Regex("""\*\*(.*?)\*\*"""), "$1")
                .replace(Regex("""^#{1,6}\s*"""), "")
                .replace(Regex("""`(.*?)`"""), "$1")
            if (cleaned.length > 1500) {
                // Split oversized paragraphs that might exceed an entire page height
                var remaining = cleaned
                while (remaining.isNotEmpty()) {
                    if (remaining.length <= 1500) {
                        paragraphs.add(remaining)
                        break
                    }
                    var splitPoint = remaining.lastIndexOf(". ", 1500)
                    if (splitPoint == -1 || splitPoint < 750) {
                        splitPoint = remaining.lastIndexOf(" ", 1500)
                    }
                    if (splitPoint == -1) splitPoint = 1500
                    paragraphs.add(remaining.substring(0, splitPoint).trim())
                    remaining = remaining.substring(splitPoint).trim()
                }
            } else {
                paragraphs.add(cleaned)
            }
        }

        val isOverallRtl = content.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' }

        for (paragraph in paragraphs) {
            val textToDraw = paragraph.ifEmpty { " " }
            val isParagraphRtl = textToDraw.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' }

            val alignment = if (isParagraphRtl) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL

            val layout = StaticLayout.Builder.obtain(textToDraw, 0, textToDraw.length, bodyPaint, contentWidth)
                .setAlignment(alignment)
                .setLineSpacing(2f, 1.15f)
                .setIncludePad(true)
                .build()

            // Check if we need to start a new page
            if (currentY + layout.height > maxY) {
                pdfDocument.finishPage(page)
                pageIndex++
                pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageIndex).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas
                currentY = marginY
            }

            canvas.save()
            canvas.translate(marginX, currentY)
            layout.draw(canvas)
            canvas.restore()

            currentY += layout.height + 6f
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
        val isOverallRtl = content.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' }
        val bidiTag = if (isOverallRtl) "<w:bidi/>" else ""
        val rtlTag = if (isOverallRtl) "<w:rtl/>" else ""
        val alignTag = if (isOverallRtl) "<w:jc w:val=\"right\"/>" else "<w:jc w:val=\"left\"/>"

        val bodyXml = StringBuilder()

        // Document Title
        bodyXml.append("""
            <w:p>
              <w:pPr>$bidiTag$alignTag</w:pPr>
              <w:r><w:rPr><w:b/><w:sz w:val="32"/>$rtlTag</w:rPr><w:t xml:space="preserve">${escapeXml(title)}</w:t></w:r>
            </w:p>
            <w:p>
              <w:pPr>$bidiTag$alignTag</w:pPr>
              <w:r><w:rPr><w:i/><w:sz w:val="18"/><w:color w:val="666666"/>$rtlTag</w:rPr><w:t xml:space="preserve">Date: ${escapeXml(date)} | Model: ${escapeXml(model)} | Account: ${escapeXml(account)}</w:t></w:r>
            </w:p>
            <w:p><w:pPr>$bidiTag$alignTag</w:pPr></w:p>
        """.trimIndent())

        for (line in content.split("\n")) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                bodyXml.append("<w:p><w:pPr>$bidiTag$alignTag</w:pPr></w:p>\n")
                continue
            }

            val isHeader = trimmed.startsWith("#")
            val isBullet = trimmed.startsWith("* ") || trimmed.startsWith("- ") || trimmed.startsWith("• ")

            val textWithoutPrefix = when {
                isHeader -> trimmed.removePrefix("###").removePrefix("##").removePrefix("#").trim()
                isBullet -> "• " + trimmed.substring(2).trim()
                else -> trimmed
            }

            val bulletIndent = if (isBullet) "<w:ind w:left=\"360\"/>" else ""
            val runsXml = buildRunsXml(textWithoutPrefix, isHeader, rtlTag)

            bodyXml.append("<w:p><w:pPr>$bidiTag$alignTag$bulletIndent</w:pPr>$runsXml</w:p>\n")
        }

        val documentXml = createDocumentXml(bodyXml.toString())

        val contentTypesXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n" +
            "  <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n" +
            "  <Default Extension=\"xml\" ContentType=\"application/xml\"/>\n" +
            "  <Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>\n" +
            "</Types>"

        val relsXml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
            "  <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>\n" +
            "</Relationships>"

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

    companion object {
        fun createDocumentXml(bodyXml: String): String {
            return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">\n" +
                "  <w:body>\n" +
                bodyXml +
                "    <w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>\n" +
                "  </w:body>\n" +
                "</w:document>"
        }

        private fun buildRunsXml(text: String, isHeader: Boolean, rtlTag: String): String {
            val runsBuilder = StringBuilder()
            val boldRegex = Regex("""\*\*(.*?)\*\*""")
            var lastIndex = 0
            val matches = boldRegex.findAll(text)

            val headingSize = if (isHeader) "<w:sz w:val=\"26\"/><w:b/>" else "<w:sz w:val=\"22\"/>"

            for (match in matches) {
                if (match.range.first > lastIndex) {
                    val normalPart = text.substring(lastIndex, match.range.first)
                    if (normalPart.isNotEmpty()) {
                        runsBuilder.append("<w:r><w:rPr>$headingSize$rtlTag</w:rPr><w:t xml:space=\"preserve\">${escapeXml(normalPart)}</w:t></w:r>")
                    }
                }
                val boldContent = match.groupValues[1]
                runsBuilder.append("<w:r><w:rPr>$headingSize<w:b/><w:bCs/>$rtlTag</w:rPr><w:t xml:space=\"preserve\">${escapeXml(boldContent)}</w:t></w:r>")
                lastIndex = match.range.last + 1
            }

            if (lastIndex < text.length) {
                val remainingPart = text.substring(lastIndex)
                if (remainingPart.isNotEmpty()) {
                    runsBuilder.append("<w:r><w:rPr>$headingSize$rtlTag</w:rPr><w:t xml:space=\"preserve\">${escapeXml(remainingPart)}</w:t></w:r>")
                }
            }

            return runsBuilder.toString()
        }

        fun escapeXml(input: String): String {
            return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")
        }
    }
}

