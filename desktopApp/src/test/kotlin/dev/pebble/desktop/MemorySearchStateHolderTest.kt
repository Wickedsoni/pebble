package dev.pebble.desktop

import dev.pebble.core.search.MemorySearch
import dev.pebble.desktop.app.pages.MemorySearchEvent
import dev.pebble.desktop.app.pages.MemorySearchStateHolder
import dev.pebble.desktop.app.pages.MemorySearchUiState
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
import kotlin.test.assertTrue

/** The "Search memory" card: searching, results with labels, nothing found, no model, and clearing. */
@OptIn(ExperimentalCoroutinesApi::class)
class MemorySearchStateHolderTest {
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun stop() = scopes.forEach { it.cancel() }

    private fun TestScope.holder(search: suspend (String) -> List<MemorySearch.Result>?) =
        MemorySearchStateHolder(search, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it })

    @Test
    fun resultsArriveWithTheirKind() = runTest {
        val h = holder { listOf(MemorySearch.Result(MemorySearch.FACT, "1", "wifi password is pebble123", 0.8f)) }
        assertNull(h.state.value.results, "nothing searched yet")
        h.onEvent(MemorySearchEvent.Search("  wifi "))
        assertTrue(h.state.value.searching)
        advanceUntilIdle()
        assertEquals(
            MemorySearchUiState("wifi", false, listOf(MemorySearchUiState.Row("You told me", "wifi password is pebble123"))),
            h.state.value,
        )
    }

    @Test
    fun nothingFoundAndNoModelAreDifferent() = runTest {
        val empty = holder { emptyList() }
        empty.onEvent(MemorySearchEvent.Search("cricket"))
        advanceUntilIdle()
        assertEquals(emptyList(), empty.state.value.results)
        assertTrue(!empty.state.value.noModel)
        val noModel = holder { null }
        noModel.onEvent(MemorySearchEvent.Search("cricket"))
        advanceUntilIdle()
        assertTrue(noModel.state.value.noModel)
    }

    @Test
    fun aNewerSearchWinsAndClearResets() = runTest {
        val slow = CompletableDeferred<List<MemorySearch.Result>>()
        val h = holder { q -> if (q == "first") slow.await() else listOf(MemorySearch.Result(MemorySearch.NOTE, "2", "second hit", 0.7f)) }
        h.onEvent(MemorySearchEvent.Search("first"))
        h.onEvent(MemorySearchEvent.Search("second"))
        advanceUntilIdle()
        slow.complete(listOf(MemorySearch.Result(MemorySearch.NOTE, "1", "stale", 0.9f)))
        advanceUntilIdle()
        assertEquals(listOf("second hit"), h.state.value.results?.map { it.text })
        h.onEvent(MemorySearchEvent.Clear)
        assertEquals(MemorySearchUiState(), h.state.value)
        h.onEvent(MemorySearchEvent.Search("   "))
        assertEquals(MemorySearchUiState(), h.state.value, "a blank search does nothing")
    }
}
