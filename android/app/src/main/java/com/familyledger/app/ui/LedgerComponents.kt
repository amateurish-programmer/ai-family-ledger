package com.familyledger.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.familyledger.app.data.ProfileChoice
import com.familyledger.app.data.AppTheme

/** The shared surface changes material, corners and decoration, not only accent colour. */
@Composable internal fun LedgerPanel(modifier: Modifier = Modifier, accent: Boolean = false,
    contentPadding: Dp = 18.dp, contentSpacing: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    val style = LocalLedgerStyle.current
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(style.cardRadius)
    val fill = if (accent && style.theme == AppTheme.WARM) Brush.linearGradient(listOf(colors.primaryContainer, colors.surfaceContainerLowest))
        else Brush.linearGradient(listOf(colors.surfaceContainerLowest, colors.surfaceContainerLowest))
    Box(modifier.fillMaxWidth()) {
        Surface(shape = shape, color = Color.Transparent,
            border = if (style.journal || style.compact) BorderStroke(1.dp, colors.outlineVariant) else null) {
            Column(Modifier.fillMaxWidth().clip(shape).background(fill).drawBehind {
                if (style.journal) {
                    val step = 22.dp.toPx()
                    var y = step
                    while (y < size.height) {
                        drawLine(colors.outlineVariant.copy(alpha = .23f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                        y += step
                    }
                }
            }.padding(contentPadding), verticalArrangement = Arrangement.spacedBy(contentSpacing), content = content)
        }
        if (style.journal && accent) Box(Modifier.align(Alignment.TopCenter).offset(y = (-5).dp)
            .size(56.dp, 10.dp).background(colors.secondaryContainer.copy(alpha = .85f)))
    }
}

@Composable internal fun ProfileBadge(choice: ProfileChoice, modifier: Modifier = Modifier) {
    val style = LocalLedgerStyle.current
    Box(modifier.size(style.iconSize).background(MaterialTheme.colorScheme.primaryContainer, if (style.journal) RoundedCornerShape(8.dp) else CircleShape)
        .semantics { contentDescription = choice.label }, contentAlignment = Alignment.Center) {
        Icon(when (choice.id) {
            "home" -> Icons.Outlined.Home; "heart" -> Icons.Outlined.FavoriteBorder
            "tree" -> Icons.Outlined.Park; "sun" -> Icons.Outlined.LightMode
            "cat" -> Icons.Outlined.Pets; "child" -> Icons.Outlined.ChildCare
            "elder" -> Icons.Outlined.Elderly; "woman" -> Icons.Outlined.Face3
            "man" -> Icons.Outlined.Face; else -> Icons.Outlined.PersonOutline
        }, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PageHeading(title: String, subtitle: String, syncing: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.align(Alignment.CenterVertically))
                if (syncing) Box(Modifier.align(Alignment.CenterVertically)) { SyncStatus(true) }
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) trailing()
    }
}

@Composable internal fun SectionHeading(title: String, subtitle: String? = null) {
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Stack the exact amount when large text or a narrow screen would squeeze the name. */
@Composable internal fun RankedAmount(name: String, amount: String, modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface) {
    val style = LocalLedgerStyle.current
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val availableWidth = maxWidth
        if (availableWidth < 280.dp || scale >= 1.3f) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium)
                Text(amount, fontFamily = style.moneyFontFamily, style = MaterialTheme.typography.titleMedium, color = color)
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Text(amount, Modifier.widthIn(max = availableWidth / 2), fontFamily = style.moneyFontFamily,
                style = MaterialTheme.typography.titleMedium, color = color)
        }
    }
}

@Composable internal fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, sage: Boolean = false) {
    val style = LocalLedgerStyle.current
    Box(modifier.size(style.iconSize).background(if (style.compact) Color.Transparent else if (sage) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
        if (style.journal) RoundedCornerShape(8.dp) else CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = if (sage) LedgerSage else MaterialTheme.colorScheme.primary, modifier = Modifier.size(23.dp))
    }
}

@Composable internal fun EmptyLedger(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IconBadge(Icons.Outlined.Home, sage = true)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Purely decorative vector artwork; no embedded words or customer information. */
@Composable internal fun FamilyIllustration(modifier: Modifier = Modifier) {
    if (LocalLedgerStyle.current.compact) return
    Canvas(modifier.size(width = 190.dp, height = 126.dp)) {
        val sx = size.width / 190f
        val sy = size.height / 126f
        fun p(x: Float, y: Float) = Offset(x * sx, y * sy)
        drawOval(Color(0xFFF1E9DA), topLeft = p(17f, 104f), size = Size(162f * sx, 15f * sy))
        drawCircle(Color(0xFFF5DABF), 24f * sx, p(146f, 29f))
        val roof = Path().apply { moveTo(46f * sx, 58f * sy); lineTo(96f * sx, 17f * sy); lineTo(147f * sx, 58f * sy); close() }
        drawPath(roof, Color(0xFFC8734C))
        drawRoundRect(Color(0xFFF0DDC5), p(54f, 57f), Size(83f * sx, 53f * sy), CornerRadius(5f * sx))
        drawRoundRect(Color(0xFF789078), p(87f, 76f), Size(20f * sx, 34f * sy), CornerRadius(10f * sx))
        drawRoundRect(Color(0xFFFFFCF5), p(64f, 69f), Size(14f * sx, 16f * sy), CornerRadius(3f * sx))
        drawRoundRect(Color(0xFFFFFCF5), p(116f, 69f), Size(12f * sx, 16f * sy), CornerRadius(3f * sx))
        drawLine(Color(0xFF789078), p(29f, 110f), p(29f, 67f), 3f * sx)
        drawOval(Color(0xFFB3BFA0), p(17f, 55f), Size(23f * sx, 29f * sy))
        drawOval(Color(0xFF8EA586), p(27f, 71f), Size(18f * sx, 25f * sy))
        drawRoundRect(Color(0xFFBC825D), p(20f, 99f), Size(23f * sx, 13f * sy), CornerRadius(3f * sx))
        drawLine(Color(0xFF789078), p(161f, 110f), p(161f, 82f), 3f * sx)
        drawOval(Color(0xFF9CAF90), p(148f, 70f), Size(25f * sx, 27f * sy))
    }
}
