package dev.pebble.desktop.pet

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Draws a character in a calm, flat style into a 200×206 design space scaled to the canvas:
 * a blocky body with a two-tone fill and thin outline, small block eyes, stubby limbs, no mouth.
 * Only plain fills and strokes — no blurs — so a frame is cheap to render.
 */
object PetPainter {
    const val VIEW_W = 200f
    const val VIEW_H = 206f
    private const val GROUND = 186f

    private val ink = Color(0xFF1F1F24)
    private val pathCache = HashMap<String, Path>()

    fun DrawScope.drawPet(character: Character, stage: Stage, pose: PetPose, frame: PetFrame) {
        val k = minOf(size.width / VIEW_W, size.height / VIEW_H)
        translate((size.width - VIEW_W * k) / 2f, size.height - VIEW_H * k) {
            scale(k, k, pivot = Offset.Zero) { drawScene(character, stage, pose, frame) }
        }
    }

    private fun DrawScope.drawScene(c: Character, stage: Stage, pose: PetPose, f: PetFrame) {
        val t = f.time
        var dy = 0f
        var sx = 1f
        var sy = 1f
        var rot = 0f
        when (pose.motion) {
            Motion.HOP -> {
                val p = (t % 0.9f) / 0.9f
                val air = if (p < 0.65f) sin(p / 0.65f * PI.toFloat()) else 0f
                dy = -16f * air
                val land = if (p >= 0.65f) sin((p - 0.65f) / 0.35f * PI.toFloat()) * 0.06f else 0f
                sx = 1f + land
                sy = 1f - land
            }

            Motion.SHAKE -> rot = 2.5f * sin(t * 2f * PI.toFloat() / 0.8f)

            Motion.STILL -> Unit
        }
        sx += 0.07f * f.squash
        sy -= 0.07f * f.squash

        // Ground shadow shrinks while airborne.
        val sw = 56f * (1f - abs(dy) / 50f) * if (stage == Stage.BABY) 0.75f else 1f
        drawOval(Color.Black.copy(alpha = 0.13f), Offset(100f - sw, GROUND - 3f), Size(sw * 2, 9f))

        withTransform({
            translate(0f, dy)
            rotate(rot, Offset(100f, GROUND))
            scale(sx, sy, Offset(100f, GROUND))
            if (stage == Stage.BABY) scale(0.74f, 0.74f, Offset(100f, GROUND))
        }) {
            when (c) {
                Character.PEBBLE -> blocky(c, pose, f, stage)
                Character.MOCHI -> mochi(c, pose, f, stage)
                Character.SPROUT -> sprout(c, pose, f, stage)
                Character.BOLT -> bolt(c, pose, f, stage)
                Character.DRIP -> drip(c, pose, f, stage)
            }
            extras(pose, t)
        }
    }

    // ------------------------------------------------------------------ bodies

    private fun DrawScope.blocky(c: Character, pose: PetPose, f: PetFrame, stage: Stage) {
        for (x in listOf(52f, 78f, 110f, 136f)) leg(c, x, 12f)
        body(c, roundRect(40f, 72f, 160f, 166f, 20f))
        accessory(stage, top = 72f)
        eyes(pose.mood, 112f, f)
        arms(c, pose.arms, 40f, 160f, 112f, f.time)
    }

    private fun DrawScope.mochi(c: Character, pose: PetPose, f: PetFrame, stage: Stage) {
        leg(c, 66f, 18f); leg(c, 116f, 18f)
        val ear = Brush.verticalGradient(listOf(c.base, c.shade), 40f, 90f)
        drawPath(path("M50 92 L56 44 L92 78 Z"), ear)
        drawPath(path("M150 92 L144 44 L108 78 Z"), ear)
        body(c, roundRect(36f, 70f, 164f, 168f, 34f))
        accessory(stage, top = 70f)
        eyes(pose.mood, 114f, f)
        arms(c, pose.arms, 36f, 164f, 116f, f.time)
    }

    private fun DrawScope.sprout(c: Character, pose: PetPose, f: PetFrame, stage: Stage) {
        leg(c, 72f, 14f); leg(c, 114f, 14f)
        drawLine(Color(0xFF5E7A5A), Offset(100f, 56f), Offset(100f, 38f), 4f, StrokeCap.Round)
        drawPath(path("M100 42 C108 28 126 26 132 34 C124 44 108 46 100 42 Z"), Color(0xFF7FA37A))
        body(c, roundRect(54f, 52f, 146f, 168f, 44f))
        accessory(stage, top = 52f, antenna = false)
        eyes(pose.mood, 104f, f, spread = 19f)
        arms(c, pose.arms, 54f, 146f, 116f, f.time)
    }

    private fun DrawScope.drip(c: Character, pose: PetPose, f: PetFrame, stage: Stage) {
        leg(c, 76f, 14f); leg(c, 110f, 14f)
        val drop = path("M100 36 C112 60 162 96 162 128 C162 154 136 170 100 170 C64 170 38 154 38 128 C38 96 88 60 100 36 Z")
        drawPath(drop, Brush.verticalGradient(listOf(c.base, c.shade), 36f, 170f))
        drawPath(drop, c.shade.copy(alpha = 0.5f), style = Stroke(2f))
        accessory(stage, top = 60f, antenna = false)
        eyes(pose.mood, 124f, f, spread = 20f)
        arms(c, pose.arms, 42f, 158f, 132f, f.time)
    }

