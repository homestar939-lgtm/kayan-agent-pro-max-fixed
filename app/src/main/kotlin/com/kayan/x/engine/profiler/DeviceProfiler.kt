package com.kayan.x.engine.profiler

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.kayan.x.engine.InferenceConfig
import com.kayan.x.engine.LlamaEngine
import com.kayan.x.engine.ModelPreset
import timber.log.Timber
import java.io.File
import kotlin.math.min

/**
 * Auto Backend / Performance Profiler
 *
 * Replaces the architectural anti-pattern of hardcoding `n_gpu_layers = 32`.
 *
 * Strategy:
 *  1. Query real device characteristics (RAM, CPU cores, CPU freq, GPU vendor)
 *  2. Apply tier-based heuristics to compute n_gpu_layers
 *  3. Return a full [InferenceConfig] with [isUserOverride = false]
 *  4. User can call [applyUserOverride] to replace any field — that sets
 *     [isUserOverride = true] so the UI can show "Manual" mode
 */
class DeviceProfiler(private val context: Context) {

    // ── GPU vendor enum ───────────────────────────────────────────────────────

    enum class GpuVendor {
        ADRENO,   // Qualcomm — best OpenCL/GPU perf on Android
        MALI,     // ARM — decent but conservative VRAM sharing
        POWERVR,  // Imagination — rare on modern Android
        APPLE,    // Should never appear on Android, but guard anyway
        UNKNOWN;  // CPU-only fallback

        companion object {
            fun parse(raw: String): GpuVendor = when {
                raw.contains("Adreno",  ignoreCase = true) -> ADRENO
                raw.contains("Mali",    ignoreCase = true) -> MALI
                raw.contains("PowerVR", ignoreCase = true) -> POWERVR
                raw.contains("Apple",   ignoreCase = true) -> APPLE
                else                                        -> UNKNOWN
            }
        }
    }

    // ── Device profile snapshot ───────────────────────────────────────────────

    data class DeviceProfile(
        val totalRamMb: Long,
        val availRamMb: Long,
        val cpuCores: Int,
        val cpuMaxFreqKHz: Long,
        val gpuVendor: GpuVendor,
        val gpuVendorRaw: String,
        val androidVersion: Int,
        val supportedAbis: List<String>
    ) {
        val isArm64: Boolean get() = supportedAbis.any { it == "arm64-v8a" }

        override fun toString(): String =
            "DeviceProfile(ram=${totalRamMb}MB avail=${availRamMb}MB " +
            "cores=$cpuCores freq=${cpuMaxFreqKHz}kHz gpu=$gpuVendor " +
            "abi=${supportedAbis.firstOrNull()} android=$androidVersion)"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Collect device profile. Safe to call on any thread; does no I/O except
     * reading /sys pseudo-files and querying ActivityManager.
     */
    fun profile(): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }

        val totalRamMb = memInfo.totalMem  / 1_048_576L
        val availRamMb = memInfo.availMem  / 1_048_576L
        val cpuCores   = Runtime.getRuntime().availableProcessors()
        val cpuFreqKHz = readCpuMaxFreqKHz()
        // Guard: detectGpuVendor() is a native @JvmStatic call.
        // If the library failed to load, calling it throws UnsatisfiedLinkError.
        val gpuRaw = if (LlamaEngine.isAvailable) {
            try { LlamaEngine.detectGpuVendor() }
            catch (e: Throwable) { Timber.w("detectGpuVendor failed: ${e.message}"); "Unknown" }
        } else {
            "Unknown"
        }
        val gpuVendor  = GpuVendor.parse(gpuRaw)
        val abis       = Build.SUPPORTED_ABIS.toList()

