package com.kayan.x.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.kayan.x.engine.ModelPreset
import com.kayan.x.engine.profiler.DeviceProfiler
import com.kayan.x.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel) {
    val state by vm.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الإعدادات والأداء") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier       = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Device Profile card
            item {
                state.deviceProfile?.let { DeviceProfileCard(it) }
            }

            item {
                SectionCard(title = "🧠 شخصية Kayan / System Prompt") {
                    var prompt by remember(state.systemPrompt) { mutableStateOf(state.systemPrompt) }
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6,
                        label = { Text("تعليمات النموذج") }
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.setSystemPrompt(prompt) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Save, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("حفظ التعليمات")
                    }
                }
            }

            item {
                state.modelNativeInfo?.let { info ->
                    SectionCard(title = "🔬 معلومات النموذج المحمّل") {
                        Text(info, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                        Text("الإعدادات أعلاه حُسبت بعد قراءة metadata الفعلية للنموذج.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // Model preset selector
            item {
                PresetSelector(
                    currentPreset = state.currentPreset,
                    onPreset      = { vm.applyPreset(it) }
                )
            }

            // Inference config (auto or manual)
            item {
                state.inferenceConfig?.let { config ->
                    InferenceConfigCard(
                        config       = config,
                        onReset      = { vm.resetToAutoConfig() },
                        onApplyManual = { ctx, threads, batch, gpu, temp, maxTok ->
                            vm.applyManualConfig(ctx, threads, batch, gpu, temp, maxTok)
                        }
                    )
                }
            }

            // Benchmark panel
            item {
                BenchmarkPanel(
                    result      = state.benchmarkResult,
                    isRunning   = state.benchmarkRunning,
                    error       = state.benchmarkError,
                    modelLoaded = state.modelLoadState is MainViewModel.ModelLoadState.Loaded,
                    onRun       = { vm.runBenchmark() }
                )
            }
            item {
                SectionCard(title = "مساحة الوكيل HSH") {
                    Text(
                        state.hshSignature ?: "اختر مجلد HSH من شاشة النموذج لتمكين أدوات الملفات.",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.hshSignature != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DeviceProfileCard(profile: DeviceProfiler.DeviceProfile) {
    SectionCard(title = "🖥️ ملف الجهاز (Auto-detected)") {
        ProfileRow("الذاكرة الكلية",      "${profile.totalRamMb} MB")
        ProfileRow("الذاكرة المتاحة",     "${profile.availRamMb} MB")
        ProfileRow("أنوية المعالج",        "${profile.cpuCores}")
        ProfileRow("تردد المعالج الأقصى", if (profile.cpuMaxFreqKHz > 0)
            "${"%.1f".format(profile.cpuMaxFreqKHz / 1_000_000f)} GHz" else "غير معروف")
        ProfileRow("وحدة المعالجة الرسومية", "${profile.gpuVendor} (${profile.gpuVendorRaw})")
        ProfileRow("معمارية الجهاز",      profile.supportedAbis.firstOrNull() ?: "—")
        ProfileRow("إصدار Android",       "API ${profile.androidVersion}")
    }
}

@Composable
private fun PresetSelector(currentPreset: ModelPreset, onPreset: (ModelPreset) -> Unit) {
    SectionCard(title = "📐 حجم النموذج (Preset)") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelPreset.entries.forEach { preset ->
                FilterChip(
                    selected = currentPreset == preset,
                    onClick  = { onPreset(preset) },
                    label    = { Text(preset.label) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "الاختيار يضبط nCtx و nBatch و maxTokens تلقائيًا.\n" +
            "n_gpu_layers يظل محددًا بواسطة المحلل التلقائي.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun InferenceConfigCard(
    config: com.kayan.x.engine.InferenceConfig,
    onReset: () -> Unit,
    onApplyManual: (Int?, Int?, Int?, Int?, Float?, Int?) -> Unit
) {
    var editMode by remember { mutableStateOf(false) }

    // Local edit state
    var nCtx     by remember(config) { mutableStateOf(config.nCtx.toString()) }
    var nThreads by remember(config) { mutableStateOf(config.nThreads.toString()) }
    var nBatch   by remember(config) { mutableStateOf(config.nBatch.toString()) }
    var nGpu     by remember(config) { mutableStateOf(config.nGpuLayers.toString()) }
    var temp     by remember(config) { mutableStateOf(config.temperature.toString()) }
    var maxTok   by remember(config) { mutableStateOf(config.maxTokens.toString()) }

    SectionCard(
        title = "⚙️ إعدادات الاستدلال${if (config.isUserOverride) " (يدوي)" else " (تلقائي ✓)"}"
    ) {
        // Current config display
        ConfigRow("n_ctx",        config.nCtx.toString(),        "حجم نافذة السياق")
        ConfigRow("n_threads",    config.nThreads.toString(),    "أنوية المعالج المستخدمة")
        ConfigRow("n_batch",      config.nBatch.toString(),      "حجم الدفعة")
        ConfigRow("n_gpu_layers", config.nGpuLayers.toString(),  "طبقات GPU (محسوبة تلقائيًا)")
        ConfigRow("temperature",  config.temperature.toString(), "درجة الحرارة")
        ConfigRow("max_tokens",   config.maxTokens.toString(),   "أقصى عدد رموز")

        Spacer(Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (config.isUserOverride) {
                OutlinedButton(onClick = onReset, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("إعادة ضبط تلقائي")
                }
            }
            Button(
                onClick  = { editMode = !editMode },
                modifier = Modifier.weight(1f)
            ) {
                Text(if (editMode) "إلغاء" else "تعديل يدوي")
            }
        }

        // Manual override fields
        if (editMode) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("⚠️ التعديل اليدوي يلغي الكشف التلقائي للجهاز.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))

            IntField("n_ctx",        nCtx)     { nCtx = it }
            IntField("n_threads",    nThreads) { nThreads = it }
            IntField("n_batch",      nBatch)   { nBatch = it }
            IntField("n_gpu_layers", nGpu,
                     hint = "0 = CPU فقط، -1 = كل الطبقات") { nGpu = it }
            IntField("max_tokens",   maxTok)   { maxTok = it }
            FloatField("temperature", temp)    { temp = it }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    onApplyManual(
                        nCtx.toIntOrNull(), nThreads.toIntOrNull(),
                        nBatch.toIntOrNull(), nGpu.toIntOrNull(),
                        temp.toFloatOrNull(), maxTok.toIntOrNull()
                    )
                    editMode = false
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("تطبيق الإعدادات اليدوية") }
        }
    }
}

@Composable
private fun BenchmarkPanel(
    result: com.kayan.x.engine.profiler.BenchmarkRunner.BenchmarkResult?,
    isRunning: Boolean,
    error: String?,
    modelLoaded: Boolean,
    onRun: () -> Unit
) {
    SectionCard(title = "📊 اختبار الأداء") {
        Button(
            onClick  = onRun,
            enabled  = modelLoaded && !isRunning,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isRunning) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
                Text("جارٍ الاختبار…")
            } else {
                Icon(Icons.Default.Speed, null, Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("تشغيل الاختبار")
            }
        }

        result?.let { r ->
            Spacer(Modifier.height(12.dp))
            BenchRow("وقت التحميل",        "${r.modelLoadMs} ms")
            BenchRow("أول رمز (latency)",  "${r.firstTokenMs} ms")
            BenchRow("رموز/ثانية",         "${"%.2f".format(r.tokensPerSec)} tok/s")
            BenchRow("استهلاك RAM (delta)", "${"%.1f".format(r.memoryDeltaMb)} MB")
            BenchRow("رموز أُنتجت",        "${r.totalTokensGenerated}")
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text("فشل الاختبار: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (!modelLoaded) {
            Spacer(Modifier.height(8.dp))
            Text("حمّل نموذجًا أولًا لتشغيل الاختبار.",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Sub-components
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ProfileRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall,
             color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.labelSmall,
             fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ConfigRow(key: String, value: String, hint: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(key,  style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            Text(hint, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        }
        Text(value, style = MaterialTheme.typography.labelSmall,
             fontFamily = FontFamily.Monospace,
             color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun BenchRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.labelSmall,
             fontFamily = FontFamily.Monospace,
             color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun IntField(label: String, value: String, hint: String = "", onChange: (String) -> Unit) {
    OutlinedTextField(
        value         = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '-' }) },
        label         = { Text(label) },
        supportingText = if (hint.isNotEmpty()) ({ Text(hint, style = MaterialTheme.typography.labelSmall) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine    = true,
        modifier      = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    )
}

@Composable
private fun FloatField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value         = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' }) },
        label         = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine    = true,
        modifier      = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    )
}
