package dev.pebble.core

import dev.pebble.core.calendar.CalendarAgenda
import dev.pebble.core.calendar.CalendarEvent
import dev.pebble.core.calendar.CalendarRepository
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.reminders.ReminderRepository
import dev.pebble.core.settings.DeviceIdentity
import dev.pebble.core.settings.SettingsRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WP E2: calendar rows follow the E1 rules (uid, device, tombstones); the agenda expands them and makes their reminders. */
class CalendarTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val db = DatabaseFactory.inMemory()
    private val settings = SettingsRepository(db)
    private val device = DeviceIdentity.ensure(settings, Random(5))
    private val calendar = CalendarRepository(db)
    private val reminders = ReminderRepository(db)
    private val agenda = CalendarAgenda(calendar, reminders) { zone }

    private fun at(d: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, d, h, m).atZone(zone).toInstant().toEpochMilli()

    private fun event(uid: String, d: Int, h: Int, rrule: String? = null, remind: Int? = null, minutes: Int = 60) =
        CalendarEvent(uid, "Event $uid", at(d, h), at(d, h) + minutes * 60_000L, zone.id, rrule = rrule, remindMinutes = remind)

    @Test
    fun aNewEventHasThisDevicesIdAndADeleteIsATombstone() {
        val uid = CalendarRepository.newUid(Random(1))
        assertTrue(uid.matches(Regex("[0-9a-f]{32}")), uid)
        calendar.save(event(uid, 9, 17), at = 1)
        val row = db.calendarQueries.eventByUid(uid).executeAsOne()
        assertEquals(device to device, row.origin_device to row.owner_device)
        assertEquals("private", row.visibility)
        assertEquals(1L, row.updated_at)
        assertNull(row.hlc, "filled by sync (WP E3)")

        calendar.delete(uid, at = 10)
        assertTrue(calendar.live().isEmpty())
        assertNull(calendar.byUid(uid))
        assertTrue(calendar.between(0, Long.MAX_VALUE).isEmpty())
        assertEquals(0L, calendar.purgeTombstones(before = 10), "not older than the limit yet")
        assertEquals(1L, calendar.purgeTombstones(before = 11))
    }

    @Test
    fun savingTheSameUidAgainReplacesItAndBringsATombstoneBack() {
        calendar.save(event("a", 9, 17), at = 1)
        calendar.delete("a", at = 2)
        calendar.save(event("a", 9, 18).copy(title = "Moved"), at = 3)
        val e = calendar.live().single()
        assertEquals("Moved" to at(9, 18), e.title to e.startAt)
        assertEquals(3L, db.calendarQueries.eventByUid("a").executeAsOne().updated_at)
    }

    @Test
    fun theAgendaExpandsRepeatingEventsAndListsALongEventOnEachDay() {
        calendar.save(event("daily", 1, 9, rrule = "FREQ=DAILY"), at = 1)
        calendar.save(event("once", 10, 12), at = 1)
        calendar.save(event("overnight", 11, 22, minutes = 4 * 60), at = 1) // 22:00 to 2:00
        calendar.save(event("gone", 10, 8), at = 1)
        calendar.delete("gone", at = 2)
        val days = agenda.days(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 12))
        assertEquals(listOf("daily", "once"), days.getValue(LocalDate.of(2026, 10, 10)).map { it.event.uid })
        assertEquals(listOf("daily", "overnight"), days.getValue(LocalDate.of(2026, 10, 11)).map { it.event.uid })
        assertEquals(listOf("overnight", "daily"), days.getValue(LocalDate.of(2026, 10, 12)).map { it.event.uid })
    }

    @Test
    fun anEventWithAnOffsetGetsOneLinkedReminderPerOccurrence() {
        val now = at(9, 8)
        calendar.save(event("standup", 9, 9, rrule = "FREQ=DAILY", remind = 15), at = now)
        calendar.save(event("plain", 9, 10), at = now)
        assertEquals(2, agenda.scheduleReminders(now), "today 8:45 and tomorrow 8:45 (two-day horizon)")
        assertEquals(0, agenda.scheduleReminders(now), "a second pass adds nothing")
        assertEquals(
            listOf("Event standup at 9:00 AM" to at(9, 8, 45), "Event standup at 9:00 AM" to at(10, 8, 45)),
            reminders.pendingOneOffs().map { it.title.replace("am", "AM") to it.dueAt },
        )
        // A done reminder is not made again.
        reminders.markOneOffDone(reminders.pendingOneOffs().first().id, at(9, 8, 45))
        assertEquals(0, agenda.scheduleReminders(now))
    }

    @Test
    fun aReminderWhoseTimeHasPassedIsDueNowAndAMovedEventGetsNewReminders() {
        val now = at(9, 16, 50)
        calendar.save(event("dentist", 9, 17, remind = 60), at = now)
        agenda.scheduleReminders(now)
        assertEquals(listOf(now), reminders.pendingOneOffs().map { it.dueAt }, "16:00 has passed; the event has not")

        calendar.save(event("dentist", 9, 19, remind = 60), at = now)
        agenda.eventChanged("dentist", now)
        assertEquals(listOf(at(9, 18)), reminders.pendingOneOffs().map { it.dueAt })

        calendar.delete("dentist", now)
        agenda.eventChanged("dentist", now)
        assertTrue(reminders.pendingOneOffs().isEmpty())
    }

    @Test
    fun eventsMadeBeforeTheDeviceHadAnIdAreClaimed() {
        val fresh = DatabaseFactory.inMemory()
        CalendarRepository(fresh).save(event("x", 9, 17), at = 1) // no device.id setting yet
        assertNull(fresh.calendarQueries.eventByUid("x").executeAsOne().origin_device)
        val id = DeviceIdentity.ensure(SettingsRepository(fresh), Random(9))
        CalendarRepository(fresh).claim(id)
        CalendarRepository(fresh).claim("other")
        val row = fresh.calendarQueries.eventByUid("x").executeAsOne()
        assertEquals(id to id, row.origin_device to row.owner_device, "claimed once; a later claim never replaces it")
    }
}
