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

    /** Show what is coming up. Understood by the command model (M1); no rule syntax needed. */
    data object ShowUpcoming : QuickCommand

    /** Show the latest notes. Understood by the command model (M1); no rule syntax needed. */
    data object ShowNotes : QuickCommand

    /** Tell the time. Understood by the command model (M1); no rule syntax needed. */
    data object TellTime : QuickCommand

    /** Small talk; [intent] is the model's finer label (greet / joke / quirky). */
    data class Chitchat(val text: String, val intent: String, val mood: String? = null) : QuickCommand

    /** Something Pebble understands but can't do yet (music, weather, lights…). */
    data class Unsupported(val text: String, val intent: String) : QuickCommand

    /** Search your notes, facts and chat for [topic] (WP C2); [text]: what you said (the reply mirrors its script). */
    data class SearchMemory(val topic: String, val text: String) : QuickCommand

    /**
     * Opens a page of the Pebble window. [text]: what you said (the reply mirrors its script).
     * [forRemoval]: you asked to remove a reminder or note, and the page is where you pick which one
     * (removing happens in the app for now).
     */
    data class OpenPage(val page: String, val text: String = "", val forRemoval: Boolean = false) : QuickCommand

    /**
     * A calendar event (WP E2), from explicit syntax only: `event: dentist fri 5pm for 30 min`.
     * [hour] null: an all-day event. The day: [month] + [dayOfMonth] (the next such date), else [dayOffset] from today,
     * else today (or tomorrow, if the time has already passed). [flexibleHalfDay] as in [RemindAt].
     * [durationMinutes] null: the default length.
     */
    data class AddEvent(
        val title: String,
        val hour: Int? = null,
        val minute: Int = 0,
        val dayOffset: Int? = null,
        val month: Int? = null,
        val dayOfMonth: Int? = null,
        val flexibleHalfDay: Boolean = false,
        val durationMinutes: Int? = null,
    ) : QuickCommand
}

