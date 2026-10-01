package dev.pebble.desktop.app.pages

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pebble.desktop.PebbleApp
import dev.pebble.desktop.app.CardLabel
import dev.pebble.desktop.app.GlassCard
import dev.pebble.desktop.app.GlassField
import dev.pebble.desktop.app.IconButton
import dev.pebble.desktop.app.PebbleIcons
import dev.pebble.desktop.ui.LocalGlass
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val noteDate = DateTimeFormatter.ofPattern("d MMM, h:mm a")

@Composable
fun NotesPage(app: PebbleApp) {
    val c = LocalGlass.current
    val notes by remember { app.notes.activeFlow(200) }.collectAsState(initial = emptyList())
    GlassCard(Modifier.fillMaxSize(), padding = 20.dp) {
        CardLabel("${notes.size} open", PebbleIcons.Notes, c.warm)
        GlassField("Write a note and press Enter", Modifier.fillMaxWidth()) { app.addNote(it) }
        Spacer(Modifier.height(12.dp))
        if (notes.isEmpty()) Text("Nothing here yet.", color = c.secondary, fontSize = 13.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(notes, key = { it.id }) { n ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(PebbleIcons.Check, size = 28.dp) { app.completeNote(n.id) }
                    Spacer(Modifier.width(12.dp))
                    Text(n.text, color = c.content, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f))
                    Text(Instant.ofEpochMilli(n.updatedAt).atZone(ZoneId.systemDefault()).format(noteDate), color = c.secondary, fontSize = 11.sp)
                }
            }
        }
    }
}
