package com.kayan.x.agent

data class AgentStep(
    val stepId: String,
    val toolName: String,
    val params: Map<String, Any>,
    val success: Boolean,
    val output: Any? = null,
    val error: String? = null,
    val verification: VerificationResult? = null,
    val durationMs: Long = 0L
)

data class VerificationResult(
    val passed: Boolean,
    val checks: List<String>,       // human-readable check descriptions
    val failures: List<String>      // which checks failed
) {
    override fun toString(): String {
        return if (passed) "PASS (${checks.size} checks)" else "FAIL: ${failures.joinToString()}"
    }
}
