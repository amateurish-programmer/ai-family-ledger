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
    val quickOpen: Boolean = false, val quickDrafts: List<LedgerEntry> = emptyList(),
    val cloudStatus: CloudStatus? = null, val cloudConflicts: List<CloudConflict> = emptyList(), val inviteCode: String? = null,
    val reportText: String? = null, val reportKey: String? = null, val cloudOpen: Boolean = false,
    val autoSync: Boolean = false)

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
    fun openQuickEntry() { mutableState.update { it.copy(quickOpen = true) } }
    fun closeQuickEntry() { if (!state.value.busy) mutableState.update { it.copy(quickOpen = false) } }
    fun updateQuickDraft(entry: LedgerEntry) { mutableState.update { it.copy(quickDrafts = it.quickDrafts.map { old -> if (old.id == entry.id) entry else old }) } }
    fun removeQuickDraft(id: String) { mutableState.update { it.copy(quickDrafts = it.quickDrafts.filterNot { e -> e.id == id }) } }
    fun parseQuick(text: String, useCloud: Boolean) = perform {
        require(text.isNotBlank() && text.length <= 2000) { "内容须为 1 至 2000 字" }
        val rows = if (useCloud) QuickEntryCodec.cloud(requireCloud().ai("parse", org.json.JSONObject().put("text", text).put("today", java.time.LocalDate.now().toString())))
            else QuickEntryCodec.local(text)
        mutableState.update { it.copy(quickDrafts = rows, message = "已整理 ${rows.size} 笔，请核对后保存") }
    }
    fun saveQuickDrafts() = perform {
        val rows = state.value.quickDrafts
        require(rows.isNotEmpty())
        repository.saveMany(rows)
        mutableState.update { it.copy(quickDrafts = emptyList(), quickOpen = false, message = "已保存 ${rows.size} 笔") }
    }
    private fun requireCloud() = cloud ?: error("云端组件未初始化")
    fun openCloud() { mutableState.update { it.copy(cloudOpen = true) }; refreshCloud() }
    fun closeCloud() { if (!state.value.busy) mutableState.update { it.copy(cloudOpen = false) } }
    fun configureCloud(url: String, key: String) = perform { requireCloud().configure(url.trim(), key.trim()); refreshCloud(); mutableState.update { it.copy(message = "云端地址已保存") } }
    fun login(email: String, password: String, signup: Boolean) = perform {
        if (signup) { val message = requireCloud().signUp(email.trim(), password); mutableState.update { it.copy(message = message) } }
        else { requireCloud().login(email.trim(), password); mutableState.update { it.copy(message = "已登录") } }
        refreshCloud()
    }
    fun logout() = perform { requireCloud().logout(); refreshCloud(); mutableState.update { it.copy(cloudConflicts = emptyList(), inviteCode = null, message = "已退出登录，本机数据保留") } }
    fun createFamily(name: String) = perform { requireCloud().createFamily(name.trim()); refreshCloud() }
    fun joinFamily(code: String) = perform { requireCloud().joinFamily(code.trim()); refreshCloud() }
    fun createInvite() = perform { val code = requireCloud().createInvite(); mutableState.update { it.copy(inviteCode = code) } }
    private fun refreshCloud() {
        try { val status = cloud?.status(); mutableState.update { it.copy(cloudStatus = status) } }
        catch (e: Exception) { mutableState.update { it.copy(cloudStatus = null, message = e.message ?: "云端状态读取失败，本机账本可继续使用") } }
    }
    fun setAutoSync(enabled: Boolean) = perform { requireCloud().setAutoSyncEnabled(enabled); mutableState.update { it.copy(autoSync = enabled) } }
    fun onForeground() { if (state.value.autoSync && state.value.cloudStatus?.email != null && state.value.cloudStatus?.familyId != null && !state.value.loading) syncCloud() }
    fun syncCloud() = perform {
        val result = requireCloud().sync(repository.allEntries(), repository::applyRemote)
        refreshCloud()
        mutableState.update { it.copy(cloudConflicts = result.conflicts, message = "已上传 ${result.uploaded} 条、下载 ${result.downloaded} 条，${result.conflicts.size} 条冲突待处理") }
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
            finally { mutableState.update { it.copy(busy = false) } }
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
