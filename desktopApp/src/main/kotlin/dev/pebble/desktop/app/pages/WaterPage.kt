package dev.pebble.desktop.app.pages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pebble.core.memory.MemoryEngine
import dev.pebble.core.settings.SettingsRepository.Keys
import dev.pebble.core.wellness.GLASS_ML
import dev.pebble.desktop.PebbleApp
import dev.pebble.desktop.app.BigStat
import dev.pebble.desktop.app.CardLabel
import dev.pebble.desktop.app.GlassCard
import dev.pebble.desktop.app.IconButton
import dev.pebble.desktop.app.PebbleIcons
import dev.pebble.desktop.app.drawBottle
import dev.pebble.desktop.app.drawWeekBars
import dev.pebble.desktop.ui.LocalGlass
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@Composable
fun WaterPage(app: PebbleApp) {
    val c = LocalGlass.current
    val today by remember { app.water.totalSinceFlow(app.startOfToday()) }.collectAsState(initial = app.water.totalSince(app.startOfToday()))
    var goal by remember { mutableIntStateOf(app.waterGoalGlasses) }
    val level by animateFloatAsState((today / GLASS_ML).toFloat() / goal, spring(dampingRatio = 0.45f, stiffness = 120f))
    // Re-read the week whenever today's total changes.
    val week = remember(today) {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().minusDays(6)
        val byDay = app.water.entriesSince(start.atStartOfDay(zone).toInstant().toEpochMilli())
            .groupBy { Instant.ofEpochMilli(it.first).atZone(zone).toLocalDate() }
            .mapValues { (_, v) -> v.sumOf { it.second } / GLASS_ML }
        (0..6).map { start.plusDays(it.toLong()) }.map { it to (byDay[it] ?: 0) }
    }
    val habit = app.memory.byKey(MemoryEngine.KEY_WATER_HOURS)?.text
    val streak = app.memory.byKey(MemoryEngine.KEY_WATER_STREAK)?.data

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassCard(Modifier.width(250.dp).fillMaxHeight(), padding = 22.dp) {
            CardLabel("Today", PebbleIcons.Water, c.water)
            Canvas(Modifier.width(110.dp).weight(1f).align(Alignment.CenterHorizontally)) { drawBottle(level.coerceIn(0f, 1f), goal, c) }
            Spacer(Modifier.height(16.dp))
            Text("${today / GLASS_ML} of $goal glasses", color = c.content, fontSize = 18.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally))
            Text("$today ml", color = c.secondary, fontSize = 13.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(14.dp))
            Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconButton(PebbleIcons.Minus, size = 40.dp) { app.undoWater() }
                IconButton(PebbleIcons.Plus, filled = true, tint = c.water, size = 40.dp) { app.logWater(1) }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassCard(Modifier.fillMaxWidth().weight(1f)) {
                CardLabel("Last 7 days")
                Canvas(Modifier.fillMaxWidth().weight(1f)) { drawWeekBars(week.map { it.second }, goal, c) }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    week.forEach { (d, _) ->
                        Text(d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()), color = c.secondary, fontSize = 11.sp,
                            textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(150.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                GlassCard(Modifier.weight(1f).fillMaxHeight()) {
                    CardLabel("Daily goal")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(PebbleIcons.Minus) { goal = (goal - 1).coerceAtLeast(4); app.settings.set(Keys.WATER_GOAL_GLASSES, "$goal") }
                        Text("$goal", color = c.content, fontSize = 28.sp, fontWeight = FontWeight.Light, textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
                        IconButton(PebbleIcons.Plus) { goal = (goal + 1).coerceAtMost(16); app.settings.set(Keys.WATER_GOAL_GLASSES, "$goal") }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("glasses of ${GLASS_ML} ml", color = c.secondary, fontSize = 12.sp)
                }
                GlassCard(Modifier.weight(1.4f).fillMaxHeight()) {
                    CardLabel("What I've noticed", PebbleIcons.Memory, c.calm)
                    if (streak != null) BigStat("$streak days", "goal streak", c.warm)
                    Text(habit ?: "Log a few glasses and I'll learn when you usually drink.", color = if (habit != null) c.content else c.secondary,
                        fontSize = 13.sp, lineHeight = 18.sp)
                }
            }
        }
    }
}
