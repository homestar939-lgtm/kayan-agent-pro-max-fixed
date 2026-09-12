package com.kayan.x

import android.content.Context
import com.kayan.x.files.PersistedUriStore
import com.kayan.x.safety.PathGuard
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PathGuardTest {

    private lateinit var guard: PathGuard
    private lateinit var store: PersistedUriStore

    @Before
    fun setup() {
        store = mockk()
        // Simulate "downloads" and "workspace" as registered roots
        every { store.hasRoot("downloads")  } returns true
        every { store.hasRoot("workspace")  } returns true
        every { store.hasRoot("unregistered") } returns false
        guard = PathGuard(store)
    }

    @Test fun `valid downloads path is accepted`() {
        val result = guard.validate("downloads:/Reports/Q1.pdf")
        assertIs<PathGuard.ValidationResult.Ok>(result)
    }

    @Test fun `valid workspace path is accepted`() {
        val result = guard.validate("workspace:/notes.txt")
        assertIs<PathGuard.ValidationResult.Ok>(result)
    }

    @Test fun `unregistered root is denied`() {
        val result = guard.validate("unregistered:/file.txt")
        assertIs<PathGuard.ValidationResult.Denied>(result)
        assertTrue((result as PathGuard.ValidationResult.Denied).reason.contains("not registered"))
    }

    @Test fun `path traversal is denied`() {
        val result = guard.validate("downloads:/../../etc/passwd")
        assertIs<PathGuard.ValidationResult.Denied>(result)
        assertTrue((result as PathGuard.ValidationResult.Denied).reason.contains("traversal"))
    }

    @Test fun `blocked extension apk is denied`() {
        val result = guard.validate("downloads:/malware.apk")
        assertIs<PathGuard.ValidationResult.Denied>(result)
        assertTrue((result as PathGuard.ValidationResult.Denied).reason.contains("blocked"))
    }

    @Test fun `blocked extension so is denied`() {
        val result = guard.validate("workspace:/lib.so")
        assertIs<PathGuard.ValidationResult.Denied>(result)
    }

    @Test fun `blocked sqlite extension is denied`() {
        val result = guard.validate("downloads:/data.sqlite3")
        assertIs<PathGuard.ValidationResult.Denied>(result)
    }

    @Test fun `missing colon format is denied`() {
        val result = guard.validate("downloads/file.txt")
        assertIs<PathGuard.ValidationResult.Denied>(result)
        assertTrue((result as PathGuard.ValidationResult.Denied).reason.contains("Invalid"))
    }

    @Test fun `single dot in path is allowed`() {
        val result = guard.validate("workspace:/./file.txt")
        assertIs<PathGuard.ValidationResult.Ok>(result)
    }

    @Test fun `gguf extension is allowed`() {
        val result = guard.validate("downloads:/models/qwen3B.gguf")
        assertIs<PathGuard.ValidationResult.Ok>(result)
    }

    // Kotlin 1.9 helper (JUnit 4 doesn't have assertIs natively)
    private inline fun <reified T> assertIs(value: Any?) {
        assertTrue("Expected ${T::class.simpleName} but got ${value?.javaClass?.simpleName}",
                   value is T)
    }
}
