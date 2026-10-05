package dev.pebble.core.memory

import dev.pebble.core.brain.NudgeArm
import dev.pebble.core.brain.NudgeContext
import dev.pebble.core.brain.NudgePolicy
import dev.pebble.core.event.PebbleEvent
import dev.pebble.core.history.EventHistory
import dev.pebble.core.reminders.ReminderAction
import dev.pebble.core.reminders.ReminderKind
import dev.pebble.db.PebbleDatabase
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/**
 * Turns the raw event log into memories: habits and timing, streaks, mood trends and what you
 * watch. Runs locally and deterministically — no AI needed — and is cheap enough to run every
 * few minutes. Time zone handling is injected ([hourOf], [dayOf]) so tests control it.
 *
 * Everything it learns is plain text you can read and delete in the app's Memory page.
 */
class MemoryEngine(
    private val db: PebbleDatabase,
    private val memory: MemoryRepository,
    private val clock: () -> Long,
    /** Local hour of day (0-23) for a timestamp. */
    private val hourOf: (Long) -> Int,
    /** Local calendar day number (e.g. epoch day) for a timestamp. */
    private val dayOf: (Long) -> Long,
    private val waterGoalMl: () -> Int,
    /** The nudge policy, whose learned timing is shown as a memory. Set after construction (it reads [quietHours]). */
    var nudge: NudgePolicy? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Hours Pebble keeps repeating reminders quiet in, learned from what you skip. */
    fun quietHours(): Set<Int> =
        memory.byKey(KEY_QUIET)?.data?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: emptySet()

    fun learn() {
        val now = clock()
        val since = now - WINDOW_DAYS * DAY
        learnWaterTiming(since, now)
        learnReminderResponses(since, now)
        learnActiveHours(since, now)
        learnStreaks(now)
        learnMood(now)
        learnMedia(now)
        learnNudgeTiming(now)
    }

    /**
     * What the nudge policy learned, in words, once there's real evidence (≥ 5 reactions): "I wait a
     * bit with water reminders around 12–4 pm". Forgetting it resets that context (PebbleApp.forget).
     */
    private fun learnNudgeTiming(now: Long) {
        val policy = nudge ?: return
        for (kind in listOf(ReminderKind.WATER, ReminderKind.STRETCH, ReminderKind.EYES)) {
            for (bucket in 0 until 6) {
                val ctx = NudgeContext(kind, bucket, busy = false)
                val key = nudgeMemoryKey(ctx)
                val e = policy.expected(ctx)
                val best = e.maxBy { it.value }
                if (policy.observations(ctx) >= 5 && best.key != NudgeArm.NOW && best.value - e.getValue(NudgeArm.NOW) >= 0.15) {
                    val from = formatHour(bucket * 4)
                    val to = formatHour((bucket * 4 + 4) % 24)
                    memory.putDerived(
                        MemoryKind.HABIT,
                        key,
                        "I wait about ${best.key.waitMinutes} min with ${kindName(
                            kind,
                        )} reminders between $from and $to — they land better then.",
                        best.key.name,
                        now,
                    )
                } else {
                    memory.dropDerived(key)
                }
            }
        }
    }

    // ------------------------------------------------------------------ habits & timing

    private fun learnWaterTiming(since: Long, now: Long) {
        val hours = events<PebbleEvent.WaterLogged>("water_logged", since).groupingBy { hourOf(it.atMillis) }.eachCount()
        val usual = hours.filterValues { it >= 3 }.entries.sortedByDescending { it.value }.take(3).map { it.key }.sorted()
        if (usual.isEmpty()) return memory.dropDerived(KEY_WATER_HOURS)
        memory.putDerived(
            MemoryKind.HABIT,
            KEY_WATER_HOURS,
            "You usually drink water around ${listHours(usual)}.",
            usual.joinToString(","),
            now,
        )
    }

    private fun learnReminderResponses(since: Long, now: Long) {
        val acted = events<PebbleEvent.ReminderActed>("reminder_acted", since)

        for (kind in ReminderKind.entries) {
            val key = "habit.reminder.${kind.name.lowercase()}"
            val mine = acted.filter { it.kind == kind }
            val skipped = mine.count { it.action != ReminderAction.DONE }
            if (mine.size >= 5 && skipped >= mine.size * 0.6) {
                memory.putDerived(MemoryKind.HABIT, key, "You often snooze or skip ${kindName(kind)} reminders.", null, now)
            } else if (mine.size >= 5 && skipped <= mine.size * 0.2) {
                memory.putDerived(MemoryKind.HABIT, key, "You almost always act on ${kindName(kind)} reminders.", null, now)
            } else {
                memory.dropDerived(key)
            }
        }

        // Quiet hours: an hour where you skip nearly every reminder is a bad time to nag.
        val quiet = acted.groupBy { hourOf(it.atMillis) }
            .filter { (_, list) -> list.size >= 3 && list.count { it.action != ReminderAction.DONE } >= list.size * 0.8 }
            .keys.sorted()
        if (quiet.isEmpty()) return memory.dropDerived(KEY_QUIET)
        memory.putDerived(
            MemoryKind.HABIT,
            KEY_QUIET,
            "I stay quiet around ${listHours(quiet)} — you usually skip reminders then.",
            quiet.joinToString(","),
            now,
        )
    }

    private fun learnActiveHours(since: Long, now: Long) {
        // One sample per (day, hour), even if the app restarted mid-hour and logged twice.
        val active = events<PebbleEvent.ActiveHour>("active_hour", since).distinctBy { dayOf(it.atMillis) to it.hour }
        val days = active.map { dayOf(it.atMillis) }.distinct().size
        if (days < 3) return
        val hours = active.groupingBy { it.hour }.eachCount().filterValues { it >= days * 0.5 }.keys.sorted()
        if (hours.isEmpty()) return memory.dropDerived(KEY_ACTIVE)
        memory.putDerived(
            MemoryKind.HABIT,
            KEY_ACTIVE,
            "You're usually at your computer from ${formatHour(hours.first())} to ${formatHour((hours.last() + 1) % 24)}.",
            hours.joinToString(","),
            now,
        )
    }

    // ------------------------------------------------------------------ streaks & progress

    private fun learnStreaks(now: Long) {
        val today = dayOf(now)
        val goal = waterGoalMl()
        val perDay = db.wellnessQueries.waterLogSince(now - 60 * DAY).executeAsList()
            .groupBy { dayOf(it.at_millis) }.mapValues { (_, rows) -> rows.sumOf { it.ml } }
        // Today still counts as "in progress", so the streak may end yesterday.
        var day = if ((perDay[today] ?: 0) >= goal) today else today - 1
        var streak = 0
        while ((perDay[day] ?: 0) >= goal) { streak++; day-- }
        if (streak >= 2) {
            memory.putDerived(MemoryKind.STREAK, KEY_WATER_STREAK, "Water goal met $streak days in a row.", "$streak", now)
        } else {
            memory.dropDerived(KEY_WATER_STREAK)
        }

        val finished = EventHistory(db, dayOf).count("note_completed")
        if (finished >= 3) memory.putDerived(MemoryKind.STREAK, KEY_NOTES_DONE, "You've finished $finished notes.", "$finished", now)

        val activeDays = events<PebbleEvent.ActiveHour>("active_hour", now - 60 * DAY).map { dayOf(it.atMillis) }.toSet()
        var d = today
        var run = 0
        while (d in activeDays) { run++; d-- }
        if (run >= 3) {
            memory.putDerived(MemoryKind.STREAK, KEY_DAYS_ACTIVE, "We've hung out $run days in a row.", "$run", now)
        } else {
            memory.dropDerived(KEY_DAYS_ACTIVE)
        }
    }

    // ------------------------------------------------------------------ mood

    private fun learnMood(now: Long) {
        val week = memory.moodSince(now - 7 * DAY)
        if (week.size < 3) return
        val avg = week.map { it.score }.average()
        val word = when {
            avg >= 4.3 -> "great"
            avg >= 3.5 -> "mostly good"
            avg >= 2.5 -> "mixed"
            else -> "a bit low"
        }
        memory.putDerived(
            MemoryKind.MOOD,
            KEY_MOOD_WEEK,
            "Your mood this week has been $word.",
            ((avg * 10).roundToInt() / 10.0).toString(),
            now,
        )

        // Does hitting the water goal line up with better days?
        val goal = waterGoalMl()
        val month = memory.moodSince(now - 30 * DAY).groupBy { dayOf(it.atMillis) }.mapValues { (_, m) -> m.map { it.score }.average() }
        val water = db.wellnessQueries.waterLogSince(now - 30 * DAY).executeAsList()
            .groupBy { dayOf(it.at_millis) }.mapValues { (_, rows) -> rows.sumOf { it.ml } }
        val (hit, missed) = month.entries.partition { (water[it.key] ?: 0) >= goal }
        if (hit.size >= 3 && missed.size >= 3 && hit.map { it.value }.average() - missed.map { it.value }.average() >= 0.5) {
            memory.putDerived(MemoryKind.MOOD, KEY_MOOD_WATER, "You feel better on days you hit your water goal.", null, now)
        }
    }

    // ------------------------------------------------------------------ media (opt-in)

    private fun learnMedia(now: Long) {
        val recent = memory.mediaSince(now - 30 * DAY)
        if (recent.isEmpty()) {
            memory.dropDerived(KEY_MEDIA_APPS); memory.dropDerived(KEY_MEDIA_RECENT)
            return
        }
        val apps = recent.groupingBy { it.app }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }
        memory.putDerived(MemoryKind.MEDIA, KEY_MEDIA_APPS, "Lately you mostly watch on ${joinWords(apps)}.", null, now)
        val titles = recent.map { it.title }.distinct().take(4)
        memory.putDerived(MemoryKind.MEDIA, KEY_MEDIA_RECENT, "Recently watched: ${titles.joinToString(" · ")}.", null, now)
    }

    // ------------------------------------------------------------------ helpers

    private inline fun <reified E : PebbleEvent> events(type: String, since: Long): List<E> =
        db.pebbleQueries.eventsOfTypeSince(type, since).executeAsList()
            .mapNotNull { runCatching { json.decodeFromString(PebbleEvent.serializer(), it.payload) }.getOrNull() as? E }

    private fun kindName(k: ReminderKind) = when (k) {
        ReminderKind.WATER -> "water"
        ReminderKind.STRETCH -> "stretch"
        ReminderKind.EYES -> "eye-break"
        ReminderKind.CUSTOM -> "custom"
    }

    companion object {
        private const val DAY = 24 * 60 * 60_000L
        private const val WINDOW_DAYS = 14

        const val KEY_WATER_HOURS = "habit.water.hours"
        const val KEY_QUIET = "habit.quietHours"
        const val NUDGE_PREFIX = "habit.nudge."

        fun nudgeMemoryKey(ctx: NudgeContext) = "$NUDGE_PREFIX${ctx.kind.name}:${ctx.hourBucket}"
        const val KEY_ACTIVE = "habit.activeHours"
        const val KEY_WATER_STREAK = "streak.water"
        const val KEY_NOTES_DONE = "streak.notes"
        const val KEY_DAYS_ACTIVE = "streak.days"
        const val KEY_MOOD_WEEK = "mood.week"
        const val KEY_MOOD_WATER = "mood.water"
        const val KEY_MEDIA_APPS = "media.apps"
        const val KEY_MEDIA_RECENT = "media.recent"

        fun formatHour(h: Int): String = when {
            h == 0 -> "12 AM"
            h < 12 -> "$h AM"
            h == 12 -> "12 PM"
            else -> "${h - 12} PM"
        }

        fun listHours(hours: List<Int>) = joinWords(hours.map(::formatHour))

        private fun joinWords(items: List<String>) = when (items.size) {
            0 -> ""
            1 -> items[0]
            else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
        }
    }
}
