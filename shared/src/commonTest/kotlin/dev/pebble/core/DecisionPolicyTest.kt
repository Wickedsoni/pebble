package dev.pebble.core

import dev.pebble.core.brain.DecisionPolicy
import dev.pebble.core.brain.DecisionPolicy.Decision
import dev.pebble.core.brain.IntentGuess
import dev.pebble.core.brain.Understood
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DecisionPolicyTest {
    private val policy = DecisionPolicy()
    private fun u(vararg g: Pair<String, Float>) = Understood(listOf("x"), g.map { IntentGuess(it.first, it.second) }, listOf("O"))

    @Test fun intentsOfOneActionAddUp() {
        // Neither intent alone reaches remind's 0.7 bar, together they do.
        val d = policy.decide(u("calendar_set" to 0.45f, "alarm_set" to 0.40f, "general_quirky" to 0.15f))
        val act = assertIs<Decision.Act>(d)
        assertEquals("remind", act.guess.action)
        assertEquals("calendar_set", act.guess.bestIntent)
    }

    @Test fun destructiveActionsNeedMoreCertainty() {
        assertIs<Decision.Ask>(policy.decide(u("calendar_remove" to 0.85f, "calendar_set" to 0.15f)))
        assertIs<Decision.Act>(policy.decide(u("calendar_remove" to 0.95f, "calendar_set" to 0.05f)))
        // The same 0.85 is plenty for a harmless query.
        assertIs<Decision.Act>(policy.decide(u("calendar_query" to 0.85f, "calendar_set" to 0.15f)))
    }

    @Test fun aTornModelAsks() {
        // Chitchat clears its 0.5 bar but is only 0.08 ahead of "remind".
        assertIs<Decision.Ask>(policy.decide(u("general_greet" to 0.54f, "calendar_set" to 0.46f)))
    }

    @Test fun unsupportedIntentsNeverAddUp() {
        // Music + weather + news are different things Pebble can't do, not one confident "other".
        assertIs<Decision.Ask>(policy.decide(u("play_music" to 0.4f, "weather_query" to 0.3f, "news_query" to 0.3f)))
        val d = policy.decide(u("play_music" to 0.9f, "general_quirky" to 0.1f))
        assertEquals("other", assertIs<Decision.Act>(d).guess.action)
    }
}
