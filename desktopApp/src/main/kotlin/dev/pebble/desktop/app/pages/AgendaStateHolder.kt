package dev.pebble.desktop.app.pages

import androidx.compose.runtime.Immutable
import dev.pebble.core.calendar.CalendarAgenda
import dev.pebble.core.calendar.CalendarRepository
import dev.pebble.desktop.core.AppEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.format.DateTimeFormatter

/** The "Agenda" card on the Today page (WP E2): what is left of today, then tomorrow. */
@Immutable
data class AgendaUiState(val rows: List<Row> = emptyList()) {
    /** [day]: "Today" or "Tomorrow". [now]: the event is on at this moment. */
    @Immutable
    data class Row(val day: String, val time: String, val title: String, val now: Boolean)
}

/** State holder for the Agenda card (docs/UI-PATTERN.md). It updates when the calendar changes, and each minute. */
class AgendaStateHolder(
    private val calendar: CalendarRepository,
    private val agenda: CalendarAgenda,
    private val env: AppEnv,
    scope: CoroutineScope,
    private val limit: Int = 4,
    /** False in tests: a clock that ticks for ever keeps `advanceUntilIdle` from returning. */
    everyMinute: Boolean = true,
) {
    private val minute = MutableStateFlow(0)

    val state: StateFlow<AgendaUiState> =
        combine(minute, calendar.liveFlow(env.dispatchers.io)) { _, _ -> build() }
            .stateIn(scope, SharingStarted.Eagerly, build())

    init {
        if (everyMinute) {
            scope.launch {
                while (isActive) {
                    delay(60_000L - env.millis() % 60_000L)
                    minute.value++
                }
            }
        }
    }

    private fun build(): AgendaUiState {
        val now = env.millis()
        val today = env.today()
        val zone = env.zone()
        val rows = agenda.days(today, today.plusDays(1)).flatMap { (day, list) ->
            list.filter { it.endAt > now || (it.endAt == it.startAt && it.startAt >= now) }.map { o ->
                val start = Instant.ofEpochMilli(o.startAt).atZone(zone)
                AgendaUiState.Row(
                    day = if (day == today) "Today" else "Tomorrow",
                    time = when {
                        o.event.allDay -> "All day"
                        start.toLocalDate() != day -> "Until " + Instant.ofEpochMilli(o.endAt).atZone(zone).format(TIME)
                        else -> start.format(TIME)
                    },
                    title = o.event.title,
                    now = o.startAt <= now && now < o.endAt,
                )
            }
        }
        return AgendaUiState(rows.take(limit))
    }

    private companion object {
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
    }
}
