package dev.pebble.core.calendar

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.pebble.core.sync.ChangeJournal
import dev.pebble.core.sync.ChangeJournal.Companion.v
import dev.pebble.core.sync.SyncTable
import dev.pebble.db.Calendar_event
import dev.pebble.db.PebbleDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonNull
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

/**
 * The calendar table. Deletes are tombstones (ADR 0013): every read skips them. A deleted event never comes back
 * (WP E3, spec D5). Each write to a synced field goes into the change journal first, with one HLC ([ChangeJournal]).
 */
class CalendarRepository(private val db: PebbleDatabase, private val journal: ChangeJournal = ChangeJournal(db)) {
    private val q get() = db.calendarQueries

    fun live(): List<CalendarEvent> = q.liveEvents().executeAsList().map(::toEvent)

    fun liveFlow(context: CoroutineContext = Dispatchers.Default): Flow<List<CalendarEvent>> =
        q.liveEvents().asFlow().mapToList(context).map { rows -> rows.map(::toEvent) }

    /** Events that can have an occurrence in [from, to): expand the repeating ones to find it. */
    fun between(from: Long, to: Long): List<CalendarEvent> = q.eventsBetween(from = from, to = to).executeAsList().map(::toEvent)

    fun byUid(uid: String): CalendarEvent? = q.eventByUid(uid).executeAsOneOrNull()?.let(::toEvent)

    /**
     * Adds [e], or changes the live event with the same uid. [at]: when. Returns false, and changes nothing, if
     * the event with this uid was deleted: a deleted event never comes back (spec D5), also from an ICS file.
     */
    fun save(e: CalendarEvent, at: Long): Boolean = db.transactionWithResult {
        val old = q.eventRowByUid(e.uid).executeAsOneOrNull()
        if (old?.deleted_at != null || (old == null && journal.isDeleted(SyncTable.CALENDAR_EVENT, e.uid))) {
            return@transactionWithResult false
        }
        val exdates = e.exdates.takeIf { it.isNotEmpty() }?.joinToString(",")
        val fields = mapOf(
            "title" to v(e.title), "notes" to v(e.notes), "start_at" to v(e.startAt), "end_at" to v(e.endAt),
            "all_day" to v(if (e.allDay) 1L else 0L), "tz" to v(e.tz), "rrule" to v(e.rrule), "exdates" to v(exdates),
            "remind_minutes" to v(e.remindMinutes?.toLong()), "visibility" to v(e.visibility.db),
        )
        val device = old?.owner_device ?: journal.deviceId()
        val changed = if (old == null) {
            fields + mapOf("owner_device" to v(device), "deleted_at" to JsonNull)
        } else {
            val before = mapOf(
                "title" to v(old.title), "notes" to v(old.notes), "start_at" to v(old.start_at), "end_at" to v(old.end_at),
                "all_day" to v(old.all_day), "tz" to v(old.tz), "rrule" to v(old.rrule), "exdates" to v(old.exdates),
                "remind_minutes" to v(old.remind_minutes), "visibility" to v(old.visibility),
            )
            fields.filter { (k, value) -> before[k] != value }
        }
        if (changed.isEmpty()) return@transactionWithResult true
        val hlc = journal.record(SyncTable.CALENDAR_EVENT, e.uid, changed, at)
        q.upsertEvent(
            uid = e.uid,
            title = e.title,
            notes = e.notes,
            startAt = e.startAt,
            endAt = e.endAt,
            allDay = if (e.allDay) 1L else 0L,
            tz = e.tz,
            rrule = e.rrule,
            exdates = exdates,
            remindMinutes = e.remindMinutes?.toLong(),
            visibility = e.visibility.db,
            at = at,
            hlc = hlc.toString(),
            ownerDevice = device,
        )
        true
    }

    /** Saves many events in one transaction (an ICS import). Returns how many were skipped because they were deleted. */
    fun saveAll(events: List<CalendarEvent>, at: Long): Int = db.transactionWithResult { events.count { !save(it, at) } }

    /** Removes the event from view. The row stays as a tombstone so that sync can tell other devices (WP E1). */
    fun delete(uid: String, at: Long) = db.transaction {
        val live = q.eventRowByUid(uid).executeAsOneOrNull()?.takeIf { it.deleted_at == null } ?: return@transaction
        val hlc = journal.record(SyncTable.CALENDAR_EVENT, live.uid, mapOf("deleted_at" to v(at)), at)
        q.deleteEvent(at = at, hlc = hlc.toString(), uid = uid)
    }

    /** Removes tombstones older than [before], and their journal entries except the graves. Returns how many. */
    fun purgeTombstones(before: Long): Long = db.transactionWithResult {
        q.purgeEventTombstones(before).value.also { journal.purgeEntries(SyncTable.CALENDAR_EVENT) }
    }

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
        fun newUid(random: Random = Random.Default): String = ChangeJournal.newUid(random)
    }
}
