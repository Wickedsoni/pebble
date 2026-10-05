package dev.pebble.core.calendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToLong

/**
 * One occurrence of [event]: its start and end (epoch milliseconds). [date]: the day it starts on in the zone of the
 * person who looks (the view zone), also for a timed event in another zone. [recurrenceShown]: false when the event
 * repeats in a way Pebble cannot expand, so it is shown only once. It must match `event.rrule`; the default computes it,
 * and [RecurrenceExpander] passes the value it already has.
 */
data class Occurrence(
    val event: CalendarEvent,
    val startAt: Long,
    val endAt: Long,
    val date: LocalDate,
    val recurrenceShown: Boolean = event.rrule == null || RecurrenceRule.parse(event.rrule) != null,
)

/**
 * Expands events into occurrences (WP E2, RFC 5545 subset in [RecurrenceRule]).
 *
 * - A repeating timed event keeps its local time of day in its own zone (`tz`), also across a DST change. A time
 *   that does not exist on a day (a spring-forward gap) moves forward by the gap; a time that exists twice uses
 *   the first one (`ZonedDateTime.of`).
 * - All-day events are dates, not instants: they start at midnight in [viewZone], the zone of the person who looks.
 * - The start (DTSTART) is always the first occurrence. Days that do not exist in a month (the 31st, or 29 Feb in a
 *   year that is not a leap year) are skipped.
 * - `COUNT` counts occurrences before `EXDATE` removes some (RFC 5545).
 */
object RecurrenceExpander {
    /** Stops a rule that never reaches the window (for example a daily event from long ago, read far in the future). */
    private const val MAX_STEPS = 200_000

    /** Stops a MONTHLY or YEARLY rule that no day fits (for example the 30th, every 12 months, from February). */
    private const val MAX_EMPTY_PERIODS = 500

    /** The earliest `from` that [occurrences] looks back from; keeps the date arithmetic far from the limits. */
    private const val MIN_FROM = -1_000_000_000_000_000L

    /** The longest event length that [occurrences] looks back over (about 100 years); a longer one cannot wrap the date. */
    private const val MAX_SPAN = 3_200_000_000_000L

    fun occurrences(e: CalendarEvent, from: Long, to: Long, viewZone: ZoneId, limit: Int = 1_000): List<Occurrence> {
        val eventZone = zoneOf(e.tz, viewZone)
        val zone = if (e.allDay) viewZone else eventZone
        val start = Instant.ofEpochMilli(e.startAt).atZone(eventZone).toLocalDateTime()
        val days = ((e.endAt - e.startAt) / 86_400_000.0).roundToLong().coerceAtLeast(1)
        val duration = (e.endAt - e.startAt).coerceAtLeast(0)
        val skippedDays = e.exdates.map { Instant.ofEpochMilli(it).atZone(eventZone).toLocalDate() }.toSet()
        val skippedStarts = e.exdates.toSet()
        val rule = e.rrule?.let(RecurrenceRule::parse)
        val shown = e.rrule == null || rule != null

        /** [date]: the day in the event's zone (the view zone for an all-day event). The result's date is the view date. */
        fun occurrence(date: LocalDate): Occurrence {
            val s = if (e.allDay) date.atStartOfDay(zone) else ZonedDateTime.of(date, start.toLocalTime(), zone)
            val end = if (e.allDay) {
                date.plusDays(days).atStartOfDay(zone).toInstant().toEpochMilli()
            } else {
                s.toInstant().toEpochMilli() + duration
            }
            val viewDate = if (e.allDay) date else s.withZoneSameInstant(viewZone).toLocalDate()
            return Occurrence(e, s.toInstant().toEpochMilli(), end, viewDate, shown)
        }

        fun overlaps(o: Occurrence) = o.startAt < to && (o.endAt > from || o.startAt >= from)

        if (rule == null) return listOf(occurrence(start.toLocalDate())).filter(::overlaps)

        // Days before this one cannot overlap the window (two days of margin for zones and DST).
        val notBefore = Instant.ofEpochMilli(
            from.coerceAtLeast(MIN_FROM) - duration.coerceAtMost(MAX_SPAN) - 2 * 86_400_000L,
        ).atZone(zone).toLocalDate()
        val out = ArrayList<Occurrence>()
        var counted = 0
        var steps = 0
        for (date in dates(rule, start.toLocalDate(), notBefore)) {
            if (++steps > MAX_STEPS || out.size >= limit) break
            val o = occurrence(date)
            if (o.startAt >= to || beyond(rule.until, o, date, start.toLocalTime())) break
            if (rule.count != null && ++counted > rule.count) break
            val isSkipped = if (e.allDay) date in skippedDays else o.startAt in skippedStarts
            if (!isSkipped && overlaps(o)) out += o
        }
        return out
    }

