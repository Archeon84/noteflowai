package com.noteflowai.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noteflowai.app.R
import com.noteflowai.app.data.chat.GroundedCitationFooter
import com.noteflowai.app.data.chat.RagSource
import com.noteflowai.app.data.noteDisplayTitle
import com.noteflowai.app.ui.theme.AppRadius
import com.noteflowai.app.ui.theme.AppSpacing
import com.noteflowai.app.ui.theme.Haptics

/**
 * Interactive Citation Verification Drawer:
 * Displayed when tapping a superscript citation [N] in AI Chat.
 * Provides transparent provenance: quoted text, relevance score, source filename,
 * and direct navigation to open the original note.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CitationDrawer(
    citationIndex: Int,
    footer: GroundedCitationFooter?,
    ragSource: RagSource?,
    onOpenSourceNote: (fileName: String, quoteText: String?) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val quoteText = footer?.quoteText?.ifBlank { null }
        ?: ragSource?.excerpt?.ifBlank { null }
    val isWebSource = ragSource?.source == "web" ||
        footer?.chunkId?.startsWith("http://") == true ||
        footer?.chunkId?.startsWith("https://") == true ||
        ragSource?.noteFileName?.startsWith("http://") == true ||
        ragSource?.noteFileName?.startsWith("https://") == true

    FreshSheet(onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
        ) {
            // Header: Citation Marker & Verified Tag
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "[$citationIndex]",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }

                    Column {
                        Text(
                            text = stringResource(R.string.citation_verified),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.citation_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(AppRadius.medium),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                ) {
                    Text(
                        text = if (isWebSource) "Web Source" else stringResource(R.string.citation_badge),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Quoted text excerpt card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(AppRadius.large)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AppSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    Text(
                        text = if (isWebSource) "EXCERPT FROM WEB SEARCH" else stringResource(R.string.citation_excerpt_label),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    val quote = quoteText ?: stringResource(R.string.citation_excerpt_fallback)

                    Text(
                        text = "\"$quote\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        lineHeight = 22.sp
                    )
                }
            }

            // Source metadata card
            val sourceFileName = footer?.chunkId?.takeIf { it.isNotBlank() && it != "NOTE" }
                ?: ragSource?.noteFileName
                ?: footer?.sourceId?.takeIf { it.isNotBlank() && it != "NOTE" }
                ?: if (isWebSource) "Web Source" else "Unknown Note"
            val displayTitle = ragSource?.noteTitle?.ifBlank { null }
                ?: (if (footer?.sourceId != null && footer.sourceId != "NOTE") footer.sourceId.removePrefix("note_").noteDisplayTitle() else null)
                ?: sourceFileName.removePrefix("note_").noteDisplayTitle()

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                ),
                shape = RoundedCornerShape(AppRadius.large)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(AppSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isWebSource) Icons.Default.Language else Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Column {
                            Text(
                                text = displayTitle,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = sourceFileName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(AppSpacing.xs))

            // Action button: Open Source Note or Website
            Button(
                onClick = {
                    Haptics.tick(context)
                    onDismiss()
                    if (isWebSource && (sourceFileName.startsWith("http://") || sourceFileName.startsWith("https://"))) {
                        try {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(sourceFileName)).apply {
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            android.util.Log.w("CitationDrawer", "Failed to open web URL: $sourceFileName", e)
                        }
                    } else {
                        onOpenSourceNote(sourceFileName, quoteText)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(AppRadius.medium),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = if (isWebSource) "Open website in browser" else stringResource(R.string.citation_cd_open),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(AppSpacing.sm))
                Text(
                    text = if (isWebSource) "Open Website" else stringResource(R.string.citation_open_note),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
