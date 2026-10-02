package dev.pebble.desktop.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pebble.desktop.ui.GlassColors
import dev.pebble.desktop.ui.LocalGlass
import dev.pebble.desktop.ui.pressable

@Composable
fun CardLabel(text: String, icon: ImageVector? = null, tint: Color? = null) {
    val c = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, tint ?: c.secondary, 16.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = c.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp)
    }
    Spacer(Modifier.height(10.dp))
}

/** Round icon button with a 36dp hit area (desktop pointer target). */
@Composable
fun IconButton(icon: ImageVector, filled: Boolean = false, size: Dp = 36.dp, tint: Color? = null, onClick: () -> Unit) {
    val c = LocalGlass.current
    Box(
        Modifier.pressable(onClick).size(size).clip(CircleShape).background(if (filled) tint ?: c.accent else c.well),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, if (filled) Color.White else c.content, size * 0.5f) }
}

/** Single-line input on glass; Enter submits. */
@Composable
fun GlassField(placeholder: String, modifier: Modifier = Modifier, onSubmit: (String) -> Unit) {
    val c = LocalGlass.current
    var text by remember { mutableStateOf("") }
    fun submit() {
        if (text.isNotBlank()) { onSubmit(text.trim()); text = "" }
    }
    Row(
        modifier.clip(RoundedCornerShape(12.dp)).background(c.well).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) Text(placeholder, color = c.secondary, fontSize = 13.sp)
            BasicTextField(
                text,
                { text = it },
                singleLine = true,
                textStyle = TextStyle(color = c.content, fontSize = 13.sp),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && (it.key == Key.Enter || it.key == Key.NumPadEnter)) { submit(); true } else false
                },
            )
        }
        IconButton(PebbleIcons.Plus, filled = true, size = 30.dp) { submit() }
    }
}

@Composable
fun BigStat(value: String, label: String, color: Color? = null) {
    val c = LocalGlass.current
    Column {
        Text(value, color = color ?: c.content, fontSize = 28.sp, fontWeight = FontWeight.Light)
        Text(label, color = c.secondary, fontSize = 12.sp)
    }
}

// ------------------------------------------------------------------ water bottle

/** Glass bottle with cap, reflections, a tick per glass and a gradient water fill. */
fun DrawScope.drawBottle(level: Float, goal: Int, c: GlassColors) {
    val w = size.width
    val h = size.height
    val capW = w * 0.46f
    val capH = h * 0.08f
    val neckW = w * 0.52f
    val neckBottom = capH + h * 0.07f
    val shoulder = h * 0.27f
    val r = w * 0.24f
    val left = 1f
    val right = w - 1f
    val bottom = h - 1f
    val glass = Path().apply {
        moveTo(w / 2 - neckW / 2, capH)
        lineTo(w / 2 + neckW / 2, capH)
        lineTo(w / 2 + neckW / 2, neckBottom)
        cubicTo(right, neckBottom + (shoulder - neckBottom) * 0.25f, right, shoulder - (shoulder - neckBottom) * 0.2f, right, shoulder)
        lineTo(right, bottom - r)
        quadraticTo(right, bottom, right - r, bottom)
        lineTo(left + r, bottom)
        quadraticTo(left, bottom, left, bottom - r)
        lineTo(left, shoulder)
        cubicTo(
            left,
            shoulder - (shoulder - neckBottom) * 0.2f,
            left,
            neckBottom + (shoulder - neckBottom) * 0.25f,
            w / 2 - neckW / 2,
            neckBottom,
        )
        close()
    }
    drawPath(glass, Color.White.copy(alpha = if (c.dark) 0.06f else 0.30f))
    val maxH = bottom - neckBottom - 4f
    val surface = bottom - 3f - maxH * level
    if (level > 0.001f) {
        clipPath(glass) {
            drawRect(
                Brush.verticalGradient(listOf(Color(0xFF7DE3F4), Color(0xFF0EA5A4)), startY = surface, endY = bottom),
                topLeft = Offset(0f, surface),
                size = Size(w, bottom - surface),
            )
            drawLine(Color.White.copy(alpha = 0.6f), Offset(0f, surface + 0.75f), Offset(w, surface + 0.75f), 1.5f)
        }
    }
    val tick = Color.White.copy(alpha = if (c.dark) 0.3f else 0.7f)
    for (i in 1 until goal) {
        val y = bottom - 3f - maxH * i / goal
        if (y > shoulder) drawLine(tick, Offset(right - w * 0.14f, y), Offset(right - w * 0.05f, y), 1.2f)
    }
    drawLine(
        Color.White.copy(alpha = if (c.dark) 0.35f else 0.8f),
        Offset(left + w * 0.1f, shoulder + 6f),
        Offset(
            left + w * 0.1f,
            bottom - r * 0.8f,
        ),
        w * 0.05f,
        StrokeCap.Round,
    )
    drawPath(glass, Color.White.copy(alpha = if (c.dark) 0.45f else 0.85f), style = Stroke(1.6f))
    drawRoundRect(
        if (c.dark) Color(0xFFB0B4BC) else Color(0xFF4B5563),
        topLeft = Offset(w / 2 - capW / 2, 0f),
        size = Size(capW, capH + 1f),
        cornerRadius = CornerRadius(3f),
    )
}

/** Simple 7-day bar chart; the dashed line is the goal. Labels are drawn by the caller. */
fun DrawScope.drawWeekBars(values: List<Int>, goal: Int, c: GlassColors) {
    val max = maxOf(goal, values.maxOrNull() ?: 0).coerceAtLeast(1).toFloat()
    val gap = size.width * 0.04f
    val barW = (size.width - gap * (values.size - 1)) / values.size
    values.forEachIndexed { i, v ->
        val bh = size.height * (v / max)
        val x = i * (barW + gap)
        drawRoundRect(c.well, Offset(x, 0f), Size(barW, size.height), CornerRadius(6f))
        if (bh > 0f) {
            drawRoundRect(
                if (v >= goal) c.water else c.water.copy(alpha = 0.55f),
                Offset(x, size.height - bh),
                Size(barW, bh),
                CornerRadius(6f),
            )
        }
    }
    val gy = size.height * (1 - goal / max)
    var x = 0f
    while (x < size.width) {
        drawLine(c.secondary, Offset(x, gy), Offset(minOf(x + 6f, size.width), gy), 1f)
        x += 12f
    }
}
