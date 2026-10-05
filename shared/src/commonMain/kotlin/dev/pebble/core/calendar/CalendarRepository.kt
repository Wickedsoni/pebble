package dev.pebble.core.calendar

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.pebble.db.Calendar_event
import dev.pebble.db.PebbleDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random

/** What a paired device may see of an event (milestone F). Until sync exists, every event is [PRIVATE]. */
enum class Visibility(val db: String) {
    PRIVATE("private"),
    BUSY("busy"),
    FULL("full"),
    ;

    companion object {
        fun parse(s: String?): Visibility = entries.firstOrNull { it.db == s } ?: PRIVATE
    }
}

/**
 * One calendar event (WP E2). [startAt] and [endAt] are the first occurrence (epoch milliseconds). [tz] is the
 * IANA zone it was made in. [rrule]: an RFC 5545 RRULE value without "RRULE:", or null. [exdates]: skipped
 * occurrence starts. [remindMinutes]: a reminder this many minutes before each occurrence, or null.
 */
data class CalendarEvent(
    val uid: String,
    val title: String,
    val startAt: Long,
    val endAt: Long,
    val tz: String,
    val allDay: Boolean = false,
    val notes: String? = null,
    val rrule: String? = null,
    val exdates: List<Long> = emptyList(),
    val remindMinutes: Int? = null,
    val visibility: Visibility = Visibility.PRIVATE,
)

/** The calendar table. Deletes are tombstones (ADR 0013): every read skips them. */
class CalendarRepository(private val db: PebbleDatabase) {
    private val q get() = db.calendarQueries

    fun live(): List<CalendarEvent> = q.liveEvents().executeAsList().map(::toEvent)

    fun liveFlow(context: CoroutineContext = Dispatchers.Default): Flow<List<CalendarEvent>> =
        q.liveEvents().asFlow().mapToList(context).map { rows -> rows.map(::toEvent) }

    /** Events that can have an occurrence in [from, to): expand the repeating ones to find it. */
    fun between(from: Long, to: Long): List<CalendarEvent> = q.eventsBetween(from = from, to = to).executeAsList().map(::toEvent)

    fun byUid(uid: String): CalendarEvent? = q.eventByUid(uid).executeAsOneOrNull()?.let(::toEvent)

    /** Adds [e], or replaces the event with the same uid (a tombstone comes back). [at]: when. */
    fun save(e: CalendarEvent, at: Long) = q.upsertEvent(
        uid = e.uid,
        title = e.title,
        notes = e.notes,
        startAt = e.startAt,
        endAt = e.endAt,
        allDay = if (e.allDay) 1L else 0L,
        tz = e.tz,
        rrule = e.rrule,
        exdates = e.exdates.takeIf { it.isNotEmpty() }?.joinToString(","),
        remindMinutes = e.remindMinutes?.toLong(),
        visibility = e.visibility.db,
        at = at,
    )

    /** Saves many events in one transaction (an ICS import). */
    fun saveAll(events: List<CalendarEvent>, at: Long) = db.transaction { events.forEach { save(it, at) } }

    /** Removes the event from view. The row stays as a tombstone so that sync can tell other devices (WP E1). */
    fun delete(uid: String, at: Long) = q.deleteEvent(at = at, uid = uid)

    /** Removes tombstones older than [before]. Returns how many. */
    fun purgeTombstones(before: Long): Long = q.purgeEventTombstones(before).value

    /** Gives events without a device (made before this device had an id) this device's id. */
    fun claim(deviceId: String) = q.claimEvents(deviceId)

    private fun toEvent(r: Calendar_event) = CalendarEvent(
        uid = r.uid,
        title = r.title,
        startAt = r.start_at,
        endAt = r.end_at,
        tz = r.tz,
        allDay = r.all_day != 0L,
        notes = r.notes,
        rrule = r.rrule,
        exdates = r.exdates?.split(',')?.mapNotNull { it.trim().toLongOrNull() }.orEmpty(),
        remindMinutes = r.remind_minutes?.toInt(),
        visibility = Visibility.parse(r.visibility),
    )

    companion object {
        /** A new uid: 128 random bits as 32 lower-case hex digits (the same form as the E1 uids). */
        fun newUid(random: Random = Random.Default): String = random.nextBytes(16).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }
}
