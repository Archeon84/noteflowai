package com.noteflowai.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.platform.LocalContext
import com.noteflowai.app.ui.theme.Haptics
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import com.noteflowai.app.ui.theme.AppRadius
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.BitmapFactory
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import java.io.File

private val RE_HEADING = Regex("^(#{1,3})\\s+(.*)")
private val RE_BULLET = Regex("^[-*+]\\s+.*")
private val RE_BULLET_STRIP = Regex("^[-*+]\\s+")
private val RE_NUMBERED = Regex("^(\\d+)\\.\\s+(.*)")
private val RE_NUMBERED_PREFIX = Regex("^(\\d+)\\.\\s+")
private val RE_THEMATIC_BREAK = Regex("^[-*_]{3,}$")
private val RE_SEPARATOR = Regex("^[-:]+$")
private val RE_CHECKBOX = Regex("^-\\s+\\[([ xX])]\\s+(.*)")
private val RE_IMAGE = Regex("^!\\[([^]]*)]\\(([^)]+)\\)")
private val RE_CITATION = Regex("\\[(\\d+)\\]")

@Composable
fun MarkdownRenderer(
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier,
    onChecklistToggle: ((lineNumber: Int, checked: Boolean) -> Unit)? = null,
    onCitationClick: ((Int) -> Unit)? = null
) {
    val blocks by produceState<List<MdBlock>>(emptyList(), text) {
        value = withContext(Dispatchers.Default) { parseMarkdownBlocks(text) }
    }
    val codeBackground = MaterialTheme.colorScheme.surfaceContainerHigh
    val codeBorderColor = MaterialTheme.colorScheme.outlineVariant
    val tableBorderColor = MaterialTheme.colorScheme.outlineVariant
    val headerColor = MaterialTheme.colorScheme.primary
    val citationColor = MaterialTheme.colorScheme.primary

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> {
                    val (fontSize, headingColor) = when (block.level) {
                        1 -> 20.sp to MaterialTheme.colorScheme.primary
                        2 -> 17.sp to MaterialTheme.colorScheme.secondary
                        else -> 15.sp to MaterialTheme.colorScheme.tertiary
                    }
                    val fontWeight = FontWeight.Bold
                    Text(
                        text = block.text,
                        color = headingColor,
                        fontSize = fontSize,
                        fontWeight = fontWeight,
                        lineHeight = fontSize * 1.3
                    )
                }
                is MdBlock.Blockquote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
                                RoundedCornerShape(AppRadius.medium)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(22.dp)
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        val annotated = buildAnnotatedString {
                            appendInlineMarkdown(this, block.text, textColor.copy(alpha = 0.9f), onCitationClick, citationColor)
                        }
                        Text(
                            text = annotated,
                            style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic, lineHeight = 20.sp),
                            color = textColor.copy(alpha = 0.9f)
                        )
                    }
                }
                is MdBlock.Paragraph -> {
                    val annotated = buildAnnotatedString {
                        appendInlineMarkdown(this, block.text, textColor, onCitationClick, citationColor)
                    }
                    if (onCitationClick != null) {
                        @Suppress("DEPRECATION")
                        androidx.compose.foundation.text.ClickableText(
                            text = annotated,
                            style = MaterialTheme.typography.bodyMedium.copy(color = textColor, lineHeight = 22.sp),
                            onClick = { offset ->
                                annotated.getStringAnnotations(tag = "citation", start = offset, end = offset)
                                    .firstOrNull()?.let { annotation ->
                                        annotation.item.toIntOrNull()?.let { markerIdx ->
                                            onCitationClick(markerIdx)
                                        }
                                    }
                            }
                        )
                    } else {
                        Text(
                            text = annotated,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 22.sp
                        )
                    }
                }
                is MdBlock.ListItem -> {
                    ListItemsRenderer(block, textColor, onCitationClick, citationColor)
                }
                is MdBlock.CodeBlock -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(codeBackground, RoundedCornerShape(AppRadius.medium))
                            .padding(12.dp)
                    ) {
                        Text(
                            text = block.code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = textColor,
                            lineHeight = 18.sp,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        )
                    }
                }
                is MdBlock.ThematicBreak -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = tableBorderColor,
                        thickness = 1.dp
                    )
                }
                is MdBlock.Table -> {
                    TableRenderer(
                        headers = block.headers,
                        rows = block.rows,
                        textColor = textColor,
                        borderColor = tableBorderColor
                    )
                }
                is MdBlock.Checklist -> {
                    ChecklistRenderer(block.items, textColor, onChecklistToggle)
                }
                is MdBlock.Image -> {
                    ImageRenderer(block.alt, block.uri)
                }
            }
        }
    }
}

