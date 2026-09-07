package com.familyledger.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable internal fun SyncStatus(syncing: Boolean) {
    if (!syncing) return
    val transition = rememberInfiniteTransition(label = "ledger_sync")
    val angle by transition.animateFloat(0f, 360f,
        infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "sync_rotation")
    Row(Modifier.testTag("sync_status").semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp).rotate(angle), tint = LedgerSage)
        Text("正在同步…", style = MaterialTheme.typography.labelSmall, color = LedgerSage, maxLines = 1)
    }
}
