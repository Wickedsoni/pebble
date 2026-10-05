package dev.pebble.core

import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.EventLogger
import dev.pebble.core.event.PebbleEvent
import dev.pebble.core.growth.GrowthEngine
import dev.pebble.core.memory.MemoryEngine
import dev.pebble.core.memory.MemoryRepository
import dev.pebble.core.reminders.ReminderAction
import dev.pebble.core.reminders.ReminderKind
import dev.pebble.core.wellness.WaterRepository
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test

/**
 * How long growth and learning take on a big history (WP B6): 200 000 events — about 8 years at the
 * ~70 events a day a real install logs — in the real mix of event types. Opt-in: `PEBBLE_BENCH=1`.
 */
class GrowthBenchmarkTest {
    private val hour = 60 * 60_000L
    private val day = 24 * hour

    @Test
    fun timeStatsAndLearnOn200kEvents() {
        if (System.getenv("PEBBLE_BENCH") != "1") { println("SKIPPED: set PEBBLE_BENCH=1 to run the history benchmark"); return }
        val file = Files.createTempFile("pebble-bench", ".db").toFile().apply { delete(); deleteOnExit() }
        val db = DatabaseFactory.create(file)
        val logger = EventLogger(db)
        val water = WaterRepository(db)
        val total = 200_000
        val days = total / 70
        val start = 20_000 * day
        val now = start + days * day
        val rnd = Random(42)
        // The real mix (event_log of an install, Oct 2026), by weight.
        val mix = listOf(
            "reminder_due" to 30, "quick_add" to 24, "nudge_decided" to 21, "active_hour" to 16, "pet_interaction" to 11,
            "water_logged" to 10, "app_started" to 10, "reminder_acted" to 9, "app_stopping" to 9, "widget_visibility" to 4,
            "note_created" to 2, "note_completed" to 1, "mood_logged" to 1, "fact_remembered" to 1,
        )
        val pick = mix.flatMap { (t, w) -> List(w) { t } }
        val t0 = System.nanoTime()
        db.transaction {
            for (i in 0 until total) {
                val at = start + (i.toLong() * days * day) / total + rnd.nextLong(hour)
                val e = when (pick[rnd.nextInt(pick.size)]) {
                    "reminder_due" -> PebbleEvent.ReminderDue("rule:water", ReminderKind.WATER, "Drink water", at)

                    "quick_add" -> PebbleEvent.QuickAddUsed("AddNote", at)

                    "nudge_decided" -> PebbleEvent.NudgeDecided("rule:eyes", "h${rnd.nextInt(24)}", "now", 0.5, at)

                    "active_hour" -> PebbleEvent.ActiveHour(((at / hour) % 24).toInt(), at)

                    "pet_interaction" -> PebbleEvent.PetInteraction("click", at)

                    "water_logged" -> PebbleEvent.WaterLogged(250, 250, at).also { water.log(250, at) }

                    "app_started" -> PebbleEvent.AppStarted(at)

                    "reminder_acted" -> PebbleEvent.ReminderActed(
                        "rule:water",
                        ReminderKind.WATER,
                        ReminderAction.entries[rnd.nextInt(3)],
                        atMillis = at,
                    )

                    "app_stopping" -> PebbleEvent.AppStopping(at)

                    "widget_visibility" -> PebbleEvent.WidgetVisibilityChanged("pet", true, at)

                    "note_created" -> PebbleEvent.NoteCreated(i.toLong(), at)

                    "note_completed" -> PebbleEvent.NoteCompleted(i.toLong(), at)

                    "mood_logged" -> PebbleEvent.MoodLogged(3, at)

                    else -> PebbleEvent.FactRemembered("fact:$i", at)
                }
                logger.log(e)
            }
        }
        println("BENCH inserted $total events over $days days in ${(System.nanoTime() - t0) / 1_000_000} ms")

        val growth = GrowthEngine(db, dayOf = { it / day }, waterGoalMl = { 2000 })
        val brain =
            MemoryEngine(db, MemoryRepository(db), clock = {
                now
            }, hourOf = { ((it / hour) % 24).toInt() }, dayOf = { it / day }, waterGoalMl = { 2000 })
        println("BENCH stats() median ${median { growth.stats() }} ms -> ${growth.stats()}")
        println("BENCH learn() median ${median { brain.learn() }} ms")
        val rolledUp = dev.pebble.core.history.HistoryCompactor(db) { it / day }
        val t1 = System.nanoTime()
        val n = rolledUp.rollUpBefore(now - 7 * day)
        println("BENCH roll-up of $n entries (once, then daily) in ${(System.nanoTime() - t1) / 1_000_000} ms")
        println("BENCH after roll-up: stats() median ${median { growth.stats() }} ms -> ${growth.stats()}")
        println("BENCH after roll-up: learn() median ${median { brain.learn() }} ms")
    }

    /** Median wall time of 5 runs after one warm-up. */
    private fun median(block: () -> Unit): Long {
        block()
        return List(5) {
            val t = System.nanoTime()
            block()
            (System.nanoTime() - t) / 1_000_000
        }.sorted()[2]
    }
}
