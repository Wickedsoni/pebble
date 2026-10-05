package dev.pebble.desktop.command

import dev.pebble.core.brain.Replies
import dev.pebble.core.calendar.CalendarEvent
import dev.pebble.core.calendar.CalendarRepository
import dev.pebble.core.event.EventBus
import dev.pebble.core.event.PebbleEvent
import dev.pebble.core.quickadd.QuickCommand
import dev.pebble.core.reminders.ReminderEngine
import dev.pebble.core.reminders.ReminderRepository
import dev.pebble.core.wellness.NoteRepository
import dev.pebble.desktop.PetLine
import dev.pebble.desktop.core.AppEnv
import dev.pebble.desktop.core.UiPort
import dev.pebble.desktop.pet.Mood
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** What a command did: the pet's reply, and how to take it back (null if there is nothing to undo). */
data class Executed(val line: PetLine, val undo: (() -> Unit)?)

/** App-level actions a command can trigger, beyond a plain repository write (events, goals, celebrations). */
interface CommandActions {
    val waterGoalGlasses: Int

    fun addNote(text: String): Long

    fun remember(text: String)

    /** Your notes, facts and chat that match [topic], best first (memory search, WP C2). */
    fun searchMemory(topic: String): List<String>

    /** Logs [glasses] and returns how many glasses you drank today. */
    fun logWater(glasses: Int = 1): Int

    /** Saves a calendar event and makes its reminders (WP E2). */
    fun saveEvent(e: CalendarEvent)

    fun deleteEvent(uid: String)
}

/**
 * Runs quick-add commands. One exhaustive `when` over the sealed [QuickCommand], so the compiler finds a
 * command without a handler (ADR 0001). Each call returns its own undo, so two commands can't mix them up.
 */