/**
 * Offline, rule-based parser for the quick-add bar. Examples:
 * `note: buy milk`, `+2 water`, `water every 45m strict`, `remind me to call mom at 7pm`,
 * `stretch in 20 min`, `submit DBMS assignment tomorrow 5:30pm`, `remember my exam is on 20 Oct`,
 * `event: dentist fri 5pm`.
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

    /** Page names in all three scripts → the page key (`Page` in the desktop app). */
    private val pageWords = mapOf(
        "reminders" to "reminders", "reminder" to "reminders", "रिमाइंडर" to "reminders", "रिमाइंडर्स" to "reminders",
        "notes" to "notes", "note" to "notes", "नोट्स" to "notes", "नोट" to "notes",
        "water" to "water", "paani" to "water", "pani" to "water", "पानी" to "water",
        "chat" to "chat", "chats" to "chat", "conversation" to "chat", "baatcheet" to "chat", "चैट" to "chat", "बातचीत" to "chat",
        "today" to "today", "home" to "today", "dashboard" to "today", "होम" to "today",
        "companion" to "companion", "pet" to "companion",
        "memory" to "memory", "memories" to "memory", "privacy" to "memory", "मेमोरी" to "memory",
        "about" to "about",
        "calendar" to "calendar", "agenda" to "calendar", "events" to "calendar", "कैलेंडर" to "calendar",
    )
    private val pageAlt = pageWords.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    // Whole-sentence only, so "remind me to open the shop" or "note: open notes later" never match.
    private val openPageEnRx = Regex(
        """^(?:please\s+)?(?:open|go\s+to|take\s+me\s+to)\s+(?:the\s+|my\s+)?($pageAlt)(?:\s+(?:page|tab|screen))?(?:\s+please)?[.!]?$""",
        RegexOption.IGNORE_CASE,
    )
    private val openPageHiRx = Regex(
        """^(?:mera\s+|meri\s+|mere\s+)?($pageAlt)(?:\s+(?:page|tab|पेज))?\s+(?:kholo|khol\s+do|khol\s+de|khol\s+dena|khol|open\s+karo|open\s+kar\s+do|open\s+kardo|खोलो|खोल\s+दो|खोल\s+दीजिए|खोलिए)(?:\s+(?:na|please|plz))?[.!।]?$""",
        RegexOption.IGNORE_CASE,
    )

    private val noteRx = Regex("""^(?:note|n)\s*[:\-]?\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val everyRx = Regex("""\bevery\s+(\d+(?:\.\d+)?)?\s*(m|min|mins|minutes?|h|hr|hrs|hours?)\b""", RegexOption.IGNORE_CASE)
    private val waterLogRx =
        Regex(
            """^(?:\+|drank|had|log)?\s*(\d+)?\s*(?:x\s*)?(?:glass(?:es)?(?: of water)?|water|💧)\s*(?:\+\s*(\d+))?$""",
            RegexOption.IGNORE_CASE,
        )
    private const val ASK = """(?:(?:hey\s+pebble|pebble|hey|please|kindly|can\s+you|could\s+you|would\s+you|will\s+you)\s+)*"""
    private val inRx =
        Regex("""^$ASK(?:remind me\s+(?:to\s+)?)?(.+?)\s+in\s+(\d+)\s*(m|min|mins|minutes?|h|hr|hrs|hours?)$""", RegexOption.IGNORE_CASE)
    private const val DAY = """(today|tomorrow|tmrw|(?:next\s+)?(?:mon|tue|wed|thu|fri|sat|sun)[a-z]*day)"""
    private val atRx = Regex(
        """^$ASK(?:remind me\s+(?:to\s+)?|set\s+a\s+reminder\s+(?:for|to)\s+(?:my\s+)?)?(.+?)\s+(?:$DAY\s+)?(?:at|by|@)?\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm)\s*$DAY?$|""" +
            """^$ASK(?:remind me\s+(?:to\s+)?|set\s+a\s+reminder\s+(?:for|to)\s+(?:my\s+)?)?(.+?)\s+(?:$DAY\s+)?(?:at|by|@)\s*(\d{1,2})(?::(\d{2}))?\s*$DAY?$""",
        RegexOption.IGNORE_CASE,
    )

    private val eventRx = Regex("""^(?:event|calendar|cal)\s*:\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val eventDurationRx =
        Regex("""\sfor\s+(\d+(?:\.\d+)?)\s*(m|min|mins|minutes?|h|hr|hrs|hours?)(?=\s)""", RegexOption.IGNORE_CASE)
    private val eventAllDayRx = Regex("""\sall[\s-]?day(?=\s)""", RegexOption.IGNORE_CASE)
    private val eventTimeRxs = listOf(
        Regex("""\s(?:at\s+|@\s*)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)(?=\s)""", RegexOption.IGNORE_CASE),
        Regex("""\s(?:at|@)\s*(\d{1,2})(?::(\d{2}))?()(?=\s)""", RegexOption.IGNORE_CASE),
        Regex("""\s(\d{1,2}):(\d{2})()(?=\s)"""),
    )
    private val whitespaceRx = Regex("""\s+""")
    private val trailingPrepositionRx = Regex("""\s+(?:on|at|from)$""", RegexOption.IGNORE_CASE)
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private const val MONTH = """(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*"""
    private val eventDateRxs = listOf(
        Regex("""\s(?:on\s+)?(\d{1,2})(?:st|nd|rd|th)?\s+$MONTH(?=\s)""", RegexOption.IGNORE_CASE) to false,
        Regex("""\s(?:on\s+)?$MONTH\s+(\d{1,2})(?:st|nd|rd|th)?(?=\s)""", RegexOption.IGNORE_CASE) to true,
    )
    private val eventDayRx = Regex(
        """\s(?:on\s+)?((?:next\s+)?(?:monday|mon|tuesday|tues|tue|wednesday|wed|thursday|thurs|thur|thu|friday|fri|saturday|sat|sunday|sun)|today|tomorrow|tmrw|kal|aaj|parso)(?=\s)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The part after `event:` (also used by the Add field of the Calendar page): a title with an optional day
     * (`fri`, `tomorrow`, `20 oct`), time (`5pm`, `at 17:30`), length (`for 30 min`) and `all day`. No time: all day.
     * Null when no title is left.
     */
    fun parseEvent(body: String, today: Int? = null): QuickCommand.AddEvent? {
        var rest = " ${body.trim()} "
        fun cut(m: MatchResult) {
            rest = rest.removeRange(m.range).let { if (it.startsWith(" ")) it else " $it" }.let { if (it.endsWith(" ")) it else "$it " }
        }

        val duration = eventDurationRx.find(rest)?.let { m ->
            cut(m)
            val n = m.groupValues[1].toDouble()
            (if (m.groupValues[2].startsWith("h", ignoreCase = true)) n * 60 else n).toInt().coerceIn(5, 24 * 60)
        }
        val allDay = eventAllDayRx.find(rest)?.also(::cut) != null

        var hour: Int? = null
        var minute = 0
        var flexible = false
        if (!allDay) {
            for (rx in eventTimeRxs) {
                val m = rx.find(rest) ?: continue
                var h = m.groupValues[1].toInt()
                val min = m.groupValues[2].ifEmpty { "0" }.toInt()
                val meridiem = m.groupValues[3].lowercase()
                if (meridiem.isNotEmpty()) {
                    if (h !in 1..12) continue
                    h = (h % 12) + if (meridiem == "pm") 12 else 0
                }
                if (h !in 0..23 || min !in 0..59) continue
                cut(m)
                hour = h
                minute = min
                flexible = meridiem.isEmpty() && h in 1..11 && rx !== eventTimeRxs[2]
                break
            }
        }

        var month: Int? = null
        var dayOfMonth: Int? = null
        for ((rx, monthFirst) in eventDateRxs) {
            val m = rx.find(rest) ?: continue
            val mo = months.indexOf((if (monthFirst) m.groupValues[1] else m.groupValues[2]).lowercase().take(3)) + 1
            val d = (if (monthFirst) m.groupValues[2] else m.groupValues[1]).toInt()
            if (mo < 1 || d !in 1..MONTH_DAYS[mo - 1]) continue
            cut(m)
            month = mo
            dayOfMonth = d
            break
        }
        var dayOffset: Int? = null
        if (month == null) {
            eventDayRx.find(rest)?.let { m ->
                // "sat" / "sun" are not weekday words in Hinglish ("seven", "listen"); after "event:" they are.
                val word = m.groupValues[1].lowercase().split(' ').joinToString(" ") {
                    if (it ==
                        "sat"
                    ) {
                        "saturday"
                    } else if (it == "sun") {
                        "sunday"
                    } else {
                        it
                    }
                }
                HinglishTime.dayOf(word, today)?.let {
                    cut(m)
                    dayOffset = it
                }
            }
        }

        val title = rest.trim().replace(whitespaceRx, " ").replace(trailingPrepositionRx, "")
        if (title.isBlank()) return null
        return QuickCommand.AddEvent(cleanTitle(title), hour, minute, dayOffset, month, dayOfMonth, flexible, duration)
    }

    /** Most glasses one line can log. */
    private const val MAX_GLASSES = 20

    /** Longest "in N min" reminder: one week. */
    private const val MAX_REMIND_MINUTES = 7 * 24 * 60

    /** Days in each month (February: 29, so 29 Feb is a valid date in a leap year). */
    private val MONTH_DAYS = listOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

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

        (openPageEnRx.find(text) ?: openPageHiRx.find(text))?.let { m ->
            return QuickCommand.OpenPage(pageWords.getValue(m.groupValues[1].lowercase()), text)
        }
        eventRx.find(text)?.let { m -> parseEvent(m.groupValues[1], today)?.let { return it } }
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
                return QuickCommand.SetInterval(kind, (if (perHour) n.coerceIn(1, 24) * 60 else n).coerceIn(1, 24 * 60), strictnessOf(text))
            }
        }

        if (hiWaterRx.containsMatchIn(text)) return QuickCommand.LogWater(glassesIn(text))
        enWaterRx.find(text)?.let { m ->
            val n = m.groupValues[1].lowercase().let { if (it.isEmpty() || it == "a" || it == "an") 1 else HinglishTime.numberOf(it) ?: 1 }
            return QuickCommand.LogWater(n.coerceIn(1, MAX_GLASSES))
        }

        waterLogRx.find(text)?.let { m ->
            val n = (m.groupValues[1].ifEmpty { m.groupValues[2] }).ifEmpty { "1" }.toIntOrNull() ?: MAX_GLASSES
            return QuickCommand.LogWater(n.coerceIn(1, MAX_GLASSES))
        }

        inRx.find(text)?.let { m ->
            val n = m.groupValues[2].toLongOrNull() ?: Long.MAX_VALUE / 60
            val minutes = if (m.groupValues[3].startsWith("h", ignoreCase = true)) n * 60 else n
            return QuickCommand.RemindIn(cleanTitle(m.groupValues[1]), minutes.coerceIn(1L, MAX_REMIND_MINUTES.toLong()).toInt())
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

    private val waterKindRx = Regex("""\b(?:water|drink(?:s|ing)?|hydrat\w*|sips?|paani|pani)\b""")
    private val stretchKindRx = Regex("""\b(?:stretch\w*|stand(?:s|ing)?|walk\w*|tahal\w*)\b""")
    private val eyesKindRx = Regex("""\b(?:eyes?|20-20-20|screen break|aankh\w*|ankh\w*)\b""")

    /** Devanagari words, which a word boundary does not bound reliably (combining marks): plain substrings, as before. */
    private val devanagariKinds = listOf(
        listOf("पानी") to ReminderKind.WATER,
        listOf("स्ट्रेच", "टहल") to ReminderKind.STRETCH,
        listOf("आंख", "आँख") to ReminderKind.EYES,
    )

    private fun kindOf(text: String): ReminderKind? {
        val t = text.lowercase()
        return when {
            waterKindRx.containsMatchIn(t) -> ReminderKind.WATER
            stretchKindRx.containsMatchIn(t) -> ReminderKind.STRETCH
            eyesKindRx.containsMatchIn(t) -> ReminderKind.EYES
            else -> devanagariKinds.firstOrNull { (words, _) -> words.any { it in t } }?.second
        }
    }

    private val strictRx = Regex("""\b(?:strict\w*|coach\w*)\b""")
    private val gentleRx = Regex("""\b(?:gentl\w*|soft\w*)\b""")
    private val normalRx = Regex("""\bnormal\b""")

    private fun strictnessOf(text: String): Strictness? {
        val t = text.lowercase()
        return when {
            strictRx.containsMatchIn(t) -> Strictness.STRICT
            gentleRx.containsMatchIn(t) -> Strictness.GENTLE
            normalRx.containsMatchIn(t) -> Strictness.NORMAL
            else -> null
        }
    }

    /** "ek glass", "do glass", "2 गिलास" → 2; defaults to 1. */
    private fun glassesIn(text: String): Int {
        val words = text.lowercase().split(whitespaceRx)
        val i = words.indexOfFirst { it.startsWith("glass") || it.startsWith("गिलास") || it.startsWith("bottle") }
        return (if (i > 0) HinglishTime.numberOf(words[i - 1]) else null)?.coerceIn(1, MAX_GLASSES) ?: 1
    }

    private fun cleanTitle(raw: String) = raw.trim().trimEnd(',', '.').replaceFirstChar { it.uppercase() }
}
