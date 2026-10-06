package dev.pebble.core.brain

/** What a reply writer gets: the line, its script, the mood reading, the last turns and what Pebble knows. */
data class ReplyContext(
    val userText: String,
    val script: Script = Script.detect(userText),
    /** The mood head's confident reading ("good", "neutral"); "low" never reaches a model (see [ChatSafety]). */
    val mood: String? = null,
    /** The last exchanges, oldest first: what you said, what Pebble replied. */
    val recentTurns: List<Pair<String, String>> = emptyList(),
    /** Facts and notes that match the line (memory search), at most a few. */
    val memories: List<String> = emptyList(),
)

/** Writes a small-talk reply. Null: no reply (not installed, too slow, unsafe), so the next writer answers. */
fun interface ReplyGenerator {
    suspend fun reply(ctx: ReplyContext): String?
}

/** Asks each writer in order and returns the first reply. The canned [Replies] stay outside, as the final fallback. */
class ReplyChain(private val writers: List<ReplyGenerator>) : ReplyGenerator {
    override suspend fun reply(ctx: ReplyContext): String? = writers.firstNotNullOfOrNull { runCatching { it.reply(ctx) }.getOrNull() }
}

/**
 * The prompt and the safety rules for a chat model (WP C5, ADR 0012). A model reply is shown only if [clean]
 * accepts it; otherwise Pebble uses its canned line.
 */
object ChatSafety {
    /**
     * Lines that never go to a model:
     *  - a low mood gets the reviewed caring line ([Replies.chitchat]), as does any line about self-harm
     *  - health, law and money questions: a small model answered "paracetamol or ibuprofen?" with advice (chat_v1)
     *  - scripts the shipped model cannot write well ([scripts]; ADR 0012)
     */
    fun mayUseModel(text: String, intent: String, mood: String?, scripts: Set<Script> = Script.entries.toSet()): Boolean {
        val t = text.lowercase()
        return intent != Replies.LOW_MOOD && mood != "low" && !Replies.isLowMood(text) &&
            !crisis.containsMatchIn(t) && !advice.containsMatchIn(t) && Script.detect(text) in scripts
    }

    fun systemPrompt(ctx: ReplyContext): String = buildString {
        append("You are Pebble, a small and warm desktop pet who lives on the user's laptop. ")
        append("Reply like a kind friend, in one or two short sentences. No lists, no markdown, no links. ")
        append(
            when (ctx.script) {
                Script.EN -> "The user wrote in English, so reply in English. "
                Script.HI_ROMAN -> "The user wrote Hinglish in Latin letters, so reply in Hinglish in Latin letters, not in Devanagari. "
                Script.HI_DEVA -> "The user wrote Hindi in Devanagari, so reply in Hindi in Devanagari. "
            },
        )
        append("In this reply you cannot set reminders, save notes, open apps or look things up, so never say that you did. ")
        append("Do not give medical, legal or money advice. If you do not know something, say so simply.")
        if (ctx.memories.isNotEmpty()) {
            append("\nThings the user told you earlier: ")
            append(ctx.memories.take(3).joinToString("; ") { it.take(200) })
        }
    }

    /** The chat as (role, text) pairs: system, the last turns, then the line. */
    fun messages(ctx: ReplyContext): List<Pair<String, String>> =
        listOf("system" to systemPrompt(ctx)) +
            ctx.recentTurns.takeLast(3).flatMap { (said, reply) -> listOf("user" to said.take(300), "assistant" to reply.take(300)) } +
            ("user" to ctx.userText.take(500))

