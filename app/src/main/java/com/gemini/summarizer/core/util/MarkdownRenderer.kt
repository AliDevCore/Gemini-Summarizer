package com.gemini.summarizer.core.util

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object MarkdownRenderer {

    fun parse(markdown: String): AnnotatedString {
        return buildAnnotatedString {
            val lines = markdown.split("\n")
            for ((lineIndex, rawLine) in lines.withIndex()) {
                var line = rawLine

                // 1. Convert bullet points (* or -) at start of line to bullet dots
                val trimmed = line.trimStart()
                val isBullet = trimmed.startsWith("* ") || trimmed.startsWith("- ")
                if (isBullet) {
                    val indentCount = line.length - trimmed.length
                    val indent = " ".repeat(indentCount)
                    line = "$indent• " + trimmed.substring(2)
                }

                // 2. Check markdown headers (#, ##, ###)
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

                // 3. Parse formatted spans (Bold, Italic, Inline Code)
                parseInlineTokens(line)

                if (isHeader) {
                    pop()
                }

                if (lineIndex < lines.size - 1) {
                    append("\n")
                }
            }
        }
    }

    private fun AnnotatedString.Builder.parseInlineTokens(text: String) {
        // Match bold **text**, inline code `text`, and italic *text* or _text_
        val tokenRegex = Regex("""(\*\*.*?\*\*|`.*?`|\*.*?\*|_.*?_)""")
        var lastIndex = 0
        val matches = tokenRegex.findAll(text)

        for (match in matches) {
            if (match.range.first > lastIndex) {
                append(text.substring(lastIndex, match.range.first))
            }

            val token = match.value
            when {
                token.startsWith("**") && token.endsWith("**") && token.length >= 4 -> {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(token.substring(2, token.length - 2))
                    pop()
                }
                token.startsWith("`") && token.endsWith("`") && token.length >= 2 -> {
                    pushStyle(SpanStyle(fontFamily = FontFamily.Monospace))
                    append(token.substring(1, token.length - 1))
                    pop()
                }
                (token.startsWith("*") && token.endsWith("*") && token.length >= 2) ||
                (token.startsWith("_") && token.endsWith("_") && token.length >= 2) -> {
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    append(token.substring(1, token.length - 1))
                    pop()
                }
                else -> {
                    append(token)
                }
            }

            lastIndex = match.range.last + 1
        }

        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }
}

