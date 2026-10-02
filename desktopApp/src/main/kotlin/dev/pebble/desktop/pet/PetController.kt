package dev.pebble.desktop.pet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.pebble.core.event.PebbleEvent
import dev.pebble.core.memory.MemoryEngine
import dev.pebble.core.quickadd.QuickCommand
import dev.pebble.core.reminders.ActiveReminder
import dev.pebble.core.reminders.Escalation
import dev.pebble.core.reminders.EscalationPolicy
import dev.pebble.core.reminders.ReminderAction
import dev.pebble.core.reminders.ReminderKind
import dev.pebble.core.settings.SettingsRepository.Keys
import dev.pebble.desktop.PebbleApp
import dev.pebble.desktop.PetLine
import dev.pebble.desktop.now
import dev.pebble.desktop.platform.Power
import dev.pebble.desktop.platform.UserActivity
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.random.Random

/** A button inside the pet's speech bubble. */
data class BubbleAction(val label: String, val onClick: () -> Unit)

data class Speech(val text: String, val actions: List<BubbleAction> = emptyList(), val untilMillis: Long? = null)

/**
 * The pet's behaviour, advanced by [update]. It wanders along the taskbar, naps when you're
 * away, hides for fullscreen apps, and turns due reminders into faces, speech bubbles, bounces,
 * toasts or following the cursor — per the reminder's strictness.
 *
 * Power: [nextFrameMillis] tells the frame loop how soon it needs another update. Still poses
 * tick at 4 Hz plus exact blink edges, motion at 24 fps (20 on battery), and a sleeping
 * or hidden pet wakes about once a second. Battery saver also stops idle wandering.
 */
class PetController(private val app: PebbleApp, private val openQuickAdd: () -> Unit, private val openApp: () -> Unit) {
    var character by mutableStateOf(app.settings.enum(Keys.PET_CHARACTER, Character.PEBBLE))
        private set
    var stage by mutableStateOf(app.settings.enum(Keys.PET_STAGE, Stage.TEEN))
        private set

    /** Window top-left in screen dp. */
    var x by mutableFloatStateOf(Float.NaN)
        private set
    var y by mutableFloatStateOf(Float.NaN)
        private set
    var hidden by mutableStateOf(false)
        private set
    var pose by mutableStateOf(PetPose())
        private set
    var frame by mutableStateOf(PetFrame())
        private set
    var speech by mutableStateOf<Speech?>(null)
        private set

    private enum class Behaviour { IDLE, WALKING, SLEEPING, DRAGGING, FALLING }

    private var behaviour = Behaviour.IDLE
    private var targetX = 0f
    private var vy = 0f
    private var squash = 0f
    private var time = 0f
    private var nextDecision = 4f
    private var nextBlink = 2f
    private var blinkUntil = -1f
    private var lastSystemPoll = -100f
    private var area: java.awt.Rectangle? = null
    private var idleMillis = 0L
    private var power = Power.State(onBattery = false, saver = false)
    private var transient: PetLine? = null
    private var transientUntil = 0L
    private var menuOpen = false
    private var reminderBubble: String? = null
    private val toasted = mutableSetOf<String>()

    // ------------------------------------------------------------------ memory-driven moments

    private var lastActiveSlot = -1L
    private var askedMoodDay = -1L
    private var nextRecall = 0L

    /** Hello on start, flavoured by what Pebble remembers (a streak, your rhythm, something you told it). */
    fun greet() {
        val hello = when (LocalTime.now().hour) {
            in 5..11 -> "Morning."
            in 12..16 -> "Hey."
            in 17..21 -> "Evening."
            else -> "Up late?"
        }
        react(PetLine(listOfNotNull(hello, recallLine()).joinToString(" "), Mood.HAPPY, 4_500))
        nextRecall = now() + 3 * 60 * 60_000L
    }

    /** A streak, a fact you shared, or a habit — whatever is most worth mentioning right now. */
    private fun recallLine(): String? {
        val m = app.memory.visible()
        m.firstOrNull { it.key == MemoryEngine.KEY_WATER_STREAK }?.let { return it.text }
        m.firstOrNull { it.key == MemoryEngine.KEY_DAYS_ACTIVE }?.let { return it.text }
        m.filter { it.fromUser && now() - it.updatedAt > 12 * 60 * 60_000L }.randomOrNull()?.let { return "You told me: ${it.text}." }
        return null
    }

