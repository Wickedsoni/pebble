package dev.pebble.core

import dev.pebble.core.brain.Embedding
import dev.pebble.core.brain.IntentGuess
import dev.pebble.core.brain.Understood
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Understood stays a value: two readings with equal embeddings are equal (arrays alone compare by reference). */
class EmbeddingTest {
    private fun u(
        vararg e: Float,
    ) = Understood(listOf("hi"), listOf(IntentGuess("general_greet", 0.9f)), listOf("O"), embedding = Embedding(e))

    @Test
    fun equalValuesMeanEqualReadings() {
        assertEquals(u(0.6f, 0.8f), u(0.6f, 0.8f))
        assertEquals(u(0.6f, 0.8f).hashCode(), u(0.6f, 0.8f).hashCode())
        assertNotEquals(u(0.6f, 0.8f), u(0.8f, 0.6f))
    }

    @Test
    fun cosineOfUnitVectors() {
        val a = Embedding(floatArrayOf(1f, 0f))
        val b = Embedding(floatArrayOf(sqrt(0.5f), sqrt(0.5f)))
        assertEquals(1f, a.cosine(a), 1e-6f)
        assertEquals(sqrt(0.5f), a.cosine(b), 1e-6f)
    }

    @Test
    fun readingsWithoutAnEmbeddingStillWork() {
        val plain = Understood(listOf("hi"), listOf(IntentGuess("general_greet", 0.9f)), listOf("O"))
        assertEquals(null, plain.embedding)
        assertEquals(plain, plain.copy())
    }
}
