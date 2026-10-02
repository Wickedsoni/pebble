package dev.pebble.core.brain

import kotlin.random.Random

/** Which script a line was written in, so Pebble can mirror it (decided in the brain plan). */
enum class Script { EN, HI_DEVA, HI_ROMAN;

    companion object {
        private val hinglishMarkers = setOf(
            "hai", "hain", "ho", "hu", "hoon", "kya", "kaise", "kaisa", "kaun", "tum", "tu", "aap", "mera", "meri", "mujhe",
            "karo", "kar", "na", "nahi", "nahin", "bahut", "yaar", "acha", "accha", "theek", "thik", "aaj", "kal", "abhi", "baje",
            "sunao", "batao", "chalo", "mein", "ka", "ki", "ke", "ko", "se", "koi", "kuch",
        )

        fun detect(text: String): Script = when {
            text.any { it in 'ऀ'..'ॿ' } -> HI_DEVA
            text.lowercase().split(Regex("""[^a-z]+""")).count { it in hinglishMarkers } >= 1 -> HI_ROMAN
            else -> EN
        }
    }
}

/**
 * Rule-based small talk until the local chat model (M5) lands: a few lines per mood and script.
 * Pebble answers in the script you used.
 */
object Replies {
    const val LOW_MOOD = "mood_low"

    private val lowMoodWords = listOf(
        "sad", "feeling low", "feel low", "feeling down", "a bit low", "upset", "lonely", "stressed", "tired of", "depressed", "anxious", "not okay", "not ok", "bad day",
        "mood off", "mood kharab", "mood thoda off", "udaas", "udas", "dukhi", "pareshan", "akela", "tension",
        "mann nahi lag", "man nahi lag", "mann nhi lag", "man nhi lag", "dil nahi lag", "dil nhi lag", "kuch acha nahi lag",
        "thak gaya hu", "thak gayi hu", "thak gaya hoon", "thak gayi hoon", "so tired", "exhausted", "burnt out", "overwhelmed",
        "उदास", "दुखी", "परेशान", "अकेला", "मूड ठीक नहीं", "मूड खराब", "टेंशन",
        "मन नहीं", "मन नही", "दिल नहीं लग", "थक गया हूं", "थक गई हूं", "थक गया हूँ", "थक गई हूँ",
    )

    /** Words that signal a low mood, in all three scripts. */
    fun isLowMood(text: String): Boolean {
        val t = " " + text.lowercase().replace(Regex("""[^\p{L}\p{M}\s]"""), " ") + " "
        return lowMoodWords.any { w -> if (w.contains(' ') || w.any { it in 'ऀ'..'ॿ' }) w in t else " $w " in t }
    }

    private val caring = mapOf(
        Script.EN to listOf(
            "I'm sorry today feels heavy. I'm right here — want to take a short break with me?",
            "That sounds tough. Be gentle with yourself today. A glass of water and a few deep breaths?",
        ),
        Script.HI_ROMAN to listOf(
            "Sorry yaar, aaj ka din bhaari lag raha hai. Main yahin hoon — thoda break lein saath mein?",
            "Koi baat nahi, aisa hota hai. Thoda paani pi lo aur do minute aaram karo.",
        ),
        Script.HI_DEVA to listOf(
            "अरे, आज का दिन भारी लग रहा है। मैं यहीं हूँ — थोड़ा ब्रेक लें साथ में?",
            "कोई बात नहीं, ऐसा होता है। थोड़ा पानी पी लो और दो मिनट आराम करो।",
        ),
    )
    private val greet = mapOf(
        Script.EN to listOf("Hey! I'm here.", "Hi. Good to see you.", "Hello! What's up?"),
        Script.HI_ROMAN to listOf("Main badhiya! Tum batao?", "Hey! Main yahin hoon.", "Sab badhiya. Tumhara din kaisa ja raha hai?"),
        Script.HI_DEVA to listOf("मैं बढ़िया! तुम बताओ?", "नमस्ते! मैं यहीं हूँ।", "सब बढ़िया। तुम्हारा दिन कैसा जा रहा है?"),
    )
    private val kind = mapOf(
        Script.EN to listOf("Aw, thanks. You're not bad yourself.", "That made my day."),
        Script.HI_ROMAN to listOf("Aww, thank you! Tum bhi kam nahi ho.", "Isse mera din ban gaya."),
        Script.HI_DEVA to listOf("अरे, धन्यवाद! तुम भी कम नहीं हो।", "इससे मेरा दिन बन गया।"),
    )
    private val jokes = mapOf(
        Script.EN to listOf(
            "Why did the developer go broke? He used up all his cache.",
            "I'd tell you a UDP joke, but you might not get it.",
            "Debugging: being the detective in a crime movie where you're also the murderer.",
        ),
        Script.HI_ROMAN to listOf(
            "Programmer ki diary: aaj bug fix kiya… kal teen naye aa gaye.",
            "Exam se pehle padhai = loading… 99%… 99%… 99%…",
            "Paani pi lo yaar, warna main bhi thirsty ho jaunga.",
        ),
        Script.HI_DEVA to listOf(
            "प्रोग्रामर की डायरी: आज एक बग ठीक किया… कल तीन नए आ गए।",
            "परीक्षा से पहले पढ़ाई = लोडिंग… 99%… 99%… 99%…",
            "पानी पी लो यार, वरना मुझे भी प्यास लग जाएगी।",
        ),
    )
    private val cant = mapOf(
        Script.EN to "I can't do that yet — want it saved as a note?",
        Script.HI_ROMAN to "Yeh abhi mujhse nahi hoga — note mein save kar doon?",
        Script.HI_DEVA to "यह अभी मुझसे नहीं होगा — नोट में सेव कर दूँ?",
    )

    fun chitchat(text: String, intent: String, random: Random = Random): String {
        val s = Script.detect(text)
        if (intent == LOW_MOOD || isLowMood(text)) return caring.getValue(Script.detect(text)).random(random)
        val pool = when (intent) {
            "general_joke" -> jokes
            "general_greet" -> greet
            else -> if (text.lowercase().split(' ').any { it in setOf("how", "kaise", "kaisa", "कैसे", "कैसा") }) greet else kind
        }
        return pool.getValue(s).random(random)
    }

    fun unsupported(text: String): String = cant.getValue(Script.detect(text))
}