    private fun DrawScope.bolt(c: Character, pose: PetPose, f: PetFrame, stage: Stage) {
        leg(c, 70f, 16f); leg(c, 114f, 16f)
        drawLine(c.shade, Offset(100f, 70f), Offset(100f, 50f), 4f, StrokeCap.Round)
        drawCircle(Color(0xFFE8A04A), 6f, Offset(100f, 46f))
        body(c, roundRect(42f, 70f, 158f, 166f, 18f))
        accessory(stage, top = 70f, antenna = false)
        // Screen face: eyes glow on a dark panel.
        drawRoundRect(Color(0xFF23272E), Offset(56f, 88f), Size(88f, 52f), CornerRadius(10f))
        val glow = Color(0xFF7FE0E8)
        val dx = f.lookX * 4f
        val dy = f.lookY * 3f
        when (pose.mood) {
            Mood.HAPPY, Mood.CELEBRATE, Mood.LOVE -> for (x in listOf(82f, 118f)) chevron(x + dx, 114f + dy, glow)

            Mood.SLEEPY, Mood.SAD -> for (x in listOf(
                82f,
                118f,
            )) {
                drawLine(glow, Offset(x - 7 + dx, 115f), Offset(x + 7 + dx, 115f), 3.5f, StrokeCap.Round)
            }

            else -> for (x in listOf(82f, 118f)) {
                val h = 16f * f.blink.coerceIn(0.12f, 1f)
                drawRoundRect(glow, Offset(x - 4.5f + dx, 114f - h / 2 + dy), Size(9f, h), CornerRadius(3f))
            }
        }
        arms(c, pose.arms, 42f, 158f, 114f, f.time)
    }

    // ------------------------------------------------------------------ parts

    private fun DrawScope.body(c: Character, shape: Path) {
        val b = shape.getBounds()
        drawPath(shape, Brush.verticalGradient(0f to c.base, 0.7f to c.base, 1f to c.shade, startY = b.top, endY = b.bottom))
        drawPath(shape, c.shade.copy(alpha = 0.55f), style = Stroke(2f))
    }

    private fun DrawScope.leg(c: Character, x: Float, w: Float) {
        drawRoundRect(c.shade, Offset(x, 150f), Size(w, GROUND - 150f), CornerRadius(4f))
    }

    private fun DrawScope.nub(cx: Float, cy: Float, rot: Float, c: Character) = rotate(rot, Offset(cx, cy)) {
        drawRoundRect(c.base, Offset(cx - 7f, cy - 10f), Size(14f, 20f), CornerRadius(6f))
        drawRoundRect(c.shade.copy(alpha = 0.55f), Offset(cx - 7f, cy - 10f), Size(14f, 20f), CornerRadius(6f), style = Stroke(1.5f))
    }

    private fun DrawScope.arms(c: Character, arms: Arms, left: Float, right: Float, y: Float, t: Float) {
        val l = left - 4f
        val r = right + 4f
        when (arms) {
            Arms.DOWN -> { nub(l, y + 6f, 0f, c); nub(r, y + 6f, 0f, c) }

            Arms.UP -> { nub(l - 4f, y - 24f, -30f, c); nub(r + 4f, y - 24f, 30f, c) }

            Arms.WAVE -> {
                nub(l, y + 6f, 0f, c)
                rotate(18f * sin(t * 2f * PI.toFloat() / 0.8f), Offset(r, y)) { nub(r + 4f, y - 24f, 30f, c) }
            }

            Arms.HOLD -> {
                nub(l, y + 6f, 0f, c)
                waterGlass(r - 2f, y - 4f)
                nub(r - 6f, y + 10f, -40f, c)
            }

            Arms.HUG -> { nub(left + 16f, y + 20f, 60f, c); nub(right - 16f, y + 20f, -60f, c) }
        }
    }

    private fun DrawScope.eyes(mood: Mood, y: Float, f: PetFrame, spread: Float = 22f) {
        val dx = f.lookX * 3f
        val dy = f.lookY * 2f
        val l = 100f - spread + dx
        val r = 100f + spread + dx
        val ey = y + dy
        when (mood) {
            Mood.HAPPY, Mood.CELEBRATE, Mood.LOVE -> { chevron(l, ey, ink); chevron(r, ey, ink) }

            Mood.SLEEPY -> for (x in listOf(l, r)) drawLine(ink, Offset(x - 7f, ey), Offset(x + 7f, ey), 4f, StrokeCap.Round)

            Mood.SAD -> {
                drawLine(ink, Offset(l - 7f, ey + 3f), Offset(l + 7f, ey - 1f), 4f, StrokeCap.Round)
                drawLine(ink, Offset(r - 7f, ey - 1f), Offset(r + 7f, ey + 3f), 4f, StrokeCap.Round)
            }

            Mood.WORKING -> for (x in listOf(l, r)) drawLine(ink, Offset(x - 7f, ey), Offset(x + 7f, ey), 5f, StrokeCap.Butt)

            else -> {
                val h = (if (mood == Mood.WORRIED) 12f else 17f) * f.blink.coerceIn(0.12f, 1f)
                for (x in listOf(l, r)) drawRoundRect(ink, Offset(x - 5f, ey - h / 2), Size(10f, h), CornerRadius(3.5f))
            }
        }
    }

