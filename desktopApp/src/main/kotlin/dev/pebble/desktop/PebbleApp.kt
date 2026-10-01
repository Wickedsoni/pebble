package dev.pebble.desktop

import dev.pebble.core.db.DatabaseFactory
import dev.pebble.core.event.EventBus
import dev.pebble.core.event.EventLogger
import dev.pebble.core.event.PebbleEvent
import dev.pebble.core.layout.WidgetLayoutRepository
import dev.pebble.core.memory.MemoryEngine
import dev.pebble.core.memory.MemoryRepository
import dev.pebble.core.quickadd.QuickCommand
import dev.pebble.core.reminders.ReminderEngine
import dev.pebble.core.reminders.ReminderKind
import dev.pebble.core.reminders.ReminderRepository
import dev.pebble.core.settings.SettingsRepository
import dev.pebble.core.settings.SettingsRepository.Keys
import dev.pebble.core.wellness.GLASS_ML
import dev.pebble.core.wellness.NoteRepository
import dev.pebble.core.wellness.WaterRepository
import dev.pebble.db.PebbleDatabase
import dev.pebble.desktop.pet.Mood
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Something the pet should say out loud (in its speech bubble), with the face to make. */
data class PetLine(val text: String, val mood: Mood = Mood.HAPPY, val durationMillis: Long = 4_000)

/** App-wide object graph. Created once in `main`, shared by every window. Lives on the Swing thread. */
class PebbleApp(db: PebbleDatabase) {
    val bus = EventBus()
    val layouts = WidgetLayoutRepository(db)
    val settings = SettingsRepository(db)
    val reminders = ReminderRepository(db).apply { seedDefaults() }
    val water = WaterRepository(db)
    val notes = NoteRepository(db)
    val memory = MemoryRepository(db)
    val brain = MemoryEngine(
        db, memory, clock = ::now,
        hourOf = { minuteOfDay(it) / 60 },
        dayOf = { java.time.Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay() },
        waterGoalMl = { waterGoalGlasses * GLASS_ML },
    )
    val engine = ReminderEngine(reminders, bus, clock = ::now, minuteOfDay = ::minuteOfDay, quietHours = brain::quietHours)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _petLines = MutableSharedFlow<PetLine>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val petLines: SharedFlow<PetLine> = _petLines

    /** Set by the UI once the tray exists; shows a Windows toast. */
    var notifier: (title: String, message: String) -> Unit = { _, _ -> }

    init {
        // Unconfined: events are written on the publisher's thread, so nothing is lost on exit.
        EventLogger(db).attach(bus, CoroutineScope(scope.coroutineContext + Dispatchers.Unconfined))
        bus.publish(PebbleEvent.AppStarted(now()))
        engine.start(scope)
        // Learning is cheap (a few small queries); every 10 minutes keeps memories fresh.
        scope.launch {
            while (isActive) {
                runCatching { brain.learn() }
                delay(10 * 60_000L)
            }
        }
    }

    fun completeNote(id: Long) {
        notes.archive(id, now())
        bus.publish(PebbleEvent.NoteCompleted(id, now()))
    }

    fun logMood(score: Int) {
        memory.logMood(score, now())
        bus.publish(PebbleEvent.MoodLogged(score, now()))
        brain.learn()
    }

    fun remember(text: String) {
        val key = memory.remember(text, now())
        bus.publish(PebbleEvent.FactRemembered(key, now()))
    }

    /** Whether the pet already asked "how was today?" today. */
    fun moodLoggedToday(): Boolean = memory.moodSince(startOfToday()).isNotEmpty()

    fun say(line: PetLine) {
        _petLines.tryEmit(line)
    }

    val waterGoalGlasses: Int get() = settings.int(Keys.WATER_GOAL_GLASSES, 8)

