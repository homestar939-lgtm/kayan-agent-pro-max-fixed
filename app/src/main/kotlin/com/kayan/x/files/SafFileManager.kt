package com.kayan.x.files

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Storage Access Framework file manager.
 *
 * All operations go through [DocumentHandle] (an opaque URI-based identifier),
 * never through raw /sdcard paths. The Agent always uses handles, not strings.
 *
 * Persisted URI permissions are stored in [PersistedUriStore] so the user
 * does not need to re-select their Downloads folder on every app launch.
 */
class SafFileManager(
    private val context: Context,
    private val uriStore: PersistedUriStore
) {

    // ─────────────────────────────────────────────────────────────────────────
    // Root registration (called after user picks folder via ACTION_OPEN_DOCUMENT_TREE)
    // ─────────────────────────────────────────────────────────────────────────

    fun registerRoot(name: String, treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        uriStore.saveRoot(name, treeUri)
        Timber.i("Registered root '$name' → $treeUri")
    }

    fun getRegisteredRoots(): Map<String, Uri> = uriStore.loadRoots()

    // ─────────────────────────────────────────────────────────────────────────
    // Handle resolution
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Resolve a virtual path like "downloads:/Reports/Q1.pdf" into a [DocumentHandle].
     * Returns null if the root is not registered or the path does not exist.
     */
    suspend fun resolve(virtualPath: String): DocumentHandle? = withContext(Dispatchers.IO) {
        val (root, relative) = splitVirtualPath(virtualPath) ?: return@withContext null
        val rootUri = uriStore.loadRoots()[root] ?: run {
            Timber.w("Root '$root' not registered")
            return@withContext null
        }

        var doc = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext null
        val segments = relative.split("/").filter { it.isNotEmpty() }
        for (seg in segments) {
            doc = doc.findFile(seg) ?: return@withContext null
        }
        DocumentHandle(doc.uri, doc.name ?: "", doc.isDirectory, doc.isFile)
    }

    /** Resolve or create intermediate directories. Returns handle for the final path. */
    suspend fun resolveOrCreate(virtualPath: String): DocumentHandle? = withContext(Dispatchers.IO) {
        val (root, relative) = splitVirtualPath(virtualPath) ?: return@withContext null
        val rootUri = uriStore.loadRoots()[root] ?: return@withContext null

        var doc = DocumentFile.fromTreeUri(context, rootUri) ?: return@withContext null
        val segments = relative.split("/").filter { it.isNotEmpty() }
        for (seg in segments) {
            doc = doc.findFile(seg)
                ?: doc.createDirectory(seg)
                ?: return@withContext null
        }
        DocumentHandle(doc.uri, doc.name ?: "", doc.isDirectory, doc.isFile)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File operations — all return [FileOpResult]
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun listFiles(virtualPath: String, recursive: Boolean = false): FileOpResult =
        withContext(Dispatchers.IO) {
            val handle = resolve(virtualPath)
                ?: return@withContext FileOpResult.error("Path not found: $virtualPath")
            if (!handle.isDirectory)
                return@withContext FileOpResult.error("Not a directory: $virtualPath")

            val doc = DocumentFile.fromSingleUri(context, handle.uri)
                ?: DocumentFile.fromTreeUri(context, handle.uri)
                ?: return@withContext FileOpResult.error("Cannot open directory")

            val entries = mutableListOf<String>()
            collectEntries(doc, "", recursive, entries)
            FileOpResult.success(entries)
        }

    suspend fun readFile(virtualPath: String, maxChars: Int = 50_000): FileOpResult =
        withContext(Dispatchers.IO) {
            val handle = resolve(virtualPath)
                ?: return@withContext FileOpResult.error("File not found: $virtualPath")
            if (!handle.isFile)
                return@withContext FileOpResult.error("Not a file: $virtualPath")
            try {
                val content = context.contentResolver.openInputStream(handle.uri)?.use { input ->
                    input.reader(Charsets.UTF_8).readText().take(maxChars)
                } ?: return@withContext FileOpResult.error("Cannot open stream")
                FileOpResult.success(content)
            } catch (e: Exception) {
                FileOpResult.error("Read failed: ${e.message}")
            }
        }

    suspend fun writeFile(virtualPath: String, content: String, overwrite: Boolean = false): FileOpResult =
        withContext(Dispatchers.IO) {
            val (root, relative) = splitVirtualPath(virtualPath)
                ?: return@withContext FileOpResult.error("Invalid virtual path")
            val rootUri = uriStore.loadRoots()[root]
                ?: return@withContext FileOpResult.error("Root '$root' not registered")

            var parentDoc = DocumentFile.fromTreeUri(context, rootUri)
                ?: return@withContext FileOpResult.error("Cannot open root")

            val segments = relative.split("/").filter { it.isNotEmpty() }
            val fileName = segments.last()
            val dirSegments = segments.dropLast(1)

            for (seg in dirSegments) {
                parentDoc = parentDoc.findFile(seg)
                    ?: parentDoc.createDirectory(seg)
                    ?: return@withContext FileOpResult.error("Cannot create directory: $seg")
            }

            val existing = parentDoc.findFile(fileName)
            if (existing != null && !overwrite) {
                return@withContext FileOpResult.error("File exists; set overwrite=true")
            }
            existing?.delete()

            val mime = "text/plain"
            val newFile = parentDoc.createFile(mime, fileName)
                ?: return@withContext FileOpResult.error("Cannot create file")

            try {
                context.contentResolver.openOutputStream(newFile.uri)?.use { out ->
                    out.writer(Charsets.UTF_8).use { it.write(content) }
                }
                FileOpResult.success("Written: $virtualPath (${content.length} chars)")
            } catch (e: Exception) {
                FileOpResult.error("Write failed: ${e.message}")
            }
        }

    suspend fun createDirectory(virtualPath: String): FileOpResult =
        withContext(Dispatchers.IO) {
            resolveOrCreate(virtualPath)
                ?.let { FileOpResult.success("Directory ready: $virtualPath") }
                ?: FileOpResult.error("Cannot create directory: $virtualPath")
        }

    suspend fun deleteFile(virtualPath: String, recursive: Boolean = false): FileOpResult =
        withContext(Dispatchers.IO) {
            val handle = resolve(virtualPath)
                ?: return@withContext FileOpResult.error("Not found: $virtualPath")
            if (handle.isDirectory && !recursive)
                return@withContext FileOpResult.error("Directory deletion requires recursive=true")
            val doc = DocumentFile.fromSingleUri(context, handle.uri)
            val deleted = doc?.delete() ?: false
            if (deleted) FileOpResult.success("Deleted: $virtualPath")
            else FileOpResult.error("Delete failed: $virtualPath")
        }

    suspend fun moveFile(src: String, dst: String): FileOpResult =
        withContext(Dispatchers.IO) {
            val srcHandle = resolve(src)
                ?: return@withContext FileOpResult.error("Source not found: $src")
            val (dstRoot, dstRelative) = splitVirtualPath(dst)
                ?: return@withContext FileOpResult.error("Invalid destination path")
            val dstRootUri = uriStore.loadRoots()[dstRoot]
                ?: return@withContext FileOpResult.error("Destination root '$dstRoot' not registered")

            return@withContext try {
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    val srcDoc = DocumentFile.fromSingleUri(context, srcHandle.uri)
                        ?: return@withContext FileOpResult.error("Cannot open source document")
                    val srcParentUri = getParentUri(srcHandle.uri)
                        ?: return@withContext FileOpResult.error("Cannot determine source parent")
                    val dstParentDoc = resolveOrCreate("$dstRoot:${dstRelative.substringBeforeLast('/')}")
                        ?: return@withContext FileOpResult.error("Cannot resolve destination parent")

                    DocumentsContract.moveDocument(
                        context.contentResolver,
                        srcHandle.uri,
                        srcParentUri,
                        dstParentDoc.uri
                    )
                    FileOpResult.success("Moved: $src → $dst")
                } else {
                    // Fallback: copy then delete
                    val content = readFile(src).data as? String
                        ?: return@withContext FileOpResult.error("Cannot read source for copy")
                    writeFile(dst, content, overwrite = true)
                    deleteFile(src)
                    FileOpResult.success("Moved (copy+delete): $src → $dst")
                }
            } catch (e: Exception) {
                FileOpResult.error("Move failed: ${e.message}")
            }
        }

    suspend fun copyFile(src: String, dst: String): FileOpResult =
        withContext(Dispatchers.IO) {
            val srcHandle = resolve(src)
                ?: return@withContext FileOpResult.error("Source not found: $src")
            return@withContext try {
                val bytes = context.contentResolver.openInputStream(srcHandle.uri)
                    ?.use { it.readBytes() }
                    ?: return@withContext FileOpResult.error("Cannot read source")

                val (dstRoot, _) = splitVirtualPath(dst)
                    ?: return@withContext FileOpResult.error("Invalid destination path")
                val dstRootUri = uriStore.loadRoots()[dstRoot]
                    ?: return@withContext FileOpResult.error("Destination root not registered")

                val dstParentPath = dst.substringBeforeLast('/')
                val dstName       = dst.substringAfterLast('/')
                val dstParent     = resolveOrCreate(dstParentPath)
                    ?: return@withContext FileOpResult.error("Cannot create destination parent")

                val dstParentDoc  = DocumentFile.fromTreeUri(context, dstRootUri)
                    ?: return@withContext FileOpResult.error("Cannot open destination tree")

                val dstFile = DocumentFile.fromSingleUri(context, dstParent.uri)
                    ?.createFile("application/octet-stream", dstName)
                    ?: return@withContext FileOpResult.error("Cannot create destination file")

                context.contentResolver.openOutputStream(dstFile.uri)?.use { out ->
                    out.write(bytes)
                }
                FileOpResult.success("Copied: $src → $dst (${bytes.size} bytes)")
            } catch (e: Exception) {
                FileOpResult.error("Copy failed: ${e.message}")
            }
        }

    suspend fun renameFile(virtualPath: String, newName: String): FileOpResult =
        withContext(Dispatchers.IO) {
            val handle = resolve(virtualPath)
                ?: return@withContext FileOpResult.error("Not found: $virtualPath")
            val safeName = newName.trim().replace("/", "_").replace("\\", "_")
            if (safeName.isBlank() || safeName == "." || safeName == "..")
                return@withContext FileOpResult.error("Invalid new name")
            return@withContext try {
                val renamed = DocumentsContract.renameDocument(context.contentResolver, handle.uri, safeName)
                if (renamed != null) FileOpResult.success("Renamed: $virtualPath → $safeName")
                else FileOpResult.error("Rename failed: $virtualPath")
            } catch (e: Exception) {
                FileOpResult.error("Rename failed: ${e.message}")
            }
        }

    suspend fun getFileInfo(virtualPath: String): FileOpResult =
        withContext(Dispatchers.IO) {
            val handle = resolve(virtualPath)
                ?: return@withContext FileOpResult.error("Not found: $virtualPath")
            val doc = DocumentFile.fromSingleUri(context, handle.uri)
            FileOpResult.success(mapOf(
                "uri"       to handle.uri.toString(),
                "name"      to handle.name,
                "is_file"   to handle.isFile,
                "is_dir"    to handle.isDirectory,
                "size"      to (doc?.length() ?: 0L),
                "modified"  to (doc?.lastModified() ?: 0L)
            ))
        }

    suspend fun searchFiles(virtualPath: String, pattern: String): FileOpResult =
        withContext(Dispatchers.IO) {
            val handle = resolve(virtualPath)
                ?: return@withContext FileOpResult.error("Directory not found: $virtualPath")
            if (!handle.isDirectory)
                return@withContext FileOpResult.error("Not a directory: $virtualPath")

            val doc = DocumentFile.fromSingleUri(context, handle.uri)
                ?: return@withContext FileOpResult.error("Cannot open directory")

            val matches = mutableListOf<String>()
            searchRecursive(doc, pattern.lowercase(), matches, limit = 500)
            FileOpResult.success(matches)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun splitVirtualPath(path: String): Pair<String, String>? {
        val idx = path.indexOf(':')
        if (idx < 1) return null
        return Pair(path.substring(0, idx), path.substring(idx + 1))
    }

    private fun collectEntries(
        doc: DocumentFile, prefix: String, recursive: Boolean, out: MutableList<String>
    ) {
        doc.listFiles().forEach { child ->
            val name = "$prefix${child.name ?: "?"}"
            out.add(name)
            if (recursive && child.isDirectory) collectEntries(child, "$name/", true, out)
        }
    }

    private fun searchRecursive(
        doc: DocumentFile, pattern: String, out: MutableList<String>, limit: Int
    ) {
        if (out.size >= limit) return
        doc.listFiles().forEach { child ->
            if (out.size >= limit) return
            if (child.name?.lowercase()?.contains(pattern) == true) {
                out.add(child.uri.toString())
            }
            if (child.isDirectory) searchRecursive(child, pattern, out, limit)
        }
    }

    private fun getParentUri(childUri: Uri): Uri? {
        return try {
            val docId = DocumentsContract.getDocumentId(childUri)
            val treeUri = DocumentsContract.buildTreeDocumentUri(
                childUri.authority, docId
            )
            val parentId = docId.substringBeforeLast('/')
            DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
        } catch (_: Exception) {
            null
        }
    }
}
