package dev.pebble.core

import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.EventLogger
import dev.pebble.core.event.PebbleEvent
import dev.pebble.core.memory.MemoryEngine
import dev.pebble.core.memory.MemoryEngine.Companion.KEY_ACTIVE
import dev.pebble.core.memory.MemoryEngine.Companion.KEY_MOOD_WATER
import dev.pebble.core.memory.MemoryEngine.Companion.KEY_MOOD_WEEK
import dev.pebble.core.memory.MemoryRepository
import dev.pebble.core.wellness.WaterRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Memories that were learned once must go when their evidence goes, and the active-hours text must be true. */
class MemoryEngineStaleTest {
    private val hour = 60 * 60_000L
    private val day = 24 * hour
    private val db = DatabaseFactory.inMemory()
    private val logger = EventLogger(db)
    private val memory = MemoryRepository(db)
    private val water = WaterRepository(db)
    private val day0 = 20_000 * day
    private var now = day0 + 10 * day + 12 * hour
    private val engine = MemoryEngine(
        db,
        memory,
        clock = { now },
        hourOf = { ((it / hour) % 24).toInt() },
        dayOf = { it / day },
        waterGoalMl = { 2000 },
    )

    private fun at(d: Int, h: Int) = day0 + d * day + h * hour

    private fun activeText(hours: List<Int>): String {
        for (d in 6..10) for (h in hours) logger.log(PebbleEvent.ActiveHour(h, at(d, h)))
        engine.learn()
        return memory.byKey(KEY_ACTIVE)!!.text
    }

    @Test
    fun activeHoursGoWhenTheEvidenceExpires() {
        activeText(listOf(10, 11))
        assertNotNull(memory.byKey(KEY_ACTIVE))
        now += 20 * day // the 14-day window no longer holds a single active day
        engine.learn()
        assertNull(memory.byKey(KEY_ACTIVE))
    }

    @Test
    fun theMoodWeekGoesOnAQuietWeekButTheMonthlyWaterLinkStays() {
        for (d in 4..6) water.log(2000, at(d, 12))
        for (d in 4..6) memory.logMood(5, at(d, 20))
        for (d in 7..9) memory.logMood(2, at(d, 20))
        engine.learn()
        assertNotNull(memory.byKey(KEY_MOOD_WEEK))
        assertNotNull(memory.byKey(KEY_MOOD_WATER))
        now += 12 * day // the month still holds the entries, the week holds none
        engine.learn()
        assertNull(memory.byKey(KEY_MOOD_WEEK))
        assertNotNull(memory.byKey(KEY_MOOD_WATER), "the water link uses 30 days, not the week")
        now += 30 * day // now the month is empty too
        engine.learn()
        assertNull(memory.byKey(KEY_MOOD_WATER))
    }

    @Test
    fun moodWaterGoesWhenTheLinkNoLongerHolds() {
        for (d in 4..6) water.log(2000, at(d, 12))
        for (d in 4..6) memory.logMood(5, at(d, 20))
        for (d in 7..9) memory.logMood(2, at(d, 20))
        engine.learn()
        assertNotNull(memory.byKey(KEY_MOOD_WATER))
        for (d in 7..9) water.log(2000, at(d, 12)) // every day hit the goal now: nothing to compare
        engine.learn()
        assertNull(memory.byKey(KEY_MOOD_WATER))
    }

    @Test
    fun activeHoursDescribeTheLongestRun() {
        assertEquals("You're usually at your computer from 9 AM to 11 AM.", activeText(listOf(9, 10, 22)))
    }

    @Test
    fun activeHoursRunAcrossMidnight() {
        assertEquals("You're usually at your computer from 11 PM to 2 AM.", activeText(listOf(0, 1, 23)))
    }

    @Test
    fun activeHoursTieGoesToTheEarlierStart() {
        assertEquals("You're usually at your computer from 1 AM to 3 AM.", activeText(listOf(1, 2, 10, 11)))
    }

    @Test
    fun activeAllDayIsNotWrittenAsAnEmptyRange() {
        assertEquals("You're usually at your computer around the clock.", activeText((0..23).toList()))
    }
}
