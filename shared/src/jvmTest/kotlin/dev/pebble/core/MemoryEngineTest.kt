package dev.pebble.core

import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.EventBus
import dev.pebble.core.event.EventLogger
import dev.pebble.core.event.PebbleEvent
import dev.pebble.core.memory.MemoryEngine
import dev.pebble.core.memory.MemoryEngine.Companion.KEY_ACTIVE
import dev.pebble.core.memory.MemoryEngine.Companion.KEY_DAYS_ACTIVE
import dev.pebble.core.memory.MemoryEngine.Companion.KEY_MOOD_WEEK
import dev.pebble.core.memory.MemoryEngine.Companion.KEY_QUIET
import dev.pebble.core.memory.MemoryEngine.Companion.KEY_WATER_HOURS
import dev.pebble.core.memory.MemoryEngine.Companion.KEY_WATER_STREAK
import dev.pebble.core.memory.MemoryRepository
import dev.pebble.core.quickadd.QuickAddParser
import dev.pebble.core.quickadd.QuickCommand
import dev.pebble.core.reminders.ReminderAction
import dev.pebble.core.reminders.ReminderEngine
import dev.pebble.core.reminders.ReminderKind
import dev.pebble.core.reminders.ReminderRepository
import dev.pebble.core.wellness.WaterRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryEngineTest {
    private val hour = 60 * 60_000L
    private val day = 24 * hour
    private val db = DatabaseFactory.inMemory()
    private val logger = EventLogger(db)
    private val memory = MemoryRepository(db)
    private val water = WaterRepository(db)
    private val day0 = 20_000 * day // an arbitrary UTC midnight
    private var now = day0 + 10 * day + 12 * hour
    private val engine = MemoryEngine(
        db,
        memory,
        clock = { now },
        hourOf = { ((it / hour) % 24).toInt() },
        dayOf = { it / day },
        waterGoalMl = { 2000 },
    )

    private fun at(d: Int, h: Int, m: Int = 0) = day0 + d * day + h * hour + m * 60_000L

    @Test
    fun learnsUsualWaterTimes() {
        for (d in 1..5) {
            logger.log(PebbleEvent.WaterLogged(250, 250, at(d, 11)))
            logger.log(PebbleEvent.WaterLogged(250, 500, at(d, 16)))
        }
        logger.log(PebbleEvent.WaterLogged(250, 750, at(3, 20))) // a one-off, not a habit
        engine.learn()
        val m = memory.byKey(KEY_WATER_HOURS)!!
        assertEquals("You usually drink water around 11 AM and 4 PM.", m.text)
        assertEquals("11,16", m.data)
    }

    @Test
    fun learnsQuietHoursAndReminderEngineRespectsThem() {
        for (d in 1..4) logger.log(PebbleEvent.ReminderActed("rule:water", ReminderKind.WATER, ReminderAction.SNOOZED, 10, at(d, 14, 5)))
        for (d in 1..4) logger.log(PebbleEvent.ReminderActed("rule:water", ReminderKind.WATER, ReminderAction.DONE, null, at(d, 10)))
        engine.learn()
        assertEquals(setOf(14), engine.quietHours())
        assertTrue(memory.byKey(KEY_QUIET)!!.text.contains("2 PM"))

        // The reminder engine holds repeating reminders during a learned quiet hour…
        val reminders = ReminderRepository(db).apply { seedDefaults() }
        var t = at(10, 14)
        val reminderEngine =
            ReminderEngine(reminders, EventBus(), { t }, { ((it / hour % 24) * 60 + it / 60_000 % 60).toInt() }, engine::quietHours)
        t += 3 * hour + 1 // well past every interval, but 17:00 is not quiet…
        reminderEngine.tick()
        assertTrue(reminderEngine.active.value.isNotEmpty())
        t = at(11, 14, 30) // …and the next day at 14:30, which is.
        reminderEngine.tick()
        assertTrue(reminderEngine.active.value.isEmpty())
    }

    @Test
    fun forgettingALearnedMemoryKeepsItForgotten() {
        for (d in 1..4) logger.log(PebbleEvent.ReminderActed("rule:eyes", ReminderKind.EYES, ReminderAction.DISMISSED, null, at(d, 9)))
        engine.learn()
        memory.forget(memory.byKey(KEY_QUIET)!!)
        engine.learn()
        assertNull(memory.byKey(KEY_QUIET))
        assertEquals(emptySet(), engine.quietHours())
    }

    @Test
    fun countsWaterStreakIncludingToday() {
        for (d in 7..10) repeat(8) { water.log(250, at(d, 9 + it)) }
        engine.learn()
        assertEquals("4", memory.byKey(KEY_WATER_STREAK)!!.data)
    }

    @Test
    fun learnsActiveHoursAndDayStreak() {
        for (d in 6..10) for (h in 10..17) logger.log(PebbleEvent.ActiveHour(h, at(d, h)))
        engine.learn()
        assertEquals("You're usually at your computer from 10 AM to 6 PM.", memory.byKey(KEY_ACTIVE)!!.text)
        assertEquals("5", memory.byKey(KEY_DAYS_ACTIVE)!!.data)
    }

    @Test
    fun summarisesMoodWeek() {
        for (d in 6..9) memory.logMood(4 + d % 2, at(d, 20))
        engine.learn()
        assertTrue(memory.byKey(KEY_MOOD_WEEK)!!.text.contains("good") || memory.byKey(KEY_MOOD_WEEK)!!.text.contains("great"))
    }

    @Test
    fun rememberCommandStoresAFact() {
        val cmd = QuickAddParser.parse("remember that my exam is on 20 Oct.")
        assertEquals(QuickCommand.RememberFact("my exam is on 20 Oct"), cmd)
        val key = memory.remember((cmd as QuickCommand.RememberFact).text, now)
        assertEquals("my exam is on 20 Oct", memory.byKey(key)!!.text)
        assertTrue(memory.byKey(key)!!.fromUser)
    }

    @Test
    fun learnedNudgeTimingBecomesAMemoryOnlyWithEvidence() {
        val policy = dev.pebble.core.brain.NudgePolicy(dev.pebble.core.brain.InMemoryNudgeStore())
        engine.nudge = policy
        val ctx = dev.pebble.core.brain.NudgeContext.of(dev.pebble.core.reminders.ReminderKind.WATER, 13, busy = false)
        repeat(3) { policy.learn(ctx, dev.pebble.core.brain.NudgeArm.WAIT_30, 1.0) }
        engine.learn()
        assertTrue(memory.visible().none { it.key.startsWith(MemoryEngine.NUDGE_PREFIX) }, "3 reactions are not enough")
        repeat(6) {
            policy.learn(ctx, dev.pebble.core.brain.NudgeArm.WAIT_30, 1.0)
            policy.learn(ctx, dev.pebble.core.brain.NudgeArm.NOW, 0.0)
        }
        engine.learn()
        val m = memory.visible().single { it.key.startsWith(MemoryEngine.NUDGE_PREFIX) }
        assertTrue("30 min with water reminders between 12 PM and 4 PM" in m.text, m.text)
    }
}
