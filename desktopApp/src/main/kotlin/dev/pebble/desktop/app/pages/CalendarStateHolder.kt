package dev.pebble.desktop.app.pages

import androidx.compose.runtime.Immutable
import dev.pebble.core.calendar.CalendarAgenda
import dev.pebble.core.calendar.CalendarRepository
import dev.pebble.core.calendar.Ics
import dev.pebble.core.calendar.Occurrence
import dev.pebble.core.calendar.RecurrenceRule
import dev.pebble.core.quickadd.QuickAddParser
import dev.pebble.core.reminders.ReminderEngine
import dev.pebble.desktop.command.EVENT_REMIND_MINUTES
import dev.pebble.desktop.command.eventFor
import dev.pebble.desktop.command.formatMinutes
import dev.pebble.desktop.core.AppEnv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** How a new event repeats (the chips on the Calendar page). */
enum class Repeat(val label: String) { NONE("Once"), DAILY("Daily"), WEEKLY("Weekly"), MONTHLY("Monthly") }

/** Everything the Calendar page shows, ready to draw. */
@Immutable
data class CalendarUiState(
    val monthTitle: String = "",
    /** 6 weeks of 7 days, Monday first. */
    val cells: List<DayCell> = emptyList(),
    val selectedTitle: String = "",
    val selected: List<EventRow> = emptyList(),
    val repeat: Repeat = Repeat.NONE,
    /** Minutes before a timed event; null: no reminder. */
    val remindMinutes: Int? = EVENT_REMIND_MINUTES,
    val remindChoices: List<RemindChoice> = REMIND_CHOICES,
    /** The result of the last add, import or export; null: the hint. */
    val message: String? = null,
) {
    @Immutable
    data class DayCell(
        val date: LocalDate,
        val label: String,
        val inMonth: Boolean,
        val today: Boolean,
        val selected: Boolean,
        val events: Int,
    )

    /**
     * [repeatLabel]: "Weekly" and so on; null for a single event. [warning]: the event repeats in a way Pebble shows only once.
     * [occurrenceAt]: the start of this day's occurrence. [canSkip]: a repeating event, so this one day can be skipped.
     */
    @Immutable
    data class EventRow(
        val uid: String,
        val timeLabel: String,
        val title: String,
        val repeatLabel: String?,
        val warning: String?,
        val occurrenceAt: Long = 0,
        val canSkip: Boolean = false,
    )

    @Immutable
    data class RemindChoice(val minutes: Int?, val label: String)

    companion object {
        val REMIND_CHOICES = listOf(null, 15, 60, 24 * 60).map {
            RemindChoice(
                it,
                it?.let { m -> "${formatMinutes(m).replace("24 hours", "1 day")} before" } ?: "No reminder",
            )
        }
    }
}

/** What you can do on the Calendar page. */
sealed interface CalendarPageEvent {
    data object PreviousMonth : CalendarPageEvent

    data object NextMonth : CalendarPageEvent

    data object GoToToday : CalendarPageEvent

    data class Select(val date: LocalDate) : CalendarPageEvent

    data class SetRepeat(val repeat: Repeat) : CalendarPageEvent

    data class SetRemind(val minutes: Int?) : CalendarPageEvent

    /** [text]: the Quick Add event syntax without "event:", e.g. "Dentist 5pm for 30 min". No day: the selected day. */
    data class Add(val text: String) : CalendarPageEvent

    /** Deletes the whole event (every occurrence of a repeating one). */
    data class Delete(val uid: String) : CalendarPageEvent

    /** Skips one occurrence of a repeating event (an EXDATE); the other days stay. */
    data class SkipDay(val uid: String, val occurrenceAt: Long) : CalendarPageEvent

    data class Import(val file: Path) : CalendarPageEvent

    data class Export(val file: Path) : CalendarPageEvent
}

/**
 * State holder for the Calendar page (docs/UI-PATTERN.md). [state] updates by itself when an event is added elsewhere
 * (Quick Add, voice), and after each [onEvent].
 *
 * Threads: runs on [scope]'s dispatcher (the UI thread in the app). Database and file work goes to `env.dispatchers.io`;
 * [ReminderEngine.tick] stays on [scope]'s thread.
 */
