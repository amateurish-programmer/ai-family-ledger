package com.familyledger.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.familyledger.app.domain.EntryType
import com.familyledger.app.domain.Money
import com.familyledger.app.data.IdentityProfile

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
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProfileBadge(IdentityProfile.familyIcons.first { it.id == (state.cloudStatus?.familyIcon ?: "home") })
            Column(Modifier.weight(1f)) {
                Text("家庭账本", style = MaterialTheme.typography.titleLarge)
                Text("${state.localRole} · ${state.cloudStatus?.familyName ?: "本机账本"}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("账本助手", style = MaterialTheme.typography.labelMedium, color = LedgerSage)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (state.chatMessages.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FamilyIllustration(Modifier.align(Alignment.CenterHorizontally))
                    Text("收支随手记，\n家里的账一起理。", style = MaterialTheme.typography.headlineMedium)
                    Text("说说今天的收支，或聊聊家庭财务。\n每一笔记录，都由你确认后保存。", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(2.dp))
                    listOf("帮我分析一下家庭财务", "家里的开支可以怎样调整？").forEach { example ->
                        SuggestionChip(onClick = { model.updateChatInput(example) }, label = { Text(example) },
                            enabled = !state.busy && state.quickDrafts.isEmpty(),
                            icon = { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp)) },
                            modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
            items(state.chatMessages, key = { it.id }) { message ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.user) Alignment.End else Alignment.Start,
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (message.user) "${IdentityProfile.avatars.first { it.id == state.localAvatar }.symbol} ${state.localRole}" else "账本助手", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Surface(color = if (message.user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = if (message.user) RoundedCornerShape(20.dp, 5.dp, 20.dp, 20.dp) else RoundedCornerShape(5.dp, 20.dp, 20.dp, 20.dp),
                        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(if (message.user) .9f else 1f).animateContentSize()) {
                        SelectionContainer { Text(message.text, Modifier.padding(17.dp), style = MaterialTheme.typography.bodyLarge) }
                    }
                }
            }
            if (state.busy) item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(state.operationStatus ?: "正在处理…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.quickDrafts.isNotEmpty()) item {
                SectionHeading("请确认这 ${state.quickDrafts.size} 笔", "尚未入账 · 可以修改金额、分类和成员")
            }
            items(state.quickDrafts, key = { it.id }) { e ->
                Card(Modifier.fillMaxWidth().animateContentSize(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconBadge(if (e.type == EntryType.INCOME) Icons.Outlined.SouthWest else Icons.Outlined.NorthEast, sage = e.type == EntryType.INCOME)
                            Column(Modifier.weight(1f)) {
                                Text(e.type.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("¥ ${Money.format(e.amountMinor)}", style = MaterialTheme.typography.headlineSmall,
                                    color = if (e.type == EntryType.INCOME) LedgerSage else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                        Text("${e.occurredOn} · ${e.categoryL1}${if (e.categoryL2.isNotBlank()) " / ${e.categoryL2}" else ""}", style = MaterialTheme.typography.bodyMedium)
                        Text("${e.member} · ${e.account} · 记账人 ${e.recordedBy}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodyMedium)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { model.removeQuickDraft(e.id) }, enabled = !state.busy) { Text("移除", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            FilledTonalButton(onClick = { editing = e.id }, enabled = !state.busy) { Text("修改记录") }
                        }
                    }
                }
            }
            if (state.quickDrafts.isNotEmpty()) item {
                Button(onClick = model::saveQuickDrafts, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("确认保存 ${state.quickDrafts.size} 笔")
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                AnimatedVisibility(state.cloudStatus?.email == null || state.cloudStatus.familyId == null) {
                    TextButton(onClick = model::openCloud, enabled = !state.busy) {
                        Icon(Icons.Outlined.PeopleOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("登录并加入家庭，开始对话")
                    }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(state.chatInput, model::updateChatInput, modifier = Modifier.weight(1f), minLines = 1, maxLines = 5,
                        placeholder = { Text("记收支，聊聊家庭财务…", style = MaterialTheme.typography.bodyMedium) },
                        shape = RoundedCornerShape(24.dp), colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                        enabled = !state.busy && !state.loading && state.quickDrafts.isEmpty())
                    FilledIconButton(onClick = model::sendChat,
                        modifier = Modifier.size(56.dp),
                        enabled = !state.busy && !state.loading && state.chatInput.isNotBlank() && state.quickDrafts.isEmpty()) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发送")
                    }
                }
                Text(if (state.quickDrafts.isNotEmpty()) "先确认或移除上方记录，再继续对话。" else "AI 处理本轮文字与收支汇总；分析基于本机账本。",
                    Modifier.padding(top = 8.dp, start = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
