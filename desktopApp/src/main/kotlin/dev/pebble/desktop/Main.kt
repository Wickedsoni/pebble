package dev.pebble.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.PebbleEvent
import dev.pebble.desktop.platform.Autostart
import dev.pebble.desktop.platform.SingleInstance
import dev.pebble.desktop.ui.GlassWidgetWindow
import dev.pebble.desktop.ui.rememberSystemDarkTheme
import dev.pebble.desktop.widgets.ClockWidget
import java.awt.GraphicsEnvironment
import kotlin.system.exitProcess

private const val CLOCK = "clock"
private val CLOCK_SIZE = DpSize(250.dp, 120.dp)

fun main() {
    if (!SingleInstance.acquire(DatabaseFactory.defaultDataDir())) exitProcess(0)
    val app = PebbleApp.create()

    application {
        val dark = rememberSystemDarkTheme()
        var clockVisible by remember { mutableStateOf(app.layouts.get(CLOCK)?.visible ?: true) }
        var keepOnTop by remember { mutableStateOf(false) }
        var autostart by remember { mutableStateOf(Autostart.isEnabled()) }

        fun setClockVisible(visible: Boolean) {
            clockVisible = visible
            app.layouts.setVisible(CLOCK, visible)
            app.bus.publish(PebbleEvent.WidgetVisibilityChanged(CLOCK, visible, now()))
        }

        Tray(
            icon = PebbleTrayIcon,
            tooltip = "Pebble",
            menu = {
                CheckboxItem("Clock", checked = clockVisible, onCheckedChange = ::setClockVisible)
                Separator()
                CheckboxItem("Keep widgets on top", checked = keepOnTop, onCheckedChange = { keepOnTop = it })
                CheckboxItem(
                    if (Autostart.isSupported) "Start with Windows" else "Start with Windows (installed app only)",
                    checked = autostart,
                    enabled = Autostart.isSupported,
                    onCheckedChange = {
                        Autostart.setEnabled(it)
                        autostart = Autostart.isEnabled()
                    },
                )
                Separator()
                Item("Quit Pebble", onClick = {
                    app.shutdown()
                    exitApplication()
                })
            },
        )

        if (clockVisible) {
            GlassWidgetWindow(
                app = app,
                widgetId = CLOCK,
                title = "Pebble Clock",
                size = CLOCK_SIZE,
                defaultPosition = { topRight(CLOCK_SIZE) },
                dark = dark,
                alwaysOnTop = keepOnTop,
            ) { ClockWidget() }
        }
    }
}

private fun topRight(size: DpSize): WindowPosition {
    val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
    return WindowPosition((bounds.x + bounds.width - size.width.value - 32).dp, (bounds.y + 32).dp)
}

/** Placeholder tray icon: a little pebble blob until the real mascot art exists. */
private object PebbleTrayIcon : Painter() {
    override val intrinsicSize = Size(32f, 32f)

    override fun DrawScope.onDraw() {
        drawOval(
            brush = Brush.linearGradient(listOf(Color(0xFF7FD8FF), Color(0xFF0A84FF))),
            topLeft = Offset(size.width * 0.08f, size.height * 0.2f),
            size = Size(size.width * 0.84f, size.height * 0.68f),
        )
        val eye = Size(size.width * 0.1f, size.height * 0.16f)
        drawOval(Color.White, Offset(size.width * 0.34f, size.height * 0.42f), eye)
        drawOval(Color.White, Offset(size.width * 0.56f, size.height * 0.42f), eye)
    }
}
