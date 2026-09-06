package com.familyledger.app.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familyledger.app.data.*
import com.familyledger.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class LedgerState(val entries: List<LedgerEntry> = emptyList(), val loading: Boolean = true,
    val busy: Boolean = false, val message: String? = null, val restorePreview: List<LedgerEntry>? = null,
    val savedEntryId: String? = null, val importPreview: ImportPreview? = null,
    val selectedImportKeys: Set<String> = emptySet(), val batches: List<ImportBatch> = emptyList(),
    val quickDrafts: List<LedgerEntry> = emptyList(),
    val chatMessages: List<ChatMessage> = emptyList(), val chatInput: String = "", val localRole: String = "本人",
    val cloudStatus: CloudStatus? = null, val cloudConflicts: List<CloudConflict> = emptyList(), val inviteCode: String? = null,
    val reportText: String? = null, val reportKey: String? = null, val cloudOpen: Boolean = false,
    val autoSync: Boolean = false, val operationStatus: String? = null)

class LedgerViewModel(private val repository: LedgerRepository, private val cloud: CloudService? = null) : ViewModel() {
    private val mutableState = MutableStateFlow(LedgerState())
    val state = mutableState.asStateFlow()

    init {
        refreshCloud()
        mutableState.update { it.copy(autoSync = cloud?.autoSyncEnabled() ?: false) }
        viewModelScope.launch {
            repository.entries.catch { mutableState.update { it.copy(loading = false, message = "账本读取失败，请关闭后重试") } }
                .collect { rows -> mutableState.update { it.copy(entries = rows, loading = false) } }
        }
    }

