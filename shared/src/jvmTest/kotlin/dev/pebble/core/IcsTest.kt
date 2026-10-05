package dev.pebble.core

import dev.pebble.core.calendar.CalendarEvent
import dev.pebble.core.calendar.Ics
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WP E2: ICS import and export (VEVENT subset), hand-written. */
class IcsTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun millis(t: LocalDateTime, zone: ZoneId) = t.atZone(zone).toInstant().toEpochMilli()

    /** Like a Google Calendar export: VTIMEZONE, folded lines, escapes, an alarm, a changed occurrence. */
    private val google = listOf(
        "BEGIN:VCALENDAR",
        "PRODID:-//Google Inc//Google Calendar 70.9054//EN",
        "VERSION:2.0",
        "BEGIN:VTIMEZONE",
        "TZID:Europe/Berlin",
        "BEGIN:STANDARD",
        "DTSTART:19701025T030000",
        "TZOFFSETFROM:+0200",
        "TZOFFSETTO:+0100",
        "END:STANDARD",
        "END:VTIMEZONE",
        "BEGIN:VEVENT",
        "DTSTART;TZID=Europe/Berlin:20261005T090000",
        "DTEND;TZID=Europe/Berlin:20261005T093000",
        "RRULE:FREQ=WEEKLY;BYDAY=MO,WE",
        "EXDATE;TZID=Europe/Berlin:20261007T090000",
        "UID:standup@example.com",
        "SUMMARY:Team standup\\, daily",
        "DESCRIPTION:Room 4\\nBring notes\\; and coffee. This line is long enough that Goo",
        " gle folds it onto a second line.",
        "BEGIN:VALARM",
        "ACTION:DISPLAY",
        "DESCRIPTION:This is an alarm, not the event",
        "TRIGGER:-P0DT0H10M0S",
        "END:VALARM",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "DTSTART;TZID=Europe/Berlin:20261012T100000",
        "DTEND;TZID=Europe/Berlin:20261012T103000",
        "RECURRENCE-ID;TZID=Europe/Berlin:20261012T090000",
        "UID:standup@example.com",
        "SUMMARY:Team standup (late)",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "DTSTART;VALUE=DATE:20261102",
        "DTEND;VALUE=DATE:20261104",
        "UID:trip@example.com",
        "SUMMARY:Trip",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "DTSTART:20261010T033000Z",
        "DURATION:PT45M",
        "UID:call@example.com",
        "SUMMARY:Call",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "DTSTART;TZID=India Standard Time:20261011T180000",
        "UID:outlook@example.com",
        "SUMMARY:Outlook zone name",
        "RRULE:FREQ=MONTHLY;BYDAY=1MO",
        "END:VEVENT",
        "BEGIN:VEVENT",
        "UID:nostart@example.com",
        "SUMMARY:No start",
        "END:VEVENT",
        "END:VCALENDAR",
    ).joinToString("\r\n")

    @Test
    fun aGoogleStyleFileIsRead() {
        val r = Ics.parse(google, kolkata) { "new" }
        assertEquals(1, r.skipped, "the VEVENT without DTSTART")
        assertEquals(1, r.unknownZones, "India Standard Time is a Windows name")
        assertEquals(1, r.shownOnce, "an nth weekday of the month")
        val byUid = r.events.associateBy { it.uid }
        val standup = byUid.getValue("standup@example.com")
        assertEquals("Team standup, daily", standup.title)
        assertEquals("Room 4\nBring notes; and coffee. This line is long enough that Google folds it onto a second line.", standup.notes)
        assertEquals(millis(LocalDateTime.of(2026, 10, 5, 9, 0), berlin), standup.startAt)
        assertEquals(30 * 60_000L, standup.endAt - standup.startAt)
        assertEquals("Europe/Berlin", standup.tz)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,WE", standup.rrule)
        assertEquals(
            setOf(millis(LocalDateTime.of(2026, 10, 7, 9, 0), berlin), millis(LocalDateTime.of(2026, 10, 12, 9, 0), berlin)),
            standup.exdates.toSet(),
            "the EXDATE, and the occurrence that was changed",
        )
        val late = r.events.single { it.title == "Team standup (late)" }
        assertNull(late.rrule)
        assertEquals(millis(LocalDateTime.of(2026, 10, 12, 10, 0), berlin), late.startAt)

        val trip = byUid.getValue("trip@example.com")
        assertTrue(trip.allDay)
        assertEquals(LocalDate.of(2026, 11, 2).atStartOfDay(kolkata).toInstant().toEpochMilli(), trip.startAt)
        assertEquals(2 * 86_400_000L, trip.endAt - trip.startAt)

        val call = byUid.getValue("call@example.com")
        assertEquals(millis(LocalDateTime.of(2026, 10, 10, 9, 0), kolkata), call.startAt)
        assertEquals(45 * 60_000L, call.endAt - call.startAt)
        assertEquals("UTC", call.tz)

        val outlook = byUid.getValue("outlook@example.com")
        assertEquals(millis(LocalDateTime.of(2026, 10, 11, 18, 0), kolkata), outlook.startAt, "read in the default zone")
        assertEquals(outlook.startAt, outlook.endAt, "no DTEND: ends when it starts")
    }

    @Test
    fun exportThenImportGivesTheSameEvents() {
        val events = listOf(
            CalendarEvent(
                "a",
                "Dentist; bring card, ok",
                millis(LocalDateTime.of(2026, 10, 9, 17, 0), kolkata),
                millis(LocalDateTime.of(2026, 10, 9, 17, 30), kolkata),
                "Asia/Kolkata",
                notes = "line 1\nline 2 \\ end",
                rrule = "FREQ=WEEKLY;BYDAY=FR;COUNT=4",
                exdates = listOf(millis(LocalDateTime.of(2026, 10, 16, 17, 0), kolkata)),
            ),
            CalendarEvent(
                "b",
                "Diwali",
                LocalDate.of(2026, 11, 8).atStartOfDay(kolkata).toInstant().toEpochMilli(),
                LocalDate.of(2026, 11, 9).atStartOfDay(kolkata).toInstant().toEpochMilli(),
                "Asia/Kolkata",
                allDay = true,
            ),
            CalendarEvent("c", "Call (UTC)", 1_790_000_000_000, 1_790_000_900_000, "UTC"),
            CalendarEvent(
                "d",
                "हिंदी में एक बहुत लंबा शीर्षक जो पचहत्तर बाइट से ज़्यादा है ताकि फ़ाइल में पंक्ति मुड़े",
                1_790_000_000_000,
                1_790_003_600_000,
                "Asia/Kolkata",
            ),
        )
        val text = Ics.write(events, now = 1_790_000_000_000)
        assertTrue(text.startsWith("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n"))
        assertTrue(text.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 }, "folded at 75 bytes")
        assertTrue("DTSTART;TZID=Asia/Kolkata:20261009T170000" in text)
        assertTrue("DTSTART;VALUE=DATE:20261108" in text)
        // All-day dates have no zone in the file: read them in the zone of the person who imports.
        assertEquals(events, Ics.parse(text, kolkata).events)
    }

    @Test
    fun anEmptyOrBrokenFileGivesNoEvents() {
        assertTrue(Ics.parse("", kolkata).events.isEmpty())
        assertTrue(Ics.parse("not a calendar\nat all", kolkata).events.isEmpty())
        val r = Ics.parse("BEGIN:VEVENT\nDTSTART:garbage\nSUMMARY:x\nEND:VEVENT", kolkata)
        assertEquals(0 to 1, r.events.size to r.skipped)
    }

    @Test
    fun aVeventWithoutEndIsDroppedAndTheLaterEventsStay() {
        val text = listOf(
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT", // never ends
            "UID:broken",
            "DTSTART:20261005T090000Z",
            "SUMMARY:Broken",
            "BEGIN:VEVENT",
            "UID:second",
            "DTSTART:20261006T090000Z",
            "SUMMARY:Second",
            "BEGIN:VALARM",
            "ACTION:DISPLAY",
            "END:VALARM",
            "END:VEVENT",
            "BEGIN:VEVENT", // ends with the calendar
            "UID:cut",
            "DTSTART:20261007T090000Z",
            "END:VCALENDAR",
            "BEGIN:VCALENDAR",
            "BEGIN:VEVENT",
            "UID:third",
            "DTSTART:20261008T090000Z",
            "BEGIN:VALARM", // an alarm without END before the event ends
            "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\n")
        val r = Ics.parse(text, kolkata)
        assertEquals(listOf("second", "third"), r.events.map { it.uid })
        assertEquals(2, r.skipped)
    }

    @Test
    fun aFileThatEndsInsideAVeventCountsIt() {
        val text = "BEGIN:VEVENT\nUID:a\nDTSTART:20261005T090000Z\nEND:VEVENT\nBEGIN:VEVENT\nUID:b\nDTSTART:20261006T090000Z"
        val r = Ics.parse(text, kolkata)
        assertEquals(listOf("a") to 1, r.events.map { it.uid } to r.skipped)
    }

    @Test
    fun writeKeepsLineBreaksOutOfUidRruleAndText() {
        val e = CalendarEvent(
            "a\r\nBEGIN:VEVENT",
            "one\rtwo\r\nthree\nfour",
            millis(LocalDateTime.of(2026, 10, 5, 9, 0), kolkata),
            millis(LocalDateTime.of(2026, 10, 5, 10, 0), kolkata),
            kolkata.id,
            rrule = "FREQ=DAILY\r\nATTENDEE:x",
        )
        val text = Ics.write(listOf(e), 0)
        assertEquals(1, Regex("BEGIN:VEVENT").findAll(text).count() - 1, "one real VEVENT; the other match is inside the UID")
        assertTrue("UID:aBEGIN:VEVENT\r\n" in text)
        assertTrue("RRULE:FREQ=DAILYATTENDEE:x\r\n" in text)
        assertTrue("SUMMARY:one\\ntwo\\nthree\\nfour\r\n" in text, text)
        assertTrue(Regex("\r(?!\n)").find(text) == null, "no lone CR")
        assertTrue(text.lines().none { it.startsWith("ATTENDEE") })
    }

    @Test
    fun aDateExdateSkipsThatDayOfATimedSeries() {
        val text = listOf(
            "BEGIN:VEVENT",
            "UID:w",
            "DTSTART;TZID=Europe/Berlin:20261005T090000",
            "DTEND;TZID=Europe/Berlin:20261005T100000",
            "RRULE:FREQ=DAILY;COUNT=4",
            "EXDATE;VALUE=DATE:20261006",
            "EXDATE;TZID=Europe/Berlin:20261008T090000",
            "END:VEVENT",
        ).joinToString("\n")
        val e = Ics.parse(text, kolkata).events.single()
        assertEquals(
            listOf(millis(LocalDateTime.of(2026, 10, 6, 9, 0), berlin), millis(LocalDateTime.of(2026, 10, 8, 9, 0), berlin)),
            e.exdates,
            "a date becomes the start of that day's occurrence; a stored time stays",
        )
        val days = dev.pebble.core.calendar.RecurrenceExpander.occurrences(e, 0, Long.MAX_VALUE, berlin).map { it.date.dayOfMonth }
        assertEquals(listOf(5, 7), days)
    }

    @Test
    fun aYearlyEventSurvivesExportAndImport() {
        val start = millis(LocalDateTime.of(2026, 3, 4, 9, 0), kolkata)
        val rrule = "FREQ=YEARLY;INTERVAL=2;BYMONTH=3;BYMONTHDAY=4"
        val e = CalendarEvent("y1", "Anniversary", start, start + 3_600_000, kolkata.id, rrule = rrule)
        val r = Ics.parse(Ics.write(listOf(e), 0), kolkata)
        assertEquals(listOf(e) to 0, r.events to r.shownOnce)
        assertEquals(0, Ics.parse("BEGIN:VEVENT\nDTSTART:20261005T090000Z\nRRULE:FREQ=YEARLY\nEND:VEVENT", kolkata).shownOnce)
    }
}
