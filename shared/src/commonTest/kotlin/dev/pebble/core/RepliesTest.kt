package dev.pebble.core

import dev.pebble.core.brain.Replies
import dev.pebble.core.brain.Script
import dev.pebble.core.reminders.ReminderCopy
import dev.pebble.core.reminders.ReminderKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepliesTest {
    @Test
    fun neverTheSameLineTwiceInARow() {
        val r = Random(1)
        val replies = List(20) { Replies.chitchat("hmm theek hai", "general_quirky", random = r) }
        replies.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
        assertTrue(replies.toSet().size >= 4, "a real variety of lines")
    }

    @Test
    fun theReplyFitsWhatWasSaid() {
        // First real install: these all got "Aww, thank you!" before.
        val aboutMe = Replies.chitchat("tumhara kya plan hai aaj", "general_quirky")
        assertTrue(
            aboutMe.contains(
                "plan",
                ignoreCase = true,
            ) || aboutMe.contains("ghoom") || aboutMe.contains("pet") || aboutMe.contains("seekh") ||
                aboutMe.contains("badhiya"),
            aboutMe,
        )
        val happy = Replies.chitchat("aaj ka din bahut badhiya ja raha hai", "general_quirky", mood = "good")
        assertTrue(listOf("Wah", "Kya baat", "Badhiya", "Mast", "Yay").any { happy.startsWith(it) }, happy)
        assertTrue(
            Replies.chitchat("आज का दिन अच्छा जा रहा है", "general_quirky", mood = "good").any {
                it in 'ऀ'..'ॿ'
            },
            "same script back",
        )
        val kind = Replies.chitchat("you are so cute", "general_quirky")
        assertTrue(kind.isNotBlank() && kind.none { it in 'ऀ'..'ॿ' })
    }

    @Test
    fun reminderWordsRotateInYourLanguage() {
        val lines = List(3) { ReminderCopy.line(ReminderKind.EYES, it, Script.EN)!!.headline }
        assertEquals(3, lines.toSet().size, "not the same 20-20-20 text every time")
        assertTrue(ReminderCopy.line(ReminderKind.WATER, 0, Script.HI_ROMAN)!!.headline.contains("Paani"))
        assertNull(ReminderCopy.line(ReminderKind.CUSTOM, 0), "your own reminders keep their own words")
    }
}