    private fun DrawScope.chevron(x: Float, y: Float, color: Color) {
        val p = Path().apply { moveTo(x - 7f, y + 3f); lineTo(x, y - 4f); lineTo(x + 7f, y + 3f) }
        drawPath(p, color, style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private fun DrawScope.waterGlass(x: Float, y: Float) = translate(x, y) {
        val cup = path("M-9 -14 L9 -14 L7 10 L-7 10 Z")
        drawPath(cup, Color.White.copy(alpha = 0.35f))
        drawPath(path("M-8 -4 L8 -4 L7 10 L-7 10 Z"), Color(0xFF6FA8DC))
        drawPath(cup, Color(0xFF5A6470), style = Stroke(1.6f))
    }

    // ------------------------------------------------------------------ accessories & extras

    private fun DrawScope.accessory(stage: Stage, top: Float, antenna: Boolean = true) {
        when (stage) {
            Stage.BABY -> Unit

            Stage.TEEN -> if (antenna) {
                drawLine(ink.copy(alpha = 0.7f), Offset(100f, top), Offset(104f, top - 16f), 3f, StrokeCap.Round)
                drawCircle(ink.copy(alpha = 0.7f), 4f, Offset(104f, top - 18f))
            }

            Stage.ADULT -> drawRoundRect(Color(0xFF5B6B7F), Offset(60f, top - 8f), Size(80f, 10f), CornerRadius(5f))

            Stage.LEGENDARY -> translate(76f, top - 22f) {
                val crown = path("M0 20 L4 4 L14 13 L24 0 L34 13 L44 4 L48 20 Z")
                drawPath(crown, Color(0xFFE2B84A))
                drawPath(crown, Color(0xFFB8902F), style = Stroke(1.6f, join = StrokeJoin.Round))
            }
        }
    }

    private fun DrawScope.extras(pose: PetPose, t: Float) {
        val muted = Color(0xFF8A8F98)
        for (e in pose.extras) {
            when (e) {
                Extra.ZZZ -> {
                    zee(148f, 66f, 14f, muted)
                    zee(164f, 48f, 10f, muted.copy(alpha = 0.7f))
                }

                Extra.SWEAT -> drawPath(path("M154 76 C156 82 160 85 160 89 a6 6 0 0 1 -12 0 c0 -4 4 -7 6 -13z"), Color(0xFF8FC1E3))

                Extra.HARDHAT -> {
                    drawPath(path("M58 72 Q100 30 142 72 Z"), Color(0xFFE6B84A))
                    drawRoundRect(Color(0xFFCF9F33), Offset(50f, 68f), Size(100f, 8f), CornerRadius(4f))
                }

                Extra.BANG -> {
                    drawCircle(Color(0xFFD9534F), 11f, Offset(170f, 60f))
                    drawRoundRect(Color.White, Offset(168f, 52f), Size(4f, 10f), CornerRadius(2f))
                    drawCircle(Color.White, 2.2f, Offset(170f, 66.5f))
                }

                Extra.SPARKLES -> {
                    sparkle(34f, 62f, 0.8f, t)
                    sparkle(168f, 52f, 0.7f, t + 0.6f)
                }

                Extra.HEART -> translate(150f, 58f) {
                    drawPath(path("M0 6 C-9 -3 -4 -10 0 -5 C4 -10 9 -3 0 6Z"), Color(0xFFD9707F))
                }
            }
        }
    }

    private fun DrawScope.zee(x: Float, y: Float, s: Float, color: Color) {
        val p = Path().apply { moveTo(x, y - s); lineTo(x + s * 0.8f, y - s); lineTo(x, y); lineTo(x + s * 0.8f, y) }
        drawPath(p, color, style = Stroke(s * 0.17f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    private fun DrawScope.sparkle(x: Float, y: Float, s: Float, t: Float) {
        val p = (sin(t * 2f * PI.toFloat() / 1.2f) + 1f) / 2f
        val sc = s * (0.7f + 0.4f * p)
        withTransform({ translate(x, y); scale(sc, sc, Offset.Zero) }) {
            drawPath(path("M0 -10 L2 -2 L10 0 L2 2 L0 10 L-2 2 L-10 0 L-2 -2 Z"), Color(0xFFE2B84A))
        }
    }

    // ------------------------------------------------------------------ primitives

    private fun path(d: String): Path = pathCache.getOrPut(d) { PathParser().parsePathString(d).toPath() }

    private fun roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float): Path =
        pathCache.getOrPut("rr$l,$t,$r,$b,$radius") {
            Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(l, t, r, b), CornerRadius(radius))) }
        }
}
