package com.noteflowai.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noteflowai.app.R
import com.noteflowai.app.data.concept.ConceptEdge
import com.noteflowai.app.data.concept.ConceptExtractor
import com.noteflowai.app.data.concept.ConceptGraph
import com.noteflowai.app.data.concept.ConceptNode
import com.noteflowai.app.data.noteDisplayTitle
import com.noteflowai.app.ui.components.FreshEmptyState
import com.noteflowai.app.ui.components.FreshFilterRow
import com.noteflowai.app.ui.components.FreshMenuCard
import com.noteflowai.app.ui.components.FreshScreen
import com.noteflowai.app.ui.components.FreshSearchField
import com.noteflowai.app.ui.components.FreshSectionHeader
import com.noteflowai.app.ui.components.FreshSheet
import com.noteflowai.app.ui.components.FreshSheetTitle
import com.noteflowai.app.ui.theme.AppSpacing
import com.noteflowai.app.ui.theme.EntityTintAction
import com.noteflowai.app.ui.theme.EntityTintPerson
import com.noteflowai.app.ui.theme.EntityTintTechnical
import com.noteflowai.app.ui.theme.EntityTintTemporal
import com.noteflowai.app.ui.theme.EntityTintTheme
import com.noteflowai.app.viewmodel.MainViewModel

private fun ConceptExtractor.ConceptType.tint() = when (this) {
    ConceptExtractor.ConceptType.ENTITY -> EntityTintPerson
    ConceptExtractor.ConceptType.THEME -> EntityTintTheme
    ConceptExtractor.ConceptType.ACTION -> EntityTintAction
    ConceptExtractor.ConceptType.TEMPORAL -> EntityTintTemporal
    ConceptExtractor.ConceptType.TECHNICAL -> EntityTintTechnical
}

