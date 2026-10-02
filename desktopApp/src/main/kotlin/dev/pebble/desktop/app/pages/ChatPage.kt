package dev.pebble.desktop.app.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pebble.desktop.PebbleApp
import dev.pebble.desktop.app.CardLabel
import dev.pebble.desktop.app.GlassCard
import dev.pebble.desktop.app.PebbleIcons
import dev.pebble.desktop.ui.Chip
import dev.pebble.desktop.ui.LocalGlass
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val turnTime = DateTimeFormatter.ofPattern("d MMM, h:mm a")

/** Everything you've said to Pebble (typed or by voice), what it did and what it replied. Local; clearable. */
@Composable
fun ChatPage(app: PebbleApp) {
    val c = LocalGlass.current
    val turns by remember { app.conversation.recentFlow(500) }.collectAsState(initial = app.conversation.recent(500))
    val list = rememberLazyListState()
    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) list.scrollToItem(turns.lastIndex) }
    GlassCard(Modifier.fillMaxSize(), padding = 20.dp) {
        Row {
            CardLabel("${turns.size} messages", PebbleIcons.Spark, c.accent)
            Spacer(Modifier.weight(1f))
            if (turns.isNotEmpty()) Chip("Clear conversation", false) { app.conversation.clear() }
        }
        Text(
            "Press Ctrl+Alt+Space to type, or hold it to talk. Stays on this computer.",
            color = c.secondary,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        if (turns.isEmpty()) Text("No conversation yet.", color = c.secondary, fontSize = 13.sp)
        LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(turns) { t ->
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        Instant.ofEpochMilli(t.atMillis).atZone(ZoneId.systemDefault()).format(turnTime) +
                            if (t.via == "voice") "  ·  🎤 voice" else "",
                        color = c.secondary,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Text(
                            t.said,
                            color = c.content,
                            fontSize = 14.sp,
                            lineHeight = 19.sp,
                            modifier = Modifier.widthIn(max = 560.dp)
                                .background(c.accent.copy(alpha = 0.22f), RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        t.reply,
                        color = c.content,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.widthIn(max = 560.dp)
                            .background(c.content.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    if (t.did != t.reply) Text("  " + t.did, color = c.secondary, fontSize = 11.sp)
                }
            }
        }
    }
}
