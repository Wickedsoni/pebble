package dev.pebble.desktop.app.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pebble.core.sync.SyncTable
import dev.pebble.desktop.PebbleApp
import dev.pebble.desktop.app.CardLabel
import dev.pebble.desktop.app.GlassCard
import dev.pebble.desktop.app.GlassField
import dev.pebble.desktop.app.IconButton
import dev.pebble.desktop.app.PebbleIcons
import dev.pebble.desktop.ui.Chip
import dev.pebble.desktop.ui.LocalGlass

/** The Notes page: makes its [NotesStateHolder] once, then only draws its state (docs/UI-PATTERN.md). */
@Composable
fun NotesPage(app: PebbleApp) {
    val scope = rememberCoroutineScope()
    val holder = remember { NotesStateHolder(app.notes, app::addNote, app::completeNote, app::editNote, app.env, scope) }
    val history = remember { HistoryStateHolder(app.history, app.journal, app.agenda, app.engine, app.env, scope) }
    val state by holder.state.collectAsState()
    val historyState by history.state.collectAsState()
    WithHistory(historyState, history::onEvent) {
        NotesContent(state, holder::onEvent) { n ->
            n.uid?.let { history.onEvent(HistoryEvent.Open(SyncTable.NOTE, it, n.text)) }
        }
    }
}

/** Stateless: draws [state], sends what you do to [onEvent]; [onHistory] opens the History dialog of a note. */
@Composable
fun NotesContent(state: NotesUiState, onEvent: (NotesEvent) -> Unit, onHistory: (NotesUiState.NoteRow) -> Unit = {}) {
    val c = LocalGlass.current
    GlassCard(Modifier.fillMaxSize(), padding = 20.dp) {
        CardLabel(state.countLabel, PebbleIcons.Notes, c.warm)
        GlassField("Write a note and press Enter", Modifier.fillMaxWidth()) { onEvent(NotesEvent.Add(it)) }
        Spacer(Modifier.height(12.dp))
        if (state.notes.isEmpty()) Text("Nothing here yet.", color = c.secondary, fontSize = 13.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(state.notes, key = { it.id }) { n ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(PebbleIcons.Check, size = 28.dp) { onEvent(NotesEvent.Complete(n.id)) }
                    Spacer(Modifier.width(12.dp))
                    if (n.id == state.editingId) {
                        GlassField(
                            "Note text",
                            Modifier.weight(1f),
                            icon = PebbleIcons.Check,
                            initial = n.text,
                            onCancel = { onEvent(NotesEvent.CancelEdit) },
                            autoFocus = true,
                        ) { onEvent(NotesEvent.SaveEdit(n.id, it)) }
                    } else {
                        Text(n.text, color = c.content, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.width(8.dp))
                    if (n.id != state.editingId) {
                        Box(Modifier.padding(end = 6.dp)) { Chip("Edit", false) { onEvent(NotesEvent.StartEdit(n.id)) } }
                    }
                    if (n.uid != null) HistoryChip { onHistory(n) }
                    Text(n.timeLabel, color = c.secondary, fontSize = 11.sp)
                }
            }
        }
    }
}
