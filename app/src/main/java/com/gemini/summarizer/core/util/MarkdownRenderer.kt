package com.gemini.summarizer.core.util

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object MarkdownRenderer {

    fun parse(markdown: String): AnnotatedString {
        return buildAnnotatedString {
            val lines = markdown.split("\n")
            for ((lineIndex, rawLine) in lines.withIndex()) {
                var line = rawLine

                // Convert bullet points (* or -) at start of line to bullet dots
                val trimmed = line.trimStart()
                val isBullet = trimmed.startsWith("* ") || trimmed.startsWith("- ")
                if (isBullet) {
                    val indentCount = line.length - trimmed.length
                    val indent = " ".repeat(indentCount)
                    line = "$indent• " + trimmed.substring(2)
                }

                // Check markdown headers (#, ##, ###)
                val isHeader = line.trimStart().startsWith("#")
                val headerLevel = if (isHeader) line.takeWhile { it == '#' || it == ' ' }.count { it == '#' } else 0
                if (isHeader) {
                    line = line.dropWhile { it == '#' || it == ' ' }
                }

                if (isHeader) {
                    pushStyle(
                        SpanStyle(
                            fontWeight = FontWeight.Bold,
                            fontSize = if (headerLevel <= 2) 18.sp else 16.sp
                        )
                    )
                }

                // Parse **bold** markdown tokens and strip the asterisks
                val boldRegex = Regex("""\*\*(.*?)\*\*""")
                var lastIndex = 0
                val matches = boldRegex.findAll(line)

                for (match in matches) {
                    if (match.range.first > lastIndex) {
                        append(line.substring(lastIndex, match.range.first))
                    }

                    val boldContent = match.groupValues[1]
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(boldContent)
                    pop()

                    lastIndex = match.range.last + 1
                }

                if (lastIndex < line.length) {
                    append(line.substring(lastIndex))
                }

                if (isHeader) {
                    pop()
                }

                if (lineIndex < lines.size - 1) {
                    append("\n")
                }
            }
        }
    }
}
