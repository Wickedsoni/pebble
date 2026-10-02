package dev.pebble.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.PebbleEvent
import dev.pebble.desktop.app.Page
import dev.pebble.desktop.app.PebbleWindow
import dev.pebble.desktop.pet.PetController
import dev.pebble.desktop.pet.PetWindows
import dev.pebble.desktop.platform.Autostart
import dev.pebble.desktop.platform.GlobalHotkey
import dev.pebble.desktop.platform.MediaWatcher
import dev.pebble.desktop.platform.SingleInstance
import dev.pebble.desktop.quickadd.QuickAddWindow
import dev.pebble.desktop.ui.rememberSystemDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

private const val PET = "pet"

/** `--background` (used by "Start with Windows") starts with just the pet; a normal launch opens the app. */
fun main(args: Array<String>) {
    if (!SingleInstance.acquire(DatabaseFactory.defaultDataDir())) exitProcess(0)
    val app = PebbleApp.create()
    val startInBackground = "--background" in args

    application {
        val dark = rememberSystemDarkTheme()
        val trayState = rememberTrayState()
        app.notifier = { title, msg -> trayState.sendNotification(Notification(title, msg, Notification.Type.Info)) }

        var petVisible by remember { mutableStateOf(app.layouts.get(PET)?.visible ?: true) }
        var autostart by remember { mutableStateOf(Autostart.isEnabled()) }
        var quickAddOpen by remember { mutableStateOf(false) }
        val talkScope = rememberCoroutineScope()
        var quickAddRetry by remember { mutableStateOf<dev.pebble.desktop.quickadd.QuickAddRetry?>(null) }
        var appOpen by remember { mutableStateOf(!startInBackground) }
        var page by remember { mutableStateOf(Page.TODAY) }

        val pet = remember {
            PetController(app, openQuickAdd = { quickAddOpen = true }, openApp = { appOpen = true; page = Page.TODAY })
        }
        LaunchedEffect(Unit) { app.petLines.collect { pet.react(it) } }
        app.openPage = { name ->
            page = Page.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Page.TODAY
            appOpen = true
        }
        LaunchedEffect(Unit) {
            GlobalHotkey(GlobalHotkey.MOD_CONTROL or GlobalHotkey.MOD_ALT, GlobalHotkey.VK_SPACE) {
                // Tap: type. Hold (> 300 ms): talk until the keys are released.
                quickAddOpen = true
                talkScope.launch {
                    delay(300)
                    if (!GlobalHotkey.isHeld(GlobalHotkey.VK_SPACE)) return@launch
                    if (!app.voice.start()) return@launch
                    while (GlobalHotkey.isHeld(GlobalHotkey.VK_SPACE)) delay(30)
                    app.voice.stop()
                }
            }.start()
            pet.greet()
        }
        LaunchedEffect(Unit) { MediaWatcher(app).run() }

        fun setPetVisible(v: Boolean) {
            petVisible = v
            app.layouts.setVisible(PET, v)
            app.bus.publish(PebbleEvent.WidgetVisibilityChanged(PET, v, now()))
        }

        fun setAutostart(v: Boolean) {
            Autostart.setEnabled(v)
            autostart = Autostart.isEnabled()
        }

        Tray(
            icon = PebbleTrayIcon,
            state = trayState,
            tooltip = "Pebble",
            onAction = { appOpen = true },
            menu = {
                Item("Open Pebble", onClick = { appOpen = true })
                Item("Quick add…   Ctrl+Alt+Space", onClick = { quickAddOpen = true })
                Separator()
                CheckboxItem("Show companion", checked = petVisible, onCheckedChange = ::setPetVisible)
                CheckboxItem(
                    if (Autostart.isSupported) "Start with Windows" else "Start with Windows (installed app only)",
                    checked = autostart,
                    enabled = Autostart.isSupported,
                    onCheckedChange = ::setAutostart,
                )
                Separator()
                Item("Quit Pebble", onClick = {
                    app.shutdown()
                    exitApplication()
                })
            },
        )

        if (petVisible) PetWindows(pet, dark)

        PebbleWindow(
            app, pet,
            visible = appOpen,
            page = page,
            onPage = { page = it },
            dark = dark,
            petVisible = petVisible,
            onPetVisible = ::setPetVisible,
            autostart = autostart,
            onAutostart = ::setAutostart,
            onClose = { appOpen = false },
        )

        QuickAddWindow(
            app,
            quickAddOpen,
            dark,
            quickAddRetry,
            onRetry = { quickAddRetry = it; quickAddOpen = true },
        ) { line ->
            quickAddOpen = false
            quickAddRetry = null
            line?.let(pet::react)
        }
    }
}

/** Tray icon: the default companion's face. */
private object PebbleTrayIcon : Painter() {
    override val intrinsicSize = Size(32f, 32f)

    override fun DrawScope.onDraw() {
        val w = size.width
        val h = size.height
        drawRoundRect(Color(0xFFD97757), Offset(w * 0.12f, h * 0.2f), Size(w * 0.76f, h * 0.62f), CornerRadius(w * 0.14f))
        drawRect(Color(0xFF1F1F24), Offset(w * 0.32f, h * 0.4f), Size(w * 0.09f, h * 0.17f))
        drawRect(Color(0xFF1F1F24), Offset(w * 0.59f, h * 0.4f), Size(w * 0.09f, h * 0.17f))
    }
}
