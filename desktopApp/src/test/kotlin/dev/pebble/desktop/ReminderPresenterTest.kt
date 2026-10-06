package dev.pebble.desktop

import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.EventBus
import dev.pebble.core.event.PebbleEvent
import dev.pebble.core.reminders.ReminderAction
import dev.pebble.core.reminders.ReminderEngine
import dev.pebble.core.reminders.ReminderRepository
import dev.pebble.core.reminders.Strictness
import dev.pebble.desktop.core.AppEnv
import dev.pebble.desktop.core.DispatcherProvider
import dev.pebble.desktop.core.UiPort
import dev.pebble.desktop.pet.ReminderPresenter
import dev.pebble.desktop.pet.Stage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The app-level reminder presenter (QA round 1, P6a): reminders must reach you whether or not the companion
 * window is shown, and the pet window no longer owns the loop that does it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderPresenterTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private var now = LocalDateTime.of(2026, 10, 4, 10, 0).atZone(zone).toInstant().toEpochMilli()
    private val minute = 60_000L

    private val clock = object : Clock() {
        override fun getZone(): ZoneId = zone

        override fun withZone(zone: ZoneId?): Clock = this

        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    private class TestDispatchers(d: CoroutineDispatcher) : DispatcherProvider {
        override val main = d
        override val default = d
        override val io = d
    }

    private val bus = EventBus()
    private val reminders = ReminderRepository(DatabaseFactory.inMemory()).apply { seedDefaults() }
    private val engine = ReminderEngine(reminders, bus, clock = { now }, minuteOfDay = { 10 * 60 })

    /** Only "water" runs, with the given strictness; it is due 60 minutes from now. */
    private fun dueWater(strictness: Strictness) {
        reminders.updateRule("water", 60, strictness, true)
        reminders.updateRule("eyes", 60, Strictness.GENTLE, false)
        reminders.updateRule("stretch", 90, Strictness.GENTLE, false)
        now += 60 * minute
        engine.tick()
    }

    private class FakeUi : UiPort {
        val toasts = mutableListOf<Pair<String, String>>()

        override fun notify(title: String, message: String) {
            toasts += title to message
        }

        override fun openPage(page: String) = Unit
    }

    private val ui = FakeUi()
    private var petVisible = false
    private var fullscreen = false
    private val earnedSeen = mutableListOf<Stage>()
    private var earnedRead = Stage.BABY

    /** Marks the time while one of its tasks runs, so a test can tell which dispatcher ran a call. */
    private class MarkingDispatcher(private val d: CoroutineDispatcher) : CoroutineDispatcher() {
        @Volatile var inside = false

        override fun dispatch(context: CoroutineContext, block: Runnable) = d.dispatch(
            context,
            Runnable {
                inside = true
                try {
                    block.run()
                } finally {
                    inside = false
                }
            },
        )
    }

    private var workDispatcher: CoroutineDispatcher? = null

    private fun TestScope.presenter(): ReminderPresenter {
        val d = StandardTestDispatcher(testScheduler)
        val work = workDispatcher ?: d
        return ReminderPresenter(
            engine = engine,
            ui = ui,
            env = AppEnv(clock, { zone }, TestDispatchers(d)),
            petVisible = { petVisible },
            quiet = { fullscreen },
            readEarned = { earnedRead },
            onEarned = { earnedSeen += it },
            work = work,
        )
    }

    private fun TestScope.presenterWithRead(read: () -> Stage): ReminderPresenter {
        val d = StandardTestDispatcher(testScheduler)
        return ReminderPresenter(
            engine = engine,
            ui = ui,
            env = AppEnv(clock, { zone }, TestDispatchers(d)),
            petVisible = { petVisible },
            quiet = { fullscreen },
            readEarned = read,
            onEarned = { earnedSeen += it },
            work = workDispatcher ?: d,
        )
    }

    @Test
    fun aHiddenPetStillShowsADueReminderAsOneToast() = runTest {
        dueWater(Strictness.GENTLE)
        val p = presenter()
        p.tick()
        assertEquals(listOf("Pebble" to "Sip some water"), ui.toasts)
        p.tick()
        now += 30_000
        p.tick()
        assertEquals(1, ui.toasts.size, "the same due time is toasted once")
    }

    @Test
    fun aHiddenPetToastsEveryStrictnessAtOnce() = runTest {
        for (s in Strictness.entries) {
            val before = ui.toasts.size
            dueWater(s)
            presenter().tick()
            assertEquals(before + 1, ui.toasts.size, "$s")
            engine.act("rule:water", ReminderAction.DONE)
        }
    }

    @Test
    fun aShownPetKeepsToTheBubbleUntilTheToastStep() = runTest {
        petVisible = true
        dueWater(Strictness.NORMAL)
        val p = presenter()
        p.tick()
        assertTrue(ui.toasts.isEmpty(), "the bubble carries it")
        now += 2 * minute
        p.tick()
        assertTrue(ui.toasts.isEmpty(), "bounce step: still no toast")
        now += 2 * minute // 4 minutes overdue
        p.tick()
        p.tick()
        assertEquals(1, ui.toasts.size, "the Normal toast step comes once")
    }

    @Test
    fun aShownPetWithAGentleReminderNeverToasts() = runTest {
        petVisible = true
        dueWater(Strictness.GENTLE)
        val p = presenter()
        p.tick()
        now += 2 * minute
        p.tick()
        assertTrue(ui.toasts.isEmpty())
    }

    @Test
    fun aFullscreenAppMakesTheReminderWaitWithoutAToast() = runTest {
        dueWater(Strictness.NORMAL)
        fullscreen = true
        val p = presenter()
        p.tick()
        assertTrue(ui.toasts.isEmpty())
        assertTrue(engine.active.value.isEmpty(), "deferred for 5 minutes")
        fullscreen = false
        now += 6 * minute
        engine.tick()
        now += 2_000
        p.tick()
        assertEquals(1, ui.toasts.size, "it comes back once you are done")
    }

    @Test
    fun aGentleReminderNobodyAnsweredStepsAsideForHalfAnHour() = runTest {
        petVisible = true
        dueWater(Strictness.GENTLE)
        val p = presenter()
        now += 4 * minute
        p.tick()
        assertTrue(engine.active.value.isEmpty())
        now += 29 * minute
        engine.tick()
        assertTrue(engine.active.value.isEmpty(), "still waiting at 29 minutes")
        now += 2 * minute
        engine.tick()
        assertEquals(1, engine.active.value.size)
    }

    @Test
    fun theReactionToAToastedReminderIsStillLogged() = runTest {
        val events = mutableListOf<PebbleEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { bus.events.collect { events += it } }
        runCurrent()
        dueWater(Strictness.NORMAL)
        presenter().tick()
        engine.act("rule:water", ReminderAction.DONE)
        runCurrent()
        assertTrue(events.any { it is PebbleEvent.ReminderDue }, "due is logged")
        val acted = events.filterIsInstance<PebbleEvent.ReminderActed>().single()
        assertEquals(ReminderAction.DONE, acted.action)
        assertEquals(1, ui.toasts.size)
    }

    @Test
    fun theLoopRunsTheTicksAndReadsGrowthOffTheMainThreadEveryThreeMinutes() = runTest {
        dueWater(Strictness.GENTLE)
        earnedRead = Stage.TEEN
        val work = MarkingDispatcher(StandardTestDispatcher(testScheduler))
        workDispatcher = work
        var readOnWork: Boolean? = null
        val inner = presenterWithRead { readOnWork = work.inside; earnedRead }
        val job = launch { inner.run() }
        runCurrent()
        assertEquals(1, ui.toasts.size, "the first pass is at once")
        advanceTimeBy(179_000)
        assertTrue(earnedSeen.isEmpty())
        advanceTimeBy(2_000)
        assertEquals(listOf(Stage.TEEN), earnedSeen)
        assertEquals(true, readOnWork, "the read ran on the work dispatcher, not on main")
        job.cancel()
    }

    @Test
    fun switchingTheCompanionOffAndOnGivesNoSecondToastForTheSameDueTime() = runTest {
        dueWater(Strictness.NORMAL)
        val p = presenter()
        petVisible = false
        p.tick()
        assertEquals(1, ui.toasts.size)
        petVisible = true
        now += 2_000
        p.tick()
        petVisible = false
        now += 2_000
        p.tick()
        assertEquals(1, ui.toasts.size, "hidden, shown, hidden: still one toast for this due time")
    }

    @Test
    fun aToastIsForgottenOnceTheReminderIsNoLongerActive() = runTest {
        dueWater(Strictness.NORMAL)
        val p = presenter()
        p.tick()
        assertEquals(1, p.toastedCount())
        engine.act("rule:water", ReminderAction.DONE)
        now += 2_000
        p.tick()
        assertEquals(0, p.toastedCount(), "nothing is active, so nothing is remembered")
    }

    @Test
    fun withThePetShownOnlyTheFirstGentleReminderStepsAside() = runTest {
        petVisible = true
        reminders.updateRule("water", 60, Strictness.GENTLE, true)
        reminders.updateRule("eyes", 60, Strictness.GENTLE, true)
        reminders.updateRule("stretch", 90, Strictness.GENTLE, false)
        now += 60 * minute
        engine.tick()
        val before = engine.active.value.map { it.key }
        assertEquals(2, before.size)
        val p = presenter()
        now += 4 * minute
        p.tick()
        assertEquals(listOf(before[1]), engine.active.value.map { it.key }, "only the shown (first) one is deferred")
    }
}