    /** Once an hour while you're here: the raw signal for "when are you usually around". */
    private fun noteActivity() {
        val nowMs = now()
        val slot = nowMs / (60 * 60_000L)
        if (slot != lastActiveSlot) {
            lastActiveSlot = slot
            app.bus.publish(PebbleEvent.ActiveHour(LocalTime.now().hour, nowMs))
        }
        maybeAskMood()
        if (nowMs > nextRecall && speech == null && currentReminder() == null) {
            nextRecall = nowMs + 3 * 60 * 60_000L
            recallLine()?.let { react(PetLine(it, Mood.HAPPY, 5_000)) }
        }
    }

    /** One evening check-in a day, only if you haven't logged a mood and nothing else is going on. */
    private fun maybeAskMood() {
        val today = LocalDate.now().toEpochDay()
        if (askedMoodDay == today || LocalTime.now().hour < 18) return
        if (speech != null || currentReminder() != null || app.moodLoggedToday()) return
        askedMoodDay = today
        speech = Speech(
            "How was today?",
            listOf("Rough", "Low", "Okay", "Good", "Great").mapIndexed { i, label ->
                BubbleAction(label) {
                    speech = null
                    app.logMood(i + 1)
                    react(
                        if (i <= 1) {
                            PetLine("Thanks for telling me. Tomorrow's a new one.", Mood.LOVE, 3_500)
                        } else {
                            PetLine("Glad to hear it.", Mood.HAPPY, 2_500)
                        },
                    )
                }
            },
            untilMillis = now() + 3 * 60_000L,
        )
    }

    fun chooseCharacter(c: Character) { character = c; app.settings.set(Keys.PET_CHARACTER, c.name) }
    fun chooseStage(s: Stage) { stage = s; app.settings.set(Keys.PET_STAGE, s.name) }

    /** Make a face and optionally say something for a while (task done, water logged…). */
    fun react(line: PetLine) {
        transient = line
        transientUntil = now() + line.durationMillis
        // Buttons close the bubble when clicked, like the reminder buttons do.
        val actions = line.actions.map { a -> BubbleAction(a.label) { speech = null; a.onClick() } }
        if (line.text.isNotEmpty()) speech = Speech(line.text, actions, untilMillis = transientUntil)
        if (behaviour == Behaviour.SLEEPING) behaviour = Behaviour.IDLE
    }

    // ------------------------------------------------------------------ input

    fun onClick() {
        app.bus.publish(PebbleEvent.PetInteraction("click", now()))
        if (behaviour == Behaviour.SLEEPING) {
            behaviour = Behaviour.IDLE
            react(PetLine("I'm awake.", Mood.IDLE, 2_000))
            return
        }
        if (currentReminder() != null) return // the reminder bubble is already showing
        menuOpen = !menuOpen
        speech = if (menuOpen) {
            Speech(
                "What do you need?",
                listOf(
                    BubbleAction("+1 water") { closeMenu(); react(app.execute(QuickCommand.LogWater(1))) },
                    BubbleAction("Quick add") { closeMenu(); openQuickAdd() },
                    BubbleAction("Open Pebble") { closeMenu(); openApp() },
                ),
            )
        } else {
            null
        }
    }

    private fun closeMenu() { menuOpen = false; speech = null }

    fun onDoubleClick() {
        app.bus.publish(PebbleEvent.PetInteraction("pet", now()))
        closeMenu()
        react(PetLine("", Mood.LOVE, 1_800))
    }

    private var dragStartWin = 0f to 0f
    private var dragStartCursor = 0f to 0f

    fun onDragStart() {
        val c = UserActivity.cursor() ?: return
        behaviour = Behaviour.DRAGGING
        dragStartWin = x to y
        dragStartCursor = c.x.toFloat() to c.y.toFloat()
        closeMenu()
        app.bus.publish(PebbleEvent.PetInteraction("drag", now()))
    }

