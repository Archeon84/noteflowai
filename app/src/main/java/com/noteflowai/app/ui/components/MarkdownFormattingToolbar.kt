package com.noteflowai.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pure Markdown text transformations. Each function takes text + cursor selection,
 * returns new text + new cursor position. No side effects, fully testable.
 */
object MarkdownFormatter {

    // Compiled once — these patterns run for every line of the selected block.
    private val NUMBERED_LINE_REGEX = Regex("^\\d+\\.\\s")

    /** Find the start of the current line containing the cursor. */
    private fun lineStart(text: String, pos: Int): Int {
        val nl = text.lastIndexOf('\n', (pos - 1).coerceAtLeast(0))
        return if (nl < 0) 0 else nl + 1
    }

    /** Find the end of the current line containing the cursor. */
    private fun lineEnd(text: String, pos: Int): Int {
        val nl = text.indexOf('\n', pos)
        return if (nl < 0) text.length else nl
    }

    /** Get the text of the line containing pos. */
    private fun currentLine(text: String, pos: Int): String {
        return text.substring(lineStart(text, pos), lineEnd(text, pos))
    }

    /** Toggle a wrapper marker around selected text, or insert placeholder. */
    private fun toggleWrapper(
        text: String,
        selection: TextRange,
        marker: String,
        placeholder: String
    ): Pair<String, TextRange> {
        val start = selection.min
        val end = selection.max
        val selected = text.substring(start, end)

        return if (selected.isNotEmpty() && selected.startsWith(marker) && selected.endsWith(marker)) {
            // Remove wrapper
            val inner = selected.removePrefix(marker).removeSuffix(marker)
            val newText = text.substring(0, start) + inner + text.substring(end)
            Pair(newText, TextRange(start, start + inner.length))
        } else if (selected.isNotEmpty()) {
            // Wrap selection
            val wrapped = "$marker$selected$marker"
            val newText = text.substring(0, start) + wrapped + text.substring(end)
            Pair(newText, TextRange(start + marker.length, start + marker.length + selected.length))
        } else {
            // Insert placeholder
            val insert = "$marker$placeholder$marker"
            val newText = text.substring(0, start) + insert + text.substring(end)
            val cursorPos = start + marker.length
            Pair(newText, TextRange(cursorPos, cursorPos + placeholder.length))
        }
    }

    fun toggleBold(text: String, selection: TextRange): Pair<String, TextRange> {
        return toggleWrapper(text, selection, "**", "bold text")
    }

    fun toggleItalic(text: String, selection: TextRange): Pair<String, TextRange> {
        return toggleWrapper(text, selection, "*", "italic text")
    }

    fun toggleCode(text: String, selection: TextRange): Pair<String, TextRange> {
        return toggleWrapper(text, selection, "`", "code")
    }

    fun cycleHeading(text: String, selection: TextRange): Pair<String, TextRange> {
        val pos = selection.min
        val ls = lineStart(text, pos)
        val le = lineEnd(text, pos)
        val line = text.substring(ls, le)

        val oldPrefix = when {
            line.startsWith("### ") -> "### "
            line.startsWith("## ") -> "## "
            line.startsWith("# ") -> "# "
            else -> ""
        }
        val body = line.removePrefix(oldPrefix)

        val newPrefix = when (oldPrefix) {
            "### " -> ""
            "## " -> "### "
            "# " -> "## "
            else -> "# "
        }

        val newText = text.substring(0, ls) + newPrefix + body + text.substring(le)
        val cursorOffset = (pos - ls) - oldPrefix.length + newPrefix.length
        return Pair(newText, TextRange(cursorOffset.coerceIn(0, newText.length)))
    }

    fun toggleBulletList(text: String, selection: TextRange): Pair<String, TextRange> {
        val start = selection.min
        val end = selection.max
        val ls = lineStart(text, start)
        val le = if (end > start) lineEnd(text, end - 1) else lineEnd(text, start)
        val block = text.substring(ls, le)

        val lines = block.split("\n")
        val allBulleted = lines.all { it.trimStart().startsWith("- ") || it.isBlank() }

        val newLines = if (allBulleted) {
            lines.map { line ->
                if (line.startsWith("- ")) line.removePrefix("- ")
                else if (line.startsWith("  ")) line.removePrefix("  ")
                else line
            }
        } else {
            lines.map { line ->
                if (line.isBlank()) line
                else if (line.startsWith("- ")) line
                else "- $line"
            }
        }

        val newBlock = newLines.joinToString("\n")
        val newText = text.substring(0, ls) + newBlock + text.substring(le)
        return Pair(newText, TextRange(start + (newBlock.length - block.length) / 2))
    }

    fun toggleNumberedList(text: String, selection: TextRange): Pair<String, TextRange> {
        val start = selection.min
        val end = selection.max
        val ls = lineStart(text, start)
        val le = if (end > start) lineEnd(text, end - 1) else lineEnd(text, start)
        val block = text.substring(ls, le)

        val lines = block.split("\n")
        val allNumbered = lines.all { NUMBERED_LINE_REGEX.containsMatchIn(it) || it.isBlank() }

        val newLines = if (allNumbered) {
            lines.map { line ->
                line.replaceFirst(NUMBERED_LINE_REGEX, "")
            }
        } else {
            var num = 1
            lines.map { line ->
                if (line.isBlank()) line
                else {
                    val result = "$num. $line"
                    num++
                    result
                }
            }
        }

        val newBlock = newLines.joinToString("\n")
        val newText = text.substring(0, ls) + newBlock + text.substring(le)
        return Pair(newText, TextRange(start + (newBlock.length - block.length) / 2))
    }

