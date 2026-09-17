package com.borderless.ankicards.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * A deliberately small Markdown renderer — just the subset our LLM prompts
 * actually emit, so we don't pull in a full Markdown dependency for it.
 *
 * Supports, block-level: ATX headings (`#`/`##`/`###`), unordered bullets
 * (`- `/`* `), ordered lists (`1. `), blank-line spacing, and plain
 * paragraphs. Inline: `**bold**`, `*italic*`/`_italic_`, and `` `code` ``.
 * Everything else renders as literal text.
 *
 * Each line becomes its own `Text` so block structure is preserved without a
 * parser. Fine for the short explanations we render; not meant for documents.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    baseStyle: TextStyle = MaterialTheme.typography.bodyMedium
) {
    val lines = remember(markdown) { markdown.replace("\r\n", "\n").split("\n") }
    val headingStyle = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
    val bigHeadingStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        lines.forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> Spacer(Modifier.height(2.dp))
                line.startsWith("### ") ->
                    Text(parseInlineMarkdown(line.removePrefix("### ")), color = color, style = headingStyle)
                line.startsWith("## ") ->
                    Text(parseInlineMarkdown(line.removePrefix("## ")), color = color, style = headingStyle)
                line.startsWith("# ") ->
                    Text(parseInlineMarkdown(line.removePrefix("# ")), color = color, style = bigHeadingStyle)
                line.startsWith("- ") || line.startsWith("* ") ->
                    MarkdownListItem("•", parseInlineMarkdown(line.drop(2)), color, baseStyle)
                else -> {
                    val numbered = NumberedPrefix.find(line)
                    if (numbered != null) {
                        MarkdownListItem(
                            marker = numbered.value.trim(),
                            content = parseInlineMarkdown(line.removePrefix(numbered.value)),
                            color = color,
                            style = baseStyle
                        )
                    } else {
                        Text(parseInlineMarkdown(line), color = color, style = baseStyle)
                    }
                }
            }
        }
    }
}

private val NumberedPrefix = Regex("^\\d+\\.\\s+")

@Composable
private fun MarkdownListItem(
    marker: String,
    content: AnnotatedString,
    color: Color,
    style: TextStyle
) {
    Row {
        Text(marker, color = color, style = style, modifier = Modifier.padding(end = 6.dp))
        Text(content, color = color, style = style, modifier = Modifier.weight(1f))
    }
}

/**
 * Parse inline emphasis (`**bold**`, `*italic*`/`_italic_`, `` `code` ``) into
 * an [AnnotatedString]. Unterminated markers render as literal characters.
 */
internal fun parseInlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = text.indexOf("**", i + 2)
                if (end < 0) {
                    append(text.substring(i)); i = text.length
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text.substring(i + 2, end)) }
                    i = end + 2
                }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end < 0) {
                    append(text[i]); i++
                } else {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                }
            }
            text[i] == '*' || text[i] == '_' -> {
                val marker = text[i]
                val end = text.indexOf(marker, i + 1)
                if (end < 0) {
                    append(text[i]); i++
                } else {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                    i = end + 1
                }
            }
            else -> {
                append(text[i]); i++
            }
        }
    }
}
