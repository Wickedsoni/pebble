package dev.pebble.core

import dev.pebble.core.sync.ClockAheadException
import dev.pebble.core.sync.Hlc
import dev.pebble.core.sync.HlcClock
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The hybrid logical clock (WP E3a, spec section 4.3). */
class HlcTest {
    private val devA = "a".repeat(26)
    private val devB = "b".repeat(26)
    private val minute = 60_000L

    @Test
    fun sendIsAlwaysLargerAlsoWhenTheWallClockGoesBack() {
        for (seed in 1..5) {
            val rnd = Random(seed)
            var wall = 1_790_000_000_000L
            var last: Hlc? = null
            repeat(10_000) {
                wall += rnd.nextLong(-5_000, 5_000) // jumps back and forth, often within one millisecond
                val next = HlcClock.send(last, wall, devA)
                last?.let { assertTrue(next > it, "seed $seed: $next after $it") }
                last = next
            }
        }
    }

    @Test
    fun aSendAfterAReceiveIsLargerThanWhatWasReceived() {
        val rnd = Random(7)
        var last: Hlc? = null
        repeat(10_000) {
            val wall = 1_790_000_000_000L + rnd.nextLong(0, 10 * minute)
            val remote = Hlc(wall + rnd.nextLong(-30 * minute, 30 * minute), rnd.nextInt(0, 100), devB)
            last = HlcClock.receive(last, remote, wall)
            val mine = HlcClock.send(last, wall, devA)
            assertTrue(mine > remote, "$mine after $remote")
            last = mine
        }
    }

    @Test
    fun twoDevicesNeverMakeTheSameHlc() {
        val rnd = Random(3)
        var a: Hlc? = null
        var b: Hlc? = null
        val seen = HashSet<Hlc>()
        repeat(10_000) {
            val wall = 1_790_000_000_000L + it / 10 // ten changes in each millisecond
            if (rnd.nextBoolean()) {
                a = HlcClock.send(a, wall, devA).also { h -> assertTrue(seen.add(h)) }
            } else {
                b = HlcClock.send(b, wall, devB).also { h -> assertTrue(seen.add(h)) }
            }
        }
    }

    @Test
    fun theTextHasTheOrderOfTheClock() {
        val rnd = Random(11)
        val hlcs = List(10_000) {
            Hlc(
                rnd.nextLong(0, Hlc.MAX_WALL),
                rnd.nextInt(0, Hlc.MAX_COUNTER + 1),
                if (rnd.nextBoolean()) devA else "dev" + "234567".take(rnd.nextInt(7)), // also prefixes of each other
            )
        }
        for (h in hlcs) assertEquals(h, Hlc.parse(h.toString()))
        assertEquals(hlcs.sorted().map { it.toString() }, hlcs.map { it.toString() }.sorted(), "text order = clock order")
        assertEquals("0000000003e8.0002.$devA", Hlc(1000, 2, devA).toString())
        for (bad in listOf(
            "",
            "3e8.0002.$devA",
            "0000000003E8.0002.$devA",
            "0000000003e8.2.$devA",
            "0000000003e8.0002.DEV",
            "0000000003e8.0002.",
        )) {
            assertNull(Hlc.parse(bad), bad)
        }
    }

    @Test
    fun theCounterRollsIntoTheNextMillisecond() {
        val full = Hlc(5_000, Hlc.MAX_COUNTER, devA)
        assertEquals(Hlc(5_001, 0, devA), HlcClock.send(full, 5_000, devA))
    }

    @Test
    fun aRemoteClockMoreThan60MinutesAheadIsRefused() {
        val now = 1_790_000_000_000L
        HlcClock.receive(null, Hlc(now + 59 * minute, 0, devB), now)
        assertFailsWith<ClockAheadException> { HlcClock.receive(null, Hlc(now + 61 * minute, 0, devB), now) }
    }
}
