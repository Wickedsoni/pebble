package dev.pebble.core.sync

/**
 * A hybrid logical clock time (WP E3, docs/specs/E3-CHANGE-JOURNAL.md section 4): wall-clock milliseconds, a counter
 * for changes in the same millisecond, and the device id that made the change. HLCs have a total order.
 *
 * Text form: `WWWWWWWWWWWW.CCCC.<device>` (12 and 4 lower-case hex digits). The wall and the counter have a fixed
 * width, so the order of the texts (SQLite's BINARY order, Kotlin's String order) is the order of the HLCs.
 */
data class Hlc(val wallMillis: Long, val counter: Int, val device: String) : Comparable<Hlc> {
    init {
        require(wallMillis in 0..MAX_WALL) { "wall time out of range: $wallMillis" }
        require(counter in 0..MAX_COUNTER) { "counter out of range: $counter" }
        require(DEVICE.matches(device)) { "not a device id: $device" }
    }

    override fun compareTo(other: Hlc): Int = compareValuesBy(this, other, { it.wallMillis }, { it.counter }, { it.device })

    override fun toString(): String = wallMillis.toString(16).padStart(12, '0') + "." + counter.toString(16).padStart(4, '0') + "." + device

    companion object {
        const val MAX_COUNTER = 0xFFFF
        const val MAX_WALL = 0xFFFF_FFFF_FFFFL

        /** Lower-case base32, as `DeviceIdentity` makes it (26 characters on a real device). */
        private val DEVICE = Regex("[a-z2-7]{1,26}")
        private val TEXT = Regex("([0-9a-f]{12})\\.([0-9a-f]{4})\\.([a-z2-7]{1,26})")

        /** The HLC of [text], or null if [text] does not have the exact form. */
        fun parse(text: String): Hlc? {
            val m = TEXT.matchEntire(text) ?: return null
            return Hlc(m.groupValues[1].toLong(16), m.groupValues[2].toInt(16), m.groupValues[3])
        }
    }
}

/** A peer's change has a wall time too far ahead of this device's clock (spec 4.2). */
class ClockAheadException(val remote: Hlc, val wallMillis: Long) :
    Exception("HLC $remote is more than ${HlcClock.MAX_DRIFT_MILLIS / 60_000} minutes ahead of this clock ($wallMillis)")

/**
 * The HLC rules (spec 4.2). The clock keeps no state of its own: `last` is the largest HLC in the change journal,
 * read in the same IMMEDIATE transaction as the write (ADR 0017), so all writers to one database share one clock.
 */
object HlcClock {
    const val MAX_DRIFT_MILLIS = 60 * 60_000L

    /** The HLC of a local change at [wall] on [device], after [last]. Always larger than [last]. */
    fun send(last: Hlc?, wall: Long, device: String): Hlc = when {
        last == null || wall > last.wallMillis -> Hlc(wall, 0, device)
        last.counter < Hlc.MAX_COUNTER -> Hlc(last.wallMillis, last.counter + 1, device)
        else -> Hlc(last.wallMillis + 1, 0, device)
    }

    /** The new `last` after a peer's change [remote] arrives at [wall]. Throws [ClockAheadException] on too much drift. */
    fun receive(last: Hlc?, remote: Hlc, wall: Long): Hlc {
        if (remote.wallMillis > wall + MAX_DRIFT_MILLIS) throw ClockAheadException(remote, wall)
        return if (last == null || remote > last) remote else last
    }
}
