package dev.pebble.desktop.app.pages

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pebble.desktop.app.CardLabel
import dev.pebble.desktop.app.GlassCard
import dev.pebble.desktop.app.PebbleIcons
import dev.pebble.desktop.ui.Chip
import dev.pebble.desktop.ui.LocalGlass
import java.awt.Desktop
import java.net.URI

private const val REPO = "https://github.com/Wickedsoni/pebble"
private const val DEVELOPER = "https://github.com/Wickedsoni"

private fun open(url: String) {
    runCatching { if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url)) }
}

/** Privacy in plain words, who makes Pebble, and how to help — all links go to the public GitHub project. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutPage() {
    val c = LocalGlass.current
    val version = System.getProperty("jpackage.app-version") ?: "development build"
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        GlassCard(Modifier.weight(1.2f).fillMaxHeight(), padding = 20.dp) {
            CardLabel("Your privacy", PebbleIcons.Shield, c.water)
            Column(Modifier.verticalScroll(rememberScrollState())) {
                listOf(
                    "Everything stays on this computer. No account, no cloud, no ads, no analytics. Pebble makes no network connections.",
                    "Your notes, reminders, conversation and habits live in %APPDATA%\\Pebble, and you can delete any of it (Chat, Memory pages).",
                    "The microphone opens only while you hold Ctrl+Alt+Space or press 🎤, and can be switched off in Memory → Privacy. Speech is understood on this PC; audio is thrown away.",
                    "Pebble glances at the title of the window in front to stay quiet during videos. It isn't stored unless you turn on \"Notice what I watch\".",
                    "Voice clips and watch history are off by default, and only kept if you turn them on.",
                ).forEach { line ->
                    Text("•  $line", color = c.content, fontSize = 13.sp, lineHeight = 19.sp)
                    Spacer(Modifier.height(8.dp))
                }
                Chip("Read the full privacy policy", false) { open("$REPO/blob/main/PRIVACY.md") }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassCard(Modifier.fillMaxWidth()) {
                CardLabel("About Pebble", PebbleIcons.Spark, c.accent)
                Text("Pebble $version", color = c.content, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "A desktop companion that understands English, हिंदी and Hinglish, with an AI that runs entirely on your laptop.",
                    color = c.secondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text("Made by @Wickedsoni · free and open source (Apache-2.0)", color = c.content, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Developer on GitHub", false) { open(DEVELOPER) }
                    Chip("Pebble on GitHub", false) { open(REPO) }
                }
            }
            GlassCard(Modifier.fillMaxWidth().weight(1f)) {
                CardLabel("Help make Pebble better", PebbleIcons.Companion, c.warm)
                Text(
                    "Found a bug, have an idea, or did Pebble misunderstand you? Every report helps — especially Hindi and Hinglish phrasings.",
                    color = c.secondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Pebble misunderstood me", false) { open("$REPO/issues/new?template=misunderstood.yml") }
                    Chip("Suggest a feature", false) { open("$REPO/issues/new?template=feature_request.yml") }
                    Chip("Report a bug", false) { open("$REPO/issues/new?template=bug_report.yml") }
                    Chip("Contribute code", false) { open("$REPO/blob/main/CONTRIBUTING.md") }
                    Chip("Discussions", false) { open("$REPO/discussions") }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Built on open models and data: multilingual-e5, Dolphin, Whisper, Silero VAD, sherpa-onnx, Amazon MASSIVE. Licences in NOTICE.",
                    color = c.secondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}
