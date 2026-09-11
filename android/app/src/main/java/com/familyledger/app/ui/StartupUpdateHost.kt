package com.familyledger.app.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.familyledger.app.data.AppUpdateService

/** Explicit activity ownership keeps startup and Settings on one update/download state. */
@Composable fun rememberAppUpdateModel(): AppUpdateViewModel {
    val context = LocalContext.current
    val application = context.applicationContext
    val owner = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<ComponentActivity>().first()
    }
    val factory = remember(application) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppUpdateViewModel(AppUpdateService(application), ForegroundStartupUpdateQuota()) as T
        }
    }
    return viewModel(viewModelStoreOwner = owner, factory = factory)
}

@Composable fun StartupUpdateHost(ledgerBusy: Boolean, onOpenUpdates: () -> Unit) {
    val model = rememberAppUpdateModel()
    val prompt by model.startupPrompt.collectAsStateWithLifecycle()
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateFlow.collectAsState()
    DisposableEffect(lifecycle, model) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) model.checkOnStartup()
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.checkOnStartup()
        onDispose { lifecycle.removeObserver(observer) }
    }
    val update = prompt
    if (update != null && !ledgerBusy && !state.busy && state.installRequest == null &&
        lifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
        AlertDialog(
            onDismissRequest = model::dismissStartupPrompt,
            title = { Text("发现新版本 v${update.versionName}") },
            text = { Text(update.notes.ifBlank { "体验优化与问题修复" },
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = {
                model.dismissStartupPrompt()
                onOpenUpdates()
            }) { Text("查看更新") } },
            dismissButton = { TextButton(onClick = model::dismissStartupPrompt) { Text("稍后") } },
        )
    }
}
