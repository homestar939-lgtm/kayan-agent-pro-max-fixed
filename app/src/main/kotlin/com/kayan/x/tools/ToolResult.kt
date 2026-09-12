package com.kayan.x.tools

data class ToolResult(
    val success: Boolean,
    val output: Any? = null,
    val error: String? = null,
    val metadata: Map<String, Any> = emptyMap()
) {
    companion object {
        fun success(output: Any, metadata: Map<String, Any> = emptyMap()) =
            ToolResult(success = true, output = output, metadata = metadata)
        fun error(msg: String) =
            ToolResult(success = false, error = msg)
    }
}
