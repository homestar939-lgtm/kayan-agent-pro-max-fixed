package com.kayan.x.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Kotlin façade over the native llama.cpp JNI layer.
 *
 * ── 8-function handle-based API ─────────────────────────────────────────────
 *  All operations are bound to an opaque [nativeHandle] (jlong pointer to a
 *  KayanContext C++ struct). This allows:
 *    - Multiple isolated model lifetimes within one process
 *    - Thread-safe cancel without killing the native thread
 *    - Precise resource accounting (no global state leaks)
 *
 * ── JNI contract (must match llama_jni.cpp exactly) ────────────────────────
 *   initBackend()                                           → Unit
 *   loadModel(path,nCtx,nThreads,nBatch,nGpuLayers)        → Long (handle, 0=failure)
 *   freeModel(handle)                                       → Unit
 *   cancelInference(handle)                                 → Unit
 *   infer(handle,prompt,maxTokens,temperature,callback?)    → String?
 *   getModelInfo(handle)                                    → String (JSON)
 *   benchmarkTokensPerSec(handle,prompt,nTokens)            → Float
 *   detectGpuVendor()   [static @JvmStatic]                → String
 *
 * ── Threading ───────────────────────────────────────────────────────────────
 *   All suspend funs dispatch on [Dispatchers.Default].
 *   cancelInference() is the only function safe to call from any thread.
 *
 * ── APK size contract ───────────────────────────────────────────────────────
 *   GGUF model is NEVER embedded in the APK.
 *   [modelPath] is always an absolute path resolved from a SAF URI.
 */
class LlamaEngine {

    @Volatile private var nativeHandle: Long = 0L

    var lastLoadTimeMs: Long = 0L
        private set

    private var activeConfig: InferenceConfig? = null
    private val inferenceMutex = Mutex()

    val isModelLoaded: Boolean get() = nativeHandle != 0L

    fun currentConfigSnapshot(): String = activeConfig?.toString() ?: "no model"

    // ── Lifecycle ────────────────────────────────────────────────────────────

    init {
        // initBackend() must run once before any model is loaded.
        // Called here (on whatever thread constructs LlamaEngine) — it is fast
        // and idempotent in llama.cpp.
        if (isAvailable) {
            try {
                initBackend()
                Timber.i("llama backend initialised")
            } catch (e: Throwable) {
                Timber.e(e, "initBackend() failed")
            }
        }
    }

    /**
     * Load a GGUF model from [modelPath].
     * Throws [IllegalStateException] on native failure.
     */
    suspend fun loadModel(modelPath: String, config: InferenceConfig) {
        withContext(Dispatchers.Default) {
            if (isModelLoaded) freeModel()

            Timber.i("loadModel | path=$modelPath | $config")
            val t0 = System.currentTimeMillis()
            val handle = loadModel(
                modelPath,
                config.nCtx,
                config.nThreads,
                config.nBatch,
                config.nGpuLayers
            )
            lastLoadTimeMs = System.currentTimeMillis() - t0

            check(handle != 0L) {
                "Native loadModel() returned null handle — see logcat KayanLlama"
            }
            nativeHandle = handle
            activeConfig = config
            Timber.i("Model loaded in ${lastLoadTimeMs}ms (handle=0x${handle.toString(16)})")
        }
    }

    /** Release all native resources. Safe to call when no model is loaded. */
    suspend fun freeModel() {
        withContext(Dispatchers.Default) {
            val h = nativeHandle
            if (h != 0L) {
                nativeHandle = 0L
                activeConfig = null
                freeModel(h)
                Timber.i("Model freed")
            }
        }
    }

    // ── Inference ────────────────────────────────────────────────────────────

