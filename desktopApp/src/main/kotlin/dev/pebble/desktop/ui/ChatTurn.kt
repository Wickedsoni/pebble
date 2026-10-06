package dev.pebble.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One exchange, ready to draw: [timeLabel] is made by the state holder from the clock of the app. */
@Immutable
data class ChatRow(
    val timeLabel: String,
    val said: String,
    /** You spoke it (did not type it). */
    val voice: Boolean,
    val reply: String,
    /** What Pebble did, when that is not the same as [reply]; else null. */
    val extra: String?,
)

/**
 * One exchange as two bubbles: what you said (right) and Pebble's reply (left), used by the Chat page and Quick Add.
 * With [showTime] the time and the voice mark go above the bubbles (Chat page); else the voice mark goes in front
 * of your words (Quick Add). [trailing] draws more under the reply, for example buttons.
 */
@Composable
fun ChatTurn(
    row: ChatRow,
    bubbleWidth: Dp,
    showTime: Boolean,
    modifier: Modifier = Modifier,
    trailing: @Composable ColumnScope.() -> Unit = {},
) {
    val c = LocalGlass.current
    Column(modifier.fillMaxWidth()) {
        if (showTime) {
            Text(
                row.timeLabel + if (row.voice) "  ·  🎤 voice" else "",
                color = c.secondary,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(4.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Bubble((if (row.voice && !showTime) "🎤 " else "") + row.said, mine = true, bubbleWidth)
        }
        Spacer(Modifier.height(4.dp))
        Bubble(row.reply, mine = false, bubbleWidth)
        row.extra?.let { Text("  $it", color = c.secondary, fontSize = 11.sp) }
        trailing()
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, maxWidth: Dp) {
    val c = LocalGlass.current
    Text(
        text,
        color = c.content,
        fontSize = 14.sp,
        lineHeight = 19.sp,
        modifier = Modifier.widthIn(max = maxWidth)
            .background(if (mine) c.accent.copy(alpha = 0.22f) else c.content.copy(alpha = 0.07f), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}
