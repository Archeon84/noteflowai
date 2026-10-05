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

        val repulsionForce = 5000f
        val attractionForce = 0.01f
        val damping = 0.9f
        val minDistance = 50f

        // Run simulation
        repeat(iterations) {
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