class CommandExecutor(
    private val env: AppEnv,
    private val bus: EventBus,
    private val notes: NoteRepository,
    private val reminders: ReminderRepository,
    private val engine: ReminderEngine,
    private val actions: CommandActions,
    /** For [QuickCommand.OpenPage]. */
    private val ui: UiPort,
) {
    fun execute(cmd: QuickCommand): Executed {
        bus.publish(PebbleEvent.QuickAddUsed(cmd::class.simpleName ?: "?", env.millis()))
        return when (cmd) {
            is QuickCommand.AddNote -> {
                val id = actions.addNote(cmd.text)
                Executed(PetLine("Saved to your notes."), undo = { notes.delete(id, env.millis()) })
            }

            is QuickCommand.RememberFact -> {
                actions.remember(cmd.text)
                Executed(PetLine("Got it, I'll remember that."), undo = null)
            }

            is QuickCommand.LogWater -> {
                val glasses = actions.logWater(cmd.glasses)
                Executed(PetLine("$glasses of ${actions.waterGoalGlasses} glasses today.", Mood.HAPPY), undo = null)
            }

            is QuickCommand.SetInterval -> {
                val rule = reminders.rules().first { it.kind == cmd.kind }
                val strictness = cmd.strictness ?: rule.strictness
                reminders.updateRule(rule.id, cmd.minutes, strictness, enabled = true)
                engine.tick()
                Executed(PetLine("Every ${formatMinutes(cmd.minutes)} · ${strictness.label}"), undo = null)
            }

            is QuickCommand.RemindIn -> {
                val at = env.millis() + cmd.minutes * 60_000L
                val id = reminders.addOneOff(cmd.title, dueAt = at, at = env.millis())
                Executed(PetLine("I'll remind you in ${formatMinutes(cmd.minutes)}."), undo = {
                    reminders.deleteOneOff(id, env.millis()); engine.tick()
                })
            }

            is QuickCommand.RemindAt -> {
                val at = resolve(cmd)
                val id = reminders.addOneOff(cmd.title, dueAt = env.toMillis(at), at = env.millis())
                Executed(PetLine("I'll remind you ${describeWhen(at)}."), undo = {
                    reminders.deleteOneOff(id, env.millis()); engine.tick()
                })
            }

            QuickCommand.ShowUpcoming -> {
                val next = engine.upcoming(3)
                Executed(
                    PetLine(
                        if (next.isEmpty()) {
                            "Nothing coming up."
                        } else {
                            "Next: " +
                                next.joinToString(" · ") { "${it.title} ${dueIn(it.dueAt)}" }
                        },
                        Mood.IDLE,
                        6_000,
                    ),
                    undo = null,
                )
            }

            QuickCommand.ShowNotes -> {
                val open = notes.recent(3)
                Executed(
                    PetLine(
                        if (open.isEmpty()) {
                            "No open notes."
                        } else {
                            open.joinToString(" · ") {
                                it.text
                            }
                        },
                        Mood.IDLE,
                        6_000,
                    ),
                    undo = null,
                )
            }

            QuickCommand.TellTime -> Executed(
                PetLine("It's " + env.localNow().format(DateTimeFormatter.ofPattern("h:mm a, EEEE")) + ".", Mood.IDLE),
                undo = null,
            )

            is QuickCommand.Chitchat -> Executed(PetLine(Replies.chitchat(cmd.text, cmd.intent, cmd.mood), Mood.HAPPY, 5_000), undo = null)

            is QuickCommand.Unsupported -> Executed(PetLine(Replies.unsupported(cmd.text), Mood.IDLE, 5_000), undo = null)

            is QuickCommand.SearchMemory -> Executed(
                PetLine(Replies.memoryHits(cmd.text, actions.searchMemory(cmd.topic)), Mood.IDLE, 8_000),
                undo = null,
            )

            is QuickCommand.AddEvent -> {
                val e = event(cmd)
                actions.saveEvent(e)
                Executed(PetLine("Added to your calendar: ${e.title}, ${describeEvent(e)}.", Mood.HAPPY, 5_000), undo = {
                    actions.deleteEvent(e.uid)
                })
            }

            is QuickCommand.OpenPage -> {
                ui.openPage(cmd.page)
                Executed(PetLine(Replies.openPage(cmd.page, cmd.text, cmd.forRemoval), Mood.HAPPY, 3_000), undo = null)
            }
        }
    }

    /** One-line preview shown under the quick-add field before you press Enter. */
    fun describe(cmd: QuickCommand): String = when (cmd) {
        is QuickCommand.AddNote -> "Save note: “${cmd.text}”"

        is QuickCommand.RememberFact -> "Remember: “${cmd.text}”"

        is QuickCommand.LogWater -> "Log ${cmd.glasses} glass${if (cmd.glasses > 1) "es" else ""} of water"

        is QuickCommand.SetInterval -> "${cmd.kind.name.lowercase().replaceFirstChar {
            it.uppercase()
        }} reminder every ${formatMinutes(cmd.minutes)}" +
            (cmd.strictness?.let { " · ${it.label}" } ?: "")

        is QuickCommand.RemindIn -> "Remind me: “${cmd.title}” in ${formatMinutes(cmd.minutes)}"

        is QuickCommand.RemindAt -> "Remind me: “${cmd.title}” ${describeWhen(resolve(cmd))}"

        QuickCommand.ShowUpcoming -> "Show what's coming up"

        QuickCommand.ShowNotes -> "Show my notes"

        QuickCommand.TellTime -> "Tell me the time"

        is QuickCommand.Chitchat -> "Chat with Pebble"

        is QuickCommand.Unsupported -> "Can't do this yet"

        is QuickCommand.SearchMemory -> "Search my notes for “${cmd.topic}”"

        is QuickCommand.OpenPage -> "Open ${cmd.page} in Pebble"

        is QuickCommand.AddEvent -> event(cmd, uid = "").let { "Add to calendar: “${it.title}” ${describeEvent(it)}" }
    }

    /** The event an [QuickCommand.AddEvent] makes now ([eventFor]). Internal for tests. */
    internal fun event(cmd: QuickCommand.AddEvent, uid: String = CalendarRepository.newUid()): CalendarEvent = eventFor(cmd, env, uid)

    /** "Fri 9 Oct, 5:00 PM–6:00 PM" or "tomorrow, all day". */
    private fun describeEvent(e: CalendarEvent): String {
        val zone = env.zone()
        val start = Instant.ofEpochMilli(e.startAt).atZone(zone)
        val day = when (start.toLocalDate()) {
            env.today() -> "today"
            env.today().plusDays(1) -> "tomorrow"
            else -> start.format(DateTimeFormatter.ofPattern("EEE d MMM"))
        }
        if (e.allDay) return "$day, all day"
        val time = DateTimeFormatter.ofPattern("h:mm a")
        return "$day, ${start.format(time)}–${Instant.ofEpochMilli(e.endAt).atZone(zone).format(time)}"
    }

    /** When a "remind me at …" command fires, on the app's clock. Internal for [dev.pebble.desktop.ResolveTimeTest]. */
    internal fun resolve(cmd: QuickCommand.RemindAt): LocalDateTime {
        val now = env.localNow()
        val base = env.today().plusDays((cmd.dayOffset ?: 0).toLong())
        // "5 baje" without am/pm: whichever of 5:00 / 17:00 comes next on that day.
        val candidates = if (cmd.flexibleHalfDay && cmd.hour < 12) listOf(cmd.hour, cmd.hour + 12) else listOf(cmd.hour)
        val onDay = candidates.map { base.atTime(it, cmd.minute) }
        if (cmd.dayOffset != null) return onDay.firstOrNull { it.isAfter(now) } ?: onDay.last()
        return onDay.firstOrNull { it.isAfter(now) } ?: base.plusDays(1).atTime(candidates.first(), cmd.minute)
    }

    private fun describeWhen(at: LocalDateTime): String {
        val time = at.format(DateTimeFormatter.ofPattern("h:mm a"))
        val today = env.today()
        return when (at.toLocalDate()) {
            today -> "at $time"
            today.plusDays(1) -> "tomorrow at $time"
            else -> at.format(DateTimeFormatter.ofPattern("EEE d MMM 'at' h:mm a"))
        }
    }

    private fun dueIn(at: Long): String {
        val m = ((at - env.millis()) / 60_000).toInt()
        return if (m <= 0) "now" else "in ${formatMinutes(m)}"
    }
}