    fun onDrag() {
        val c = UserActivity.cursor() ?: return
        x = dragStartWin.first + (c.x - dragStartCursor.first)
        y = dragStartWin.second + (c.y - dragStartCursor.second)
    }

    fun onDragEnd() {
        behaviour = Behaviour.FALLING
        vy = 0f
    }

    // ------------------------------------------------------------------ per-frame update

    /** How long the frame loop may sleep before the next [update]. */
    fun nextFrameMillis(): Long {
        val moving = behaviour == Behaviour.WALKING || behaviour == Behaviour.FALLING || behaviour == Behaviour.DRAGGING ||
            pose.animated || squash > 0.01f
        return when {
            hidden -> 1_000

            behaviour == Behaviour.SLEEPING && speech == null -> 1_000

            moving -> if (power.onBattery) 50 else 42

            // Still: wake for the next blink edge (open or close) or a 4 Hz gaze check, whichever is first.
            else -> {
                val toBlinkEdge = ((if (time < blinkUntil) blinkUntil else nextBlink) - time) * 1000f
                toBlinkEdge.toLong().coerceIn(16, 250)
            }
        }
    }

    fun update(dt: Float, sizeW: Float, sizeH: Float) {
        time += dt
        val wa = area ?: UserActivity.workArea().also { area = it }
        val groundY = (wa.y + wa.height) - sizeH + 3f
        val minX = wa.x.toFloat()
        val maxX = (wa.x + wa.width) - sizeW
        if (x.isNaN()) { x = maxX - 180f; y = groundY }

        if (time - lastSystemPoll > 2f) {
            lastSystemPoll = time
            area = UserActivity.workArea()
            hidden = UserActivity.isFullscreenBusy()
            idleMillis = UserActivity.idleMillis()
            if (idleMillis < 60_000) noteActivity()
            power = Power.state()
        }
        if (hidden) return

        val nowMs = now()
        if (transient != null && nowMs > transientUntil) transient = null
        speech?.untilMillis?.let { if (nowMs > it) speech = null }

        val reminder = currentReminder()
        val escalation = reminder?.let { EscalationPolicy.stage(it.strictness, nowMs - it.dueAt) }
        if (reminder != null) {
            showReminder(reminder, escalation!!)
        } else if (reminderBubble != null) {
            // The reminder was handled elsewhere (water widget, quick add, another device): drop its bubble.
            if (speech?.text == reminderBubble) speech = null
            reminderBubble = null
        }

        when (behaviour) {
            Behaviour.DRAGGING -> Unit

            Behaviour.FALLING -> {
                vy += 2600f * dt
                y += vy * dt
                if (y >= groundY) {
                    y = groundY
                    squash = (vy / 1400f).coerceIn(0.3f, 1f)
                    behaviour = Behaviour.IDLE
                    nextDecision = time + 3f
                }
            }

            Behaviour.SLEEPING -> if (idleMillis < 5_000 || reminder != null) behaviour = Behaviour.IDLE

            Behaviour.IDLE, Behaviour.WALKING -> {
                if (y < groundY - 1f) { behaviour = Behaviour.FALLING; vy = 0f }
                if (escalation == Escalation.FOLLOW) {
                    UserActivity.cursor()?.let { targetX = (it.x - sizeW / 2f).coerceIn(minX, maxX) }
                    behaviour = if (abs(targetX - x) > 6f) Behaviour.WALKING else Behaviour.IDLE
                } else if (idleMillis > SLEEP_AFTER_MS && reminder == null && transient == null) {
                    behaviour = Behaviour.SLEEPING
                } else if (time > nextDecision && behaviour == Behaviour.IDLE && reminder == null && !menuOpen) {
                    // Wander now and then; less often on battery, never in battery saver.
                    nextDecision = time + Random.nextFloat() * 30f + if (power.onBattery) 60f else 25f
                    if (!power.saver && Random.nextFloat() < 0.4f) {
                        targetX = (x + (Random.nextFloat() - 0.5f) * 400f).coerceIn(minX, maxX)
                        behaviour = Behaviour.WALKING
                    }
                }
                if (behaviour == Behaviour.WALKING) {
                    val speed = if (escalation == Escalation.FOLLOW) 160f else 50f
                    val dx = targetX - x
                    x += (sign(dx) * speed * dt).let { if (abs(it) > abs(dx)) dx else it }
                    if (abs(targetX - x) < 1f) behaviour = Behaviour.IDLE
                }
            }
        }
        x = x.coerceIn(minX, maxX)
        squash = if (squash > 0.01f) squash * exp(-dt * 8f) else 0f

        pose = when {
            transient != null -> transient!!.mood.defaultPose()
            reminder != null -> reminderPose(reminder.kind, escalation!!)
            behaviour == Behaviour.SLEEPING -> Mood.SLEEPY.defaultPose()
            behaviour == Behaviour.DRAGGING -> PetPose(Mood.WORRIED, Arms.UP)
            menuOpen -> PetPose(Mood.IDLE, Arms.WAVE)
            else -> PetPose()
        }.let { if (behaviour == Behaviour.WALKING) it.copy(motion = Motion.HOP) else it }

        if (time > nextBlink) { blinkUntil = time + 0.15f; nextBlink = time + 3f + Random.nextFloat() * 4f }
        val blink = if (time < blinkUntil) 0.12f else 1f
        val (lx, ly) = if (behaviour == Behaviour.SLEEPING) {
            0f to 0f
        } else {
            UserActivity.cursor()?.let { c ->
                // Quantise gaze so tiny mouse moves don't trigger redraws.
                val gx = ((c.x - (x + sizeW / 2f)) / 400f).coerceIn(-1f, 1f)
                val gy = ((c.y - (y + sizeH / 2f)) / 300f).coerceIn(-1f, 1f)
                (gx * 4).roundToInt() / 4f to (gy * 4).roundToInt() / 4f
            } ?: (0f to 0f)
        }
        // Time only matters to animated poses; freezing it otherwise keeps the frame equal so nothing redraws.
        val next = PetFrame(if (pose.animated || squash > 0f) time else 0f, blink, lx, ly, squash)
        if (next != frame) frame = next
    }

