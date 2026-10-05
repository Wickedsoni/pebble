package dev.pebble.desktop.app.pages

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import dev.pebble.desktop.ui.pressable
import java.nio.file.Path

/** The Calendar page (WP E2): makes its [CalendarStateHolder] once, then only draws its state (docs/UI-PATTERN.md). */
@Composable
fun CalendarPage(app: PebbleApp) {
    val scope = rememberCoroutineScope()
    val holder = remember { CalendarStateHolder(app.calendar, app.agenda, app.engine, app.env, scope) }
    val history = remember { HistoryStateHolder(app.history, app.journal, app.agenda, app.engine, app.env, scope) }
    val state by holder.state.collectAsState()
    val historyState by history.state.collectAsState()
    WithHistory(historyState, history::onEvent) {
        CalendarContent(state, holder::onEvent) { history.onEvent(HistoryEvent.Open(SyncTable.CALENDAR_EVENT, it.uid, it.title)) }
    }
}

private const val HINT =
    "No time: an all-day event. Add “fri”, “tomorrow” or “20 oct” for another day. “Skip day” removes one day of a repeating event; ✕ deletes all its days."

private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/** Stateless: draws [state], sends what you do to [onEvent]; [onHistory] opens the History dialog of an event. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarContent(state: CalendarUiState, onEvent: (CalendarPageEvent) -> Unit, onHistory: (CalendarUiState.EventRow) -> Unit = {}) {
    val c = LocalGlass.current
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassCard(Modifier.weight(1.25f).fillMaxHeight(), padding = 18.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    state.monthTitle,
                    color = c.content,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(PebbleIcons.Back, size = 30.dp) { onEvent(CalendarPageEvent.PreviousMonth) }
                Spacer(Modifier.width(6.dp))
                IconButton(PebbleIcons.Forward, size = 30.dp) { onEvent(CalendarPageEvent.NextMonth) }
                Spacer(Modifier.width(8.dp))
                Chip("Today", false) { onEvent(CalendarPageEvent.GoToToday) }
            }
            Spacer(Modifier.height(10.dp))
            Row {
                WEEKDAYS.forEach {
                    Text(it, color = c.secondary, fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(4.dp))
            Column(Modifier.weight(1f)) {
                state.cells.chunked(7).forEach { week ->
                    Row(Modifier.weight(1f).fillMaxWidth()) {
                        week.forEach { cell ->
                            DayCellView(cell, Modifier.weight(1f).fillMaxHeight()) { onEvent(CalendarPageEvent.Select(cell.date)) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Import .ics…", false) { chooseIcs(save = false)?.let { onEvent(CalendarPageEvent.Import(it)) } }
                Chip("Export .ics…", false) { chooseIcs(save = true)?.let { onEvent(CalendarPageEvent.Export(it)) } }
            }
        }
        GlassCard(Modifier.weight(1f).fillMaxHeight()) {
            CardLabel(state.selectedTitle, PebbleIcons.Calendar, c.accent)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (state.selected.isEmpty()) Text("Nothing on this day.", color = c.secondary, fontSize = 13.sp)
                state.selected.forEach { r ->
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.title, color = c.content, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(r.timeLabel, r.repeatLabel).joinToString(" · "), color = c.secondary, fontSize = 12.sp)
                            r.warning?.let { Text(it, color = c.warm, fontSize = 11.sp, lineHeight = 14.sp) }
                        }
                        HistoryChip { onHistory(r) }
                        if (r.canSkip) {
                            Chip("Skip day", false) { onEvent(CalendarPageEvent.SkipDay(r.uid, r.occurrenceAt)) }
                            Spacer(Modifier.width(6.dp))
                        }
                        IconButton(PebbleIcons.Close, size = 22.dp) { onEvent(CalendarPageEvent.Delete(r.uid)) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            GlassField("Add: “Dentist 5pm for 30 min”", Modifier.fillMaxWidth()) { onEvent(CalendarPageEvent.Add(it)) }
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Repeat.entries.forEach { r -> Chip(r.label, r == state.repeat) { onEvent(CalendarPageEvent.SetRepeat(r)) } }
            }
            Spacer(Modifier.height(5.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                state.remindChoices.forEach { r ->
                    Chip(r.label, r.minutes == state.remindMinutes) { onEvent(CalendarPageEvent.SetRemind(r.minutes)) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                state.message ?: HINT,
                color = c.secondary,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )
        }
    }
}

@Composable
private fun DayCellView(cell: CalendarUiState.DayCell, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalGlass.current
    Box(
        modifier.padding(2.dp).clip(RoundedCornerShape(10.dp))
            .background(if (cell.selected) c.well else Color.Transparent)
            .pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                cell.label,
                color = when {
                    cell.today -> c.accent
                    cell.inMonth -> c.content
                    else -> c.secondary.copy(alpha = 0.5f)
                },
                fontSize = 13.sp,
                fontWeight = if (cell.today || cell.selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Row(Modifier.height(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(cell.events.coerceAtMost(3)) {
                    Canvas(Modifier.size(5.dp)) { drawCircle(if (cell.inMonth) c.accent else c.secondary) }
                }
            }
        }
    }
}

/** The Windows file dialog for .ics files; null if you cancel. [save]: choose where to write. */
private fun chooseIcs(save: Boolean): Path? {
    val d = java.awt.FileDialog(
        null as java.awt.Frame?,
        if (save) "Export your calendar" else "Import a calendar file",
        if (save) java.awt.FileDialog.SAVE else java.awt.FileDialog.LOAD,
    )
    d.file = if (save) "pebble-calendar.ics" else "*.ics"
    d.isVisible = true
    val f = d.file ?: return null
    return Path.of(d.directory, if (save && !f.endsWith(".ics", ignoreCase = true)) "$f.ics" else f)
}
