package dev.pebble.core.reminders

import dev.pebble.core.brain.Script

/**
 * What the pet says for a repeating reminder: a short headline and a one-line tip, rotating so the same
 * words don't come back every time, in the language you talk to Pebble in. One-off reminders use their title.
 */
object ReminderCopy {
    data class Line(val headline: String, val tip: String)

    private val en = mapOf(
        ReminderKind.EYES to listOf(
            Line("Eyes need a break 👀", "Look at something far away for 20 seconds."),
            Line("Blink break!", "Look out of the window for a moment."),
            Line("Rest your eyes", "Find the farthest thing you can see and focus on it."),
            Line("Screen pause 👀", "Close your eyes for a few seconds."),
        ),
        ReminderKind.WATER to listOf(
            Line("Water time 💧", "A few sips is enough."),
            Line("Thirsty?", "Your glass is waiting."),
            Line("Hydration check 💧", "One glass, then back to it."),
            Line("Sip break", "Water now, focus later."),
        ),
        ReminderKind.STRETCH to listOf(
            Line("Stretch break 🙆", "Stand up and roll your shoulders."),
            Line("Time to move", "A short walk or a stretch."),
            Line("Unstiffen!", "Stand up, reach up, breathe."),
            Line("Quick stretch 🙆", "Your back will thank you."),
        ),
    )
    private val hiRoman = mapOf(
        ReminderKind.EYES to listOf(
            Line("Aankhon ko break do 👀", "20 second door kisi cheez ko dekho."),
            Line("Thoda blink karo!", "Khidki ke bahar dekh lo ek pal."),
            Line("Screen se break 👀", "Kuch second aankhein band karo."),
        ),
        ReminderKind.WATER to listOf(
            Line("Paani ka time 💧", "Do-teen ghoont kaafi hain."),
            Line("Pyaas lagi?", "Glass wait kar raha hai."),
            Line("Paani piya? 💧", "Ek glass, phir wapas kaam pe."),
        ),
        ReminderKind.STRETCH to listOf(
            Line("Stretch break 🙆", "Utho, kandhe ghumao."),
            Line("Thoda chal lo", "Chhota sa walk ya stretch."),
            Line("Akad gaye? 🙆", "Khade ho jao, haath upar, lambi saans."),
        ),
    )
    private val hiDeva = mapOf(
        ReminderKind.EYES to listOf(
            Line("आँखों को आराम दो 👀", "20 सेकंड दूर किसी चीज़ को देखो।"),
            Line("थोड़ा पलक झपकाओ!", "एक पल खिड़की के बाहर देख लो।"),
        ),
        ReminderKind.WATER to listOf(
            Line("पानी का समय 💧", "दो-तीन घूँट काफ़ी हैं।"),
            Line("पानी पिया? 💧", "एक गिलास, फिर काम पर।"),
        ),
        ReminderKind.STRETCH to listOf(
            Line("स्ट्रेच ब्रेक 🙆", "उठो, कंधे घुमाओ।"),
            Line("थोड़ा चल लो", "छोटी सी सैर या स्ट्रेच।"),
        ),
    )

    /** The [n]th line for [kind] (rotates), or null for kinds without built-in copy (one-offs). */
    fun line(kind: ReminderKind, n: Int, script: Script = Script.EN): Line? {
        val byScript = when (script) {
            Script.EN -> en
            Script.HI_ROMAN -> hiRoman
            Script.HI_DEVA -> hiDeva
        }
        val pool = byScript[kind] ?: en[kind] ?: return null
        return pool[((n % pool.size) + pool.size) % pool.size]
    }
}
