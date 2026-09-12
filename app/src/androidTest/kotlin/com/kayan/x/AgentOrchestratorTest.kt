package com.kayan.x

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kayan.x.files.PersistedUriStore
import com.kayan.x.files.SafFileManager
import com.kayan.x.safety.PathGuard
import com.kayan.x.tools.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for tool integration via ToolRegistry + PathGuard.
 *
 * Note: Full AgentOrchestrator tests require a loaded model.
 * These tests verify the Tool → PathGuard → SafFileManager chain.
 * LlamaEngine is NOT loaded here — we test the plumbing only.
 */
@RunWith(AndroidJUnit4::class)
class AgentOrchestratorTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    // Register a "workspace" root pointing to the app's private files dir
    private fun buildRegistry(): ToolRegistry {
        val store = PersistedUriStore(ctx)
        // Use the app's internal files dir as "workspace" root for tests
        val workspaceUri = android.net.Uri.fromFile(ctx.filesDir)
        store.saveRoot("workspace", workspaceUri)

        val saf   = SafFileManager(ctx, store)
        val guard = PathGuard(store)
        return ToolRegistry(guard, saf)
    }

    @Test fun `tool registry contains all 9 tools`() {
        val registry = buildRegistry()
        val expected = setOf(
            "list_files", "read_file", "write_file", "create_directory",
            "get_file_info", "move_file", "copy_file", "delete_file", "search_files"
        )
        assertEquals(expected, registry.toolNames())
    }

    @Test fun `path traversal is blocked at tool layer`() {
        val registry = buildRegistry()
        val result = kotlinx.coroutines.runBlocking {
            registry.execute("list_files", mapOf("path" to "workspace:/../../etc"))
        }
        assertFalse("Path traversal should be denied", result.success)
        assertTrue(result.error?.contains("traversal") == true)
    }

    @Test fun `blocked apk extension is denied`() {
        val registry = buildRegistry()
        val result = kotlinx.coroutines.runBlocking {
            registry.execute("read_file", mapOf("file_path" to "workspace:/bad.apk"))
        }
        assertFalse(result.success)
        assertTrue(result.error?.contains("blocked") == true)
    }

    @Test fun `delete requires explicit path`() {
        val registry = buildRegistry()
        val result = kotlinx.coroutines.runBlocking {
            registry.execute("delete_file", mapOf<String, Any>())   // missing file_path
        }
        assertFalse(result.success)
        assertNotNull(result.error)
    }

    @Test fun `unknown tool returns error`() {
        val registry = buildRegistry()
        val result = kotlinx.coroutines.runBlocking {
            registry.execute("fly_to_mars", mapOf("destination" to "Mars"))
        }
        assertFalse(result.success)
        assertTrue(result.error?.contains("Unknown tool") == true)
    }

    @Test fun `result includes latency metadata`() {
        val registry = buildRegistry()
        val result = kotlinx.coroutines.runBlocking {
            registry.execute("list_files", mapOf("path" to "workspace:/"))
        }
        // latency_ms is injected by ToolRegistry even on failure
        assertTrue(result.metadata.containsKey("latency_ms"))
    }
}
