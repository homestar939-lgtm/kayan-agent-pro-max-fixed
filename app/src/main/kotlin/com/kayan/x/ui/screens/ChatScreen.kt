package com.kayan.x.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kayan.x.agent.AgentStep
import com.kayan.x.safety.ConfirmationPolicy
import com.kayan.x.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: MainViewModel) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(state.messages.size, state.streamingText) {
        val last = state.messages.size + if (state.streamingText.isNotEmpty()) 1 else 0
        if (last > 0) listState.animateScrollToItem(last - 1)
    }

    state.pendingConfirmation?.let { pending ->
        ConfirmationDialog(pending.toolName, pending.params, pending.riskLevel, pending.onConfirm, pending.onDeny)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("KAYAN X", style = MaterialTheme.typography.titleMedium); Text("محرك محلي • ${state.activeModel?.displayName ?: "لا يوجد نموذج"}", style = MaterialTheme.typography.labelSmall) } },
                actions = {
                    IconButton(onClick = {
                        val text = vm.conversationText()
                        val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cb.setPrimaryClip(ClipData.newPlainText("Kayan X", text))
                        copied = true
                    }, enabled = state.messages.isNotEmpty()) { Icon(Icons.Default.ContentCopy, "نسخ المحادثة") }
                    IconButton(onClick = { vm.newConversation() }) { Icon(Icons.Default.AddComment, "محادثة جديدة") }
                    if (state.agentRunning) IconButton(onClick = { vm.cancelAgent() }) { Icon(Icons.Default.Close, "إيقاف", tint = MaterialTheme.colorScheme.error) }
                }
            )
        },
        bottomBar = {
            ChatInputBar(
                value = input,
                enabled = !state.agentRunning && state.nativeLibError == null && state.modelLoadState is MainViewModel.ModelLoadState.Loaded,
                isRunning = state.agentRunning,
                mode = state.mode,
                onModeChange = vm::setMode,
                onValueChange = { input = it },
                onSend = { if (input.isNotBlank()) { vm.sendMessage(input.trim()); input = "" } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (copied) {
                LaunchedEffect(Unit) { kotlinx.coroutines.delay(1500); copied = false }
                Text("تم نسخ المحادثة إلى الحافظة", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { state.nativeLibError?.let { ErrorBanner("المكتبة النووية لم تُحمّل: $it") } ?: ModelStatusBanner(state.modelLoadState) }
                items(state.messages, key = { it.id }) { MessageBubble(it) }
                if (state.streamingText.isNotEmpty()) item { StreamingBubble(state.streamingText, state.streamingTokensPerSec) }
                if (state.agentRunning && state.streamingText.isEmpty()) item { ThinkingIndicator(state.mode) }
            }
        }
    }
}

@Composable private fun ErrorBanner(text: String) {
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) { Text("❌ $text", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp)) }
}

@Composable private fun ModelStatusBanner(loadState: MainViewModel.ModelLoadState) {
    val (text, color) = when (loadState) {
        MainViewModel.ModelLoadState.NotLoaded -> "لم يُحمّل نموذج GGUF" to MaterialTheme.colorScheme.error
        is MainViewModel.ModelLoadState.Loading -> "جارٍ تحميل ${loadState.modelName}…" to MaterialTheme.colorScheme.secondary
        is MainViewModel.ModelLoadState.Loaded -> "✓ النموذج جاهز • ${loadState.loadMs}ms" to MaterialTheme.colorScheme.primary
        is MainViewModel.ModelLoadState.Failed -> "✗ فشل التحميل: ${loadState.error}" to MaterialTheme.colorScheme.error
    }
    Surface(shape = RoundedCornerShape(10.dp), color = color.copy(alpha = .12f), modifier = Modifier.fillMaxWidth()) { Text(text, color = color, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp)) }
}

