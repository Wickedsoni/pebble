package dev.pebble.desktop.ui

import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import dev.pebble.core.event.PebbleEvent
import dev.pebble.desktop.PebbleApp
import dev.pebble.desktop.now
import dev.pebble.desktop.platform.WindowsEffects
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterIsInstance
import kotlin.math.roundToInt

/**
 * A frameless, frosted, draggable widget window. Uses a dialog (owned by Swing's hidden frame)
 * so widgets don't show up as taskbar buttons. Its position is saved and restored per [widgetId].
 */
@OptIn(FlowPreview::class)
@Composable
fun GlassWidgetWindow(
    app: PebbleApp,
    widgetId: String,
    title: String,
    size: DpSize,
    defaultPosition: () -> WindowPosition,
    dark: Boolean,
    alwaysOnTop: Boolean = false,
    content: @Composable () -> Unit,
) {
    val initialPosition = remember {
        app.layouts.get(widgetId)?.takeIf { it.hasPosition }
            ?.let { WindowPosition(it.x.dp, it.y.dp) }
            ?: defaultPosition()
    }
    val state = rememberDialogState(position = initialPosition, size = size)

    DialogWindow(
        onCloseRequest = {},
        state = state,
        title = title,
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = alwaysOnTop,
    ) {
        var blurred by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { blurred = WindowsEffects.applyGlass(window) }
        LaunchedEffect(dark) { WindowsEffects.setDarkMode(window, dark) }

        LaunchedEffect(state) {
            snapshotFlow { state.position }
                .filterIsInstance<WindowPosition.Absolute>()
                .drop(1)
                .debounce(400)
                .collect { p ->
                    val x = p.x.value.roundToInt()
                    val y = p.y.value.roundToInt()
                    app.layouts.savePosition(widgetId, x, y)
                    app.bus.publish(PebbleEvent.WidgetMoved(widgetId, x, y, now()))
                }
        }

        CompositionLocalProvider(LocalGlassColors provides glassColors(dark, blurred)) {
            WindowDraggableArea {
                GlassSurface(
                    cornerRadius = (if (blurred) WindowsEffects.SYSTEM_CORNER_DP else GLASS_CORNER_DP).dp,
                ) { content() }
            }
        }
    }
}
