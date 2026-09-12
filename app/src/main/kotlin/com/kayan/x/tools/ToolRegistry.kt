package com.kayan.x.tools

import com.kayan.x.files.SafFileManager
import com.kayan.x.safety.ConfirmationPolicy
import com.kayan.x.safety.PathGuard

/**
 * Central tool registry.
 * Holds all registered [FileTool] instances and dispatches execution by name.
 * The Agent calls [execute] — it never instantiates tools directly.
 */
class ToolRegistry(guard: PathGuard, saf: SafFileManager) {

    private val tools: Map<String, FileTool> = listOf(
        ListFilesTool(guard, saf),
        ReadFileTool(guard, saf),
        WriteFileTool(guard, saf),
        CreateDirectoryTool(guard, saf),
        GetFileInfoTool(guard, saf),
        MoveFileTool(guard, saf),
        CopyFileTool(guard, saf),
        DeleteFileTool(guard, saf),
        RenameFileTool(guard, saf),
        SearchFilesTool(guard, saf)
    ).associateBy { it.name }

    fun toolNames(): Set<String> = tools.keys

    fun schemaFor(name: String): String? = tools[name]?.let { tool ->
        """{"name":"${tool.name}","description":"${tool.description}","risk":"${tool.risk}"}"""
    }

    fun allSchemas(): String = tools.values.joinToString(",", "[", "]") { tool ->
        """{"name":"${tool.name}","description":"${tool.description}"}"""
    }

    fun riskOf(name: String): ConfirmationPolicy.OperationRisk =
        tools[name]?.risk ?: ConfirmationPolicy.OperationRisk.HIGH

    suspend fun execute(name: String, params: Map<String, Any>): ToolResult {
        val tool = tools[name] ?: return ToolResult.error("Unknown tool: $name")
        val t0 = System.currentTimeMillis()
        val result = tool.execute(params)
        val latencyMs = System.currentTimeMillis() - t0
        return result.copy(metadata = result.metadata + mapOf("latency_ms" to latencyMs))
    }
}
