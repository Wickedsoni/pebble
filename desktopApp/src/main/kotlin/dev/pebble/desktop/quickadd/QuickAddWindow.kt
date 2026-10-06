package dev.pebble.desktop.quickadd

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberDialogState
import dev.pebble.core.brain.CommandRouter
import dev.pebble.core.brain.Turn
import dev.pebble.core.quickadd.QuickCommand
import dev.pebble.desktop.PebbleApp
import dev.pebble.desktop.PetLine
import dev.pebble.desktop.platform.UserActivity
import dev.pebble.desktop.platform.WindowsEffects
import dev.pebble.desktop.ui.Chip
import dev.pebble.desktop.ui.FrostedPanel
import dev.pebble.desktop.ui.LocalGlass
import dev.pebble.desktop.ui.glassColors
import dev.pebble.desktop.voice.VoiceInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.concurrent.atomic.AtomicBoolean

private val SIZE = DpSize(660.dp, 480.dp)

private fun sourceLabel(r: CommandRouter.Routed.Run): String = when (r.source) {
    CommandRouter.Source.RULES -> "exact match"
    CommandRouter.Source.MODEL -> "understood · ${((r.understood?.top?.confidence ?: 0f) * 100).toInt()}% sure"
    CommandRouter.Source.FALLBACK -> "brain not loaded — saving as note"
}

/** After "Not what I meant": reopen with [text] and ask, leaving out [wrongAction]. */
data class QuickAddRetry(val text: String, val wrongAction: String)

/**
 * Talk to Pebble: type or hold Ctrl+Alt+Space to speak. Shows your recent conversation, runs what you
 * say, and keeps Pebble's reply on screen (also said by the pet). Voice commands run as soon as they're
 * understood; when Pebble isn't sure it asks. Closes on Esc or when you click elsewhere.
 * [retry] puts a sentence Pebble got wrong back in, straight into the choices.
 */
