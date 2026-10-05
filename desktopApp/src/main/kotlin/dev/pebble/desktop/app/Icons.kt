// SVG path data reads best unbroken.
@file:Suppress("ktlint:standard:max-line-length")

package dev.pebble.desktop.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One consistent line-icon family (24-unit grid, 1.8 stroke, round caps) instead of emoji,
 * per the UI rules: vector, themeable, same stroke and style everywhere.
 */
object PebbleIcons {
    private fun icon(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach {
                addPath(
                    pathData = addPathNodes(it),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 1.8f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Home = icon("home", "M4 11 L12 4 L20 11", "M6 10 V20 H18 V10")
    val Water = icon("water", "M12 3.5 C12 3.5 5.5 10.5 5.5 15 A6.5 6.5 0 0 0 18.5 15 C18.5 10.5 12 3.5 12 3.5 Z")
    val Notes = icon("notes", "M6 4 H14.5 L18 7.5 V20 H6 Z", "M14 4 V8 H18", "M9 12 H15", "M9 16 H13")
    val Bell = icon("bell", "M6.5 16 V11 A5.5 5.5 0 0 1 17.5 11 V16 L19 18 H5 Z", "M10 21 H14")
    val Companion = icon("companion", "M5 7.5 H19 V18.5 H5 Z", "M9.5 11.5 V13.5", "M14.5 11.5 V13.5", "M7.5 18.5 V21", "M16.5 18.5 V21")
    val Memory = icon(
        "memory",
        "M12 3.5 A6 6 0 0 0 8 14 C8.8 14.8 9 15.6 9 16.5 H15 C15 15.6 15.2 14.8 16 14 A6 6 0 0 0 12 3.5 Z",
        "M9.5 19.5 H14.5",
    )
    val Plus = icon("plus", "M12 5 V19", "M5 12 H19")
    val Minus = icon("minus", "M5 12 H19")
    val Close = icon("close", "M6.5 6.5 L17.5 17.5", "M17.5 6.5 L6.5 17.5")
    val Check = icon("check", "M5 12.5 L10 17 L19 7")
    val Minimize = icon("minimize", "M6 12 H18")
    val Search = icon("search", "M10.5 4 A6.5 6.5 0 1 1 10.49 4 Z", "M15.3 15.3 L20 20")
    val Calendar = icon("calendar", "M4.5 6 H19.5 V20 H4.5 Z", "M4.5 10 H19.5", "M8.5 3.5 V7.5", "M15.5 3.5 V7.5")
    val Back = icon("back", "M14.5 6 L8.5 12 L14.5 18")
    val Forward = icon("forward", "M9.5 6 L15.5 12 L9.5 18")
    val Clock = icon("clock", "M12 3.5 A8.5 8.5 0 1 1 11.99 3.5 Z", "M12 7.5 V12 L15 14")
    val Flame =
        icon(
            "flame",
            "M12 3 C13.5 6.5 17.5 8.5 17.5 13.5 A5.5 5.5 0 0 1 6.5 13.5 C6.5 11 8 9.2 9 8.4 C9.2 10 10 11 11 11.2 C11 8.4 11.2 5.5 12 3 Z",
        )
    val Shield = icon("shield", "M12 3.5 L19 6.5 V11.5 C19 16 16 19.2 12 20.5 C8 19.2 5 16 5 11.5 V6.5 Z")
    val Spark =
        icon("spark", "M12 4 V8", "M12 16 V20", "M4 12 H8", "M16 12 H20", "M6.5 6.5 L9 9", "M15 15 L17.5 17.5", "M17.5 6.5 L15 9", "M9 15 L6.5 17.5")
}

@Composable
fun Icon(vector: ImageVector, tint: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Image(vector, contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(tint))
}
