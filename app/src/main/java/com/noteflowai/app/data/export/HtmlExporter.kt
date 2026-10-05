package com.noteflowai.app.data.export

import com.noteflowai.app.data.NoteFile
import java.text.SimpleDateFormat
import java.util.*

/**
 * Converts a NoteFile with markdown content into clean HTML5 with inline CSS.
 */
object HtmlExporter {

    private val DATE_FORMAT = SimpleDateFormat("MMMM d, yyyy 'at' h:mm a", Locale.getDefault())

    /**
     * Generate a complete HTML5 document from a note.
     */
    fun export(note: NoteFile): String {
        val dateStr = DATE_FORMAT.format(Date(note.lastModified))
        val tagsHtml = if (note.tags.isNotEmpty()) {
            note.tags.joinToString("") { "<span class=\"tag\">${escapeHtml(it)}</span>" }
        } else ""

        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang=\"en\">")
            appendLine("<head>")
            appendLine("<meta charset=\"UTF-8\">")
            appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
            appendLine("<title>${escapeHtml(note.fileName)}</title>")
            appendLine("<style>")
            appendLine(CSS)
            appendLine("</style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("<div class=\"container\">")

            // Metadata header
            appendLine("<div class=\"meta\">")
            appendLine("<div class=\"meta-date\">$dateStr</div>")
            if (note.category.isNotBlank() && note.category != "Uncategorized") {
                appendLine("<span class=\"category\">${escapeHtml(note.category)}</span>")
            }
            if (tagsHtml.isNotBlank()) {
                appendLine("<div class=\"tags\">$tagsHtml</div>")
            }
            appendLine("</div>")

            // Title
            appendLine("<h1>${escapeHtml(note.fileName)}</h1>")

            // Content
            appendLine("<div class=\"content\">")
            appendLine(markdownToHtml(note.content))
            appendLine("</div>")

