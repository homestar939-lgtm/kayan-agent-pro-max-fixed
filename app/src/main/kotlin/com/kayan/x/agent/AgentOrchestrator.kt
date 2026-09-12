package com.kayan.x.agent

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kayan.x.engine.LlamaEngine
import com.kayan.x.safety.ConfirmationPolicy
import com.kayan.x.tools.ToolRegistry
import com.kayan.x.tools.ToolResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Agent Orchestrator — full dynamic loop.
 *
 * Loop:
 *   User → Planner (LLM) → Tool Selection → Permission/Policy → Execution
 *       → Observation → Verification → Re-plan → Final Answer
 *
 * Properties:
 *  - The plan is DYNAMIC. Each step is decided based on the PREVIOUS step's result.
 *  - The LLM proposes one action per cycle; Python/Kotlin owns execution & security.
 *  - Deterministic verification runs after every tool call.
 *  - High-risk operations surface a [PendingConfirmation] and pause the loop.
 *    The ViewModel resumes or cancels via [confirmPending] / [cancelPending].
 */
class AgentOrchestrator(
    private val engine: LlamaEngine,
    private val tools: ToolRegistry,
    private val maxSteps: Int = 20
) {
    private val gson = Gson()

    // ── Observable state for the UI ──────────────────────────────────────────
    private val _agentState = MutableStateFlow<AgentState?>(null)
    val agentState: StateFlow<AgentState?> = _agentState.asStateFlow()

    // ── Pending confirmation (null = no op awaiting confirmation) ────────────
    @Volatile private var pendingConfirmation: PendingConfirmation? = null

    data class PendingConfirmation(
        val toolName: String,
        val params: Map<String, Any>,
        val riskLevel: ConfirmationPolicy.OperationRisk,
        val resume: suspend () -> ToolResult
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Run the agent for [task]. Suspends until the agent finishes or hits [maxSteps].
     * Emits state changes via [agentState].
     */
    suspend fun run(task: String, onConfirmationRequired: suspend (PendingConfirmation) -> Unit): String {
        val state = AgentState(task = task)
        _agentState.value = state

        for (i in 0 until maxSteps) {
            state.status = AgentState.Status.PLANNING
            _agentState.value = state.copy()

            // ── Step 1: Ask the LLM for the next action ──────────────────────
            val actionJson = planNextAction(state) ?: run {
                state.status = AgentState.Status.FAILED
                _agentState.value = state.copy()
                return "تعذر توليد الخطوة التالية."
            }

            // ── Step 2: Parse the action ─────────────────────────────────────
            var parsed = parseAction(actionJson)
            if (parsed.action == "invalid") {
                // Local models sometimes wrap JSON in prose/markdown. Repair the
                // format instead of incorrectly treating the prose as a final answer.
                val repaired = repairAction(actionJson)
                parsed = parseAction(repaired)
                if (parsed.action == "invalid") {
                    state.status = AgentState.Status.FAILED
                    _agentState.value = state.copy()
                    return "تعذر فهم خطة الوكيل من النموذج المحلي. أعد المحاولة."
                }
            }

            if (parsed.action == "finish") {
                state.status = AgentState.Status.SUCCESS
                state.finalAnswer = parsed.finalMessage ?: "تم إنجاز المهمة."
                _agentState.value = state.copy()
                return state.finalAnswer!!
            }

            val toolName = parsed.tool ?: run {
                state.status = AgentState.Status.FAILED
                return "رد LLM لا يتضمن أداة."
            }

            val params = parsed.params ?: emptyMap()

            // ── Step 3: Policy / confirmation check ──────────────────────────
            state.status = AgentState.Status.POLICY_CHECK
            _agentState.value = state.copy()

            val risk = tools.riskOf(toolName)
            if (ConfirmationPolicy.requiresConfirmation(toolName)) {
                state.status = AgentState.Status.AWAITING_CONFIRMATION
                _agentState.value = state.copy()

                // Surface to UI and wait for user decision
                var toolResult: ToolResult? = null
                val pending = PendingConfirmation(
                    toolName  = toolName,
                    params    = params,
                    riskLevel = risk,
                    resume    = { tools.execute(toolName, params).also { toolResult = it } }
                )
                pendingConfirmation = pending
                onConfirmationRequired(pending)

                // The UI calls confirmPending() which resumes the lambda above
                // We poll until toolResult is set (the lambda ran) or cancelled
                // In practice, onConfirmationRequired suspends until user responds
                if (toolResult == null) {
                    // User cancelled
                    state.status = AgentState.Status.CANCELLED
                    _agentState.value = state.copy()
                    return "تم إلغاء العملية من قِبل المستخدم."
                }
                recordAndVerify(state, toolName, params, toolResult!!, i)
                continue
            }

            // ── Step 4: Execute ───────────────────────────────────────────────
            state.status = AgentState.Status.EXECUTING
            _agentState.value = state.copy()

            val t0 = System.currentTimeMillis()
            val toolResult = try {
                tools.execute(toolName, params)
            } catch (e: Exception) {
                Timber.e(e, "Tool execution exception: $toolName")
                ToolResult.error("Tool threw exception: ${e.message}")
            }
            val durationMs = System.currentTimeMillis() - t0

            // ── Step 5: Observe + Verify ─────────────────────────────────────
            recordAndVerify(state, toolName, params, toolResult, i, durationMs)

            state.status = AgentState.Status.REPLANNING
            _agentState.value = state.copy()
        }

        state.status = AgentState.Status.MAX_STEPS_REACHED
        _agentState.value = state.copy()
        return "توقفت المهمة بعد بلوغ الحد الأقصى ($maxSteps) من الخطوات."
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private: LLM planning
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun planNextAction(state: AgentState): String? {
        val systemPrompt = buildSystemPrompt()
        val userPrompt   = buildUserPrompt(state)

        return try {
            engine.infer(
                prompt      = buildFull(systemPrompt, userPrompt),
                maxTokens   = 256,
                temperature = 0.1f   // deterministic planning
            )
        } catch (e: Exception) {
            Timber.e(e, "LLM planning failed")
            null
        }
    }

    private fun buildSystemPrompt(): String = """
        أنت مساعد Kayan — وكيل يعمل محليًا على جهاز Android.
        
        عند الرد، أعد JSON فقط بدون أي نص إضافي:
        إذا أردت تنفيذ أداة:
        {"action":"tool","tool":"<tool_name>","params":{...}}
        
        إذا أنهيت المهمة:
        {"action":"finish","final_message":"<رسالتك للمستخدم>"}
        
        الأدوات المتاحة:
        ${tools.allSchemas()}
        
        قواعد هامة:
        - لا تخترع مسارات. استخدم: hsh:/... فقط لمساحة عمل HSH المصرح بها.
        - لا تحذف ملفات إلا إذا طُلب صراحةً.
        - نفذ خطوة واحدة في كل مرة وانظر نتيجتها قبل التالية.
    """.trimIndent()

    private fun buildUserPrompt(state: AgentState): String = buildString {
        appendLine("المهمة: ${state.task}")
        appendLine()
        if (state.history.isNotEmpty()) {
            appendLine("الخطوات السابقة:")
            appendLine(state.buildHistorySummary())
            appendLine()
            appendLine("الملاحظة الأخيرة:")
            appendLine(state.buildObservation())
        } else {
            appendLine("هذه الخطوة الأولى. ابدأ بتخطيط وتنفيذ أول أداة.")
        }
        appendLine()
        appendLine("ما هي الأداة التالية؟ (JSON فقط)")
    }

    private fun buildFull(system: String, user: String): String = """
<|im_start|>system
$system
<|im_end|>
<|im_start|>user
$user
<|im_end|>
<|im_start|>assistant
"""

    // ─────────────────────────────────────────────────────────────────────────
    // Private: verification (deterministic checks after each tool call)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun recordAndVerify(
        state: AgentState,
        toolName: String,
        params: Map<String, Any>,
        result: ToolResult,
        stepIdx: Int,
        durationMs: Long = 0L
    ) {
        state.status = AgentState.Status.VERIFYING
        val verification = verify(toolName, params, result)

        val step = AgentStep(
            stepId       = "step_${stepIdx + 1}",
            toolName     = toolName,
            params       = params,
            success      = result.success && verification.passed,
            output       = result.output,
            error        = result.error,
            verification = verification,
            durationMs   = durationMs
        )
        state.record(step)
        state.lastObservation = state.buildObservation()
        _agentState.value = state.copy()
        Timber.d("Step ${step.stepId}: $toolName → ${step.success} [${durationMs}ms]")
    }

    /**
     * Deterministic verification.
     * After WRITE: confirm file exists and has content.
     * After DELETE: confirm file is gone.
     * After MOVE/COPY: confirm destination exists.
     * After CREATE_DIRECTORY: confirm dir exists.
     * All other tools: pass if success=true.
     */
    private suspend fun verify(
        toolName: String,
        params: Map<String, Any>,
        result: ToolResult
    ): VerificationResult {
        val checks   = mutableListOf<String>()
        val failures = mutableListOf<String>()

        // Baseline: tool reported success
        checks.add("tool_success")
        if (!result.success) failures.add("tool_success")

        when (toolName) {
            "write_file" -> {
                val path = params["file_path"] as? String
                if (path != null) {
                    checks.add("file_exists_after_write")
                    val info = tools.execute("get_file_info", mapOf("file_path" to path))
                    if (!info.success) failures.add("file_exists_after_write")
                }
            }
            "create_directory" -> {
                val path = params["path"] as? String
                if (path != null) {
                    checks.add("directory_exists_after_create")
                    val info = tools.execute("get_file_info", mapOf("file_path" to path))
                    if (!info.success) failures.add("directory_exists_after_create")
                }
            }
            "delete_file" -> {
                val path = params["file_path"] as? String
                if (path != null) {
                    checks.add("file_gone_after_delete")
                    val info = tools.execute("get_file_info", mapOf("file_path" to path))
                    if (info.success) failures.add("file_gone_after_delete")  // should NOT exist
                }
            }
            "move_file" -> {
                val dst = params["destination"] as? String
                if (dst != null) {
                    checks.add("destination_exists_after_move")
                    val info = tools.execute("get_file_info", mapOf("file_path" to dst))
                    if (!info.success) failures.add("destination_exists_after_move")
                }
            }
            "copy_file" -> {
                val dst = params["destination"] as? String
                if (dst != null) {
                    checks.add("destination_exists_after_copy")
                    val info = tools.execute("get_file_info", mapOf("file_path" to dst))
                    if (!info.success) failures.add("destination_exists_after_copy")
                }
            }
        }

        return VerificationResult(
            passed   = failures.isEmpty(),
            checks   = checks,
            failures = failures
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private: JSON parsing
    // ─────────────────────────────────────────────────────────────────────────

    private data class ParsedAction(
        val action: String,
        val tool: String? = null,
        val params: Map<String, Any>? = null,
        val finalMessage: String? = null
    )

    private fun parseAction(json: String): ParsedAction {
        return try {
            // Extract first JSON object from response (LLM may add extra text)
            val start = json.indexOf('{')
            val end   = json.lastIndexOf('}')
            if (start < 0 || end < start) return ParsedAction("invalid", finalMessage = json)

            val clean = json.substring(start, end + 1)
            val obj   = JsonParser.parseString(clean).asJsonObject

            val action = obj.get("action")?.asString ?: "finish"
            val tool   = obj.get("tool")?.asString
            val params = obj.get("params")?.asJsonObject?.let { parseParams(it) }
            val msg    = obj.get("final_message")?.asString

            ParsedAction(action, tool, params, msg)
        } catch (e: Exception) {
            Timber.w("Failed to parse action JSON: $json (${e.message})")
            ParsedAction("invalid", finalMessage = json)
        }
    }

    private suspend fun repairAction(raw: String): String {
        val prompt = """
أنت مُصحح إخراج لوكيل Kayan محلي. حوّل النص التالي إلى JSON واحد فقط.
ممنوع أي Markdown أو شرح.
الصيغة المسموحة للأداة: {\"action\":\"tool\",\"tool\":\"اسم_الأداة\",\"params\":{}}
والإنهاء: {\"action\":\"finish\",\"final_message\":\"...\"}
النص: ${raw.take(3000)}
JSON فقط:
""".trimIndent()
        return try { engine.infer(prompt, maxTokens = 180, temperature = 0.0f) } catch (_: Exception) { "" }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseParams(obj: JsonObject): Map<String, Any> {
        val map = mutableMapOf<String, Any>()
        for ((key, element) in obj.entrySet()) {
            map[key] = when {
                element.isJsonPrimitive -> {
                    val prim = element.asJsonPrimitive
                    when {
                        prim.isBoolean -> prim.asBoolean
                        prim.isNumber  -> prim.asNumber
                        else           -> prim.asString
                    }
                }
                element.isJsonArray     -> gson.fromJson(element, List::class.java)
                element.isJsonObject    -> gson.fromJson(element, Map::class.java)
                else                   -> element.toString()
            }
        }
        return map
    }
}
