package dev.pebble.desktop.app.pages

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    var keepVoice by remember { mutableStateOf(app.settings.bool(Keys.KEEP_VOICE_CORRECTIONS, false)) }
    var micOn by remember { mutableStateOf(app.settings.bool(Keys.MICROPHONE_ENABLED, true)) }
    var voiceClips by remember { mutableStateOf(app.voiceSamples.count()) }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // The right column is full (search + privacy), so the Teach card shares the left column.
        Column(Modifier.weight(1.6f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassCard(Modifier.fillMaxWidth().weight(1f), padding = 20.dp) {
                CardLabel("What I remember about you", PebbleIcons.Memory, c.calm)
                GlassField("Tell me something to remember…", Modifier.fillMaxWidth()) { app.remember(it) }
                Spacer(Modifier.height(10.dp))
                if (memories.isEmpty()) {
                    Text(
                        "Nothing yet. I learn from how you use me, and you can tell me things here.",
                        color = c.secondary,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                }
                LazyColumn {
                    MemoryKind.entries.forEach { kind ->
                        val group = memories.filter { it.kind == kind }
                        if (group.isNotEmpty()) {
                            item(key = "h-$kind") {
                                Text(
                                    kind.label,
                                    color = c.secondary,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                                )
                            }
                            items(group, key = { it.id }) { MemoryRow(it) { app.forget(it) } }
                        }
                    }
                }
            }
            TeachCard(app, Modifier.fillMaxWidth().weight(1f))
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            MemorySearchCard(app, Modifier.fillMaxWidth())
            GlassCard(Modifier.fillMaxWidth()) {
                CardLabel("Privacy", PebbleIcons.Shield, c.water)
                Text("Everything stays on this computer. Nothing is uploaded.", color = c.content, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(12.dp))
                SettingRow("Notice what I watch", mediaOn) {
                    mediaOn = it
                    app.settings.set(Keys.MEDIA_TRACKING, it.toString())
                }
                Text(
                    "Reads the title of video apps and sites (YouTube, Netflix, Prime Video, VLC…) once a minute. Off by default.",
                    color = c.secondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(8.dp))
                Chip("Clear watch history", false) { app.memory.clearMedia(); runCatching { app.brain.learn() } }
                Spacer(Modifier.height(12.dp))
                SettingRow("Microphone (talk to Pebble)", micOn) {
                    micOn = it
                    app.settings.set(Keys.MICROPHONE_ENABLED, it.toString())
                }
                Text(
                    if (micOn) {
                        "Only while you hold Ctrl+Alt+Space or press 🎤. Speech is understood on this computer."
                    } else {
                        "Off: Pebble never opens the microphone."
                    },
                    color = c.secondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(8.dp))
                SettingRow("Keep voice clips I correct", keepVoice) {
                    keepVoice = it
                    app.settings.set(Keys.KEEP_VOICE_CORRECTIONS, it.toString())
                }
                Text(
                    "When you fix what I heard, I keep that clip and your words, to understand your voice better. " +
                        "Off by default; the mic is only on while you hold the talk key.",
                    color = c.secondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                )
                if (voiceClips > 0) {
                    Spacer(Modifier.height(8.dp))
                    Chip("Delete $voiceClips voice clips", false) { app.clearVoiceSamples(); voiceClips = 0 }
                }
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

/** "Search memory": makes its state holder once, then only draws its state (docs/UI-PATTERN.md). */
@Composable
private fun MemorySearchCard(app: PebbleApp, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val holder = remember { MemorySearchStateHolder(app::searchMemoryLoading, scope) }
    val state by holder.state.collectAsState()
    MemorySearchContent(state, holder::onEvent, modifier)
}

/** Stateless: the search field and what it found. */
@Composable
fun MemorySearchContent(state: MemorySearchUiState, onEvent: (MemorySearchEvent) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalGlass.current
    GlassCard(modifier) {
        CardLabel("Search memory", PebbleIcons.Search, c.calm)
        GlassField("Search your notes and what you told me…", Modifier.fillMaxWidth(), icon = PebbleIcons.Search) {
            onEvent(MemorySearchEvent.Search(it))
        }
        Spacer(Modifier.height(8.dp))
        val results = state.results
        when {
            state.searching -> Text("Looking for “${state.query}”…", color = c.secondary, fontSize = 12.sp)

            state.noModel -> Text("Search needs the command model, and it isn't installed.", color = c.secondary, fontSize = 12.sp)

            results == null -> Text(
                "Finds notes, facts and things you said by meaning, in English, Hinglish or Hindi.",
                color = c.secondary,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )

            results.isEmpty() -> Text("Nothing about “${state.query}” yet.", color = c.secondary, fontSize = 12.sp)

            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("For “${state.query}”", color = c.secondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    IconButton(PebbleIcons.Close, size = 22.dp) { onEvent(MemorySearchEvent.Clear) }
                }
                results.forEach { r ->
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(r.kindLabel, color = c.secondary, fontSize = 11.sp)
                        Text(
                            r.text,
                            color = c.content,
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** "Teach Pebble a command": makes its state holder once, then only draws its state (docs/UI-PATTERN.md). */
@Composable
private fun TeachCard(app: PebbleApp, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val holder = remember { TeachStateHolder(app.teaching, scope) }
    val state by holder.state.collectAsState()
    TeachContent(state, holder::onEvent, modifier)
}

/** Stateless: what the phrase should do, the phrase field, and what you taught. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TeachContent(state: TeachUiState, onEvent: (TeachEvent) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalGlass.current
    GlassCard(modifier) {
        CardLabel("Teach Pebble a command", PebbleIcons.Spark, c.accent)
        Text(
            "Pick what it should do, then type 3 to 5 ways you say it. Pebble uses them at once.",
            color = c.secondary,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            state.choices.forEach { ch -> Chip(ch.label, ch.action == state.action) { onEvent(TeachEvent.Choose(ch.action)) } }
        }
        Spacer(Modifier.height(8.dp))
        GlassField("How you say it, e.g. “notes kholo yaar”", Modifier.fillMaxWidth()) { onEvent(TeachEvent.Teach(it)) }
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(state.taught, key = { it.id }) { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(row.text, color = c.content, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(row.actionLabel, color = c.secondary, fontSize = 11.sp)
                    }
                    IconButton(PebbleIcons.Close, size = 22.dp) { onEvent(TeachEvent.Unteach(row.id)) }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Chip("Forget what you taught me", false) { onEvent(TeachEvent.ForgetAll) }
    }
}
