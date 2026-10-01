package dev.pebble.desktop.ui

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Window

/**
 * Drags [window] when the user drags this element. Unlike Compose's `WindowDraggableArea`,
 * it only reacts once the pointer moves past touch slop, so taps on buttons inside still work.
 * Uses global cursor positions, so moving the window under the pointer doesn't cause jitter.
 */
fun Modifier.dragsWindow(window: Window): Modifier = pointerInput(window) {
    var startWindow = Point()
    var startCursor = Point()
    detectDragGestures(
        onDragStart = {
            startWindow = window.location
            startCursor = MouseInfo.getPointerInfo()?.location ?: Point()
        },
        onDrag = { change, _ ->
            change.consume()
            val c = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
            window.setLocation(startWindow.x + c.x - startCursor.x, startWindow.y + c.y - startCursor.y)
        },
    )
}
