package com.familyledger.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal val LedgerSage = Color(0xFF486552)
internal val LedgerTerracotta = Color(0xFFAD4B29)
internal val LedgerCream = Color(0xFFFCF8F1)

private val LedgerColors = lightColorScheme(
    primary = LedgerTerracotta, onPrimary = Color.White,
    primaryContainer = Color(0xFFFBE5D8), onPrimaryContainer = Color(0xFF713119),
    secondary = LedgerSage, onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4EBDD), onSecondaryContainer = Color(0xFF2F4837),
    tertiary = Color(0xFF866640), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF2E8D6), onTertiaryContainer = Color(0xFF534022),
    background = LedgerCream, onBackground = Color(0xFF302D28),
    surface = LedgerCream, onSurface = Color(0xFF302D28),
    surfaceVariant = Color(0xFFF0EAE0), onSurfaceVariant = Color(0xFF736B60),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F1E7),
    surfaceContainer = Color(0xFFF2ECE2), surfaceContainerHigh = Color(0xFFEDE5D9),
    surfaceContainerHighest = Color(0xFFE8DFD2),
    outline = Color(0xFF918679), outlineVariant = Color(0xFFDED5C8),
    error = Color(0xFFAE352F), onError = Color.White,
    errorContainer = Color(0xFFFCE8E4), onErrorContainer = Color(0xFF7F2923)
)

private val LedgerTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 39.sp, letterSpacing = (-.6).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 34.sp, letterSpacing = (-.4).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 31.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 21.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 27.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 21.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 17.sp)
)

@Composable fun FamilyLedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LedgerColors, typography = LedgerTypography,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(30.dp)),
        content = content)
}
