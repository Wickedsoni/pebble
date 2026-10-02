package dev.pebble.desktop.quickadd

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.foundation.layout.Arrangement
import dev.pebble.core.brain.CommandRouter
import dev.pebble.desktop.now
import dev.pebble.desktop.ui.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import dev.pebble.desktop.PebbleApp
import dev.pebble.desktop.PetLine
import dev.pebble.desktop.platform.UserActivity
import dev.pebble.desktop.platform.WindowsEffects
import dev.pebble.desktop.ui.FrostedPanel
import dev.pebble.desktop.ui.LocalGlass
import dev.pebble.desktop.ui.glassColors
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

private val SIZE = DpSize(640.dp, 150.dp)

private fun sourceLabel(r: CommandRouter.Routed.Run): String = when (r.source) {
    CommandRouter.Source.RULES -> "exact match"
    CommandRouter.Source.MODEL -> "understood · ${((r.understood?.top?.confidence ?: 0f) * 100).toInt()}% sure"
    CommandRouter.Source.FALLBACK -> "brain not loaded — saving as note"
}

/** After "Not what I meant": reopen with [text] and ask, leaving out [wrongAction]. */
data class QuickAddRetry(val text: String, val wrongAction: String)

/**
 * Spotlight-style bar: type naturally, see what it will do, press Enter. Closes on Esc or focus loss.
 * [retry] reopens it on a sentence Pebble got wrong, straight into the choices.
 */
@Composable
fun QuickAddWindow(
    app: PebbleApp,
    visible: Boolean,
    dark: Boolean,
    retry: QuickAddRetry? = null,
    onRetry: (QuickAddRetry) -> Unit = {},
    onDone: (PetLine?) -> Unit,
) {
    if (!visible) return
    val area = remember { UserActivity.workArea() }
    val state = rememberDialogState(
        position = WindowPosition((area.x + (area.width - SIZE.width.value) / 2).dp, (area.y + area.height * 0.22f).dp),
        size = SIZE,
    )
    DialogWindow(
        onCloseRequest = { onDone(null) },
        state = state,
        title = "Pebble Quick Add",
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
    ) {
        var text by remember { mutableStateOf(retry?.text ?: "") }
        val focus = remember { FocusRequester() }
        var routed by remember { mutableStateOf<CommandRouter.Routed?>(null) }
        // Route as you type: rules are instant; the model (~7 ms) runs off the UI thread, debounced.
        LaunchedEffect(text) {
            if (text.isBlank()) { routed = null; return@LaunchedEffect }
            delay(120)
            routed = withContext(Dispatchers.Default) {
                if (retry != null && text == retry.text) app.router.ask(text, exclude = retry.wrongAction) else app.router.route(text)
            }
        }

        fun choose(option: CommandRouter.Option) {
            val ask = routed as? CommandRouter.Routed.Ask
            app.commandFeedback.record(text.trim(), option.action, ask?.understood, now())
            onDone(app.execute(option.command))
        }

        fun submit() {
            when (val r = routed ?: app.router.route(text)) {
                is CommandRouter.Routed.Run ->
                    if (r.source == CommandRouter.Source.MODEL) onDone(app.executeFromModel(text, r) { t, a -> onRetry(QuickAddRetry(t, a)) })
                    else onDone(app.execute(r.command))
                is CommandRouter.Routed.Ask -> Unit // pick an option instead
                null -> onDone(null)
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
                override fun windowLostFocus(e: WindowEvent?) = onDone(null)
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }

        CompositionLocalProvider(LocalGlass provides glassColors(dark)) {
            val colors = LocalGlass.current
            FrostedPanel {
                Column(Modifier.fillMaxSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        dev.pebble.desktop.app.Icon(dev.pebble.desktop.app.PebbleIcons.Spark, colors.accent, 24.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.fillMaxWidth()) {
                            if (text.isEmpty()) Text(
                                "remind me to call mom at 7pm · remember …",
                                color = colors.secondary, fontSize = 20.sp,
                            )
                            BasicTextField(
                                text, { text = it },
                                singleLine = true,
                                textStyle = TextStyle(color = colors.content, fontSize = 20.sp),
                                cursorBrush = SolidColor(colors.accent),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { e ->
                                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                    when (e.key) {
                                        Key.Escape -> { onDone(null); true }
                                        Key.Enter, Key.NumPadEnter -> { submit(); true }
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
                    Spacer(Modifier.height(12.dp))
                    when (val r = routed) {
                        is CommandRouter.Routed.Run -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(app.describe(r.command) + "   ↵", color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.width(10.dp))
                            Text(sourceLabel(r), color = colors.secondary, fontSize = 11.sp)
                        }
                        is CommandRouter.Routed.Ask -> Column {
                            Text(r.question, color = colors.content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                r.options.forEachIndexed { i, o -> Chip("${i + 1}  ${o.label}", false) { choose(o) } }
                            }
                        }
                        null -> Text("Type in English, Hindi or Hinglish · Esc to close", color = colors.secondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
