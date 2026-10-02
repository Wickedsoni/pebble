// Regex patterns read best on one line each.
@file:Suppress("ktlint:standard:max-line-length")

package dev.pebble.core.quickadd

import dev.pebble.core.brain.HinglishTime
import dev.pebble.core.reminders.ReminderKind
import dev.pebble.core.reminders.Strictness

/** What a line typed into the quick-add bar means. Times are left unresolved so parsing stays pure. */
sealed interface QuickCommand {
    data class AddNote(val text: String) : QuickCommand
    data class RememberFact(val text: String) : QuickCommand
    data class LogWater(val glasses: Int) : QuickCommand
    data class SetInterval(val kind: ReminderKind, val minutes: Int, val strictness: Strictness?) : QuickCommand
    data class RemindIn(val title: String, val minutes: Int) : QuickCommand

    /**
     * [dayOffset] null means "today, or tomorrow if that time has already passed".
     * [flexibleHalfDay]: the user gave no am/pm cue ("5 baje"), so use the next of [hour] or [hour]+12.
     */
    data class RemindAt(
        val title: String,
        val hour: Int,
        val minute: Int,
        val dayOffset: Int?,
        val flexibleHalfDay: Boolean = false,
    ) : QuickCommand

    // Understood by the command model (M1); no rule syntax needed.
    data object ShowUpcoming : QuickCommand
    data object ShowNotes : QuickCommand
    data object TellTime : QuickCommand

    /** Small talk; [intent] is the model's finer label (greet / joke / quirky). */
    data class Chitchat(val text: String, val intent: String) : QuickCommand

    /** Something Pebble understands but can't do yet (music, weather, lights…). */
    data class Unsupported(val text: String, val intent: String) : QuickCommand

    /** Removing reminders/notes happens in the app for now. */
    data class OpenPage(val page: String) : QuickCommand
}

/**
 * Offline, rule-based parser for the quick-add bar. Examples:
 * `note: buy milk`, `+2 water`, `water every 45m strict`, `remind me to call mom at 7pm`,
 * `stretch in 20 min`, `submit DBMS assignment tomorrow 5:30pm`, `remember my exam is on 20 Oct`.
 * Anything else becomes a note.
 */
