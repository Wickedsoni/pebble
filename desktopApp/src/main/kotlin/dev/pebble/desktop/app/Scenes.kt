package dev.pebble.desktop.app

import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.Point
import java.time.LocalTime
import kotlin.random.Random

/**
 * Generated "Aurora UI" backgrounds for the app window: blurred colour fields that blend into a
 * mesh gradient, a few flowing light ribbons, a soft vignette and fine grain. Rendered once per
 * window open (static, so no battery cost), then blurred again for the glass cards.
 */
enum class Scene(val label: String) {
    AUTO("Auto"),
    BLOOM("Bloom"),
    OCEAN("Ocean"),
    DUSK("Dusk"),
    AURORA("Aurora"),
    WALLPAPER("Wallpaper"),
    ;

    /** Auto follows the time of day: soft bloom in the morning, ocean by day, dusk, then aurora at night. */
    fun resolve(now: LocalTime = LocalTime.now()): Scene = if (this != AUTO) this else when (now.hour) {
        in 5..10 -> BLOOM
        in 11..16 -> OCEAN
        in 17..20 -> DUSK
        else -> AURORA
    }
}

private class Palette(val base: Int, val fields: List<Int>, val ribbons: List<Int>)

private fun palette(scene: Scene, dark: Boolean): Palette = when (scene) {
    Scene.BLOOM -> if (dark) Palette(0xFF1A1024.argb, listOf(0xFF7A2E5B, 0xFF3B2A6E, 0xFFB4553F, 0xFF2B1840).argb, listOf(0xFFFF9EC7, 0xFFFFC38A).argb)
    else Palette(0xFFFDE8EF.argb, listOf(0xFFFFB3C9, 0xFFC8B6FF, 0xFFFFD3A8, 0xFFFFE4F1).argb, listOf(0xFFFFFFFF, 0xFFFF8FB8).argb)
    Scene.OCEAN -> if (dark) Palette(0xFF07141F.argb, listOf(0xFF0B4F6C, 0xFF123B7A, 0xFF0E6E6A, 0xFF0A2540).argb, listOf(0xFF5EEAD4, 0xFF60A5FA).argb)
    else Palette(0xFFE6F6FB.argb, listOf(0xFF9BE3F0, 0xFFA5C8FF, 0xFFB8F2E0, 0xFFDDEFFF).argb, listOf(0xFFFFFFFF, 0xFF7DD3FC).argb)
    Scene.DUSK -> if (dark) Palette(0xFF160D1C.argb, listOf(0xFF8A2C3B, 0xFF4A2A7A, 0xFFB45A2A, 0xFF2A1840).argb, listOf(0xFFFFA36C, 0xFFFF5E8A).argb)
    else Palette(0xFFFFEDE2.argb, listOf(0xFFFFB199, 0xFFD9B8FF, 0xFFFFD08A, 0xFFFFC2D4).argb, listOf(0xFFFFFFFF, 0xFFFF9466).argb)
    Scene.AURORA, Scene.AUTO, Scene.WALLPAPER -> if (dark) Palette(0xFF060B18.argb, listOf(0xFF0F2D52, 0xFF2A1452, 0xFF063F3A, 0xFF101A3A).argb, listOf(0xFF34F5C5, 0xFF8B5CF6, 0xFF38BDF8).argb)
    else Palette(0xFFEAF0FF.argb, listOf(0xFFB9D4FF, 0xFFD6C4FF, 0xFFB7F5E3, 0xFFE3ECFF).argb, listOf(0xFFFFFFFF, 0xFF6EE7B7, 0xFFA78BFA).argb)
}

private val Long.argb get() = toInt()
private val List<Long>.argb get() = map { it.toInt() }

fun drawScene(canvas: Canvas, scene: Scene, w: Int, h: Int, dark: Boolean) {
    val p = palette(scene, dark)
    val rnd = Random(scene.ordinal * 7919 + if (dark) 1 else 0) // stable per scene, so it never "jumps"
    val fw = w.toFloat()
    val fh = h.toFloat()

    canvas.drawPaint(Paint().apply { color = p.base })

    // 1) Colour fields: big soft circles that melt into a mesh gradient.
    val anchors = listOf(0.12f to 0.85f, 0.85f to 0.15f, 0.55f to 0.55f, 0.2f to 0.15f, 0.9f to 0.9f)
    anchors.forEachIndexed { i, (ax, ay) ->
        val color = p.fields[i % p.fields.size]
        val r = fh * (0.45f + rnd.nextFloat() * 0.25f)
        canvas.drawCircle(
            fw * ax + (rnd.nextFloat() - 0.5f) * fw * 0.1f, fh * ay + (rnd.nextFloat() - 0.5f) * fh * 0.1f, r,
            Paint().apply {
                this.color = color
                alpha = if (dark) 230 else 200
                imageFilter = ImageFilter.makeBlur(fh * 0.16f, fh * 0.16f, FilterTileMode.DECAL)
            },
        )
    }

    // 2) Aurora ribbons: long flowing curves of light, screen-blended so they glow.
    p.ribbons.forEachIndexed { i, color ->
        val y0 = fh * (0.25f + i * 0.22f + rnd.nextFloat() * 0.08f)
        val path = PathBuilder()
            // Each ribbon gets its own sweep and tilt, so they cross and fan out instead of stacking.
            .moveTo(-fw * 0.1f, y0 + fh * (0.1f + rnd.nextFloat() * 0.3f))
            .cubicTo(
                fw * (0.15f + rnd.nextFloat() * 0.25f), y0 - fh * (0.15f + rnd.nextFloat() * 0.35f),
                fw * (0.5f + rnd.nextFloat() * 0.25f), y0 + fh * (0.1f + rnd.nextFloat() * 0.4f),
                fw * 1.1f, y0 - fh * (rnd.nextFloat() * 0.45f),
            )
            .detach()
        listOf(fh * 0.22f to 0.35f, fh * 0.07f to 0.55f).forEach { (width, a) ->
            canvas.drawPath(path, Paint().apply {
                this.color = color
                alpha = ((if (dark) a else a * 0.7f) * 255).toInt()
                mode = PaintMode.STROKE
                strokeWidth = width
                strokeCap = PaintStrokeCap.ROUND
                blendMode = BlendMode.SCREEN
                imageFilter = ImageFilter.makeBlur(width * 0.6f, width * 0.6f, FilterTileMode.DECAL)
            })
        }
    }

    // 3) Vignette in dark mode keeps the edges calm behind the sidebar and window controls.
    if (dark) {
        val edge = Paint().apply {
            color = 0xFF000000.toInt()
            alpha = 120
            mode = PaintMode.STROKE
            strokeWidth = fh * 0.35f
            imageFilter = ImageFilter.makeBlur(fh * 0.15f, fh * 0.15f, FilterTileMode.DECAL)
        }
        canvas.drawRect(org.jetbrains.skia.Rect.makeWH(fw, fh), edge)
    }

    // 4) Fine grain so large gradients don't band on 8-bit displays.
    val grain = Random(42)
    val points = Array((w * h) / 90) { Point(grain.nextFloat() * fw, grain.nextFloat() * fh) }
    canvas.drawPoints(points, Paint().apply { color = if (dark) 0x0DFFFFFF else 0x0F000000; strokeWidth = 1f })
}
