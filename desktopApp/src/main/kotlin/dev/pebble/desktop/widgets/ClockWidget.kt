package dev.pebble.desktop.widgets

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pebble.desktop.ui.LocalGlassColors
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val timeFormat = DateTimeFormatter.ofPattern("h:mm")
private val periodFormat = DateTimeFormatter.ofPattern("a")
private val dateFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM")

@Composable
fun ClockWidget() {
    val colors = LocalGlassColors.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1_000L - (System.currentTimeMillis() % 1_000L))
        }
    }

    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            now.format(dateFormat).uppercase(),
            color = colors.accent,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.8.sp,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            val style = TextStyle(color = colors.content, fontSize = 46.sp, fontWeight = FontWeight.Light)
            // Each digit rolls independently, like a flip clock.
            now.format(timeFormat).forEach { ch -> RollingChar(ch, style) }
            Text(
                now.format(periodFormat).uppercase(),
                color = colors.secondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(start = 6.dp, bottom = 10.dp),
            )
        }
    }
}

@Composable
private fun RollingChar(ch: Char, style: TextStyle) {
    AnimatedContent(
        targetState = ch,
        transitionSpec = {
            val slide = spring<IntOffset>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)
            (slideInVertically(slide) { -it } + fadeIn(tween(220)))
                .togetherWith(slideOutVertically(slide) { it } + fadeOut(tween(180)))
        },
        label = "digit",
    ) { Text(it.toString(), style = style) }
}
