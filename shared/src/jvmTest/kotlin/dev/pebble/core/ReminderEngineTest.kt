package dev.pebble.core

import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.EventBus
import dev.pebble.core.reminders.Escalation
import dev.pebble.core.reminders.EscalationPolicy
import dev.pebble.core.reminders.ReminderAction
import dev.pebble.core.reminders.ReminderEngine
import dev.pebble.core.reminders.ReminderRepository
import dev.pebble.core.reminders.Strictness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReminderEngineTest {
    private val minute = 60_000L
    private var now = 1_000_000_000L
    private var minuteOfDay = 12 * 60 // noon, inside the default 08:00–23:00 window
    private val repo = ReminderRepository(DatabaseFactory.inMemory()).apply { seedDefaults() }
    private val engine = ReminderEngine(repo, EventBus(), clock = { now }, minuteOfDay = { minuteOfDay })

    private fun advance(minutes: Int) { now += minutes * minute; engine.tick() }
    private fun activeKeys() = engine.active.value.map { it.key }.toSet()

    @Test
    fun rulesBecomeDueAfterTheirInterval() {
        // Calm defaults: eyes and water hourly, stretch every 90 minutes.
        advance(59)
        assertEquals(emptySet(), activeKeys())
        advance(1)
        assertEquals(setOf("rule:eyes", "rule:water"), activeKeys())
        advance(30)
        assertEquals(setOf("rule:eyes", "rule:water", "rule:stretch"), activeKeys())
    }

    @Test
    fun skippingInARowPushesTheNextOneFurtherOut() {
        advance(60)
        engine.act("rule:eyes", ReminderAction.DISMISSED)
        advance(60)
        assertTrue("rule:eyes" !in activeKeys(), "after a skip the gap doubles")
        advance(60)
        assertTrue("rule:eyes" in activeKeys())
        engine.act("rule:eyes", ReminderAction.DONE)
        advance(60)
        assertTrue("rule:eyes" in activeKeys(), "doing it resets the gap")
    }

    @Test
    fun lessOftenStretchesTheGapAndDeferIsNotAReaction() {
        advance(60)
        assertEquals(90, engine.lessOften("rule:eyes"))
        assertEquals(90, repo.rules().single { it.id == "eyes" }.intervalMinutes)
        assertEquals(null, engine.lessOften("once:1"), "one-offs have no interval")
        // Held back while you watch a video: gone for now, back after the wait.
        engine.defer("rule:water", minutes = 5)
        assertTrue("rule:water" !in activeKeys())
        advance(5)
        assertTrue("rule:water" in activeKeys())
    }

    @Test
    fun doneResetsTheIntervalAndSnoozeDelays() {
        advance(60)
        engine.act("rule:eyes", ReminderAction.DONE)
        assertTrue("rule:eyes" !in activeKeys())
        advance(59)
        assertTrue("rule:eyes" !in activeKeys())
        advance(1)
        engine.act("rule:eyes", ReminderAction.SNOOZED, snoozeMinutes = 5)
        assertTrue("rule:eyes" !in activeKeys())
        advance(5)
        assertTrue("rule:eyes" in activeKeys())
    }

    @Test
    fun quietOutsideActiveHoursAndDisabledRules() {
        minuteOfDay = 2 * 60
        advance(120)
        assertEquals(emptySet(), activeKeys())
        minuteOfDay = 12 * 60
        repo.updateRule("water", 45, Strictness.NORMAL, enabled = false)
        engine.tick()
        assertEquals(setOf("rule:eyes", "rule:stretch"), activeKeys())
    }

    @Test
    fun oneOffRemindersFireOnceAndCompleteWhenDone() {
        repo.addOneOff("Call mom", dueAt = now + 3 * minute)
        advance(2)
        assertTrue(activeKeys().none { it.startsWith("once:") })
        advance(1)
        val key = activeKeys().single { it.startsWith("once:") }
        engine.act(key, ReminderAction.DONE)
        assertTrue(key !in activeKeys())
        assertTrue(repo.pendingOneOffs().isEmpty())
    }

    @Test
    fun escalationDependsOnStrictness() {
        assertEquals(Escalation.BUBBLE, EscalationPolicy.stage(Strictness.GENTLE, 60 * minute))
        assertEquals(Escalation.BUBBLE, EscalationPolicy.stage(Strictness.NORMAL, 30_000))
        assertEquals(Escalation.BOUNCE, EscalationPolicy.stage(Strictness.NORMAL, 2 * minute))
        assertEquals(Escalation.TOAST, EscalationPolicy.stage(Strictness.NORMAL, 5 * minute))
        assertEquals(Escalation.FOLLOW, EscalationPolicy.stage(Strictness.STRICT, 3 * minute))
    }
}
