package dev.pebble.core

import dev.pebble.core.quickadd.QuickAddParser
import dev.pebble.core.quickadd.QuickCommand.AddNote
import dev.pebble.core.quickadd.QuickCommand.RememberFact
import dev.pebble.core.quickadd.QuickCommand.LogWater
import dev.pebble.core.quickadd.QuickCommand.RemindAt
import dev.pebble.core.quickadd.QuickCommand.RemindIn
import dev.pebble.core.quickadd.QuickCommand.SetInterval
import dev.pebble.core.reminders.ReminderKind
import dev.pebble.core.reminders.Strictness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QuickAddParserTest {
    private fun p(s: String) = QuickAddParser.parse(s)

    @Test fun notes() {
        assertEquals(AddNote("buy milk"), p("note: buy milk"))
        assertEquals(AddNote("idea for app"), p("n idea for app"))
        assertEquals(AddNote("random thought"), p("random thought"))
        assertNull(p("   "))
    }

    @Test fun waterLogging() {
        assertEquals(LogWater(1), p("water"))
        assertEquals(LogWater(2), p("+2 water"))
        assertEquals(LogWater(3), p("drank 3 glasses"))
        assertEquals(LogWater(1), p("water +1"))
    }

    @Test fun intervals() {
        assertEquals(SetInterval(ReminderKind.WATER, 45, null), p("water every 45m"))
        assertEquals(SetInterval(ReminderKind.WATER, 60, Strictness.STRICT), p("drink water every hour strict"))
        assertEquals(SetInterval(ReminderKind.STRETCH, 90, Strictness.GENTLE), p("stretch every 1.5h gentle"))
        assertEquals(SetInterval(ReminderKind.EYES, 20, null), p("eye break every 20 minutes"))
    }

    @Test fun relativeReminders() {
        assertEquals(RemindIn("Call mom", 20), p("remind me to call mom in 20 min"))
        assertEquals(RemindIn("Check the oven", 120), p("check the oven in 2h"))
    }

    @Test fun absoluteReminders() {
        assertEquals(RemindAt("Call mom", 19, 0, null), p("remind me to call mom at 7pm"))
        assertEquals(RemindAt("Submit DBMS assignment", 17, 30, 1), p("submit DBMS assignment tomorrow 5:30pm"))
        assertEquals(RemindAt("Standup", 9, 15, 0, flexibleHalfDay = true), p("standup today at 9:15"))
        assertEquals(RemindAt("DBMS assignment", 5, 0, 1, flexibleHalfDay = true), p("set a reminder for my DBMS assignment tomorrow at 5"))
        assertEquals(RemindAt("Lunch", 12, 0, null), p("lunch at 12pm"))
        assertEquals(RemindAt("Sleep", 0, 30, 1), p("sleep at 12:30am tomorrow"))
    }

    @Test fun hindiAndHinglishRules() {
        assertEquals(RememberFact("mera exam 20 tareekh ko hai"), p("yaad rakhna ki mera exam 20 tareekh ko hai"))
        assertEquals(RememberFact("मेरा एग्जाम बीस तारीख को है"), p("याद रखना कि मेरा एग्जाम बीस तारीख को है"))
        assertEquals(SetInterval(ReminderKind.WATER, 45, null), p("har 45 minute mein paani peene ki yaad dilana"))
        assertEquals(SetInterval(ReminderKind.WATER, 45, null), p("हर पैंतालीस मिनट में पानी पीने की याद दिलाना"))
        assertEquals(SetInterval(ReminderKind.STRETCH, 60, null), p("har ghante stretch karna yaad dilana"))
        assertEquals(LogWater(1), p("maine ek glass paani pi liya"))
        assertEquals(LogWater(2), p("do glass paani piya"))
        assertEquals(LogWater(1), p("मैंने एक गिलास पानी पी लिया"))
        assertEquals(LogWater(1), p("i drank a glass of water"))
        assertEquals(LogWater(2), p("had two glasses of water"))
    }
}
