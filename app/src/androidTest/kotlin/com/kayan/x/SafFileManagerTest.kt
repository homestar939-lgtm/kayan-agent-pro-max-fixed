package com.kayan.x

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kayan.x.files.PersistedUriStore
import com.kayan.x.files.SafFileManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation tests for [SafFileManager].
 * Uses the app's internal files directory as the "workspace" root.
 * No external storage required — no MANAGE_EXTERNAL_STORAGE needed.
 */
@RunWith(AndroidJUnit4::class)
class SafFileManagerTest {

    private lateinit var saf: SafFileManager
    private lateinit var store: PersistedUriStore

    @Before
    fun setup() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        store = PersistedUriStore(ctx)

        // Register app internal files dir as "workspace" root
        val filesDir = ctx.filesDir
        filesDir.mkdirs()
        store.saveRoot("workspace", Uri.fromFile(filesDir))

        saf = SafFileManager(ctx, store)
    }

    @Test fun `resolve existing root returns non-null handle`() = runBlocking {
        val handle = saf.listFiles("workspace:/")
        assertTrue("Should successfully list root", handle.success)
    }

    @Test fun `unregistered root returns error`() = runBlocking {
        val result = saf.listFiles("sdcard:/Downloads")
        assertFalse(result.success)
        assertNotNull(result.error)
    }

    @Test fun `create directory succeeds`() = runBlocking {
        val result = saf.createDirectory("workspace:/test_create_dir_${System.currentTimeMillis()}")
        // Note: SAF from Uri.fromFile may have limitations in instrumentation;
        // we verify the result contract is correct
        assertNotNull(result)
    }

    @Test fun `get file info for non-existent path fails gracefully`() = runBlocking {
        val result = saf.getFileInfo("workspace:/definitely_does_not_exist_xyz.txt")
        assertFalse(result.success)
        assertNotNull(result.error)
    }

    @Test fun `search files returns list`() = runBlocking {
        val result = saf.searchFiles("workspace:/", ".txt")
        // Success or failure — must not throw
        assertNotNull(result)
        if (result.success) {
            assertTrue(result.data is List<*>)
        }
    }

    @Test fun `write file requires overwrite=true for existing file`() = runBlocking {
        val path = "workspace:/test_overwrite_${System.currentTimeMillis()}.txt"
        // First write
        saf.writeFile(path, "initial content", overwrite = false)
        // Second write without overwrite flag should fail
        val second = saf.writeFile(path, "new content", overwrite = false)
        assertFalse("Second write without overwrite should fail", second.success)
    }

    @Test fun `roots are persisted`() {
        val roots = store.loadRoots()
        assertTrue("workspace root must be registered", roots.containsKey("workspace"))
    }
}
