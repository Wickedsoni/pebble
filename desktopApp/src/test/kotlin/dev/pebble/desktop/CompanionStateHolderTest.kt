package dev.pebble.desktop

import dev.pebble.core.growth.Growth
import dev.pebble.core.growth.Level
import dev.pebble.core.growth.Task
import dev.pebble.desktop.app.pages.CompanionEvent
import dev.pebble.desktop.app.pages.CompanionStateHolder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The growth of the Companion page: it is counted on request, the newest count wins, a failure keeps the last state. */
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionStateHolderTest {
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun stop() = scopes.forEach { it.cancel() }

    private fun growth(done: Int) = Growth(Level.entries.first(), Level.entries.getOrNull(1), listOf(Task("Do things", done, 5)))

    private fun TestScope.holder(load: suspend () -> Growth): CompanionStateHolder {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it }
        return CompanionStateHolder(load, scope)
    }

    @Test
    fun theFirstStateHasNoGrowthAndNothingIsCountedBeforeTheFirstRefresh() = runTest {
        var counts = 0
        val h = holder { counts++; growth(1) }
        advanceUntilIdle()
        assertNull(h.state.value.growth)
        assertEquals(0, counts)
    }

    @Test
    fun refreshCountsAndShowsTheGrowth() = runTest {
        val h = holder { growth(2) }
        h.onEvent(CompanionEvent.Refresh)
        advanceUntilIdle()
        assertEquals(growth(2), h.state.value.growth)
    }

    @Test
    fun theNewestCountWinsOverOneThatStillRuns() = runTest {
        val slow = CompletableDeferred<Growth>()
        var call = 0
        val h = holder { if (call++ == 0) slow.await() else growth(4) }
        h.onEvent(CompanionEvent.Refresh)
        advanceUntilIdle()
        h.onEvent(CompanionEvent.Refresh)
        advanceUntilIdle()
        slow.complete(growth(1))
        advanceUntilIdle()
        assertEquals(growth(4), h.state.value.growth)
    }

    @Test
    fun aFailedCountKeepsTheLastGrowth() = runTest {
        var fail = false
        val h = holder { if (fail) error("database is locked") else growth(3) }
        h.onEvent(CompanionEvent.Refresh)
        advanceUntilIdle()
        fail = true
        h.onEvent(CompanionEvent.Refresh)
        advanceUntilIdle()
        assertEquals(growth(3), h.state.value.growth)
    }
}
