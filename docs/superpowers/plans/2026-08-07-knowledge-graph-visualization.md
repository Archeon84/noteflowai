# Knowledge Graph Visualization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an interactive Canvas-based graph view that renders concept nodes and their co-occurrence edges, with tap-to-navigate and cluster coloring.

**Architecture:** A new `KnowledgeGraphScreen` composable renders nodes as circles and edges as lines on a Canvas. Node positions are computed by a force-directed layout algorithm running on a background thread. The screen reads from the existing `ConceptGraphRepository.graph` StateFlow via `MainViewModel`. A new navigation entry point is added to `HomeScreen` and the `Screen` enum.

**Tech Stack:** Jetpack Compose Canvas, Kotlin Coroutines (force layout), Material3, existing ConceptGraphRepository + ConceptGraph data model.

## Global Constraints

- Android minSdk 26, targetSdk 34
- Kotlin 1.9+, Compose BOM 2026.06
- No new dependencies (Canvas is built-in Compose)
- Follow existing patterns: ViewModel + StateFlow, Compose Material3, file-per-screen in `ui/screens/`
- All data comes from existing `ConceptGraphRepository.graph` StateFlow — no new persistence

## File Structure

| File | Responsibility |
|------|---------------|
| `ui/screens/KnowledgeGraphScreen.kt` | Main screen composable: Canvas rendering, node/edge layout, tap handling |
| `data/graph/ForceLayout.kt` | Force-directed graph layout algorithm (nodes as particles, edges as springs) |
| `viewmodel/MainViewModel.kt:~295` | Expose `conceptGraph` StateFlow from `ConceptGraphRepository.graph` |
| `ui/screens/NoteFlowApp.kt:47` | Add `GRAPH` to `Screen` enum |
| `ui/screens/HomeScreen.kt:~172` | Add "Knowledge Graph" quick-action button |
| `ui/navigation/NoteFlowNavigation.kt` | Add route for `Screen.GRAPH` |

---

### Task 1: Expose concept graph data from MainViewModel

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt:292-295`

**Interfaces:**
- Consumes: `conceptGraphRepository.graph` (existing `StateFlow<ConceptGraph>`)
- Produces: `val conceptGraph: StateFlow<ConceptGraph>` on MainViewModel

- [ ] **Step 1: Add conceptGraph StateFlow to MainViewModel**

In `MainViewModel.kt`, after the existing `conceptGraphEnabled` declaration (~line 292), add:

```kotlin
val conceptGraph: StateFlow<ConceptGraph> = conceptGraphRepository.graph
```

Import `com.noteflowai.app.data.concept.ConceptGraph` if not already imported.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/viewmodel/MainViewModel.kt
git commit -m "feat: expose conceptGraph StateFlow from MainViewModel"
```

---

### Task 2: Force-directed layout algorithm

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/data/graph/ForceLayout.kt`

**Interfaces:**
- Consumes: `List<ConceptNode>`, `List<ConceptEdge>` (from `ConceptGraph`)
- Produces: `ForceLayoutResult(nodePositions: Map<String, Offset>, edgeEndpoints: List<Pair<Offset, Offset>>)` — pre-computed positions ready for Canvas drawing

- [ ] **Step 1: Create ForceLayout.kt with data classes**

Create `app/src/main/java/com/noteflowai/app/data/graph/ForceLayout.kt`:

```kotlin
package com.noteflowai.app.data.graph

import androidx.compose.ui.geometry.Offset
import com.noteflowai.app.data.concept.ConceptGraph

/**
 * Force-directed graph layout. Computes node positions using a simple
 * spring-electric model: edges attract, nodes repel.
 */
object ForceLayout {

    data class NodePosition(
        val canonicalForm: String,
        val position: Offset,
        val radius: Float  // scaled by importance
    )

    data class EdgeLine(
        val from: Offset,
        val to: Offset,
        val strength: Float
    )

    data class LayoutResult(
        val nodes: List<NodePosition>,
        val edges: List<EdgeLine>
    )

