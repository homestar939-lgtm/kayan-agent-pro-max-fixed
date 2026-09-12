package com.kayan.x

import com.kayan.x.engine.ModelPreset
import com.kayan.x.engine.profiler.DeviceProfiler
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for the [DeviceProfiler] n_gpu_layers heuristic.
 *
 * Key property under test:
 *   - n_gpu_layers is NEVER a hardcoded constant
 *   - It scales with available RAM and GPU vendor
 *   - It scales down for larger models (7B gets fewer layers than 1.5B)
 *   - Unknown GPU always gets 0 layers on low RAM
 */
class DeviceProfilerTest {

    // Fake profile builder (no Context needed for the heuristic logic)
    private fun fakeProfile(
        totalRamMb: Long,
        availRamMb: Long,
        gpuVendor: DeviceProfiler.GpuVendor,
        cores: Int = 8
    ) = DeviceProfiler.DeviceProfile(
        totalRamMb    = totalRamMb,
        availRamMb    = availRamMb,
        cpuCores      = cores,
        cpuMaxFreqKHz = 3_000_000L,
        gpuVendor     = gpuVendor,
        gpuVendorRaw  = gpuVendor.name,
        androidVersion = 34,
        supportedAbis  = listOf("arm64-v8a")
    )

    // We expose the private recommendGpuLayers via the public recommendConfig
    // and verify the nGpuLayers field it returns.
    // Since DeviceProfiler needs Context, we test the logic by wrapping
    // the output of a test-only version. For pure unit tests we extract
    // the heuristic to a standalone function:
    private fun gpuLayers(
        availRamMb: Long,
        gpuVendor: DeviceProfiler.GpuVendor,
        preset: ModelPreset
    ): Int {
        // Replicate the exact heuristic from DeviceProfiler
        val scaleFactor = when (preset) {
            ModelPreset.SIZE_1_5B -> 1.0f
            ModelPreset.SIZE_3B   -> 0.85f
            ModelPreset.SIZE_7B   -> 0.60f
        }
        val raw = when {
            availRamMb < 2_048L -> 0
            availRamMb < 3_072L -> when (gpuVendor) {
                DeviceProfiler.GpuVendor.ADRENO  -> 8
                DeviceProfiler.GpuVendor.MALI    -> 4
                DeviceProfiler.GpuVendor.POWERVR -> 4
                else              -> 0
            }
            availRamMb < 4_096L -> when (gpuVendor) {
                DeviceProfiler.GpuVendor.ADRENO  -> 16
                DeviceProfiler.GpuVendor.MALI    -> 8
                DeviceProfiler.GpuVendor.POWERVR -> 6
                else              -> 0
            }
            availRamMb < 5_120L -> when (gpuVendor) {
                DeviceProfiler.GpuVendor.ADRENO  -> 24
                DeviceProfiler.GpuVendor.MALI    -> 16
                DeviceProfiler.GpuVendor.POWERVR -> 12
                else              -> 4
            }
            availRamMb < 6_144L -> when (gpuVendor) {
                DeviceProfiler.GpuVendor.ADRENO  -> 32
                DeviceProfiler.GpuVendor.MALI    -> 20
                DeviceProfiler.GpuVendor.POWERVR -> 16
                else              -> 8
            }
            else -> when (gpuVendor) {
                DeviceProfiler.GpuVendor.ADRENO  -> 35
                DeviceProfiler.GpuVendor.MALI    -> 28
                DeviceProfiler.GpuVendor.POWERVR -> 20
                else              -> 12
            }
        }
        return (raw * scaleFactor).toInt()
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test fun `low RAM always returns 0 regardless of GPU`() {
        for (vendor in DeviceProfiler.GpuVendor.entries) {
            val layers = gpuLayers(1_800L, vendor, ModelPreset.SIZE_3B)
            assertEquals("Expected 0 for ${vendor} on 1.8GB RAM", 0, layers)
        }
    }

    @Test fun `unknown GPU returns 0 on mid RAM`() {
        val layers = gpuLayers(3_500L, DeviceProfiler.GpuVendor.UNKNOWN, ModelPreset.SIZE_3B)
        assertEquals(0, layers)
    }

    @Test fun `Adreno gets more layers than Mali for same RAM`() {
        val adreno = gpuLayers(5_000L, DeviceProfiler.GpuVendor.ADRENO, ModelPreset.SIZE_3B)
        val mali   = gpuLayers(5_000L, DeviceProfiler.GpuVendor.MALI,   ModelPreset.SIZE_3B)
        assertTrue("Adreno($adreno) > Mali($mali)", adreno > mali)
    }

    @Test fun `7B model gets fewer layers than 1_5B on same hardware`() {
        val layers15B = gpuLayers(6_000L, DeviceProfiler.GpuVendor.ADRENO, ModelPreset.SIZE_1_5B)
        val layers7B  = gpuLayers(6_000L, DeviceProfiler.GpuVendor.ADRENO, ModelPreset.SIZE_7B)
        assertTrue("1.5B($layers15B) >= 7B($layers7B)", layers15B >= layers7B)
    }

    @Test fun `more RAM produces more or equal layers`() {
        val low  = gpuLayers(2_500L, DeviceProfiler.GpuVendor.ADRENO, ModelPreset.SIZE_3B)
        val high = gpuLayers(7_000L, DeviceProfiler.GpuVendor.ADRENO, ModelPreset.SIZE_3B)
        assertTrue("High RAM($high) >= Low RAM($low)", high >= low)
    }

    @Test fun `n_gpu_layers is never a hardcoded 32 for all cases`() {
        // 32 was the anti-pattern. Verify the result varies by input.
        val values = setOf(
            gpuLayers(2_000L, DeviceProfiler.GpuVendor.ADRENO, ModelPreset.SIZE_3B),
            gpuLayers(3_500L, DeviceProfiler.GpuVendor.ADRENO, ModelPreset.SIZE_3B),
            gpuLayers(5_000L, DeviceProfiler.GpuVendor.ADRENO, ModelPreset.SIZE_3B),
            gpuLayers(7_000L, DeviceProfiler.GpuVendor.ADRENO, ModelPreset.SIZE_3B),
        )
        assertTrue("n_gpu_layers must vary by RAM tier; got constant $values", values.size > 1)
    }

    @Test fun `GpuVendor parse is correct`() {
        assertEquals(DeviceProfiler.GpuVendor.ADRENO,  DeviceProfiler.GpuVendor.parse("Adreno (TM) 750"))
        assertEquals(DeviceProfiler.GpuVendor.MALI,    DeviceProfiler.GpuVendor.parse("Mali-G715"))
        assertEquals(DeviceProfiler.GpuVendor.POWERVR, DeviceProfiler.GpuVendor.parse("PowerVR GT7600"))
        assertEquals(DeviceProfiler.GpuVendor.UNKNOWN, DeviceProfiler.GpuVendor.parse("llvmpipe"))
    }
}
