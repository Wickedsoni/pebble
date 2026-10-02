package dev.pebble.desktop.app.pages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pebble.core.memory.MemoryKind
import dev.pebble.core.wellness.GLASS_ML
import dev.pebble.desktop.PebbleApp
import dev.pebble.desktop.app.BigStat
import dev.pebble.desktop.app.CardLabel
import dev.pebble.desktop.app.GlassCard
import dev.pebble.desktop.app.IconButton
import dev.pebble.desktop.app.PebbleIcons
import dev.pebble.desktop.app.drawBottle
import dev.pebble.desktop.now
import dev.pebble.desktop.pet.Mood
import dev.pebble.desktop.pet.PetController
import dev.pebble.desktop.pet.PetFrame
import dev.pebble.desktop.pet.PetPainter.drawPet
import dev.pebble.desktop.pet.PetPose
import dev.pebble.desktop.pet.defaultPose
import dev.pebble.desktop.ui.Chip
import dev.pebble.desktop.ui.LocalGlass
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

val MOODS = listOf("Rough", "Low", "Okay", "Good", "Great")

/** Bento overview: time and greeting, companion and mood, water, what's next, streaks, notes, memory. */
@Composable
fun TodayPage(app: PebbleApp, pet: PetController) {
    var clock by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = LocalDateTime.now()
            delay(60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth().height(168.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GreetingCard(app, clock, Modifier.weight(1.55f).fillMaxHeight())
            CompanionCard(app, pet, Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().height(186.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            WaterCard(app, Modifier.weight(1f).fillMaxHeight())
            NextUpCard(app, Modifier.weight(1f).fillMaxHeight())
            StreakCard(app, Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            NotesPreviewCard(app, Modifier.weight(1.55f).fillMaxHeight())
            MemoryHighlightCard(app, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun GreetingCard(app: PebbleApp, now: LocalDateTime, modifier: Modifier) {
    val c = LocalGlass.current
    val greeting = when (now.hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Up late"
    }
    GlassCard(modifier, padding = 22.dp) {
        Text(greeting, color = c.secondary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                now.format(DateTimeFormatter.ofPattern("h:mm")),
                color = c.content,
                fontSize = 60.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-2).sp,
            )
            Text(
                now.format(DateTimeFormatter.ofPattern(" a")).uppercase(),
                color = c.secondary,
                fontSize = 16.sp,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
        Text(now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM")), color = c.secondary, fontSize = 14.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompanionCard(app: PebbleApp, pet: PetController, modifier: Modifier) {
    val c = LocalGlass.current
    var logged by remember { mutableStateOf(app.moodLoggedToday()) }
    GlassCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(64.dp)) {
                drawPet(
                    pet.character,
                    pet.stage,
                    if (logged) Mood.HAPPY.defaultPose().copy(motion = dev.pebble.desktop.pet.Motion.STILL) else PetPose(),
                    PetFrame(),
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(pet.character.displayName, color = c.content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(if (logged) "Thanks for checking in." else "How are you feeling?", color = c.secondary, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.weight(1f))
        if (!logged) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                MOODS.forEachIndexed { i, label -> Chip(label, false) { app.logMood(i + 1); logged = true } }
            }
        }
    }
}

@Composable
private fun WaterCard(app: PebbleApp, modifier: Modifier) {
    val c = LocalGlass.current
    val ml by remember { app.water.totalSinceFlow(app.startOfToday()) }.collectAsState(initial = app.water.totalSince(app.startOfToday()))
    val goal = app.waterGoalGlasses
    val level by animateFloatAsState((ml / GLASS_ML).toFloat() / goal, spring(dampingRatio = 0.45f, stiffness = 120f))
    GlassCard(modifier) {
        CardLabel("Water", PebbleIcons.Water, c.water)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.width(44.dp).fillMaxHeight()) { drawBottle(level.coerceIn(0f, 1f), goal, c) }
            Spacer(Modifier.width(14.dp))
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${ml / GLASS_ML}", color = c.content, fontSize = 32.sp, fontWeight = FontWeight.Light)
                    Text(" / $goal", color = c.secondary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 6.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(PebbleIcons.Plus, filled = true, tint = c.water, size = 32.dp) { app.logWater(1) }
                    IconButton(PebbleIcons.Minus, size = 32.dp) { app.undoWater() }
                }
            }
        }
    }
}

@Composable
private fun NextUpCard(app: PebbleApp, modifier: Modifier) {
    val c = LocalGlass.current
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }
    val active by app.engine.active.collectAsState()
    val upcoming = remember(tick, active) { app.engine.upcoming(3) }
    GlassCard(modifier) {
        CardLabel("Next up", PebbleIcons.Bell, c.accent)
        upcoming.forEach { r ->
            val mins = ((r.dueAt - now()) / 60_000).toInt()
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.title,
                    color = c.content,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (mins <=
                        0
                    ) {
                        "now"
                    } else if (mins <
                        60
                    ) {
                        "${mins}m"
                    } else {
                        "${mins / 60}h ${mins % 60}m"
                    },
                    color = if (mins <= 0) c.warm else c.secondary,
                    fontSize = 12.sp,
                )
            }
        }
        if (upcoming.isEmpty()) Text("Nothing scheduled.", color = c.secondary, fontSize = 13.sp)
    }
}

@Composable
private fun StreakCard(app: PebbleApp, modifier: Modifier) {
    val c = LocalGlass.current
    val memories by remember { app.memory.visibleFlow() }.collectAsState(initial = app.memory.visible())
    val streaks = memories.filter { it.kind == MemoryKind.STREAK }
    GlassCard(modifier) {
        CardLabel("Streaks", PebbleIcons.Flame, c.warm)
        if (streaks.isEmpty()) Text("Keep showing up — streaks appear here.", color = c.secondary, fontSize = 13.sp)
        streaks.take(3).forEach {
            Text(it.text, color = c.content, fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp))
        }
    }
}

@Composable
private fun NotesPreviewCard(app: PebbleApp, modifier: Modifier) {
    val c = LocalGlass.current
    val notes by remember { app.notes.activeFlow(5) }.collectAsState(initial = emptyList())
    GlassCard(modifier) {
        CardLabel("Notes", PebbleIcons.Notes, c.warm)
        if (notes.isEmpty()) Text("No open notes.", color = c.secondary, fontSize = 13.sp)
        notes.forEach { n ->
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(PebbleIcons.Check, size = 24.dp) { app.completeNote(n.id) }
                Spacer(Modifier.width(10.dp))
                Text(n.text, color = c.content, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MemoryHighlightCard(app: PebbleApp, modifier: Modifier) {
    val c = LocalGlass.current
    val memories by remember { app.memory.visibleFlow() }.collectAsState(initial = app.memory.visible())
    val highlights = memories.filter { it.kind != MemoryKind.STREAK }.take(3)
    GlassCard(modifier) {
        CardLabel("Pebble remembers", PebbleIcons.Memory, c.calm)
        if (highlights.isEmpty()) {
            Text(
                "I'm still learning your rhythm. Tell me things with quick add: “remember …”.",
                color = c.secondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        }
        highlights.forEach {
            Text(it.text, color = c.content, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(vertical = 3.dp))
        }
    }
}
