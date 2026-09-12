package com.kayan.x.ui

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kayan.x.agent.AgentOrchestrator
import com.kayan.x.agent.AgentStep
import com.kayan.x.engine.InferenceConfig
import com.kayan.x.engine.LlamaEngine
import com.kayan.x.engine.ModelPreset
import com.kayan.x.engine.profiler.BenchmarkRunner
import com.kayan.x.engine.profiler.DeviceProfiler
import com.kayan.x.files.PersistedUriStore
import com.kayan.x.files.SafFileManager
import com.kayan.x.model.ModelInfo
import com.kayan.x.model.ModelManager
import com.kayan.x.safety.ConfirmationPolicy
import com.kayan.x.safety.PathGuard
import com.kayan.x.tools.ToolRegistry
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import timber.log.Timber

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val ctx = application.applicationContext
    private val nativeLibReady = LlamaEngine.isAvailable
    private val engine: LlamaEngine? = if (nativeLibReady) LlamaEngine() else null
    private val modelManager = ModelManager(ctx)
    private val uriStore = PersistedUriStore(ctx)
    private val saf = SafFileManager(ctx, uriStore)
    private val pathGuard = PathGuard(uriStore)
    private val toolRegistry = ToolRegistry(pathGuard, saf)
    private val profiler = DeviceProfiler(ctx)
    private val benchmarkRunner = engine?.let { BenchmarkRunner(ctx, it) }
    private var orchestrator: AgentOrchestrator? = null
    private var agentJob: Job? = null

    enum class ConversationMode { CHAT, AGENT }

    data class UiState(
        val registeredModels: List<ModelInfo> = emptyList(),
        val activeModel: ModelInfo? = null,
        val modelLoadState: ModelLoadState = ModelLoadState.NotLoaded,
        val currentPreset: ModelPreset = ModelPreset.SIZE_3B,
        val inferenceConfig: InferenceConfig? = null,
        val deviceProfile: DeviceProfiler.DeviceProfile? = null,
        val messages: List<ChatMessage> = emptyList(),
        val mode: ConversationMode = ConversationMode.CHAT,
        val agentRunning: Boolean = false,
        val streamingText: String = "",
        val streamingTokenCount: Int = 0,
        val streamingTokensPerSec: Float = 0f,
        val pendingConfirmation: PendingConfirmationUi? = null,
        val benchmarkResult: BenchmarkRunner.BenchmarkResult? = null,
        val benchmarkRunning: Boolean = false,
        val benchmarkError: String? = null,
        val registeredRoots: Map<String, String> = emptyMap(),
        val hshSignature: String? = null,
        val nativeLibError: String? = null,
        val modelNativeInfo: String? = null,
        val systemPrompt: String = "أنت Kayan، مساعد ذكاء اصطناعي محلي احترافي. أجب بدقة ووضوح وباللغة العربية ما لم يطلب المستخدم غير ذلك.",
        val hshReady: Boolean = false
    )

    sealed class ModelLoadState {
        data object NotLoaded : ModelLoadState()
        data class Loading(val modelName: String) : ModelLoadState()
        data class Loaded(val modelName: String, val loadMs: Long) : ModelLoadState()
        data class Failed(val error: String) : ModelLoadState()
    }

    data class ChatMessage(
        val id: Long = System.nanoTime(),
        val role: Role,
        val content: String,
        val agentSteps: List<AgentStep> = emptyList(),
        val tokensPerSec: Float? = null,
        val mode: ConversationMode? = null
    ) { enum class Role { USER, AGENT, SYSTEM } }

    data class PendingConfirmationUi(
        val toolName: String,
        val params: Map<String, Any>,
        val riskLevel: ConfirmationPolicy.OperationRisk,
        val onConfirm: () -> Unit,
        val onDeny: () -> Unit
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        if (!nativeLibReady) {
            _uiState.value = _uiState.value.copy(nativeLibError = LlamaEngine.loadError ?: "kayan_llama.so failed to load")
            Timber.e("Native library not available: ${LlamaEngine.loadError}")
        }
        viewModelScope.launch {
            val profile = profiler.profile()
            val config = profiler.recommendConfig(ModelPreset.SIZE_3B, profile)
            _uiState.value = _uiState.value.copy(
                deviceProfile = profile,
                inferenceConfig = config,
                registeredModels = modelManager.listModels(),
                registeredRoots = saf.getRegisteredRoots().mapValues { it.value.toString() },
                hshSignature = if (saf.getRegisteredRoots().containsKey("hsh")) "HSH" else null,
                hshReady = saf.getRegisteredRoots().containsKey("hsh")
            )
        }
    }

    fun onModelPicked(uri: Uri) {
        viewModelScope.launch {
            try {
                val info = modelManager.registerModel(uri)
                _uiState.value = _uiState.value.copy(registeredModels = modelManager.listModels())
                postSystem("تم تسجيل النموذج الحقيقي: ${info.displayName} (${info.sizeLabel})")
            } catch (e: Exception) { postSystem("فشل تسجيل النموذج: ${e.message}") }
        }
    }

    fun loadModel(info: ModelInfo) {
        val eng = engine ?: run { postSystem("المكتبة النووية غير متاحة: ${LlamaEngine.loadError}"); return }
        val profile = _uiState.value.deviceProfile ?: run { postSystem("لم تكتمل قراءة الجهاز بعد."); return }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(modelLoadState = ModelLoadState.Loading(info.displayName), benchmarkResult = null, benchmarkError = null)
            try {
                // Start with a conservative profile derived from the selected GGUF size,
                // then verify it against the real n_params reported by llama.cpp.
                val initialPreset = presetForFileSize(info.sizeBytes)
                var config = profiler.recommendConfig(initialPreset, profile)
                _uiState.value = _uiState.value.copy(currentPreset = initialPreset, inferenceConfig = config)
                val nativePath = modelManager.resolveNativePath(Uri.parse(info.uri))
                eng.loadModel(nativePath, config)

                val nativeInfo = eng.getModelInfo()
                val paramsB = parseParamsBillions(nativeInfo)
                val actualPreset = paramsB?.let {
                    when { it <= 2.0 -> ModelPreset.SIZE_1_5B; it <= 4.5 -> ModelPreset.SIZE_3B; else -> ModelPreset.SIZE_7B }
                } ?: initialPreset

                if (actualPreset != initialPreset) {
                    config = profiler.recommendConfig(actualPreset, profile)
                    eng.freeModel()
                    eng.loadModel(nativePath, config)
                }

                _uiState.value = _uiState.value.copy(
                    modelLoadState = ModelLoadState.Loaded(info.displayName, eng.lastLoadTimeMs),
                    activeModel = info,
                    currentPreset = actualPreset,
                    inferenceConfig = config,
                    modelNativeInfo = eng.getModelInfo(),
                    benchmarkResult = null
                )
                orchestrator = AgentOrchestrator(eng, toolRegistry)
                postSystem("تم تحميل ${info.displayName} فعليًا • ${formatParams(paramsB)} • الإعداد التلقائي: ${actualPreset.label}")
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(modelLoadState = ModelLoadState.Failed(e.message ?: "خطأ غير معروف"), modelNativeInfo = null)
                postSystem("فشل تحميل النموذج: ${e.message}")
                Timber.e(e, "loadModel failed")
            }
        }
    }

    private fun presetForFileSize(bytes: Long): ModelPreset = when {
        bytes <= 0L -> ModelPreset.SIZE_3B
        bytes < 1_300_000_000L -> ModelPreset.SIZE_1_5B
        bytes < 3_500_000_000L -> ModelPreset.SIZE_3B
        else -> ModelPreset.SIZE_7B
    }

    private fun parseParamsBillions(json: String): Float? = try {
        JSONObject(json).optLong("n_params", 0L).takeIf { it > 0 }?.div(1_000_000_000f)
    } catch (_: Exception) { null }

    private fun formatParams(paramsB: Float?): String = paramsB?.let { "${"%.2f".format(it)}B parameters" } ?: "معلومات النموذج غير متاحة"

    fun unloadModel() {
        viewModelScope.launch {
            engine?.freeModel(); orchestrator = null
            _uiState.value = _uiState.value.copy(modelLoadState = ModelLoadState.NotLoaded, activeModel = null)
            postSystem("تم تفريغ النموذج.")
        }
    }

    fun removeModel(uri: String) {
        modelManager.removeModel(uri)
        if (_uiState.value.activeModel?.uri == uri) unloadModel()
        _uiState.value = _uiState.value.copy(registeredModels = modelManager.listModels())
    }

    fun applyPreset(preset: ModelPreset) {
        val profile = _uiState.value.deviceProfile ?: return
        _uiState.value = _uiState.value.copy(currentPreset = preset, inferenceConfig = profiler.recommendConfig(preset, profile))
    }

    fun applyManualConfig(nCtx: Int? = null, nThreads: Int? = null, nBatch: Int? = null, nGpuLayers: Int? = null, temperature: Float? = null, maxTokens: Int? = null) {
        val base = _uiState.value.inferenceConfig ?: return
        _uiState.value = _uiState.value.copy(inferenceConfig = profiler.applyUserOverride(base, nCtx, nThreads, nBatch, nGpuLayers, temperature, maxTokens))
    }

    fun resetToAutoConfig() {
        val profile = _uiState.value.deviceProfile ?: return
        _uiState.value = _uiState.value.copy(inferenceConfig = profiler.recommendConfig(_uiState.value.currentPreset, profile))
    }

    fun setMode(mode: ConversationMode) {
        if (_uiState.value.agentRunning) return
        _uiState.value = _uiState.value.copy(mode = mode)
    }

    fun newConversation() {
        cancelAgent()
        _uiState.value = _uiState.value.copy(messages = emptyList(), streamingText = "", streamingTokenCount = 0)
    }

    fun conversationText(): String = buildString {
        _uiState.value.messages.forEach { msg ->
            append(when (msg.role) { ChatMessage.Role.USER -> "المستخدم"; ChatMessage.Role.AGENT -> "Kayan X"; ChatMessage.Role.SYSTEM -> "النظام" })
            append(": ").appendLine(msg.content)
        }
    }.trim()

    fun registerHshRoot(treeUri: Uri) {
        viewModelScope.launch {
            val selected = DocumentFile.fromTreeUri(ctx, treeUri)
            val actual = if (selected?.name.equals("HSH", ignoreCase = true)) {
                selected
            } else {
                selected?.findFile("HSH") ?: selected?.createDirectory("HSH")
            }
            val actualUri = actual?.uri ?: return@launch postSystem("تعذر العثور على مجلد HSH.")
            saf.registerRoot("hsh", actualUri)
            val signature = "HSH | Kayan Agent workspace | LOCAL-OFFLINE | HSH-ROOT"
            val result = saf.writeFile("hsh:/HSH.signature", signature, overwrite = true)
            _uiState.value = _uiState.value.copy(
                registeredRoots = saf.getRegisteredRoots().mapValues { it.value.toString() },
                hshSignature = if (result.success) "HSH • HSH.signature ✓" else "HSH (توقيع لم يُكتب: ${result.error})",
                hshReady = result.success
            )
            postSystem(if (result.success) "تم ربط مساحة العمل HSH وكتابة التوقيع الحقيقي." else "تم ربط HSH لكن تعذر كتابة التوقيع: ${result.error}")
        }
    }

    fun registerRoot(name: String, treeUri: Uri) = registerHshRoot(treeUri)

    fun sendMessage(text: String) {
        val eng = engine ?: run { postSystem("المكتبة النووية غير متاحة."); return }
        if (!eng.isModelLoaded) { postSystem("لم يُحمَّل أي نموذج بعد. اختر ملف GGUF."); return }
        if (_uiState.value.agentRunning || text.isBlank()) return
        appendMsg(ChatMessage(role = ChatMessage.Role.USER, content = text, mode = _uiState.value.mode))
        agentJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(agentRunning = true)
            try {
                if (_uiState.value.mode == ConversationMode.CHAT) runChat(eng, text) else runAgent()
            } catch (_: CancellationException) {
                if (_uiState.value.streamingText.isNotBlank()) appendMsg(ChatMessage(role = ChatMessage.Role.AGENT, content = _uiState.value.streamingText, tokensPerSec = _uiState.value.streamingTokensPerSec, mode = _uiState.value.mode))
                postSystem("تم إلغاء التوليد.")
            } catch (e: Exception) {
                postSystem("خطأ حقيقي من المحرك: ${e.message}"); Timber.e(e, "sendMessage failed")
            } finally {
                _uiState.value = _uiState.value.copy(agentRunning = false, pendingConfirmation = null, streamingText = "", streamingTokenCount = 0, streamingTokensPerSec = 0f)
            }
        }
    }

    private suspend fun runChat(eng: LlamaEngine, text: String) {
        val history = _uiState.value.messages
            .filter { it.role == ChatMessage.Role.USER || it.role == ChatMessage.Role.AGENT }
            .dropLast(1)
            .takeLast(10)
            .joinToString("\n") { msg ->
                val role = if (msg.role == ChatMessage.Role.USER) "user" else "assistant"
                "$role: ${msg.content.take(6000)}"
            }
        val modelName = _uiState.value.activeModel?.displayName.orEmpty().lowercase()
        val useQwenTemplate = modelName.contains("qwen") || modelName.contains("deepseek-r1")
        val prompt = if (useQwenTemplate) {
            buildString {
                append("<|im_start|>system\n")
                append(_uiState.value.systemPrompt).append("<|im_end|>\n")
                if (history.isNotBlank()) {
                    history.lines().forEach { line ->
                        val role = line.substringBefore(": ", "user")
                        val body = line.substringAfter(": ", "")
                        append("<|im_start|>$role\n$body<|im_end|>\n")
                    }
                }
                append("<|im_start|>user\n$text<|im_end|>\n<|im_start|>assistant\n")
            }
        } else {
            buildString {
                append(_uiState.value.systemPrompt).append("\n\n")
                if (history.isNotBlank()) append(history).append("\n")
                append("user: ").append(text).append("\nassistant:")
            }
        }
        val started = System.nanoTime()
        var count = 0
        val answer = eng.inferStreaming(prompt, onToken = { token ->
            count++
            val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
            val tps = if (elapsed > 0) count / elapsed else 0.0
            _uiState.value = _uiState.value.copy(streamingText = _uiState.value.streamingText + token, streamingTokenCount = count, streamingTokensPerSec = tps.toFloat())
            true
        })
        val elapsed = (System.nanoTime() - started) / 1_000_000_000.0
        val tps = if (elapsed > 0) count / elapsed else 0.0
        appendMsg(ChatMessage(role = ChatMessage.Role.AGENT, content = answer, tokensPerSec = tps.toFloat(), mode = ConversationMode.CHAT))
    }

    private suspend fun runAgent() {
        val orc = orchestrator ?: error("الوكيل غير مهيأ؛ أعد تحميل النموذج")
        val task = _uiState.value.messages.lastOrNull { it.role == ChatMessage.Role.USER }?.content ?: return
        val answer = orc.run(task) { pending ->
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                _uiState.value = _uiState.value.copy(pendingConfirmation = PendingConfirmationUi(pending.toolName, pending.params, pending.riskLevel,
                    onConfirm = { viewModelScope.launch { pending.resume(); _uiState.value = _uiState.value.copy(pendingConfirmation = null); cont.resume(Unit) { _ -> } } },
                    onDeny = { _uiState.value = _uiState.value.copy(pendingConfirmation = null); cont.cancel() }))
            }
        }
        appendMsg(ChatMessage(role = ChatMessage.Role.AGENT, content = answer, agentSteps = orc.agentState.value?.history ?: emptyList(), mode = ConversationMode.AGENT))
    }

    fun setSystemPrompt(prompt: String) {
        _uiState.value = _uiState.value.copy(systemPrompt = prompt.ifBlank { "أنت Kayan، مساعد ذكاء اصطناعي محلي احترافي. أجب بدقة ووضوح وباللغة العربية ما لم يطلب المستخدم غير ذلك." })
    }

    fun cancelAgent() { agentJob?.cancel(); engine?.cancelInference(); _uiState.value = _uiState.value.copy(agentRunning = false, pendingConfirmation = null) }

    fun runBenchmark() {
        val runner = benchmarkRunner ?: run { _uiState.value = _uiState.value.copy(benchmarkError = "المكتبة النووية غير متاحة"); return }
        if (engine?.isModelLoaded != true) { _uiState.value = _uiState.value.copy(benchmarkError = "حمّل نموذج GGUF أولًا"); return }
        if (_uiState.value.benchmarkRunning) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(benchmarkRunning = true, benchmarkError = null)
            try { _uiState.value = _uiState.value.copy(benchmarkResult = runner.run()) }
            catch (e: Exception) { _uiState.value = _uiState.value.copy(benchmarkError = e.message ?: "فشل الاختبار") }
            finally { _uiState.value = _uiState.value.copy(benchmarkRunning = false) }
        }
    }

    private fun appendMsg(msg: ChatMessage) { _uiState.value = _uiState.value.copy(messages = _uiState.value.messages + msg) }
    private fun postSystem(text: String) { appendMsg(ChatMessage(role = ChatMessage.Role.SYSTEM, content = text)) }
    override fun onCleared() { super.onCleared(); viewModelScope.launch { engine?.freeModel() } }
}
