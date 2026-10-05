package com.noteflowai.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.noteflowai.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.noteflowai.app.pptx.SlideData

@Composable
fun SlidePreviewDialog(
    slides: List<SlideData>,
    title: String,
    onDismiss: () -> Unit,
    onShare: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Share,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(stringResource(R.string.slide_preview_title), fontWeight = FontWeight.Bold)
        },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 400.dp)
            ) {
                itemsIndexed(slides, key = { i, slide -> "${i}_${slide.title.hashCode()}_${slide.isTitleSlide}" }) { index, slide ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (slide.isTitleSlide)
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                            else
                                MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.slide_preview_number, index + 1),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = slide.title,
                                style = if (slide.isTitleSlide) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            if (!slide.isTitleSlide) {
                                Spacer(modifier = Modifier.height(8.dp))
                                if (slide.content.isEmpty() && slide.bullets.isNotEmpty()) {
                                    slide.bullets.forEach { bullet ->
                                        Text(
                                            text = "• $bullet",
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                                        )
                                    }
                                }
                                slide.content.forEach { block ->
                                    when (block) {
                                        is com.noteflowai.app.pptx.SlideBlock.Bullet -> {
                                            Text(
                                                text = "• ${block.text}",
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                                            )
                                        }
                                        is com.noteflowai.app.pptx.SlideBlock.Code -> {
                                            Card(
                                                colors = CardDefaults.cardColors(
                                                    containerColor = MaterialTheme.colorScheme.surface
                                                ),
                                                modifier = Modifier.padding(top = 4.dp).fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = block.code,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.padding(8.dp)
                                                )
                                            }
                                        }
                                        is com.noteflowai.app.pptx.SlideBlock.Table -> {
                                            Text(
                                                text = block.headers.joinToString(" | "),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                            block.rows.forEach { row ->
                                                Text(
                                                    text = row.joinToString(" | "),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.padding(start = 8.dp, top = 1.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onDismiss) {
                    Text(stringResource(R.string.slide_preview_close))
                }
                Button(onClick = onShare) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.slide_preview_share))
                }
            }
        },
    )
}