    /** Full blocking inference — returns complete generated string. */
    suspend fun infer(
        prompt: String,
        maxTokens: Int  = activeConfig?.maxTokens  ?: 512,
        temperature: Float = activeConfig?.temperature ?: 0.7f
    ): String = inferenceMutex.withLock {
        withContext(Dispatchers.Default) {
            checkHandle()
            infer(nativeHandle, prompt, maxTokens, temperature, null)
                ?: error("Native infer() returned null")
        }
    }

    /**
     * Streaming inference — [onToken] is called for each decoded piece.
     * Return false from [onToken] to stop generation early.
     */
    suspend fun inferStreaming(
        prompt: String,
        maxTokens: Int     = activeConfig?.maxTokens  ?: 512,
        temperature: Float = activeConfig?.temperature ?: 0.7f,
        onToken: (String) -> Boolean
    ): String = inferenceMutex.withLock {
        withContext(Dispatchers.Default) {
            checkHandle()
            val cb = object : TokenCallback {
                override fun onToken(token: String): Boolean = onToken(token)
            }
            infer(nativeHandle, prompt, maxTokens, temperature, cb)
                ?: error("Native infer() returned null")
        }
    }

    /**
     * Thread-safe cancel — can be called from the main thread at any time.
     * Sets an atomic flag; the native generate loop checks it each token.
     */
    fun cancelInference() {
        if (isModelLoaded) cancelInference(nativeHandle)
    }

    // ── Diagnostics ──────────────────────────────────────────────────────────

    suspend fun benchmarkTokensPerSec(prompt: String, nTokens: Int = 64): Float =
        inferenceMutex.withLock {
            withContext(Dispatchers.Default) {
                checkHandle()
                benchmarkTokensPerSec(nativeHandle, prompt, nTokens)
            }
        }

    suspend fun getModelInfo(): String = withContext(Dispatchers.Default) {
        if (!isModelLoaded) return@withContext "{}"
        getModelInfo(nativeHandle)
    }

    // ── Private ──────────────────────────────────────────────────────────────

    private fun checkHandle() = check(isModelLoaded) {
        "No model loaded — call loadModel() first"
    }

    // ── JNI declarations (8 functions — names must match llama_jni.cpp) ──────

    /** Callback interface passed to [infer] for streaming. Implemented as anonymous object. */
    interface TokenCallback {
        /** Return true to continue, false to stop generation. */
        fun onToken(token: String): Boolean
    }

    private external fun initBackend()
    private external fun loadModel(
        path: String, nCtx: Int, nThreads: Int, nBatch: Int, nGpuLayers: Int
    ): Long
    private external fun freeModel(handle: Long)
    private external fun cancelInference(handle: Long)
    private external fun infer(
        handle: Long, prompt: String, maxTokens: Int,
        temperature: Float, callback: TokenCallback?
    ): String?
    private external fun getModelInfo(handle: Long): String
    private external fun benchmarkTokensPerSec(handle: Long, prompt: String, nTokens: Int): Float

    companion object {

        private var _libraryLoaded = false
        private var _libraryError: String? = null

        /** True if `libkayan_llama.so` was found and loaded by the dynamic linker. */
        val isAvailable: Boolean get() = _libraryLoaded
        /** Non-null error message when [isAvailable] is false. */
        val loadError: String?  get() = _libraryError

        init {
            try {
                System.loadLibrary("kayan_llama")
                _libraryLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                // Do NOT rethrow — MainViewModel checks isAvailable and
                // surfaces a graceful error banner instead of crashing.
                _libraryError = e.message
                Timber.e("libkayan_llama.so failed to load: ${e.message}")
            }
        }

        /**
         * Query GPU vendor WITHOUT EGL (no GLESv2 dependency).
         * Reads Android system properties + /proc/cpuinfo as fallback.
         * Called by [DeviceProfiler] to determine n_gpu_layers tier.
         *
         * Note: This is a static (@JvmStatic) JNI call — the C++ side
         * receives `jclass` not `jobject` as the second parameter.
         */
        @JvmStatic
        external fun detectGpuVendor(): String
    }
}
