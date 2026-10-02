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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pebble.desktop.app.CardLabel
import dev.pebble.desktop.app.GlassCard
import dev.pebble.desktop.app.PebbleIcons
import dev.pebble.desktop.app.Scene
import dev.pebble.desktop.pet.Character
import dev.pebble.desktop.pet.Mood
import dev.pebble.desktop.pet.PetController
import dev.pebble.desktop.pet.PetFrame
import dev.pebble.desktop.pet.PetPainter.drawPet
import dev.pebble.desktop.pet.PetPose
import dev.pebble.desktop.pet.Stage
import dev.pebble.desktop.pet.defaultPose
import dev.pebble.desktop.platform.Autostart
import dev.pebble.desktop.ui.Chip
import dev.pebble.desktop.ui.LocalGlass
import dev.pebble.desktop.ui.Toggle
import dev.pebble.desktop.ui.pressable

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompanionPage(
    pet: PetController,
    petVisible: Boolean,
    onPetVisible: (Boolean) -> Unit,
    autostart: Boolean,
    onAutostart: (Boolean) -> Unit,
    scene: Scene,
    onScene: (Scene) -> Unit,
) {
    val c = LocalGlass.current
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassCard(Modifier.fillMaxWidth().height(250.dp), padding = 20.dp) {
            CardLabel("Choose your companion", PebbleIcons.Companion)
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Character.entries.forEach { ch ->
                    val selected = ch == pet.character
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(14.dp))
                            .background(if (selected) c.accent.copy(alpha = 0.22f) else c.well)
                            .pressable { pet.chooseCharacter(ch) }.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Canvas(Modifier.size(96.dp)) {
                            drawPet(
                                ch,
                                pet.stage,
                                if (selected) Mood.HAPPY.defaultPose().copy(motion = dev.pebble.desktop.pet.Motion.STILL) else PetPose(),
                                PetFrame(),
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            ch.displayName,
                            color = c.content,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassCard(Modifier.weight(1f).fillMaxHeight()) {
                CardLabel("Growth", PebbleIcons.Spark, c.warm)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Stage.entries.forEach { s ->
                        val name = s.name.lowercase().replaceFirstChar { it.uppercase() }
                        Chip(if (s <= pet.earned) name else "🔒 $name", s == pet.stage) { pet.chooseStage(s) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                val growth = remember(pet.earned) { pet.growth() }
                val next = growth.next
                if (next == null) {
                    Text("Legendary! Every stage unlocked. 🏆", color = c.content, fontSize = 13.sp)
                } else {
                    Text("To grow into ${next.label}:", color = c.content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    growth.tasks.forEach { t -> GrowthTask(t) }
                }
                Spacer(Modifier.height(16.dp))
                CardLabel("Background")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Scene.entries.forEach { s -> Chip(s.label, s == scene) { onScene(s) } }
                }
            }
            GlassCard(Modifier.weight(1f).fillMaxHeight()) {
                CardLabel("Behaviour")
                SettingRow("Show ${pet.character.displayName} on the desktop", petVisible, onPetVisible)
                SettingRow(
                    if (Autostart.isSupported) "Start with Windows" else "Start with Windows (installed app only)",
                    autostart,
                    onAutostart,
                )
            }
        }
    }
}

@Composable
fun SettingRow(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalGlass.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.content, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        Toggle(on, onChange)
    }
}

@Composable
private fun GrowthTask(t: dev.pebble.core.growth.Task) {
    val c = LocalGlass.current
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                (if (t.done) "✓ " else "") + t.label,
                color = if (t.done) c.secondary else c.content,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            Text("${minOf(t.current, t.target)} / ${t.target}", color = c.secondary, fontSize = 11.sp)
        }
        Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(4.dp).background(c.well, RoundedCornerShape(2.dp))) {
            Box(Modifier.fillMaxWidth(t.progress).height(4.dp).background(if (t.done) c.calm else c.warm, RoundedCornerShape(2.dp)))
        }
    }
}
