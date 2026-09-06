package com.familyledger.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familyledger.app.domain.Money

@Composable fun QuickEntryScreen(model: LedgerViewModel, state: LedgerState, snackbar: SnackbarHostState) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val draft = state.quickDrafts.firstOrNull { it.id == editing }
    if (draft != null) {
        key(draft.id) { EntryEditor(draft, state.busy, snackbar, onClose = { editing = null }, onSave = { model.updateQuickDraft(it); editing = null }) }
        return
    }
    LaunchedEffect(state.chatMessages.size, state.quickDrafts.size, state.busy) {
        val count = list.layoutInfo.totalItemsCount
        if (count > 0) list.animateScrollToItem(count - 1)
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text("账本助手", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("${state.localRole} · ${state.cloudStatus?.familyName ?: "本机账本"}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.chatMessages.isEmpty()) item {
                Text("今天想记什么？", style = MaterialTheme.typography.titleLarge)
                Text("可以输入“午饭花了 36 元”，也可以问“这个月支出多少”。\n记账结果确认后才会保存。", Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(state.chatMessages, key = { it.id }) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.user) Arrangement.End else Arrangement.Start) {
                    Surface(color = if (message.user) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth(if (message.user) .88f else 1f)) {
                        SelectionContainer { Text(message.text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
            if (state.busy) item { Text("正在处理…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (state.quickDrafts.isNotEmpty()) item { Text("待确认记录", style = MaterialTheme.typography.titleMedium) }
            items(state.quickDrafts, key = { it.id }) { e ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${e.type.label} ¥ ${Money.format(e.amountMinor)}", style = MaterialTheme.typography.titleLarge)
                        Text("${e.occurredOn} · ${e.categoryL1}")
                        Text("${e.member} · ${e.account} · 记账人 ${e.recordedBy}", style = MaterialTheme.typography.bodySmall)
                        if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { editing = e.id }, enabled = !state.busy) { Text("修改") }
                            TextButton(onClick = { model.removeQuickDraft(e.id) }, enabled = !state.busy) { Text("移除") }
                        }
                    }
                }
            }
            if (state.quickDrafts.isNotEmpty()) item {
                Button(onClick = model::saveQuickDrafts, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("确认保存 ${state.quickDrafts.size} 笔") }
            }
        }
        Surface(shadowElevation = 2.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                if (state.cloudStatus?.email == null || state.cloudStatus.familyId == null) {
                    TextButton(onClick = model::openCloud, enabled = !state.busy) { Text("登录并加入家庭，开始对话") }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(state.chatInput, model::updateChatInput, modifier = Modifier.weight(1f), minLines = 1, maxLines = 5,
                        placeholder = { Text("记收支，或问问账本…") }, enabled = !state.busy && state.quickDrafts.isEmpty())
                    Button(onClick = model::sendChat, enabled = !state.busy && !state.loading && state.chatInput.isNotBlank() && state.quickDrafts.isEmpty()) { Text("发送") }
                }
                Text(if (state.quickDrafts.isNotEmpty()) "先确认或移除上方记录，再继续对话。" else "发送内容由 DeepSeek 处理；账本问答基于本机记录。",
                    Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
