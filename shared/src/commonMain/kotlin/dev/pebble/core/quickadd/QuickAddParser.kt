package dev.pebble.core.quickadd

import dev.pebble.core.reminders.ReminderKind
import dev.pebble.core.reminders.Strictness

/** What a line typed into the quick-add bar means. Times are left unresolved so parsing stays pure. */
sealed interface QuickCommand {
    data class AddNote(val text: String) : QuickCommand
    data class RememberFact(val text: String) : QuickCommand
    data class LogWater(val glasses: Int) : QuickCommand
    data class SetInterval(val kind: ReminderKind, val minutes: Int, val strictness: Strictness?) : QuickCommand
    data class RemindIn(val title: String, val minutes: Int) : QuickCommand

    /** [dayOffset] null means "today, or tomorrow if that time has already passed". */
    data class RemindAt(val title: String, val hour: Int, val minute: Int, val dayOffset: Int?) : QuickCommand
}

/**
 * Offline, rule-based parser for the quick-add bar. Examples:
 * `note: buy milk`, `+2 water`, `water every 45m strict`, `remind me to call mom at 7pm`,
 * `stretch in 20 min`, `submit DBMS assignment tomorrow 5:30pm`, `remember my exam is on 20 Oct`.
 * Anything else becomes a note.
 */
object QuickAddParser {
    private val rememberRx = Regex("""^remember(?:\s+that)?\s*[:\-]?\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val noteRx = Regex("""^(?:note|n)\s*[:\-]?\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val everyRx = Regex("""\bevery\s+(\d+(?:\.\d+)?)?\s*(m|min|mins|minutes?|h|hr|hrs|hours?)\b""", RegexOption.IGNORE_CASE)
    private val waterLogRx = Regex("""^(?:\+|drank|had|log)?\s*(\d+)?\s*(?:x\s*)?(?:glass(?:es)?(?: of water)?|water|💧)\s*(?:\+\s*(\d+))?$""", RegexOption.IGNORE_CASE)
    private val inRx = Regex("""^(?:remind me\s+(?:to\s+)?)?(.+?)\s+in\s+(\d+)\s*(m|min|mins|minutes?|h|hr|hrs|hours?)$""", RegexOption.IGNORE_CASE)
    private val atRx = Regex(
        """^(?:remind me\s+(?:to\s+)?)?(.+?)\s+(?:(today|tomorrow|tmrw)\s+)?(?:at|by|@)?\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm)\s*(today|tomorrow|tmrw)?$|""" +
            """^(?:remind me\s+(?:to\s+)?)?(.+?)\s+(?:(today|tomorrow|tmrw)\s+)?(?:at|by|@)\s*(\d{1,2})(?::(\d{2}))?\s*(today|tomorrow|tmrw)?$""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(input: String): QuickCommand? {
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
            if (hour in 0..23 && minute in 0..59) {
                val offset = when (dayWord.lowercase()) {
                    "today" -> 0
                    "tomorrow", "tmrw" -> 1
                    else -> null
                }
                return QuickCommand.RemindAt(cleanTitle(title), hour, minute, offset)
            }
        }

        return QuickCommand.AddNote(text)
    }

    private fun kindOf(text: String): ReminderKind? {
        val t = text.lowercase()
        return when {
            listOf("water", "drink", "hydrat", "sip").any { it in t } -> ReminderKind.WATER
            "stretch" in t || "stand" in t || "walk" in t -> ReminderKind.STRETCH
            "eye" in t || "20-20-20" in t || "screen break" in t -> ReminderKind.EYES
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

    private fun cleanTitle(raw: String) = raw.trim().trimEnd(',', '.').replaceFirstChar { it.uppercase() }
}