@Composable
private fun ListItemsRenderer(
    block: MdBlock.ListItem,
    textColor: Color,
    onCitationClick: ((Int) -> Unit)? = null,
    citationColor: Color = textColor
) {
    fun isNumbered(list: List<MdBlock.ListItem>): Boolean {
        return list.firstOrNull()?.number != null
    }

    @Composable
    fun RenderItems(items: List<MdBlock.ListItem>, indent: Int) {
        val numbered = isNumbered(items)
        Column(modifier = Modifier.padding(start = (8 + indent * 16).dp)) {
            items.forEachIndexed { index, item ->
                val marker = if (numbered) "${item.number ?: (index + 1)}. " else "\u2022 "
                val annotated = buildAnnotatedString {
                    appendInlineMarkdown(this, item.text, textColor, onCitationClick, citationColor)
                }
                val markerColor = if (numbered) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = marker,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = if (numbered) FontWeight.SemiBold else FontWeight.Bold
                        ),
                        color = markerColor
                    )
                    if (onCitationClick != null) {
                        @Suppress("DEPRECATION")
                        androidx.compose.foundation.text.ClickableText(
                            text = annotated,
                            style = MaterialTheme.typography.bodyMedium.copy(color = textColor, lineHeight = 22.sp),
                            modifier = Modifier.weight(1f),
                            onClick = { offset ->
                                annotated.getStringAnnotations(tag = "citation", start = offset, end = offset)
                                    .firstOrNull()?.let { annotation ->
                                        annotation.item.toIntOrNull()?.let { markerIdx ->
                                            onCitationClick(markerIdx)
                                        }
                                    }
                            }
                        )
                    } else {
                        Text(
                            text = annotated,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            lineHeight = 22.sp
                        )
                    }
                }
                if (item.children.isNotEmpty()) {
                    RenderItems(item.children, indent + 1)
                }
            }
        }
    }

    RenderItems(block.children, 0)
}

