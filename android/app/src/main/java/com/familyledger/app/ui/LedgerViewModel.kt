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
    val savedEntryId: String? = null)

class LedgerViewModel(private val repository: LedgerRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(LedgerState())
    val state = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.entries.catch { mutableState.update { it.copy(loading = false, message = "账本读取失败，请关闭后重试") } }
                .collect { rows -> mutableState.update { it.copy(entries = rows, loading = false) } }
        }
    }

    fun clearMessage() { mutableState.update { it.copy(message = null) } }
    fun cancelRestore() { if (!state.value.busy) mutableState.update { it.copy(restorePreview = null) } }
    fun consumeSavedEntry() { mutableState.update { it.copy(savedEntryId = null) } }
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
}
