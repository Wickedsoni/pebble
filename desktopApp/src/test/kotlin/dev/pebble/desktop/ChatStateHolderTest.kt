package dev.pebble.desktop

import dev.pebble.core.brain.ConversationRepository
import dev.pebble.core.brain.Turn
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.desktop.app.pages.ChatEvent
import dev.pebble.desktop.app.pages.ChatStateHolder
import dev.pebble.desktop.app.pages.ConversationPort
import dev.pebble.desktop.app.pages.RepositoryConversationPort
import dev.pebble.desktop.core.AppEnv
import dev.pebble.desktop.core.DispatcherProvider
import dev.pebble.desktop.core.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Chat page (and the conversation of Quick Add): rows, time labels, scroll key and the confirm step. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatStateHolderTest {
    private val zone = ZoneId.of("Asia/Kolkata")

    private val clock = object : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(0)
    }

    private class TestDispatchers(d: CoroutineDispatcher) : DispatcherProvider {
        override val main = d
        override val default = d
        override val io = d
    }

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun stop() = scopes.forEach { it.cancel() }

    /** A conversation in memory. [subscriptions] counts how often the flow is collected; [clears] how often it is cleared. */
    private class FakePort(initial: List<Turn> = emptyList()) : ConversationPort {
        val turns = MutableStateFlow(initial)
        var subscriptions = 0
        var clears = 0
        var failClear = false

        override fun recent(limit: Long): Flow<List<Turn>> = turns.onStart { subscriptions++ }

        override suspend fun clear() {
            clears++
            if (failClear) error("database is locked")
            turns.value = emptyList()
        }
    }

    private fun turn(i: Int, via: String = "typed", did: String = "reply $i") = Turn(1_000L * i, "said $i", via, did, "reply $i")

    private fun TestScope.holder(port: ConversationPort, limit: Long = 500): ChatStateHolder {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }
        return ChatStateHolder(port, AppEnv(clock, { zone }, TestDispatchers(dispatcher)), scope, limit)
    }

    @Test
    fun theFirstStateIsNotLoadedUntilTheFirstReadIsDone() = runTest {
        val h = holder(FakePort())
        assertFalse(h.state.value.loaded)
        advanceUntilIdle()
        assertTrue(h.state.value.loaded)
        assertTrue(h.state.value.rows.isEmpty())
    }

    @Test
    fun rowsHaveTheTimeLabelOfTheZoneOfTheApp() = runTest {
        val h = holder(FakePort(listOf(Turn(0, "hi", "voice", "said hello", "hello"))))
        advanceUntilIdle()
        val row = h.state.value.rows.single()
        // The AM/PM text depends on the language of the system: compare only the day and the time.
        assertTrue(row.timeLabel.startsWith("1 Jan, 5:30"), row.timeLabel)
        assertTrue(row.voice)
        assertEquals("said hello", row.extra)
        assertEquals("1 message", h.state.value.countLabel)
    }

    @Test
    fun anExtraLineIsOnlyThereWhenWhatPebbleDidIsNotTheReply() = runTest {
        val h = holder(FakePort(listOf(turn(1, did = "reply 1"), turn(2, did = "added a note"))))
        advanceUntilIdle()
        assertEquals(listOf(null, "added a note"), h.state.value.rows.map { it.extra })
    }

    @Test
    fun theScrollTargetFollowsTheLastTurnAlsoWhenTheListStaysAtItsLimit() = runTest {
        val port = FakePort((1..500).map { turn(it) })
        val h = holder(port)
        advanceUntilIdle()
        val before = h.state.value
        assertEquals(500, before.rows.size)
        // The query keeps the newest 500: one turn comes in, the oldest one goes out, the size stays.
        port.turns.value = (2..501).map { turn(it) }
        advanceUntilIdle()
        val after = h.state.value
        assertEquals(500, after.rows.size)
        assertNotEquals(before.scrollKey, after.scrollKey)
        assertEquals("reply 501", after.rows.last().reply)
    }

    @Test
    fun theScrollKeyIsNullWithoutTurns() = runTest {
        val h = holder(FakePort())
        advanceUntilIdle()
        assertNull(h.state.value.scrollKey)
    }

    @Test
    fun clearConversationAsksFirstAndDeletesNothingYet() = runTest {
        val port = FakePort(listOf(turn(1), turn(2)))
        val h = holder(port)
        advanceUntilIdle()
        h.onEvent(ChatEvent.AskClear)
        advanceUntilIdle()
        assertTrue(h.state.value.confirmClear)
        assertEquals(0, port.clears)
        assertEquals(2, h.state.value.rows.size)
    }

    @Test
    fun confirmingDeletesTheConversationAndClosesTheQuestion() = runTest {
        val port = FakePort(listOf(turn(1), turn(2)))
        val h = holder(port)
        advanceUntilIdle()
        h.onEvent(ChatEvent.AskClear)
        h.onEvent(ChatEvent.ConfirmClear)
        advanceUntilIdle()
        assertEquals(1, port.clears)
        assertTrue(h.state.value.rows.isEmpty())
        assertFalse(h.state.value.confirmClear)
    }

    @Test
    fun aFailedClearIsLoggedKeepsTheTurnsAndTheScopeAlive() = runTest {
        val port = FakePort(listOf(turn(1))).apply { failClear = true }
        val warnings = mutableListOf<String>()
        val log = object : Logger {
            override fun info(tag: String, msg: String) = Unit

            override fun warn(tag: String, msg: String, t: Throwable?) {
                warnings += msg
            }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }
        val h = ChatStateHolder(port, AppEnv(clock, { zone }, TestDispatchers(dispatcher)), scope, log = log)
        advanceUntilIdle()
        h.onEvent(ChatEvent.AskClear)
        h.onEvent(ChatEvent.ConfirmClear)
        advanceUntilIdle()
        assertEquals(1, warnings.size)
        assertFalse(h.state.value.confirmClear)
        assertEquals(1, h.state.value.rows.size)
        assertTrue(scope.isActive)
    }

    @Test
    fun aSecondClickOnDeleteItDoesNotClearTwice() = runTest {
        val port = FakePort(listOf(turn(1)))
        val h = holder(port)
        advanceUntilIdle()
        h.onEvent(ChatEvent.AskClear)
        h.onEvent(ChatEvent.ConfirmClear)
        assertFalse(h.state.value.confirmClear.also { advanceUntilIdle() })
        assertEquals(1, port.clears)
    }

    @Test
    fun cancelKeepsEverything() = runTest {
        val port = FakePort(listOf(turn(1), turn(2)))
        val h = holder(port)
        advanceUntilIdle()
        h.onEvent(ChatEvent.AskClear)
        h.onEvent(ChatEvent.CancelClear)
        advanceUntilIdle()
        assertFalse(h.state.value.confirmClear)
        assertEquals(0, port.clears)
        assertEquals(2, h.state.value.rows.size)
    }

    @Test
    fun theConversationIsReadOnceForAsManyStateUpdatesAsYouLike() = runTest {
        val port = FakePort(listOf(turn(1)))
        val h = holder(port)
        advanceUntilIdle()
        // A keystroke or a microphone level in Quick Add only recomposes: the holder is not asked again.
        repeat(100) { h.state.value }
        h.onEvent(ChatEvent.AskClear)
        h.onEvent(ChatEvent.CancelClear)
        advanceUntilIdle()
        assertEquals(1, port.subscriptions)
    }

    @Test
    fun theRealRepositoryFeedsTheHolderOnTheDispatcherOfTheApp() = runTest {
        val repo = ConversationRepository(DatabaseFactory.inMemory())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val env = AppEnv(clock, { zone }, TestDispatchers(dispatcher))
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }
        val h = ChatStateHolder(RepositoryConversationPort(repo, env), env, scope, limit = 30)
        advanceUntilIdle()
        repo.add(turn(1))
        advanceUntilIdle()
        assertEquals(1, h.state.value.rows.size)
        h.onEvent(ChatEvent.AskClear)
        h.onEvent(ChatEvent.ConfirmClear)
        advanceUntilIdle()
        assertTrue(h.state.value.rows.isEmpty())
        assertTrue(repo.recent(10).isEmpty())
    }
}
