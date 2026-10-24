package app.earful.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Colours follow Apple's semantic system palette (label / secondary label / fill / separator),
 * so contrast ratios stay where Apple tuned them for both light and dark.
 */
@Immutable
data class Palette(
    val bg: Color,
    val bgElevated: Color,
    val grouped: Color,
    val label: Color,
    val secondary: Color,
    val tertiary: Color,
    val fill: Color,
    val separator: Color,
    val accent: Color,
    val chrome: Color, // translucent bars
    val isDark: Boolean,
)

val LightPalette = Palette(
    bg = Color(0xFFFFFFFF),
    bgElevated = Color(0xFFFFFFFF),
    grouped = Color(0xFFF2F2F7),
    label = Color(0xFF000000),
    secondary = Color(0x993C3C43),
    tertiary = Color(0x4D3C3C43),
    fill = Color(0x1F787880),
    separator = Color(0x4A3C3C43),
    accent = Color(0xFFFA2D48),
    chrome = Color(0xF0F9F9F9),
    isDark = false,
)

val DarkPalette = Palette(
    bg = Color(0xFF000000),
    bgElevated = Color(0xFF1C1C1E),
    grouped = Color(0xFF1C1C1E),
    label = Color(0xFFFFFFFF),
    secondary = Color(0x99EBEBF5),
    tertiary = Color(0x4DEBEBF5),
    fill = Color(0x3D787880),
    separator = Color(0x99545458),
    accent = Color(0xFFFF375F),
    chrome = Color(0xEB161618),
    isDark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/**
 * Apple's type ramp (Large Title 34 → Caption 12) with SF-like tracking: big text is pulled
 * tighter, small text is opened up, which is what makes Apple text feel "calm".
 */
object Type {
    private val base = TextStyle(
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    val largeTitle = base.copy(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em)
    val title2 = base.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.015).em)
    val title3 = base.copy(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.012).em)
    val headline = base.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em)
    val body = base.copy(fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.01).em)
    val callout = base.copy(fontSize = 16.sp, lineHeight = 21.sp, letterSpacing = (-0.008).em)
    val subhead = base.copy(fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (-0.006).em)
    val subheadStrong = subhead.copy(fontWeight = FontWeight.SemiBold)
    val footnote = base.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.em)
    val caption = base.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.005.em)
    val captionStrong = caption.copy(fontWeight = FontWeight.SemiBold)
}

/** Motion vocabulary. Springs, never linear: objects should feel like they have mass. */
object Motion {
    /** Sheets & layout: settles fast, no wobble. */
    fun <T> sheet() = spring<T>(dampingRatio = 0.9f, stiffness = 560f)
    /** Artwork breathing on play/pause: a touch of bounce reads as "alive". */
    fun <T> lively() = spring<T>(dampingRatio = 0.62f, stiffness = 260f)
    /** Taps & presses: immediate. */
    fun <T> snappy() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1400f)
}

@Composable
fun EarfulTheme(content: @Composable () -> Unit) {
    val palette = if (isSystemInDarkTheme()) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides palette, content = content)
}
