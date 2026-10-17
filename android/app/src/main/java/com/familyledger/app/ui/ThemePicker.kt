package com.familyledger.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familyledger.app.data.AppTheme

/** Uses the same preference callback as the app, with no ledger or account side effects. */
@Composable internal fun ThemePicker(modifier: Modifier = Modifier) {
    val current = LocalAppTheme.current
    val onThemeChange = LocalThemeChange.current
    val style = LocalLedgerStyle.current
    Column(modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AppTheme.entries.forEach { choice ->
            val selected = current == choice
            Surface(shape = RoundedCornerShape(style.cardRadius), color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) {
                Row(Modifier.fillMaxWidth().testTag("theme_${choice.storageId}")
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onThemeChange(choice) }).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ThemeSurfacePreview(choice)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(choice.label, style = MaterialTheme.typography.titleMedium)
                        if (choice == AppTheme.WARM) {
                            Text("默认", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                        Text(when (choice) {
                            AppTheme.WARM -> "柔和奶油色与暖橙，圆润舒展"
                            AppTheme.JOURNAL -> "纸张、便签与衬线数字"
                            AppTheme.FINANCE -> "冷白青灰，清爽紧凑"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(if (selected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = if (selected) "已选择" else null, modifier = Modifier.size(22.dp),
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

/** Generic shapes only; these decorative previews never carry sample or customer ledger data. */
@Composable private fun ThemeSurfacePreview(theme: AppTheme) {
    val warm = theme == AppTheme.WARM
    val journal = theme == AppTheme.JOURNAL
    val background = Color(if (warm) 0xFFF6EADB else if (journal) 0xFFF2E7CA else 0xFFE9EFF1)
    val surface = Color(if (journal) 0xFFFFFAE9 else 0xFFFFFFFF)
    val accent = Color(if (warm) 0xFFB36843 else if (journal) 0xFF7F8861 else 0xFF315B61)
    val muted = Color(if (warm) 0xFFD7B99A else if (journal) 0xFFC5B78D else 0xFFA6BDC0)
    val shape = RoundedCornerShape(if (warm) 13.dp else if (journal) 3.dp else 6.dp)
    Column(Modifier.width(94.dp).height(112.dp).clearAndSetSemantics { }.clip(shape).background(background)
        .drawBehind {
            if (journal) {
                var y = 12.dp.toPx()
                while (y < size.height) {
                    drawLine(muted.copy(alpha = .3f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    y += 12.dp.toPx()
                }
            }
        }.padding(9.dp), verticalArrangement = Arrangement.spacedBy(if (warm) 7.dp else 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (warm) Icon(Icons.Outlined.Home, null, Modifier.size(13.dp), tint = accent)
            Text("家庭账本", fontFamily = if (journal) FontFamily.Serif else FontFamily.SansSerif,
                fontSize = 9.sp, lineHeight = 12.sp, color = accent, fontWeight = FontWeight.SemiBold)
        }
        Box(Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(if (warm) 9.dp else 2.dp))
            .background(if (warm) Brush.linearGradient(listOf(Color(0xFFF5D4B4), Color(0xFFFFF9ED)))
                else Brush.linearGradient(listOf(surface, surface)))) {
            if (journal) Box(Modifier.align(Alignment.TopCenter).size(25.dp, 5.dp).background(Color(0xFFDCCB98).copy(alpha = .85f)))
            Column(Modifier.padding(start = 7.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(Modifier.size(27.dp, 3.dp).background(muted, RoundedCornerShape(2.dp)))
                Text("¥ —", color = accent, fontSize = if (journal) 18.sp else 16.sp, lineHeight = 21.sp,
                    fontFamily = if (journal) FontFamily.Serif else FontFamily.SansSerif)
            }
        }
        repeat(2) { index ->
            Row(Modifier.fillMaxWidth().height(if (warm) 11.dp else 9.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(if (warm) 9.dp else 6.dp).background(accent.copy(alpha = .25f),
                    RoundedCornerShape(if (warm) 5.dp else 1.dp)))
                Box(Modifier.width(if (index == 0) 32.dp else 24.dp).height(3.dp).background(muted, RoundedCornerShape(1.dp)))
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(10.dp, 3.dp).background(accent.copy(alpha = .6f), RoundedCornerShape(1.dp)))
            }
        }
    }
}
