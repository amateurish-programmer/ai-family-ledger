package com.familyledger.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.familyledger.app.domain.CategoryCatalog
import com.familyledger.app.domain.EntryType
import com.familyledger.app.domain.LedgerEntry

@Composable
fun CategoryPicker(
    type: EntryType,
    categoryL1: String,
    categoryL2: String,
    entries: List<LedgerEntry> = emptyList(),
    enabled: Boolean = true,
    onChange: (String, String) -> Unit
) {
    val catalog = remember(type, entries) { CategoryCatalog.choices(type, entries) }
    var picking by rememberSaveable { mutableIntStateOf(0) }
    var adding by rememberSaveable { mutableIntStateOf(0) }
    var newName by rememberSaveable { mutableStateOf("") }
    val parents = (catalog.keys + listOf(categoryL1).filter { it.isNotBlank() }).distinct()
    val children = (catalog[categoryL1].orEmpty() + listOf(categoryL2).filter { it.isNotBlank() }).distinct()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CategoryField("一级分类", categoryL1.ifBlank { "请选择一级分类" }, enabled,
            Modifier.testTag("category_l1_picker")) { picking = 1 }
        CategoryField("二级分类（可选）", categoryL2.ifBlank { "未设置" }, enabled && categoryL1.isNotBlank(),
            Modifier.testTag("category_l2_picker")) { picking = 2 }
    }

    if (picking != 0) {
        val primary = picking == 1
        AlertDialog(onDismissRequest = { picking = 0 },
            title = { Text(if (primary) "选择一级分类" else "选择二级分类") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (!primary) TextButton(onClick = { onChange(categoryL1, ""); picking = 0 }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth()) { Text("不设二级分类", Modifier.fillMaxWidth()) }
                    (if (primary) parents else children).forEach { choice ->
                        TextButton(onClick = {
                            if (primary) onChange(choice, "") else onChange(categoryL1, choice)
                            picking = 0
                        }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                            Text(choice, Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { adding = picking; picking = 0; newName = "" }, enabled = enabled) {
                    Text(if (primary) "新增一级分类" else "新增二级分类")
                }
            },
            dismissButton = { TextButton(onClick = { picking = 0 }) { Text("关闭") } })
    }

    if (adding != 0) {
        val primary = adding == 1
        val validName = newName.trim().isNotEmpty() && newName.trim().length <= 100
        AlertDialog(onDismissRequest = { adding = 0 },
            title = { Text(if (primary) "新增一级分类" else "新增二级分类") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!primary) Text("所属一级分类：$categoryL1")
                    OutlinedTextField(newName, { newName = it }, singleLine = true, enabled = enabled,
                        label = { Text(if (primary) "新一级分类" else "新二级分类") },
                        isError = newName.trim().length > 100, modifier = Modifier.fillMaxWidth())
                    Text("保存记录后，家庭后续记账可继续选择此分类。", style = MaterialTheme.typography.bodySmall)
                    if (newName.trim().length > 100) Text("分类名称最多 100 字", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (primary) onChange(newName.trim(), "") else onChange(categoryL1, newName.trim())
                    adding = 0
                }, enabled = enabled && validName) { Text("添加并使用") }
            },
            dismissButton = { TextButton(onClick = { adding = 0 }) { Text("取消") } })
    }
}

@Composable
private fun CategoryField(label: String, value: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth().heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
    }
}
