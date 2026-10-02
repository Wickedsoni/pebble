package dev.pebble.desktop.pet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import dev.pebble.desktop.pet.PetPainter.drawPet
import dev.pebble.desktop.platform.UserActivity
import dev.pebble.desktop.ui.LocalGlass
import dev.pebble.desktop.ui.glassColors
import dev.pebble.desktop.ui.pressable
import kotlinx.coroutines.delay
import java.awt.Cursor

val PET_SIZE = DpSize(96.dp, 100.dp)
private val BUBBLE_SIZE = DpSize(260.dp, 140.dp)

/** The always-on-top, unfocusable window the pet lives in, plus its speech bubble. */
@Composable
fun PetWindows(controller: PetController, dark: Boolean) {
    val petState = rememberDialogState(position = WindowPosition(0.dp, 0.dp), size = PET_SIZE)

    // Paced simulation loop: the controller decides how soon it needs the next tick, so a still
    // or sleeping pet costs almost nothing. Compose only redraws when pose/frame/position change.
    LaunchedEffect(controller) {
        var last = System.nanoTime()
        while (true) {
            delay(controller.nextFrameMillis())
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceAtMost(0.1f)
            last = now
            controller.update(dt, PET_SIZE.width.value, PET_SIZE.height.value)
            if (!controller.x.isNaN()) {
                val p = WindowPosition(controller.x.dp, controller.y.dp)
                if (petState.position != p) petState.position = p
            }
        }
    }

    DialogWindow(
        onCloseRequest = {},
        state = petState,
        visible = !controller.hidden && !controller.x.isNaN(),
        title = "Pebble Pet",
        undecorated = true,
        transparent = true,
        resizable = false,
        focusable = false,
        alwaysOnTop = true,
    ) {
        Canvas(
            Modifier.fillMaxSize()
                .pointerHoverIcon(PointerIcon(Cursor(Cursor.HAND_CURSOR)))
                .pointerInput(controller) {
                    detectTapGestures(onTap = { controller.onClick() }, onDoubleTap = { controller.onDoubleClick() })
                }
                .pointerInput(controller) {
                    detectDragGestures(
                        onDragStart = { controller.onDragStart() },
                        onDrag = { change, _ -> change.consume(); controller.onDrag() },
                        onDragEnd = { controller.onDragEnd() },
                        onDragCancel = { controller.onDragEnd() },
                    )
                },
        ) {
            drawPet(controller.character, controller.stage, controller.pose, controller.frame)
        }
    }

    SpeechBubble(controller, dark)
}

@Composable
private fun SpeechBubble(controller: PetController, dark: Boolean) {
    val speech = controller.speech
    var shown by remember { mutableStateOf<Speech?>(null) }
    val visibleState = remember { MutableTransitionState(false) }
    if (speech != null) shown = speech
    visibleState.targetState = speech != null && !controller.hidden

    val windowVisible = visibleState.currentState || visibleState.targetState
    val state = rememberDialogState(size = BUBBLE_SIZE)
    // Keep the bubble on screen near the edges; the tail shifts so it still points at the pet.
    var tailShift = 0f
    if (!controller.x.isNaN()) {
        val area = remember { UserActivity.workArea() }
        val wanted = controller.x + PET_SIZE.width.value / 2f - BUBBLE_SIZE.width.value / 2f
        val x = wanted.coerceIn(area.x.toFloat(), (area.x + area.width) - BUBBLE_SIZE.width.value)
        tailShift = (wanted - x).coerceIn(-BUBBLE_SIZE.width.value / 2f + 24f, BUBBLE_SIZE.width.value / 2f - 24f)
        val p = WindowPosition(x.dp, (controller.y - BUBBLE_SIZE.height.value + 10f).dp)
        if (state.position != p) state.position = p
    }

    DialogWindow(
        onCloseRequest = {},
        state = state,
        visible = windowVisible && !controller.x.isNaN(),
        title = "Pebble Says",
        undecorated = true,
        transparent = true,
        resizable = false,
        focusable = false,
        alwaysOnTop = true,
    ) {
        CompositionLocalProvider(LocalGlass provides glassColors(dark)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                AnimatedVisibility(
                    visibleState,
                    enter = scaleIn(
                        spring(dampingRatio = 0.75f, stiffness = 600f),
                        initialScale = 0.85f,
                        transformOrigin = TransformOrigin(0.5f, 1f),
                    ) + fadeIn(tween(120)),
                    exit = fadeOut(tween(150)),
                ) {
                    shown?.let { BubbleCard(it, tailShift) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BubbleCard(speech: Speech, tailShift: Float) {
    val c = LocalGlass.current
    val bg = if (c.dark) Color(0xFF2C2C2E).copy(alpha = 0.96f) else Color.White.copy(alpha = 0.97f)
    val border = if (c.dark) Color.White.copy(alpha = 0.14f) else Color.Black.copy(alpha = 0.10f)
    Column(
        Modifier.fillMaxWidth().wrapContentHeight().padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 12.dp)
            .drawBehind {
                val r = 14.dp.toPx()
                val tail = 7.dp.toPx()
                val cx = size.width / 2f + tailShift.dp.toPx()
                val tailPath = Path().apply {
                    moveTo(cx - tail, size.height - 1f)
                    lineTo(cx, size.height + tail)
                    lineTo(cx + tail, size.height - 1f)
                }
                drawRoundRect(bg, cornerRadius = CornerRadius(r))
                drawRoundRect(border, Offset(0.5f, 0.5f), Size(size.width - 1f, size.height - 1f), CornerRadius(r), style = Stroke(1f))
                drawPath(tailPath, bg)
                drawPath(tailPath, border, style = Stroke(1f))
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            speech.text,
            color = c.content,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            lineHeight = 17.sp,
        )
        if (speech.actions.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                speech.actions.forEachIndexed { i, a -> BubbleButton(a, primary = i == 0) }
            }
        }
    }
}

@Composable
private fun BubbleButton(action: BubbleAction, primary: Boolean) {
    val c = LocalGlass.current
    val bg = if (primary) c.accent else c.well
    Text(
        action.label,
        color = if (primary) Color.White else c.content,
        maxLines = 1,
        softWrap = false,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .pressable(action.onClick)
            .drawBehind { drawRoundRect(bg, cornerRadius = CornerRadius(size.height / 2)) }
            .padding(horizontal = 11.dp, vertical = 5.dp),
    )
}
