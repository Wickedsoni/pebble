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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pebble.core.reminders.Strictness
import dev.pebble.core.sync.SyncTable
import dev.pebble.desktop.PebbleApp
import dev.pebble.desktop.app.CardLabel
import dev.pebble.desktop.app.GlassCard
import dev.pebble.desktop.app.IconButton
import dev.pebble.desktop.app.PebbleIcons
import dev.pebble.desktop.ui.Chip
import dev.pebble.desktop.ui.LocalGlass
import dev.pebble.desktop.ui.Toggle

/** The Reminders page: makes its [RemindersStateHolder] once, then only draws its state (docs/UI-PATTERN.md). */
@Composable
fun RemindersPage(app: PebbleApp) {
    val scope = rememberCoroutineScope()
    val holder = remember { RemindersStateHolder(app.reminders, app.engine, app.memory, app.env, scope) }
    val history = remember { HistoryStateHolder(app.history, app.journal, app.agenda, app.engine, app.env, scope) }
    val state by holder.state.collectAsState()
    val historyState by history.state.collectAsState()
    WithHistory(historyState, history::onEvent) {
        RemindersContent(state, holder::onEvent) { r ->
            r.uid?.let { history.onEvent(HistoryEvent.Open(SyncTable.ONE_OFF_REMINDER, it, r.title)) }
        }
    }
}

/** Stateless: draws [state], sends what you do to [onEvent]; [onHistory] opens the History dialog of a reminder. */
@Composable
fun RemindersContent(state: RemindersUiState, onEvent: (RemindersEvent) -> Unit, onHistory: (RemindersUiState.OneOffRow) -> Unit = {}) {
    val c = LocalGlass.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassCard(Modifier.fillMaxWidth(), padding = 20.dp) {
            CardLabel("Repeating", PebbleIcons.Bell, c.accent)
            state.rules.forEach { r ->
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Toggle(r.enabled) { onEvent(RemindersEvent.SetEnabled(r.id, it)) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.title, color = c.content, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(r.intervalLabel, color = c.secondary, fontSize = 12.sp)
                    }
                    IconButton(PebbleIcons.Minus, size = 30.dp) { onEvent(RemindersEvent.ChangeInterval(r.id, -5)) }
                    Spacer(Modifier.width(6.dp))
                    IconButton(PebbleIcons.Plus, size = 30.dp) { onEvent(RemindersEvent.ChangeInterval(r.id, +5)) }
                    Spacer(Modifier.width(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Strictness.entries.forEach { s ->
                            Chip(if (s == Strictness.STRICT) "Strict" else s.label, s == r.strictness) {
                                onEvent(RemindersEvent.SetStrictness(r.id, s))
                            }
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
                CardLabel("Your reminders · ${state.oneOffs.size}", PebbleIcons.Clock)
                if (state.oneOffs.isEmpty()) {
                    Text("None yet. Say or type “kal 7 baje mummy ko call” (Ctrl + Alt + Space).", color = c.secondary, fontSize = 12.sp)
                }
                state.oneOffs.forEach { r ->
                    Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            r.title,
                            color = c.content,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(r.dueLabel, color = c.secondary, fontSize = 12.sp)
                        Spacer(Modifier.width(8.dp))
                        if (r.uid != null) HistoryChip { onHistory(r) }
                        IconButton(PebbleIcons.Close, size = 22.dp) { onEvent(RemindersEvent.DeleteOneOff(r.id)) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                CardLabel("Repeating, next", PebbleIcons.Bell)
                state.upcoming.forEach { r ->
                    Row(Modifier.padding(vertical = 5.dp)) {
                        Text(
                            r.title,
                            color = c.content,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(r.dueLabel, color = if (r.overdue) c.warm else c.secondary, fontSize = 12.sp)
                    }
                }
            }
            GlassCard(Modifier.weight(1f).fillMaxHeight()) {
                CardLabel("Learned timing", PebbleIcons.Memory, c.calm)
                Text(
                    state.learnedQuiet ?: "When you keep skipping reminders at a certain hour, I'll learn to stay quiet then.",
                    color = if (state.learnedQuiet != null) c.content else c.secondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}
