package com.familyledger.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.familyledger.app.data.AppTheme

internal val LedgerSage: Color @Composable get() = MaterialTheme.colorScheme.secondary
internal val LedgerTerracotta: Color @Composable get() = MaterialTheme.colorScheme.primary
internal val LedgerCream: Color @Composable get() = MaterialTheme.colorScheme.background

internal data class LedgerStyle(val theme: AppTheme) {
    val journal get() = theme == AppTheme.JOURNAL
    val compact get() = theme == AppTheme.FINANCE
    val pagePadding: Dp get() = if (compact) 18.dp else 22.dp
    val sectionSpacing: Dp get() = if (compact) 14.dp else 20.dp
    val rowPadding: Dp get() = if (compact) 12.dp else 16.dp
    val cardRadius: Dp get() = when (theme) { AppTheme.WARM -> 24.dp; AppTheme.JOURNAL -> 8.dp; AppTheme.FINANCE -> 12.dp }
    val iconSize: Dp get() = if (compact) 36.dp else 42.dp
    val moneyFontFamily: FontFamily get() = if (journal) FontFamily.Serif else FontFamily.SansSerif
}

internal val LocalAppTheme = staticCompositionLocalOf { AppTheme.WARM }
internal val LocalThemeChange = staticCompositionLocalOf<(AppTheme) -> Unit> { {} }
internal val LocalLedgerStyle = staticCompositionLocalOf { LedgerStyle(AppTheme.WARM) }

private fun colorsFor(theme: AppTheme): ColorScheme {
    val warm = theme == AppTheme.WARM
    val journal = theme == AppTheme.JOURNAL
    return lightColorScheme(
        primary = Color(if (warm) 0xFFAC502E else if (journal) 0xFF636E48 else 0xFF176B69), onPrimary = Color.White,
        primaryContainer = Color(if (warm) 0xFFFBE4D5 else if (journal) 0xFFE7E9CF else 0xFFDBEEEA),
        onPrimaryContainer = Color(if (warm) 0xFF71351D else if (journal) 0xFF3D472D else 0xFF164745),
        secondary = Color(if (warm) 0xFF516B55 else if (journal) 0xFF87653E else 0xFF327C67), onSecondary = Color.White,
        secondaryContainer = Color(if (warm) 0xFFE7EDDF else if (journal) 0xFFF0E1C4 else 0xFFE1EFEB), onSecondaryContainer = Color(0xFF2F4837),
        tertiary = Color(if (journal) 0xFF9C5948 else 0xFF826342), onTertiary = Color.White,
        tertiaryContainer = Color(0xFFF1E6D2), onTertiaryContainer = Color(0xFF544022),
        background = Color(if (warm) 0xFFFBF6EE else if (journal) 0xFFF5F0DF else 0xFFF4F7F8),
        onBackground = Color(if (warm) 0xFF382F28 else if (journal) 0xFF403E32 else 0xFF203537),
        surface = Color(if (warm) 0xFFFBF6EE else if (journal) 0xFFF5F0DF else 0xFFF4F7F8),
        onSurface = Color(if (warm) 0xFF382F28 else if (journal) 0xFF403E32 else 0xFF203537),
        surfaceVariant = Color(if (warm) 0xFFF0E7DC else if (journal) 0xFFECE6D2 else 0xFFE8EFF0),
        onSurfaceVariant = Color(if (warm) 0xFF796B5E else if (journal) 0xFF716C55 else 0xFF5D7378),
        surfaceContainerLowest = Color(if (journal) 0xFFFFFDF3 else 0xFFFFFFFF),
        surfaceContainerLow = Color(if (warm) 0xFFF6EEDF else if (journal) 0xFFF0E9D5 else 0xFFECF2F3),
        surfaceContainer = Color(if (warm) 0xFFF2E8DC else if (journal) 0xFFEAE3CE else 0xFFE7EEF0),
        surfaceContainerHigh = Color(if (warm) 0xFFEDE0D3 else if (journal) 0xFFE4DCC5 else 0xFFE0E9EB),
        surfaceContainerHighest = Color(if (warm) 0xFFE6D9CD else if (journal) 0xFFDED5BC else 0xFFD8E3E5),
        outline = Color(if (warm) 0xFF998777 else if (journal) 0xFF9C9478 else 0xFF859CA0),
        outlineVariant = Color(if (warm) 0xFFE7DACB else if (journal) 0xFFD5CBAF else 0xFFDCE6E8),
        error = Color(0xFFAD3D37), onError = Color.White, errorContainer = Color(0xFFFBE7E2), onErrorContainer = Color(0xFF7F2923)
    )
}

private fun typographyFor(style: LedgerStyle): Typography {
    val heading = if (style.journal) FontFamily.Serif else FontFamily.SansSerif
    return Typography(
        headlineLarge = TextStyle(fontFamily = heading, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-.5).sp),
        headlineMedium = TextStyle(fontFamily = heading, fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 34.sp),
        headlineSmall = TextStyle(fontFamily = heading, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 30.sp),
        titleLarge = TextStyle(fontFamily = heading, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
        titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 23.sp),
        titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 21.sp),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = if (style.compact) 25.sp else 28.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 23.sp), bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
        labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 21.sp),
        labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
        labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 17.sp)
    )
}

@Composable fun FamilyLedgerTheme(theme: AppTheme = AppTheme.WARM, onThemeChange: (AppTheme) -> Unit = {}, content: @Composable () -> Unit) {
    val style = remember(theme) { LedgerStyle(theme) }
    CompositionLocalProvider(LocalAppTheme provides theme, LocalThemeChange provides onThemeChange, LocalLedgerStyle provides style) {
        MaterialTheme(colorScheme = remember(theme) { colorsFor(theme) }, typography = remember(theme) { typographyFor(style) },
            shapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(if (style.journal) 4.dp else 10.dp),
                medium = RoundedCornerShape(if (style.journal) 6.dp else 16.dp), large = RoundedCornerShape(style.cardRadius),
                extraLarge = RoundedCornerShape(if (style.journal) 12.dp else 28.dp)), content = content)
    }
}
