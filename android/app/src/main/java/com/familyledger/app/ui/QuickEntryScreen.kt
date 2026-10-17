package com.familyledger.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.familyledger.app.domain.EntryType
import com.familyledger.app.domain.Money
import com.familyledger.app.data.IdentityProfile
import com.familyledger.app.data.ChatMessage
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

internal fun chatInputEnabled(state: LedgerState): Boolean =
    !state.loading && !state.chatSending && state.quickDrafts.isEmpty() && (!state.busy || state.syncing)

internal fun chatSendEnabled(state: LedgerState): Boolean =
    chatInputEnabled(state) && !state.busy && state.chatInput.isNotBlank()

@OptIn(ExperimentalLayoutApi::class)
@Composable fun QuickEntryScreen(model: LedgerViewModel, state: LedgerState, snackbar: SnackbarHostState,
    editing: String?, onEditingChange: (String?) -> Unit, conversationKey: String) {
    val draft = state.quickDrafts.firstOrNull { it.id == editing }
    if (draft != null) {
        key(draft.id) { EntryEditor(draft, state.busy, snackbar, onClose = { onEditingChange(null) }, onSave = { model.updateQuickDraft(it); onEditingChange(null) }) }
        return
    }
    val style = LocalLedgerStyle.current
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = style.pagePadding, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProfileBadge(IdentityProfile.familyIcons.first { it.id == (state.cloudStatus?.familyIcon ?: "home") })
            Column(Modifier.weight(1f)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("家庭账本", style = MaterialTheme.typography.titleLarge, modifier = Modifier.align(Alignment.CenterVertically))
                    if (state.syncing) Box(Modifier.align(Alignment.CenterVertically)) { SyncStatus(true) }
                }
                Text("${state.localRole} · ${state.cloudStatus?.familyName ?: "本机账本"}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("账本助手", style = MaterialTheme.typography.labelMedium, color = LedgerSage)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
        ChatTimeline(state.chatMessages, conversationKey, state.localRole, state.localAvatar,
            modifier = Modifier.weight(1f).fillMaxWidth(), emptyContent = {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!style.compact) FamilyIllustration(Modifier.align(Alignment.CenterHorizontally))
                    Text(if (style.compact) "记下收支，看清生活。" else if (style.journal) "一笔一页，\n收藏家的日常。" else "收支随手记，\n家里的账一起理。", style = MaterialTheme.typography.headlineMedium)
                    Text("记录收支、查询账目，或聊聊家庭财务。\n每一笔记录，都由你确认后保存。", color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(2.dp))
                    listOf("帮我分析一下家庭财务", "家里的开支可以怎样调整？").forEach { example ->
                        SuggestionChip(onClick = { model.updateChatInput(example) }, label = { Text(example) },
                            enabled = !state.busy && state.quickDrafts.isEmpty(),
                            icon = { Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(16.dp)) },
                            modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }) {
            // The reverse list starts with the latest content; visual draft order is unchanged.
            if (state.quickDrafts.size > 1) item(key = "chat:confirm") {
                Button(onClick = model::saveQuickDrafts, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(Icons.Outlined.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("确认保存 ${state.quickDrafts.size} 笔")
                }
            }
            items(state.quickDrafts.asReversed(), key = { "chat:draft:${it.id}" }) { e ->
                LedgerPanel(Modifier.fillMaxWidth().animateContentSize(), accent = true, contentPadding = 14.dp, contentSpacing = 6.dp) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("待确认 · 尚未入账", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(e.occurredOn, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconBadge(if (e.type == EntryType.INCOME) Icons.Outlined.SouthWest else Icons.Outlined.NorthEast, sage = e.type == EntryType.INCOME)
                            Column(Modifier.weight(1f)) {
                                Text("${e.type.label} · ${e.categoryL2.ifBlank { e.categoryL1 }}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("¥ ${Money.format(e.amountMinor)}", style = MaterialTheme.typography.headlineSmall,
                                    fontFamily = style.moneyFontFamily,
                                    color = if (e.type == EntryType.INCOME) LedgerSage else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        Text("归属 ${e.member} · ${e.account}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (e.isGift) Text("人情往来 · ${e.counterparty.ifBlank { "待补全对象" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { model.removeQuickDraft(e.id) }, enabled = !state.busy, contentPadding = PaddingValues(4.dp)) { Text("移除", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            TextButton(onClick = { onEditingChange(e.id) }, enabled = !state.busy, contentPadding = PaddingValues(4.dp)) { Text("修改") }
                            if (state.quickDrafts.size == 1) Button(onClick = model::saveQuickDrafts, enabled = !state.busy,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("确认入账") }
                        }
                }
            }
            if (state.quickDrafts.size > 1) item(key = "chat:draft-heading") {
                SectionHeading("请确认这 ${state.quickDrafts.size} 笔", "尚未入账 · 可以修改金额、分类和成员")
            }
            if (state.busy && !state.syncing) item(key = "chat:processing") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(state.operationStatus ?: "正在处理…", modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                AnimatedVisibility(state.cloudStatus?.email == null || state.cloudStatus.familyId == null) {
                    TextButton(onClick = model::openCloud, enabled = !state.busy) {
                        Icon(Icons.Outlined.PeopleOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("登录并加入家庭，开始记账")
                    }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(state.chatInput, model::updateChatInput, modifier = Modifier.weight(1f), minLines = 1, maxLines = 5,
                        placeholder = { Text("记收支，聊聊家庭财务…", style = MaterialTheme.typography.bodyMedium) },
                        shape = MaterialTheme.shapes.large, colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
                        enabled = chatInputEnabled(state))
                    FilledIconButton(onClick = model::sendChat,
                        modifier = Modifier.size(56.dp), shape = MaterialTheme.shapes.large,
                        enabled = chatSendEnabled(state)) {
                        Icon(Icons.AutoMirrored.Outlined.Send, contentDescription = "发送")
                    }
                }
                Text(if (state.quickDrafts.isNotEmpty()) "先确认或移除上方记录，再继续记账。" else "说说收支，也可以问问家里的账。",
                    Modifier.padding(top = 8.dp, start = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** [latestContent] is ordered from the bottom upwards, like the reversed messages. */
@Composable internal fun ChatTimeline(
    messages: List<ChatMessage>, conversationKey: String, localRole: String, localAvatar: String,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberSaveable(conversationKey, saver = LazyListState.Saver) { LazyListState() },
    emptyContent: @Composable () -> Unit = {},
    latestContent: LazyListScope.() -> Unit = {}
) {
    val messageIds = remember(messages) { messages.map { it.id } }
    var firstLoadedId by rememberSaveable(conversationKey) {
        mutableStateOf(messageIds.getOrNull(chatWindowStart(messageIds, null)))
    }
    val start = remember(messageIds, firstLoadedId) { chatWindowStart(messageIds, firstLoadedId) }
    val visibleMessages = remember(messages, start) { messages.subList(start, messages.size).asReversed() }
    val latest = messages.lastOrNull()
    var previousLatestId by rememberSaveable(conversationKey) { mutableStateOf(latest?.id) }
    var savedAnchorKey by rememberSaveable(conversationKey) { mutableStateOf<String?>(null) }
    var savedAnchorOffset by rememberSaveable(conversationKey) { mutableIntStateOf(0) }
    val restoredAnchor = remember(conversationKey) { savedAnchorKey to savedAnchorOffset }
    var positionRestored by remember(conversationKey) { mutableStateOf(false) }
    val nearLatestOffset = with(LocalDensity.current) { 96.dp.roundToPx() }
    val style = LocalLedgerStyle.current
    // Sample before this message changes the measured indices. Busy/status changes never scroll.
    val wasNearLatest = remember(latest?.id) {
        listState.firstVisibleItemIndex <= 1 && listState.firstVisibleItemScrollOffset <= nearLatestOffset
    }
    LaunchedEffect(messageIds, firstLoadedId) {
        if (messageIds.isNotEmpty() && firstLoadedId !in messageIds) firstLoadedId = messageIds[start]
    }
    LaunchedEffect(latest?.id) {
        val follow = shouldFollowChatMessage(previousLatestId, latest?.id, latest?.user == true, wasNearLatest)
        if (!positionRestored && messages.isNotEmpty()) {
            // LazyListState saves an index. Save a message key too, because replies can arrive
            // while this tab is absent and shift every existing message's reversed index.
            if (!follow && restoredAnchor.first != null) {
                withFrameNanos { }
                val messageIndex = visibleMessages.indexOfFirst { "chat:message:${it.id}" == restoredAnchor.first }
                val latestItemCount = (listState.layoutInfo.totalItemsCount - visibleMessages.size - 1).coerceAtLeast(0)
                if (messageIndex >= 0) listState.scrollToItem(messageIndex + latestItemCount, restoredAnchor.second)
            }
            positionRestored = true
        }
        if (follow) {
            listState.scrollToItem(0)
        }
        previousLatestId = latest?.id
    }
    LaunchedEffect(listState, conversationKey) {
        snapshotFlow { positionRestored }.first { it }
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == listState.firstVisibleItemIndex }
                ?.let { it.key.toString() to listState.firstVisibleItemScrollOffset }
        }.filterNotNull().collect { (key, offset) ->
            savedAnchorKey = key
            savedAnchorOffset = offset
        }
    }
    LazyColumn(modifier.testTag("chat_timeline"), state = listState, reverseLayout = messages.isNotEmpty(),
        contentPadding = PaddingValues(horizontal = style.pagePadding, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(style.sectionSpacing)) {
        latestContent()
        if (messages.isEmpty()) item(key = "chat:empty") { emptyContent() }
        items(visibleMessages, key = { "chat:message:${it.id}" }) { message ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.user) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (message.user) localRole else "账本助手",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(color = if (message.user) MaterialTheme.colorScheme.primaryContainer else if (style.compact) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLowest,
                    shape = if (style.journal || style.compact) MaterialTheme.shapes.medium else if (message.user) RoundedCornerShape(20.dp, 5.dp, 20.dp, 20.dp) else RoundedCornerShape(5.dp, 20.dp, 20.dp, 20.dp),
                    border = if (style.journal) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
                    modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(if (message.user) .9f else 1f).animateContentSize()) {
                    if (message.user) SelectionContainer { Text(message.text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge) }
                    else AssistantMessage(message.text, Modifier.padding(if (style.compact) 2.dp else 16.dp))
                }
            }
        }
        if (messages.isNotEmpty()) item(key = "chat:history") {
            if (start > 0) TextButton(onClick = { firstLoadedId = earlierChatWindowStart(messageIds, firstLoadedId) },
                modifier = Modifier.fillMaxWidth()) { Text("继续加载（还有 $start 条）") }
            else Text("已显示全部记录", modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
