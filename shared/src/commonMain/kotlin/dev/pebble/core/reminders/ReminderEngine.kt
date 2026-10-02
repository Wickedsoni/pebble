package dev.pebble.core.reminders

import dev.pebble.core.brain.NudgeArm
import dev.pebble.core.brain.NudgeContext
import dev.pebble.core.brain.NudgePolicy
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
    /**
     * Learns *when* repeating reminders land (NudgePolicy). Null = fire on time, as before. With a
     * policy, quiet hours stop being a hard block and only bias it; one-off reminders always fire on time.
     */
    private val nudge: NudgePolicy? = null,
    /** You're in a fullscreen app / presenting: part of the nudge policy's context. */
    private val busy: () -> Boolean = { false },
) {
    /** One nudge decision per due cycle of a repeating reminder: at most one wait, then it shows. */
    private class Decision(val ctx: NudgeContext, val arm: NudgeArm, var shownAt: Long? = null, var rewarded: Boolean = false)
    private val decided = mutableMapOf<String, Decision>()

    private val startedAt = clock()
    private val snoozedUntil = mutableMapOf<String, Long>()
    private val announced = mutableSetOf<String>()

    private val _active = MutableStateFlow<List<ActiveReminder>>(emptyList())
    val active: StateFlow<List<ActiveReminder>> = _active.asStateFlow()

    fun tick() {
        val now = clock()
        val quiet = nudge == null && (minuteOfDay(now) / 60) in quietHours()
        val due = buildList {
            if (!quiet) {
                repo.rules().filter { it.enabled }.forEach { rule ->
                    val key = ruleKey(rule.id)
                    val dueAt = snoozedUntil[key] ?: ((rule.lastDoneAt ?: startedAt) + rule.intervalMinutes * 60_000L)
                    if (now >= dueAt && inWindow(rule, now) && !deferredByPolicy(key, rule.kind, now)) {
                        add(ActiveReminder(key, rule.kind, rule.title, rule.strictness, dueAt))
                    }
                }
            }
            repo.pendingOneOffs().forEach { r ->
                val key = oneOffKey(r.id)
                val dueAt = snoozedUntil[key] ?: r.dueAt
                if (now >= dueAt) add(ActiveReminder(key, ReminderKind.CUSTOM, r.title, r.strictness, dueAt))
            }
        }.sortedBy { it.dueAt }

        due.filter { announced.add(it.key) }.forEach {
            decided[it.key]?.let { d -> if (d.shownAt == null) d.shownAt = now }
            bus.publish(PebbleEvent.ReminderDue(it.key, it.kind, it.title, now))
        }
        // Shown for 30 minutes with no reaction: that nudge didn't land.
        decided.forEach { (_, d) ->
            val shown = d.shownAt
            if (shown != null && !d.rewarded && now - shown >= IGNORED_AFTER) {
                nudge?.learn(d.ctx, d.arm, NudgePolicy.rewardFor(null, 30.0))
                d.rewarded = true
            }
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
        decided[key]?.let { d ->
            if (!d.rewarded) {
                nudge?.learn(d.ctx, d.arm, NudgePolicy.rewardFor(action, (now - (d.shownAt ?: now)) / 60_000.0))
                d.rewarded = true
            }
            if (action != ReminderAction.SNOOZED) decided.remove(key)
        }
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
            PebbleEvent.ReminderActed(
                key,
                reminder?.kind ?: ReminderKind.CUSTOM,
                action,
                snoozeMinutes.takeIf {
                    action ==
                        ReminderAction.SNOOZED
                },
                now,
            ),
        )
        tick()
    }

    fun start(scope: CoroutineScope, periodMillis: Long = 30_000): Job = scope.launch {
        while (isActive) {
            tick()
            delay(periodMillis)
        }
    }

    /**
     * Asks the nudge policy once per due cycle. WAIT_x defers this reminder once (the existing snooze
     * mechanism), after which it shows regardless: Pebble may wait at most 30 minutes, never hide it.
     */
    private fun deferredByPolicy(key: String, kind: ReminderKind, now: Long): Boolean {
        val policy = nudge ?: return false
        if (key in decided) return false
        val ctx = NudgeContext.of(kind, minuteOfDay(now) / 60, busy())
        val choice = policy.choose(ctx)
        decided[key] = Decision(ctx, choice.arm)
        bus.publish(PebbleEvent.NudgeDecided(key, ctx.key, choice.arm.name, choice.propensity, now))
        if (choice.arm == NudgeArm.NOW) return false
        snoozedUntil[key] = now + choice.arm.waitMinutes * 60_000L
        return true
    }

    private fun inWindow(rule: ReminderRule, now: Long): Boolean {
        val m = minuteOfDay(now)
        return if (rule.activeFromMinute <= rule.activeToMinute) {
            m in rule.activeFromMinute until rule.activeToMinute
        } else {
            m >= rule.activeFromMinute || m < rule.activeToMinute // window crosses midnight
        }
    }

    companion object {
        private const val RULE = "rule:"
        private const val ONCE = "once:"
        private const val IGNORED_AFTER = 30 * 60_000L
        fun ruleKey(id: String) = RULE + id
        fun oneOffKey(id: Long) = ONCE + id
    }
}
