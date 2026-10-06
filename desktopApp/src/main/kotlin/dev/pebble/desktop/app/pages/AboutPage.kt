package dev.pebble.desktop.app.pages

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pebble.desktop.PebbleApp
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
fun AboutPage(app: PebbleApp) {
    val c = LocalGlass.current
    val version = System.getProperty("jpackage.app-version") ?: "development build"
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            GlassCard(Modifier.fillMaxWidth().weight(1f), padding = 20.dp) {
                CardLabel("Your privacy", PebbleIcons.Shield, c.water)
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    listOf(
                        "Everything stays on this computer. No account, no cloud, no ads, no analytics. Pebble makes no connections to the internet.",
                        "Connections: only if you turn on Smart replies, Pebble starts its chat model as a helper program and talks to it on this computer (127.0.0.1, a random port and key). Nothing else listens or connects.",
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
            ModelPacksCard(app, Modifier.fillMaxWidth())
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
            BackupCard(app, Modifier.fillMaxWidth())
            GlassCard(Modifier.fillMaxWidth().weight(1f)) {
                CardLabel("Help make Pebble better", PebbleIcons.Companion, c.warm)
                // The Backup card above can grow (its messages), so this text scrolls instead of being cut.
                Column(Modifier.verticalScroll(rememberScrollState())) {
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
}

private const val BACKUP_EXTENSION = ".pebblebackup"

private const val BACKUP_HINT =
    "An encrypted copy of everything (notes, reminders, calendar, memory). If you forget the passphrase, nobody can open it, not even Pebble."

/** "Backup" (WP E4): makes its state holder once, then only draws its state (docs/UI-PATTERN.md). */
@Composable
private fun BackupCard(app: PebbleApp, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val holder = remember { BackupStateHolder(app.backup, scope) }
    val state by holder.state.collectAsState()
    // The day is read at the click, from the clock of the app (not the system clock).
    BackupContent(state, holder::onEvent, { "pebble-backup-${app.env.today()}$BACKUP_EXTENSION" }, modifier)
}

/**
 * Stateless: two passphrase fields, "Back up" and "Restore". The typed passphrase stays in this card only until a
 * button is pressed; then the fields are cleared.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupContent(
    state: BackupUiState,
    onEvent: (BackupEvent) -> Unit,
    /** The suggested file name of a new backup. */
    saveName: () -> String,
    modifier: Modifier = Modifier,
) {
    val c = LocalGlass.current
    var pass by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    GlassCard(modifier) {
        CardLabel("Backup", PebbleIcons.Shield, c.calm)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PassphraseField(pass, { pass = it }, "Passphrase", Modifier.weight(1f))
            PassphraseField(repeat, { repeat = it }, "Again (to back up)", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(if (state.busy) "Working…" else "Back up to a file…", false) {
                if (!state.busy) {
                    chooseFile("Save a Pebble backup", save = true, saveName(), BACKUP_EXTENSION)?.let {
                        onEvent(BackupEvent.Export(it, pass.toCharArray(), repeat.toCharArray()))
                    }
                    pass = ""
                    repeat = ""
                }
            }
            Chip("Restore from a file…", false) {
                if (!state.busy) {
                    chooseFile("Restore a Pebble backup", save = false, "*$BACKUP_EXTENSION")?.let {
                        onEvent(BackupEvent.Restore(it, pass.toCharArray()))
                    }
                    pass = ""
                    repeat = ""
                }
            }
            if (state.restorePending) Chip("Cancel the restore", false) { onEvent(BackupEvent.CancelRestore) }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            state.message ?: BACKUP_HINT,
            color = if (state.error) c.warm else c.secondary,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

/** A one-line field that shows dots instead of the text. */
@Composable
private fun PassphraseField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier) {
    val c = LocalGlass.current
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(c.well).padding(horizontal = 10.dp, vertical = 8.dp)) {
        if (value.isEmpty()) Text(placeholder, color = c.secondary, fontSize = 12.sp)
        BasicTextField(
            value,
            onChange,
            singleLine = true,
            textStyle = TextStyle(color = c.content, fontSize = 12.sp),
            cursorBrush = SolidColor(c.accent),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The Windows file dialog. In save mode [name] is the suggested file name, and [extension] (for example ".zip") is added
 * when you leave it out. In open mode [name] is the filter (for example "*.zip"). Null if you cancel.
 */
private fun chooseFile(title: String, save: Boolean, name: String, extension: String? = null): java.nio.file.Path? {
    val d = java.awt.FileDialog(null as java.awt.Frame?, title, if (save) java.awt.FileDialog.SAVE else java.awt.FileDialog.LOAD)
    d.file = name
    d.isVisible = true
    val f = d.file ?: return null
    val fileName = if (save && extension != null && !f.endsWith(extension, ignoreCase = true)) f + extension else f
    return java.nio.file.Path.of(d.directory, fileName)
}

/** "Model packs": makes its state holder once, then only draws its state (docs/UI-PATTERN.md). */
@Composable
private fun ModelPacksCard(app: PebbleApp, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val holder = remember { ModelPacksStateHolder(app.modelPacks, scope) }
    val state by holder.state.collectAsState()
    ModelPacksContent(state, holder::onEvent, modifier)
}

/** Stateless: each model with where it comes from, and install / remove. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelPacksContent(state: ModelPacksUiState, onEvent: (ModelPacksEvent) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalGlass.current
    GlassCard(modifier) {
        CardLabel("Model packs", PebbleIcons.Spark, c.calm)
        state.rows.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.label, color = c.content, fontSize = 13.sp)
                    Text(r.status, color = c.secondary, fontSize = 11.sp)
                }
                if (r.canRemove) Chip("Remove", false) { onEvent(ModelPacksEvent.Remove(r.name)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip(if (state.busy) "Checking…" else "Install a pack from a file…", false) {
                if (!state.busy) {
                    chooseFile("Install a Pebble model pack", save = false, "*.zip")?.let {
                        onEvent(ModelPacksEvent.Install(it))
                    }
                }
            }
        }
        Text(
            state.message ?: "Only packs signed by the Pebble project are used. Installing works offline, from a .zip file.",
            color = c.secondary,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}
