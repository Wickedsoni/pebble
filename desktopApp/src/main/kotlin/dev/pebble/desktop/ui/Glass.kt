package dev.pebble.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.pebble.desktop.platform.SystemTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Semantic colour tokens for text and controls on glass, per theme. */
@Immutable
data class GlassColors(
    val content: Color,
    val secondary: Color,
    val accent: Color,
    /** Fill for inner controls (chips, fields, buttons). */
    val well: Color,
    /** 1px separators and outlines. */
    val hairline: Color,
    /** Glass tint laid over the blurred backdrop. */
    val tint: Color,
    /** Light-source reflection at the top-left of glass. */
    val reflection: Color,
    val dark: Boolean,
    // Feature accents (from the palette search: teal for water, amber for streaks/notes).
    val water: Color,
    val warm: Color,
    val calm: Color,
)

fun glassColors(dark: Boolean) = if (dark) {
    GlassColors(
        content = Color(0xFFF7F7FA),
        secondary = Color(0xFFF7F7FA).copy(alpha = 0.72f),
        accent = Color(0xFF8AB4FF),
        well = Color.White.copy(alpha = 0.10f),
        hairline = Color.White.copy(alpha = 0.16f),
        tint = Color(0xFF0E1118).copy(alpha = 0.42f),
        reflection = Color.White.copy(alpha = 0.10f),
        dark = true,
        water = Color(0xFF5EEAD4),
        warm = Color(0xFFFBBF24),
        calm = Color(0xFFC4B5FD),
    )
} else {
    GlassColors(
        content = Color(0xFF111318),
        secondary = Color(0xFF111318).copy(alpha = 0.68f),
        accent = Color(0xFF2563EB),
        well = Color.White.copy(alpha = 0.45f),
        hairline = Color.White.copy(alpha = 0.7f),
        tint = Color.White.copy(alpha = 0.50f),
        reflection = Color.White.copy(alpha = 0.45f),
        dark = false,
        water = Color(0xFF0D9488),
        warm = Color(0xFFD97706),
        calm = Color(0xFF7C3AED),
    )
}

val LocalGlass = compositionLocalOf { glassColors(dark = false) }

/** Follows the Windows light/dark app setting. Polled rarely — a registry read every 30 s is negligible. */
@Composable
fun rememberSystemDarkTheme(): Boolean {
    var dark by remember { mutableStateOf(SystemTheme.isDark()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            dark = withContext(Dispatchers.IO) { SystemTheme.isDark() }
        }
    }
    return dark
}

/**
 * A frosted panel for small floating windows (quick add) where there is no backdrop to blur:
 * near-opaque tint so text stays readable over anything, plus the reflection and light border.
 */
@Composable
fun FrostedPanel(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    padding: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = LocalGlass.current
    val shape = RoundedCornerShape(cornerRadius)
    val base = if (c.dark) Color(0xFF1B1E26).copy(alpha = 0.97f) else Color(0xFFF6F7FA).copy(alpha = 0.98f)
    Box(
        modifier.fillMaxSize().clip(shape)
            .background(base)
            .background(Brush.linearGradient(0f to c.reflection, 0.5f to Color.Transparent))
            .border(1.dp, c.hairline, shape)
            .padding(padding),
        content = content,
    )
}