object QuickAddParser {
    private val rememberRx = Regex(
        """^(?:remember(?:\s+that)?|yaad\s+rakh(?:na|o|iyo|ana)?(?:\s+ki)?|याद\s+रख(?:ना|ो|िए|ियो|ें)?(?:\s+कि)?)\s*[:\-]?\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )

    /** "har 45 minute", "हर पैंतालीस मिनट", "har ghante" — Hindi for "every". */
    private val harRx =
        Regex(
            """(?:^|\s)(?:har|हर)\s+(?:(\S+)\s+)?(min|mins|minute|minutes|minat|मिनट|ghanta|ghante|घंटा|घंटे|hour|hours)(?=\s|$|[.,!?])""",
            RegexOption.IGNORE_CASE,
        )
    private val hiWaterRx = Regex("""(?:paani|pani|पानी)\s+(?:pi|pee|piya|पी|पिया)(?=\s|$|[.,!?])""", RegexOption.IGNORE_CASE)
    private val enWaterRx =
        Regex(
            """\b(?:drank|had|finished)\s+(?:(a|an|one|two|three|four|\d+)\s+)?(?:glass(?:es)?|cups?|bottles?)\s+of\s+water\b""",
            RegexOption.IGNORE_CASE,
        )
    private val noteRx = Regex("""^(?:note|n)\s*[:\-]?\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val everyRx = Regex("""\bevery\s+(\d+(?:\.\d+)?)?\s*(m|min|mins|minutes?|h|hr|hrs|hours?)\b""", RegexOption.IGNORE_CASE)
    private val waterLogRx =
        Regex(
            """^(?:\+|drank|had|log)?\s*(\d+)?\s*(?:x\s*)?(?:glass(?:es)?(?: of water)?|water|💧)\s*(?:\+\s*(\d+))?$""",
            RegexOption.IGNORE_CASE,
        )
    private val inRx =
        Regex("""^(?:remind me\s+(?:to\s+)?)?(.+?)\s+in\s+(\d+)\s*(m|min|mins|minutes?|h|hr|hrs|hours?)$""", RegexOption.IGNORE_CASE)
    private const val DAY = """(today|tomorrow|tmrw|(?:next\s+)?(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*day)"""
    private val atRx = Regex(
        """^(?:remind me\s+(?:to\s+)?|set\s+a\s+reminder\s+(?:for|to)\s+(?:my\s+)?)?(.+?)\s+(?:$DAY\s+)?(?:at|by|@)?\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm)\s*$DAY?$|""" +
            """^(?:remind me\s+(?:to\s+)?|set\s+a\s+reminder\s+(?:for|to)\s+(?:my\s+)?)?(.+?)\s+(?:$DAY\s+)?(?:at|by|@)\s*(\d{1,2})(?::(\d{2}))?\s*$DAY?$""",
        RegexOption.IGNORE_CASE,
    )

    /** Like [parseStrict], but anything unrecognised becomes a note (the pre-model behaviour). */
    fun parse(input: String, today: Int? = null): QuickCommand? {
        val text = input.trim()
        if (text.isEmpty()) return null
        return parseStrict(text, today) ?: QuickCommand.AddNote(text)
    }

    /**
     * Only explicit rule matches; null when no rule applies (so the command model can try).
     * [today] (ISO weekday, 1 = Monday) lets "call mom friday at 5pm" pick a date.
     */
    fun parseStrict(input: String, today: Int? = null): QuickCommand? {
        val text = input.trim()
        if (text.isEmpty()) return null

        rememberRx.find(text)?.let { return QuickCommand.RememberFact(it.groupValues[1].trim().trimEnd('.')) }
        noteRx.find(text)?.let { return QuickCommand.AddNote(it.groupValues[1].trim()) }

        everyRx.find(text)?.let { m ->
            val kind = kindOf(text)
            if (kind != null) {
                val amount = m.groupValues[1].ifEmpty { "1" }.toDouble()
                val minutes = if (m.groupValues[2].startsWith("h", ignoreCase = true)) amount * 60 else amount
                return QuickCommand.SetInterval(kind, minutes.toInt().coerceIn(1, 24 * 60), strictnessOf(text))
            }
        }

        harRx.find(text)?.let { m ->
            val kind = kindOf(text)
            val n = m.groupValues[1].let { if (it.isEmpty()) 1 else HinglishTime.numberOf(it.lowercase()) }
            if (kind != null && n != null) {
                val perHour = m.groupValues[2].lowercase().let { it.startsWith("gh") || it.startsWith("घं") || it.startsWith("hour") }
                return QuickCommand.SetInterval(kind, (if (perHour) n * 60 else n).coerceIn(1, 24 * 60), strictnessOf(text))
            }
        }

        if (hiWaterRx.containsMatchIn(text)) return QuickCommand.LogWater(glassesIn(text))
        enWaterRx.find(text)?.let { m ->
            val n = m.groupValues[1].lowercase().let { if (it.isEmpty() || it == "a" || it == "an") 1 else HinglishTime.numberOf(it) ?: 1 }
            return QuickCommand.LogWater(n.coerceIn(1, 20))
        }

        waterLogRx.find(text)?.let { m ->
            val n = (m.groupValues[1].ifEmpty { m.groupValues[2] }).ifEmpty { "1" }.toInt()
            return QuickCommand.LogWater(n.coerceIn(1, 20))
        }

        inRx.find(text)?.let { m ->
            val n = m.groupValues[2].toInt()
            val minutes = if (m.groupValues[3].startsWith("h", ignoreCase = true)) n * 60 else n
            return QuickCommand.RemindIn(cleanTitle(m.groupValues[1]), minutes)
        }

        atRx.find(text)?.let { m ->
            val g = m.groupValues
            // First alternative (with am/pm) uses groups 1-6, second (24h with "at") uses 7-11.
            val withMeridiem = g[1].isNotEmpty()
            val title = if (withMeridiem) g[1] else g[7]
            val dayWord = if (withMeridiem) g[2].ifEmpty { g[6] } else g[8].ifEmpty { g[11] }
            var hour = (if (withMeridiem) g[3] else g[9]).toInt()
            val minute = (if (withMeridiem) g[4] else g[10]).ifEmpty { "0" }.toInt()
            if (withMeridiem) {
                val pm = g[5].equals("pm", ignoreCase = true)
                hour = (hour % 12) + if (pm) 12 else 0
            }
            // No am/pm ("at 5"): take whichever of 5:00 / 17:00 comes next.
            val flexible = !withMeridiem && hour in 1..11
            if (hour in 0..23 && minute in 0..59) {
                val offset = if (dayWord.isEmpty()) null else HinglishTime.dayOf(dayWord, today)
                return QuickCommand.RemindAt(cleanTitle(title), hour, minute, offset, flexible)
            }
        }

        return null
    }

    private fun kindOf(text: String): ReminderKind? {
        val t = text.lowercase()
        return when {
            listOf("water", "drink", "hydrat", "sip", "paani", "pani", "पानी").any { it in t } -> ReminderKind.WATER
            listOf("stretch", "stand", "walk", "स्ट्रेच", "टहल", "tahal").any { it in t } -> ReminderKind.STRETCH
            listOf("eye", "20-20-20", "screen break", "aankh", "ankh", "आंख", "आँख").any { it in t } -> ReminderKind.EYES
            else -> null
        }
    }

    private fun strictnessOf(text: String): Strictness? {
        val t = text.lowercase()
        return when {
            "strict" in t || "coach" in t -> Strictness.STRICT
            "gentle" in t || "soft" in t -> Strictness.GENTLE
            "normal" in t -> Strictness.NORMAL
            else -> null
        }
    }

    /** "ek glass", "do glass", "2 गिलास" → 2; defaults to 1. */
    private fun glassesIn(text: String): Int {
        val words = text.lowercase().split(Regex("""\s+"""))
        val i = words.indexOfFirst { it.startsWith("glass") || it.startsWith("गिलास") || it.startsWith("bottle") }
        return (if (i > 0) HinglishTime.numberOf(words[i - 1]) else null)?.coerceIn(1, 20) ?: 1
    }

    private fun cleanTitle(raw: String) = raw.trim().trimEnd(',', '.').replaceFirstChar { it.uppercase() }
}