/**
 * The event an [QuickCommand.AddEvent] makes, on [env]'s clock and zone. [onDate]: the day to use when [cmd] names
 * none (the day selected on the Calendar page); null: today, or tomorrow if the time has passed. A timed event gets
 * a reminder [EVENT_REMIND_MINUTES] before it; an all-day event gets none.
 */
fun eventFor(cmd: QuickCommand.AddEvent, env: AppEnv, uid: String = CalendarRepository.newUid(), onDate: LocalDate? = null): CalendarEvent {
    val zone = env.zone()
    val today = env.today()
    val month = cmd.month
    val dayOfMonth = cmd.dayOfMonth
    val date = if (month != null && dayOfMonth != null) {
        // The next such date: this year, or a later year if it has passed (29 Feb: the next leap year).
        generateSequence(today.year) { it + 1 }.take(8).mapNotNull {
            runCatching { LocalDate.of(it, month, dayOfMonth) }.getOrNull()
        }.firstOrNull { !it.isBefore(today) } ?: today
    } else {
        cmd.dayOffset?.let { today.plusDays(it.toLong()) } ?: onDate ?: today
    }
    val hour = cmd.hour
    if (hour == null) {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        return CalendarEvent(uid, cmd.title, start, date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(), zone.id, allDay = true)
    }
    val now = env.localNow()
    val candidates = if (cmd.flexibleHalfDay && hour < 12) listOf(hour, hour + 12) else listOf(hour)
    val explicitDay = cmd.dayOffset != null || month != null || onDate != null
    val onDay = candidates.map { date.atTime(it, cmd.minute) }
    val start = onDay.firstOrNull { it.isAfter(now) }
        ?: if (explicitDay) onDay.last() else date.plusDays(1).atTime(candidates.first(), cmd.minute)
    val startAt = env.toMillis(start)
    val length = (cmd.durationMinutes ?: EVENT_MINUTES) * 60_000L
    return CalendarEvent(uid, cmd.title, startAt, startAt + length, zone.id, remindMinutes = EVENT_REMIND_MINUTES)
}

/** The length of an event made without "for …". */
const val EVENT_MINUTES = 60

/** Timed events from Quick Add and the Calendar page remind you this many minutes before. */
const val EVENT_REMIND_MINUTES = 15

fun formatMinutes(m: Int): String = when {
    m < 60 -> "$m min"
    m % 60 == 0 -> if (m == 60) "1 hour" else "${m / 60} hours"
    else -> "${m / 60}h ${m % 60}m"
}