    fun clearMessage() { mutableState.update { it.copy(message = null) } }
    fun cancelRestore() { if (!state.value.busy) mutableState.update { it.copy(restorePreview = null) } }
    fun consumeSavedEntry() { mutableState.update { it.copy(savedEntryId = null) } }
    fun updateChatInput(value: String) { if (value.length <= 2000) mutableState.update { it.copy(chatInput = value) } }
    fun setLocalRole(value: String) = perform { requireCloud().setLocalRole(value); refreshCloud() }
    private fun chatMessage(user: Boolean, text: String, context: String = text) {
        mutableState.update { it.copy(chatMessages = (it.chatMessages + ChatMessage(java.util.UUID.randomUUID().toString(), user, text, context)).takeLast(80)) }
    }
    fun updateQuickDraft(entry: LedgerEntry) { mutableState.update { it.copy(quickDrafts = it.quickDrafts.map { old -> if (old.id == entry.id) entry else old }) } }
    fun removeQuickDraft(id: String) { mutableState.update { it.copy(quickDrafts = it.quickDrafts.filterNot { e -> e.id == id }) } }
    fun sendChat() = perform {
        val snapshot = state.value
        val text = snapshot.chatInput.trim()
        require(text.isNotBlank() && text.length <= 2000) { "内容须为 1 至 2000 字" }
        require(snapshot.quickDrafts.isEmpty()) { "请先确认或移除待保存记录" }
        require(snapshot.cloudStatus?.email != null && snapshot.cloudStatus.familyId != null) { "请先在设置中登录并创建或加入家庭" }
        val history = org.json.JSONArray().apply {
            snapshot.chatMessages.takeLast(8).forEach { put(org.json.JSONObject().put("role", if (it.user) "user" else "assistant").put("text", it.context.take(750))) }
        }
        val rows = repository.allEntries()
        val today = java.time.LocalDate.now()
        val lastSync = snapshot.cloudStatus.lastSync.takeIf { it > 0 }?.let {
            java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString()
        } ?: "尚未成功同步"
        val finance = withContext(Dispatchers.Default) { FinanceContext.build(rows, today, lastSync) }
        val input = org.json.JSONObject().put("text", text).put("today", java.time.LocalDate.now().toString())
            .put("role", snapshot.localRole).put("history", history)
            .put("finance", finance)
            .put("members", org.json.JSONArray(snapshot.entries.map { it.member }.distinct().take(20)))
            .put("categories", org.json.JSONArray(snapshot.entries.flatMap { listOf(it.categoryL1, it.categoryL2) }.filter { it.isNotBlank() }.distinct().take(20)))
        chatMessage(true, text)
        mutableState.update { it.copy(chatInput = "", operationStatus = "正在分析家庭账本…") }
        try {
            val result = ChatCodec.decode(requireCloud().ai("chat", input), snapshot.localRole)
            val answer = result.query?.let { query ->
                mutableState.update { it.copy(operationStatus = "正在解读查询结果…") }
                val selected = withContext(Dispatchers.Default) { FinanceContext.build(rows, today, lastSync, query) }
                try {
                    requireCloud().ai("analyze", org.json.JSONObject().put("text", text).put("history", history).put("finance", selected))
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    "AI 解读暂未完成：${e.message ?: "服务暂不可用"}。以下为程序计算的查询结果：\n\n" +
                        withContext(Dispatchers.Default) { ChatCodec.answer(query, rows) }
                }
            }
            // Preserve the actual answer for follow-up questions, not only the query filter.
            val reply = answer ?: result.reply
            chatMessage(false, reply)
            mutableState.update { it.copy(quickDrafts = result.entries) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            chatMessage(false, "发送失败：${e.message ?: "服务暂不可用"}\n内容已放回输入框，可以重试。", "上次请求失败，没有保存记录。")
            mutableState.update { it.copy(chatInput = text) }
        } finally { mutableState.update { it.copy(operationStatus = null) } }
    }
    fun saveQuickDrafts() = perform {
        val rows = state.value.quickDrafts
        require(rows.isNotEmpty())
        repository.saveMany(rows)
        mutableState.update { it.copy(quickDrafts = emptyList()) }
        chatMessage(false, "已保存 ${rows.size} 笔记录。可以继续记账或提问。")
    }
    private fun requireCloud() = cloud ?: error("云端组件未初始化")
    fun openCloud() { mutableState.update { it.copy(cloudOpen = true) }; refreshCloud() }
    fun closeCloud() { if (!state.value.busy) mutableState.update { it.copy(cloudOpen = false) } }
    fun login(email: String, password: String, signup: Boolean) = perform {
        if (signup) { val message = requireCloud().signUp(email.trim(), password); mutableState.update { it.copy(message = message) } }
        else { requireCloud().login(email.trim(), password); mutableState.update { it.copy(message = "已登录") } }
        refreshCloud()
    }
    fun logout() = perform { requireCloud().logout(); refreshCloud(); mutableState.update { it.copy(cloudConflicts = emptyList(), inviteCode = null,
        chatMessages = emptyList(), chatInput = "", quickDrafts = emptyList(), message = "已退出登录，本机数据保留") } }
    fun createFamily(name: String) = perform { requireCloud().createFamily(name.trim()); refreshCloud() }
    fun joinFamily(code: String) = perform { requireCloud().joinFamily(code.trim()); refreshCloud() }
    fun createInvite() = perform { val code = requireCloud().createInvite(); mutableState.update { it.copy(inviteCode = code) } }
    private fun refreshCloud() {
        try { val status = cloud?.status(); val role = cloud?.localRole() ?: "本人"; mutableState.update { it.copy(cloudStatus = status, localRole = role) } }
        catch (e: Exception) { mutableState.update { it.copy(cloudStatus = null, message = e.message ?: "云端状态读取失败，本机账本可继续使用") } }
    }
    fun setAutoSync(enabled: Boolean) = perform { requireCloud().setAutoSyncEnabled(enabled); mutableState.update { it.copy(autoSync = enabled) } }
    fun onForeground() { if (state.value.autoSync && state.value.cloudStatus?.email != null && state.value.cloudStatus?.familyId != null && !state.value.loading) syncCloud() }
    fun syncCloud() = perform {
        val result = requireCloud().sync(repository.allEntries(), applyRemote = repository::applyRemote,
            onProgress = { progress -> mutableState.update { it.copy(operationStatus = progress) } })
        refreshCloud()
        mutableState.update { it.copy(cloudConflicts = result.conflicts, message =
            "同步完成，用时 ${(result.elapsedMillis + 999) / 1000} 秒、${result.requestCount} 次请求；上传 ${result.uploaded} 条、下载 ${result.downloaded} 条，${result.conflicts.size} 条冲突") }
    }
    fun resolveConflict(conflict: CloudConflict, useRemote: Boolean) = perform {
        val current = repository.allEntries().firstOrNull { it.id == conflict.id }
        require(current == conflict.local) { "本机记录已修改，请重新同步后再处理冲突" }
        requireCloud().resolve(conflict, useRemote, repository::applyRemote)
        mutableState.update { it.copy(cloudConflicts = it.cloudConflicts.filterNot { c -> c.id == conflict.id }, message = "冲突已处理") }
    }
    fun analyzeReport(report: LedgerReport, key: String) = perform {
        val input = org.json.JSONObject().put("period", report.period)
            .put("income", Money.format(report.summary.income)).put("expense", Money.format(report.summary.expense)).put("balance", Money.format(report.summary.balance))
        fun amounts(rows: List<CategoryTotal>) = org.json.JSONArray().apply { rows.forEach { put(org.json.JSONObject().put("name", it.name).put("amount", Money.format(it.amount))) } }
        input.put("categories", amounts(report.summary.categories)).put("members", amounts(report.members))
        input.put("trend", org.json.JSONArray().apply { report.trend.forEach { put(org.json.JSONObject().put("period", it.period).put("income", Money.format(it.income)).put("expense", Money.format(it.expense))) } })
        val result = requireCloud().ai("report", input)
        mutableState.update { it.copy(reportKey = key, reportText = result) }
    }
    fun exportReport(resolver: ContentResolver, uri: Uri, text: String) = perform {
        withContext(Dispatchers.IO) {
            (resolver.openOutputStream(uri, "wt") ?: error("无法写入报告")).bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        }
        mutableState.update { it.copy(message = "报告已导出") }
    }
    fun cancelImport() { if (!state.value.busy) mutableState.update { it.copy(importPreview = null, selectedImportKeys = emptySet()) } }
    fun toggleImport(key: String) {
        if (state.value.busy) return
        mutableState.update { it.copy(selectedImportKeys = if (key in it.selectedImportKeys) it.selectedImportKeys - key else it.selectedImportKeys + key) }
    }
    fun previewImport(resolver: ContentResolver, uri: Uri) = perform {
        val preview = withContext(Dispatchers.IO) {
            val fileName = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            } ?: "导入工作簿.xlsx"
            val bytes = (resolver.openInputStream(uri) ?: error("无法读取文件")).use { input -> readLimited(input, XlsxCodec.MAX_BYTES) }
            repository.previewImport(bytes, fileName)
        }
        mutableState.update { it.copy(importPreview = preview, selectedImportKeys = preview.rows.filter { r -> r.status == ImportStatus.NEW }.map { r -> r.key }.toSet()) }
    }
    fun confirmImport() = perform {
        val snapshot = state.value
        val preview = snapshot.importPreview ?: return@perform
        val rows = preview.rows.filter { it.key in snapshot.selectedImportKeys && it.status in listOf(ImportStatus.NEW, ImportStatus.SUSPECTED) }.mapNotNull { it.entry }
        val allowSimilar = preview.rows.filter { it.status == ImportStatus.SUSPECTED }.mapNotNull { it.entry?.id }.toSet()
        val count = repository.commitImport(rows, allowSimilar)
        mutableState.update { it.copy(importPreview = null, selectedImportKeys = emptySet(), message = "已导入 $count 条，跳过 ${rows.size - count} 条已处理记录") }
        refreshBatchesNow()
    }
    fun loadBatches() = perform { refreshBatchesNow() }
    private suspend fun refreshBatchesNow() { val batches = repository.batches(); mutableState.update { it.copy(batches = batches) } }
    fun rollbackBatch(id: String) = perform {
        val count = repository.rollbackBatch(id)
        refreshBatchesNow()
        mutableState.update { it.copy(message = "已撤销 $count 条，其他账目保留") }
    }
    fun exportSpreadsheet(resolver: ContentResolver, uri: Uri) = perform {
        withContext(Dispatchers.IO) {
            val bytes = repository.exportSpreadsheet()
            (resolver.openOutputStream(uri, "wt") ?: error("无法写入文件")).use { it.write(bytes) }
        }
        mutableState.update { it.copy(message = "Excel 已导出") }
    }
    fun save(entry: LedgerEntry) = perform {
        repository.save(entry)
        mutableState.update { it.copy(message = "已保存", savedEntryId = entry.id) }
    }
    fun delete(id: String) = perform {
        repository.delete(id)
        mutableState.update { it.copy(message = "已删除") }
    }
    fun export(resolver: ContentResolver, uri: Uri) = perform {
        withContext(Dispatchers.IO) {
            val text = repository.backup()
            val output = resolver.openOutputStream(uri, "wt") ?: error("无法打开目标文件")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        }
        mutableState.update { it.copy(message = "备份已导出，请妥善保存") }
    }
    fun previewRestore(resolver: ContentResolver, uri: Uri) = perform {
        val entries = withContext(Dispatchers.IO) {
            val stream = resolver.openInputStream(uri) ?: error("无法读取备份文件")
            val bytes = stream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    require(output.size() + count <= BackupCodec.MAX_BYTES) { "备份超过 10 MB 上限" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            BackupCodec.decode(bytes.toString(Charsets.UTF_8))
        }
        mutableState.update { it.copy(restorePreview = entries) }
    }
    fun confirmRestore() = perform {
        val rows = state.value.restorePreview ?: return@perform
        val count = repository.restore(rows)
        mutableState.update { it.copy(restorePreview = null, message = "已恢复 $count 条，跳过 ${rows.size - count} 条已有记录") }
    }

    private fun perform(block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { mutableState.update { it.copy(message = e.message ?: "操作失败，请重试") } }
            finally { mutableState.update { it.copy(busy = false, operationStatus = null) } }
        }
    }
    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer); if (n == -1) break
            require(out.size() + n <= limit) { "文件超过 ${limit / 1024 / 1024} MB" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
