package dev.pebble.core.reminders

import dev.pebble.core.event.EventBus
import dev.pebble.core.event.PebbleEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Decides which reminders are due. Pure scheduling logic: time comes from [clock] and the
 * local minute-of-day from [minuteOfDay], so tests can drive it without waiting.
 */
class ReminderEngine(
    private val repo: ReminderRepository,
    private val bus: EventBus,
    private val clock: () -> Long,
    private val minuteOfDay: (Long) -> Int,
    /** Hours (0-23) Pebble learned you usually skip reminders in; repeating reminders wait them out. */
    private val quietHours: () -> Set<Int> = { emptySet() },
) {
    private val startedAt = clock()
    private val snoozedUntil = mutableMapOf<String, Long>()
    private val announced = mutableSetOf<String>()

    private val _active = MutableStateFlow<List<ActiveReminder>>(emptyList())
    val active: StateFlow<List<ActiveReminder>> = _active.asStateFlow()

    fun tick() {
        val now = clock()
        val quiet = (minuteOfDay(now) / 60) in quietHours()
        val due = buildList {
            if (!quiet) repo.rules().filter { it.enabled }.forEach { rule ->
                val key = ruleKey(rule.id)
                val dueAt = snoozedUntil[key] ?: ((rule.lastDoneAt ?: startedAt) + rule.intervalMinutes * 60_000L)
                if (now >= dueAt && inWindow(rule, now)) add(ActiveReminder(key, rule.kind, rule.title, rule.strictness, dueAt))
            }
            repo.pendingOneOffs().forEach { r ->
                val key = oneOffKey(r.id)
                val dueAt = snoozedUntil[key] ?: r.dueAt
                if (now >= dueAt) add(ActiveReminder(key, ReminderKind.CUSTOM, r.title, r.strictness, dueAt))
            }
        }.sortedBy { it.dueAt }

        due.filter { announced.add(it.key) }.forEach {
            bus.publish(PebbleEvent.ReminderDue(it.key, it.kind, it.title, now))
        }
        announced.retainAll(due.map { it.key }.toSet())
        _active.value = due
    }

    /** What's coming next — repeating reminders' next due time and pending one-offs — soonest first. */
    fun upcoming(limit: Int = 5): List<ActiveReminder> {
        val rules = repo.rules().filter { it.enabled }.map { rule ->
            val key = ruleKey(rule.id)
            val dueAt = snoozedUntil[key] ?: ((rule.lastDoneAt ?: startedAt) + rule.intervalMinutes * 60_000L)
            ActiveReminder(key, rule.kind, rule.title, rule.strictness, dueAt)
        }
        val once = repo.pendingOneOffs().map {
            val key = oneOffKey(it.id)
            ActiveReminder(key, ReminderKind.CUSTOM, it.title, it.strictness, snoozedUntil[key] ?: it.dueAt)
        }
        return (rules + once).sortedBy { it.dueAt }.take(limit)
    }

    fun act(key: String, action: ReminderAction, snoozeMinutes: Int = 10) {
        val now = clock()
        val reminder = _active.value.firstOrNull { it.key == key }
        when (action) {
            ReminderAction.SNOOZED -> snoozedUntil[key] = now + snoozeMinutes * 60_000L
            ReminderAction.DONE, ReminderAction.DISMISSED -> {
                snoozedUntil.remove(key)
                when {
                    key.startsWith(RULE) -> repo.markRuleDone(key.removePrefix(RULE), now)
                    key.startsWith(ONCE) -> repo.markOneOffDone(key.removePrefix(ONCE).toLong(), now)
                }
            }
        }
        bus.publish(
            PebbleEvent.ReminderActed(key, reminder?.kind ?: ReminderKind.CUSTOM, action, snoozeMinutes.takeIf { action == ReminderAction.SNOOZED }, now),
        )
        tick()
    }

    fun start(scope: CoroutineScope, periodMillis: Long = 30_000): Job = scope.launch {
        while (isActive) {
            tick()
            delay(periodMillis)
        }
    }

    private fun inWindow(rule: ReminderRule, now: Long): Boolean {
        val m = minuteOfDay(now)
        return if (rule.activeFromMinute <= rule.activeToMinute) m in rule.activeFromMinute until rule.activeToMinute
        else m >= rule.activeFromMinute || m < rule.activeToMinute // window crosses midnight
    }

    companion object {
        private const val RULE = "rule:"
        private const val ONCE = "once:"
        fun ruleKey(id: String) = RULE + id
        fun oneOffKey(id: Long) = ONCE + id
    }
}
