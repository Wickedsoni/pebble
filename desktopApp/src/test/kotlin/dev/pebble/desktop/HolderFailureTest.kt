package dev.pebble.desktop

import dev.pebble.core.brain.CommandFeedback
import dev.pebble.desktop.app.pages.MemorySearchEvent
import dev.pebble.desktop.app.pages.MemorySearchStateHolder
import dev.pebble.desktop.app.pages.TeachEvent
import dev.pebble.desktop.app.pages.TeachStateHolder
import dev.pebble.desktop.app.pages.TeachingPort
import dev.pebble.desktop.core.Logger
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A database or model error must not leave "searching" on, must show a message, and must reach the log (R3-10). */
@OptIn(ExperimentalCoroutinesApi::class)
class HolderFailureTest {
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun stop() = scopes.forEach { it.cancel() }

    private class Recording : Logger {
        val warnings = mutableListOf<Pair<String, Throwable?>>()

        override fun info(tag: String, msg: String) = Unit

        override fun warn(tag: String, msg: String, t: Throwable?) {
            warnings += msg to t
        }
    }

    private fun TestScope.scope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it }

    @Test
    fun aFailedSearchStopsSearchingShowsAMessageAndLogs() = runTest {
        val log = Recording()
        var fail = true
        val h = MemorySearchStateHolder({ if (fail) error("onnx broke") else emptyList() }, scope(), log)
        h.onEvent(MemorySearchEvent.Search("wifi"))
        assertTrue(h.state.value.searching)
        advanceUntilIdle()
        assertFalse(h.state.value.searching, "not stuck on 'Looking for'")
        assertNotNull(h.state.value.error)
        assertEquals("wifi", h.state.value.query)
        assertEquals("onnx broke", log.warnings.single().second?.message)
        fail = false
        h.onEvent(MemorySearchEvent.Search("wifi"))
        advanceUntilIdle()
        assertNull(h.state.value.error, "the next search starts clean")
    }

    @Test
    fun aFailedTeachShowsAMessageLogsAndTheNextOneWorks() = runTest {
        val log = Recording()
        var fail = true
        val rows = mutableListOf<CommandFeedback>()
        val port = object : TeachingPort {
            override suspend fun taught() = rows.toList()

            override suspend fun teach(phrase: String, action: String) {
                if (fail) throw IllegalStateException("database is locked")
                rows += CommandFeedback(phrase, action, null, null, 0, "taught", 1)
            }

            override suspend fun unteach(id: Long) = throw IllegalStateException("database is locked")

            override suspend fun forgetAll() = throw IllegalStateException("database is locked")
        }
        val h = TeachStateHolder(port, scope(), log)
        advanceUntilIdle()
        assertNull(h.state.value.error)
        h.onEvent(TeachEvent.Teach("chai time"))
        advanceUntilIdle()
        assertNotNull(h.state.value.error)
        assertEquals(1, log.warnings.size)
        h.onEvent(TeachEvent.Unteach(1))
        h.onEvent(TeachEvent.ForgetAll)
        advanceUntilIdle()
        assertEquals(3, log.warnings.size, "every failure is logged, none reaches the scope")
        fail = false
        h.onEvent(TeachEvent.Teach("chai time"))
        advanceUntilIdle()
        assertNull(h.state.value.error)
        assertEquals(listOf("chai time"), h.state.value.taught.map { it.text })
    }

    @Test
    fun aFailingFirstLoadDoesNotCrashTheScope() = runTest {
        val port = object : TeachingPort {
            override suspend fun taught(): List<CommandFeedback> = throw IllegalStateException("no database")

            override suspend fun teach(phrase: String, action: String) = Unit

            override suspend fun unteach(id: Long) = Unit

            override suspend fun forgetAll() = Unit
        }
        val h = TeachStateHolder(port, scope())
        advanceUntilIdle()
        assertNotNull(h.state.value.error)
    }
}
