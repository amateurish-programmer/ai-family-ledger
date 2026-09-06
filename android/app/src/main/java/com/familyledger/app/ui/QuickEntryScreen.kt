package com.familyledger.app.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun QuickEntryScreen(model: LedgerViewModel, state: LedgerState, snackbar: SnackbarHostState) {
    var text by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    var cloudConfirm by remember { mutableStateOf(false) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text = if (text.isBlank()) it else "$text；$it" }
        }
    }
    BackHandler(enabled = !state.busy) { model.closeQuickEntry() }
    val draft = state.quickDrafts.firstOrNull { it.id == editing }
    if (draft != null) {
        key(draft.id) { EntryEditor(draft, state.busy, snackbar, onClose = { editing = null }, onSave = { model.updateQuickDraft(it); editing = null }) }
        return
    }
    Scaffold(topBar = { TopAppBar(title = { Text("一句话记账") }, navigationIcon = {
        TextButton(onClick = model::closeQuickEntry, enabled = !state.busy) { Text("返回") }
    }) }, snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        if (state.quickDrafts.isNotEmpty()) Surface {
            Button(onClick = model::saveQuickDrafts, enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) { Text("确认保存 ${state.quickDrafts.size} 笔") }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("先说出来，再确认", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("例如：昨天午饭 36.80 元，今天工资 5000 元。简单整理会默认成员为本人、账户为银行卡，请逐笔核对。")
                OutlinedTextField(text, { if (it.length <= 2000) text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 7,
                    label = { Text("记账内容") }, enabled = !state.busy)
                OutlinedButton(onClick = {
                    try {
                        voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "说出收支情况")
                        })
                    } catch (_: android.content.ActivityNotFoundException) { voiceError = "手机未安装语音识别服务，请使用键盘输入" }
                }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("语音输入") }
                Text("语音由手机的识别服务处理，联网与否取决于该服务。", style = MaterialTheme.typography.bodySmall)
                voiceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { model.parseQuick(text, false) }, enabled = !state.busy && text.isNotBlank(), modifier = Modifier.weight(1f)) { Text("简单整理") }
                    OutlinedButton(onClick = { cloudConfirm = true }, enabled = !state.busy && text.isNotBlank(), modifier = Modifier.weight(1f)) { Text("云端 AI 整理") }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.quickDrafts.isNotEmpty()) item { Text("待确认记录", style = MaterialTheme.typography.titleLarge) }
            items(state.quickDrafts, key = { it.id }) { e ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${e.type.label} ¥ ${com.familyledger.app.domain.Money.format(e.amountMinor)}", style = MaterialTheme.typography.titleLarge)
                        Text("${e.occurredOn} · ${e.categoryL1}\n${e.account} · ${e.member}")
                        Text(e.note, style = MaterialTheme.typography.bodySmall)
                        Row { TextButton(onClick = { editing = e.id }, enabled = !state.busy) { Text("修改") }
                            TextButton(onClick = { model.removeQuickDraft(e.id) }, enabled = !state.busy) { Text("移除") } }
                    }
                }
            }
        }
    }
    if (cloudConfirm) AlertDialog(onDismissRequest = { cloudConfirm = false }, title = { Text("发送到云端 AI？") },
        text = { Text("将发送当前输入内容给 DeepSeek 进行整理。返回结果只作为待确认记录，确认后才入账。请先登录并加入家庭。") },
        confirmButton = { TextButton(onClick = { cloudConfirm = false; model.parseQuick(text, true) }) { Text("发送并整理") } },
        dismissButton = { TextButton(onClick = { cloudConfirm = false }) { Text("取消") } })
}