    /**
     * Compute layout for a ConceptGraph.
     * @param graph the concept graph
     * @param width canvas width in pixels
     * @param height canvas height in pixels
     * @param iterations number of simulation steps (more = better convergence)
     */
    fun compute(
        graph: ConceptGraph,
        width: Float,
        height: Float,
        iterations: Int = 100
    ): LayoutResult {
        val nodeList = graph.nodes.values.toList()
        val edgeList = graph.edges

        if (nodeList.isEmpty()) return LayoutResult(emptyList(), emptyList())

        // Initialize positions randomly within canvas bounds (deterministic seed)
        val positions = mutableMapOf<String, Offset>()
        nodeList.forEachIndexed { index, node ->
            val angle = 2.0 * Math.PI * index / nodeList.size
            val radius = minOf(width, height) * 0.3f
            positions[node.canonicalForm] = Offset(
                (width / 2f + radius * Math.cos(angle)).toFloat(),
                (height / 2f + radius * Math.sin(angle)).toFloat()
            )
        }

        // Build adjacency for fast lookup
        val edgeMap = edgeList.associateBy {
            minOf(it.sourceConcept, it.targetConcept) to maxOf(it.sourceConcept, it.targetConcept)
        }

        val repulsionForce = 5000f
        val attractionForce = 0.01f
        val damping = 0.9f
        val minDistance = 50f

        // Run simulation
        repeat(iterations) { step ->
            val velocities = mutableMapOf<String, Offset>()

            // Repulsion between all node pairs
            for (i in nodeList.indices) {
                for (j in i + 1 until nodeList.size) {
                    val posA = positions[nodeList[i].canonicalForm] ?: continue
                    val posB = positions[nodeList[j].canonicalForm] ?: continue
                    val delta = posA - posB
                    val distance = maxOf(delta.getDistance(), minDistance)
                    val force = repulsionForce / (distance * distance)
                    val direction = delta / distance
                    val velocity = direction * force

                    velocities[nodeList[i].canonicalForm] =
                        (velocities[nodeList[i].canonicalForm] ?: Offset.Zero) + velocity
                    velocities[nodeList[j].canonicalForm] =
                        (velocities[nodeList[j].canonicalForm] ?: Offset.Zero) - velocity
                }
            }

            // Attraction along edges
            for (edge in edgeList) {
                val posA = positions[edge.sourceConcept] ?: continue
                val posB = positions[edge.targetConcept] ?: continue
                val delta = posB - posA
                val distance = delta.getDistance()
                val force = attractionForce * distance * edge.strength
                val direction = if (distance > 0) delta / distance else Offset.Zero
                val velocity = direction * force

                velocities[edge.sourceConcept] =
                    (velocities[edge.sourceConcept] ?: Offset.Zero) + velocity
                velocities[edge.targetConcept] =
                    (velocities[edge.targetConcept] ?: Offset.Zero) - velocity
            }

            // Apply velocities with damping, clamp to canvas
            for (node in nodeList) {
                val vel = velocities[node.canonicalForm] ?: continue
                val pos = positions[node.canonicalForm] ?: continue
                val newPos = pos + vel * damping
                positions[node.canonicalForm] = Offset(
                    newPos.x.coerceIn(40f, width - 40f),
                    newPos.y.coerceIn(40f, height - 40f)
                )
            }
        }

        // Build output
        val nodePositions = nodeList.map { node ->
            NodePosition(
                canonicalForm = node.canonicalForm,
                position = positions[node.canonicalForm] ?: Offset.Zero,
                radius = 8f + node.importance * 20f  // 8-28px radius
            )
        }

        val edgeLines = edgeList.mapNotNull { edge ->
            val from = positions[edge.sourceConcept] ?: return@mapNotNull null
            val to = positions[edge.targetConcept] ?: return@mapNotNull null
            EdgeLine(from = from, to = to, strength = edge.strength)
        }

        return LayoutResult(nodePositions, edgeLines)
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/data/graph/ForceLayout.kt
git commit -m "feat: add force-directed graph layout algorithm"
```

---

### Task 3: KnowledgeGraphScreen composable

**Files:**
- Create: `app/src/main/java/com/noteflowai/app/ui/screens/KnowledgeGraphScreen.kt`

**Interfaces:**
- Consumes: `MainViewModel.conceptGraph` (from Task 1), `ForceLayout.compute()` (from Task 2)
- Produces: Full-screen graph view with interactive nodes

- [ ] **Step 1: Create KnowledgeGraphScreen.kt**

Create `app/src/main/java/com/noteflowai/app/ui/screens/KnowledgeGraphScreen.kt`:

```kotlin
package com.noteflowai.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.noteflowai.app.data.concept.ConceptExtractor
import com.noteflowai.app.data.concept.ConceptGraph
import com.noteflowai.app.data.concept.ConceptNode
import com.noteflowai.app.data.graph.ForceLayout
import com.noteflowai.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeGraphScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onNoteClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val conceptGraph by viewModel.conceptGraph.collectAsState()
    var selectedNode by remember { mutableStateOf<ConceptNode?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // Force layout recomputes when graph or refreshKey changes
    val layoutResult = remember(conceptGraph, refreshKey) {
        if (conceptGraph.nodes.isEmpty()) null
        else ForceLayout.compute(conceptGraph, 1000f, 1400f, iterations = 80)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Knowledge Graph") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { refreshKey++ }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Re-layout")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (conceptGraph.nodes.isEmpty()) {
                // Empty state
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No concepts indexed yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Create some notes to build your knowledge graph",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (layoutResult != null) {
                GraphCanvas(
                    layout = layoutResult,
                    nodes = conceptGraph.nodes,
                    selectedNode = selectedNode,
                    onNodeTap = { canonicalForm ->
                        selectedNode = conceptGraph.nodes[canonicalForm]
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Selected node detail card
                selectedNode?.let { node ->
                    NodeDetailCard(
                        node = node,
                        graph = conceptGraph,
                        onNoteClick = onNoteClick,
                        onDismiss = { selectedNode = null },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                    )
                }
            }

            // Node count badge
            if (conceptGraph.nodes.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = "${conceptGraph.nodes.size} concepts, ${conceptGraph.edges.size} connections",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun GraphCanvas(
    layout: ForceLayout.LayoutResult,
    nodes: Map<String, ConceptNode>,
    selectedNode: ConceptNode?,
    onNodeTap: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Scale factor: layout computed at 1000x1400, canvas fills screen
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 3f)
        offset += panChange
    }

    // Type colors
    val typeColors = mapOf(
        ConceptExtractor.ConceptType.ENTITY to Color(0xFF4CAF50),
        ConceptExtractor.ConceptType.THEME to Color(0xFF2196F3),
        ConceptExtractor.ConceptType.ACTION to Color(0xFFFF9800),
        ConceptExtractor.ConceptType.TEMPORAL to Color(0xFF9C27B0),
        ConceptExtractor.ConceptType.TECHNICAL to Color(0xFF00BCD4)
    )

    Canvas(
        modifier = modifier
            .transformable(state = transformState)
            .pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    // Find tapped node
                    val layoutOffset = (tapOffset - offset) / scale
                    val hitNode = layout.nodes.firstOrNull { nodePos ->
                        val distance = (nodePos.position - layoutOffset).getDistance()
                        distance <= nodePos.radius + 8f  // 8px tap tolerance
                    }
                    if (hitNode != null) {
                        onNodeTap(hitNode.canonicalForm)
                    }
                }
            }
    ) {
        // Apply transform
        drawContext.canvas.nativeCanvas.save()
        drawContext.canvas.nativeCanvas.translate(offset.x, offset.y)
        drawContext.canvas.nativeCanvas.scale(scale, scale)

        // Draw edges
        for (edge in layout.edges) {
            val alpha = (edge.strength * 0.6f + 0.1f).coerceIn(0.1f, 0.7f)
            val strokeWidth = 1f + edge.strength * 2f
            drawLine(
                color = Color.Gray.copy(alpha = alpha),
                start = edge.from,
                end = edge.to,
                strokeWidth = strokeWidth
            )
        }

        // Draw nodes
        for (nodePos in layout.nodes) {
            val concept = nodes[nodePos.canonicalForm] ?: continue
            val color = typeColors[concept.type] ?: Color.Gray
            val isSelected = selectedNode?.canonicalForm == nodePos.canonicalForm

            // Node circle
            drawCircle(
                color = color,
                radius = nodePos.radius,
                center = nodePos.position
            )

            // Selection ring
            if (isSelected) {
                drawCircle(
                    color = color,
                    radius = nodePos.radius + 4f,
                    center = nodePos.position,
                    style = Stroke(width = 3f)
                )
            }

            // Node label
            val paint = android.graphics.Paint().apply {
                this.color = android.graphics.Color.DKGRAY
                textSize = 10f + concept.importance * 6f
                textAlign = android.graphics.Paint.Align.CENTER
                isAntiAlias = true
            }
            drawContext.canvas.nativeCanvas.drawText(
                concept.displayForm,
                nodePos.position.x,
                nodePos.position.y + nodePos.radius + 14f,
                paint
            )
        }

        drawContext.canvas.nativeCanvas.restore()
    }
}

@Composable
private fun NodeDetailCard(
    node: ConceptNode,
    graph: ConceptGraph,
    onNoteClick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val relatedConcepts = remember(node, graph) {
        graph.getRelatedConcepts(node.canonicalForm, maxResults = 5)
    }

    Card(
        modifier = modifier,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = node.displayForm,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${node.type.name.lowercase()} · ${node.noteCount} notes",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }

            if (relatedConcepts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Connected to:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                relatedConcepts.forEach { (concept, score) ->
                    Text(
                        text = "$concept (${(score * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp, top = 2.dp)
                    )
                }
            }

            if (node.sampleNotes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Found in:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                node.sampleNotes.take(3).forEach { noteName ->
                    TextButton(
                        onClick = { onNoteClick(noteName) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = noteName.removeSuffix(".md"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/KnowledgeGraphScreen.kt
git commit -m "feat: add KnowledgeGraphScreen with force-directed Canvas layout"
```

---

### Task 4: Add GRAPH navigation entry

**Files:**
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/NoteFlowApp.kt:47-49` — add `GRAPH` to Screen enum
- Modify: `app/src/main/java/com/noteflowai/app/ui/navigation/NoteFlowNavigation.kt` — add route
- Modify: `app/src/main/java/com/noteflowai/app/ui/screens/HomeScreen.kt:~172` — add quick action

**Interfaces:**
- Consumes: `KnowledgeGraphScreen` composable (from Task 3)
- Produces: Navigation route `Screen.GRAPH` accessible from HomeScreen

- [ ] **Step 1: Add GRAPH to Screen enum**

In `NoteFlowApp.kt`, add `GRAPH` after the existing entries (around line 49):

```kotlin
enum class Screen {
    HOME, RECORD, NOTES, NOTE_DETAIL, SCAN, YOUTUBE, DOCUMENT, CHAT, SETTINGS, GRAPH
}
```

- [ ] **Step 2: Add navigation route**

In `NoteFlowNavigation.kt`, find the `composable(Screen.SETTINGS.name)` block and add after it:

```kotlin
composable(Screen.GRAPH.name) {
    KnowledgeGraphScreen(
        viewModel = viewModel,
        onBack = { navController.popBackStack() },
        onNoteClick = { fileName ->
            viewModel.selectNoteByFileName(fileName)
            navController.navigate(Screen.NOTE_DETAIL.name)
        }
    )
}
```

Add the import: `import com.noteflowai.app.ui.screens.KnowledgeGraphScreen`

- [ ] **Step 3: Add HomeScreen quick action**

In `HomeScreen.kt`, find the Quick Actions section (around line 172-200 where other action buttons are). Add a new action button in the same pattern:

```kotlin
// After existing quick action buttons:
QuickActionButton(
    icon = Icons.Default.AccountTree,
    label = "Graph",
    onClick = { onNavigate(Screen.GRAPH) }
)
```

Add import: `import androidx.compose.material.icons.filled.AccountTree`

- [ ] **Step 4: Verify it compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/noteflowai/app/ui/screens/NoteFlowApp.kt \
       app/src/main/java/com/noteflowai/app/ui/navigation/NoteFlowNavigation.kt \
       app/src/main/java/com/noteflowai/app/ui/screens/HomeScreen.kt
git commit -m "feat: add GRAPH navigation entry from HomeScreen"
```

---

### Task 5: End-to-end test and polish

**Files:**
- No new files; verification only

- [ ] **Step 1: Build and install**

Run: `./gradlew installDebug`
Expected: App installs on device/emulator

- [ ] **Step 2: Manual verification**

1. Open app → HomeScreen → tap "Graph" quick action
2. Verify graph screen opens with concept nodes rendered as colored circles
3. Verify edges connect related concepts
4. Tap a node → detail card shows at bottom with concept name, type, connected concepts, and note links
5. Tap a note link → navigates to note detail
6. Pinch-to-zoom and pan work on the canvas
7. Tap Refresh → layout re-randomizes
8. Empty state shows when no concepts are indexed

- [ ] **Step 3: Commit final state**

```bash
git add -A
git commit -m "feat: knowledge graph visualization complete"
```
