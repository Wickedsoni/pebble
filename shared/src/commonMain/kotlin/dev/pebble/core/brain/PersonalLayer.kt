package dev.pebble.core.brain

import kotlin.concurrent.Volatile
import kotlin.math.sqrt

/**
 * Tier 2 of the brain: what you taught Pebble, what you picked from "Did you mean…" and what you marked
 * "Not what I meant" change the next reading of the same or a very close sentence at once, with no
 * retraining. The formula and why each number has its value: docs/adr/0010-personal-layer.md.
 *
 * For a sentence, the layer collects signed votes per action:
 *  - the same sentence (after [normalize]) counts [EXACT_WEIGHT] per label
 *  - up to [NEIGHBOURS] other sentences count `(cos − τ) / (1 − τ)` each, if cos ≥ [TAU] and they share
 *    words ([MIN_SHARED_WORDS]); the shared-words gate stops one example from spreading over a whole intent
 *  - taught and picked count +1, wrong counts −1; "confirmed" rows never count (they are the model's own guess)
 *
 * Then, with P(a) the positive and N(a) the negative votes, n = ΣP and w = n / (n + [K]):
 *   p'(a) = (1 − w) · p(a) · K / (N(a) + K) + w · P(a) / n
 * A wrong vote takes probability away and does not give it to other actions, so p' can sum to less than 1:
 * the layer is less sure, and [DecisionPolicy] asks. Its thresholds do not change. The layer never lifts a
 * removal above [REMOVAL_CAP], so only the model can make Pebble remove something without asking.
 */
