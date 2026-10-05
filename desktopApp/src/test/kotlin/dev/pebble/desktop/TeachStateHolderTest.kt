package dev.pebble.desktop

import dev.pebble.core.brain.CommandFeedback
import dev.pebble.desktop.app.pages.TeachEvent
import dev.pebble.desktop.app.pages.TeachStateHolder
import dev.pebble.desktop.app.pages.TeachUiState
import dev.pebble.desktop.app.pages.TeachingPort
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
import kotlin.test.assertTrue
import dev.pebble.core.brain.PebbleActions as A

/** The "Teach Pebble a command" card: first state, choosing, teaching, removing one, forgetting all. */
@OptIn(ExperimentalCoroutinesApi::class)
class TeachStateHolderTest {
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun stop() = scopes.forEach { it.cancel() }

    /** An in-memory port; [forgot] counts "Forget what you taught me". */
    private class FakePort : TeachingPort {
        val rows = mutableListOf<CommandFeedback>()
        var forgot = 0
        private var nextId = 1L

        override suspend fun taught() = rows.toList()

        override suspend fun teach(phrase: String, action: String) {
            rows += CommandFeedback(phrase, action, null, null, 0, "taught", nextId++)
        }

        override suspend fun unteach(id: Long) {
            rows.removeAll { it.id == id }
        }

        override suspend fun forgetAll() {
            rows.clear()
            forgot++
        }
    }

    private fun TestScope.holder(port: TeachingPort) =
        TeachStateHolder(port, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)).also { scopes += it })

    @Test
    fun startsWithTheTaughtPhrases() = runTest {
        val port = FakePort().apply { teach("notes kholo yaar", A.NOTES_QUERY) }
        val h = holder(port)
        advanceUntilIdle()
        assertEquals(listOf(TeachUiState.Row(1, "notes kholo yaar", "Show my notes")), h.state.value.taught)
        assertEquals(A.REMIND, h.state.value.action)
        assertTrue(
            h.state.value.choices.none {
                it.action == A.NOTE_REMOVE || it.action == A.REMINDER_REMOVE
            },
            "removals are not teachable",
        )
    }

    @Test
    fun teachesThePhraseAsTheChosenAction() = runTest {
        val port = FakePort()
        val h = holder(port)
        h.onEvent(TeachEvent.Choose(A.NOTES_QUERY))
        h.onEvent(TeachEvent.Teach("  meri list dikha  "))
        h.onEvent(TeachEvent.Teach("   "))
        advanceUntilIdle()
        assertEquals(listOf("meri list dikha" to A.NOTES_QUERY), port.rows.map { it.text to it.chosenAction })
        assertEquals(listOf("Show my notes"), h.state.value.taught.map { it.actionLabel })
        h.onEvent(TeachEvent.Choose(A.NOTE_REMOVE))
        assertEquals(A.NOTES_QUERY, h.state.value.action, "a removal cannot be chosen")
    }

    @Test
    fun removesOneOrForgetsAll() = runTest {
        val port = FakePort()
        val h = holder(port)
        h.onEvent(TeachEvent.Teach("chai time"))
        h.onEvent(TeachEvent.Teach("chai ka time"))
        advanceUntilIdle()
        h.onEvent(TeachEvent.Unteach(h.state.value.taught.first().id))
        advanceUntilIdle()
        assertEquals(listOf("chai ka time"), h.state.value.taught.map { it.text })
        h.onEvent(TeachEvent.ForgetAll)
        advanceUntilIdle()
        assertEquals(emptyList(), h.state.value.taught)
        assertEquals(1, port.forgot)
    }
}
