package dev.pebble.core.calendar

import dev.pebble.core.reminders.ReminderRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The calendar as days and occurrences (WP E2): expands events in a time window, and keeps the linked one-off
 * reminders of events with a reminder offset.
 *
 * Threads: every method reads or writes the database; call it off the UI thread.
 */
class CalendarAgenda(
    private val events: CalendarRepository,
    private val reminders: ReminderRepository,
    /** The zone of the person who looks (the app's zone). All-day events are days in this zone. */
    private val zone: () -> ZoneId,
) {
    /** Occurrences that overlap [from, to), soonest first; all-day ones first on their day. */
    fun between(from: Long, to: Long): List<Occurrence> {
        val z = zone()
        // A day either side: an all-day event's stored midnight is in its own zone, which can differ from z.
        return events.between(from - DAY, to + DAY)
            .flatMap { RecurrenceExpander.occurrences(it, from, to, z) }
            .sortedWith(compareBy<Occurrence> { it.date }.thenBy { !it.event.allDay }.thenBy { it.startAt }.thenBy { it.event.title })
    }

    /** The occurrences on each day from [first] to [last] (both included). */
    fun days(first: LocalDate, last: LocalDate): Map<LocalDate, List<Occurrence>> {
        val z = zone()
        val from = first.atStartOfDay(z).toInstant().toEpochMilli()
        val to = last.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli()
        val out = linkedMapOf<LocalDate, MutableList<Occurrence>>()
        for (o in between(from, to)) {
            // A multi-day event is listed on each day it covers.
            var d = maxOf(o.date, first)
            val end = Instant.ofEpochMilli(maxOf(o.endAt - 1, o.startAt)).atZone(z).toLocalDate()
            while (!d.isAfter(minOf(end, last))) {
                out.getOrPut(d) { mutableListOf() } += o
                d = d.plusDays(1)
            }
        }
        return out
    }

    /**
     * Adds the missing linked reminders for occurrences that start within [horizonMillis] from [now]. A reminder whose
     * time has passed but whose occurrence has not started yet is due at [now]. Returns how many it added.
     */
    fun scheduleReminders(now: Long, horizonMillis: Long = REMINDER_HORIZON): Int {
        var added = 0
        val withOffset = events.between(now, now + horizonMillis + MAX_OFFSET).filter { it.remindMinutes != null }
        for (e in withOffset) {
            val offset = e.remindMinutes!! * 60_000L
            for (o in RecurrenceExpander.occurrences(e, now, now + horizonMillis + offset, zone())) {
                val due = o.startAt - offset
                if (o.startAt <= now || due > now + horizonMillis) continue
                if (reminders.addLinked(reminderTitle(o), maxOf(due, now), e.uid, o.startAt, now)) added++
            }
        }
        return added
    }

    /** The event [uid] was saved or deleted: its reminders that did not fire yet go, then the current ones are made again. */
    fun eventChanged(uid: String, now: Long): Int {
        reminders.deletePendingLinked(uid, now)
        return scheduleReminders(now)
    }

    private fun reminderTitle(o: Occurrence): String = if (o.event.allDay) {
        "${o.event.title} (today)"
    } else {
        "${o.event.title} at " + Instant.ofEpochMilli(o.startAt).atZone(zone()).format(TIME)
    }

    companion object {
        const val DAY = 86_400_000L

        /** Reminders are made this far ahead; the app makes the next ones every 10 minutes. */
        const val REMINDER_HORIZON = 2 * DAY

        /** The longest reminder offset Pebble offers (1 day), so an event just past the horizon still gets its reminder. */
        private const val MAX_OFFSET = DAY
        private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")
    }
}