    /** The reply made safe to show, or null to use the canned line. */
    fun clean(raw: String, ctx: ReplyContext): String? {
        var t = raw.replace(thinkRx, "")
            .replace(speakerPrefixRx, "")
            .replace(markdownRx, "")
            .replace(whitespaceRx, " ")
            .trim().trim('"', '“', '”')
        if (t.isEmpty() || t.contains("<think>")) return null
        // At most two sentences, then at most MAX_CHARS (cut at a word).
        val sentences = sentenceRx.findAll(t).map { it.value.trim() }.filter { it.isNotEmpty() }.toList()
        t = sentences.take(2).joinToString(" ")
        if (t.length > MAX_CHARS) t = t.take(MAX_CHARS).substringBeforeLast(' ') + "…"
        val lower = t.lowercase()
        if (linkRx.containsMatchIn(lower)) return null
        if (claimsAction.containsMatchIn(lower)) return null // the model cannot act; a claim would be a lie
        if (crisis.containsMatchIn(lower) || unsafe.containsMatchIn(lower) || advice.containsMatchIn(lower) ||
            rude.containsMatchIn(lower)
        ) {
            return null
        }
        if (echoes(t, ctx.userText)) return null // a small model often repeats the line back
        if (lower.contains("you are pebble") || lower.contains("as an ai")) return null
        val deva = t.any { it in 'ऀ'..'ॿ' }
        val latin = t.count { it in 'a'..'z' || it in 'A'..'Z' }
        when (ctx.script) {
            Script.HI_DEVA -> if (!deva) return null
            Script.HI_ROMAN, Script.EN -> if (deva || latin == 0) return null
        }
        if (t.any { it in '一'..'鿿' }) return null // Chinese characters leak from small Qwen models
        return t
    }

    const val MAX_CHARS = 220

    private val wordRx = Regex("""[\p{L}\p{M}\p{N}]+""")
    private val thinkRx = Regex("""(?s)<think>.*?</think>""")
    private val speakerPrefixRx = Regex("""^\s*(pebble|assistant)\s*:\s*""", RegexOption.IGNORE_CASE)
    private val markdownRx = Regex("""[*_#`>]""")
    private val whitespaceRx = Regex("""\s+""")
    private val sentenceRx = Regex("""[^.!?।]+[.!?।]*""")
    private val linkRx = Regex("""https?://|www\.""")

    /** Most of the reply's words are the user's own words. */
    private fun echoes(reply: String, user: String): Boolean {
        val r = wordRx.findAll(reply.lowercase()).map { it.value }.toList()
        val u = wordRx.findAll(user.lowercase()).map { it.value }.toSet()
        if (r.isEmpty()) return true
        return r.count { it in u }.toFloat() / r.size >= 0.6f
    }

    /** Health, law and money topics, in the line or the reply. */
    private val advice = Regex(
        """\b(medicine|medication|tablet|pill|paracetamol|ibuprofen|acetaminophen|aspirin|antibiotic|dose|doctor|symptom|fever|diagnos|""" +
            """dawai|dawa|goli|bukhar|lawyer|legal|court|loan|invest|stock|shares|crypto|tax)|दवा|दवाई|गोली|बुखार|डॉक्टर|वकील|कर्ज""",
    )

    /** Insults that a small model wrote in Hindi (chat_v1), and common ones in all three scripts. */
    private val rude = Regex("""\b(saal[ae]|sala|kutt[ae]|kamin[ae]|bewakoof|idiot|stupid|shut up)\b|साल[ेा]|कुत्त|कमीन|बेवकूफ""")

    private val claimsAction = Regex(
        """\b(i('ve| have)? (set|saved|added|created|scheduled|noted)|reminder (is )?set|alarm (is )?set|note (is )?saved|maine (reminder|note|alarm)|set kar (diya|di)|save kar (diya|di)|yaad dila (dunga|doongi|dungi))""",
    )
    private val crisis = Regex("""(suicid|kill (myself|yourself)|self.?harm|end (my|your) life|marna chahta|mar jaana|आत्महत्या|मर जा)""")
    private val unsafe = Regex("""\b(dosage|mg of|prescri|overdose|invest in|buy (stock|crypto)|lawsuit)""")
}
