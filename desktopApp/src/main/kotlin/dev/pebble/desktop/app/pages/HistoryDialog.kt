package dev.pebble.desktop.app.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pebble.desktop.app.CardLabel
import dev.pebble.desktop.app.GlassCard
import dev.pebble.desktop.app.IconButton
import dev.pebble.desktop.app.PebbleIcons
import dev.pebble.desktop.ui.Chip
import dev.pebble.desktop.ui.LocalGlass
import dev.pebble.desktop.ui.pressable

/**
 * Stateless: the History dialog (WP E3c-2) over the page that holds it, when [state] is open. A click outside the
 * card closes it. Use it as the last child of a [Box] that fills the page.
 */
@Composable
fun BoxScope.HistoryOverlay(state: HistoryUiState, onEvent: (HistoryEvent) -> Unit) {
    if (!state.open) return
    val c = LocalGlass.current
    Box(
        Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.25f)).pressable { onEvent(HistoryEvent.Close) },
        contentAlignment = Alignment.Center,
    ) {
        // The card takes its own clicks, so a click on it does not close the dialog.
        GlassCard(Modifier.fillMaxWidth(0.6f).fillMaxHeight(0.8f).pressable { }, padding = 20.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { CardLabel("History", PebbleIcons.Clock, c.accent) }
                IconButton(PebbleIcons.Close, size = 26.dp) { onEvent(HistoryEvent.Close) }
            }
            Text(
                state.title,
                color = c.content,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                state.message ?: "Older versions, newest first. “Restore” makes a version the current one, as a new edit.",
                color = c.secondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.versions.isEmpty()) {
                    Text("No older versions yet. Pebble keeps them for 90 days after each change.", color = c.secondary, fontSize = 13.sp)
                }
                state.versions.forEach { v ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                listOfNotNull(v.whenLabel, v.deviceLabel).joinToString(" · "),
                                color = c.secondary,
                                fontSize = 12.sp,
                            )
                            v.lostLabel?.let { Text(it, color = c.warm, fontSize = 12.sp) }
                            v.changes.forEach { Text(it, color = c.content, fontSize = 13.sp, lineHeight = 18.sp) }
                        }
                        Spacer(Modifier.width(10.dp))
                        if (v.canRestore) Chip("Restore", false) { onEvent(HistoryEvent.Restore(v.index)) }
                    }
                }
            }
        }
    }
}

/** A plain "History" chip with padding, for a row on a page. */
@Composable
fun HistoryChip(onClick: () -> Unit) {
    Box(Modifier.padding(end = 6.dp)) { Chip("History", false, onClick) }
}

/** The page body under a History dialog: [content] fills the page, the dialog lies over it. */
@Composable
fun WithHistory(state: HistoryUiState, onEvent: (HistoryEvent) -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
        HistoryOverlay(state, onEvent)
    }
}
