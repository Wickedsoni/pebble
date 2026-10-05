package dev.pebble.core

import dev.pebble.core.SyncMergeTest.Replica
import dev.pebble.core.calendar.CalendarEvent
import dev.pebble.core.calendar.Visibility
import dev.pebble.core.reminders.Strictness
import dev.pebble.core.sync.ApplyResult
import dev.pebble.core.sync.ChangeBatch
import dev.pebble.core.sync.Cursor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The gate of WP E3 (spec section 9; 300 sequences on `main`, 60 on each PR): three devices make random changes with skewed clocks, then exchange their
 * journals in a random order, in random batch sizes, with duplicates and stale batches sent again. After each
 * exchange all three hold the same synced fields for every row (live and tombstones) and the same journal.
 */
class ConvergenceTest {
    private val minute = 60_000L

    /**
     * 60 sequences on each PR (fast CI); the full gate of the spec (300) on each push to `main`, from "Run workflow" in
     * GitHub Actions, or locally: `PEBBLE_CONVERGENCE_SEQUENCES=300 ./gradlew :shared:jvmTest --tests "*ConvergenceTest*"`.
     */
    private val sequences = System.getenv("PEBBLE_CONVERGENCE_SEQUENCES")?.toIntOrNull()?.takeIf { it > 0 } ?: 60

    /** Event uids from a small pool, so that two devices often make or change "the same" event (an ICS import). */
    private val eventUids = List(12) { "ev$it" }

    private fun step(r: Replica, rnd: Random) {
        val notes = r.db.wellnessQueries.activeNotes(1_000).executeAsList()
        val reminders = r.reminders.pendingOneOffs().filter { it.title.startsWith("rem") } // not the calendar-made ones
        val events = r.calendar.live()
        when (rnd.nextInt(12)) {
            0, 1 -> r.notes.add("note ${rnd.nextInt(1000)}", r.now)

            2 -> notes.randomOrNull(rnd)?.let { r.notes.update(it.id, "edited ${rnd.nextInt(1000)}", r.now) }

            3 -> notes.randomOrNull(rnd)?.let { r.notes.archive(it.id, r.now) }

            4 -> notes.randomOrNull(rnd)?.let { r.notes.delete(it.id, r.now) }

            5 -> r.reminders.addOneOff(
                "rem ${rnd.nextInt(1000)}",
                r.now + rnd.nextLong(minute, 600 * minute),
                Strictness.entries.random(rnd),
                r.now,
            )

            6 -> reminders.randomOrNull(rnd)?.let { r.reminders.rescheduleOneOff(it.id, r.now + rnd.nextLong(minute, 600 * minute), r.now) }

            7 -> reminders.randomOrNull(rnd)?.let {
                if (rnd.nextBoolean()) r.reminders.markOneOffDone(it.id, r.now) else r.reminders.deleteOneOff(it.id, r.now)
            }

            8, 9 -> {
                // Add, or import again, an event with a uid from the pool: a deleted one is skipped (D5).
                val uid = eventUids.random(rnd)
                val start = 1_790_100_000_000L + rnd.nextInt(48) * 30 * minute
                val e = CalendarEvent(
                    uid,
                    "event ${rnd.nextInt(100)}",
                    start,
                    start + 30 * minute,
                    "Asia/Kolkata",
                    remindMinutes = listOf(null, 15, 60).random(rnd),
                    visibility = Visibility.entries.random(rnd),
                    rrule = listOf(null, "FREQ=DAILY", "FREQ=WEEKLY;BYDAY=MO").random(rnd),
                )
                if (r.calendar.save(e, r.now)) r.agenda.eventChanged(uid, r.now)
            }

            10 -> events.randomOrNull(rnd)?.let {
                r.calendar.save(it.copy(exdates = (it.exdates + it.startAt).distinct()), r.now)
                r.agenda.eventChanged(it.uid, r.now)
            }

            else -> events.randomOrNull(rnd)?.let {
                r.calendar.delete(it.uid, r.now)
                r.agenda.eventChanged(it.uid, r.now)
            }
        }
    }

