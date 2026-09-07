package com.familyledger.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.familyledger.app.data.AppUpdate
import com.familyledger.app.data.AppUpdateService
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, CANCELLING, READY, VERIFYING }

data class AppUpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val update: AppUpdate? = null,
    val file: File? = null,
    val received: Long = 0,
    val total: Long = 0,
    val message: String? = null,
    val error: Boolean = false,
    val installRequest: File? = null,
) {
    val busy: Boolean get() = phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING, UpdatePhase.CANCELLING, UpdatePhase.VERIFYING)
}

/** Kept independently of the ledger and survives Settings recomposition/rotation. */
class AppUpdateViewModel(
    val currentVersionName: String,
    private val checkUpdate: suspend () -> AppUpdate?,
    private val downloadUpdate: suspend (AppUpdate, (Long, Long) -> Unit) -> File,
    private val verifyUpdate: suspend (AppUpdate, File) -> Unit,
) : ViewModel() {
    constructor(service: AppUpdateService) : this(service.currentVersionName, service::check, service::download,
        { update, file -> withContext(Dispatchers.IO) { service.verifyForInstall(update, file) } })

    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()
    private var operation: Job? = null

    fun check() {
        if (state.value.busy || state.value.installRequest != null) return
        mutableState.value = AppUpdateState(phase = UpdatePhase.CHECKING)
        operation = viewModelScope.launch {
            try {
                val update = checkUpdate()
                mutableState.value = AppUpdateState(
                    phase = if (update == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE,
                    update = update, message = if (update == null) "已是最新版本" else null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutableState.value = AppUpdateState(message = failure(error), error = true) }
        }
    }

    fun download() {
        val before = state.value
        val update = before.update ?: return
        if (before.busy || before.installRequest != null) return
        mutableState.value = before.copy(phase = UpdatePhase.DOWNLOADING, file = null, received = 0,
            total = update.sizeBytes, message = null, error = false)
        operation = viewModelScope.launch {
            try {
                val file = downloadUpdate(update) { received, total ->
                    mutableState.update { if (it.phase == UpdatePhase.DOWNLOADING) it.copy(received = received, total = total) else it }
                }
                mutableState.update { it.copy(phase = UpdatePhase.READY, file = file, received = update.sizeBytes,
                    message = "下载完成，安装包已校验") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.update { it.copy(phase = UpdatePhase.AVAILABLE, file = null, message = failure(error), error = true) }
            } finally {
                // Do not allow a new writer until cancellation and temporary-file cleanup finish.
                mutableState.update { if (it.phase == UpdatePhase.CANCELLING) it.copy(phase = UpdatePhase.AVAILABLE,
                    message = "下载已取消", error = false, file = null) else it }
            }
        }
    }

    fun cancelDownload() {
        if (state.value.phase != UpdatePhase.DOWNLOADING) return
        mutableState.update { it.copy(phase = UpdatePhase.CANCELLING, message = "正在取消下载…") }
        operation?.cancel()
    }

    fun prepareInstall() {
        val before = state.value
        val update = before.update ?: return
        val file = before.file ?: return
        if (before.phase != UpdatePhase.READY || before.installRequest != null) return
        mutableState.update { it.copy(phase = UpdatePhase.VERIFYING, message = "正在核对安装包…", error = false) }
        operation = viewModelScope.launch {
            try {
                verifyUpdate(update, file)
                mutableState.update { it.copy(phase = UpdatePhase.READY, installRequest = file, message = null) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.update { it.copy(phase = UpdatePhase.AVAILABLE, file = null, installRequest = null,
                    message = failure(error), error = true) }
            }
        }
    }

    fun consumeInstallRequest(): File? {
        val file = state.value.installRequest ?: return null
        mutableState.update { it.copy(installRequest = null) }
        return file
    }

    fun notice(message: String, error: Boolean = false) {
        mutableState.update { it.copy(message = message, error = error) }
    }

    private fun failure(error: Exception): String = when (error) {
        is java.net.SocketTimeoutException -> "更新请求超时，请检查网络后重试"
        is java.io.IOException -> "更新连接失败，请检查网络或可用空间后重试"
        is IllegalArgumentException, is IllegalStateException -> error.message ?: "更新校验失败，请重新检查更新"
        else -> "暂时无法完成更新，请重试"
    }
}
