package dev.pebble.core.brain

/**
 * Reads a time out of English, Roman Hindi (Hinglish) or Devanagari text — usually the `time`
 * and `date` slots the command model found, e.g. "शाम सात बजे", "kal 5 baje", "7 pm tomorrow",
 * "saade paanch baje", "20 min baad". Pure and offline.
 */
object HinglishTime {
    sealed interface When {
        /** [hour] 0–23. [flexibleHalfDay]: no am/pm cue, so "5" may mean 5:00 or 17:00 — pick the next one. */
        data class At(val hour: Int, val minute: Int, val dayOffset: Int?, val flexibleHalfDay: Boolean) : When
        data class In(val minutes: Int) : When
    }

    /**
     * [today] is today's ISO weekday (1 = Monday … 7 = Sunday). Without it, weekday words
     * ("friday", "shukravar", "शुक्रवार") can't be turned into a date and are ignored.
     */
    fun parse(text: String, today: Int? = null): When? {
        val t = normalise(text)
        val words = t.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        relative(words)?.let { return it }

        val day = dayOffset(words, today)
        val part = partOfDay(words)
        val (hour12, minute) = clock(words) ?: return part?.let { When.At(it.defaultHour, 0, day, false) }
        var hour = hour12
        var flexible = false
        when {
            hour > 12 -> Unit // already 24h ("17:30")
            part != null -> hour = part.to24(hour)
            else -> flexible = hour in 1..11
        }
        if (hour == 24) hour = 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return When.At(hour, minute, day, flexible)
    }

    // ---------------------------------------------------------------- pieces

    private enum class Part(val defaultHour: Int) {
        MORNING(9), NOON(13), EVENING(18), NIGHT(21);

        fun to24(h: Int): Int = when (this) {
            MORNING -> if (h == 12) 0 else h
            NOON -> if (h in 1..4) h + 12 else h
            EVENING -> if (h < 12) h + 12 else h
            NIGHT -> if (h == 12) 0 else if (h in 5..11) h + 12 else h
        }
    }

    private val partWords = mapOf(
        Part.MORNING to setOf("am", "morning", "subah", "subha", "savere", "सुबह", "सवेरे"),
        Part.NOON to setOf("noon", "afternoon", "dopahar", "dopehar", "दोपहर"),
        Part.EVENING to setOf("pm", "evening", "shaam", "sham", "शाम"),
        Part.NIGHT to setOf("night", "tonight", "raat", "rat", "रात"),
    )

    private fun partOfDay(w: List<String>) = partWords.entries.firstOrNull { (_, set) -> w.any { it in set } }?.key

    /** Days from today to the day named in [text] ("kal", "friday", "अगले सोमवार"), or null if none. */
    fun dayOf(text: String, today: Int? = null): Int? = dayOffset(normalise(text).split(' ').filter { it.isNotEmpty() }, today)

    private val weekdayWords: Map<String, Int> = buildMap {
        listOf(
            listOf("monday", "mon", "somvar", "somwar", "somvaar", "सोमवार"),
            listOf("tuesday", "tue", "tues", "mangalvar", "mangalwar", "mangal", "मंगलवार", "मंगल"),
            listOf("wednesday", "wed", "budhvar", "budhwar", "budh", "बुधवार", "बुध"),
            listOf("thursday", "thu", "thurs", "guruvar", "guruwar", "brihaspativar", "veervar", "गुरुवार", "बृहस्पतिवार", "वीरवार"),
            listOf("friday", "fri", "shukravar", "shukrawar", "shukra", "sukravar", "शुक्रवार"),
            listOf("saturday", "shanivar", "shaniwar", "shani", "शनिवार"),
            listOf("sunday", "ravivar", "raviwar", "itvar", "itwar", "रविवार", "इतवार"),
        ).forEachIndexed { i, names -> names.forEach { put(it, i + 1) } }
    }

    /** "next friday", "agle shukravar", "अगले शुक्रवार": skip today if today is that day. */
    private val nextWords = setOf("next", "agle", "agla", "agli", "अगले", "अगला", "अगली")

    private fun weekdayOffset(w: List<String>, today: Int?): Int? {
        if (today == null) return null
        // No "sat" / "sun": in Hinglish those are "seven" and "listen".
        val i = w.indexOfFirst { it in weekdayWords }
        if (i < 0) return null
        val diff = (weekdayWords.getValue(w[i]) - today + 7) % 7
        return if (diff == 0 && w.getOrNull(i - 1) in nextWords) 7 else diff
    }

    private fun dayOffset(w: List<String>, today: Int? = null): Int? = weekdayOffset(w, today) ?: when {
        w.any { it in setOf("parso", "parson", "परसों") } -> 2
        w.containsAll(listOf("day", "after", "tomorrow")) -> 2
        w.any { it in setOf("tomorrow", "kal", "tmrw", "कल") } -> 1
        w.any { it in setOf("today", "aaj", "aj", "tonight", "आज") } -> 0
        else -> null
    }

    private val units = mapOf(
        "min" to 1, "mins" to 1, "minute" to 1, "minutes" to 1, "minat" to 1, "मिनट" to 1,
        "hour" to 60, "hours" to 60, "hr" to 60, "hrs" to 60, "ghanta" to 60, "ghante" to 60, "घंटा" to 60, "घंटे" to 60,
    )

    /** Fractions of a unit: "aadhe ghante", "dedh ghanta", "ढाई घंटे", "half an hour". */
    private val fractions = mapOf(
        "half" to 0.5, "aadha" to 0.5, "aadhe" to 0.5, "adha" to 0.5, "adhe" to 0.5, "आधा" to 0.5, "आधे" to 0.5,
        "dedh" to 1.5, "डेढ़" to 1.5, "डेढ" to 1.5, "dhai" to 2.5, "ढाई" to 2.5,
    )

