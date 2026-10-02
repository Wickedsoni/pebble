package dev.pebble.core

import dev.pebble.core.growth.GrowthRules
import dev.pebble.core.growth.GrowthStats
import dev.pebble.core.growth.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GrowthTest {
    private fun stats(days: Int = 0, done: Int = 0, goalDays: Int = 0, streak: Int = 0, notes: Int = 0, chats: Int = 0) =
        GrowthStats(days, done, goalDays, streak, notes, chats)

    @Test
    fun everyoneStartsAsABabyAndSeesWhatsNext() {
        val g = GrowthRules.growth(stats())
        assertEquals(Level.BABY to Level.TEEN, g.earned to g.next)
        assertEquals(4, g.tasks.size)
        assertTrue(g.tasks.none { it.done })
    }

    @Test
    fun aLevelNeedsAllItsTasksNotJustMost() {
        val almost = stats(days = 3, done = 10, goalDays = 1, chats = 4) // one chat short
        assertEquals(Level.BABY, GrowthRules.growth(almost).earned)
        assertEquals(Level.TEEN, GrowthRules.growth(almost.copy(chats = 5)).earned)
    }

    @Test
    fun levelsAreEarnedInOrder() {
        // Enough for Legendary's water streak but not Adult's notes: stays Teen.
        val teen = stats(days = 40, done = 300, goalDays = 30, streak = 10, notes = 2, chats = 200)
        assertEquals(Level.TEEN, GrowthRules.growth(teen).earned)
        val legend = GrowthRules.growth(teen.copy(notesDone = 5))
        assertEquals(Level.LEGENDARY, legend.earned)
        assertNull(legend.next)
        assertTrue(legend.unlocked(Level.ADULT))
    }
}
