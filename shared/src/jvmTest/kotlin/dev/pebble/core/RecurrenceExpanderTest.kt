package dev.pebble.core

import dev.pebble.core.calendar.CalendarEvent
import dev.pebble.core.calendar.RecurrenceExpander
import dev.pebble.core.calendar.RecurrenceRule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WP E2: the RRULE subset, expanded in the event's zone (DST), with month ends, COUNT, UNTIL and EXDATE. */
class RecurrenceExpanderTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val london = ZoneId.of("Europe/London")
    private val newYork = ZoneId.of("America/New_York")

    private fun millis(t: LocalDateTime, zone: ZoneId) = t.atZone(zone).toInstant().toEpochMilli()

    private fun event(start: LocalDateTime, zone: ZoneId, rrule: String?, minutes: Long = 60, exdates: List<Long> = emptyList()) =
        CalendarEvent(
            "e1",
            "Standup",
            millis(start, zone),
            millis(start.plusMinutes(minutes), zone),
            zone.id,
            rrule = rrule,
            exdates = exdates,
        )

    private fun allDay(date: LocalDate, zone: ZoneId, rrule: String?, days: Long = 1) = CalendarEvent(
        "a1",
        "Holiday",
        date.atStartOfDay(zone).toInstant().toEpochMilli(),
        date.plusDays(days).atStartOfDay(zone).toInstant().toEpochMilli(),
        zone.id,
        allDay = true,
        rrule = rrule,
    )

    /** Local start times in [zone] of every occurrence in [from, to). */
    private fun starts(e: CalendarEvent, from: LocalDate, to: LocalDate, zone: ZoneId, view: ZoneId = zone): List<LocalDateTime> =
        RecurrenceExpander.occurrences(
            e,
            from.atStartOfDay(zone).toInstant().toEpochMilli(),
            to.atStartOfDay(zone).toInstant().toEpochMilli(),
            view,
        )
            .map { java.time.Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDateTime() }

    @Test
    fun theSubsetParsesAndEverythingElseIsRefused() {
        val r = RecurrenceRule.parse("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=6")!!
        assertEquals(RecurrenceRule.Freq.WEEKLY, r.freq)
        assertEquals(2, r.interval)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), r.byDay)
        assertEquals(6, r.count)
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=6", r.format())
        assertEquals(r, RecurrenceRule.parse("RRULE:" + r.format()))
        listOf(
            "FREQ=YEARLY;BYMONTHDAY=3", // a day without a month means every month
            "FREQ=HOURLY",
            "FREQ=MONTHLY;BYDAY=1MO", // nth weekday of the month
            "FREQ=WEEKLY;BYDAY=1MO",
            "FREQ=DAILY;BYDAY=MO", // BYDAY only with WEEKLY
            "FREQ=WEEKLY;BYMONTHDAY=3",
            "FREQ=MONTHLY;BYMONTHDAY=-1", // last day of the month
            "FREQ=MONTHLY;BYSETPOS=1",
            "FREQ=DAILY;COUNT=3;UNTIL=20261231",
            "FREQ=DAILY;INTERVAL=0",
            "FREQ=WEEKLY;WKST=SU",
            "FREQ=DAILY;FREQ=WEEKLY",
            "INTERVAL=2",
            "",
        ).forEach { assertNull(RecurrenceRule.parse(it), it) }
    }

    @Test
    fun dailyWithAnIntervalAndACount() {
        val e = event(LocalDateTime.of(2026, 10, 1, 9, 0), kolkata, "FREQ=DAILY;INTERVAL=2;COUNT=4")
        assertEquals(
            listOf(1, 3, 5, 7).map { LocalDateTime.of(2026, 10, it, 9, 0) },
            starts(e, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 1), kolkata),
        )
    }

    @Test
    fun weeklyOnSomeDaysUntilADate() {
        // Thu 1 Oct 2026; Tuesdays and Thursdays until Thu 15 Oct (included).
        val e = event(LocalDateTime.of(2026, 10, 1, 18, 30), kolkata, "FREQ=WEEKLY;BYDAY=TU,TH;UNTIL=20261015")
        assertEquals(
            listOf(1, 6, 8, 13, 15).map { LocalDateTime.of(2026, 10, it, 18, 30) },
            starts(e, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), kolkata),
        )
    }

    @Test
    fun everySecondWeekCountsWeeksFromTheStartWeek() {
        val e = event(LocalDateTime.of(2026, 10, 5, 10, 0), kolkata, "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,FR")
        assertEquals(
            listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 23)),
            starts(e, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 26), kolkata).map { it.toLocalDate() },
        )
    }

    @Test
    fun monthlyOnThe31stSkipsShortMonths() {
        val e = event(LocalDateTime.of(2026, 1, 31, 8, 0), kolkata, "FREQ=MONTHLY")
        assertEquals(
            listOf(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 5, 31), LocalDate.of(2026, 7, 31)),
            starts(e, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 1), kolkata).map { it.toLocalDate() },
        )
        val leap = event(LocalDateTime.of(2028, 1, 29, 8, 0), kolkata, "FREQ=MONTHLY;BYMONTHDAY=29;COUNT=3")
        assertEquals(
            listOf(LocalDate.of(2028, 1, 29), LocalDate.of(2028, 2, 29), LocalDate.of(2028, 3, 29)),
            starts(leap, LocalDate.of(2028, 1, 1), LocalDate.of(2029, 1, 1), kolkata).map { it.toLocalDate() },
        )
    }

    @Test
    fun monthlyOnSeveralDays() {
        val e = event(LocalDateTime.of(2026, 10, 1, 9, 0), kolkata, "FREQ=MONTHLY;BYMONTHDAY=1,15;COUNT=5")
        assertEquals(
            listOf(
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 15),
                LocalDate.of(2026, 11, 1),
                LocalDate.of(2026, 11, 15),
                LocalDate.of(2026, 12, 1),
            ),
            starts(e, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 6, 1), kolkata).map { it.toLocalDate() },
        )
    }

    @Test
    fun aWeeklyEventKeepsItsLocalTimeAcrossTheDstChange() {
        // London: clocks go back on Sun 25 Oct 2026. 9:00 stays 9:00 local, so the UTC time moves by an hour.
        val e = event(LocalDateTime.of(2026, 10, 20, 9, 0), london, "FREQ=WEEKLY")
        val got = RecurrenceExpander.occurrences(e, 0, Long.MAX_VALUE, kolkata, limit = 2)
        assertEquals(
            listOf(LocalTime.of(9, 0), LocalTime.of(9, 0)),
            got.map {
                java.time.Instant.ofEpochMilli(it.startAt).atZone(london).toLocalTime()
            },
        )
        assertEquals(7 * 24 + 1, ((got[1].startAt - got[0].startAt) / 3_600_000L).toInt(), "one hour longer than a week")
        assertEquals(60 * 60_000L, got[1].endAt - got[1].startAt, "the length stays one hour")
    }

    @Test
    fun aTimeThatDoesNotExistMovesForwardAndAnHourThatHappensTwiceUsesTheFirst() {
        // New York: 8 Mar 2026 has no 2:30 (spring forward), 1 Nov 2026 has 1:30 twice (fall back).
        val gap = event(LocalDateTime.of(2026, 3, 7, 2, 30), newYork, "FREQ=DAILY;COUNT=3")
        assertEquals(
            listOf(LocalDateTime.of(2026, 3, 7, 2, 30), LocalDateTime.of(2026, 3, 8, 3, 30), LocalDateTime.of(2026, 3, 9, 2, 30)),
            starts(gap, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), newYork),
        )
        val overlap = event(LocalDateTime.of(2026, 10, 31, 1, 30), newYork, "FREQ=DAILY;COUNT=2")
        val second = RecurrenceExpander.occurrences(overlap, 0, Long.MAX_VALUE, newYork)[1]
        assertEquals(-4 * 3600, java.time.Instant.ofEpochMilli(second.startAt).atZone(newYork).offset.totalSeconds, "EDT, the first 1:30")
    }

    @Test
    fun skippedDatesAreLeftOutButStillCountForCount() {
        val start = LocalDateTime.of(2026, 10, 1, 9, 0)
        val e = event(start, kolkata, "FREQ=DAILY;COUNT=4", exdates = listOf(millis(start.plusDays(1), kolkata)))
        assertEquals(listOf(1, 3, 4), starts(e, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 1), kolkata).map { it.dayOfMonth })
    }

    @Test
    fun untilAsAUtcTimeIsIncluded() {
        val e = event(LocalDateTime.of(2026, 10, 1, 9, 0), kolkata, "FREQ=DAILY;UNTIL=20261003T033000Z") // 9:00 IST on 3 Oct
        assertEquals(listOf(1, 2, 3), starts(e, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 1), kolkata).map { it.dayOfMonth })
    }

    @Test
    fun theWindowCutsTheOccurrencesAndALongPastStartIsFast() {
        val e = event(LocalDateTime.of(2016, 1, 1, 7, 0), kolkata, "FREQ=DAILY")
        val t = System.nanoTime()
        val got = starts(e, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 13), kolkata)
        assertEquals(listOf(10, 11, 12), got.map { it.dayOfMonth })
        assertTrue(System.nanoTime() - t < 500_000_000L, "ten years of a daily event")
    }

    @Test
    fun anEventThatStartedBeforeTheWindowButIsStillOnIsIncluded() {
        val e = event(LocalDateTime.of(2026, 10, 10, 23, 0), kolkata, null, minutes = 120)
        assertEquals(1, starts(e, LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 12), kolkata).size)
    }

    @Test
    fun anUnsupportedRuleIsShownOnce() {
        val e = event(LocalDateTime.of(2026, 10, 1, 9, 0), kolkata, "FREQ=MONTHLY;BYDAY=1MO")
        val got = RecurrenceExpander.occurrences(e, 0, Long.MAX_VALUE, kolkata)
        assertEquals(1, got.size)
        assertFalse(got.single().recurrenceShown)
        assertTrue(
            RecurrenceExpander.occurrences(
                event(LocalDateTime.of(2026, 10, 1, 9, 0), kolkata, "FREQ=DAILY"),
                0,
                Long.MAX_VALUE,
                kolkata,
                3,
            ).all {
                it.recurrenceShown
            },
        )
    }

    @Test
    fun allDayEventsAreDaysInTheZoneOfThePersonWhoLooks() {
        // Made in New York, seen in Kolkata: still 2 Oct, from midnight to midnight in Kolkata.
        val e = allDay(LocalDate.of(2026, 10, 2), newYork, "FREQ=WEEKLY;COUNT=2", days = 2)
        val got = RecurrenceExpander.occurrences(e, 0, Long.MAX_VALUE, kolkata)
        assertEquals(listOf(LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 9)), got.map { it.date })
        assertEquals(LocalDate.of(2026, 10, 2).atStartOfDay(kolkata).toInstant().toEpochMilli(), got[0].startAt)
        assertEquals(LocalDate.of(2026, 10, 4).atStartOfDay(kolkata).toInstant().toEpochMilli(), got[0].endAt, "two days long")
    }

    /** Runs [block] on its own thread; fails when it does not end within 5 seconds (a rule that loops for ever). */
    private fun <T> endsQuickly(block: () -> T): T {
        var result: Result<T>? = null
        val t = Thread { result = runCatching(block) }.apply {
            isDaemon = true
            start()
            join(5_000)
        }
        assertFalse(t.isAlive, "the expansion did not end")
        return result!!.getOrThrow()
    }

    @Test
    fun aMonthlyRuleThatNoDayFitsEndsAtOnceAndKeepsTheStart() {
        // Every 12 months from February never reaches a 30th; every 12 months from April never reaches a 31st.
        val feb = event(LocalDateTime.of(2026, 2, 10, 9, 0), kolkata, "FREQ=MONTHLY;INTERVAL=12;BYMONTHDAY=30")
        val apr = event(LocalDateTime.of(2026, 4, 10, 9, 0), kolkata, "FREQ=MONTHLY;INTERVAL=12;BYMONTHDAY=31")
        for (e in listOf(feb, apr)) {
            val got = endsQuickly { RecurrenceExpander.occurrences(e, 0, Long.MAX_VALUE, kolkata) }
            assertEquals(listOf(e.startAt), got.map { it.startAt })
        }
    }

    @Test
    fun aTimedEventIsListedOnItsDayInTheViewZone() {
        // 02:00 on 4 Oct in Kolkata is 20:30 UTC on 3 Oct, and the event is stored with tz = UTC.
        val start = millis(LocalDateTime.of(2026, 10, 3, 20, 30), ZoneId.of("UTC"))
        val e = CalendarEvent("u1", "Early", start, start + 3_600_000, "UTC", rrule = "FREQ=DAILY;COUNT=3")
        val got = RecurrenceExpander.occurrences(e, 0, Long.MAX_VALUE, kolkata)
        assertEquals(listOf(4, 5, 6), got.map { it.date.dayOfMonth }, "the day in Kolkata, not in UTC")
        assertEquals(listOf(start), got.take(1).map { it.startAt })
    }

    @Test
    fun theEventZoneStillDecidesUntil() {
        // UNTIL 4 Oct (a UTC date): the 3 Oct and 4 Oct 20:30 UTC starts are in; 5 Oct is out.
        val start = millis(LocalDateTime.of(2026, 10, 3, 20, 30), ZoneId.of("UTC"))
        val e = CalendarEvent("u2", "x", start, start + 1, "UTC", rrule = "FREQ=DAILY;UNTIL=20261004")
        assertEquals(2, RecurrenceExpander.occurrences(e, 0, Long.MAX_VALUE, kolkata).size)
    }

    @Test
    fun anOldStartIsReachedWithoutWalkingFromIt() {
        val start = LocalDateTime.of(2000, 1, 3, 9, 0) // a Monday
        val from = LocalDate.of(2026, 10, 1)
        val to = LocalDate.of(2026, 11, 1)
        val rules = listOf("FREQ=DAILY", "FREQ=DAILY;INTERVAL=7", "FREQ=WEEKLY;INTERVAL=3;BYDAY=MO,FR", "FREQ=WEEKLY;BYDAY=TU,SU")
        for (rule in rules) {
            val r = RecurrenceRule.parse(rule)!!
            val got = endsQuickly { starts(event(start, kolkata, rule), from, to, kolkata) }
            // The same rule with a COUNT too large to matter: no jump, so every step is walked.
            val walked = starts(event(start, kolkata, "$rule;COUNT=100000"), from, to, kolkata)
            assertEquals(walked, got, rule)
            val week0 = LocalDate.of(2000, 1, 3)
            val expected = generateSequence(from) { it.plusDays(1) }.takeWhile { it.isBefore(to) }.filter { d ->
                val days = java.time.temporal.ChronoUnit.DAYS.between(week0, d)
                when (r.freq) {
                    RecurrenceRule.Freq.DAILY -> days % r.interval == 0L
                    else -> (days / 7) % r.interval == 0L && d.dayOfWeek in r.byDay
                }
            }.map { it.atTime(9, 0) }.toList()
            assertEquals(expected, got, rule)
        }
    }

    @Test
    fun yearlyRepeatsOnTheDayOfTheStartAndSkips29FebInOtherYears() {
        val leap = event(LocalDateTime.of(2024, 2, 29, 9, 0), kolkata, "FREQ=YEARLY;COUNT=3")
        assertEquals(
            listOf(2024, 2028, 2032),
            starts(leap, LocalDate.of(2020, 1, 1), LocalDate.of(2040, 1, 1), kolkata).map { it.year },
            "RFC 5545: 29 Feb does not exist in other years, so those years have no occurrence",
        )
        val everyTwo = event(LocalDateTime.of(2026, 10, 5, 9, 0), kolkata, "FREQ=YEARLY;INTERVAL=2;UNTIL=20311005")
        assertEquals(
            listOf(LocalDate.of(2026, 10, 5), LocalDate.of(2028, 10, 5), LocalDate.of(2030, 10, 5)),
            starts(everyTwo, LocalDate.of(2026, 1, 1), LocalDate.of(2040, 1, 1), kolkata).map { it.toLocalDate() },
        )
    }

    @Test
    fun yearlyWithMonthsAndDaysFollowsThem() {
        val e = event(LocalDateTime.of(2026, 3, 1, 9, 0), kolkata, "FREQ=YEARLY;BYMONTH=3,9;BYMONTHDAY=1,15")
        assertEquals(
            listOf(
                LocalDate.of(2026, 3, 1),
                LocalDate.of(2026, 3, 15),
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2027, 3, 1),
                LocalDate.of(2027, 3, 15),
            ),
            starts(e, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 4, 1), kolkata).map { it.toLocalDate() },
        )
        val rule = RecurrenceRule.parse("FREQ=YEARLY;INTERVAL=2;BYMONTH=9,3;BYMONTHDAY=15;COUNT=4")!!
        assertEquals("FREQ=YEARLY;INTERVAL=2;BYMONTH=3,9;BYMONTHDAY=15;COUNT=4", rule.format())
        assertEquals(listOf(3, 9), rule.byMonth)
        assertNull(RecurrenceRule.parse("FREQ=YEARLY;BYMONTH=13"))
        assertNull(RecurrenceRule.parse("FREQ=MONTHLY;BYMONTH=3"))
        assertNull(RecurrenceRule.parse("FREQ=YEARLY;BYMONTHDAY=3"), "a day without a month means every month")
    }

    @Test
    fun aYearlyRuleThatNoYearFitsEndsAtOnce() {
        // 29 Feb every 4 years from 2023 never meets a leap year.
        val e = event(LocalDateTime.of(2023, 2, 28, 9, 0), kolkata, "FREQ=YEARLY;INTERVAL=4;BYMONTH=2;BYMONTHDAY=29")
        val got = endsQuickly { RecurrenceExpander.occurrences(e, 0, Long.MAX_VALUE, kolkata) }
        assertEquals(listOf(e.startAt), got.map { it.startAt })
    }

    @Test
    fun recurrenceShownIsSetOnEachOccurrence() {
        val day = LocalDateTime.of(2026, 10, 5, 9, 0)
        val ok = RecurrenceExpander.occurrences(event(day, kolkata, "FREQ=DAILY;COUNT=2"), 0, Long.MAX_VALUE, kolkata)
        assertTrue(ok.all { it.recurrenceShown })
        val odd = RecurrenceExpander.occurrences(event(day, kolkata, "FREQ=HOURLY"), 0, Long.MAX_VALUE, kolkata)
        assertEquals(listOf(false), odd.map { it.recurrenceShown })
    }
}