    /** Exchanges in a random order until no pull brings anything new; also re-sends old batches and duplicates. */
    private fun exchange(
        replicas: List<Replica>,
        cursors: MutableMap<Pair<Int, Int>, Cursor?>,
        rnd: Random,
        sent: MutableList<Triple<Int, Int, ChangeBatch>>,
    ) {
        val pairs = replicas.indices.flatMap { i -> replicas.indices.filter { it != i }.map { i to it } }
        var quietRounds = 0
        while (quietRounds < 2) {
            var moved = false
            for ((from, to) in pairs.shuffled(rnd)) {
                val batch = replicas[from].journal.changesSince(
                    cursors[from to to],
                    limit = if (rnd.nextInt(4) ==
                        0
                    ) {
                        rnd.nextInt(1, 8)
                    } else {
                        rnd.nextInt(8, 300)
                    },
                )
                if (batch.rows.isEmpty()) continue
                moved = true
                val target = replicas[to]
                repeat(if (rnd.nextInt(5) == 0) 2 else 1) {
                    // sometimes the same batch twice
                    val r = target.journal.apply(batch, target.now)
                    assertIs<ApplyResult.Applied>(r, "$r")
                    r.changedEvents.forEach { target.agenda.eventChanged(it, target.now) }
                }
                cursors[from to to] = batch.next
                sent += Triple(from, to, batch)
                if (rnd.nextInt(6) == 0) { // an old batch arrives late
                    val (f, t, old) = sent.random(rnd)
                    assertIs<ApplyResult.Applied>(replicas[t].journal.apply(old, replicas[t].now), "late batch from $f")
                }
            }
            quietRounds = if (moved) 0 else quietRounds + 1
        }
    }

    @Test
    fun threeDevicesAgreeAfterAnyOrderOfExchange() {
        val rnd = Random(2026)
        val skew = listOf(-25 * minute, 0L, 25 * minute) // within the 60-minute drift limit
        var replicas = List(3) { Replica(100 + it) }
        var cursors = mutableMapOf<Pair<Int, Int>, Cursor?>()
        val sent = mutableListOf<Triple<Int, Int, ChangeBatch>>()
        var time = 1_790_000_000_000L
        var checks = 0
        var largest = 0
        repeat(sequences) { sequence ->
            // New devices every 10 sequences: each check reads all data, so the test would grow with the square of it.
            if (sequence > 0 && sequence % 10 == 0) {
                largest = maxOf(largest, replicas[0].state().first.size)
                replicas = List(3) { Replica(100 + sequence + it) }
                cursors = mutableMapOf()
                sent.clear()
            }
            repeat(50) {
                time += rnd.nextLong(1, 20_000) // often several changes in one second, on different devices
                val i = rnd.nextInt(3)
                replicas[i].now = time + skew[i]
                // One transaction, so that the step's reads share one connection (outside one, each query opens one).
                replicas[i].db.transaction { step(replicas[i], rnd) }
            }
            replicas.forEachIndexed { i, r -> r.now = time + skew[i] }
            exchange(replicas, cursors, rnd, sent)
            if (sent.size > 400) sent.subList(0, sent.size - 400).clear()
            val first = replicas[0].db.transactionWithResult { replicas[0].state() }
            for (r in replicas.drop(1)) assertEquals(first, r.db.transactionWithResult { r.state() }, "sequence $sequence: replicas differ")
            for (r in replicas) assertEquals(emptyList(), r.db.transactionWithResult { r.journal.verify() }, "sequence $sequence")
            checks++
        }
        val (rows, journal) = replicas[0].state()
        println("converged after each of $checks sequences; the last devices hold ${rows.size} rows and ${journal.size} journal entries")
        assertEquals(sequences, checks)
        assertTrue(rows.keys.count { it.startsWith("ev ") } > 0 && maxOf(largest, rows.size) > 20, "the test made real data")
    }
}
