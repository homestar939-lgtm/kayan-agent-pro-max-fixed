package com.kayan.x.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kayan.x.model.ModelInfo
import com.kayan.x.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelScreen(vm: MainViewModel) {
    val state = vm.uiState.collectAsState().value
    val ctx   = LocalContext.current

    // SAF launcher — pick a single GGUF file
    val modelPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            ctx.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            vm.onModelPicked(it)
        }
    }

    // SAF launcher — pick the HSH workspace root
    val rootPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { vm.registerHshRoot(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("إدارة النموذج") },
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
            // ── APK size truth card ──────────────────────────────────────────
            item { ApkSizeInfoCard() }

            // ── Add model button ─────────────────────────────────────────────
            item {
                OutlinedButton(
                    onClick  = { modelPicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(8.dp))
                    Text("اختر ملف GGUF من الجهاز")
                }
            }

            // ── HSH workspace picker ─────────────────────────────────────────
            item {
                val hasHsh = state.registeredRoots.containsKey("hsh")
                OutlinedButton(
                    onClick  = { rootPicker.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        if (hasHsh) Icons.Default.CheckCircle else Icons.Default.FolderOpen,
                        null,
                        tint = if (hasHsh) MaterialTheme.colorScheme.primary
                               else               MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (hasHsh) "مساحة HSH مرتبطة وموقعة ✓" else "اختيار مجلد HSH لمساحة الوكيل")
                }
            }

            // ── Model list ───────────────────────────────────────────────────
            if (state.registeredModels.isEmpty()) {
                item {
                    EmptyModelsHint()
                }
            } else {
                items(state.registeredModels, key = { it.uri }) { info ->
                    ModelCard(
                        info      = info,
                        isActive  = state.activeModel?.uri == info.uri,
                        loadState = state.modelLoadState,
                        onLoad    = { vm.loadModel(info) },
                        onUnload  = { vm.unloadModel() },
                        onRemove  = { vm.removeModel(info.uri) }
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun ApkSizeInfoCard() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("📦 تفاصيل حجم التطبيق", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SizeRow("Kotlin/Compose + منطق التطبيق", "~5–8 MB")
            SizeRow("kayan_llama.so  (arm64-v8a، بعد strip)", "~10–20 MB")
            SizeRow("إجمالي الـ APK للجهاز (ABI split)", "~15–30 MB")
            Divider(Modifier.padding(vertical = 6.dp))
            SizeRow("نموذج GGUF 1.5B (خارج APK)", "~900 MB – 1 GB")
            SizeRow("نموذج GGUF 3B  (خارج APK)", "~1.8 – 2 GB")
            SizeRow("نموذج GGUF 7B  (خارج APK)", "~3.8 – 4.5 GB")
            Spacer(Modifier.height(6.dp))
            Text(
                "⚠️ النموذج لا يُضمَّن داخل APK أبدًا. يختاره المستخدم من الجهاز عبر SAF.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun SizeRow(label: String, size: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall,
             modifier = Modifier.weight(1f))
        Text(size, style = MaterialTheme.typography.labelSmall,
             fontFamily = FontFamily.Monospace,
             color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ModelCard(
    info: ModelInfo,
    isActive: Boolean,
    loadState: MainViewModel.ModelLoadState,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onRemove: () -> Unit
) {
    val isLoading = loadState is MainViewModel.ModelLoadState.Loading && isActive
    val isLoaded  = loadState is MainViewModel.ModelLoadState.Loaded  && isActive

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isLoaded) Icons.Default.CheckCircle else Icons.Default.Storage,
                    null,
                    tint = if (isLoaded) MaterialTheme.colorScheme.primary
                           else           MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(info.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(info.sizeLabel,   style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
                IconButton(onClick = onRemove, enabled = !isLoaded && !isLoading) {
                    Icon(Icons.Default.Delete, "حذف",
                         tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f))
                }
            }

            Spacer(Modifier.height(8.dp))

            if (isLoading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isLoaded) {
                        OutlinedButton(onClick = onUnload, modifier = Modifier.weight(1f)) {
                            Text("تفريغ النموذج")
                        }
                    } else {
                        Button(onClick = onLoad, modifier = Modifier.weight(1f)) {
                            Text("تحميل النموذج")
                        }
                    }
                }
            }

            if (isLoaded && loadState is MainViewModel.ModelLoadState.Loaded) {
                Text(
                    "وقت التحميل: ${loadState.loadMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyModelsHint() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Default.Storage, null,
                 modifier = Modifier.size(48.dp),
                 tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
            Spacer(Modifier.height(12.dp))
            Text("لا توجد نماذج مسجّلة",
                 style = MaterialTheme.typography.bodyMedium,
                 color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            Spacer(Modifier.height(4.dp))
            Text("حمِّل نموذج GGUF على جهازك ثم اضغط «اختر ملف GGUF».",
                 style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
        }
    }
}
