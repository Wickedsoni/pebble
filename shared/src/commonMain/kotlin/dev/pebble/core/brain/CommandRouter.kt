package dev.pebble.core.brain

import dev.pebble.core.quickadd.QuickAddParser
import dev.pebble.core.quickadd.QuickCommand
import dev.pebble.core.search.MemorySearch
import dev.pebble.core.brain.PebbleActions as A

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
    private val policy: DecisionPolicy = DecisionPolicy(),
    /** Today's ISO weekday (1 = Monday … 7 = Sunday), so "friday wali meeting" gets a date. */
    private val today: () -> Int? = { null },
) {
    enum class Source { RULES, MODEL, FALLBACK }

    sealed interface Routed {
        /** [action]: the Pebble action the model chose (MODEL source only), so a "Not what I meant" can name it. */
        data class Run(
            val command: QuickCommand,
            val source: Source,
            val understood: Understood? = null,
            val action: String? = null,
        ) : Routed

        /** Not sure enough to act: show [question] with [options]; the chosen one is recorded as feedback. */
        data class Ask(val question: String, val options: List<Option>, val understood: Understood?) : Routed
    }

    data class Option(val label: String, val action: String, val command: QuickCommand)

    fun route(input: String): Routed? {
        val text = input.trim()
        if (text.isEmpty()) return null
        QuickAddParser.parseStrict(text, today())?.let { return Routed.Run(it, Source.RULES) }
        val lowByWords = Replies.isLowMood(text)
        val understood = model()?.understand(text)
            ?: return if (lowByWords) {
                Routed.Run(QuickCommand.Chitchat(text, Replies.LOW_MOOD), Source.RULES)
            } else {
                Routed.Run(QuickCommand.AddNote(text), Source.FALLBACK)
            }
        // Feelings: a low mood (word list, or the mood head for what words miss — "sab galat ho raha hai")
        // gets a caring reply instead of a joke or a "did you mean". A real command still runs:
        // "tension hai, kal 5 baje yaad dila dena" sets the reminder.
        val decision = policy.decide(understood)
        val lowByModel = understood.mood?.let { it.isLow && it.confidence >= LOW_MOOD_BAR } == true
        if (lowByWords || lowByModel) {
            val action = (decision as? DecisionPolicy.Decision.Act)?.guess?.action
            if (action == null || action == A.CHITCHAT || action == A.OTHER) {
                return Routed.Run(QuickCommand.Chitchat(text, Replies.LOW_MOOD), if (lowByWords) Source.RULES else Source.MODEL, understood)
            }
        }

        return when (val d = decision) {
            is DecisionPolicy.Decision.Act -> {
                val cmd = toCommand(d.guess.action, understood, text, d.guess.bestIntent)
                if (cmd != null) Routed.Run(cmd, Source.MODEL, understood, d.guess.action) else askWhen(text, understood)
            }

            DecisionPolicy.Decision.Ask -> didYouMean(text, understood)
        }
    }

    // Null for a reminder whose time we couldn't read.

    /**
     * Always asks: the choices for [input] without [exclude] — used after "Not what I meant", where
     * [exclude] is what Pebble wrongly did. Saving as a note is always offered (unless excluded).
     */
    fun ask(input: String, exclude: String? = null): Routed.Ask {
        val text = input.trim()
        val u = model()?.understand(text)
        val guesses = if (u != null) didYouMean(text, u, limit = 4).options else emptyList()
        val options = guesses.filter { it.action != exclude }.toMutableList()
        if (exclude != A.ADD_NOTE && options.none { it.action == A.ADD_NOTE }) {
            options += Option(labelFor(A.ADD_NOTE), A.ADD_NOTE, QuickCommand.AddNote(text))
        }
        return Routed.Ask("What did you mean?", options.take(3), u)
    }

    /** Model action → concrete command. [intent] is the finer label behind it (alarm_set, general_joke…). */
    private fun toCommand(action: String, u: Understood, text: String, intent: String): QuickCommand? = when (action) {
        A.REMIND -> reminder(u, text, intent)

        A.ADD_NOTE -> QuickCommand.AddNote(text)

        A.REMINDERS_QUERY -> QuickCommand.ShowUpcoming

        // "what did I note about the project": a named topic is a search; "show my notes" shows the latest.
        A.NOTES_QUERY -> MemorySearch.topicOf(text)?.let { QuickCommand.SearchMemory(it, text) } ?: QuickCommand.ShowNotes

        A.TIME_QUERY -> QuickCommand.TellTime

        A.CHITCHAT -> QuickCommand.Chitchat(text, intent, u.mood?.takeIf { it.confidence >= 0.7f }?.mood)

        A.REMINDER_REMOVE -> QuickCommand.OpenPage("reminders", text, forRemoval = true)

        A.NOTE_REMOVE -> QuickCommand.OpenPage("notes", text, forRemoval = true)

        else -> QuickCommand.Unsupported(text, intent)
    }

    private fun reminder(u: Understood, text: String, intent: String): QuickCommand? {
        val slots = u.slots()
        val slotText = listOfNotNull(slots["date"], slots["timeofday"], slots["time"]).joinToString(" ")
        // The model sometimes tags only "shaam"/"kal" as the time. If the slot has no clock number,
        // read the whole sentence, which still contains "7 baje".
        val timeText = if (slotText.isNotBlank() && HinglishTime.hasClock(slotText)) {
            slotText
        } else if (HinglishTime.hasClock(text)) {
            text
        } else {
            slotText.ifBlank { text }
        }
        val title = reminderTitle(u, text, intent)
        val weekday = today()
        return when (val w = HinglishTime.parse(timeText, weekday)) {
            // The day may sit outside the time slot ("friday wali meeting 5 baje"), so look in the whole sentence too.
            is HinglishTime.When.At -> QuickCommand.RemindAt(
                title,
                w.hour,
                w.minute,
                w.dayOffset ?: HinglishTime.dayOf(text, weekday),
                w.flexibleHalfDay,
            )

            is HinglishTime.When.In -> QuickCommand.RemindIn(title, w.minutes)

            null -> null
        }
    }

    /**
     * The reminder text: every word except time/date words and reminder filler, so slot words like
     * the person ("mummy") stay in. Never the whole sentence: if nothing is left, a plain label.
     */
    private fun reminderTitle(u: Understood, text: String, intent: String = u.top.intent): String {
        val timeTags = setOf("time", "date", "timeofday")
        val kept = u.words.zip(u.tags).filter { (w, t) ->
            val kind = t.removePrefix("B-").removePrefix("I-")
            (t == "O" || kind !in timeTags) &&
                w.lowercase().trim(',', '.', '?', '!') !in filler &&
                HinglishTime.numberOf(w.lowercase()) == null
        }.map { it.first }
        val title = kept.joinToString(" ").trim()
        return title.ifBlank { if (intent == "alarm_set") "Wake up" else "Reminder" }.replaceFirstChar(Char::uppercase)
    }

    private fun askWhen(text: String, u: Understood): Routed.Ask {
        val title = reminderTitle(u, text)
        val weekday = today()
        val day = HinglishTime.dayOf(text, weekday)
        if (day != null && day > 0) {
            // We know the day but not the time: offer times on that day, plus a heads-up the evening before.
            val name = dayName(day, weekday)
            return Routed.Ask(
                "What time $name: “$title”?",
                listOf(
                    Option("$name 9 AM", A.REMIND, QuickCommand.RemindAt(title, 9, 0, day)),
                    Option("$name 1 PM", A.REMIND, QuickCommand.RemindAt(title, 13, 0, day)),
                    Option("$name 6 PM", A.REMIND, QuickCommand.RemindAt(title, 18, 0, day)),
                    Option("${dayName(day - 1, weekday)} 8 PM", A.REMIND, QuickCommand.RemindAt(title, 20, 0, day - 1)),
                ),
                u,
            )
        }
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

    private fun didYouMean(text: String, u: Understood, limit: Int = 3): Routed.Ask {
        // Ranked by summed action probability; skip near-zero actions so options stay meaningful.
        val options = u.actions.filter { it.action != A.OTHER && it.confidence >= 0.05f }.mapNotNull { g ->
            val cmd = toCommand(g.action, u, text, g.bestIntent) ?: return@mapNotNull null
            Option(labelFor(g.action), g.action, cmd)
        }.toMutableList()
        if (options.none { it.action == A.ADD_NOTE }) options += Option(labelFor(A.ADD_NOTE), A.ADD_NOTE, QuickCommand.AddNote(text))
        return Routed.Ask("Did you mean…", options.take(limit), u)
    }

    companion object {
        /** Calibrated mood-head probability needed to answer with care instead of acting. */
        const val LOW_MOOD_BAR = 0.7f

        private val weekdayNames = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

        /** "Today", "Tomorrow", or the weekday name [offset] days from [today]. */
        fun dayName(offset: Int, today: Int?): String = when {
            offset == 0 -> "Today"
            offset == 1 -> "Tomorrow"
            today == null -> "In $offset days"
            else -> weekdayNames[(today - 1 + offset) % 7]
        }

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
            "around", "approximately", "by", "pls", "plz", "dont", "lagbhag", "लगभग",
            "mujhe", "yaad", "dila", "dilana", "dilaana", "dena", "dila", "do", "karo", "kar", "ki", "ka", "ke", "ko",
            "reminder", "laga", "lagao", "set", "utha",
            "मुझे", "याद", "दिला", "दिलाना", "देना", "दो", "करो", "की", "का", "के", "को", "रिमाइंडर", "लगा", "लगाओ", "जगा", "उठा",
            // Time words the model may leave untagged — they belong to the time, never the title.
            "baje", "bje", "o'clock", "oclock", "am", "pm", "subah", "shaam", "sham", "raat", "dopahar", "kal", "aaj", "parso",
            "today", "tomorrow", "tonight", "morning", "evening", "night", "afternoon", "min", "mins", "minute", "minutes", "baad", "in",
            "बजे", "सुबह", "शाम", "रात", "दोपहर", "कल", "आज", "परसों", "मिनट", "बाद", "घंटे",
        )
    }
}