    fun startOfToday(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    fun logWater(glasses: Int = 1): Int {
        val goalMl = waterGoalGlasses * GLASS_ML
        val before = water.totalSince(startOfToday())
        repeat(glasses) { water.log(GLASS_ML, now()) }
        val total = water.totalSince(startOfToday())
        bus.publish(PebbleEvent.WaterLogged(glasses * GLASS_ML, total, now()))
        // Drinking counts as answering an open water reminder.
        engine.active.value.firstOrNull { it.kind == ReminderKind.WATER }
            ?.let { engine.act(it.key, dev.pebble.core.reminders.ReminderAction.DONE) }
        if (before < goalMl && total >= goalMl) {
            runCatching { brain.learn() }
            val streak = memory.byKey(MemoryEngine.KEY_WATER_STREAK)?.data
            say(PetLine(if (streak != null) "Water goal done — $streak days in a row." else "Water goal done for today.", Mood.CELEBRATE, 3_500))
        }
        return total / GLASS_ML
    }

    fun undoWater() {
        water.undoLast(startOfToday())
    }

    fun addNote(text: String): Long = notes.add(text, now()).also { bus.publish(PebbleEvent.NoteCreated(it, now())) }

    /** Runs a quick-add command and returns what the pet should say about it. */
    fun execute(cmd: QuickCommand): PetLine {
        bus.publish(PebbleEvent.QuickAddUsed(cmd::class.simpleName ?: "?", now()))
        return when (cmd) {
            is QuickCommand.AddNote -> {
                addNote(cmd.text)
                PetLine("Saved to your notes.")
            }
            is QuickCommand.RememberFact -> {
                remember(cmd.text)
                PetLine("Got it, I'll remember that.")
            }
            is QuickCommand.LogWater -> {
                val glasses = logWater(cmd.glasses)
                PetLine("$glasses of $waterGoalGlasses glasses today.", Mood.HAPPY)
            }
            is QuickCommand.SetInterval -> {
                val rule = reminders.rules().first { it.kind == cmd.kind }
                val strictness = cmd.strictness ?: rule.strictness
                reminders.updateRule(rule.id, cmd.minutes, strictness, enabled = true)
                engine.tick()
                PetLine("Every ${formatMinutes(cmd.minutes)} · ${strictness.label}")
            }
            is QuickCommand.RemindIn -> {
                val at = now() + cmd.minutes * 60_000L
                reminders.addOneOff(cmd.title, at)
                PetLine("I'll remind you in ${formatMinutes(cmd.minutes)}.")
            }
            is QuickCommand.RemindAt -> {
                val at = resolve(cmd)
                reminders.addOneOff(cmd.title, at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                PetLine("I'll remind you ${describeWhen(at)}.")
            }
        }
    }

    /** One-line preview shown under the quick-add field before you press Enter. */
    fun describe(cmd: QuickCommand): String = when (cmd) {
        is QuickCommand.AddNote -> "Save note: “${cmd.text}”"
        is QuickCommand.RememberFact -> "Remember: “${cmd.text}”"
        is QuickCommand.LogWater -> "Log ${cmd.glasses} glass${if (cmd.glasses > 1) "es" else ""} of water"
        is QuickCommand.SetInterval -> "${cmd.kind.name.lowercase().replaceFirstChar { it.uppercase() }} reminder every ${formatMinutes(cmd.minutes)}" +
            (cmd.strictness?.let { " · ${it.label}" } ?: "")
        is QuickCommand.RemindIn -> "Remind me: “${cmd.title}” in ${formatMinutes(cmd.minutes)}"
        is QuickCommand.RemindAt -> "Remind me: “${cmd.title}” ${describeWhen(resolve(cmd))}"
    }

    private fun resolve(cmd: QuickCommand.RemindAt): LocalDateTime {
        val today = LocalDate.now().atTime(cmd.hour, cmd.minute)
        return when (cmd.dayOffset) {
            null -> if (today.isAfter(LocalDateTime.now())) today else today.plusDays(1)
            else -> today.plusDays(cmd.dayOffset!!.toLong())
        }
    }

    private fun describeWhen(at: LocalDateTime): String {
        val time = at.format(DateTimeFormatter.ofPattern("h:mm a"))
        return when (at.toLocalDate()) {
            LocalDate.now() -> "at $time"
            LocalDate.now().plusDays(1) -> "tomorrow at $time"
            else -> at.format(DateTimeFormatter.ofPattern("EEE d MMM 'at' h:mm a"))
        }
    }

    fun shutdown() {
        bus.publish(PebbleEvent.AppStopping(now()))
        scope.cancel()
    }

    companion object {
        fun create(): PebbleApp = PebbleApp(DatabaseFactory.create())
    }
}

fun now(): Long = System.currentTimeMillis()

fun minuteOfDay(millis: Long): Int =
    java.time.Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().let { it.hour * 60 + it.minute }

fun formatMinutes(m: Int): String = when {
    m < 60 -> "$m min"
    m % 60 == 0 -> if (m == 60) "1 hour" else "${m / 60} hours"
    else -> "${m / 60}h ${m % 60}m"
}