    /** True when [o] starts after the rule's UNTIL. [date]: the day of [o] in the event's zone, as UNTIL counts it. */
    private fun beyond(until: RecurrenceRule.Until?, o: Occurrence, date: LocalDate, time: LocalTime): Boolean = when (until) {
        null -> false
        is RecurrenceRule.Until.Date -> date.isAfter(until.date)
        is RecurrenceRule.Until.Utc -> o.startAt > until.instant.toEpochMilli()
        is RecurrenceRule.Until.Local -> LocalDateTime.of(date, time).isAfter(until.time)
    }

    /**
     * Candidate days in order, from [first] on: [first] itself, then each day the rule gives after it. Without COUNT,
     * a DAILY or WEEKLY rule jumps to the period of [notBefore], so a start long ago costs nothing. A MONTHLY or
     * YEARLY loop stops after [MAX_EMPTY_PERIODS] periods in a row without a day.
     */
    private fun dates(rule: RecurrenceRule, first: LocalDate, notBefore: LocalDate): Sequence<LocalDate> = sequence {
        yield(first)
        val jump = rule.count == null && notBefore.isAfter(first)
        when (rule.freq) {
            RecurrenceRule.Freq.DAILY -> {
                var k = if (jump) maxOf(1L, ChronoUnit.DAYS.between(first, notBefore) / rule.interval) else 1L
                while (true) yield(first.plusDays(k++ * rule.interval))
            }

            RecurrenceRule.Freq.WEEKLY -> {
                val week0 = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val days = rule.byDay.ifEmpty { setOf(first.dayOfWeek) }.sorted()
                var k = if (jump) ChronoUnit.WEEKS.between(week0, notBefore) / rule.interval else 0L
                while (true) {
                    val week = week0.plusWeeks(k++ * rule.interval)
                    days.forEach { d -> week.plusDays(d.value - 1L).takeIf { it.isAfter(first) }?.let { yield(it) } }
                }
            }

            RecurrenceRule.Freq.MONTHLY -> {
                val month0 = YearMonth.from(first)
                val days = rule.byMonthDay.ifEmpty { listOf(first.dayOfMonth) }
                var empty = 0
                var k = 0L
                while (empty <= MAX_EMPTY_PERIODS) {
                    val month = month0.plusMonths(k++ * rule.interval)
                    val found = days.filter { it <= month.lengthOfMonth() }.map { month.atDay(it) }.filter { it.isAfter(first) }
                    if (found.isEmpty()) empty++ else empty = 0
                    found.forEach { yield(it) }
                }
            }

            RecurrenceRule.Freq.YEARLY -> {
                val months = rule.byMonth.ifEmpty { listOf(first.monthValue) }
                val days = rule.byMonthDay.ifEmpty { listOf(first.dayOfMonth) }
                var empty = 0
                var k = 0L
                while (empty <= MAX_EMPTY_PERIODS) {
                    val year = first.year + k++ * rule.interval
                    val found = months.flatMap { m ->
                        val month = YearMonth.of(year.toInt(), m)
                        days.filter { it <= month.lengthOfMonth() }.map { month.atDay(it) }
                    }.filter { it.isAfter(first) }
                    if (found.isEmpty()) empty++ else empty = 0
                    found.forEach { yield(it) }
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
