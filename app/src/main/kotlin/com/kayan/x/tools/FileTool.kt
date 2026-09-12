package com.kayan.x.tools

import com.kayan.x.files.SafFileManager
import com.kayan.x.safety.ConfirmationPolicy
import com.kayan.x.safety.PathGuard

/**
 * All 9 filesystem tools.
 *
 * Every tool:
 *   1. Routes through [PathGuard] BEFORE touching the filesystem.
 *   2. Routes through [ConfirmationPolicy] — high-risk ops require UI confirmation.
 *   3. Executes via [SafFileManager] (SAF, never raw paths).
 *   4. Returns [ToolResult] with success/failure and metadata.
 *
 * The LLM is NEVER the final decision-maker on permissions.
 */

sealed class FileTool(
    val name: String,
    val description: String,
    val risk: ConfirmationPolicy.OperationRisk
) {
    abstract suspend fun execute(params: Map<String, Any>): ToolResult
}

class ListFilesTool(
    private val guard: PathGuard,
    private val saf: SafFileManager
) : FileTool(
    name = "list_files",
    description = "List entries in an allowed directory.",
    risk = ConfirmationPolicy.OperationRisk.SAFE
) {
    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val path      = params["path"] as? String ?: return ToolResult.error("Missing 'path'")
        val recursive = params["recursive"] as? Boolean ?: false

        val validation = guard.validate(path)
        if (validation is PathGuard.ValidationResult.Denied)
            return ToolResult.error("Access denied: ${validation.reason}")

        val result = saf.listFiles(path, recursive)
        return if (result.success)
            ToolResult.success(result.data ?: emptyList<String>())
        else
            ToolResult.error(result.error ?: "Unknown error")
    }
}

class ReadFileTool(
    private val guard: PathGuard,
    private val saf: SafFileManager
) : FileTool(
    name = "read_file",
    description = "Read a bounded UTF-8 text slice from a file.",
    risk = ConfirmationPolicy.OperationRisk.SAFE
) {
    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val path     = params["file_path"] as? String ?: return ToolResult.error("Missing 'file_path'")
        val maxChars = (params["max_chars"] as? Number)?.toInt()?.coerceIn(1, 100_000) ?: 50_000

        val validation = guard.validate(path)
        if (validation is PathGuard.ValidationResult.Denied)
            return ToolResult.error("Access denied: ${validation.reason}")

        val result = saf.readFile(path, maxChars)
        return if (result.success)
            ToolResult.success(result.data ?: "")
        else
            ToolResult.error(result.error ?: "Read failed")
    }
}

class WriteFileTool(
    private val guard: PathGuard,
    private val saf: SafFileManager
) : FileTool(
    name = "write_file",
    description = "Create or replace a UTF-8 text file.",
    risk = ConfirmationPolicy.OperationRisk.MEDIUM
) {
    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val path      = params["file_path"] as? String  ?: return ToolResult.error("Missing 'file_path'")
        val content   = params["content"]   as? String  ?: return ToolResult.error("Missing 'content'")
        val overwrite = params["overwrite"] as? Boolean ?: false

        val validation = guard.validate(path)
        if (validation is PathGuard.ValidationResult.Denied)
            return ToolResult.error("Access denied: ${validation.reason}")

        val result = saf.writeFile(path, content, overwrite)
        return if (result.success)
            ToolResult.success(result.data ?: "Written")
        else
            ToolResult.error(result.error ?: "Write failed")
    }
}

class CreateDirectoryTool(
    private val guard: PathGuard,
    private val saf: SafFileManager
) : FileTool(
    name = "create_directory",
    description = "Create an allowed directory (including parents).",
    risk = ConfirmationPolicy.OperationRisk.SAFE
) {
    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val path = params["path"] as? String ?: return ToolResult.error("Missing 'path'")
        val validation = guard.validate(path)
        if (validation is PathGuard.ValidationResult.Denied)
            return ToolResult.error("Access denied: ${validation.reason}")
        val result = saf.createDirectory(path)
        return if (result.success) ToolResult.success(result.data ?: "Created")
        else ToolResult.error(result.error ?: "Failed")
    }
}

class GetFileInfoTool(
    private val guard: PathGuard,
    private val saf: SafFileManager
) : FileTool(
    name = "get_file_info",
    description = "Return deterministic filesystem metadata for a file or directory.",
    risk = ConfirmationPolicy.OperationRisk.SAFE
) {
    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val path = params["file_path"] as? String ?: return ToolResult.error("Missing 'file_path'")
        val validation = guard.validate(path)
        if (validation is PathGuard.ValidationResult.Denied)
            return ToolResult.error("Access denied: ${validation.reason}")
        val result = saf.getFileInfo(path)
        return if (result.success) ToolResult.success(result.data ?: mapOf<String, Any>())
        else ToolResult.error(result.error ?: "Failed")
    }
}

