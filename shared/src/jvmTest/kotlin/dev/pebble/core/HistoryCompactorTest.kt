package dev.pebble.core

import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.EventLogger
import dev.pebble.core.event.PebbleEvent
import dev.pebble.core.growth.GrowthEngine
import dev.pebble.core.history.HistoryCompactor
import dev.pebble.core.memory.MemoryEngine
import dev.pebble.core.memory.MemoryEngine.Companion.KEY_NOTES_DONE
import dev.pebble.core.memory.MemoryRepository
import dev.pebble.core.reminders.ReminderAction
import dev.pebble.core.reminders.ReminderKind
import dev.pebble.core.wellness.WaterRepository
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Rolling old days into daily_stat changes no number the pet or its memories show, and deletes nothing. */
class HistoryCompactorTest {
    private val hour = 60 * 60_000L
    private val day = 24 * hour
    private val day0 = 20_000 * day
    private val dayOf = { t: Long -> t / day }
    private val db = DatabaseFactory.inMemory()
    private val logger = EventLogger(db)
    private val growth = GrowthEngine(db, dayOf, waterGoalMl = { 2000 })
    private val compactor = HistoryCompactor(db, dayOf)
    private var now = day0 + 400 * day
    private val memory = MemoryRepository(db)
    private val brain =
        MemoryEngine(db, memory, clock = { now }, hourOf = { ((it / hour) % 24).toInt() }, dayOf = dayOf, waterGoalMl = { 2000 })

    private fun rawRows() = db.pebbleQueries.recentEvents(Long.MAX_VALUE).executeAsList().size

    /** 400 days: most days active, reminders acted on in every way, some notes finished, water most days. */
    private fun seed(fromDay: Int, toDay: Int, rnd: Random = Random(7)) {
        val water = WaterRepository(db)
        for (d in fromDay until toDay) {
            val base = day0 + d * day
            if (rnd.nextInt(10) < 8) logger.log(PebbleEvent.ActiveHour(9, base + 9 * hour))
            repeat(rnd.nextInt(4)) {
                logger.log(
                    PebbleEvent.ReminderActed(
                        "rule:water",
                        ReminderKind.WATER,
                        ReminderAction.entries[rnd.nextInt(3)],
                        atMillis =
                        base + (10 + it) * hour,
                    ),
                )
            }
            if (rnd.nextInt(5) == 0) logger.log(PebbleEvent.NoteCompleted(d.toLong(), base + 18 * hour))
            repeat(rnd.nextInt(10)) { water.log(250, base + (8 + it) * hour) }
            logger.log(PebbleEvent.AppStarted(base + 8 * hour))
        }
    }

    @Test
    fun growthIsIdenticalBeforeAndAfterRollUp() {
        seed(0, 400)
        val before = growth.stats()
        val rows = rawRows()
        val rolled = compactor.rollUpBefore(day0 + 393 * day)
        assertEquals(before, growth.stats())
        assertEquals(before.let(dev.pebble.core.growth.GrowthRules::growth), growth.growth())
        assertEquals(rows, rawRows(), "raw rows are kept")
        assertEquals(true, rolled > 1_000)
    }

    @Test
    fun rollingUpTwiceCountsNothingTwice() {
        seed(0, 400)
        val before = growth.stats()
        compactor.rollUpBefore(day0 + 300 * day)
        assertEquals(0, compactor.rollUpBefore(day0 + 300 * day))
        compactor.rollUpBefore(day0 + 393 * day) // the watermark moves on by whole days
        assertEquals(before, growth.stats())
    }

    @Test
    fun newEntriesAfterTheWatermarkStillCount() {
        seed(0, 300)
        compactor.rollUpBefore(day0 + 290 * day)
        seed(300, 400, Random(8))
        val withRollUp = growth.stats()
        // The same history, never rolled up:
        val plain = HistoryCompactorTest().also { it.seed(0, 300); it.seed(300, 400, Random(8)) }.growth.stats()
        assertEquals(plain, withRollUp)
    }

    @Test
    fun theFinishedNotesMemoryKeepsItsCount() {
        seed(0, 400)
        brain.learn()
        val before = memory.byKey(KEY_NOTES_DONE)?.text
        compactor.rollUpBefore(day0 + 393 * day)
        brain.learn()
        assertEquals(before, memory.byKey(KEY_NOTES_DONE)?.text)
    }

    @Test
    fun onlyRealDoneActionsCountNotTextThatLooksLikeOne() {
        // The old check matched the text "action":"DONE" anywhere in the JSON, so this snooze counted as done.
        logger.log(PebbleEvent.ReminderActed("\"action\":\"DONE\"", ReminderKind.WATER, ReminderAction.SNOOZED, atMillis = day0))
        logger.log(PebbleEvent.ReminderActed("rule:water", ReminderKind.WATER, ReminderAction.DONE, atMillis = day0 + hour))
        assertEquals(1, growth.stats().remindersDone)
        compactor.rollUpBefore(day0 + day)
        assertEquals(1, growth.stats().remindersDone, "and the same after the roll-up")
    }
}