@Composable
private fun TableRenderer(
    headers: List<String>,
    rows: List<List<String>>,
    textColor: Color,
    borderColor: Color
) {
    val cellStyle = MaterialTheme.typography.bodySmall
    val headerStyle = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
    val numCols = headers.size.coerceAtLeast(rows.maxOfOrNull { it.size } ?: 0)

    val columnWidths = remember(headers, rows) {
        val widths = IntArray(numCols) { 60 }
        for (col in 0 until numCols) {
            var maxChars = headers.getOrNull(col)?.length ?: 0
            for (row in rows) {
                maxChars = maxChars.coerceAtLeast(row.getOrNull(col)?.length ?: 0)
            }
            widths[col] = (maxChars * 7 + 24).coerceIn(60, 200)
        }
        widths.map { it.dp }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                // Header row
                Row(
                    modifier = Modifier.background(borderColor.copy(alpha = 0.15f))
                ) {
                    for (col in 0 until numCols) {
                        Text(
                            text = headers.getOrNull(col)?.trim() ?: "",
                            style = headerStyle,
                            color = textColor,
                            modifier = Modifier
                                .width(columnWidths[col])
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                HorizontalDivider(color = borderColor, thickness = 1.dp)
                // Data rows with alternating background
                for ((rowIndex, row) in rows.withIndex()) {
                    val rowBg = if (rowIndex % 2 == 0) Color.Transparent else borderColor.copy(alpha = 0.05f)
                    Row(modifier = Modifier.background(rowBg)) {
                        for (col in 0 until numCols) {
                            val cellText = row.getOrNull(col)?.trim() ?: ""
                            val annotated = buildAnnotatedString {
                                appendInlineMarkdown(this, cellText, textColor)
                            }
                            Text(
                                text = annotated,
                                style = cellStyle,
                                modifier = Modifier
                                    .width(columnWidths[col])
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (rowIndex < rows.lastIndex) {
                        HorizontalDivider(color = borderColor, thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecklistRenderer(
    items: List<MdBlock.ChecklistItem>,
    textColor: Color,
    onChecklistToggle: ((lineNumber: Int, checked: Boolean) -> Unit)?
) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(start = 8.dp)) {
        items.forEach { item ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .then(
                        if (onChecklistToggle != null) {
                            Modifier.toggleable(
                                value = item.checked,
                                onValueChange = { Haptics.tap(context); onChecklistToggle(item.lineNumber, !item.checked) }
                            )
                        } else {
                            Modifier
                        }
                    )
            ) {
                Checkbox(
                    checked = item.checked,
                    onCheckedChange = if (onChecklistToggle != null) { checked -> onChecklistToggle(item.lineNumber, checked) } else null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                val annotated = buildAnnotatedString {
                    appendInlineMarkdown(this, item.text, textColor)
                }
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    color = if (item.checked) textColor.copy(alpha = 0.5f) else textColor,
                    textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                    lineHeight = 22.sp
                )
            }
        }
    }
}

private fun decodeSampledBitmap(path: String): android.graphics.Bitmap? {
    // First pass: get dimensions only
    val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, opts)
    // Calculate sample size to fit within 800x600
    opts.inSampleSize = calculateInSampleSize(opts, 800, 600)
    opts.inJustDecodeBounds = false
    return android.graphics.BitmapFactory.decodeFile(path, opts)
}

private fun calculateInSampleSize(options: android.graphics.BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    val (height, width) = options.outHeight to options.outWidth
    var inSampleSize = 1
    if (height > reqHeight || width > reqWidth) {
        val halfHeight = height / 2
        val halfWidth = width / 2
        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }
    return inSampleSize
}

@Composable
private fun ImageRenderer(alt: String, uri: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val bitmap by produceState<android.graphics.Bitmap?>(null, uri) {
            value = withContext(Dispatchers.IO) {
                try {
                    when {
                        uri.startsWith("file://") -> {
                            val file = File(uri.removePrefix("file://"))
                            if (file.exists()) decodeSampledBitmap(file.absolutePath) else null
                        }
                        uri.startsWith("/") -> decodeSampledBitmap(uri)
                        else -> null
                    }
                } catch (e: Exception) { null }
            }
        }
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = alt.ifBlank { "Image" },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(AppRadius.medium)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (alt.isNotBlank()) "[Image: $alt]" else "[Image: $uri]",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        }
        if (alt.isNotBlank()) {
            Text(
                text = alt,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

// --- Markdown parsing ---

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class ListItem(val text: String, val number: Int?, val indent: Int, val children: List<ListItem>) : MdBlock()
    data class CodeBlock(val code: String) : MdBlock()
    data object ThematicBreak : MdBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock()
    data class Checklist(val items: List<ChecklistItem>) : MdBlock()
    data class ChecklistItem(val text: String, val checked: Boolean, val lineNumber: Int)
    data class Blockquote(val text: String) : MdBlock()
    data class Image(val alt: String, val uri: String) : MdBlock()
}

private fun parseMarkdownBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.split("\n")
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        val iAtLoopStart = i

        // Code block (fenced)
        if (trimmed.startsWith("```")) {
            val lang = trimmed.removePrefix("```").trim()
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (i < lines.size) i++ // skip closing ```
            blocks.add(MdBlock.CodeBlock(codeLines.joinToString("\n")))
            continue
        }

        // Empty line
        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // Thematic break
        if (RE_THEMATIC_BREAK.matches(trimmed)) {
            blocks.add(MdBlock.ThematicBreak)
            i++
            continue
        }

        // Table (pipe-delimited)
        if (trimmed.contains("|") && trimmed.count { it == '|' } >= 2) {
            val tableLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().contains("|")) {
                tableLines.add(lines[i].trim())
                i++
            }
            if (tableLines.size >= 2) {
                val parsed = parseTable(tableLines)
                if (parsed != null) {
                    blocks.add(parsed)
                    continue
                }
            }
            // If table parsing failed, fall through to paragraph
            blocks.add(MdBlock.Paragraph(tableLines.joinToString("\n")))
            continue
        }

        // Heading
        val headingMatch = RE_HEADING.find(trimmed)
        if (headingMatch != null) {
            val level = headingMatch.groupValues[1].length
            blocks.add(MdBlock.Heading(level, headingMatch.groupValues[2].trim()))
            i++
            continue
        }

        // Blockquote (> text)
        if (trimmed.startsWith(">")) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                quoteLines.add(lines[i].trim().removePrefix(">").trim())
                i++
            }
            if (quoteLines.isNotEmpty()) {
                blocks.add(MdBlock.Blockquote(quoteLines.joinToString("\n")))
            }
            continue
        }

        // Image (must check before bullet list since images start with !)
        val imageMatch = RE_IMAGE.find(trimmed)
        if (imageMatch != null) {
            blocks.add(MdBlock.Image(imageMatch.groupValues[1], imageMatch.groupValues[2]))
            i++
            continue
        }

        // Checklist items (- [ ] / - [x])
        if (RE_CHECKBOX.matches(trimmed)) {
            val checklistItems = mutableListOf<MdBlock.ChecklistItem>()
            while (i < lines.size) {
                val t = lines[i].trim()
                val cbMatch = RE_CHECKBOX.find(t)
                if (cbMatch != null) {
                    val checked = cbMatch.groupValues[1].lowercase() == "x"
                    checklistItems.add(MdBlock.ChecklistItem(cbMatch.groupValues[2], checked, i))
                    i++
                } else {
                    break
                }
            }
            if (checklistItems.isNotEmpty()) {
                blocks.add(MdBlock.Checklist(checklistItems))
            }
            continue
        }

        // Bullet list item
        if (RE_BULLET.matches(trimmed) || RE_NUMBERED.matches(trimmed)) {
            val listLines = mutableListOf<Pair<Int, Pair<String, Int?>>>()
            while (i < lines.size) {
                val raw = lines[i]
                val t = raw.trim()
                val indent = raw.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                if (RE_BULLET.matches(t)) {
                    listLines.add(indent to (t.replace(RE_BULLET_STRIP, "") to null))
                    i++
                } else {
                    val numMatch = RE_NUMBERED.find(t)
                    if (numMatch != null) {
                        listLines.add(indent to (numMatch.groupValues[2] to numMatch.groupValues[1].toIntOrNull()))
                        i++
                    } else {
                        break
                    }
                }
            }
            if (listLines.isNotEmpty()) {
                blocks.add(buildListBlock(listLines))
            }
            continue
        }

        // Paragraph (collect contiguous non-blank lines).
        // By the time we reach here, the line is not a code fence, heading,
        // bullet/numbered list, thematic break, or table (those branches
        // already handled/consumed it above). Collect it as paragraph text
        // and ALWAYS advance i to avoid an infinite parse loop.
        val paraLines = mutableListOf<String>()
        while (i < lines.size && lines[i].trim().isNotEmpty()) {
            paraLines.add(lines[i].trim())
            i++
        }
        if (paraLines.isNotEmpty()) {
            blocks.add(MdBlock.Paragraph(paraLines.joinToString(" ")))
        }

        // Safety: guarantee forward progress so a malformed line can never
        // cause an infinite parse loop (which would freeze the UI).
        if (i == iAtLoopStart) i++
    }

    return blocks
}

private fun parseTable(lines: List<String>): MdBlock.Table? {
    if (lines.size < 2) return null

    fun parseRow(line: String): List<String> {
        val trimmed = line.trim()
            .removePrefix("|").removeSuffix("|")
        return trimmed.split("|").map { it.trim() }
    }

    val headers = parseRow(lines[0])
    if (headers.isEmpty() || headers.all { it.isEmpty() }) return null

    // Check that line 1 is a separator row (e.g. |---|---|)
    val separatorLine = lines[1].trim()
        .removePrefix("|").removeSuffix("|")
    val isSeparator = separatorLine.split("|").map { it.trim() }.all { part ->
        part.isBlank() || RE_SEPARATOR.matches(part)
    }
    if (!isSeparator) return null

    val rows = mutableListOf<List<String>>()
    for (idx in 2 until lines.size) {
        rows.add(parseRow(lines[idx]))
    }

    return MdBlock.Table(headers, rows)
}

private fun buildListBlock(rawItems: List<Pair<Int, Pair<String, Int?>>>): MdBlock.ListItem {
    // Build a tree of list items based on indentation levels.
    fun build(from: Int, minIndent: Int): List<MdBlock.ListItem> {
        val items = mutableListOf<MdBlock.ListItem>()
        var idx = from
        while (idx < rawItems.size) {
            val (indent, pair) = rawItems[idx]
            if (indent < minIndent) break
            val (text, number) = pair
            val children = mutableListOf<MdBlock.ListItem>()
            idx++
            if (idx < rawItems.size && rawItems[idx].first > indent) {
                children.addAll(build(idx, rawItems[idx].first))
            }
            items.add(MdBlock.ListItem(text, number, indent, children))
        }
        return items
    }
    val top = build(0, rawItems.first().first)
    // Wrap top-level list as a single root node (text unused, number null).
    return MdBlock.ListItem("", null, rawItems.first().first, top)
}

// --- Inline markdown parsing (bold, italic, code) ---

private fun appendInlineMarkdown(
    builder: AnnotatedString.Builder,
    text: String,
    baseColor: Color,
    onCitationClick: ((Int) -> Unit)? = null,
    citationColor: Color = baseColor
) {
    var remaining = text

    while (remaining.isNotEmpty()) {
        // Inline code (highest priority)
        val codeIdx = remaining.indexOf("`")
        // Bold **
        val boldIdx = remaining.indexOf("**")
        // Italic * (but not **)
        val italicStarIdx = run {
            var idx = -1
            var searchFrom = 0
            while (searchFrom < remaining.length) {
                val found = remaining.indexOf('*', searchFrom)
                if (found == -1) break
                if (found + 1 < remaining.length && remaining[found + 1] == '*') {
                    searchFrom = found + 2
                } else {
                    idx = found
                    break
                }
            }
            idx
        }
        // Bold __
        val boldUnderscoreIdx = remaining.indexOf("__")
        // Italic _
        val italicUnderscoreIdx = remaining.indexOf('_')
        // Citation marker [N] (e.g. [1], [12])
        val citationMatch = if (onCitationClick != null) RE_CITATION.find(remaining) else null
        val citationIdx = citationMatch?.range?.first ?: -1

        // Find the earliest match
        val nextInline = listOf(
            Triple("code", codeIdx, Int.MAX_VALUE),
            Triple("bold", boldIdx, if (boldIdx >= 0) boldIdx + 2 else Int.MAX_VALUE),
            Triple("bold", boldUnderscoreIdx, if (boldUnderscoreIdx >= 0) boldUnderscoreIdx + 2 else Int.MAX_VALUE),
            Triple("italic", italicStarIdx, if (italicStarIdx >= 0) italicStarIdx + 1 else Int.MAX_VALUE),
            Triple("italic", italicUnderscoreIdx, if (italicUnderscoreIdx >= 0) italicUnderscoreIdx + 1 else Int.MAX_VALUE),
            Triple("citation", citationIdx, if (citationMatch != null) citationMatch.range.last + 1 else Int.MAX_VALUE)
        ).filter { it.second >= 0 }.minByOrNull { it.second }

        if (nextInline == null) {
            builder.append(remaining)
            break
        }

        val (type, idx, _) = nextInline

        // Append text before the match
        if (idx > 0) {
            builder.append(remaining.substring(0, idx))
        }

        when (type) {
            "citation" -> {
                val match = citationMatch!!
                val markerIdx = match.groupValues[1].toInt()
                val markerText = match.value
                builder.pushStyle(
                    SpanStyle(
                        color = citationColor,
                        baselineShift = androidx.compose.ui.text.style.BaselineShift.Superscript,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
                builder.pushStringAnnotation(tag = "citation", annotation = "$markerIdx")
                builder.append(markerText)
                builder.pop()
                builder.pop()
                remaining = remaining.substring(match.range.last + 1)
            }
            "code" -> {
                val end = remaining.indexOf('`', idx + 1)
                if (end >= 0) {
                    val code = remaining.substring(idx + 1, end)
                    builder.pushStyle(SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    ))
                    builder.append(code)
                    builder.pop()
                    remaining = remaining.substring(end + 1)
                } else {
                    builder.append(remaining.substring(idx))
                    break
                }
            }
            "bold" -> {
                val delimiter = if (idx + 1 < remaining.length && remaining[idx + 1] == remaining[idx]) {
                    "${remaining[idx]}${remaining[idx]}"
                } else {
                    "${remaining[idx]}"
                }
                val end = remaining.indexOf(delimiter, idx + delimiter.length)
                if (end >= 0) {
                    val bold = remaining.substring(idx + delimiter.length, end)
                    builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    appendInlineMarkdown(builder, bold, baseColor, onCitationClick, citationColor)
                    builder.pop()
                    remaining = remaining.substring(end + delimiter.length)
                } else {
                    builder.append(remaining.substring(idx))
                    break
                }
            }
            "italic" -> {
                val char = remaining[idx]
                val end = remaining.indexOf(char, idx + 1)
                if (end >= 0) {
                    val italic = remaining.substring(idx + 1, end)
                    builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    appendInlineMarkdown(builder, italic, baseColor, onCitationClick, citationColor)
                    builder.pop()
                    remaining = remaining.substring(end + 1)
                } else {
                    builder.append(remaining.substring(idx))
                    break
                }
            }
        }
    }
}
