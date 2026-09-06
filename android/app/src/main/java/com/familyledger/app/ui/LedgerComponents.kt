package com.familyledger.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.familyledger.app.data.ProfileChoice

@Composable internal fun ProfileBadge(choice: ProfileChoice, modifier: Modifier = Modifier) {
    Box(modifier.size(46.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
        .semantics { contentDescription = choice.label }, contentAlignment = Alignment.Center) {
        Text(choice.symbol, style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable internal fun PageHeading(title: String, subtitle: String, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.headlineLarge)
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

@Composable internal fun IconBadge(icon: ImageVector, modifier: Modifier = Modifier, sage: Boolean = false) {
    Box(modifier.size(46.dp).background(if (sage) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
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
