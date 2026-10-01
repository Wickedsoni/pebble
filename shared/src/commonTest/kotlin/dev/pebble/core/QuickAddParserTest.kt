package dev.pebble.core

import dev.pebble.core.quickadd.QuickAddParser
import dev.pebble.core.quickadd.QuickCommand.AddNote
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
        assertEquals(RemindAt("Standup", 9, 15, 0), p("standup today at 9:15"))
        assertEquals(RemindAt("Lunch", 12, 0, null), p("lunch at 12pm"))
        assertEquals(RemindAt("Sleep", 0, 30, 1), p("sleep at 12:30am tomorrow"))
    }
}