    /** "in 20 minutes", "20 min baad", "बीस मिनट बाद", "ek ghante mein", "aadhe ghante baad", "thodi der mein". */
    private fun relative(w: List<String>): When? {
        val marker = w.any { it in setOf("in", "after", "baad", "bad", "mein", "me", "बाद", "में") }
        val der = w.indexOfFirst { it == "der" || it == "देर" }
        if (der > 0 && w[der - 1] in setOf("thodi", "thori", "थोड़ी", "थोडी") && marker) return When.In(15)
        val i = w.indexOfFirst { it in units }
        if (i <= 0) return null
        val prev = w[i - 1]
        val n: Double = when {
            prev in fractions -> fractions.getValue(prev)
            prev in setOf("a", "an") && w.getOrNull(i - 2) == "half" -> 0.5
            else -> (number(prev) ?: if (prev in setOf("a", "an", "one")) 1 else return null).toDouble()
        }
        return if (marker || i == w.lastIndex) When.In((n * units.getValue(w[i])).toInt()) else null
    }

    /** Hour and minute from "5", "5:30", "17.00", "saade paanch", "पौने सात", "dedh", "half past five". */
    private fun clock(w: List<String>): Pair<Int, Int>? {
        w.firstNotNullOfOrNull { Regex("""^(\d{1,2})[:.](\d{2})$""").find(it) }?.let {
            return it.groupValues[1].toInt() to it.groupValues[2].toInt()
        }
        w.firstOrNull { it in setOf("dedh", "डेढ़", "डेढ") }?.let { return 1 to 30 }
        w.firstOrNull { it in setOf("dhai", "ढाई") }?.let { return 2 to 30 }
        if (w.any { it in setOf("noon", "midday") }) return 12 to 0
        if (w.any { it == "midnight" }) return 0 to 0
        for ((i, word) in w.withIndex()) {
            val h = hourNumber(word) ?: continue
            if (w.getOrNull(i + 1) in dateWords) continue
            val prev = w.getOrNull(i - 1)
            return when (prev) {
                "saade", "sade", "साढ़े", "साढे" -> h to 30
                "sava", "savaa", "sawa", "सवा" -> h to 15
                "paune", "pone", "पौने" -> (if (h == 1) 12 else h - 1) to 45
                "past" -> h to (number(w.getOrNull(i - 2) ?: "") ?: if (w.getOrNull(i - 2) == "half") 30 else 0).coerceAtMost(59)
                else -> h to 0
            }
        }
        return null
    }

    private val dateWords = setOf("tareekh", "tarikh", "tareek", "तारीख", "date", "th", "st", "nd", "rd")

    /** A clock hour (1–24): digits or 1–12 in words, ignoring durations already handled. */
    private fun hourNumber(word: String): Int? {
        val n = number(word) ?: return null
        return n.takeIf { it in 1..24 }
    }

    private val numberWords: Map<String, Int> = buildMap {
        listOf(
            listOf("one", "ek", "एक"), listOf("two", "do", "दो"), listOf("three", "teen", "tin", "तीन"),
            listOf("four", "char", "chaar", "चार"), listOf("five", "panch", "paanch", "पांच", "पाँच"),
            listOf("six", "chhe", "che", "chah", "chhah", "छह", "छः", "छे"), listOf("seven", "saat", "sat", "सात"),
            listOf("eight", "aath", "ath", "आठ"), listOf("nine", "nau", "नौ"), listOf("ten", "das", "dus", "दस"),
            listOf("eleven", "gyarah", "gyara", "ग्यारह"), listOf("twelve", "barah", "bara", "बारह"),
        ).forEachIndexed { i, names -> names.forEach { put(it, i + 1) } }
        mapOf(
            15 to listOf("fifteen", "pandrah", "pandra", "पंद्रह"), 20 to listOf("twenty", "bees", "bis", "बीस"),
            25 to listOf("pachees", "pachis", "पच्चीस"), 30 to listOf("thirty", "tees", "tis", "तीस"),
            40 to listOf("forty", "chalees", "chalis", "चालीस"), 45 to listOf("paintalis", "paintalees", "पैंतालीस"),
            50 to listOf("fifty", "pachaas", "pachas", "पचास"),
        ).forEach { (n, names) -> names.forEach { put(it, n) } }
    }

    private fun number(word: String): Int? = word.toIntOrNull() ?: numberWords[word]

    /** A number written as digits or a common English / Hindi / Devanagari word ("bees", "पैंतालीस"). */
    fun numberOf(word: String): Int? = number(normalise(word))

    /** True when [text] names a clock time ("7", "7:30", "saade paanch"), not just "shaam" / "kal". */
    fun hasClock(text: String): Boolean = clock(normalise(text).split(' ').filter { it.isNotEmpty() }) != null

    private val devanagariDigits = "०१२३४५६७८९"

    private fun normalise(s: String): String {
        val sb = StringBuilder()
        for (c in s.lowercase()) {
            val d = devanagariDigits.indexOf(c)
            sb.append(if (d >= 0) ('0' + d) else c)
        }
        // "7pm" → "7 pm", "5baje" → "5 baje"; drop punctuation except time separators.
        return sb.toString()
            .replace(Regex("""(\d)(am|pm|a\.m\.|p\.m\.)"""), "$1 $2")
            .replace("a.m.", "am").replace("p.m.", "pm")
            .replace(Regex("""[,!?;"'()]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
