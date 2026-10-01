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

const val GLASS_CORNER_DP = 22

@Immutable
data class GlassColors(
    val top: Color,
    val bottom: Color,
    val border: Color,
    val content: Color,
    val secondary: Color,
    val accent: Color,
)

fun glassColors(dark: Boolean, blurred: Boolean): GlassColors {
    // With real acrylic behind us we can be more see-through; without it we need more body.
    val a = if (blurred) 0.55f else 0.86f
    return if (dark) GlassColors(
        top = Color(0xFF3A3A3C).copy(alpha = a),
        bottom = Color(0xFF1C1C1E).copy(alpha = a),
        border = Color.White.copy(alpha = 0.14f),
        content = Color.White,
        secondary = Color.White.copy(alpha = 0.6f),
        accent = Color(0xFF64D2FF),
    ) else GlassColors(
        top = Color.White.copy(alpha = a),
        bottom = Color(0xFFF2F2F7).copy(alpha = a - 0.08f),
        border = Color.White.copy(alpha = 0.7f),
        content = Color(0xFF1C1C1E),
        secondary = Color(0xFF3C3C43).copy(alpha = 0.65f),
        accent = Color(0xFF0A84FF),
    )
}

val LocalGlassColors = compositionLocalOf { glassColors(dark = false, blurred = false) }

/** Follows the Windows light/dark app setting, re-checking every few seconds. */
@Composable
fun rememberSystemDarkTheme(): Boolean {
    var dark by remember { mutableStateOf(SystemTheme.isDark()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            dark = withContext(Dispatchers.IO) { SystemTheme.isDark() }
        }
    }
    return dark
}

/**
 * The frosted card every widget sits on. Fills its window exactly; when the system acrylic is
 * behind it, [cornerRadius] must match the DWM window rounding or light corners show through.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = GLASS_CORNER_DP.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalGlassColors.current
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier
            .fillMaxSize()
            .clip(shape)
            .background(Brush.verticalGradient(listOf(colors.top, colors.bottom)))
            .border(1.dp, colors.border, shape)
            .padding(16.dp),
        content = content,
    )
}
