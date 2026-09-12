package com.kayan.x.agent

/**
 * Mutable agent execution state for one task run.
 * Owned entirely by [AgentOrchestrator]; never directly mutated by the LLM.
 */
data class AgentState(
    val task: String,
    var status: Status = Status.PLANNING,
    var stepCount: Int = 0,
    val history: MutableList<AgentStep> = mutableListOf(),
    var lastObservation: String = "",
    var finalAnswer: String? = null
) {
    enum class Status {
        PLANNING,
        POLICY_CHECK,
        AWAITING_CONFIRMATION,
        EXECUTING,
        OBSERVING,
        VERIFYING,
        REPLANNING,
        SUCCESS,
        FAILED,
        BLOCKED,
        CANCELLED,
        MAX_STEPS_REACHED
    }

    fun record(step: AgentStep): AgentStep {
        history.add(step)
        stepCount++
        return step
    }

    /** Build the observation string passed back to the LLM on the next cycle. */
    fun buildObservation(): String {
        val last = history.lastOrNull() ?: return "No previous steps."
        return buildString {
            appendLine("Step ${last.stepId} result:")
            appendLine("Tool: ${last.toolName}")
            appendLine("Success: ${last.success}")
            if (last.output != null) appendLine("Output: ${last.output.toString().take(2000)}")
            if (last.error != null)  appendLine("Error: ${last.error}")
            if (last.verification != null) appendLine("Verification: ${last.verification}")
        }
    }

    /** Build the full conversation history for the LLM prompt. */
    fun buildHistorySummary(): String = buildString {
        appendLine("Task: $task")
        appendLine("Steps completed: $stepCount")
        history.takeLast(5).forEach { step ->
            appendLine("  [${step.stepId}] ${step.toolName}(${step.params}) → success=${step.success}")
        }
    }
}
