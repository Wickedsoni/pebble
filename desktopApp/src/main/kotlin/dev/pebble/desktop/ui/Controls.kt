package dev.pebble.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Cursor

private val Hand = PointerIcon(Cursor(Cursor.HAND_CURSOR))

/** Tap handling with a small press-down scale, shared by all glass controls. */
@Composable
fun Modifier.pressable(onClick: () -> Unit): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val s by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.5f, stiffness = 800f))
    return this.pointerHoverIcon(Hand)
        .pointerInput(onClick) {
            detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }, onTap = { onClick() })
        }
        .scale(s)
}

/** Round icon-ish button; [filled] uses the accent colour. */
@Composable
fun CircleButton(label: String, filled: Boolean = false, size: Dp = 30.dp, onClick: () -> Unit) {
    val c = LocalGlass.current
    Box(
        Modifier.pressable(onClick).size(size).clip(CircleShape).background(if (filled) c.accent else c.well),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (filled) Color.White else c.content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Selectable pill; used for pickers in the hub. */
@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalGlass.current
    val bg by animateColorAsState(if (selected) c.accent else c.well)
    Text(
        label,
        color = if (selected) Color.White else c.content,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier.pressable(onClick)
            .drawBehind { drawRoundRect(bg, cornerRadius = CornerRadius(size.height / 2)) }
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

/** iOS-style switch. */
@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalGlass.current
    val track by animateColorAsState(if (on) c.accent else c.well)
    val knob by animateDpAsState(if (on) 16.dp else 2.dp, spring(dampingRatio = 0.7f))
    Box(
        Modifier.pressable { onChange(!on) }.size(36.dp, 22.dp)
            .drawBehind { drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2)) },
    ) {
        Box(Modifier.padding(start = knob, top = 2.dp).size(18.dp).clip(CircleShape).background(Color.White))
    }
}