class PersonalLayer(
    /** Taught, picked and wrong rows, oldest first ([CommandFeedbackRepository.personalSince]). */
    private val source: () -> List<CommandFeedback>,
    /** The sentence embedding of a text; null when the command model is not loaded. */
    private val embed: (String) -> Embedding?,
    /** Which model makes the embeddings; embeddings of another model are made again. */
    private val modelVersion: () -> String?,
    /** Hour of the day (0-23) of a time in millis, in your time zone. */
    private val hourOf: (Long) -> Int,
    private val clock: () -> Long,
) {
    /** One sentence you gave a label to: its signed vote per action, and its embedding once made. */
    class Example(val text: String, val votes: Map<String, Int>, val embedding: Embedding?)

    private class Snapshot(
        val version: String?,
        val byText: Map<String, Example>,
        /** Actions you used (picked or confirmed) per 3-hour slot of the day: the context prior for "Did you mean…". */
        val usedBySlot: Map<Int, Map<String, Int>>,
    )

    @Volatile private var snapshot = Snapshot(null, emptyMap(), emptyMap())

    val size: Int get() = snapshot.byText.size

    /**
     * Reads the examples again and embeds the sentences that have no embedding for the current model.
     * Sentences stay without one while the model is not loaded; the same sentence still counts.
     * Returns how many embeddings were made. Runs on a background thread (IO).
     */
    fun refresh(): Int = synchronized(this) {
        val rows = source()
        val version = modelVersion()
        val old = snapshot.takeIf { it.version == version }?.byText ?: emptyMap()
        // The latest label per (sentence, action) wins: a pick after a "Not what I meant" undoes the veto.
        val latest = LinkedHashMap<String, LinkedHashMap<String, Int>>()
        val slots = HashMap<Int, HashMap<String, Int>>()
        for (r in rows) {
            if (r.chosenAction == PebbleActions.OTHER) continue
            if (r.outcome == CommandFeedbackRepository.PICKED || r.outcome == CommandFeedbackRepository.CONFIRMED) {
                val counts = slots.getOrPut(slotOf(hourOf(r.atMillis))) { HashMap() }
                counts[r.chosenAction] = (counts[r.chosenAction] ?: 0) + 1
            }
            val sign = when (r.outcome) {
                CommandFeedbackRepository.TAUGHT, CommandFeedbackRepository.PICKED -> 1
                CommandFeedbackRepository.WRONG -> -1
                else -> continue // a confirmed row is the model's own guess: not an example
            }
            latest.getOrPut(normalize(r.text)) { LinkedHashMap() }[r.chosenAction] = sign
        }
        var made = 0
        var modelGone = false
        val byText = latest.mapValues { (text, votes) ->
            val e = old[text]?.embedding ?: if (modelGone || version == null) {
                null
            } else {
                embed(text).also { if (it == null) modelGone = true else made++ }
            }
            Example(text, votes, e)
        }
        snapshot = Snapshot(version, byText, slots)
        made
    }

    /** [u] with your examples blended in; [u] itself when no example is close to [text]. */
    fun adjust(text: String, u: Understood): Understood {
        val s = snapshot
        if (s.byText.isEmpty()) return u
        val key = normalize(text)
        val votes = HashMap<String, Float>()
        s.byText[key]?.votes?.forEach { (a, sign) -> votes[a] = (votes[a] ?: 0f) + EXACT_WEIGHT * sign }
        val qe = u.embedding
        if (qe != null && s.version != null) {
            val qWords = words(key)
            s.byText.values.asSequence()
                .filter { it.text != key && it.embedding != null && it.embedding.size == qe.size }
                .map { it to it.embedding!!.cosine(qe) }
                .filter { (ex, cos) -> cos >= TAU && jaccard(qWords, words(ex.text)) >= MIN_SHARED_WORDS }
                .sortedByDescending { it.second }
                .take(NEIGHBOURS)
                .forEach { (ex, cos) ->
                    val kappa = (cos - TAU) / (1f - TAU)
                    ex.votes.forEach { (a, sign) -> votes[a] = (votes[a] ?: 0f) + kappa * sign }
                }
        }
        if (votes.values.all { it == 0f }) return u
        return blend(u, votes)
    }

    /**
     * How much the context (the hour now) favours [action] among "Did you mean…" options: 1 = no opinion.
     * It only re-ranks options; it never changes whether Pebble acts. It only lifts an action you use more
     * than the average at this hour; it never pushes one down (an action you rarely use is not a wrong one).
     */
    fun contextWeight(action: String): Float {
        val counts = snapshot.usedBySlot[slotOf(hourOf(clock()))] ?: return 1f
        val total = counts.values.sum()
        val share = ((counts[action] ?: 0) + 1f) / (total + ACTIONS)
        return sqrt(share * ACTIONS).coerceAtLeast(1f)
    }

    companion object {
        /** Lowest cosine for a neighbour. Measured on the eval sets: see ADR 0010. */
        const val TAU = 0.90f

        /** Lowest share of common words (Jaccard) for a neighbour: one example then reaches 0.5 sentences on average, not 3.5. */
        const val MIN_SHARED_WORDS = 0.2f

        const val NEIGHBOURS = 5

        /** Votes needed for half weight: w = n / (n + K). */
        const val K = 2f

        /** The same sentence counts as this many close neighbours: one pick or one taught phrase gives w = 2/3. */
        const val EXACT_WEIGHT = 4f

        /** The layer never lifts a removal to this or more (the act threshold for removals is 0.9). */
        const val REMOVAL_CAP = 0.85f

        /** Pebble actions a pick can name ("other" is never one), for the smoothed context prior. */
        private const val ACTIONS = 8f

        private val wordRx = Regex("""[\p{L}\p{M}\p{N}]+""")

        /** The key for "the same sentence": lower case, single spaces (as brain/feedback.py does). */
        fun normalize(text: String): String = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

        private fun words(text: String): Set<String> = wordRx.findAll(text).map { it.value }.filter { it.length > 1 }.toSet()

        private fun jaccard(a: Set<String>, b: Set<String>): Float =
            if (a.isEmpty() || b.isEmpty()) 0f else (a intersect b).size.toFloat() / (a union b).size

        private fun slotOf(hour: Int) = hour / 3

        private fun groupOf(intent: String): String =
            PebbleActions.fromMassive(intent).let { if (it == PebbleActions.OTHER) "other:$intent" else it }

        /** The formula in the class comment, applied to every intent guess so [Understood.actions] stays consistent. */
        internal fun blend(u: Understood, votes: Map<String, Float>): Understood {
            val positive = votes.filterValues { it > 0f }
            val n = positive.values.sum()
            val w = n / (n + K)
            val modelMass = u.guesses.groupBy {
                groupOf(it.intent)
            }.mapValues { (_, gs) -> gs.sumOf { it.confidence.toDouble() }.toFloat() }

            // The voted action's share goes to its intents in the model's own ratio (or the first one, if all are 0).
            fun shareOf(g: IntentGuess, group: String): Float {
                val mass = modelMass[group] ?: 0f
                if (mass > 0f) return g.confidence / mass
                return if (u.guesses.first { groupOf(it.intent) == group } === g) 1f else 0f
            }
            var guesses = u.guesses.map { g ->
                val group = groupOf(g.intent)
                val negative = (-(votes[group] ?: 0f)).coerceAtLeast(0f)
                val kept = (1f - w) * g.confidence * K / (negative + K)
                val added = if (n > 0f) w * ((positive[group] ?: 0f) / n) * shareOf(g, group) else 0f
                g.copy(confidence = kept + added)
            }
            for (removal in listOf(PebbleActions.REMINDER_REMOVE, PebbleActions.NOTE_REMOVE)) {
                val before = modelMass[removal] ?: continue
                val after = guesses.filter { groupOf(it.intent) == removal }.sumOf { it.confidence.toDouble() }.toFloat()
                val limit = maxOf(before, REMOVAL_CAP)
                if (after > limit) {
                    val scale = limit / after
                    guesses = guesses.map { if (groupOf(it.intent) == removal) it.copy(confidence = it.confidence * scale) else it }
                }
            }
            return u.copy(guesses = guesses.sortedByDescending { it.confidence })
        }
    }
}