    private fun currentReminder(): ActiveReminder? = app.engine.active.value.firstOrNull()

    private fun showReminder(r: ActiveReminder, escalation: Escalation) {
        menuOpen = false
        val text = r.title + if (escalation == Escalation.FOLLOW) "\nI'll stay with you until it's done." else ""
        val actions = listOf(
            BubbleAction("Done") { act(r, ReminderAction.DONE) },
            BubbleAction("Snooze 10m") { act(r, ReminderAction.SNOOZED) },
            BubbleAction("Skip") { act(r, ReminderAction.DISMISSED) },
        )
        if (speech?.text != text) speech = Speech(text, actions)
        reminderBubble = text
        if (escalation == Escalation.TOAST && toasted.add(r.key + r.dueAt)) {
            app.notifier("Pebble", r.title)
        }
    }

    private fun act(r: ActiveReminder, action: ReminderAction) {
        speech = null
        if (r.kind == ReminderKind.WATER && action == ReminderAction.DONE) app.logWater(1) else app.engine.act(r.key, action)
        react(
            when (action) {
                ReminderAction.DONE -> PetLine("Nice.", Mood.CELEBRATE, 2_200)
                ReminderAction.SNOOZED -> PetLine("Okay, in 10 minutes.", Mood.IDLE, 1_800)
                ReminderAction.DISMISSED -> PetLine("", Mood.SAD, 1_200)
            },
        )
    }

    private fun reminderPose(kind: ReminderKind, escalation: Escalation): PetPose {
        val base = when (kind) {
            ReminderKind.WATER -> Mood.THIRSTY.defaultPose()
            ReminderKind.STRETCH -> PetPose(Mood.IDLE, Arms.UP)
            ReminderKind.EYES -> PetPose(Mood.IDLE, Arms.WAVE)
            ReminderKind.CUSTOM -> Mood.NEEDS_INPUT.defaultPose()
        }
        return if (escalation >= Escalation.BOUNCE) base.copy(motion = Motion.HOP) else base
    }

    companion object {
        private const val SLEEP_AFTER_MS = 5 * 60_000L
    }
}
