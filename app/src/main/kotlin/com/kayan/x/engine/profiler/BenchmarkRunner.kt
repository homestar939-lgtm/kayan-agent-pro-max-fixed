package com.kayan.x.engine.profiler

import android.app.ActivityManager
import android.content.Context
import com.kayan.x.engine.LlamaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * In-app benchmark suite.
 *
 * Measures all metrics required by spec §19:
 *  - model load time
 *  - tokens/sec (generation)
 *  - first token latency
 *  - memory usage (RSS delta)
 *  - tool execution latency (measured separately by AgentOrchestrator)
 */
class BenchmarkRunner(
    private val context: Context,
    private val engine: LlamaEngine
) {
    data class BenchmarkResult(
        val modelLoadMs: Long,
        val firstTokenMs: Long,
        val tokensPerSec: Float,
        val memoryDeltaMb: Float,
        val totalTokensGenerated: Int,
        val configSnapshot: String
    ) {
        fun summary(): String = buildString {
            appendLine("┌── Benchmark Results ─────────────────┐")
            appendLine("│ Load time        : ${modelLoadMs} ms")
            appendLine("│ First token      : ${firstTokenMs} ms")
            appendLine("│ Tokens/sec       : ${"%.2f".format(tokensPerSec)}")
            appendLine("│ Memory delta     : ${"%.1f".format(memoryDeltaMb)} MB")
            appendLine("│ Tokens generated : $totalTokensGenerated")
            appendLine("│ Config           : $configSnapshot")
            appendLine("└──────────────────────────────────────┘")
        }
    }

    private val benchPrompt = "Tell me about artificial intelligence in three sentences."
    private val benchTokens = 64

    /**
     * Run a full benchmark on the currently loaded model.
     * Must be called AFTER the model is loaded ([engine.isModelLoaded] == true).
     * Runs on [Dispatchers.Default] — call from a coroutine.
     */
    suspend fun run(): BenchmarkResult = withContext(Dispatchers.Default) {
        Timber.i("Starting benchmark...")

        val memBefore = getCurrentRssMb()

        // ── First token latency ──────────────────────────────────────────────
        var firstTokenMs = -1L
        val firstTokenStart = System.currentTimeMillis()

        var tokensGenerated = 0
        engine.inferStreaming(
            prompt     = benchPrompt,
            maxTokens  = benchTokens,
            temperature = 0.0f  // greedy for determinism
        ) { _ ->
            if (firstTokenMs < 0) {
                firstTokenMs = System.currentTimeMillis() - firstTokenStart
            }
            tokensGenerated++
            true
        }

        // Use the same streaming path as real chat. This makes the test a
        // faithful measurement of the loaded model and avoids running a second
        // generation immediately after the first one.
        val elapsedSec = ((System.currentTimeMillis() - firstTokenStart).coerceAtLeast(1L)) / 1000f
        val tps = tokensGenerated / elapsedSec

        val memAfter = getCurrentRssMb()
        val memDelta = (memAfter - memBefore).coerceAtLeast(0f)

        val result = BenchmarkResult(
            modelLoadMs          = engine.lastLoadTimeMs,
            firstTokenMs         = firstTokenMs.coerceAtLeast(0L),
            tokensPerSec         = tps,
            memoryDeltaMb        = memDelta,
            totalTokensGenerated = tokensGenerated,
            configSnapshot       = engine.currentConfigSnapshot()
        )
        Timber.i("Benchmark complete:\n${result.summary()}")
        result
    }

    /** Read current process RSS from /proc/self/status (kB → MB). */
    private fun getCurrentRssMb(): Float {
        return try {
            val lines = java.io.File("/proc/self/status").readLines()
            val vmRss = lines.firstOrNull { it.startsWith("VmRSS:") }
                ?.removePrefix("VmRSS:")?.trim()?.split("\\s+".toRegex())?.firstOrNull()
                ?.toLong() ?: 0L
            vmRss / 1024f
        } catch (_: Exception) {
            // Fallback: ActivityManager
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            (mi.totalMem - mi.availMem) / 1_048_576f
        }
    }
}
