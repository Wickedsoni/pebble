package dev.pebble.desktop

import dev.pebble.core.brain.CommandRouter.Routed
import dev.pebble.core.brain.CommandRouter.Source
import dev.pebble.core.brain.IntentGuess
import dev.pebble.core.brain.Understood
import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.quickadd.QuickCommand
import dev.pebble.desktop.core.AppEnv
import dev.pebble.desktop.core.DispatcherProvider
import dev.pebble.desktop.core.Logger
import dev.pebble.desktop.quickadd.freshRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.time.Clock
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** QA round 1: the answer of Quick Add belongs to the app, not to the window (R3-9), and one undo (R3-16). */
class AsyncConverseTest {
    private val env = AppEnv(
        Clock.systemUTC(),
        { ZoneOffset.UTC },
        object : DispatcherProvider {
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val io: CoroutineDispatcher = Dispatchers.IO
        },
    )

    @Test
    fun theSmartReplyIsRecordedAndSaidEvenWhenTheWindowIsGone() = runBlocking {
        val app = PebbleApp(DatabaseFactory.inMemory(), env)
        val gate = CompletableDeferred<String?>()
        val got = CompletableDeferred<PetLine>()
        val collector = launch(Dispatchers.Default) { got.complete(app.petLines.first()) }
        kotlinx.coroutines.delay(200) // let the collector subscribe
        val cmd = QuickCommand.Chitchat("how are you", "greeting")
        // No window callback is given (the window is closed); the app still finishes the turn.
        val job = app.converseAsync("how are you", "typed", Routed.Run(cmd, Source.RULES), { _, _ -> }, reply = { gate.await() })
        assertTrue(app.conversation.recent(5).isEmpty(), "still thinking")
        gate.complete("Doing fine, thanks!")
        withTimeout(5_000) { job.join() }
        val line = withTimeout(5_000) { got.await() }
        collector.cancel()
        assertEquals("Doing fine, thanks!", line.text)
        assertEquals("Doing fine, thanks!", app.conversation.recent(5).single().reply)
    }

    @Test
    fun aFailingReplyFallsBackToTheCannedLine() = runBlocking {
        val app = PebbleApp(DatabaseFactory.inMemory(), env)
        val cmd = QuickCommand.Chitchat("hello", "greeting")
        val job = app.converseAsync("hello", "typed", Routed.Run(cmd, Source.RULES), { _, _ -> }, reply = { error("server down") })
        withTimeout(5_000) { job.join() }
        assertEquals(1, app.conversation.recent(5).size)
    }

    @Test
    fun notWhatIMeantActsOnlyOnTheFirstClick() {
        val app = PebbleApp(DatabaseFactory.inMemory())
        val text = "ek note bana lo project ka topic"
        val understood = Understood(text.split(" "), listOf(IntentGuess("lists_createoradd", 0.8f)), List(7) { "O" })
        var retries = 0
        val line = app.executeFromModel(text, Routed.Run(QuickCommand.AddNote(text), Source.MODEL, understood, "add_note")) { _, _ ->
            retries++
        }
        val action = line.actions.single()
        action.onClick() // the pet bubble
        action.onClick() // Quick Add shows the same button
        assertEquals(1, retries)
        assertEquals(1, app.commandFeedback.all().size)
    }

    @Test
    fun aFailingTurnStillResetsTheWindowAndIsLogged() = runBlocking {
        val warnings = mutableListOf<String>()
        val log = object : Logger {
            override fun info(tag: String, msg: String) = Unit

            override fun warn(tag: String, msg: String, t: Throwable?) {
                warnings += msg
            }
        }
        val app = PebbleApp(DatabaseFactory.inMemory(), env, log = log)
        var calls = 0
        var last: PetLine? = PetLine("x")
        app.guarded({ calls++; last = it }) { error("boom") }
        assertEquals(1, calls)
        assertNull(last)
        assertEquals(1, warnings.size)
        assertFailsWith<CancellationException> { app.guarded({ calls++ }) { throw CancellationException("stop") } }
        assertEquals(1, calls, "cancellation does not call back")
    }

    @Test
    fun aRouteIsUsedOnlyForTheTextItWasMadeFor() {
        assertEquals("r", freshRoute("r", "note buy milk", "note buy milk"))
        assertNull(freshRoute("r", "note buy mil", "note buy milk"), "Enter within the debounce routes again")
        assertNull(freshRoute("r", null, "note"))
    }
}