@Composable
fun QuickAddWindow(
    app: PebbleApp,
    visible: Boolean,
    dark: Boolean,
    retry: QuickAddRetry? = null,
    onRetry: (QuickAddRetry) -> Unit = {},
    onPet: (PetLine) -> Unit = {},
    onDone: () -> Unit,
) {
    if (!visible) return
    val area = remember { UserActivity.workArea() }
    val state = rememberDialogState(
        position = WindowPosition((area.x + (area.width - SIZE.width.value) / 2).dp, (area.y + area.height * 0.16f).dp),
        size = SIZE,
    )
    DialogWindow(
        onCloseRequest = onDone,
        state = state,
        title = "Pebble",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
    ) {
        var text by remember { mutableStateOf("") }
        var activeRetry by remember { mutableStateOf<QuickAddRetry?>(null) }
        LaunchedEffect(retry) { if (retry != null) { activeRetry = retry; text = retry.text } }
        // What Whisper heard, so an edit before Enter can be told apart from typing (a correction).
        var heard by remember { mutableStateOf<String?>(null) }
        // Pebble's last answer, with its buttons ("Not what I meant").
        var lastLine by remember { mutableStateOf<PetLine?>(null) }
        // The chat model is writing a reply (Smart replies).
        var thinking by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        // The first read happens once, not at every recomposition (a keystroke, a mic level).
        val firstTurns = remember { app.conversation.recent(30) }
        val turns by remember { app.conversation.recentFlow(30) }.collectAsState(initial = firstTurns)
        // False once this window is gone: a reply that finishes later is then only said by the pet.
        val alive = remember { AtomicBoolean(true) }
        DisposableEffect(Unit) { onDispose { alive.set(false) } }
        val debounce = remember { arrayOfNulls<Job>(1) }
        var submitting by remember { mutableStateOf(false) }
        val voice = app.voice
        val voiceState by voice.state.collectAsState()
        DisposableEffect(Unit) { onDispose { voice.cancel() } }
        val focus = remember { FocusRequester() }
        var routed by remember { mutableStateOf<CommandRouter.Routed?>(null) }
        // The text that [routed] was made for: Enter must not run the route of an older text.
        var routedFor by remember { mutableStateOf<String?>(null) }

        fun answered(line: PetLine, pet: Boolean = true) {
            lastLine = line
            if (pet) onPet(line)
            text = ""
            heard = null
            activeRetry = null
            routed = null
            routedFor = null
            focus.requestFocus()
        }

        fun via() = if (heard != null) "voice" else "typed"

        fun run(r: CommandRouter.Routed.Run) {
            if (thinking) return
            if (heard != null) app.noteVoiceCorrection(text)
            if (r.command is QuickCommand.Chitchat && app.smartRepliesOn()) {
                // Smart replies (WP C5): ask the chat model off the UI thread; it falls back to the canned line.
                // App-owned (not this window's scope): closing Quick Add must not drop the answer.
                thinking = true
                app.converseAsync(text, via(), r, { t, a -> onRetry(QuickAddRetry(t, a)) }) { line ->
                    if (alive.get()) {
                        thinking = false
                        if (line != null) answered(line, pet = false)
                    }
                }
                return
            }
            answered(app.converse(text, via(), r) { t, a -> onRetry(QuickAddRetry(t, a)) })
        }

        fun choose(option: CommandRouter.Option) {
            if (thinking) return
            if (heard != null) app.noteVoiceCorrection(text)
            val ask = routed as? CommandRouter.Routed.Ask
            if (option.command is QuickCommand.Chitchat && app.smartRepliesOn()) {
                thinking = true
                app.converseChoiceAsync(text, via(), option, ask?.understood) { line ->
                    if (alive.get()) {
                        thinking = false
                        if (line != null) answered(line, pet = false)
                    }
                }
                return
            }
            answered(app.converseChoice(text, via(), option, ask?.understood))
        }

        fun route(t: String): CommandRouter.Routed? {
            val r = activeRetry
            return if (r != null && t == r.text) app.router.ask(t, exclude = r.wrongAction) else app.router.route(t)
        }

        // Route as you type: rules are instant; the model (~5 ms) runs off the UI thread, debounced.
        LaunchedEffect(text) {
            debounce[0] = currentCoroutineContext()[Job]
            if (text.isBlank()) {
                routed = null
                routedFor = null
                return@LaunchedEffect
            }
            delay(120)
            val typed = text
            routed = withContext(app.env.dispatchers.default) { route(typed) }
            routedFor = typed
        }

        // Voice: show what was heard, then just do it when Pebble is sure (it asks when it isn't).
        LaunchedEffect(voiceState) {
            val heardNow = (voiceState as? VoiceInput.State.Heard)?.transcript?.text ?: return@LaunchedEffect
            voice.reset()
            text = heardNow
            heard = heardNow
            val r = withContext(app.env.dispatchers.default) { route(heardNow) }
            routed = r
            routedFor = heardNow
            if (r is CommandRouter.Routed.Run) {
                delay(400) // a beat to see your words before Pebble answers
                if (text == heardNow) run(r)
            }
        }

        fun submit() {
            if (submitting) return
            submitting = true
            debounce[0]?.cancel() // Enter came inside the debounce: route here, once
            val typed = text
            scope.launch {
                try {
                    val r = freshRoute(routed, routedFor, typed) ?: withContext(app.env.dispatchers.default) { route(typed) }
                    if (text != typed) return@launch // edited while routing
                    when (r) {
                        is CommandRouter.Routed.Run -> run(r)

                        is CommandRouter.Routed.Ask -> {
                            routed = r
                            routedFor = typed
                        }

                        // pick one of the choices
                        null -> Unit
                    }
                } finally {
                    submitting = false
                }
            }
        }

        LaunchedEffect(Unit) {
            app.model.warmUp()
            WindowsEffects.roundCorners(window)
            window.toFront()
            window.requestFocus()
            focus.requestFocus()
        }
        DisposableEffect(window) {
            val listener = object : WindowAdapter() {
                override fun windowLostFocus(e: WindowEvent?) = onDone()
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }

        CompositionLocalProvider(LocalGlass provides glassColors(dark)) {
            val colors = LocalGlass.current
            FrostedPanel {
                Column(Modifier.fillMaxSize()) {
                    Conversation(turns, lastLine, Modifier.weight(1f).fillMaxWidth())
                    if (thinking) Text("Pebble is thinking…", color = colors.secondary, fontSize = 11.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        dev.pebble.desktop.app.Icon(dev.pebble.desktop.app.PebbleIcons.Spark, colors.accent, 22.dp)
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.fillMaxWidth()) {
                            if (text.isEmpty()) {
                                Text("Say or type anything · “kal 7 baje mummy ko call”", color = colors.secondary, fontSize = 18.sp)
                            }
                            BasicTextField(
                                text,
                                { text = it },
                                singleLine = true,
                                textStyle = TextStyle(color = colors.content, fontSize = 18.sp),
                                cursorBrush = SolidColor(colors.accent),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (e.key) {
                                        Key.Escape -> {
                                            voice.cancel()
                                            onDone()
                                            true
                                        }

                                        Key.Enter, Key.NumPadEnter -> {
                                            submit()
                                            true
                                        }

                                        Key.One, Key.Two, Key.Three -> {
                                            val ask = routed as? CommandRouter.Routed.Ask ?: return@onPreviewKeyEvent false
                                            val i = listOf(Key.One, Key.Two, Key.Three).indexOf(e.key)
                                            ask.options.getOrNull(i)?.let(::choose)
                                            ask.options.getOrNull(i) != null
                                        }

                                        else -> false
                                    }
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val talking = voiceState is VoiceInput.State.Listening || voiceState is VoiceInput.State.Transcribing
                    if (talking) VoiceBar(voiceState, voice.isAllowed, onMic = { if (voice.isListening) voice.stop() else voice.start() })
                    if (!talking) {
                        when (val r = routed) {
                            is CommandRouter.Routed.Run -> Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    app.describe(r.command) + "   ↵",
                                    color = colors.accent,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(sourceLabel(r), color = colors.secondary, fontSize = 11.sp)
                            }

                            is CommandRouter.Routed.Ask -> Column {
                                Text(r.question, color = colors.content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    r.options.forEachIndexed { i, o -> Chip("${i + 1}  ${o.label}", false) { choose(o) } }
                                }
                            }

                            null -> VoiceBar(voiceState, voice.isAllowed, onMic = {
                                if (voice.isListening) voice.stop() else voice.start()
                            })
                        }
                    }
                }
            }
        }
    }
}

/** Your recent exchanges with Pebble, newest at the bottom; the last answer keeps its buttons. */
@Composable
private fun Conversation(turns: List<Turn>, lastLine: PetLine?, modifier: Modifier) {
    val colors = LocalGlass.current
    val list = rememberLazyListState()
    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) list.scrollToItem(turns.lastIndex) }
    if (turns.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "Hi! Tell me what to remember, remind you about, or just chat.\nHold Ctrl+Alt+Space to talk — English, हिंदी or Hinglish.",
                color = colors.secondary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }
        return
    }
    LazyColumn(modifier, state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(turns) { t ->
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Bubble((if (t.via == "voice") "🎤 " else "") + t.said, mine = true)
                }
                Spacer(Modifier.height(4.dp))
                Bubble(t.reply, mine = false)
                if (t.did != t.reply) Text("  " + t.did, color = colors.secondary, fontSize = 11.sp)
                if (t == turns.last()) {
                    val actions = lastLine?.actions.orEmpty()
                    if (actions.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            actions.forEach { a -> Chip(a.label, false) { a.onClick() } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean) {
    val colors = LocalGlass.current
    Text(
        text,
        color = colors.content,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        modifier = Modifier.widthIn(max = 480.dp)
            .background(if (mine) colors.accent.copy(alpha = 0.22f) else colors.content.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Mic chip + what the voice session is doing. */
@Composable
private fun VoiceBar(state: VoiceInput.State, allowed: Boolean, onMic: () -> Unit) {
    val colors = LocalGlass.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        val listening = state is VoiceInput.State.Listening
        Chip(
            if (listening) {
                "■  Stop"
            } else if (allowed) {
                "🎤  Speak"
            } else {
                "🎤  Off"
            },
            listening,
            onMic,
        )
        Spacer(Modifier.width(10.dp))
        val note = when (state) {
            is VoiceInput.State.Listening -> "Listening " + "▮".repeat(1 + (state.level * 8).toInt()) +
                "   (release the keys or press Stop)"

            VoiceInput.State.Transcribing -> "Understanding…"

            is VoiceInput.State.Failed -> state.message

            else -> if (allowed) "or hold Ctrl+Alt+Space and talk · Esc to close" else "Voice is off (Memory → Privacy) · Esc to close"
        }
        Text(note, color = if (state is VoiceInput.State.Failed) colors.accent else colors.secondary, fontSize = 11.sp)
    }
}

/** The route of [typed] when [routed] was made for exactly that text; else null (route again). */
internal fun <T> freshRoute(routed: T?, routedFor: String?, typed: String): T? = routed.takeIf { routedFor == typed }
