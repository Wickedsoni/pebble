package dev.pebble.core.brain

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.pebble.db.PebbleDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One exchange: what you said ([via] "typed" or "voice"), what Pebble did, and what it replied. */
data class Turn(val atMillis: Long, val said: String, val via: String, val did: String, val reply: String)

/** Your conversation with Pebble, oldest first. Local only; cleared from the Chat page. */
class ConversationRepository(private val db: PebbleDatabase) {
    private val q get() = db.brainQueries

    fun add(t: Turn) {
        q.insertTurn(t.atMillis, t.said, t.via, t.did, t.reply)
    }

    fun recent(limit: Long = 50): List<Turn> = q.recentTurns(limit).executeAsList().map(::toTurn)

    fun recentFlow(limit: Long = 200): Flow<List<Turn>> =
        q.recentTurns(limit).asFlow().mapToList(Dispatchers.Default).map { rows -> rows.map(::toTurn) }

    fun clear() {
        q.clearTurns()
    }

    private fun toTurn(r: dev.pebble.db.Conversation_turn) = Turn(r.at_millis, r.said, r.via, r.did, r.reply)
}
