package com.familyledger.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.familyledger.app.domain.*
import java.time.YearMonth

@Composable fun LedgerApp(model: LedgerViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var editorKey by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val month = YearMonth.parse(monthText)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, model) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) model.onForeground()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(state.loading) { if (!state.loading) model.onForeground() }

    LaunchedEffect(state.message) {
        state.message?.let { message -> snackbar.showSnackbar(message); model.clearMessage() }
    }
    LaunchedEffect(state.savedEntryId) {
        if (state.savedEntryId != null) {
            editorKey = null
            model.consumeSavedEntry()
        }
    }
    val editor = editorKey
    if (state.cloudOpen) {
        CloudScreen(model, state, snackbar)
        return
    }
    if (state.importPreview != null) {
        ImportScreen(model, state, snackbar)
        return
    }
    if (editor != null) {
        val existing = state.entries.firstOrNull { it.id == editor }
        if (existing == null) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state.loading) CircularProgressIndicator()
                    else {
                        Text("这笔记录已不可用，请返回账本重新选择。")
                        Button(onClick = { editorKey = null }) { Text("返回账本") }
                    }
                }
            }
            return
        }
        EntryEditor(existing, state.busy, snackbar,
            onClose = { if (!state.busy) editorKey = null },
            onSave = model::save)
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                listOf("对话", "账本", "报表", "设置").forEachIndexed { index, title ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index },
                        icon = { Icon(when (index) {
                            0 -> Icons.Outlined.ChatBubbleOutline
                            1 -> Icons.AutoMirrored.Outlined.ReceiptLong
                            2 -> Icons.Outlined.BarChart
                            else -> Icons.Outlined.Settings
                        }, contentDescription = null) }, label = { Text(title) })
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            when (tab) {
                0 -> QuickEntryScreen(model, state, snackbar)
                1 -> LedgerScreen(state, month, { monthText = it.toString() }, { editorKey = it.id }, model::delete)
                2 -> ReportsScreen(state.entries, month, { monthText = it.toString() }, model, state)
                3 -> SettingsScreen(model, state)
            }
        }
    }
}

@Composable private fun LedgerScreen(state: LedgerState, month: YearMonth, onMonth: (YearMonth) -> Unit,
    onEdit: (LedgerEntry) -> Unit, onDelete: (String) -> Unit) {
    val start = month.atDay(1)
    val end = month.plusMonths(1).atDay(1)
    val summary = remember(state.entries, month) { summarize(state.entries, start, end) }
    val entries = remember(state.entries, month) { state.entries.filter { it.occurredOn >= start.toString() && it.occurredOn < end.toString() } }
    var selected by remember { mutableStateOf<LedgerEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<LedgerEntry?>(null) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 108.dp)) {
        item {
            Text("家庭账本", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(state.cloudStatus?.familyName?.let { "$it · 本机副本" } ?: "本机账本", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            PeriodSelector("${month.year} 年 ${month.monthValue} 月", { onMonth(month.minusMonths(1)) }, { onMonth(month.plusMonths(1)) })
            Spacer(Modifier.height(16.dp))
            SummaryBlock(summary)
            Spacer(Modifier.height(30.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("收支明细", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("${entries.size} 笔", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            if (state.loading) CircularProgressIndicator(Modifier.padding(24.dp))
            else if (entries.isEmpty()) {
                Text("这个月还没有记录", Modifier.padding(top = 28.dp), style = MaterialTheme.typography.titleMedium)
                Text("在「对话」中输入文字，记录日常收支。", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(entries, key = { it.id }) { entry ->
            EntryRow(entry) { selected = entry }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
        }
    }
    selected?.let { entry ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("${entry.type.label} ¥${Money.format(entry.amountMinor)}") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${entry.occurredOn} · ${entry.categoryL1} ${entry.categoryL2}")
                Text("账户：${entry.account}"); Text("成员：${entry.member} · 记账人：${entry.recordedBy}")
                if (entry.merchant.isNotBlank()) Text("商家：${entry.merchant}")
                if (entry.project.isNotBlank()) Text("项目：${entry.project}")
                if (entry.note.isNotBlank()) Text(entry.note)
            } },
            confirmButton = { TextButton(onClick = { selected = null; onEdit(entry) }, enabled = !state.busy) { Text("编辑") } },
            dismissButton = { TextButton(onClick = { selected = null; deleteEntry = entry }, enabled = !state.busy) { Text("删除", color = MaterialTheme.colorScheme.error) } })
    }
    deleteEntry?.let { entry ->
        AlertDialog(onDismissRequest = { deleteEntry = null }, title = { Text("删除这笔记录？") },
            text = { Text("${entry.categoryL1} · ¥${Money.format(entry.amountMinor)}\n删除后将从收支报表中移除。") },
            confirmButton = { TextButton(onClick = { onDelete(entry.id); deleteEntry = null }, enabled = !state.busy) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleteEntry = null }) { Text("取消") } })
    }
}

@Composable private fun EntryRow(entry: LedgerEntry, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(entry.categoryL2.ifBlank { entry.categoryL1 }, style = MaterialTheme.typography.titleMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${entry.occurredOn.substring(5)} · ${entry.member} · ${entry.account}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (entry.note.isNotBlank()) Text(entry.note, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Text("${when(entry.type) { EntryType.INCOME -> "+"; EntryType.EXPENSE -> "−"; else -> "调整 " }}${Money.format(entry.amountMinor)}",
            fontWeight = FontWeight.SemiBold, color = if (entry.type == EntryType.INCOME) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable internal fun PeriodSelector(label: String, previous: () -> Unit, next: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        IconButton(onClick = previous) { Icon(Icons.Outlined.ChevronLeft, "上一期") }
        Text(label, style = MaterialTheme.typography.titleMedium)
        IconButton(onClick = next) { Icon(Icons.Outlined.ChevronRight, "下一期") }
    }
}

@Composable internal fun SummaryBlock(summary: Summary) {
    Surface(color = MaterialTheme.colorScheme.primary, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text("收支结余", style = MaterialTheme.typography.labelLarge)
            Text("¥ ${Money.format(summary.balance)}", Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("收入", style = MaterialTheme.typography.labelMedium); Text("¥ ${Money.format(summary.income)}") }
                Column { Text("支出", style = MaterialTheme.typography.labelMedium); Text("¥ ${Money.format(summary.expense)}") }
            }
        }
    }
}
