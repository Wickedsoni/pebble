package dev.pebble.desktop.app.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pebble.core.memory.MemoryEngine
import dev.pebble.core.reminders.ReminderRule
import dev.pebble.core.reminders.Strictness
import dev.pebble.desktop.PebbleApp
import dev.pebble.desktop.app.CardLabel
import dev.pebble.desktop.app.GlassCard
import dev.pebble.desktop.app.IconButton
import dev.pebble.desktop.app.PebbleIcons
import dev.pebble.desktop.formatMinutes
import dev.pebble.desktop.ui.Chip
import dev.pebble.desktop.ui.LocalGlass
import dev.pebble.desktop.ui.Toggle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val dueFormat = DateTimeFormatter.ofPattern("EEE h:mm a")

@Composable
fun RemindersPage(app: PebbleApp) {
    val c = LocalGlass.current
    var version by remember { mutableIntStateOf(0) }
    val rules = remember(version) { app.reminders.rules() }
    val active by app.engine.active.collectAsState()
    val upcoming = remember(version, active) { app.engine.upcoming(8) }
    fun update(r: ReminderRule, interval: Int = r.intervalMinutes, strictness: Strictness = r.strictness, enabled: Boolean = r.enabled) {
        app.reminders.updateRule(r.id, interval.coerceIn(5, 240), strictness, enabled)
        app.engine.tick()
        version++
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassCard(Modifier.fillMaxWidth(), padding = 20.dp) {
            CardLabel("Repeating", PebbleIcons.Bell, c.accent)
            rules.forEach { r ->
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Toggle(r.enabled) { update(r, enabled = it) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.title, color = c.content, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text("every ${formatMinutes(r.intervalMinutes)}", color = c.secondary, fontSize = 12.sp)
                    }
                    IconButton(PebbleIcons.Minus, size = 30.dp) { update(r, interval = r.intervalMinutes - 5) }
                    Spacer(Modifier.width(6.dp))
                    IconButton(PebbleIcons.Plus, size = 30.dp) { update(r, interval = r.intervalMinutes + 5) }
                    Spacer(Modifier.width(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Strictness.entries.forEach { s ->
                            Chip(if (s == Strictness.STRICT) "Strict" else s.label, s == r.strictness) { update(r, strictness = s) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Gentle: a speech bubble. Normal: then a nudge and a notification. Strict: Pebble follows your cursor until it's done.",
                color = c.secondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassCard(Modifier.weight(1.4f).fillMaxHeight()) {
                CardLabel("Coming up", PebbleIcons.Clock)
                upcoming.forEach { r ->
                    Row(Modifier.padding(vertical = 5.dp)) {
                        Text(
                            r.title,
                            color = c.content,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        val overdue = r.dueAt <= dev.pebble.desktop.now()
                        Text(
                            if (overdue) "Due now" else Instant.ofEpochMilli(r.dueAt).atZone(ZoneId.systemDefault()).format(dueFormat),
                            color = if (overdue) c.warm else c.secondary,
                            fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text("Add one-off reminders with quick add (Ctrl + Alt + Space): “call mom at 7pm”.", color = c.secondary, fontSize = 12.sp)
            }
            GlassCard(Modifier.weight(1f).fillMaxHeight()) {
                CardLabel("Learned timing", PebbleIcons.Memory, c.calm)
                val quiet = app.memory.byKey(MemoryEngine.KEY_QUIET)?.text
                Text(
                    quiet ?: "When you keep skipping reminders at a certain hour, I'll learn to stay quiet then.",
                    color = if (quiet != null) c.content else c.secondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}
