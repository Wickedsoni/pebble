package dev.pebble.core.brain

import dev.pebble.db.PebbleDatabase

/** [NudgeStore] in the local database, so what Pebble learned about timing survives restarts. */
class SqlNudgeStore(private val db: PebbleDatabase) : NudgeStore {
    private val q get() = db.brainQueries

    override fun load(): Map<String, Pair<Double, Double>> =
        q.allNudgeStats().executeAsList().associate { it.key to (it.alpha to it.beta) }

    override fun save(key: String, alpha: Double, beta: Double) {
        q.saveNudgeStat(key, alpha, beta)
    }

    fun clear() {
        q.clearNudgeStats()
    }
}
