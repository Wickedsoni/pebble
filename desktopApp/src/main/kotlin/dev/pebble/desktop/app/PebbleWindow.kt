package dev.pebble.desktop.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import dev.pebble.desktop.PebbleApp
import dev.pebble.desktop.app.pages.AboutPage
import dev.pebble.desktop.app.pages.CalendarPage
import dev.pebble.desktop.app.pages.ChatPage
import dev.pebble.desktop.app.pages.CompanionPage
import dev.pebble.desktop.app.pages.MemoryPage
import dev.pebble.desktop.app.pages.NotesPage
import dev.pebble.desktop.app.pages.RemindersPage
import dev.pebble.desktop.app.pages.TodayPage
import dev.pebble.desktop.app.pages.WaterPage
import dev.pebble.desktop.pet.PetController
import dev.pebble.desktop.pet.PetFrame
import dev.pebble.desktop.pet.PetPainter.drawPet
import dev.pebble.desktop.pet.PetPose
import dev.pebble.desktop.platform.WindowsEffects
import dev.pebble.desktop.ui.LocalGlass
import dev.pebble.desktop.ui.dragsWindow
import dev.pebble.desktop.ui.glassColors
import dev.pebble.desktop.ui.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Frame

enum class Page(val title: String, val subtitle: String, val icon: ImageVector) {
    TODAY("Today", "Your day at a glance", PebbleIcons.Home),
    CHAT("Chat", "Everything you've said to Pebble", PebbleIcons.Spark),
    WATER("Water", "Hydration and history", PebbleIcons.Water),
    NOTES("Notes", "Quick thoughts and to-dos", PebbleIcons.Notes),
    REMINDERS("Reminders", "What Pebble nudges you about", PebbleIcons.Bell),
    CALENDAR("Calendar", "Your events, by month and day", PebbleIcons.Calendar),
    COMPANION("Companion", "Your desktop buddy", PebbleIcons.Companion),
    MEMORY("Memory", "What Pebble has learned", PebbleIcons.Memory),
    ABOUT("About", "Privacy, who makes Pebble, how to help", PebbleIcons.Shield),
}

private val WINDOW_SIZE = DpSize(1060.dp, 700.dp)

/**
 * The Pebble app: one window, sidebar navigation, bento pages of glass cards over your
 * wallpaper. Closing just hides it — the pet keeps running.
 */
@Composable
fun PebbleWindow(
    app: PebbleApp,
    pet: PetController,
    visible: Boolean,
    raise: Int,
    page: Page,
    onPage: (Page) -> Unit,
    dark: Boolean,
    petVisible: Boolean,
    onPetVisible: (Boolean) -> Unit,
    autostart: Boolean,
    onAutostart: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val state = rememberWindowState(position = WindowPosition(Alignment.Center), size = WINDOW_SIZE)
    Window(
        onCloseRequest = onClose,
        state = state,
        visible = visible,
        title = "Pebble",
        undecorated = true,
        resizable = false,
    ) {
        LaunchedEffect(dark) {
            WindowsEffects.roundCorners(window)
            WindowsEffects.setDarkMode(window, dark)
        }
        LaunchedEffect(visible, raise) {
            if (visible) {
                state.isMinimized = false
                window.toFront()
                window.requestFocus()
            }
        }

        // Build the backdrop once per open/theme/size, off the UI thread.
        val density = LocalDensity.current
        val wPx = with(density) { WINDOW_SIZE.width.roundToPx() }
        val hPx = with(density) { WINDOW_SIZE.height.roundToPx() }
        var scene by remember { mutableStateOf(app.settings.enum(dev.pebble.core.settings.SettingsRepository.Keys.APP_SCENE, Scene.AUTO)) }
        val backdrop by produceState<Backdrop?>(null, visible, dark, scene) {
            if (visible) value = withContext(Dispatchers.Default) { runCatching { Wallpaper.build(wPx, hPx, dark, scene) }.getOrNull() }
        }

        CompositionLocalProvider(LocalGlass provides glassColors(dark), LocalBackdrop provides backdrop) {
            Box(Modifier.fillMaxSize().background(if (dark) Color(0xFF0B0D12) else Color(0xFFE9ECF2))) {
                backdrop?.let {
                    Image(it.sharp, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    // Wallpapers can be busy (logos, text): a scrim keeps them from fighting the content.
                    if (scene ==
                        Scene.WALLPAPER
                    ) {
                        Box(
                            Modifier.fillMaxSize().background(
                                if (dark) Color.Black.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.12f),
                            ),
                        )
                    }
                }
                Row(Modifier.fillMaxSize().padding(14.dp)) {
                    Sidebar(pet, page, onPage, Modifier.width(214.dp).fillMaxHeight().dragsWindow(window))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Header(page, window, onClose, Modifier.dragsWindow(window))
                        Spacer(Modifier.height(14.dp))
                        AnimatedContent(page, transitionSpec = {
                            fadeIn(tween(160)) togetherWith fadeOut(tween(100))
                        }, label = "page") { p ->
                            when (p) {
                                Page.TODAY -> TodayPage(app, pet)

                                Page.WATER -> WaterPage(app)

                                Page.NOTES -> NotesPage(app)

                                Page.REMINDERS -> RemindersPage(app)

                                Page.CALENDAR -> CalendarPage(app)

                                Page.COMPANION -> CompanionPage(
                                    pet,
                                    petVisible,
                                    onPetVisible,
                                    autostart,
                                    onAutostart,
                                    scene,
                                    app.env.dispatchers.io,
                                    app.log,
                                ) {
                                    scene = it
                                    app.settings.set(dev.pebble.core.settings.SettingsRepository.Keys.APP_SCENE, it.name)
                                }

                                Page.CHAT -> ChatPage(app)

                                Page.MEMORY -> MemoryPage(app)

                                Page.ABOUT -> AboutPage(app)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Sidebar(pet: PetController, page: Page, onPage: (Page) -> Unit, modifier: Modifier) {
    val c = LocalGlass.current
    GlassCard(modifier, padding = 14.dp) {
        Row(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(34.dp)) { drawPet(pet.character, pet.stage, PetPose(), PetFrame()) }
            Spacer(Modifier.width(10.dp))
            Text("Pebble", color = c.content, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Page.entries.forEach { p ->
            val selected = p == page
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (selected) c.well else Color.Transparent)
                    .pressable { onPage(p) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(p.icon, if (selected) c.accent else c.secondary, 18.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    p.title,
                    color = if (selected) c.content else c.secondary,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Text("Quick add anywhere", color = c.secondary, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp))
        Text(
            "Ctrl + Alt + Space",
            color = c.content,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 6.dp, top = 2.dp),
        )
    }
}

@Composable
private fun Header(page: Page, window: java.awt.Window, onClose: () -> Unit, modifier: Modifier) {
    val c = LocalGlass.current
    Row(modifier.fillMaxWidth().padding(start = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(page.title, color = c.content, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(page.subtitle, color = c.secondary, fontSize = 13.sp)
        }
        IconButton(PebbleIcons.Minimize, size = 32.dp) { (window as? Frame)?.state = Frame.ICONIFIED }
        Spacer(Modifier.width(8.dp))
        IconButton(PebbleIcons.Close, size = 32.dp, onClick = onClose)
    }
}
