package dev.pebble.desktop.app.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pebble.desktop.PebbleApp
import dev.pebble.desktop.app.CardLabel
import dev.pebble.desktop.app.GlassCard
import dev.pebble.desktop.app.PebbleIcons
import dev.pebble.desktop.ui.ChatTurn
import dev.pebble.desktop.ui.Chip
import dev.pebble.desktop.ui.LocalGlass

/** Everything you've said to Pebble (typed or by voice), what it did and what it replied. Local; clearable. */
@Composable
fun ChatPage(app: PebbleApp) {
    val scope = rememberCoroutineScope()
    val holder = remember { ChatStateHolder(RepositoryConversationPort(app.conversation, app.env), app.env, scope) }
    val state by holder.state.collectAsState()
    ChatContent(state, holder::onEvent)
}

/** Stateless: the conversation, "Clear conversation" and its question. */
@Composable
fun ChatContent(state: ChatUiState, onEvent: (ChatEvent) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalGlass.current
    val list = rememberLazyListState()
    // Keyed on the last turn, not on the size: the list stops growing at its limit.
    LaunchedEffect(state.scrollKey) { if (state.rows.isNotEmpty()) list.scrollToItem(state.rows.lastIndex) }
    GlassCard(modifier.fillMaxSize(), padding = 20.dp) {
        Row {
            CardLabel(state.countLabel, PebbleIcons.Spark, c.accent)
            Spacer(Modifier.weight(1f))
            if (state.rows.isNotEmpty() && !state.confirmClear) Chip("Clear conversation", false) { onEvent(ChatEvent.AskClear) }
        }
        Text(
            "Press Ctrl+Alt+Space to type, or hold it to talk. Stays on this computer.",
            color = c.secondary,
            fontSize = 12.sp,
        )
        if (state.confirmClear) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Delete the whole conversation? You cannot undo this.",
                color = c.warm,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Delete it", false) { onEvent(ChatEvent.ConfirmClear) }
                Chip("Cancel", false) { onEvent(ChatEvent.CancelClear) }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (state.loaded && state.rows.isEmpty()) Text("No conversation yet.", color = c.secondary, fontSize = 13.sp)
        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.rows) { row -> ChatTurn(row, bubbleWidth = 560.dp, showTime = true) }
        }
    }
}
