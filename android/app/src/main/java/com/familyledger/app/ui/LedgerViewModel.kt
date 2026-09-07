package com.familyledger.app.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familyledger.app.data.*
import com.familyledger.app.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LedgerState(val entries: List<LedgerEntry> = emptyList(), val loading: Boolean = true,
    val busy: Boolean = false, val syncing: Boolean = false, val message: String? = null, val restorePreview: List<LedgerEntry>? = null,
    val savedEntryId: String? = null, val importPreview: ImportPreview? = null,
    val selectedImportKeys: Set<String> = emptySet(), val batches: List<ImportBatch> = emptyList(),
    val quickDrafts: List<LedgerEntry> = emptyList(),
    val giftHistoryOpen: Boolean = false, val giftCategories: List<GiftCategory> = emptyList(),
    val selectedGiftCategories: Set<GiftCategory> = emptySet(), val giftProposals: List<GiftHistoryProposal>? = null,
    val giftAnalyzing: Boolean = false,
    val chatMessages: List<ChatMessage> = emptyList(), val chatInput: String = "", val localRole: String = "本人",
    val cloudStatus: CloudStatus? = null, val cloudConflicts: List<CloudConflict> = emptyList(), val inviteCode: String? = null,
    val reportText: String? = null, val reportKey: String? = null, val cloudOpen: Boolean = false,
    val autoSync: Boolean = false, val operationStatus: String? = null, val localAvatar: String = "person", val documentPickerOpen: Boolean = false, val spreadsheetExportReady: Boolean = false)

typealias LedgerSyncAction = suspend (List<LedgerEntry>, suspend (List<LedgerEntry>) -> Unit, (String) -> Unit) -> SyncResult

data class GiftHistoryClient(val owner: () -> String, val analyze: suspend (org.json.JSONObject) -> String)

