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
import dev.pebble.desktop.pet.ReminderPresenter
import dev.pebble.desktop.platform.Autostart
import dev.pebble.desktop.platform.GlobalHotkey
import dev.pebble.desktop.platform.MediaWatcher
import dev.pebble.desktop.platform.SingleInstance
import dev.pebble.desktop.platform.UserActivity
import dev.pebble.desktop.quickadd.QuickAddWindow
import dev.pebble.desktop.ui.rememberSystemDarkTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

private const val PET = "pet"

/** `--background` (used by "Start with Windows") starts with just the pet; a normal launch opens the app. */
fun main(args: Array<String>) {
    val dataDir = DatabaseFactory.defaultDataDir()
    if (!SingleInstance.acquire(dataDir)) {
        // Pebble runs already, maybe hidden (Start with Windows). A normal launch asks it to open its window.
        if ("--background" !in args) SingleInstance.signalFirst(dataDir)
        exitProcess(0)
    }
    val app = try {
        PebbleApp.create()
    } catch (e: RestoreNotFinishedException) {
        // The packaged app has no console: tell the user, then stop. Nothing is deleted; the next start tries again.
        javax.swing.JOptionPane.showMessageDialog(
            null,
            "Pebble could not finish restoring a backup, so it did not start.\n\n" +
                "Your old data is safe in this folder, in the files named pebble.db.restoring-*:\n${e.dataDir}\n\n" +
                "Start Pebble again. If this message comes back, ask for help and keep these files.",
            "Pebble",
            javax.swing.JOptionPane.ERROR_MESSAGE,
        )
        exitProcess(1)
    }
    val startInBackground = "--background" in args

    application {
        val dark = rememberSystemDarkTheme(app.env.dispatchers.io)
        val trayState = rememberTrayState()

        var petVisible by remember { mutableStateOf(app.layouts.get(PET)?.visible ?: true) }
        var autostart by remember { mutableStateOf(Autostart.isEnabled()) }
        var quickAddOpen by remember { mutableStateOf(false) }
        val talkScope = rememberCoroutineScope()
        var quickAddRetry by remember { mutableStateOf<dev.pebble.desktop.quickadd.QuickAddRetry?>(null) }
        var appOpen by remember { mutableStateOf(!startInBackground) }
        var page by remember { mutableStateOf(Page.TODAY) }
        var quitting by remember { mutableStateOf(false) }

        val pet = remember {
            PetController(app, openQuickAdd = { quickAddOpen = true }, openApp = { appOpen = true; page = Page.TODAY })
        }
        LaunchedEffect(Unit) { app.petLines.collect { pet.react(it) } }
        // Reminders reach you with or without the pet window (hidden: a toast; shown: the bubble). Main thread:
        // the ReminderEngine is not thread-safe.
        LaunchedEffect(Unit) {
            app.startPresenter(
                ReminderPresenter(
                    engine = app.engine,
                    ui = app.ui,
                    env = app.env,
                    petVisible = { petVisible },
                    quiet = {
                        val fg = UserActivity.foreground()
                        UserActivity.isFullscreenBusy() || fg.coversScreen || fg.title?.let { MediaWatcher.match(it) } != null
                    },
                    readEarned = pet::earnedStage,
                    onEarned = pet::onEarned,
                    work = app.workDispatcher,
                    log = app.log,
                ),
            )
        }
        // Grows at each "show Pebble" signal: the window restores and raises itself, also when it is open already.
        var raise by remember { mutableStateOf(0) }

        fun showPage(name: String) {
            page = Page.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Page.TODAY
            appOpen = true
        }

        // Once: application {} recomposes on every UI change; the callbacks only touch remembered state.
        LaunchedEffect(Unit) {
            app.bindUi(
                object : dev.pebble.desktop.core.UiPort {
                    override fun notify(title: String, message: String) =
                        trayState.sendNotification(Notification(title, message, Notification.Type.Info))

                    override fun openPage(page: String) = showPage(page)
                },
            )
            // A second launch (Start menu, shortcut) sets a Win32 event; then this window opens. After bindUi, so
            // the signal is not lost. The waiter runs on its own thread: move to the Swing thread to open the page.
            // An open window keeps its page; a closed one opens on Today.
            SingleInstance.listen {
                SwingUtilities.invokeLater {
                    if (!appOpen) page = Page.TODAY
                    appOpen = true
                    raise++
                }
            }?.let { waiter ->
                // Fails only when the JVM is already stopping; the waiter is a daemon thread, so that is safe.
                runCatching { Runtime.getRuntime().addShutdownHook(Thread({ waiter.stop() }, "pebble-show-signal-stop")) }
            }
        }
        LaunchedEffect(Unit) {
            GlobalHotkey(
                GlobalHotkey.MOD_CONTROL or GlobalHotkey.MOD_ALT,
                GlobalHotkey.VK_SPACE,
                onRegistered = { ok ->
                    if (!ok) {
                        app.log.warn("hotkey", "Ctrl+Alt+Space is already used by another app; Quick Add works from the tray only")
                        SwingUtilities.invokeLater { app.ui.notify("Pebble", "Ctrl+Alt+Space is used by another app") }
                    }
                },
            ) {
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
            app.bus.publish(PebbleEvent.WidgetVisibilityChanged(PET, v, app.now()))
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
                    // Hide every window first, then save (up to 2 s) off the UI thread: the UI never looks frozen.
                    quitting = true
                    talkScope.launch {
                        withContext(app.env.dispatchers.io) { app.shutdown() }
                        exitApplication()
                    }
                })
            },
        )

        if (petVisible && !quitting) PetWindows(pet, dark)

        PebbleWindow(
            app, pet,
            visible = appOpen && !quitting,
            raise = raise,
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
            quickAddOpen && !quitting,
            dark,
            quickAddRetry,
            onRetry = {
                quickAddRetry = it
                quickAddOpen = true
            },
            onPet = pet::react,
        ) {
            quickAddOpen = false
            quickAddRetry = null
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
