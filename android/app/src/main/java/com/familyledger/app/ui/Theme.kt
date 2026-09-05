package com.familyledger.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LedgerColors = lightColorScheme(
    primary = Color(0xFF205A44), onPrimary = Color.White,
    primaryContainer = Color(0xFFD9EADC), onPrimaryContainer = Color(0xFF103B2A),
    secondary = Color(0xFF53665A), background = Color(0xFFF7F8F3),
    surface = Color(0xFFF7F8F3), surfaceVariant = Color(0xFFE9EDE5),
    onSurface = Color(0xFF202D25), onSurfaceVariant = Color(0xFF536158),
    error = Color(0xFFAC3B30)
)

@Composable fun FamilyLedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LedgerColors, content = content)
}
