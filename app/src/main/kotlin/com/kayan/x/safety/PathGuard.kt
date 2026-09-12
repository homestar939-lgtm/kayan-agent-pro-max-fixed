package com.kayan.x.safety

import com.kayan.x.files.PersistedUriStore

/**
 * Path guard for virtual path validation.
 *
 * The Agent only ever sees virtual paths of the form "root:/relative/path".
 * PathGuard validates that:
 *   1. The root segment refers to a registered root.
 *   2. The relative path does not escape the root (path traversal prevention).
 *   3. Blocked extensions are not accessed.
 *
 * PathGuard does NOT own file I/O — that is [SafFileManager]'s job.
 * PathGuard is the POLICY enforcer; SafFileManager is the EXECUTOR.
 *
 * The LLM is NEVER the decision-maker for path access.
 * Android Security Layer (via this class + SafFileManager) owns the final say.
 */
class PathGuard(private val uriStore: PersistedUriStore) {

    // Extensions that will never be opened, regardless of what the Agent asks.
    private val blockedExtensions = setOf(
        ".apk", ".dex", ".so", ".obb", ".oat", ".odex",  // executables
        ".key", ".pem", ".p12", ".pfx",                    // certificates
        ".db", ".sqlite", ".sqlite3"                       // raw databases (agent uses them via abstraction)
    )

    /**
     * Validate a virtual path before any file operation.
     * Returns [ValidationResult.Ok] or [ValidationResult.Denied].
     */
    fun validate(virtualPath: String): ValidationResult {
        val (root, relative) = splitPath(virtualPath)
            ?: return ValidationResult.Denied("Invalid virtual path format: $virtualPath")

        // 1. Root must be registered
        if (!uriStore.hasRoot(root))
            return ValidationResult.Denied("Root '$root' is not registered. User must grant access first.")

        // 2. Path traversal prevention
        val segments = relative.split("/")
        var depth = 0
        for (seg in segments) {
            when (seg) {
                ".."  -> depth--
                "."   -> { /* no-op */ }
                ""    -> { /* trailing slash */ }
                else  -> depth++
            }
            if (depth < 0)
                return ValidationResult.Denied("Path traversal detected in: $virtualPath")
        }

        // 3. Blocked extensions
        val ext = relative.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        if (ext.lowercase() in blockedExtensions)
            return ValidationResult.Denied("Access to '$ext' files is blocked for security.")

        return ValidationResult.Ok
    }

    private fun splitPath(path: String): Pair<String, String>? {
        val idx = path.indexOf(':')
        if (idx < 1) return null
        return Pair(path.substring(0, idx), path.substring(idx + 1))
    }

    sealed class ValidationResult {
        object Ok : ValidationResult()
        data class Denied(val reason: String) : ValidationResult()
    }
}