private fun ConceptExtractor.ConceptType.icon(): ImageVector = when (this) {
    ConceptExtractor.ConceptType.ENTITY -> Icons.Default.Person
    ConceptExtractor.ConceptType.THEME -> Icons.AutoMirrored.Filled.Label
    ConceptExtractor.ConceptType.ACTION -> Icons.Default.Bolt
    ConceptExtractor.ConceptType.TEMPORAL -> Icons.Default.Schedule
    ConceptExtractor.ConceptType.TECHNICAL -> Icons.Default.Code
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeGraphScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onNoteClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Lifecycle-aware: stops collecting (and recomposing the whole graph) when off-screen.
    val conceptGraph by viewModel.conceptGraph.collectAsStateWithLifecycle()
    var selectedNode by remember { mutableStateOf<ConceptNode?>(null) }
    var query by remember { mutableStateOf("") }
    var isCanvasMode by remember { mutableStateOf(true) }

    val typeAll = stringResource(R.string.graph_type_all)
    val typeLabels = mapOf(
        ConceptExtractor.ConceptType.ENTITY to stringResource(R.string.graph_type_entity),
        ConceptExtractor.ConceptType.THEME to stringResource(R.string.graph_type_theme),
        ConceptExtractor.ConceptType.ACTION to stringResource(R.string.graph_type_action),
        ConceptExtractor.ConceptType.TEMPORAL to stringResource(R.string.graph_type_temporal),
        ConceptExtractor.ConceptType.TECHNICAL to stringResource(R.string.graph_type_technical)
    )
    var selectedType by remember { mutableStateOf<String?>(null) }

    // Built once per locale — FreshFilterRow previously saw a new list identity
    // every recomposition, defeating skipping for the filter row and list.
    val filterOptions = remember(typeAll, typeLabels) { listOf(typeAll) + typeLabels.values.toList() }

    val visibleNodes = remember(conceptGraph, query, selectedType) {
        conceptGraph.nodes.values
            .filter { node ->
                (selectedType == null || typeLabels[node.type] == selectedType) &&
                    (query.isBlank() || node.displayForm.contains(query, ignoreCase = true))
            }
            .sortedWith(compareByDescending<ConceptNode> { it.noteCount }.thenByDescending { it.importance })
    }
    val grouped = remember(visibleNodes) {
        visibleNodes.groupBy { it.type }
            .toSortedMap(compareBy { typeLabels[it] })
    }

    FreshScreen(
        title = stringResource(R.string.nav_desc_graph),
        onBack = onBack,
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            Box(modifier = Modifier.padding(horizontal = AppSpacing.lg)) {
                FreshSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.graph_search_hint)
                )
            }
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            FreshFilterRow(
                options = filterOptions,
                selected = selectedType ?: typeAll,
                onSelect = { label -> selectedType = if (label == typeAll) null else label },
                contentPadding = PaddingValues(horizontal = AppSpacing.lg)
            )
            Spacer(modifier = Modifier.height(AppSpacing.xs))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.lg),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.graph_stats,
                        conceptGraph.nodes.size,
                        conceptGraph.edges.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = isCanvasMode,
                        onClick = { isCanvasMode = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text(stringResource(R.string.graph_view_canvas), style = MaterialTheme.typography.labelSmall)
                    }
                    SegmentedButton(
                        selected = !isCanvasMode,
                        onClick = { isCanvasMode = false },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text(stringResource(R.string.graph_view_list), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(modifier = Modifier.height(AppSpacing.sm))
            if (conceptGraph.nodes.isEmpty()) {
                FreshEmptyState(
                    icon = Icons.Default.Hub,
                    title = stringResource(R.string.graph_empty_title),
                    message = stringResource(R.string.graph_empty_desc),
                    modifier = Modifier.fillMaxSize()
                )
            } else if (visibleNodes.isEmpty()) {
                FreshEmptyState(
                    icon = Icons.Default.Hub,
                    title = stringResource(R.string.notes_nomatch_title),
                    message = stringResource(R.string.notes_nomatch_subtitle),
                    modifier = Modifier.fillMaxSize()
                )
            } else if (isCanvasMode) {
                InteractiveGraphCanvas(
                    nodes = visibleNodes.take(75),
                    edges = conceptGraph.edges,
                    selectedNode = selectedNode,
                    onNodeClick = { selectedNode = it },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = AppSpacing.lg,
                        end = AppSpacing.lg,
                        bottom = AppSpacing.xl
                    ),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    grouped.forEach { (type, nodes) ->
                        item(key = "header-${type.name}") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = CircleShape,
                                    color = type.tint(),
                                    modifier = Modifier.size(10.dp)
                                ) {}
                                Spacer(modifier = Modifier.width(AppSpacing.sm))
                                FreshSectionHeader(title = "${typeLabels[type]} (${nodes.size})")
                            }
                        }
                        items(nodes, key = { "node-${type.name}-${it.canonicalForm}" }) { node ->
                            val related = remember(node, conceptGraph) {
                                conceptGraph.getRelatedConcepts(node.canonicalForm, maxResults = 5)
                            }
                            FreshMenuCard(
                                icon = type.icon(),
                                iconContentDescription = typeLabels[type],
                                title = node.displayForm,
                                description = stringResource(
                                    R.string.graph_notes_count,
                                    node.noteCount
                                ) + if (related.isNotEmpty()) " · ${related.size} links" else "",
                                trailing = {
                                    Text(
                                        text = "${(node.importance * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = type.tint(),
                                        maxLines = 1
                                    )
                                },
                                onClick = { selectedNode = node }
                            )
                        }
                    }
                }
            }
        }
    }

    selectedNode?.let { node ->
        NodeDetailSheet(
            node = node,
            graph = conceptGraph,
            typeLabel = typeLabels[node.type] ?: node.type.name,
            onSelectRelated = { canonical -> selectedNode = conceptGraph.nodes[canonical] },
            onNoteClick = { name ->
                selectedNode = null
                onNoteClick(name)
            },
            onDismiss = { selectedNode = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeDetailSheet(
    node: ConceptNode,
    graph: ConceptGraph,
    typeLabel: String,
    onSelectRelated: (String) -> Unit,
    onNoteClick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val relatedConcepts = remember(node, graph) {
        graph.getRelatedConcepts(node.canonicalForm, maxResults = 5)
    }
    FreshSheet(onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = CircleShape,
                color = node.type.tint(),
                modifier = Modifier.size(12.dp)
            ) {}
            Spacer(modifier = Modifier.width(AppSpacing.sm))
            FreshSheetTitle(text = node.displayForm, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.graph_close))
            }
        }
        Text(
            text = "$typeLabel · " + stringResource(R.string.graph_notes_count, node.noteCount),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (relatedConcepts.isNotEmpty()) {
            Text(
                text = stringResource(R.string.graph_connected_to),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            relatedConcepts.forEach { (concept, score) ->
                val target = graph.nodes[concept]
                AssistChip(
                    onClick = { if (target != null) onSelectRelated(concept) },
                    label = {
                        Text(
                            "$concept (${(score * 100).toInt()}%)",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                )
            }
        }
        if (node.sampleNotes.isNotEmpty()) {
            Text(
                text = stringResource(R.string.graph_found_in),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            node.sampleNotes.take(3).forEach { noteName ->
                TextButton(onClick = { onNoteClick(noteName) }) {
                    Text(
                        text = noteName.noteDisplayTitle(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun InteractiveGraphCanvas(
    nodes: List<ConceptNode>,
    edges: List<ConceptEdge>,
    selectedNode: ConceptNode?,
    onNodeClick: (ConceptNode) -> Unit,
    modifier: Modifier = Modifier
) {
    val positions = remember(nodes, edges) {
        computeGraphLayout(nodes, edges)
    }

    var pan by remember { mutableStateOf(Offset.Zero) }
    var zoom by remember { mutableFloatStateOf(1.0f) }
    val textMeasurer = rememberTextMeasurer()
    val edgeColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = modifier
            .clipToBounds()
            .pointerInput(nodes, positions, pan, zoom) {
                detectTapGestures { tapOffset ->
                    val graphCenter = Offset(size.width / 2f, size.height / 2f)
                    val graphX = (tapOffset.x - graphCenter.x - pan.x) / zoom
                    val graphY = (tapOffset.y - graphCenter.y - pan.y) / zoom
                    val tapPoint = Offset(graphX, graphY)

                    val clicked = nodes.firstOrNull { node ->
                        val pos = positions[node.canonicalForm] ?: return@firstOrNull false
                        val nodeRadius = (16f + node.noteCount * 2f).coerceIn(16f, 32f)
                        val touchPadding = 20f
                        val dist = kotlin.math.hypot(pos.x - tapPoint.x, pos.y - tapPoint.y)
                        dist <= (nodeRadius + touchPadding)
                    }
                    if (clicked != null) {
                        onNodeClick(clicked)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTransformGestures { _, panDelta, zoomDelta, _ ->
                    pan += panDelta
                    zoom = (zoom * zoomDelta).coerceIn(0.25f, 3.5f)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val graphCenter = Offset(size.width / 2f, size.height / 2f)

            withTransform({
                translate(left = graphCenter.x + pan.x, top = graphCenter.y + pan.y)
                scale(scaleX = zoom, scaleY = zoom, pivot = Offset.Zero)
            }) {
                // 1. Draw connecting edges
                val relevantNodes = nodes.associateBy { it.canonicalForm }
                for (edge in edges) {
                    if (!relevantNodes.containsKey(edge.sourceConcept) || !relevantNodes.containsKey(edge.targetConcept)) continue
                    val p1 = positions[edge.sourceConcept] ?: continue
                    val p2 = positions[edge.targetConcept] ?: continue
                    val alpha = (0.25f + edge.strength * 0.5f).coerceIn(0.2f, 0.75f)
                    val strokeWidth = 1.5f + edge.strength * 2.5f
                    drawLine(
                        color = edgeColor.copy(alpha = alpha),
                        start = p1,
                        end = p2,
                        strokeWidth = strokeWidth
                    )
                }

                // 2. Draw nodes
                for (node in nodes) {
                    val pos = positions[node.canonicalForm] ?: continue
                    val nodeRadius = (16f + node.noteCount * 2f).coerceIn(16f, 32f)
                    val tint = node.type.tint()
                    val isSelected = selectedNode?.canonicalForm == node.canonicalForm

                    if (isSelected) {
                        drawCircle(
                            color = tint.copy(alpha = 0.3f),
                            radius = nodeRadius + 9f,
                            center = pos
                        )
                        drawCircle(
                            color = tint,
                            radius = nodeRadius + 3f,
                            center = pos,
                            style = Stroke(width = 3f)
                        )
                    }

                    drawCircle(
                        color = tint,
                        radius = nodeRadius,
                        center = pos
                    )

                    drawCircle(
                        color = Color.White.copy(alpha = 0.3f),
                        radius = nodeRadius * 0.45f,
                        center = pos
                    )

                    // Draw label when zoom is sufficient
                    if (zoom >= 0.55f) {
                        val textLayout = textMeasurer.measure(
                            text = AnnotatedString(node.displayForm),
                            style = TextStyle(
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = textColor
                            ),
                            maxLines = 1
                        )
                        drawText(
                            textLayoutResult = textLayout,
                            topLeft = Offset(
                                pos.x - textLayout.size.width / 2f,
                                pos.y + nodeRadius + 4f
                            )
                        )
                    }
                }
            }
        }

        // Floating interactive controls overlay (bottom-end)
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(AppSpacing.md),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
            tonalElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { zoom = (zoom * 1.25f).coerceAtMost(3.5f) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.graph_zoom_in),
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = {
                        pan = Offset.Zero
                        zoom = 1.0f
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.graph_reset_view),
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(
                    onClick = { zoom = (zoom / 1.25f).coerceAtLeast(0.25f) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Text("−", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
            }
        }
    }
}

private fun computeGraphLayout(
    nodes: List<ConceptNode>,
    edges: List<ConceptEdge>
): Map<String, Offset> {
    if (nodes.isEmpty()) return emptyMap()

    val nodeMap = nodes.associateBy { it.canonicalForm }
    val count = nodes.size
    val positions = mutableMapOf<String, Offset>()
    val velocities = mutableMapOf<String, Offset>()

    val angleStep = (2.0 * Math.PI) / count
    nodes.forEachIndexed { i, node ->
        val angle = i * angleStep
        val radius = 180f + (i % 5) * 55f
        positions[node.canonicalForm] = Offset(
            (kotlin.math.cos(angle) * radius).toFloat(),
            (kotlin.math.sin(angle) * radius).toFloat()
        )
        velocities[node.canonicalForm] = Offset.Zero
    }

    val relevantEdges = edges.filter { edge ->
        nodeMap.containsKey(edge.sourceConcept) && nodeMap.containsKey(edge.targetConcept)
    }

    val iterations = 40
    val k = 150f

    for (iter in 0 until iterations) {
        val forces = mutableMapOf<String, Offset>()
        nodes.forEach { forces[it.canonicalForm] = Offset.Zero }

        // Coulomb repulsion between all node pairs
        for (i in 0 until nodes.size) {
            val n1 = nodes[i]
            val p1 = positions[n1.canonicalForm] ?: continue
            for (j in (i + 1) until nodes.size) {
                val n2 = nodes[j]
                val p2 = positions[n2.canonicalForm] ?: continue

                val dx = p1.x - p2.x
                val dy = p1.y - p2.y
                val dist = kotlin.math.hypot(dx, dy).coerceAtLeast(10f)
                if (dist < 600f) {
                    val repForce = (k * k) / (dist * dist)
                    val forceMag = repForce.coerceAtMost(30f)
                    val fx = (dx / dist) * forceMag
                    val fy = (dy / dist) * forceMag
                    val fVec = Offset(fx, fy)
                    forces[n1.canonicalForm] = (forces[n1.canonicalForm] ?: Offset.Zero) + fVec
                    forces[n2.canonicalForm] = (forces[n2.canonicalForm] ?: Offset.Zero) - fVec
                }
            }
        }

        // Hooke attraction along edges
        for (edge in relevantEdges) {
            val p1 = positions[edge.sourceConcept] ?: continue
            val p2 = positions[edge.targetConcept] ?: continue
            val dx = p2.x - p1.x
            val dy = p2.y - p1.y
            val dist = kotlin.math.hypot(dx, dy).coerceAtLeast(10f)
            val attForce = (dist * dist) / (k * 2.5f) * edge.strength.coerceIn(0.3f, 1f)
            val forceMag = attForce.coerceAtMost(25f)
            val fx = (dx / dist) * forceMag
            val fy = (dy / dist) * forceMag
            val fVec = Offset(fx, fy)
            forces[edge.sourceConcept] = (forces[edge.sourceConcept] ?: Offset.Zero) + fVec
            forces[edge.targetConcept] = (forces[edge.targetConcept] ?: Offset.Zero) - fVec
        }

        // Weak centering gravity
        for (node in nodes) {
            val pos = positions[node.canonicalForm] ?: continue
            val centerForce = Offset(-pos.x * 0.03f, -pos.y * 0.03f)
            forces[node.canonicalForm] = (forces[node.canonicalForm] ?: Offset.Zero) + centerForce
        }

        // Integrate forces with cooling damping
        val damping = 0.82f
        for (node in nodes) {
            val id = node.canonicalForm
            val f = forces[id] ?: Offset.Zero
            val prevV = velocities[id] ?: Offset.Zero
            val vx = (prevV.x + f.x) * damping
            val vy = (prevV.y + f.y) * damping
            velocities[id] = Offset(vx, vy)
            val currentPos = positions[id] ?: Offset.Zero
            positions[id] = Offset(currentPos.x + vx, currentPos.y + vy)
        }
    }

    return positions
}
