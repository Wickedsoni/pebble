package dev.pebble.core.brain

import dev.pebble.core.brain.PebbleActions as A
import dev.pebble.core.quickadd.QuickAddParser
import dev.pebble.core.quickadd.QuickCommand

/**
 * The command cascade — cheapest path first, as in the brain plan:
 *   1. rules (QuickAddParser.parseStrict): exact syntax like "water every 45m", "note: …"
 *   2. the local model ([Understanding]): free-form Hindi / Hinglish / English
 *   3. not sure → ask "Did you mean…?" with the top guesses; your pick becomes a training label
 * With no model available (missing file, low-spec mode), step 2 is skipped and anything
 * unrecognised is saved as a note, exactly like before.
 */
class CommandRouter(
    private val model: () -> Understanding?,
    private val confident: Float = 0.6f,
) {
    enum class Source { RULES, MODEL, FALLBACK }

    sealed interface Routed {
        data class Run(val command: QuickCommand, val source: Source, val understood: Understood? = null) : Routed

        /** Not sure enough to act: show [question] with [options]; the chosen one is recorded as feedback. */
        data class Ask(val question: String, val options: List<Option>, val understood: Understood?) : Routed
    }

    data class Option(val label: String, val action: String, val command: QuickCommand)

    fun route(input: String): Routed? {
        val text = input.trim()
        if (text.isEmpty()) return null
        QuickAddParser.parseStrict(text)?.let { return Routed.Run(it, Source.RULES) }

        val understood = model()?.understand(text)
            ?: return Routed.Run(QuickCommand.AddNote(text), Source.FALLBACK)

        val action = A.fromMassive(understood.top.intent)
        if (understood.top.confidence >= confident) {
            val cmd = toCommand(action, understood, text)
            return if (cmd != null) Routed.Run(cmd, Source.MODEL, understood) else askWhen(text, understood)
        }
        return didYouMean(text, understood)
    }

    /** Model action → concrete command. Null for a reminder whose time we couldn't read. */
    private fun toCommand(action: String, u: Understood, text: String): QuickCommand? = when (action) {
        A.REMIND -> reminder(u, text)
        A.ADD_NOTE -> QuickCommand.AddNote(text)
        A.REMINDERS_QUERY -> QuickCommand.ShowUpcoming
        A.NOTES_QUERY -> QuickCommand.ShowNotes
        A.TIME_QUERY -> QuickCommand.TellTime
        A.CHITCHAT -> QuickCommand.Chitchat(text, u.top.intent)
        A.REMINDER_REMOVE -> QuickCommand.OpenPage("reminders")
        A.NOTE_REMOVE -> QuickCommand.OpenPage("notes")
        else -> QuickCommand.Unsupported(text, u.top.intent)
    }

    private fun reminder(u: Understood, text: String): QuickCommand? {
        val slots = u.slots()
        val timeText = listOfNotNull(slots["date"], slots["time"], slots["timeofday"]).joinToString(" ")
        val title = reminderTitle(u, text)
        return when (val w = HinglishTime.parse(timeText.ifBlank { text })) {
            is HinglishTime.When.At -> QuickCommand.RemindAt(title, w.hour, w.minute, w.dayOffset, w.flexibleHalfDay)
            is HinglishTime.When.In -> QuickCommand.RemindIn(title, w.minutes)
            null -> null
        }
    }

    /** The reminder text: the event slot if the model found one, else the words minus time words and filler. */
    private fun reminderTitle(u: Understood, text: String): String {
        u.slots()["event_name"]?.let { return it.replaceFirstChar(Char::uppercase) }
        val kept = u.words.zip(u.tags).filter { (w, t) ->
            t == "O" && w.lowercase().trim(',', '.', '?', '!') !in filler
        }.map { it.first }
        return kept.joinToString(" ").ifBlank { text }.replaceFirstChar(Char::uppercase)
    }

    private fun askWhen(text: String, u: Understood): Routed.Ask {
        val title = reminderTitle(u, text)
        return Routed.Ask(
            "When should I remind you: “$title”?",
            listOf(
                Option("In 30 min", A.REMIND, QuickCommand.RemindIn(title, 30)),
                Option("In 1 hour", A.REMIND, QuickCommand.RemindIn(title, 60)),
                Option("This evening", A.REMIND, QuickCommand.RemindAt(title, 19, 0, null)),
                Option("Tomorrow 9 AM", A.REMIND, QuickCommand.RemindAt(title, 9, 0, 1)),
            ),
            u,
        )
    }

    private fun didYouMean(text: String, u: Understood): Routed.Ask {
        val seen = mutableSetOf<String>()
        val options = u.guesses.mapNotNull { g ->
            val action = A.fromMassive(g.intent)
            if (!seen.add(action) || action == A.OTHER) return@mapNotNull null
            val cmd = toCommand(action, u.copy(guesses = listOf(g)), text) ?: return@mapNotNull null
            Option(labelFor(action), action, cmd)
        }.toMutableList()
        if (options.none { it.action == A.ADD_NOTE }) options += Option(labelFor(A.ADD_NOTE), A.ADD_NOTE, QuickCommand.AddNote(text))
        return Routed.Ask("Did you mean…", options.take(3), u)
    }

    companion object {
        fun labelFor(action: String) = when (action) {
            A.REMIND -> "Set a reminder"
            A.REMINDERS_QUERY -> "Show what's coming up"
            A.REMINDER_REMOVE -> "Remove a reminder"
            A.ADD_NOTE -> "Save as a note"
            A.NOTES_QUERY -> "Show my notes"
            A.NOTE_REMOVE -> "Remove a note"
            A.TIME_QUERY -> "Tell me the time"
            A.CHITCHAT -> "Just chatting"
            else -> "Something else"
        }

        /** Words that frame a reminder rather than describe it, in all three scripts. */
        private val filler = setOf(
            "remind", "me", "to", "set", "a", "reminder", "for", "please", "alarm", "wake", "up", "at", "about", "don't", "let", "forget",
            "mujhe", "yaad", "dila", "dilana", "dilaana", "dena", "dila", "do", "karo", "kar", "ki", "ka", "ke", "ko", "reminder", "laga", "lagao", "set", "utha",
            "मुझे", "याद", "दिला", "दिलाना", "देना", "दो", "करो", "की", "का", "के", "को", "रिमाइंडर", "लगा", "लगाओ", "जगा", "उठा",
        )
    }
}
