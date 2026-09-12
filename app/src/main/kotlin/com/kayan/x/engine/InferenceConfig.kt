package com.kayan.x.engine

/**
 * Complete inference configuration.
 *
 * IMPORTANT: n_gpu_layers is NEVER hardcoded to 32.
 * It is determined by [DeviceProfiler] and can be overridden by the user.
 */
data class InferenceConfig(
    /** Context window size in tokens. */
    val nCtx: Int = 4096,
    /** Number of CPU threads for inference. */
    val nThreads: Int = 4,
    /** Batch size for prompt processing. */
    val nBatch: Int = 512,
    /**
     * Number of transformer layers to offload to GPU.
     * -1 = offload ALL layers (use only when you know the model layer count)
     *  0 = CPU-only
     *  N = offload exactly N layers
     *
     * Default: determined by [DeviceProfiler.recommendConfig], never a magic constant.
     */
    val nGpuLayers: Int = 0,
    /** Sampling temperature. */
    val temperature: Float = 0.7f,
    /** Max new tokens per inference call. */
    val maxTokens: Int = 512,
    /**
     * Whether this config was set by the user (true) or auto-detected (false).
     * Used in the Settings UI to display an "Auto" badge.
     */
    val isUserOverride: Boolean = false
) {
    init {
        require(nCtx in 128..131072)   { "nCtx must be in [128, 131072]" }
        require(nThreads in 1..32)     { "nThreads must be in [1, 32]" }
        require(nBatch in 32..4096)    { "nBatch must be in [32, 4096]" }
        require(nGpuLayers >= -1)      { "nGpuLayers must be >= -1" }
        require(temperature in 0f..2f) { "temperature must be in [0.0, 2.0]" }
        require(maxTokens in 1..8192)  { "maxTokens must be in [1, 8192]" }
    }
}

/**
 * Model size presets.
 * These configure CONTEXT and BATCH sizes appropriate for each model class.
 * GPU layers are still determined by [DeviceProfiler] — not hardcoded here.
 */
enum class ModelPreset(
    val label: String,
    val nCtx: Int,
    val nBatch: Int,
    val maxTokens: Int,
    /** Advisory model parameter count for profiler heuristics. */
    val paramsBillions: Float
) {
    SIZE_1_5B(
        label       = "1.5B",
        nCtx        = 4096,
        nBatch      = 512,
        maxTokens   = 512,
        paramsBillions = 1.5f
    ),
    SIZE_3B(
        label       = "3B",
        nCtx        = 4096,
        nBatch      = 512,
        maxTokens   = 512,
        paramsBillions = 3.0f
    ),
    SIZE_7B(
        label       = "7B",
        nCtx        = 4096,
        nBatch      = 256,   // smaller batch — higher memory pressure
        maxTokens   = 256,
        paramsBillions = 7.0f
    );

    companion object {
        fun fromLabel(label: String): ModelPreset =
            entries.firstOrNull { it.label == label } ?: SIZE_3B
    }
}
