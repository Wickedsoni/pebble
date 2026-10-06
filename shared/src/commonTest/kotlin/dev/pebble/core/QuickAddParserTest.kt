package dev.pebble.core

import dev.pebble.core.quickadd.QuickAddParser
import dev.pebble.core.quickadd.QuickCommand.AddEvent
import dev.pebble.core.quickadd.QuickCommand.AddNote
import dev.pebble.core.quickadd.QuickCommand.LogWater
import dev.pebble.core.quickadd.QuickCommand.OpenPage
import dev.pebble.core.quickadd.QuickCommand.RememberFact
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

    @Test fun openAPageInEveryScript() {
        fun page(text: String) = (QuickAddParser.parseStrict(text) as? OpenPage)?.page
        assertEquals("reminders", page("open reminders"))
        assertEquals("reminders", page("open reminder"))
        assertEquals("reminders", page("Open the reminders page."))
        assertEquals("notes", page("go to my notes"))
        assertEquals("water", page("take me to water"))
        assertEquals("memory", page("open privacy"))
        assertEquals("reminders", page("reminders kholo"))
        assertEquals("notes", page("mere notes khol do na"))
        assertEquals("water", page("paani page open karo"))
        assertEquals("reminders", page("रिमाइंडर खोलो"))
        assertEquals("notes", page("नोट्स खोल दो।"))
        assertEquals(OpenPage("chat", "open chat"), QuickAddParser.parseStrict("open chat"))
    }

    @Test fun openingIsOnlyAWholeSentence() {
        fun page(text: String) = (QuickAddParser.parseStrict(text) as? OpenPage)?.page
        assertNull(page("remind me to open the shop at 9"))
        assertNull(page("open the window"))
        assertNull(page("open reminders for tomorrow"))
        assertEquals(AddNote("open notes later"), QuickAddParser.parseStrict("note: open notes later"))
    }

    /** WP E2: explicit event syntax only. Today is a Sunday (7) in these cases. */
    @Test fun events() {
        fun e(s: String) = QuickAddParser.parseStrict(s, today = 7)
        assertEquals(AddEvent("Dentist", hour = 17, dayOffset = 5), e("event: dentist fri 5pm"))
        assertEquals(
            AddEvent("Dentist", hour = 17, minute = 30, dayOffset = 5, durationMinutes = 30),
            e("event: Dentist on friday at 5:30 pm for 30 min"),
        )
        assertEquals(AddEvent("Team lunch", hour = 13, dayOffset = 1, durationMinutes = 90), e("cal: team lunch tomorrow 13:00 for 1.5h"))
        assertEquals(AddEvent("Diwali", month = 11, dayOfMonth = 8), e("event: Diwali 8 nov"))
        assertEquals(AddEvent("Exam", hour = 10, month = 10, dayOfMonth = 20), e("event: exam oct 20th 10am"))
        assertEquals(AddEvent("Trip", dayOffset = 6), e("event: trip sat all day"))
        assertEquals(AddEvent("Standup", hour = 9, flexibleHalfDay = true), e("calendar: standup at 9"))
        assertEquals(AddEvent("Party", hour = 19, dayOffset = 7), e("event: party next sunday 7pm"))
        assertEquals(AddEvent("Holiday"), e("event: holiday"))
        assertEquals(AddEvent("Monster truck show", hour = 18, dayOffset = 1), e("event: monster truck show tmrw 6pm"))
        assertNull(QuickAddParser.parseEvent("5pm"), "no title left")
        assertEquals(AddNote("event planning tomorrow"), QuickAddParser.parse("event planning tomorrow"), "no colon: not an event")
        assertEquals(OpenPage("calendar", "open calendar"), e("open calendar"))
        // The Calendar page's Add field uses the same reader without "event:".
        assertEquals(AddEvent("Dentist", hour = 17, durationMinutes = 30), QuickAddParser.parseEvent("Dentist 5pm for 30 min"))
    }

    @Test fun hugeNumbersNeverThrow() {
        assertEquals(LogWater(20), p("12345678901 water"))
        assertEquals(RemindIn("Stretch", 7 * 24 * 60), p("stretch in 99999999999 min"))
        assertEquals(RemindIn("Stretch", 7 * 24 * 60), p("stretch in 99999999 h"))
        assertEquals(RemindIn("Stretch", 5), p("stretch in 5 min"))
        assertEquals(SetInterval(ReminderKind.WATER, 24 * 60, null), p("water every 99999999999 minutes"))
        assertEquals(SetInterval(ReminderKind.WATER, 24 * 60, null), p("har 99999999 ghante paani"))
        assertEquals(LogWater(1), p("paani pi 99999999999 glass"))
        assertEquals(AddEvent("Dentist", durationMinutes = 24 * 60), QuickAddParser.parseEvent("Dentist for 99999999999999999999 min"))
    }

    @Test fun kindAndStrictnessNeedWholeWords() {
        assertEquals(AddNote("outstanding tasks every 30 min"), p("outstanding tasks every 30 min"))
        assertEquals(AddNote("gossip every 10 min"), p("gossip every 10 min"))
        assertEquals(AddNote("eyebrow trim every 5 min"), p("eyebrow trim every 5 min"))
        assertEquals(SetInterval(ReminderKind.WATER, 120, null), p("water every 2 hours microsoft"))
        assertEquals(SetInterval(ReminderKind.STRETCH, 30, Strictness.GENTLE), p("stretch every 30 min soft"))
        assertEquals(SetInterval(ReminderKind.STRETCH, 30, null), p("stretch every 30 min microsoft"))
        assertEquals(SetInterval(ReminderKind.EYES, 20, Strictness.STRICT), p("eyes every 20 min strict"))
    }

    @Test fun politePrefixesAreNotPartOfTheTitle() {
        assertEquals(RemindAt("Call mom", 5, 0, null, flexibleHalfDay = true), p("can you remind me to call mom at 5"))
        assertEquals(RemindAt("Call mom", 17, 0, null), p("hey pebble remind me to call mom at 5pm"))
        assertEquals(RemindIn("Stretch", 20), p("please remind me to stretch in 20 min"))
    }

    @Test fun strictnessWordsMayHaveEndings() {
        assertEquals(SetInterval(ReminderKind.STRETCH, 30, Strictness.GENTLE), p("stretch every 30 min gently"))
        assertEquals(SetInterval(ReminderKind.WATER, 30, Strictness.STRICT), p("water every 30 min strictly"))
    }

    @Test fun strictnessWordsDoNotMatchLongerWords() {
        assertEquals(SetInterval(ReminderKind.WATER, 30, null), p("water every 30 min software"))
        assertEquals(SetInterval(ReminderKind.WATER, 30, null), p("water every 30 min softball"))
        assertEquals(SetInterval(ReminderKind.WATER, 30, null), p("water every 30 min coaching"))
        assertEquals(SetInterval(ReminderKind.WATER, 30, Strictness.GENTLE), p("water every 30 min softly"))
    }
}
