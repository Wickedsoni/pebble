package dev.pebble.desktop.app.pages

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pebble.core.memory.Memory
import dev.pebble.core.memory.MemoryKind
import dev.pebble.core.settings.SettingsRepository.Keys
import dev.pebble.desktop.PebbleApp
import dev.pebble.desktop.app.CardLabel
import dev.pebble.desktop.app.GlassCard
import dev.pebble.desktop.app.GlassField
import dev.pebble.desktop.app.IconButton
import dev.pebble.desktop.app.PebbleIcons
import dev.pebble.desktop.ui.Chip
import dev.pebble.desktop.ui.LocalGlass

/**
 * Everything Pebble has learned or been told, grouped by kind, each deletable. Plus the
 * privacy switches: watch-history is opt-in and stays on this computer.
 */
@Composable
fun MemoryPage(app: PebbleApp) {
    val c = LocalGlass.current
    LaunchedEffect(Unit) { runCatching { app.brain.learn() } }
    val memories by remember { app.memory.visibleFlow() }.collectAsState(initial = app.memory.visible())
    var mediaOn by remember { mutableStateOf(app.settings.bool(Keys.MEDIA_TRACKING, false)) }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassCard(Modifier.weight(1.6f).fillMaxHeight(), padding = 20.dp) {
            CardLabel("What I remember about you", PebbleIcons.Memory, c.calm)
            GlassField("Tell me something to remember…", Modifier.fillMaxWidth()) { app.remember(it) }
            Spacer(Modifier.height(10.dp))
            if (memories.isEmpty()) Text("Nothing yet. I learn from how you use me, and you can tell me things here.",
                color = c.secondary, fontSize = 13.sp, lineHeight = 18.sp)
            LazyColumn {
                MemoryKind.entries.forEach { kind ->
                    val group = memories.filter { it.kind == kind }
                    if (group.isNotEmpty()) {
                        item(key = "h-$kind") {
                            Text(kind.label, color = c.secondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        }
                        items(group, key = { it.id }) { MemoryRow(it) { app.forget(it) } }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassCard(Modifier.fillMaxWidth()) {
                CardLabel("Privacy", PebbleIcons.Shield, c.water)
                Text("Everything stays on this computer. Nothing is uploaded.", color = c.content, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                SettingRow("Notice what I watch", mediaOn) {
                    mediaOn = it
                    app.settings.set(Keys.MEDIA_TRACKING, it.toString())
                }
                Text("Reads the title of video apps and sites (YouTube, Netflix, Prime Video, VLC…) once a minute. Off by default.",
                    color = c.secondary, fontSize = 12.sp, lineHeight = 16.sp)
                Spacer(Modifier.height(8.dp))
                Chip("Clear watch history", false) { app.memory.clearMedia(); runCatching { app.brain.learn() } }
            }
            GlassCard(Modifier.fillMaxWidth().weight(1f)) {
                CardLabel("AI assistants", PebbleIcons.Spark, c.accent)
                Text("Coming later: connect Claude Code, Codex or Gemini so I can remember what you worked on together.",
                    color = c.secondary, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun MemoryRow(m: Memory, onForget: () -> Unit) {
    val c = LocalGlass.current
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(Modifier.fillMaxWidth().hoverable(hover).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(m.text, color = c.content, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        // Forget button is always there for keyboard/mouse; it just gets quieter when not hovered.
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            IconButton(PebbleIcons.Close, size = if (hovered) 28.dp else 24.dp, onClick = onForget)
        }
    }
}
