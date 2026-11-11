package com.familyledger.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.familyledger.app.domain.CategoryCatalog
import com.familyledger.app.domain.LedgerEntry
import com.familyledger.app.domain.Money

private data class CategoryChangeProposal(val original: LedgerEntry, val replacement: LedgerEntry)

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CategoryReviewScreen(
    entries: List<LedgerEntry>,
    busy: Boolean,
    onClose: () -> Unit,
    onApply: (List<LedgerEntry>, List<LedgerEntry>) -> Unit
) {
    val proposals = remember {
        entries.filter { it.deletedAt == null }.mapNotNull { original ->
            CategoryCatalog.suggested(original)?.let { CategoryChangeProposal(original, it) }
        }
    }
    var selectedIds by rememberSaveable { mutableStateOf(proposals.map { it.original.id }) }
    var confirmation by remember { mutableStateOf<List<CategoryChangeProposal>?>(null) }
    val current = remember(entries) { entries.associateBy { it.id } }
    val staleIds = remember(proposals, current) {
        proposals.filter { current[it.original.id] != it.original }.map { it.original.id }.toSet()
    }
    val selected = proposals.filter { it.original.id in selectedIds && it.original.id !in staleIds }
    LaunchedEffect(staleIds) { selectedIds = selectedIds.filterNot { it in staleIds } }
    BackHandler { if (!busy) onClose() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("历史分类整理") }, navigationIcon = {
            TextButton(onClick = onClose, enabled = !busy) { Text("取消") }
        })
    }, bottomBar = {
        Surface {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("已选 ${selected.size} 条，确认前不会修改账本。", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { confirmation = selected.toList() }, enabled = !busy && selected.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().testTag("category_review_apply")) {
                    Text(if (busy) "正在保存…" else "保存所选 ${selected.size} 条分类")
                }
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("核对旧分类与建议分类", fontWeight = FontWeight.Bold)
                Text("仅列出能够明确对应的旧分类。含义不清的记录请在账单中逐条编辑。可取消勾选不需要整理的记录。")
                Text("确认后更新所选记录的两级分类，金额、日期、归属成员和导入来源保持原值。",
                    style = MaterialTheme.typography.bodySmall)
                if (staleIds.isNotEmpty()) {
                    Text("${staleIds.size} 条记录已变化或删除，已取消选择。请关闭后重新预览这些记录。",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (proposals.isEmpty()) item { Text("没有可以明确整理的旧分类。") }
            items(proposals, key = { it.original.id }) { proposal ->
                val row = proposal.original
                val stale = row.id in staleIds
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = row.id in selectedIds && !stale, enabled = !busy && !stale,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) (selectedIds + row.id).distinct() else selectedIds - row.id
                                }, modifier = Modifier.testTag("category_review_select_${row.id}"))
                            Text("${row.occurredOn} · ${row.type.label} ¥ ${Money.format(row.amountMinor)}",
                                Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        }
                        Text("归属：${row.member}", style = MaterialTheme.typography.bodySmall)
                        Text("原分类：${categoryLabel(row)}")
                        Text("建议分类：${categoryLabel(proposal.replacement)}", fontWeight = FontWeight.Medium)
                        if (row.merchant.isNotBlank()) Text("商家：${row.merchant}", style = MaterialTheme.typography.bodySmall)
                        if (row.note.isNotBlank()) Text(row.note, style = MaterialTheme.typography.bodySmall)
                        if (stale) Text("记录已变化，请重新预览。", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    confirmation?.let { pending ->
        val fresh = pending.isNotEmpty() && pending.all { current[it.original.id] == it.original }
        AlertDialog(onDismissRequest = { if (!busy) confirmation = null },
            title = { Text("确认修改历史分类？") },
            text = {
                Text(if (fresh) "将修改所选 ${pending.size} 条记录的两级分类，并随账本同步。请确认已核对每条建议。"
                    else "选中记录已变化或删除，本次无法保存。请返回核对后重新预览。")
            },
            confirmButton = {
                TextButton(enabled = !busy && fresh, modifier = Modifier.testTag("category_review_confirm"),
                    onClick = confirm@{
                        val approved = confirmation ?: return@confirm
                        if (busy || approved.any { current[it.original.id] != it.original }) return@confirm
                        confirmation = null
                        onApply(approved.map { it.original }, approved.map { it.replacement })
                    }) { Text("确认保存") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { confirmation = null }) { Text("继续核对") }
            })
    }
}

private fun categoryLabel(entry: LedgerEntry) = listOf(entry.categoryL1, entry.categoryL2)
    .filter { it.isNotBlank() }.joinToString(" / ")