class LedgerViewModel(private val repository: LedgerRepository, private val cloud: CloudService? = null,
    private val syncAction: LedgerSyncAction = { rows, applyRemote, progress ->
        checkNotNull(cloud) { "请先登录并加入家庭，再同步账本" }.sync(rows, applyRemote, progress)
    }, private val giftHistoryClient: GiftHistoryClient = GiftHistoryClient(owner = {
        val service = checkNotNull(cloud) { "云端组件未初始化" }
        val status = service.status()
        require(status.email != null && status.familyId != null) { "请先登录并加入家庭" }
        service.conversationOwner() + ":" + status.familyId
    }, analyze = { input -> checkNotNull(cloud).ai("gift_history", input) })) : ViewModel() {
    private val mutableState = MutableStateFlow(LedgerState())
    val state = mutableState.asStateFlow()
    private var chatOwner: String? = null
    private val operationMutex = Mutex()
    private var pendingDocumentOperations = 0
    private var preparedSpreadsheet: SpreadsheetExport? = null
    private var giftAnalysisJob: Job? = null
    private var giftAnalysisOwner: String? = null

    fun launchDocumentPicker(launch: () -> Unit) {
        if (state.value.busy || state.value.loading || state.value.documentPickerOpen) return
        mutableState.update { it.copy(documentPickerOpen = true, spreadsheetExportReady = false) }
        try { launch() }
        catch (e: Exception) {
            finishDocumentPicker(cancelled = true)
            mutableState.update { it.copy(message = e.message ?: "无法打开文件选择器") }
        }
    }
    fun finishDocumentPicker(cancelled: Boolean = false) {
        if (cancelled) preparedSpreadsheet = null
        mutableState.update { it.copy(documentPickerOpen = false) }
    }
    fun prepareSpreadsheetExport() = perform {
        // Publish a request, not a captured screen launcher: generation may outlive composition.
        preparedSpreadsheet = withContext(Dispatchers.IO) { repository.exportSpreadsheet() }
        mutableState.update { it.copy(spreadsheetExportReady = true) }
    }
    fun launchPreparedSpreadsheet(launch: () -> Unit) {
        if (state.value.spreadsheetExportReady) launchDocumentPicker(launch)
    }

    private suspend fun restoreConversation() {
        val owner = cloud?.conversationOwner() ?: "local"
        if (owner == chatOwner) return
        // Do not display one account's messages while another account's history is loading.
        mutableState.update { it.copy(chatMessages = emptyList(), quickDrafts = emptyList(), chatInput = "") }
        val messages = repository.chatMessages(owner)
        val drafts = repository.chatDrafts(owner)
        chatOwner = owner
        mutableState.update { it.copy(chatMessages = messages, quickDrafts = drafts,
            chatInput = messages.lastOrNull()?.takeIf { m -> m.user }?.text?.take(2000) ?: "") }
    }

    init {
        refreshCloud()
        mutableState.update { it.copy(autoSync = cloud?.autoSyncEnabled() ?: false) }
        viewModelScope.launch {
            try { restoreConversation() }
            catch (e: Exception) { mutableState.update { it.copy(message = "对话读取失败，请重新打开应用；已有数据未删除") } }
            repository.entries.catch { mutableState.update { it.copy(loading = false, message = "账本读取失败，请关闭后重试") } }
                .collect { rows -> mutableState.update { it.copy(entries = rows, loading = false) } }
        }
    }


    fun openGiftHistory() {
        if (state.value.busy || state.value.loading || state.value.documentPickerOpen) return
        val categories = GiftHistoryCodec.categories(state.value.entries)
        giftAnalysisOwner = null
        mutableState.update { it.copy(giftHistoryOpen = true, giftCategories = categories,
            selectedGiftCategories = categories.filter { c -> c.suggested }.toSet(), giftProposals = null) }
    }
    fun closeGiftHistory() {
        if (state.value.busy) return
        giftAnalysisOwner = null
        mutableState.update { it.copy(giftHistoryOpen = false, giftProposals = null) }
    }
    fun toggleGiftCategory(category: GiftCategory) {
        if (state.value.busy || state.value.giftProposals != null) return
        mutableState.update { it.copy(selectedGiftCategories = if (category in it.selectedGiftCategories)
            it.selectedGiftCategories - category else it.selectedGiftCategories + category) }
    }
    fun resetGiftProposals() {
        if (!state.value.busy) {
            giftAnalysisOwner = null
            mutableState.update { it.copy(giftProposals = null) }
        }
    }
    fun updateGiftProposal(id: String, selected: Boolean, counterparty: String) {
        if (state.value.busy || counterparty.length > 100) return
        mutableState.update { it.copy(giftProposals = it.giftProposals?.map { p ->
            if (p.original.id == id) p.copy(selected = selected, counterparty = counterparty) else p
        }) }
    }
    fun cancelGiftAnalysis() { giftAnalysisJob?.cancel() }
    fun analyzeGiftHistory() = perform {
        require(state.value.giftHistoryOpen)
        val owner = giftHistoryClient.owner()
        val candidates = GiftHistoryCodec.candidates(repository.allEntries(), state.value.selectedGiftCategories)
        require(candidates.isNotEmpty()) { "所选分类没有待整理的历史收支" }
        giftAnalysisJob = currentCoroutineContext()[Job]
        mutableState.update { it.copy(giftAnalyzing = true, giftProposals = null) }
        giftAnalysisOwner = null
        try {
            val proposals = mutableListOf<GiftHistoryProposal>()
            val batches = GiftHistoryCodec.batches(candidates)
            for ((index, batch) in batches.withIndex()) {
                currentCoroutineContext().ensureActive()
                check(giftHistoryClient.owner() == owner) { "账号已改变，请重新整理" }
                mutableState.update { it.copy(operationStatus = "正在整理第 " + (index + 1) + " / " + batches.size + " 批…") }
                val response = giftHistoryClient.analyze(GiftHistoryCodec.input(batch))
                currentCoroutineContext().ensureActive()
                proposals += GiftHistoryCodec.decode(response, batch)
            }
            check(giftHistoryClient.owner() == owner && state.value.giftHistoryOpen) { "账号或整理页面已改变，请重试" }
            giftAnalysisOwner = owner
            mutableState.update { it.copy(giftProposals = proposals) }
        } finally {
            giftAnalysisJob = null
            mutableState.update { it.copy(giftAnalyzing = false) }
        }
    }
    fun confirmGiftHistory() = perform {
        require(state.value.giftHistoryOpen)
        check(giftAnalysisOwner != null && giftHistoryClient.owner() == giftAnalysisOwner) { "账号已改变，请重新整理" }
        val selected = state.value.giftProposals.orEmpty().filter { it.selected }
        require(selected.isNotEmpty()) { "请勾选需要标注的记录" }
        val count = repository.confirmGiftHistory(selected.map { it.original }, selected.associate { it.original.id to it.counterparty })
        giftAnalysisOwner = null
        mutableState.update { it.copy(giftProposals = null, giftHistoryOpen = false, message = "已标注 " + count + " 条原始记录为礼金，未新增账目") }
    }

    fun clearMessage() { mutableState.update { it.copy(message = null) } }
    fun cancelRestore() { if (!state.value.busy) mutableState.update { it.copy(restorePreview = null) } }
    fun consumeSavedEntry() { mutableState.update { it.copy(savedEntryId = null) } }
    fun updateChatInput(value: String) { if (value.length <= 2000) mutableState.update { it.copy(chatInput = value) } }
    fun setLocalRole(value: String) = perform { requireCloud().setLocalRole(value); refreshCloud() }
    fun setLocalIdentity(role: String, avatar: String) = perform { requireCloud().setLocalIdentity(role, avatar); refreshCloud() }
    private suspend fun chatMessage(user: Boolean, text: String, context: String = text, drafts: List<LedgerEntry> = state.value.quickDrafts) {
        val message = ChatMessage(java.util.UUID.randomUUID().toString(), user, text, context)
        repository.appendChat(chatOwner ?: error("对话尚未加载，请重新打开应用"), message, drafts)
        mutableState.update { it.copy(chatMessages = it.chatMessages + message, quickDrafts = drafts) }
    }
    fun updateQuickDraft(entry: LedgerEntry) = perform {
        val valid = validateEntry(entry)
        val drafts = state.value.quickDrafts.map { if (it.id == valid.id) valid else it }
        repository.saveChatDrafts(chatOwner ?: error("对话尚未加载"), drafts)
        mutableState.update { it.copy(quickDrafts = drafts) }
    }
    fun removeQuickDraft(id: String) = perform {
        val drafts = state.value.quickDrafts.filterNot { it.id == id }
        repository.saveChatDrafts(chatOwner ?: error("对话尚未加载"), drafts)
        mutableState.update { it.copy(quickDrafts = drafts) }
    }
    fun sendChat() = perform {
        restoreConversation()
        check(chatOwner == (cloud?.conversationOwner() ?: "local")) { "账号已改变，请重新发送" }
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
            .put("finance", finance).put("giftFields", true)
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
            chatMessage(false, reply, drafts = result.entries)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            chatMessage(false, "发送失败：${e.message ?: "服务暂不可用"}\n内容已放回输入框，可以重试。", "上次请求失败，没有保存记录。")
            mutableState.update { it.copy(chatInput = text) }
        } finally { mutableState.update { it.copy(operationStatus = null) } }
    }
    fun saveQuickDrafts() = perform {
        val rows = state.value.quickDrafts
        require(rows.isNotEmpty())
        val confirmation = repository.confirmChatDrafts(chatOwner ?: error("对话尚未加载"), rows)
        mutableState.update { it.copy(quickDrafts = emptyList(), chatMessages = it.chatMessages + confirmation) }
    }
    private fun requireCloud() = cloud ?: error("云端组件未初始化")
    fun openCloud() { mutableState.update { it.copy(cloudOpen = true) }; refreshCloud() }
    fun closeCloud() { if (!state.value.busy) mutableState.update { it.copy(cloudOpen = false) } }
    fun login(email: String, password: String, signup: Boolean) = perform {
        try {
        if (signup) { val message = requireCloud().signUp(email.trim(), password); mutableState.update { it.copy(message = message) } }
        else { requireCloud().login(email.trim(), password); mutableState.update { it.copy(message = "已登录") } }
        } finally { refreshCloud(); restoreConversation() }
    }
    fun logout() = perform { requireCloud().logout(); refreshCloud(); restoreConversation()
        mutableState.update { it.copy(cloudConflicts = emptyList(), inviteCode = null, message = "已退出登录，对话与本机账本保留") } }
    fun requestPasswordReset(email: String, onSuccess: () -> Unit = {}) = perform {
        val message = requireCloud().requestPasswordReset(email)
        mutableState.update { it.copy(message = message) }; onSuccess()
    }
    fun resetPassword(email: String, code: String, password: String, onSuccess: () -> Unit = {}) = perform {
        val message = requireCloud().resetPassword(email, code, password)
        mutableState.update { it.copy(message = message) }; onSuccess()
    }
    fun updateFamilyProfile(name: String, icon: String) = perform {
        requireCloud().updateFamilyProfile(name, icon); refreshCloud()
        mutableState.update { it.copy(message = "家庭资料已更新，家人刷新或同步后可见") }
    }
    fun refreshFamily() = perform { try { requireCloud().refreshFamily() } finally { refreshCloud(); restoreConversation() } }
    fun createFamily(name: String) = perform { try { requireCloud().createFamily(name.trim()) } finally { refreshCloud(); restoreConversation() } }
    fun joinFamily(code: String) = perform { try { requireCloud().joinFamily(code.trim()) } finally { refreshCloud(); restoreConversation() } }
    fun createInvite() = perform { val code = requireCloud().createInvite(); mutableState.update { it.copy(inviteCode = code) } }
    private fun refreshCloud() {
        try { val status = cloud?.status(); val role = cloud?.localRole() ?: "本人"; val avatar = cloud?.localAvatar() ?: "person"
            mutableState.update { it.copy(cloudStatus = status, localRole = role, localAvatar = avatar) } }
        catch (e: Exception) { mutableState.update { it.copy(cloudStatus = null, message = e.message ?: "云端状态读取失败，本机账本可继续使用") } }
    }
    fun setAutoSync(enabled: Boolean) = perform { requireCloud().setAutoSyncEnabled(enabled); mutableState.update { it.copy(autoSync = enabled) } }
    fun onForeground() {
        val snapshot = state.value
        if (snapshot.autoSync && snapshot.cloudStatus?.email != null && snapshot.cloudStatus.familyId != null &&
            !snapshot.loading && !snapshot.busy && !snapshot.giftHistoryOpen && !snapshot.documentPickerOpen && !snapshot.spreadsheetExportReady && pendingDocumentOperations == 0 &&
            AutoSyncPolicy.isDue(snapshot.cloudStatus.lastSync, System.currentTimeMillis())) syncCloud()
    }
    fun syncCloud() = perform(syncing = true) {
        val result = syncAction(repository.allEntries(), repository::applyRemote,
            { progress -> mutableState.update { it.copy(operationStatus = progress) } })
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
    fun exportReport(resolver: ContentResolver, uri: Uri, text: String) = performDocument {
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
    fun previewImport(resolver: ContentResolver, uri: Uri) = performDocument {
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
    fun exportSpreadsheet(resolver: ContentResolver, uri: Uri) = performDocument {
        val exported = withContext(Dispatchers.IO) {
            val data = preparedSpreadsheet ?: repository.exportSpreadsheet()
            require(data.bytes.isNotEmpty()) { "表格生成失败，请重新导出" }
            (resolver.openOutputStream(uri, "wt") ?: error("无法写入文件")).use { output ->
                output.write(data.bytes); output.flush()
            }
            data
        }
        preparedSpreadsheet = null
        mutableState.update { it.copy(message = "Excel 已导出：全部历史 ${exported.count} 条记录（不含已删除）") }
    }
    fun save(entry: LedgerEntry) = perform {
        repository.save(entry)
        mutableState.update { it.copy(message = "已保存", savedEntryId = entry.id) }
    }
    fun delete(id: String) = perform {
        repository.delete(id)
        mutableState.update { it.copy(message = "已删除") }
    }
    fun export(resolver: ContentResolver, uri: Uri) = performDocument {
        withContext(Dispatchers.IO) {
            val text = repository.backup()
            val output = resolver.openOutputStream(uri, "wt") ?: error("无法打开目标文件")
            output.bufferedWriter(Charsets.UTF_8).use { it.write(text) }
        }
        mutableState.update { it.copy(message = "备份已导出，请妥善保存") }
    }
    fun previewRestore(resolver: ContentResolver, uri: Uri) = performDocument {
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

    private fun perform(syncing: Boolean = false, block: suspend () -> Unit) {
        if (state.value.busy) return
        if (state.value.loading) { mutableState.update { it.copy(message = "正在加载本机数据，请稍候") }; return }
        if (!operationMutex.tryLock()) return
        mutableState.update { it.copy(busy = true, syncing = syncing) }
        viewModelScope.launch {
            try { runOperation(block) } finally { operationMutex.unlock() }
        }
    }
    private fun performDocument(block: suspend () -> Unit) {
        // A returned document URI must not be discarded when foreground work is busy.
        pendingDocumentOperations++
        viewModelScope.launch {
            try {
                operationMutex.withLock {
                    state.first { !it.loading }
                    runOperation(block)
                }
            } finally { pendingDocumentOperations-- }
        }
    }
    private suspend fun runOperation(block: suspend () -> Unit) {
        mutableState.update { it.copy(busy = true) }
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { mutableState.update { it.copy(message = e.message ?: "操作失败，请重试") } }
        finally { mutableState.update { it.copy(busy = false, syncing = false, operationStatus = null) } }
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
