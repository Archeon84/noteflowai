package com.noteflowai.app.data.eval

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.measureTimeMillis

class PerformanceBenchmarkRunner {

    data class BenchmarkReport(
        val avgNoteSaveLatencyMs: Long,
        val avgSearchLatencyMs: Long,
        val noteSaveTargetMet: Boolean,
        val searchTargetMet: Boolean
    )

    suspend fun measureNoteSaveLatency(notesDir: File, sampleCount: Int = 10): Long = withContext(Dispatchers.IO) {
        var totalMs = 0L
        notesDir.mkdirs()

        for (i in 1..sampleCount) {
            val noteFile = File(notesDir, "bench_note_$i.json")
            val content = """{"fileName":"bench_note_$i.json","content":"Sample benchmark note content for latency testing #$i","category":"Benchmark"}"""
            val elapsed = measureTimeMillis {
                noteFile.writeText(content)
            }
            totalMs += elapsed
        }

        (totalMs / sampleCount.coerceAtLeast(1))
    }

    suspend fun measureSearchLatency(sampleCount: Int = 20): Long = withContext(Dispatchers.Default) {
        val sampleCorpus = (1..100).map { "Note #$it discussing project milestones, architecture, and sprint deadlines." }
        var totalMs = 0L

        for (i in 1..sampleCount) {
            val query = if (i % 2 == 0) "milestones" else "architecture"
            val elapsed = measureTimeMillis {
                val matches = sampleCorpus.filter { it.contains(query, ignoreCase = true) }
                matches.size
            }
            totalMs += elapsed
        }

        (totalMs / sampleCount.coerceAtLeast(1))
    }

    suspend fun runFullBenchmark(notesDir: File): BenchmarkReport {
        val saveMs = measureNoteSaveLatency(notesDir)
        val searchMs = measureSearchLatency()
        return BenchmarkReport(
            avgNoteSaveLatencyMs = saveMs,
            avgSearchLatencyMs = searchMs,
            noteSaveTargetMet = saveMs < 500L,
            searchTargetMet = searchMs < 150L
        )
    }
}