@Composable
private fun MessageBubble(msg: MainViewModel.ChatMessage) {
    val context = LocalContext.current
    val isUser = msg.role == MainViewModel.ChatMessage.Role.USER
    val isSystem = msg.role == MainViewModel.ChatMessage.Role.SYSTEM
    var expanded by remember { mutableStateOf(false) }
    if (isSystem) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Text(msg.content, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f), modifier = Modifier.padding(10.dp))
        }
        return
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 380.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(msg.content, color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                if (!isUser) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        msg.tokensPerSec?.let {
                            Text("${"%.2f".format(it)} tok/s", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = {
                            val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cb.setPrimaryClip(ClipData.newPlainText("Kayan", msg.content))
                        }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.ContentCopy, "نسخ الإجابة", Modifier.size(16.dp))
                        }
                    }
                }
                if (msg.agentSteps.isNotEmpty()) {
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "إخفاء خطوات الوكيل" else "عرض خطوات الوكيل (${msg.agentSteps.size})") }
                    if (expanded) msg.agentSteps.forEach { AgentStepCard(it) }
                }
            }
        }
    }
}

@Composable private fun StreamingBubble(text: String, tps: Float) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.widthIn(max = 350.dp)) {
        Column(Modifier.padding(12.dp)) { Text(text, style = MaterialTheme.typography.bodyMedium); Text("${"%.2f".format(tps)} tok/s • يولّد الآن", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable private fun AgentStepCard(step: AgentStep) {
    Surface(shape = RoundedCornerShape(8.dp), color = if (step.success) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .3f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = .3f), modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(if (step.success) Icons.Default.Check else Icons.Default.Warning, null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(5.dp)); Text("${step.stepId}: ${step.toolName}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace); Spacer(Modifier.weight(1f)); Text("${step.durationMs}ms", style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable private fun ThinkingIndicator(mode: MainViewModel.ConversationMode) { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text(if (mode == MainViewModel.ConversationMode.AGENT) "الوكيل يخطط وينفذ…" else "Kayan يولّد…", style = MaterialTheme.typography.bodySmall) } }

@Composable private fun ChatInputBar(value: String, enabled: Boolean, isRunning: Boolean, mode: MainViewModel.ConversationMode, onModeChange: (MainViewModel.ConversationMode) -> Unit, onValueChange: (String) -> Unit, onSend: () -> Unit) {
    Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                FilterChip(selected = mode == MainViewModel.ConversationMode.CHAT, onClick = { onModeChange(MainViewModel.ConversationMode.CHAT) }, label = { Text("شات عادي") }, leadingIcon = { Icon(Icons.Default.Chat, null, Modifier.size(16.dp)) })
                Spacer(Modifier.width(8.dp))
                FilterChip(selected = mode == MainViewModel.ConversationMode.AGENT, onClick = { onModeChange(MainViewModel.ConversationMode.AGENT) }, label = { Text("وكيل الملفات") }, leadingIcon = { Icon(Icons.Default.SmartToy, null, Modifier.size(16.dp)) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = value, onValueChange = onValueChange, enabled = enabled, placeholder = { Text(if (enabled) if (mode == MainViewModel.ConversationMode.AGENT) "اكتب مهمة داخل HSH…" else "اكتب رسالتك…" else "حمّل نموذجًا أولًا") }, modifier = Modifier.weight(1f), maxLines = 4, shape = RoundedCornerShape(22.dp))
                Spacer(Modifier.width(8.dp)); FilledIconButton(onClick = onSend, enabled = enabled && value.isNotBlank()) { Icon(Icons.Default.Send, "إرسال") }
            }
        }
    }
}

@Composable private fun ConfirmationDialog(toolName: String, params: Map<String, Any>, riskLevel: ConfirmationPolicy.OperationRisk, onConfirm: () -> Unit, onDeny: () -> Unit) {
    val riskColor = if (riskLevel == ConfirmationPolicy.OperationRisk.CRITICAL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    AlertDialog(onDismissRequest = onDeny, icon = { Icon(Icons.Default.Warning, null, tint = riskColor) }, title = { Text("تأكيد عملية الوكيل") }, text = { Column { Text("$toolName • ${riskLevel.name}", color = riskColor); params.forEach { (k, v) -> Text("$k: $v", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) } } }, confirmButton = { Button(onClick = onConfirm) { Text("تنفيذ") } }, dismissButton = { OutlinedButton(onClick = onDeny) { Text("إلغاء") } })
}
