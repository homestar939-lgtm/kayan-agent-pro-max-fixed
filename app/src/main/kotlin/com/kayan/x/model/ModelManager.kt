package com.kayan.x.model

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * GGUF Model Manager
 *
 * ══════════════════════════════════════════════════════════════════
 * ARCHITECTURAL CONTRACT:
 *
 * The GGUF model is NEVER bundled inside the APK.
 * Reasons:
 *   - A 3B Q4_K_M model ≈ 2 GB  → APK limit is 4 GB but store limit is 150 MB
 *   - A 7B Q4_K_M model ≈ 4 GB  → physically impossible in APK
 *   - Including even a tiny 1.5B model (~1 GB) would make the APK unusable
 *
 * Actual APK size breakdown:
 *   - Base Kotlin/Compose code + resources : ~5–8 MB
 *   - kayan_llama.so (arm64-v8a, stripped) : ~10–20 MB
 *   - Total per-ABI APK                    : ~15–30 MB
 *   - GGUF model (user-supplied, external) : NOT in APK
 *
 * The user selects a GGUF file via ACTION_OPEN_DOCUMENT (SAF).
 * We store the URI with persisted permission so re-selection is not needed.
 * We resolve the URI to an absolute path for llama.cpp (which needs a C path).
 * ══════════════════════════════════════════════════════════════════
 */
class ModelManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("model_manager", Context.MODE_PRIVATE)
    private val gson  = Gson()

    // ─────────────────────────────────────────────────────────────────────────
    // Persisted model registry
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Register a model URI returned by SAF file picker.
     * Takes a persisted permission grant and stores model metadata.
     */
    suspend fun registerModel(uri: Uri, displayName: String? = null): ModelInfo =
        withContext(Dispatchers.IO) {
            // Persist permission across reboots
            context.contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )

            val name = displayName ?: resolveDisplayName(uri)
            val sizeBytes = resolveFileSize(uri)
            val info = ModelInfo(
                uri         = uri.toString(),
                displayName = name,
                sizeBytes   = sizeBytes,
                addedAt     = System.currentTimeMillis()
            )
            saveModelInfo(info)
            Timber.i("Registered model: $name (${sizeBytes / 1_048_576L} MB)")
            info
        }

    fun listModels(): List<ModelInfo> {
        val json = prefs.getString(KEY_MODELS, "[]") ?: "[]"
        return try {
            val type = object : TypeToken<List<ModelInfo>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun removeModel(uri: String) {
        val updated = listModels().filter { it.uri != uri }
        prefs.edit().putString(KEY_MODELS, gson.toJson(updated)).apply()
        // Release persisted permission
        try {
            val u = Uri.parse(uri)
            context.contentResolver.releasePersistableUriPermission(
                u, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: Exception) { /* already revoked */ }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // URI → native path resolution
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolve a content:// URI to an absolute filesystem path that llama.cpp
     * can open via standard C fopen().
     *
     * Strategy:
     *   1. Try to extract the real path from DocumentsContract (works for
     *      primary storage documents on most devices).
     *   2. If that fails, copy the file to the app's private cache dir and
     *      return that path (works universally, costs disk space).
     *
     * Note: The copy path is only used as a last resort. On most modern
     * Android devices with primary storage documents, path extraction works.
     */
    suspend fun resolveNativePath(uri: Uri): String = withContext(Dispatchers.IO) {
        // Attempt 1: Direct path extraction from primary storage
        val direct = tryExtractPath(uri)
        if (direct != null && File(direct).canRead()) {
            Timber.d("Resolved model path (direct): $direct")
            return@withContext direct
        }

        // Attempt 2: Copy to private cache (always works, but takes time + space)
        val cached = copyToCache(uri)
        Timber.d("Resolved model path (cached copy): $cached")
        cached
    }

    /**
     * Attempt to extract a real /storage/... path from a content:// URI.
     * Returns null if extraction is not possible on this device/URI.
     */
    private fun tryExtractPath(uri: Uri): String? {
        return try {
            if (!DocumentsContract.isDocumentUri(context, uri)) return null

            val docId = DocumentsContract.getDocumentId(uri)
            // Primary storage document URIs typically have IDs like "primary:DCIM/..."
            if (docId.startsWith("primary:")) {
                val relativePath = docId.removePrefix("primary:")
                val path = "/storage/emulated/0/$relativePath"
                if (File(path).exists()) path else null
            } else {
                null  // SD card or other provider — fall through to copy
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Copy the model from a content URI into the app's private cache.
     * Creates a symlink-named file based on URI hash to avoid re-copying.
     */
    private suspend fun copyToCache(uri: Uri): String = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "models").also { it.mkdirs() }
        val hash     = uri.toString().hashCode().toUInt().toString(16)
        val name     = resolveDisplayName(uri).ifEmpty { "model_$hash.gguf" }
        val dest     = File(cacheDir, name)

        if (dest.exists()) return@withContext dest.absolutePath   // already cached

        Timber.i("Copying model to cache: ${dest.absolutePath}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Cannot open input stream for URI: $uri")

        dest.absolutePath
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun resolveDisplayName(uri: Uri): String {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: uri.lastPathSegment ?: "unknown.gguf"
        } catch (_: Exception) {
            uri.lastPathSegment ?: "unknown.gguf"
        }
    }

    private fun resolveFileSize(uri: Uri): Long {
        return try {
            context.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.SIZE),
                null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun saveModelInfo(info: ModelInfo) {
        val current = listModels().filter { it.uri != info.uri }.toMutableList()
        current.add(0, info)   // most recent first
        prefs.edit().putString(KEY_MODELS, gson.toJson(current)).apply()
    }

    companion object {
        private const val KEY_MODELS = "registered_models"
    }
}
