package dev.pebble.core.calendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong

/** One occurrence of [event]: its start and end (epoch milliseconds). [date]: the local day it starts on. */
data class Occurrence(val event: CalendarEvent, val startAt: Long, val endAt: Long, val date: LocalDate) {
    /** False when the event repeats in a way Pebble cannot expand, so it is shown only once. */
    val recurrenceShown: Boolean get() = event.rrule == null || RecurrenceRule.parse(event.rrule) != null
}

/**
 * Expands events into occurrences (WP E2, RFC 5545 subset in [RecurrenceRule]).
 *
 * - A repeating timed event keeps its local time of day in its own zone (`tz`), also across a DST change. A time
 *   that does not exist on a day (a spring-forward gap) moves forward by the gap; a time that exists twice uses
 *   the first one (`ZonedDateTime.of`).
 * - All-day events are dates, not instants: they start at midnight in [viewZone], the zone of the person who looks.
 * - The start (DTSTART) is always the first occurrence. Days that do not exist in a month (the 31st) are skipped.
 * - `COUNT` counts occurrences before `EXDATE` removes some (RFC 5545).
 */
object RecurrenceExpander {
    /** Stops a rule that never reaches the window (for example a daily event from long ago, read far in the future). */
    private const val MAX_STEPS = 200_000

    fun occurrences(e: CalendarEvent, from: Long, to: Long, viewZone: ZoneId, limit: Int = 1_000): List<Occurrence> {
        val eventZone = zoneOf(e.tz, viewZone)
        val zone = if (e.allDay) viewZone else eventZone
        val start = Instant.ofEpochMilli(e.startAt).atZone(eventZone).toLocalDateTime()
        val days = ((e.endAt - e.startAt) / 86_400_000.0).roundToLong().coerceAtLeast(1)
        val duration = (e.endAt - e.startAt).coerceAtLeast(0)
        val skippedDays = e.exdates.map { Instant.ofEpochMilli(it).atZone(eventZone).toLocalDate() }.toSet()
        val skippedStarts = e.exdates.toSet()

        fun occurrence(date: LocalDate): Occurrence {
            val s = if (e.allDay) date.atStartOfDay(zone) else ZonedDateTime.of(date, start.toLocalTime(), zone)
            val end = if (e.allDay) {
                date.plusDays(days).atStartOfDay(zone).toInstant().toEpochMilli()
            } else {
                s.toInstant().toEpochMilli() +
                    duration
            }
            return Occurrence(e, s.toInstant().toEpochMilli(), end, date)
        }

        fun overlaps(o: Occurrence) = o.startAt < to && (o.endAt > from || o.startAt >= from)

        val rule = e.rrule?.let(RecurrenceRule::parse)
        if (rule == null) return listOf(occurrence(start.toLocalDate())).filter(::overlaps)

        val out = ArrayList<Occurrence>()
        var counted = 0
        var steps = 0
        for (date in dates(rule, start.toLocalDate())) {
            if (++steps > MAX_STEPS || out.size >= limit) break
            val o = occurrence(date)
            if (o.startAt >= to || beyond(rule.until, o, start.toLocalTime())) break
            if (rule.count != null && ++counted > rule.count) break
            val isSkipped = if (e.allDay) date in skippedDays else o.startAt in skippedStarts
            if (!isSkipped && overlaps(o)) out += o
        }
        return out
    }

    /** True when [o] starts after the rule's UNTIL. */
    private fun beyond(until: RecurrenceRule.Until?, o: Occurrence, time: java.time.LocalTime): Boolean = when (until) {
        null -> false
        is RecurrenceRule.Until.Date -> o.date.isAfter(until.date)
        is RecurrenceRule.Until.Utc -> o.startAt > until.instant.toEpochMilli()
        is RecurrenceRule.Until.Local -> LocalDateTime.of(o.date, time).isAfter(until.time)
    }

    /** Candidate days in order, from [first] on: [first] itself, then each day the rule gives after it. */
    private fun dates(rule: RecurrenceRule, first: LocalDate): Sequence<LocalDate> = sequence {
        yield(first)
        when (rule.freq) {
            RecurrenceRule.Freq.DAILY -> generateSequence(1L) { it + 1 }.forEach { yield(first.plusDays(it * rule.interval)) }

            RecurrenceRule.Freq.WEEKLY -> {
                val week0 = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val days = rule.byDay.ifEmpty { setOf(first.dayOfWeek) }.sorted()
                generateSequence(0L) { it + 1 }.forEach { w ->
                    val week = week0.plusWeeks(w * rule.interval)
                    days.forEach { d -> week.plusDays(d.value - 1L).takeIf { it.isAfter(first) }?.let { yield(it) } }
                }
            }

            RecurrenceRule.Freq.MONTHLY -> {
                val month0 = YearMonth.from(first)
                val days = rule.byMonthDay.ifEmpty { listOf(first.dayOfMonth) }
                generateSequence(0L) { it + 1 }.forEach { m ->
                    val month = month0.plusMonths(m * rule.interval)
                    days.forEach { d -> if (d <= month.lengthOfMonth()) month.atDay(d).takeIf { it.isAfter(first) }?.let { yield(it) } }
                }
            }
        }
    }

    /** The event's zone; an unknown id (a damaged row) falls back to [fallback]. */
    fun zoneOf(tz: String, fallback: ZoneId): ZoneId = runCatching { ZoneId.of(tz) }.getOrElse {
        if (tz ==
            "Z"
        ) {
            ZoneOffset.UTC
        } else {
            fallback
        }
    }
}
