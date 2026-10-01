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
        advance(19)
        assertEquals(emptySet(), activeKeys())
        advance(1)
        assertEquals(setOf("rule:eyes"), activeKeys())
        advance(25)
        assertEquals(setOf("rule:eyes", "rule:water"), activeKeys())
    }

    @Test
    fun doneResetsTheIntervalAndSnoozeDelays() {
        advance(20)
        engine.act("rule:eyes", ReminderAction.DONE)
        assertTrue("rule:eyes" !in activeKeys())
        advance(19)
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