class MoveFileTool(
    private val guard: PathGuard,
    private val saf: SafFileManager
) : FileTool(
    name = "move_file",
    description = "Move a file or directory to a new allowed location.",
    risk = ConfirmationPolicy.OperationRisk.HIGH
) {
    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val src = params["source"]      as? String ?: return ToolResult.error("Missing 'source'")
        val dst = params["destination"] as? String ?: return ToolResult.error("Missing 'destination'")
        for (path in listOf(src, dst)) {
            val v = guard.validate(path)
            if (v is PathGuard.ValidationResult.Denied)
                return ToolResult.error("Access denied: ${v.reason}")
        }
        val result = saf.moveFile(src, dst)
        return if (result.success) ToolResult.success(result.data ?: "Moved")
        else ToolResult.error(result.error ?: "Move failed")
    }
}

class CopyFileTool(
    private val guard: PathGuard,
    private val saf: SafFileManager
) : FileTool(
    name = "copy_file",
    description = "Copy a file to a new allowed location.",
    risk = ConfirmationPolicy.OperationRisk.MEDIUM
) {
    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val src = params["source"]      as? String ?: return ToolResult.error("Missing 'source'")
        val dst = params["destination"] as? String ?: return ToolResult.error("Missing 'destination'")
        for (path in listOf(src, dst)) {
            val v = guard.validate(path)
            if (v is PathGuard.ValidationResult.Denied)
                return ToolResult.error("Access denied: ${v.reason}")
        }
        val result = saf.copyFile(src, dst)
        return if (result.success) ToolResult.success(result.data ?: "Copied")
        else ToolResult.error(result.error ?: "Copy failed")
    }
}

class DeleteFileTool(
    private val guard: PathGuard,
    private val saf: SafFileManager
) : FileTool(
    name = "delete_file",
    description = "Delete an allowed file. Directories require recursive=true.",
    risk = ConfirmationPolicy.OperationRisk.CRITICAL
) {
    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val path      = params["file_path"] as? String  ?: return ToolResult.error("Missing 'file_path'")
        val recursive = params["recursive"] as? Boolean ?: false
        val validation = guard.validate(path)
        if (validation is PathGuard.ValidationResult.Denied)
            return ToolResult.error("Access denied: ${validation.reason}")
        val result = saf.deleteFile(path, recursive)
        return if (result.success) ToolResult.success(result.data ?: "Deleted")
        else ToolResult.error(result.error ?: "Delete failed")
    }
}

class RenameFileTool(
    private val guard: PathGuard,
    private val saf: SafFileManager
) : FileTool(
    name = "rename_file",
    description = "Rename an allowed file or directory within the same parent.",
    risk = ConfirmationPolicy.OperationRisk.MEDIUM
) {
    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val path = params["file_path"] as? String ?: return ToolResult.error("Missing 'file_path'")
        val newName = params["new_name"] as? String ?: return ToolResult.error("Missing 'new_name'")
        val validation = guard.validate(path)
        if (validation is PathGuard.ValidationResult.Denied) return ToolResult.error("Access denied: ${validation.reason}")
        val result = saf.renameFile(path, newName)
        return if (result.success) ToolResult.success(result.data ?: "Renamed")
        else ToolResult.error(result.error ?: "Rename failed")
    }
}


class SearchFilesTool(
    private val guard: PathGuard,
    private val saf: SafFileManager
) : FileTool(
    name = "search_files",
    description = "Search filenames recursively under an allowed directory.",
    risk = ConfirmationPolicy.OperationRisk.SAFE
) {
    override suspend fun execute(params: Map<String, Any>): ToolResult {
        val path    = params["path"]    as? String ?: return ToolResult.error("Missing 'path'")
        val pattern = params["pattern"] as? String ?: return ToolResult.error("Missing 'pattern'")
        val validation = guard.validate(path)
        if (validation is PathGuard.ValidationResult.Denied)
            return ToolResult.error("Access denied: ${validation.reason}")
        val result = saf.searchFiles(path, pattern)
        return if (result.success) ToolResult.success(result.data ?: emptyList<String>())
        else ToolResult.error(result.error ?: "Search failed")
    }
}
