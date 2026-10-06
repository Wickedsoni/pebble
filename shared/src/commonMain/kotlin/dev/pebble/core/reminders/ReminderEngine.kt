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
 *
 * Not thread-safe: the engine keeps plain mutable state and is confined to one thread (the main thread in
 * the app). Call [tick], [act], [defer], [lessOften] and [upcoming] from that thread only.
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

    // State, all keyed by reminder key. [tick] drops the keys of rules that are off and one-offs that are gone.
    private val startedAt = clock()
    private val decided = mutableMapOf<String, Decision>()
    private val snoozedUntil = mutableMapOf<String, Long>()
    private val skips = mutableMapOf<String, Int>()
    private val announced = mutableSetOf<String>()

    private val _active = MutableStateFlow<List<ActiveReminder>>(emptyList())
    val active: StateFlow<List<ActiveReminder>> = _active.asStateFlow()

    fun tick() {
        val now = clock()
        val quiet = nudge == null && (minuteOfDay(now) / 60) in quietHours()
        val rules = repo.rules().filter { it.enabled }
        val oneOffs = repo.pendingOneOffs()
        pruneState(rules.map { ruleKey(it.id) }.toSet() + oneOffs.map { oneOffKey(it.id) })
        val due = buildList {
            if (!quiet) {
                rules.forEach { rule ->
                    val key = ruleKey(rule.id)
                    val dueAt = snoozedUntil[key] ?: ((rule.lastDoneAt ?: startedAt) + rule.intervalMinutes * backOff(key) * 60_000L)
                    if (now >= dueAt && inWindow(rule, now) && !deferredByPolicy(key, rule.kind, now)) {
                        add(ActiveReminder(key, rule.kind, rule.title, rule.strictness, dueAt))
                    }
                }
            }
            oneOffs.forEach { r ->
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

    /** Forgets the state of rules that are off and of one-offs that are gone, so it cannot come back with a re-enabled rule. */
    private fun pruneState(liveKeys: Set<String>) {
        decided.keys.retainAll(liveKeys)
        snoozedUntil.keys.retainAll(liveKeys)
        skips.keys.retainAll(liveKeys)
    }

    /**
     * What's coming next — repeating reminders' next due time and pending one-offs — soonest first. A repeating
     * reminder whose due time falls outside its active window is shown at the start of the next window.
     */
    fun upcoming(limit: Int = 5): List<ActiveReminder> {
        val rules = repo.rules().filter { it.enabled }.map { rule ->
            val key = ruleKey(rule.id)
            val dueAt = snoozedUntil[key] ?: ((rule.lastDoneAt ?: startedAt) + rule.intervalMinutes * backOff(key) * 60_000L)
            ActiveReminder(key, rule.kind, rule.title, rule.strictness, if (inWindow(rule, dueAt)) dueAt else nextWindowStart(rule, dueAt))
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
                // Each skip in a row pushes the next one further out (x2, then x3); doing it resets that.
                if (action == ReminderAction.DISMISSED) skips[key] = (skips[key] ?: 0) + 1 else skips.remove(key)
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

    private fun backOff(key: String): Int = 1 + (skips[key] ?: 0).coerceAtMost(2)

    /**
     * Holds a due reminder back for [minutes] without counting it as a reaction — used while you're
     * watching a video or a fullscreen app, or when a gentle reminder went unanswered.
     */
    fun defer(key: String, minutes: Int) {
        snoozedUntil[key] = clock() + minutes * 60_000L
        tick()
    }

    /** "Less often": this repeating reminder's gap grows by half (max 4 h), and this one counts as done. */
    fun lessOften(key: String): Int? {
        if (!key.startsWith(RULE)) return null
        val rule = repo.rules().firstOrNull { it.id == key.removePrefix(RULE) } ?: return null
        val minutes = (rule.intervalMinutes * 3 / 2).coerceAtMost(240)
        repo.updateRule(rule.id, minutes, rule.strictness, rule.enabled)
        act(key, ReminderAction.DONE)
        return minutes
    }

    /**
     * The first minute at or after [from] that opens [rule]'s active window (local time, from [minuteOfDay]).
     * A clock change (DST) between [from] and the window start moves the local minute, so the guess is checked once
     * and moved by the difference. If the start minute does not exist that day (it is in the spring-forward gap), the
     * window opens at the first minute after the gap.
     */
    private fun nextWindowStart(rule: ReminderRule, from: Long): Long {
        val wait = (rule.activeFromMinute - minuteOfDay(from) + MINUTES_PER_DAY) % MINUTES_PER_DAY
        val guess = from - from.mod(MINUTE) + wait * MINUTE
        val half = MINUTES_PER_DAY / 2
        val drift = (rule.activeFromMinute - minuteOfDay(guess) + MINUTES_PER_DAY + half) % MINUTES_PER_DAY - half
        val corrected = guess + drift * MINUTE
        if (corrected >= from && minuteOfDay(corrected) == rule.activeFromMinute) return corrected
        val start = maxOf(corrected, from - from.mod(MINUTE))
        // A window that lies wholly in the gap does not open that day: show the start of the next day.
        return (0 until MAX_GAP_MINUTES).asSequence().map { start + it * MINUTE }.firstOrNull { inWindow(rule, it) }
            ?: (guess + MINUTE * MINUTES_PER_DAY)
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
        private const val MINUTES_PER_DAY = 24 * 60
        private const val MINUTE = 60_000L
        private const val MAX_GAP_MINUTES = 180 // longer than any clock-change gap
        private const val IGNORED_AFTER = 30 * 60_000L
        fun ruleKey(id: String) = RULE + id
        fun oneOffKey(id: Long) = ONCE + id
    }
}
