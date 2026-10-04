package dev.pebble.desktop

import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.EventBus
import dev.pebble.core.memory.MemoryEngine
import dev.pebble.core.memory.MemoryKind
import dev.pebble.core.memory.MemoryRepository
import dev.pebble.core.reminders.ReminderEngine
import dev.pebble.core.reminders.ReminderRepository
import dev.pebble.core.reminders.Strictness
import dev.pebble.desktop.app.pages.RemindersEvent
import dev.pebble.desktop.app.pages.RemindersStateHolder
import dev.pebble.desktop.app.pages.RemindersUiState
import dev.pebble.desktop.core.AppEnv
import dev.pebble.desktop.core.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Reminders page's state holder: what you do changes the state, and the state is ready to draw. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemindersStateHolderTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private var now = LocalDateTime.of(2026, 10, 4, 10, 0).atZone(zone).toInstant().toEpochMilli() // a Sunday

    /** A clock the test can move. */
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    private val db = DatabaseFactory.inMemory()
    private val reminders = ReminderRepository(db).apply { seedDefaults() }
    private val memory = MemoryRepository(db)
    private val engine = ReminderEngine(reminders, EventBus(), clock = { now }, minuteOfDay = { 10 * 60 })

    /** Every dispatcher is the test's, so `advanceUntilIdle` runs the holder's work, database writes included. */
    private class TestDispatchers(d: CoroutineDispatcher) : DispatcherProvider {
        override val main = d
        override val default = d
        override val io = d
    }

    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun stop() = scopes.forEach { it.cancel() }

    /**
     * Not runTest's backgroundScope: `advanceUntilIdle()` only drives foreground tasks, and background ones
     * would never run. A scope on the test scheduler's dispatcher is foreground; [stop] cancels it.
     */
    private fun TestScope.holder(): RemindersStateHolder {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val env = AppEnv(clock, { zone }, TestDispatchers(dispatcher))
        val scope = CoroutineScope(SupervisorJob() + dispatcher).also { scopes += it }
        return RemindersStateHolder(reminders, engine, memory, env, scope)
    }

    private fun RemindersUiState.rule(id: String) = rules.single { it.id == id }

    @Test
    fun theFirstStateShowsTheBuiltInRules() = runTest {
        val s = holder().state.value
        assertEquals(listOf("Sip some water", "Stand up & stretch", "Rest your eyes").sorted(), s.rules.map { it.title }.sorted())
        assertEquals("every 1 hour", s.rule("eyes").intervalLabel)
        assertEquals(3, s.upcoming.size)
        assertTrue(s.oneOffs.isEmpty())
        assertNull(s.learnedQuiet)
    }

    @Test
    fun turningARuleOffRemovesItFromWhatIsNext() = runTest {
        val h = holder()
        h.onEvent(RemindersEvent.SetEnabled("eyes", false))
        advanceUntilIdle()
        assertFalse(h.state.value.rule("eyes").enabled)
        assertTrue(h.state.value.upcoming.none { it.key == "rule:eyes" })
    }

    @Test
    fun theIntervalMovesInStepsAndStaysBetween5MinAnd4Hours() = runTest {
        val h = holder()
        h.onEvent(RemindersEvent.ChangeInterval("eyes", -5))
        advanceUntilIdle()
        assertEquals("every 55 min", h.state.value.rule("eyes").intervalLabel)
        h.onEvent(RemindersEvent.ChangeInterval("eyes", +500))
        advanceUntilIdle()
        assertEquals("every 4 hours", h.state.value.rule("eyes").intervalLabel)
        h.onEvent(RemindersEvent.ChangeInterval("eyes", -1_000))
        advanceUntilIdle()
        assertEquals("every 5 min", h.state.value.rule("eyes").intervalLabel)
    }

    @Test
    fun strictnessChangesOnlyThatRule() = runTest {
        val h = holder()
        h.onEvent(RemindersEvent.SetStrictness("water", Strictness.STRICT))
        advanceUntilIdle()
        assertEquals(Strictness.STRICT, h.state.value.rule("water").strictness)
        assertEquals(Strictness.GENTLE, h.state.value.rule("eyes").strictness)
    }

    @Test
    fun aReminderAddedElsewhereAppearsAndCanBeDeleted() = runTest {
        val h = holder()
        // Quick Add or voice adds it; the page finds out through the live list, without an event of its own.
        val id = reminders.addOneOff("Call mom", LocalDateTime.of(2026, 10, 5, 19, 0).atZone(zone).toInstant().toEpochMilli())
        advanceUntilIdle()
        val shown = h.state.value
        val row = shown.oneOffs.single()
        assertEquals(id to "Call mom", row.id to row.title)
        assertEquals("mon 7:00 pm", row.dueLabel.lowercase(), "in the app's zone; AM/PM case follows the system locale")
        h.onEvent(RemindersEvent.DeleteOneOff(id))
        advanceUntilIdle()
        assertTrue(h.state.value.oneOffs.isEmpty())
        assertTrue(reminders.pendingOneOffs().isEmpty())
    }

    @Test
    fun aRuleThatIsDueSaysDueNow() = runTest {
        val h = holder()
        assertTrue(h.state.value.upcoming.none { it.overdue })
        now += 2 * 60 * 60_000L // two hours later: every rule's interval has passed
        engine.tick()
        advanceUntilIdle()
        val due = h.state.value.upcoming.filter { it.overdue }
        assertTrue(due.isNotEmpty())
        assertTrue(due.all { it.dueLabel == "Due now" })
    }

    @Test
    fun whatPebbleLearnedAboutQuietHoursIsShown() = runTest {
        memory.putDerived(MemoryKind.HABIT, MemoryEngine.KEY_QUIET, "You usually skip reminders around 2 PM.", "14", at = now)
        assertEquals("You usually skip reminders around 2 PM.", holder().state.value.learnedQuiet)
    }
}