            appendLine("</div>")
            appendLine("</body>")
            appendLine("</html>")
        }
    }

    /**
     * Convert markdown content to HTML body content.
     */
    fun markdownToHtml(content: String): String {
        val sb = StringBuilder()
        val lines = content.split("\n")
        var i = 0
        var inCodeBlock = false
        val codeBuffer = StringBuilder()

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // Fenced code block
            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    sb.append("<pre><code>${escapeHtml(codeBuffer.toString().trimEnd())}</code></pre>\n")
                    codeBuffer.clear()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                i++
                continue
            }

            if (inCodeBlock) {
                codeBuffer.appendLine(line)
                i++
                continue
            }

            // Empty line
            if (trimmed.isEmpty()) {
                i++
                continue
            }

            // Heading
            val headingMatch = Regex("^(#{1,3})\\s+(.*)").find(trimmed)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                sb.appendLine("<h$level>${inlineHtml(headingMatch.groupValues[2])}</h$level>")
                i++
                continue
            }

            // Thematic break
            if (Regex("^[-*_]{3,}$").matches(trimmed)) {
                sb.appendLine("<hr>")
                i++
                continue
            }

            // Table
            if (trimmed.contains("|") && trimmed.count { it == '|' } >= 2) {
                val tableLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().contains("|")) {
                    tableLines.add(lines[i].trim())
                    i++
                }
                sb.appendHtmlTable(tableLines)
                continue
            }

            // Checklist
            val cbMatch = Regex("^-\\s+\\[([ xX])]\\s+(.*)").find(trimmed)
            if (cbMatch != null) {
                val checked = cbMatch.groupValues[1].lowercase() == "x"
                val checkbox = if (checked) "☑" else "☐"
                val style = if (checked) " class=\"checked\"" else ""
                sb.appendLine("<div class=\"checklist\"$style>$checkbox ${inlineHtml(cbMatch.groupValues[2])}</div>")
                i++
                continue
            }

            // Bullet list
            if (Regex("^[-*+]\\s+.*").matches(trimmed)) {
                sb.appendLine("<ul>")
                while (i < lines.size && Regex("^[-*+]\\s+.*").matches(lines[i].trim())) {
                    val itemText = lines[i].trim().replace(Regex("^[-*+]\\s+"), "")
                    sb.appendLine("<li>${inlineHtml(itemText)}</li>")
                    i++
                }
                sb.appendLine("</ul>")
                continue
            }

            // Numbered list
            val numMatch = Regex("^(\\d+)\\.\\s+(.*)").find(trimmed)
            if (numMatch != null) {
                sb.appendLine("<ol>")
                while (i < lines.size) {
                    val nm = Regex("^(\\d+)\\.\\s+(.*)").find(lines[i].trim())
                    if (nm != null) {
                        sb.appendLine("<li>${inlineHtml(nm.groupValues[2])}</li>")
                        i++
                    } else {
                        break
                    }
                }
                sb.appendLine("</ol>")
                continue
            }

            // Image
            val imgMatch = Regex("^!\\[([^]]*)]\\(([^)]+)\\)").find(trimmed)
            if (imgMatch != null) {
                val alt = imgMatch.groupValues[1]
                val src = imgMatch.groupValues[2]
                sb.appendLine("<img src=\"${escapeHtml(src)}\" alt=\"${escapeHtml(alt)}\" class=\"image\">")
                i++
                continue
            }

            // Paragraph
            sb.appendLine("<p>${inlineHtml(trimmed)}</p>")
            i++
        }

        return sb.toString()
    }

    private fun inlineHtml(text: String): String {
        var result = text
        // Bold
        result = result.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>$1</strong>")
        result = result.replace(Regex("__(.+?)__"), "<strong>$1</strong>")
        // Italic
        result = result.replace(Regex("\\*(.+?)\\*"), "<em>$1</em>")
        result = result.replace(Regex("_(.+?)_"), "<em>$1</em>")
        // Inline code
        result = result.replace(Regex("`(.+?)`"), "<code>$1</code>")
        return result
    }

    private fun StringBuilder.appendHtmlTable(tableLines: List<String>) {
        if (tableLines.size < 2) return
        fun parseRow(line: String): List<String> {
            return line.removePrefix("|").removeSuffix("|").split("|").map { it.trim() }
        }
        val headers = parseRow(tableLines[0])
        val separatorLine = tableLines[1].removePrefix("|").removeSuffix("|")
        val isSeparator = separatorLine.split("|").map { it.trim() }.all { it.isBlank() || Regex("^[-:]+$").matches(it) }
        if (!isSeparator) {
            // Not a valid table, render as paragraph
            appendLine("<p>${inlineHtml(tableLines.joinToString(" "))}</p>")
            return
        }

        appendLine("<table>")
        appendLine("<thead><tr>")
        for (h in headers) {
            appendLine("<th>${inlineHtml(h)}</th>")
        }
        appendLine("</tr></thead>")
        appendLine("<tbody>")
        for (idx in 2 until tableLines.size) {
            val row = parseRow(tableLines[idx])
            appendLine("<tr>")
            for (cell in row) {
                appendLine("<td>${inlineHtml(cell)}</td>")
            }
            appendLine("</tr>")
        }
        appendLine("</tbody>")
        appendLine("</table>")
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    private val CSS = """
        * { margin: 0; padding: 0; box-sizing: border-box; }
        body {
            font-family: system-ui, -apple-system, sans-serif;
            line-height: 1.6;
            color: #1a1a1a;
            background: #fafafa;
            padding: 20px;
        }
        .container {
            max-width: 800px;
            margin: 0 auto;
            background: white;
            padding: 40px;
            border-radius: 8px;
            box-shadow: 0 1px 3px rgba(0,0,0,0.1);
        }
        h1 { font-size: 1.8em; margin-bottom: 16px; color: #1a1a1a; }
        h2 { font-size: 1.4em; margin: 24px 0 12px; color: #2563eb; }
        h3 { font-size: 1.2em; margin: 20px 0 10px; color: #2563eb; }
        p { margin-bottom: 12px; }
        .meta {
            margin-bottom: 20px;
            padding-bottom: 12px;
            border-bottom: 1px solid #e5e7eb;
            font-size: 0.85em;
            color: #6b7280;
        }
        .meta-date { margin-bottom: 4px; }
        .category {
            display: inline-block;
            background: #e0e7ff;
            color: #3730a3;
            padding: 2px 8px;
            border-radius: 4px;
            font-size: 0.85em;
            margin-right: 8px;
        }
        .tags { margin-top: 4px; }
        .tag {
            display: inline-block;
            background: #f3f4f6;
            color: #374151;
            padding: 2px 8px;
            border-radius: 12px;
            font-size: 0.8em;
            margin-right: 4px;
        }
        pre {
            background: #f3f4f6;
            border: 1px solid #e5e7eb;
            border-radius: 6px;
            padding: 16px;
            overflow-x: auto;
            margin: 12px 0;
        }
        code {
            font-family: 'SF Mono', 'Fira Code', monospace;
            font-size: 0.9em;
        }
        p code {
            background: #f3f4f6;
            padding: 2px 6px;
            border-radius: 4px;
        }
        ul, ol { margin: 12px 0 12px 24px; }
        li { margin-bottom: 4px; }
        table {
            width: 100%;
            border-collapse: collapse;
            margin: 12px 0;
        }
        th, td {
            border: 1px solid #e5e7eb;
            padding: 8px 12px;
            text-align: left;
        }
        th { background: #f9fafb; font-weight: 600; }
        tr:nth-child(even) { background: #f9fafb; }
        hr { border: none; border-top: 1px solid #e5e7eb; margin: 20px 0; }
        .checklist {
            margin: 4px 0;
            font-size: 0.95em;
        }
        .checklist.checked {
            color: #6b7280;
            text-decoration: line-through;
        }
        .image {
            max-width: 100%;
            border-radius: 6px;
            margin: 12px 0;
        }
        strong { font-weight: 600; }
        em { font-style: italic; }
    """.trimIndent()
}
