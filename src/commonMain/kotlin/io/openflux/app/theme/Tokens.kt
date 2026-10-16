package io.openflux.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The colours of the interface: the 2.x palette (deep navy, one blue accent) made richer: the accent runs into
 * azure on what it fills, and glows a little under what matters.
 */
@Immutable
data class Colors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    /** The side column and the bottom bar: a step away from the page. */
    val sidebar: Color,
    val border: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    /** What the accent fills (buttons, the chosen place); white text sits on it. */
    val accent: Color,
    /** Where the accent's gradient ends. */
    val accentEnd: Color,
    /** The accent as text or an icon on the page: brighter than [accent] on a dark page. */
    val accentText: Color,
    /** A faint wash of the accent: the chosen row, a tonal button. */
    val accentSoft: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val scrim: Color,
)

/** The accent's fill: blue into azure, corner to corner. */
val Colors.accentBrush: Brush get() = Brush.linearGradient(listOf(accent, accentEnd))

/** The light under a filled accent. */
val Colors.glow: Color get() = accent.copy(alpha = if (isDark) 0.55f else 0.35f)

val DarkColors = Colors(
    isDark = true,
    background = Color(0xFF070A12),
    surface = Color(0xFF121725),
    surfaceHigh = Color(0xFF1B2234),
    sidebar = Color(0xFF0B0F1A),
    border = Color(0xFF242D42),
    text = Color(0xFFF4F6FB),
    textSecondary = Color(0xFF97A0B5),
    textMuted = Color(0xFF5E677C),
    accent = Color(0xFF3B68FA),
    accentEnd = Color(0xFF2C86F0),
    accentText = Color(0xFF7D9BFF),
    accentSoft = Color(0x293B68FA),
    onAccent = Color(0xFFFFFFFF),
    success = Color(0xFF34D399),
    warning = Color(0xFFFBBF24),
    danger = Color(0xFFF28B82),
    scrim = Color(0x99000000),
)

val LightColors = Colors(
    isDark = false,
    background = Color(0xFFF4F6FB),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFECF0FA),
    sidebar = Color(0xFFEDF0F8),
    border = Color(0xFFDCE1EC),
    text = Color(0xFF141926),
    textSecondary = Color(0xFF5A6275),
    textMuted = Color(0xFF8A91A3),
    accent = Color(0xFF2F5BEA),
    accentEnd = Color(0xFF1C6FD8),
    accentText = Color(0xFF2F5BEA),
    accentSoft = Color(0x1A2F5BEA),
    onAccent = Color(0xFFFFFFFF),
    success = Color(0xFF13804A),
    warning = Color(0xFF8F6400),
    danger = Color(0xFFC9352B),
    scrim = Color(0x66000000),
)

@Immutable
data class Type(
    val screenTitle: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val caption: TextStyle,
    /** A group's small heading in capitals: ТРАНСПОРТЫ, ПАРАМЕТРЫ. */
    val label: TextStyle,
    val mono: TextStyle,
)

private fun type(touch: Boolean): Type {
    val bump = if (touch) 1 else 0
    return Type(
        screenTitle = TextStyle(fontSize = (24 + bump).sp, lineHeight = (30 + bump).sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        title = TextStyle(fontSize = (16 + bump).sp, lineHeight = (22 + bump).sp, fontWeight = FontWeight.SemiBold),
        body = TextStyle(fontSize = (14 + bump).sp, lineHeight = (20 + bump).sp),
        bodySmall = TextStyle(fontSize = (13 + bump).sp, lineHeight = (18 + bump).sp),
        caption = TextStyle(fontSize = (12 + bump).sp, lineHeight = (16 + bump).sp, fontWeight = FontWeight.Medium),
        label = TextStyle(fontSize = (11 + bump).sp, lineHeight = (16 + bump).sp, fontWeight = FontWeight.Bold, letterSpacing = 0.9.sp),
        mono = TextStyle(fontSize = (13 + bump).sp, lineHeight = (18 + bump).sp, fontFamily = FontFamily.Monospace),
    )
}

@Immutable
data class Spacing(
    val xs: Dp = 4.dp, val s: Dp = 8.dp, val m: Dp = 12.dp, val l: Dp = 16.dp,
    val xl: Dp = 24.dp, val xxl: Dp = 32.dp, val page: Dp = 16.dp,
)

/** Corner radii. */
@Immutable
data class Shapes(val small: Dp = 10.dp, val medium: Dp = 14.dp, val large: Dp = 22.dp)

val MinTouch: Dp = 48.dp

private val LocalColors = staticCompositionLocalOf { DarkColors }
private val LocalType = staticCompositionLocalOf { type(false) }
private val LocalSpacing = staticCompositionLocalOf { Spacing() }
private val LocalShapes = staticCompositionLocalOf { Shapes() }
private val LocalTouch = staticCompositionLocalOf { false }

object AppTheme {
    val colors: Colors @Composable get() = LocalColors.current
    val type: Type @Composable get() = LocalType.current
    val spacing: Spacing @Composable get() = LocalSpacing.current
    val shapes: Shapes @Composable get() = LocalShapes.current
    val touch: Boolean @Composable get() = LocalTouch.current
}

/** A row's minimum height: a finger needs more than a pointer. */
@Composable
fun rowMinHeight(): Dp = if (AppTheme.touch) 56.dp else 44.dp

@Composable
fun AppTheme(dark: Boolean, touch: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accent, onPrimary = c.onAccent,
        primaryContainer = c.accentSoft, onPrimaryContainer = c.accentText,
        secondaryContainer = c.accentSoft, onSecondaryContainer = c.accentText,
        background = c.background, onBackground = c.text,
        surface = c.surface, onSurface = c.text,
        surfaceVariant = c.surfaceHigh, onSurfaceVariant = c.textSecondary,
        surfaceContainer = c.surface, surfaceContainerHigh = c.surfaceHigh,
        surfaceContainerLow = c.surface, surfaceContainerHighest = c.surfaceHigh,
        inverseSurface = c.text, inverseOnSurface = c.background,
        outline = c.border, outlineVariant = c.border, error = c.danger, scrim = c.scrim,
    )
    CompositionLocalProvider(
        LocalColors provides c,
        LocalType provides type(touch),
        LocalSpacing provides Spacing(),
        LocalShapes provides Shapes(),
        LocalTouch provides touch,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
