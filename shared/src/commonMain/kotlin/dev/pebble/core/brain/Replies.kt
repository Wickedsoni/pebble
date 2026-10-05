package dev.pebble.core.brain

import kotlin.random.Random

/** Which script a line was written in, so Pebble can mirror it (decided in the brain plan). */
enum class Script {
    EN,
    HI_DEVA,
    HI_ROMAN,
    ;

    companion object {
        private val hinglishMarkers = setOf(
            "hai", "hain", "ho", "hu", "hoon", "kya", "kaise", "kaisa", "kaun", "tum", "tu", "aap", "mera", "meri", "mujhe",
            "karo", "kar", "na", "nahi", "nahin", "bahut", "yaar", "acha", "accha", "theek", "thik", "aaj", "kal", "abhi", "baje",
            "sunao", "batao", "chalo", "mein", "ka", "ki", "ke", "ko", "se", "koi", "kuch", "kholo", "khol", "paani", "pani",
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
        "sad", "feeling low", "feel low", "feeling down", "a bit low", "upset", "lonely", "stressed", "tired of",
        "depressed", "anxious", "not okay", "not ok", "bad day",
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

    private fun pools(en: List<String>, roman: List<String>, deva: List<String>) = mapOf(
        Script.EN to en,
        Script.HI_ROMAN to roman,
        Script.HI_DEVA to deva,
    )

    private val caring = pools(
        listOf(
            "I'm sorry today feels heavy. I'm right here — want to take a short break with me?",
            "That sounds tough. Be gentle with yourself today. A glass of water and a few deep breaths?",
            "Rough day? You don't have to fix it all now. One small thing at a time.",
            "I'm here. Want me to keep reminders quiet for a bit?",
        ),
        listOf(
            "Sorry yaar, aaj ka din bhaari lag raha hai. Main yahin hoon — thoda break lein saath mein?",
            "Koi baat nahi, aisa hota hai. Thoda paani pi lo aur do minute aaram karo.",
            "Sab ek saath theek karna zaroori nahi. Ek chhoti cheez se shuru karte hain.",
            "Main yahin hoon. Thodi der reminders band rakhun?",
        ),
        listOf(
            "अरे, आज का दिन भारी लग रहा है। मैं यहीं हूँ — थोड़ा ब्रेक लें साथ में?",
            "कोई बात नहीं, ऐसा होता है। थोड़ा पानी पी लो और दो मिनट आराम करो।",
            "सब एक साथ ठीक करना ज़रूरी नहीं। एक छोटी चीज़ से शुरू करते हैं।",
        ),
    )
    private val happy = pools(
        listOf(
            "Love that! 😊 Keep it going.",
            "That's great to hear!",
            "Yay! Good days deserve a little celebration.",
            "Nice — I'm doing a happy hop over here.",
            "So good! What made it great?",
        ),
        listOf(
            "Wah! Sunke bahut accha laga 😊 Aisa hi chalta rahe.",
            "Kya baat hai! Aaj toh full mood mein ho.",
            "Badhiya! Kis cheez ne din bana diya?",
            "Mast! Main bhi khush ho gaya sunke.",
            "Yay! Aise din pe ek chhota sa celebration banta hai.",
        ),
        listOf(
            "वाह! सुनकर बहुत अच्छा लगा 😊 ऐसे ही चलता रहे।",
            "क्या बात है! आज तो पूरे मूड में हो।",
            "बढ़िया! किस चीज़ ने दिन बना दिया?",
            "मस्त! सुनकर मैं भी खुश हो गया।",
        ),
    )
    private val aboutMe = pools(
        listOf(
            "My plan? Hang out on your taskbar and make sure you drink water. 😄",
            "Just wandering around here. What are you up to?",
            "I'm a small pet with a big job: keeping your day on track.",
            "Today I'm learning how you talk — so I understand you better tomorrow.",
            "I'm good! A bit sleepy, but here for you.",
        ),
        listOf(
            "Mera plan? Tumhare taskbar pe ghoomna aur yaad dilana ki paani piyo 😄",
            "Bas yahin ghoom raha hoon. Tum kya kar rahe ho?",
            "Main chhota sa pet hoon, kaam bada hai: tumhara din sambhalna.",
            "Aaj main seekh raha hoon tum kaise baat karte ho — taaki kal aur achhe se samjhun.",
            "Main badhiya! Thoda neend mein hoon, par tumhare liye haazir.",
        ),
        listOf(
            "मेरा प्लान? तुम्हारे टास्कबार पर घूमना और याद दिलाना कि पानी पियो 😄",
            "बस यहीं घूम रहा हूँ। तुम क्या कर रहे हो?",
            "मैं छोटा सा पेट हूँ, काम बड़ा है: तुम्हारा दिन संभालना।",
            "आज मैं सीख रहा हूँ तुम कैसे बात करते हो — ताकि कल और अच्छे से समझूँ।",
        ),
    )
    private val greet = pools(
        listOf(
            "Hey! I'm here.",
            "Hi! Good to see you.",
            "Hello! What's up?",
            "Hey hey! How's your day going?",
            "Hi there 👋 Need anything?",
        ),
        listOf(
            "Main badhiya! Tum batao?",
            "Hey! Main yahin hoon.",
            "Sab badhiya. Tumhara din kaisa ja raha hai?",
            "Hello hello! Kya chal raha hai?",
            "Arre, aa gaye! Kuch yaad dilana hai?",
        ),
        listOf(
            "मैं बढ़िया! तुम बताओ?",
            "नमस्ते! मैं यहीं हूँ।",
            "सब बढ़िया। तुम्हारा दिन कैसा जा रहा है?",
            "अरे, आ गए! कुछ याद दिलाना है?",
        ),
    )
    private val kind = pools(
        listOf(
            "Aw, thanks. You're not bad yourself.",
            "That made my day.",
            "Stop it, I'm blushing 🙈",
            "You're sweet. Thank you!",
            "Right back at you!",
        ),
        listOf(
            "Aww, thank you! Tum bhi kam nahi ho.",
            "Isse mera din ban gaya.",
            "Arre, sharma gaya main 🙈",
            "Tum bhi bahut achhe ho!",
            "Shukriya dost!",
        ),
        listOf("अरे, धन्यवाद! तुम भी कम नहीं हो।", "इससे मेरा दिन बन गया।", "अरे, शरमा गया मैं 🙈", "तुम भी बहुत अच्छे हो!"),
    )
    private val chat = pools(
        listOf(
            "Hmm, tell me more!",
            "Interesting! And then?",
            "I'm listening. 🙂",
            "Got it. Want me to remember that? Say \"remember …\".",
            "Ha, okay! Anything I can remind you about?",
        ),
        listOf(
            "Hmm, aur batao!",
            "Accha? Phir kya hua?",
            "Sun raha hoon 🙂",
            "Samajh gaya. Yaad rakhun? Bolo \"yaad rakhna …\".",
            "Haha theek hai! Kuch yaad dilana hai?",
        ),
        listOf("हम्म, और बताओ!", "अच्छा? फिर क्या हुआ?", "सुन रहा हूँ 🙂", "समझ गया। याद रखूँ? बोलो \"याद रखना …\"।"),
    )
    private val jokes = pools(
        listOf(
            "Why did the developer go broke? He used up all his cache.",
            "I'd tell you a UDP joke, but you might not get it.",
            "Debugging: being the detective in a crime movie where you're also the murderer.",
            "There are 10 kinds of people: those who get binary and those who don't.",
        ),
        listOf(
            "Programmer ki diary: aaj bug fix kiya… kal teen naye aa gaye.",
            "Exam se pehle padhai = loading… 99%… 99%… 99%…",
            "Paani pi lo yaar, warna main bhi thirsty ho jaunga.",
            "Assignment ki deadline aur main: dono kal pe bharosa karte hain 😄",
        ),
        listOf(
            "प्रोग्रामर की डायरी: आज एक बग ठीक किया… कल तीन नए आ गए।",
            "परीक्षा से पहले पढ़ाई = लोडिंग… 99%… 99%… 99%…",
            "पानी पी लो यार, वरना मुझे भी प्यास लग जाएगी।",
        ),
    )

    /** Page names as the pet says them, per script. */
    private val pageNames = mapOf(
        "reminders" to Triple("Reminders", "Reminders", "रिमाइंडर"),
        "notes" to Triple("Notes", "Notes", "नोट्स"),
        "water" to Triple("Water", "Paani", "पानी"),
        "chat" to Triple("Chat", "Chat", "चैट"),
        "today" to Triple("Today", "Today", "आज"),
        "companion" to Triple("Companion", "Companion", "साथी"),
        "memory" to Triple("Memory", "Memory", "मेमोरी"),
        "about" to Triple("About", "About", "अबाउट"),
    )

    /**
     * What the pet says when it opens a page. [forRemoval]: you asked to remove a reminder or note; Pebble
     * doesn't guess which one, so it shows the list and says so.
     */
    fun openPage(page: String, text: String, forRemoval: Boolean): String {
        val (en, roman, deva) = pageNames[page] ?: Triple(page.replaceFirstChar(Char::uppercase), page, page)
        val what = if (page == "notes") "note" else "reminder"
        return when (Script.detect(text)) {
            Script.EN -> if (forRemoval) "Here are your ${en.lowercase()} — remove the $what you want." else "Opening $en."

            Script.HI_ROMAN -> if (forRemoval) {
                "Yeh rahe aapke ${roman.lowercase()} — jo hatana hai, wahan se hata do."
            } else {
                "$roman khol raha hoon."
            }

            Script.HI_DEVA -> if (forRemoval) "ये रहे आपके $deva — जो हटाना है, वहाँ से हटा दीजिए।" else "$deva खोल रहा हूँ।"
        }
    }

    /** What the pet says after a memory search: up to three things it found, or that it found nothing. */
    fun memoryHits(text: String, hits: List<String>): String {
        val found = hits.take(3).joinToString(" · ") { "“$it”" }
        return when (Script.detect(text)) {
            Script.EN -> if (hits.isEmpty()) NOTHING_EN else "Here's what I found: $found"
            Script.HI_ROMAN -> if (hits.isEmpty()) NOTHING_ROMAN else "Yeh mila: $found"
            Script.HI_DEVA -> if (hits.isEmpty()) NOTHING_DEVA else "यह मिला: $found"
        }
    }

    private const val NOTHING_EN = "I couldn't find anything about that in your notes or what you told me."
    private const val NOTHING_ROMAN = "Iske baare mein kuch nahi mila — na notes mein, na aapki baaton mein."
    private const val NOTHING_DEVA = "इसके बारे में कुछ नहीं मिला — न नोट्स में, न आपकी बातों में।"

    private val cant = mapOf(
        Script.EN to "I can't do that yet — want it saved as a note?",
        Script.HI_ROMAN to "Yeh abhi mujhse nahi hoga — note mein save kar doon?",
        Script.HI_DEVA to "यह अभी मुझसे नहीं होगा — नोट में सेव कर दूँ?",
    )

    private val youWords = setOf(
        "you", "your", "u", "tum", "tumhara", "tumhare", "tumhari", "tu", "tera", "teri", "aap", "aapka",
        "तुम", "तुम्हारा", "तुम्हारे", "तू", "आप", "आपका",
    )

    private val askWords = setOf("what", "how", "kya", "kaise", "kaisa", "kaun", "kab", "kyu", "kyun", "क्या", "कैसे", "कैसा", "कौन")
    private val warmWords = setOf(
        "cute", "love", "best", "awesome", "sweet", "thank", "thanks", "thankyou", "shukriya", "dhanyavaad", "pyare", "pyara",
        "achhe", "acche", "mast", "क्यूट", "प्यारे", "धन्यवाद", "शुक्रिया", "अच्छे",
    )
    private val goodWords =
        setOf("great", "amazing", "happy", "khush", "maza", "badhiya", "accha", "acha", "achha", "बढ़िया", "खुश", "मज़ा", "अच्छा")

    /** What was said last from each pool, so the same line never comes twice in a row. */
    private val lastSaid = mutableMapOf<List<String>, String>()

    private fun pick(pool: List<String>, random: Random): String {
        val fresh = pool.filter { it != lastSaid[pool] }.ifEmpty { pool }
        return fresh.random(random).also { lastSaid[pool] = it }
    }

    /**
     * A reply to small talk, in the script you used. [mood] is the mood head's confident reading
     * ("low" / "good"), if any. Questions to Pebble, compliments, good news, greetings and jokes each get
     * their own lines; anything else gets an engaged, open reply — never the same line twice in a row.
     */
    fun chitchat(text: String, intent: String, mood: String? = null, random: Random = Random): String {
        val s = Script.detect(text)
        val words = text.lowercase().split(Regex("""[^\p{L}\p{M}]+""")).filter { it.isNotEmpty() }.toSet()
        val pool = when {
            intent == LOW_MOOD || mood == "low" || isLowMood(text) -> caring
            intent == "general_joke" -> jokes
            words.any { it in warmWords } -> kind
            words.any { it in youWords } && (words.any { it in askWords } || text.contains('?')) -> aboutMe
            mood == "good" || words.any { it in goodWords } -> happy
            intent == "general_greet" -> greet
            else -> chat
        }
        return pick(pool.getValue(s), random)
    }

    fun unsupported(text: String): String = cant.getValue(Script.detect(text))
}