class CalendarStateHolder(
    private val calendar: CalendarRepository,
    private val agenda: CalendarAgenda,
    private val engine: ReminderEngine,
    private val env: AppEnv,
    private val scope: CoroutineScope,
) {
    private data class View(val month: YearMonth, val selected: LocalDate, val repeat: Repeat, val remind: Int?, val message: String?)

    private val view = MutableStateFlow(env.today().let { View(YearMonth.from(it), it, Repeat.NONE, EVENT_REMIND_MINUTES, null) })

    val state: StateFlow<CalendarUiState> =
        combine(view, calendar.liveFlow(env.dispatchers.io)) { v, _ -> build(v) }
            .stateIn(scope, SharingStarted.Eagerly, build(view.value))

    fun onEvent(e: CalendarPageEvent) {
        when (e) {
            CalendarPageEvent.PreviousMonth -> view.update { it.copy(month = it.month.minusMonths(1), message = null) }

            CalendarPageEvent.NextMonth -> view.update { it.copy(month = it.month.plusMonths(1), message = null) }

            CalendarPageEvent.GoToToday -> env.today().let { t ->
                view.update { it.copy(month = YearMonth.from(t), selected = t, message = null) }
            }

            is CalendarPageEvent.Select -> view.update { it.copy(selected = e.date, month = YearMonth.from(e.date), message = null) }

            is CalendarPageEvent.SetRepeat -> view.update { it.copy(repeat = e.repeat) }

            is CalendarPageEvent.SetRemind -> view.update { it.copy(remind = e.minutes) }

            is CalendarPageEvent.Add -> add(e.text)

            is CalendarPageEvent.Delete -> write { calendar.delete(e.uid, env.millis()); agenda.eventChanged(e.uid, env.millis()); null }

            is CalendarPageEvent.SkipDay -> write { skip(e.uid, e.occurrenceAt) }

            is CalendarPageEvent.Import -> write { import(e.file) }

            is CalendarPageEvent.Export -> write { export(e.file) }
        }
    }

    private fun add(text: String) {
        val v = view.value
        val cmd = QuickAddParser.parseEvent(text, env.today().dayOfWeek.value) ?: run {
            view.update { it.copy(message = "Type a title, for example “Dentist 5pm for 30 min”.") }
            return
        }
        val base = eventFor(cmd, env, onDate = v.selected)
        val day = Instant.ofEpochMilli(base.startAt).atZone(env.zone()).toLocalDate()
        val event = base.copy(
            rrule = when (v.repeat) {
                Repeat.NONE -> null
                Repeat.DAILY -> "FREQ=DAILY"
                Repeat.WEEKLY -> "FREQ=WEEKLY;BYDAY=" + day.dayOfWeek.name.take(2)
                Repeat.MONTHLY -> "FREQ=MONTHLY;BYMONTHDAY=${day.dayOfMonth}"
            },
            remindMinutes = if (base.allDay) null else v.remind,
        )
        view.update { it.copy(selected = day, month = YearMonth.from(day), repeat = Repeat.NONE) }
        write {
            calendar.save(event, env.millis())
            agenda.eventChanged(event.uid, env.millis())
            "Added “${event.title}”."
        }
    }

    /** Runs [work] on the IO dispatcher; its result (a message, or null) is shown. Then the reminder engine catches up. */
    private fun write(work: () -> String?) {
        scope.launch {
            val message =
                withContext(env.dispatchers.io) {
                    runCatching(work).getOrElse { "That did not work: ${it.message ?: it::class.simpleName}" }
                }
            engine.tick()
            view.update { it.copy(message = message) }
        }
    }

    private fun skip(uid: String, occurrenceAt: Long): String? {
        val e = calendar.byUid(uid) ?: return null
        // An all-day day is matched by its date in the event's zone (RecurrenceExpander), so store that midnight.
        val skipAt = if (e.allDay) {
            val zone = dev.pebble.core.calendar.RecurrenceExpander.zoneOf(e.tz, env.zone())
            Instant.ofEpochMilli(occurrenceAt).atZone(env.zone()).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
        } else {
            occurrenceAt
        }
        calendar.save(e.copy(exdates = (e.exdates + skipAt).distinct().sorted()), env.millis())
        agenda.eventChanged(uid, env.millis())
        return "Skipped “${e.title}” on this day. The other days stay."
    }

    private fun import(file: Path): String {
        val result = Ics.parse(Files.readString(file), env.zone())
        calendar.saveAll(result.events, env.millis())
        agenda.scheduleReminders(env.millis())
        return buildList {
            add("Imported ${result.events.size} event${if (result.events.size == 1) "" else "s"} from ${file.fileName}.")
            if (result.shownOnce > 0) add("${result.shownOnce} repeat in a way Pebble shows only once.")
            if (result.unknownZones >
                0
            ) {
                add(
                    "${result.unknownZones} time zone name${if (result.unknownZones == 1) " was" else "s were"} not known; read in your zone.",
                )
            }
            if (result.skipped > 0) add("${result.skipped} had no start and were skipped.")
        }.joinToString(" ")
    }

    private fun export(file: Path): String {
        val events = calendar.live()
        Files.writeString(file, Ics.write(events, env.millis()))
        return "Exported ${events.size} event${if (events.size == 1) "" else "s"} to ${file.fileName}."
    }

    private fun build(v: View): CalendarUiState {
        val today = env.today()
        val first = v.month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val last = first.plusDays(41)
        val days = agenda.days(first, last)
        return CalendarUiState(
            monthTitle = v.month.atDay(1).format(MONTH),
            cells = (0L..41L).map { i ->
                val d = first.plusDays(i)
                CalendarUiState.DayCell(
                    d,
                    d.dayOfMonth.toString(),
                    YearMonth.from(d) == v.month,
                    d == today,
                    d == v.selected,
                    days[d]?.size ?: 0,
                )
            },
            selectedTitle = when (v.selected) {
                today -> "Today, " + v.selected.format(DAY)
                today.plusDays(1) -> "Tomorrow, " + v.selected.format(DAY)
                else -> v.selected.format(DAY_LONG)
            },
            selected = (
                if (v.selected in
                    first..last
                ) {
                    days[v.selected]
                } else {
                    agenda.days(v.selected, v.selected)[v.selected]
                }
                ).orEmpty().map { row(it, v.selected) },
            repeat = v.repeat,
            remindMinutes = v.remind,
            message = v.message,
        )
    }

    private fun row(o: Occurrence, day: LocalDate): CalendarUiState.EventRow {
        val zone = env.zone()
        val start = Instant.ofEpochMilli(o.startAt).atZone(zone)
        val end = Instant.ofEpochMilli(o.endAt).atZone(zone)
        val time = when {
            o.event.allDay -> "All day"

            start.toLocalDate() != day -> "Until " + end.format(TIME)

            // the second day of an event that crosses midnight
            o.endAt == o.startAt -> start.format(TIME)

            else -> start.format(TIME) + " – " + end.format(TIME)
        }
        val rule = o.event.rrule
        return CalendarUiState.EventRow(
            uid = o.event.uid,
            timeLabel = time,
            title = o.event.title,
            repeatLabel = rule?.let { r -> RecurrenceRule.parse(r)?.let(::repeatLabel) ?: "Repeats" },
            warning = if (o.recurrenceShown) null else "Repeats in a way Pebble cannot show yet: shown once.",
            occurrenceAt = o.startAt,
            canSkip = rule != null && o.recurrenceShown,
        )
    }

    private fun repeatLabel(r: RecurrenceRule): String {
        if (r.interval == 1) return Repeat.valueOf(r.freq.name).label
        val unit = when (r.freq) {
            RecurrenceRule.Freq.DAILY -> "days"
            RecurrenceRule.Freq.WEEKLY -> "weeks"
            RecurrenceRule.Freq.MONTHLY -> "months"
        }
        return "Every ${r.interval} $unit"
    }

    private companion object {
        val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
        val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM")
        val DAY_LONG: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
    }
}