    fun toggleChecklist(text: String, selection: TextRange): Pair<String, TextRange> {
        val start = selection.min
        val end = selection.max
        val ls = lineStart(text, start)
        val le = if (end > start) lineEnd(text, end - 1) else lineEnd(text, start)
        val block = text.substring(ls, le)

        val lines = block.split("\n")
        val newLines = lines.map { line ->
            when {
                line.startsWith("- [ ] ") -> "- [x] " + line.removePrefix("- [ ] ")
                line.startsWith("- [x] ") -> "- [ ] " + line.removePrefix("- [x] ")
                line.isBlank() -> line
                line.startsWith("- ") -> "- [ ] " + line.removePrefix("- ")
                else -> "- [ ] $line"
            }
        }

        val newBlock = newLines.joinToString("\n")
        val newText = text.substring(0, ls) + newBlock + text.substring(le)
        return Pair(newText, TextRange(start + (newBlock.length - block.length) / 2))
    }

    fun insertLink(text: String, selection: TextRange): Pair<String, TextRange> {
        val start = selection.min
        val end = selection.max
        val selected = text.substring(start, end)

        return if (selected.isNotEmpty()) {
            // Wrap as [text](url)
            val insert = "[$selected](url)"
            val newText = text.substring(0, start) + insert + text.substring(end)
            val cursorPos = start + selected.length + 3 // after ](
            Pair(newText, TextRange(cursorPos, cursorPos + 3))
        } else {
            val insert = "[link text](url)"
            val newText = text.substring(0, start) + insert + text.substring(end)
            Pair(newText, TextRange(start + 1, start + 10))
        }
    }

    fun insertDivider(text: String, selection: TextRange): Pair<String, TextRange> {
        val pos = selection.min
        val insert = "\n\n---\n\n"
        val newText = text.substring(0, pos) + insert + text.substring(pos)
        return Pair(newText, TextRange(pos + insert.length))
    }

    fun insertCodeBlock(text: String, selection: TextRange): Pair<String, TextRange> {
        val start = selection.min
        val end = selection.max
        val selected = text.substring(start, end)

        return if (selected.isNotEmpty()) {
            val insert = "```\n$selected\n```"
            val newText = text.substring(0, start) + insert + text.substring(end)
            Pair(newText, TextRange(start + 4, start + 4 + selected.length))
        } else {
            val insert = "```\ncode here\n```"
            val newText = text.substring(0, start) + insert + text.substring(end)
            Pair(newText, TextRange(start + 4, start + 4 + 9))
        }
    }

    fun insertQuote(text: String, selection: TextRange): Pair<String, TextRange> {
        val start = selection.min
        val end = selection.max
        val selected = text.substring(start, end)

        return if (selected.isNotEmpty()) {
            val quoted = selected.lines().joinToString("\n") { "> $it" }
            val newText = text.substring(0, start) + quoted + text.substring(end)
            Pair(newText, TextRange(start, start + quoted.length))
        } else {
            val insert = "> "
            val newText = text.substring(0, start) + insert + text.substring(end)
            Pair(newText, TextRange(start + insert.length))
        }
    }
}

/**
 * Toolbar for Markdown formatting. Each button applies a formatting action
 * to the current text/selection.
 */
@Composable
fun MarkdownFormattingToolbar(
    text: String,
    selection: TextRange,
    onFormat: (String, TextRange) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .height(44.dp)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Group 1: Inline formatting
            FormatButton(Icons.Default.Title, "Heading") {
                val (t, s) = MarkdownFormatter.cycleHeading(text, selection)
                onFormat(t, s)
            }
            FormatButton(Icons.Default.FormatBold, "Bold") {
                val (t, s) = MarkdownFormatter.toggleBold(text, selection)
                onFormat(t, s)
            }
            FormatButton(Icons.Default.FormatItalic, "Italic") {
                val (t, s) = MarkdownFormatter.toggleItalic(text, selection)
                onFormat(t, s)
            }
            FormatButton(Icons.Default.Code, "Code") {
                val (t, s) = MarkdownFormatter.toggleCode(text, selection)
                onFormat(t, s)
            }

            FormatDivider()

            // Group 2: Lists
            FormatButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Bullet list") {
                val (t, s) = MarkdownFormatter.toggleBulletList(text, selection)
                onFormat(t, s)
            }
            FormatButton(null, "1.") {
                val (t, s) = MarkdownFormatter.toggleNumberedList(text, selection)
                onFormat(t, s)
            }
            FormatButton(null, "☑") {
                val (t, s) = MarkdownFormatter.toggleChecklist(text, selection)
                onFormat(t, s)
            }

            FormatDivider()

            // Group 3: Blocks
            FormatButton(Icons.Default.Link, "Link") {
                val (t, s) = MarkdownFormatter.insertLink(text, selection)
                onFormat(t, s)
            }
            FormatButton(Icons.Default.FormatQuote, "Quote") {
                val (t, s) = MarkdownFormatter.insertQuote(text, selection)
                onFormat(t, s)
            }
            FormatButton(Icons.Default.HorizontalRule, "Divider") {
                val (t, s) = MarkdownFormatter.insertDivider(text, selection)
                onFormat(t, s)
            }
            FormatButton(Icons.Default.DataObject, "Code block") {
                val (t, s) = MarkdownFormatter.insertCodeBlock(text, selection)
                onFormat(t, s)
            }
        }
    }
}

@Composable
private fun FormatButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    contentDescription: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            // For checklist — use text icon since there's no material icon
            Text(
                text = contentDescription,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FormatDivider() {
    HorizontalDivider(
        modifier = Modifier
            .height(24.dp)
            .padding(horizontal = 4.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}
