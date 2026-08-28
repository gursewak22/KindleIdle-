package com.kindleidle.host.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The same paper-and-ink palette the two web screens use, so the native
 * remote does not look like a different product from the page it replaces.
 */

private val Paper = Color(0xFFFAF9F6)
private val PaperEdge = Color(0xFFF2F0EA)
private val Ink = Color(0xFF1B1A17)
private val Muted = Color(0xFF6F6B62)
private val Rule = Color(0xFFD8D3C8)

private val NightPaper = Color(0xFF121211)
private val NightEdge = Color(0xFF1E1D1A)
private val NightInk = Color(0xFFEBE7DD)
private val NightMuted = Color(0xFF948E83)
private val NightRule = Color(0xFF35322C)

private val LightScheme = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    secondary = Muted,
    onSecondary = Paper,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperEdge,
    onSurfaceVariant = Muted,
    outline = Rule,
    outlineVariant = Rule,
    error = Color(0xFF8C2F2F),
    onError = Paper
)

private val DarkScheme = darkColorScheme(
    primary = NightInk,
    onPrimary = NightPaper,
    secondary = NightMuted,
    onSecondary = NightPaper,
    background = NightPaper,
    onBackground = NightInk,
    surface = NightPaper,
    onSurface = NightInk,
    surfaceVariant = NightEdge,
    onSurfaceVariant = NightMuted,
    outline = NightRule,
    outlineVariant = NightRule,
    error = Color(0xFFD98A8A),
    onError = NightPaper
)

// The web screens are set in a serif face; the nearest thing every Android
// device has is its own serif, so headings follow suit and body text stays on
// the system sans where it reads better at small sizes.
private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        letterSpacing = 1.5.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp
    )
)

@Composable
fun KindleIdleTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AppTypography,
        content = content
    )
}