        val profile = DeviceProfile(
            totalRamMb     = totalRamMb,
            availRamMb     = availRamMb,
            cpuCores       = cpuCores,
            cpuMaxFreqKHz  = cpuFreqKHz,
            gpuVendor      = gpuVendor,
            gpuVendorRaw   = gpuRaw,
            androidVersion = Build.VERSION.SDK_INT,
            supportedAbis  = abis
        )
        Timber.d("DeviceProfile: $profile")
        return profile
    }

    /**
     * Compute a recommended [InferenceConfig] for [preset] given [profile].
     *
     * The n_gpu_layers value is the key output — calculated from RAM tier and
     * GPU vendor, NOT a hardcoded constant.
     */
    fun recommendConfig(preset: ModelPreset, profile: DeviceProfile): InferenceConfig {
        val gpuLayers  = recommendGpuLayers(profile, preset)
        val cpuThreads = recommendThreads(profile)

        val config = InferenceConfig(
            nCtx         = preset.nCtx,
            nThreads     = cpuThreads,
            nBatch       = preset.nBatch,
            nGpuLayers   = gpuLayers,
            temperature  = 0.7f,
            maxTokens    = preset.maxTokens,
            isUserOverride = false
        )
        Timber.i("Auto config for ${preset.label}: threads=$cpuThreads gpu_layers=$gpuLayers")
        return config
    }

    /**
     * Apply a user-specified override to any field of an existing config.
     * Sets [isUserOverride = true] so the UI can show "Manual" mode.
     */
    fun applyUserOverride(
        base: InferenceConfig,
        nCtx: Int?       = null,
        nThreads: Int?   = null,
        nBatch: Int?     = null,
        nGpuLayers: Int? = null,
        temperature: Float? = null,
        maxTokens: Int?  = null
    ): InferenceConfig = base.copy(
        nCtx         = nCtx       ?: base.nCtx,
        nThreads     = nThreads   ?: base.nThreads,
        nBatch       = nBatch     ?: base.nBatch,
        nGpuLayers   = nGpuLayers ?: base.nGpuLayers,
        temperature  = temperature ?: base.temperature,
        maxTokens    = maxTokens  ?: base.maxTokens,
        isUserOverride = true
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Private: n_gpu_layers heuristic
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Determine n_gpu_layers based on available RAM and GPU vendor.
     *
     * The heuristic model:
     *  - Android GPU drivers share system RAM; there is no dedicated VRAM.
     *  - Each transformer layer in a 3B Q4 model ≈ 20-30 MB.
     *  - We need headroom for: OS + app + KV cache + model weights.
     *  - Conservative rule: use at most 60% of avail RAM for GPU-mapped layers.
     *
     * Tier table (avail RAM → max layers per GPU vendor):
     * ┌─────────────┬────────┬──────┬─────────┬─────────┐
     * │ Avail RAM   │ Adreno │ Mali │ PowerVR │ Unknown │
     * ├─────────────┼────────┼──────┼─────────┼─────────┤
     * │ < 2 GB      │   0    │   0  │    0    │    0    │
     * │ 2–3 GB      │   8    │   4  │    4    │    0    │
     * │ 3–4 GB      │  16    │   8  │    6    │    0    │
     * │ 4–5 GB      │  24    │  16  │   12    │    4    │
     * │ 5–6 GB      │  32    │  20  │   16    │    8    │
     * │ > 6 GB      │  35    │  28  │   20    │   12    │
     * └─────────────┴────────┴──────┴─────────┴─────────┘
     */
    private fun recommendGpuLayers(profile: DeviceProfile, preset: ModelPreset): Int {
        // Larger models need more layers but also more RAM per layer — reduce ceiling
        val scaleFactor = when (preset) {
            ModelPreset.SIZE_1_5B -> 1.0f
            ModelPreset.SIZE_3B   -> 0.85f
            ModelPreset.SIZE_7B   -> 0.60f
        }

        val raw = when {
            profile.availRamMb < 2_048L -> 0
            profile.availRamMb < 3_072L -> when (profile.gpuVendor) {
                GpuVendor.ADRENO  -> 8
                GpuVendor.MALI    -> 4
                GpuVendor.POWERVR -> 4
                else              -> 0
            }
            profile.availRamMb < 4_096L -> when (profile.gpuVendor) {
                GpuVendor.ADRENO  -> 16
                GpuVendor.MALI    -> 8
                GpuVendor.POWERVR -> 6
                else              -> 0
            }
            profile.availRamMb < 5_120L -> when (profile.gpuVendor) {
                GpuVendor.ADRENO  -> 24
                GpuVendor.MALI    -> 16
                GpuVendor.POWERVR -> 12
                else              -> 4
            }
            profile.availRamMb < 6_144L -> when (profile.gpuVendor) {
                GpuVendor.ADRENO  -> 32
                GpuVendor.MALI    -> 20
                GpuVendor.POWERVR -> 16
                else              -> 8
            }
            else -> when (profile.gpuVendor) {
                GpuVendor.ADRENO  -> 35
                GpuVendor.MALI    -> 28
                GpuVendor.POWERVR -> 20
                else              -> 12
            }
        }

        val result = (raw * scaleFactor).toInt()
        Timber.d("GPU layers: raw=$raw scale=$scaleFactor → $result (avail=${profile.availRamMb}MB, ${profile.gpuVendor})")
        return result
    }

    /**
     * Recommend thread count: leave 1-2 cores free for the OS and UI rendering.
     */
    private fun recommendThreads(profile: DeviceProfile): Int {
        val cores = profile.cpuCores
        return when {
            cores <= 2  -> 1
            cores <= 4  -> cores - 1
            cores <= 8  -> cores - 2
            else        -> min(cores - 2, 8)  // cap at 8; beyond that yields diminishing returns
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private: CPU frequency
    // ─────────────────────────────────────────────────────────────────────────

    private fun readCpuMaxFreqKHz(): Long {
        return try {
            // Most Android devices expose this sysfs path
            File("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")
                .readText().trim().toLong()
        } catch (_: Exception) {
            0L
        }
    }
}
