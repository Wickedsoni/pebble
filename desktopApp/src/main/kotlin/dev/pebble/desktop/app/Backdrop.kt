package dev.pebble.desktop.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.pebble.desktop.ui.LocalGlass
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** The window background (your wallpaper) and a pre-blurred copy that glass cards sample. */
@Immutable
class Backdrop(val sharp: ImageBitmap, val blurred: ImageBitmap)

val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

object Wallpaper {
    /** Windows keeps a decoded copy of the current wallpaper here; fall back to the registry path. */
    fun bytes(): ByteArray? {
        val appData = System.getenv("APPDATA")
        val candidates = listOfNotNull(
            appData?.let { File(it, "Microsoft\\Windows\\Themes\\TranscodedWallpaper") },
            registryPath()?.let(::File),
        )
        return candidates.firstOrNull { it.isFile && it.length() > 0 }?.let { runCatching { it.readBytes() }.getOrNull() }
    }

    private fun registryPath(): String? = runCatching {
        com.sun.jna.platform.win32.Advapi32Util.registryGetStringValue(
            com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER, "Control Panel\\Desktop", "WallPaper",
        )
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Renders the chosen [Scene] (or your wallpaper, cover-scaled) to [w]×[h] px, plus
     * a heavily blurred copy. Done once per window size, off the UI thread — glass then costs
     * nothing per frame.
     */
    fun build(w: Int, h: Int, dark: Boolean, scene: Scene = Scene.AUTO): Backdrop {
        val sharpSurface = Surface.makeRasterN32Premul(w, h)
        val canvas = sharpSurface.canvas
        val image = if (scene == Scene.WALLPAPER) bytes()?.let { runCatching { Image.makeFromEncoded(it) }.getOrNull() } else null
        if (image != null) drawCover(canvas, image, w, h) else drawScene(canvas, scene.resolve(), w, h, dark)
        val sharp = sharpSurface.makeImageSnapshot()

        val blurSurface = Surface.makeRasterN32Premul(w, h)
        val blurPaint = Paint().apply { imageFilter = ImageFilter.makeBlur(36f, 36f, FilterTileMode.CLAMP) }
        blurSurface.canvas.drawImage(sharp, 0f, 0f, blurPaint)
        return Backdrop(sharp.toComposeImageBitmap(), blurSurface.makeImageSnapshot().toComposeImageBitmap())
    }

    private fun drawCover(canvas: Canvas, image: Image, w: Int, h: Int) {
        val scale = max(w.toFloat() / image.width, h.toFloat() / image.height)
        val sw = w / scale
        val sh = h / scale
        val sx = (image.width - sw) / 2f
        val sy = (image.height - sh) / 2f
        canvas.drawImageRect(image, Rect.makeXYWH(sx, sy, sw, sh), Rect.makeWH(w.toFloat(), h.toFloat()), SamplingMode.LINEAR, null, true)
    }
}

/**
 * Glassmorphism card: a blurred view of the backdrop behind it, a translucent tint, a top-left
 * light reflection and a hairline border that's brighter where the light hits.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    corner: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalGlass.current
    val backdrop = LocalBackdrop.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    Column(
        modifier
            .onGloballyPositioned { origin = it.positionInWindow() }
            .drawBehind {
                val r = CornerRadius(corner.toPx())
                val shape = Path().apply { addRoundRect(RoundRect(size.toRect(), r)) }
                clipPath(shape) {
                    backdrop?.blurred?.let { img ->
                        val x = origin.x.roundToInt().coerceIn(0, img.width - 1)
                        val y = origin.y.roundToInt().coerceIn(0, img.height - 1)
                        val w = size.width.roundToInt().coerceAtMost(img.width - x)
                        val h = size.height.roundToInt().coerceAtMost(img.height - y)
                        if (w > 0 && h > 0) drawImage(img, IntOffset(x, y), IntSize(w, h), dstSize = IntSize(w, h))
                    }
                    drawRect(c.tint)
                    drawRect(Brush.linearGradient(0f to c.reflection, 0.55f to Color.Transparent, end = Offset(size.width * 0.8f, size.height)))
                }
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = if (c.dark) 0.28f else 0.85f), Color.White.copy(alpha = if (c.dark) 0.06f else 0.35f))),
                    cornerRadius = r, style = Stroke(1.dp.toPx()),
                )
            }
            .padding(padding),
        content = content,
    )
}
