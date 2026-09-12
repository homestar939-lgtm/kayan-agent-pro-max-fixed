package com.kayan.x.safety

/**
 * Confirmation policy for dangerous file operations.
 *
 * Operations in [OperationRisk.CRITICAL] MUST be confirmed by the user before
 * the Agent executes them. The UI layer surfaces a confirmation dialog.
 * The Agent cannot bypass this by rephrasing the request.
 */
object ConfirmationPolicy {

    enum class OperationRisk { SAFE, MEDIUM, HIGH, CRITICAL }

    private val riskMap = mapOf(
        "list_files"       to OperationRisk.SAFE,
        "read_file"        to OperationRisk.SAFE,
        "get_file_info"    to OperationRisk.SAFE,
        "search_files"     to OperationRisk.SAFE,
        "create_directory" to OperationRisk.SAFE,
        "write_file"       to OperationRisk.MEDIUM,
        "copy_file"        to OperationRisk.MEDIUM,
        "move_file"        to OperationRisk.HIGH,
        "delete_file"      to OperationRisk.CRITICAL,
        "rename_file"      to OperationRisk.MEDIUM
    )

    fun riskOf(toolName: String): OperationRisk =
        riskMap[toolName] ?: OperationRisk.HIGH

    fun requiresConfirmation(toolName: String): Boolean =
        riskOf(toolName) >= OperationRisk.HIGH

    fun requiresExplicitConfirmation(toolName: String): Boolean =
        riskOf(toolName) == OperationRisk.CRITICAL
}
