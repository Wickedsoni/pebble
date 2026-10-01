package dev.pebble.desktop.quickadd

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import dev.pebble.core.quickadd.QuickAddParser
import dev.pebble.desktop.PebbleApp
import dev.pebble.desktop.PetLine
import dev.pebble.desktop.platform.UserActivity
import dev.pebble.desktop.platform.WindowsEffects
import dev.pebble.desktop.ui.FrostedPanel
import dev.pebble.desktop.ui.LocalGlass
import dev.pebble.desktop.ui.glassColors
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

private val SIZE = DpSize(600.dp, 118.dp)

/** Spotlight-style bar: type naturally, see what it will do, press Enter. Closes on Esc or focus loss. */
@Composable
fun QuickAddWindow(app: PebbleApp, visible: Boolean, dark: Boolean, onDone: (PetLine?) -> Unit) {
    if (!visible) return
    val area = remember { UserActivity.workArea() }
    val state = rememberDialogState(
        position = WindowPosition((area.x + (area.width - SIZE.width.value) / 2).dp, (area.y + area.height * 0.22f).dp),
        size = SIZE,
    )
    DialogWindow(
        onCloseRequest = { onDone(null) },
        state = state,
        title = "Pebble Quick Add",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
    ) {
        var text by remember { mutableStateOf("") }
        val focus = remember { FocusRequester() }
        val command = remember(text) { QuickAddParser.parse(text) }

        LaunchedEffect(Unit) {
            WindowsEffects.roundCorners(window)
            window.toFront()
            window.requestFocus()
            focus.requestFocus()
        }
        DisposableEffect(window) {
            val listener = object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent?) = onDone(null)
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }

        CompositionLocalProvider(LocalGlass provides glassColors(dark)) {
            val colors = LocalGlass.current
            FrostedPanel {
                Column(Modifier.fillMaxSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        dev.pebble.desktop.app.Icon(dev.pebble.desktop.app.PebbleIcons.Spark, colors.accent, 24.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.fillMaxWidth()) {
                            if (text.isEmpty()) Text(
                                "remind me to call mom at 7pm · remember …",
                                color = colors.secondary, fontSize = 20.sp,
                            )
                            BasicTextField(
                                text, { text = it },
                                singleLine = true,
                                textStyle = TextStyle(color = colors.content, fontSize = 20.sp),
                                cursorBrush = SolidColor(colors.accent),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (e.key) {
                                        Key.Escape -> { onDone(null); true }
                                        Key.Enter, Key.NumPadEnter -> { onDone(command?.let(app::execute)); true }
                                        else -> false
                                    }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        command?.let { app.describe(it) + "   ↵" } ?: "Esc to close",
                        color = if (command != null) colors.accent else colors.secondary,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
