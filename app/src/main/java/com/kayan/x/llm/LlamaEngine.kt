package com.kayan.x.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

class LlamaEngine {

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var isNativeLoaded = false

    sealed class State {
        object Idle : State()
        object Loading : State()
        data class Loaded(val modelName: String) : State()
        data class Generating(val partial: String) : State()
        data class Error(val message: String) : State()
    }

    companion object {
        init {
            try {
                System.loadLibrary("kayan_llama")
            } catch (e: UnsatisfiedLinkError) {
                Timber.e(e, "Failed to load kayan_llama")
            }
        }
    }

    // JNI
    private external fun nativeInit(modelPath: String): Boolean
    private external fun nativeGenerate(prompt: String, maxTokens: Int, temperature: Float, topP: Float): String
    private external fun nativeFree()
    private external fun nativeGetLastError(): String
    private external fun nativeIsLoaded(): Boolean

    suspend fun loadModel(modelFile: File): Boolean = withContext(Dispatchers.IO) {
        if (!modelFile.exists()) {
            _state.value = State.Error("Model not found: ${modelFile.absolutePath}")
            return@withContext false
        }
        _state.value = State.Loading
        try {
            val success = nativeInit(modelFile.absolutePath)
            if (success) {
                isNativeLoaded = true
                _state.value = State.Loaded(modelFile.name)
                Timber.i("Model loaded: ${modelFile.name}")
                true
            } else {
                val err = nativeGetLastError()
                _state.value = State.Error(err)
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Load failed")
            _state.value = State.Error(e.message ?: "Unknown error")
            false
        }
    }

    fun generate(
        prompt: String,
        systemPrompt: String = "You are Kayan, a helpful AI assistant.",
        maxTokens: Int = 512,
        temperature: Float = 0.7f,
        topP: Float = 0.9f
    ): Flow<String> = flow {
        if (!nativeIsLoaded()) {
            emit("Error: Model not loaded. Load a GGUF model first.")
            return@flow
        }

        val fullPrompt = buildPrompt(systemPrompt, prompt)
        Timber.i("Generating: ${fullPrompt.take(100)}...")

        try {
            // Streaming simulation - llama.cpp b4600 generates full text then we stream it
            val result = withContext(Dispatchers.IO) {
                nativeGenerate(fullPrompt, maxTokens, temperature, topP)
            }

            // Simulate streaming for UI
            var buffer = ""
            for (c in result) {
                buffer += c
                if (c == ' ' || c == '\n' || buffer.length % 4 == 0) {
                    emit(buffer)
                    kotlinx.coroutines.delay(20)
                }
            }
            if (buffer.isNotEmpty() && buffer != result) {
                emit(result)
            } else if (result.isNotEmpty() && buffer.isEmpty()) {
                emit(result)
            }

            _state.value = (_state.value as? State.Loaded)?.let { it } ?: State.Idle

        } catch (e: Exception) {
            Timber.e(e, "Generation failed")
            _state.value = State.Error(e.message ?: "Generation failed")
            emit("Error: ${e.message}")
        }
    }.flowOn(Dispatchers.Default)

    private fun buildPrompt(system: String, user: String): String {
        // ChatML format for best compatibility
        return "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
    }

    fun unload() {
        if (isNativeLoaded) {
            nativeFree()
            isNativeLoaded = false
            _state.value = State.Idle
        }
    }

    fun isLoaded(): Boolean = try { nativeIsLoaded() } catch (e: Exception) { false }
}
