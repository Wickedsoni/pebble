package dev.pebble.core.growth

import dev.pebble.core.history.EventHistory
import dev.pebble.core.reminders.ReminderAction
import dev.pebble.db.PebbleDatabase

/** The pet's growth levels, earned in order. The desktop pet draws each with its own size and accessory. */
enum class Level(val label: String) { BABY("Baby"), TEEN("Teen"), ADULT("Adult"), LEGENDARY("Legendary") }

/** One requirement for the next level: [current] out of [target]. */
data class Task(val label: String, val current: Int, val target: Int) {
    val done: Boolean get() = current >= target
    val progress: Float get() = (current.toFloat() / target).coerceIn(0f, 1f)
}

/** Where the pet is: the highest level earned, and what's still needed for the next one (null at the top). */
data class Growth(val earned: Level, val next: Level?, val tasks: List<Task>) {
    fun unlocked(level: Level): Boolean = level <= earned
}

/** What you've done with Pebble, counted from its local data. */
data class GrowthStats(
    val activeDays: Int,
    val remindersDone: Int,
    val waterGoalDays: Int,
    val bestWaterStreak: Int,
    val notesDone: Int,
    val chats: Int,
)

/**
 * The pet grows by what you actually do, not by a button: every level has tasks built from healthy
 * habits Pebble already tracks (days you're around, reminders you complete, water goals, notes, chats).
 * Pure counting over the local database — no extra tracking.
 */
object GrowthRules {
    fun requirements(level: Level, s: GrowthStats): List<Task> = when (level) {
        Level.BABY -> emptyList()

        Level.TEEN -> listOf(
            Task("Use Pebble on 3 days", s.activeDays, 3),
            Task("Finish 10 reminders", s.remindersDone, 10),
            Task("Reach your water goal once", s.waterGoalDays, 1),
            Task("Chat with Pebble 5 times", s.chats, 5),
        )

        Level.ADULT -> listOf(
            Task("Use Pebble on 10 days", s.activeDays, 10),
            Task("Finish 50 reminders", s.remindersDone, 50),
            Task("Reach your water goal on 5 days", s.waterGoalDays, 5),
            Task("Complete 5 notes", s.notesDone, 5),
        )

        Level.LEGENDARY -> listOf(
            Task("Use Pebble on 30 days", s.activeDays, 30),
            Task("Finish 200 reminders", s.remindersDone, 200),
            Task("Keep a 7-day water streak", s.bestWaterStreak, 7),
            Task("Chat with Pebble 100 times", s.chats, 100),
        )
    }

    fun growth(s: GrowthStats): Growth {
        var earned = Level.BABY
        for (level in Level.entries.drop(1)) {
            if (requirements(level, s).all { it.done }) earned = level else break
        }
        val next = Level.entries.getOrNull(earned.ordinal + 1)
        return Growth(earned, next, next?.let { requirements(it, s) }.orEmpty())
    }
}

/** Counts [GrowthStats] from the database. [dayOf] maps a timestamp to a local calendar day number. */
class GrowthEngine(
    private val db: PebbleDatabase,
    private val dayOf: (Long) -> Long,
    private val waterGoalMl: () -> Int,
) {
    /** Old days come from `daily_stat`, recent ones from the raw log (WP B6), so this stays fast on years of history. */
    private val history = EventHistory(db, dayOf)

    fun stats(): GrowthStats {
        val activeDays = history.days("active_hour").size
        val done = history.count("reminder_acted", ReminderAction.DONE.name).toInt()
        val goal = waterGoalMl()
        val perDay = db.wellnessQueries.waterLogSince(0).executeAsList().groupBy { dayOf(it.at_millis) }
            .mapValues { (_, rows) -> rows.sumOf { it.ml } }
        val goalDays = perDay.filterValues { it >= goal }.keys.sorted()
        var best = 0
        var run = 0
        var prev: Long? = null
        for (d in goalDays) {
            run = if (prev != null && d == prev + 1) run + 1 else 1
            best = maxOf(best, run)
            prev = d
        }
        return GrowthStats(
            activeDays = activeDays,
            remindersDone = done,
            waterGoalDays = goalDays.size,
            bestWaterStreak = best,
            notesDone = history.count("note_completed").toInt(),
            chats = db.brainQueries.turnCount().executeAsOne().toInt(),
        )
    }

    fun growth(): Growth = GrowthRules.growth(stats())
}
