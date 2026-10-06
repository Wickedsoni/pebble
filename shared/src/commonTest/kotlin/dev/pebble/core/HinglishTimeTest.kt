package dev.pebble.core

import dev.pebble.core.brain.HinglishTime
import dev.pebble.core.brain.HinglishTime.When.At
import dev.pebble.core.brain.HinglishTime.When.In
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HinglishTimeTest {
    private fun p(s: String) = HinglishTime.parse(s)

    @Test fun english() {
        assertEquals(At(19, 0, null, false), p("7 pm"))
        assertEquals(At(19, 0, null, false), p("7pm"))
        assertEquals(At(6, 0, 1, false), p("6 am tomorrow"))
        assertEquals(At(17, 30, null, false), p("17:30"))
        assertEquals(At(5, 30, null, true), p("half past five"))
        assertEquals(At(12, 0, null, false), p("noon"))
    }

    @Test fun romanHindi() {
        assertEquals(At(5, 0, 1, true), p("kal 5 baje"))
        assertEquals(At(19, 0, null, false), p("shaam 7 baje"))
        assertEquals(At(6, 0, 1, false), p("kal subah 6 baje"))
        assertEquals(At(17, 30, null, false), p("shaam saade paanch baje"))
        assertEquals(At(18, 45, null, false), p("sham paune saat baje"))
        assertEquals(At(1, 30, null, true), p("dedh baje"))
        assertEquals(At(21, 0, 2, false), p("parso raat 9 baje"))
    }

    @Test fun devanagari() {
        assertEquals(At(19, 0, null, false), p("शाम सात बजे"))
        assertEquals(At(6, 0, 1, false), p("कल सुबह छह बजे"))
        assertEquals(At(16, 15, 0, false), p("आज दोपहर सवा चार बजे"))
        assertEquals(At(9, 0, null, true), p("९ बजे"))
    }

    @Test fun relative() {
        assertEquals(In(20), p("in 20 minutes"))
        assertEquals(In(20), p("20 min baad"))
        assertEquals(In(20), p("बीस मिनट बाद"))
        assertEquals(In(60), p("ek ghante mein"))
        assertEquals(In(120), p("2 hours"))
        assertEquals(In(30), p("aadhe ghante baad"))
        assertEquals(In(30), p("आधे घंटे बाद"))
        assertEquals(In(30), p("in half an hour"))
        assertEquals(In(90), p("dedh ghante mein"))
        assertEquals(In(15), p("thodi der mein"))
        assertEquals(At(1, 30, null, true), p("dedh baje")) // a clock time, not a duration
    }

    @Test fun partOfDayOnly() {
        assertEquals(At(18, 0, 1, false), p("kal shaam"))
        assertEquals(At(9, 0, 1, false), p("tomorrow morning"))
        assertNull(p("call mom"))
    }

    @Test fun weekdays() {
        val wed = 3
        assertEquals(At(5, 0, 2, true), HinglishTime.parse("friday 5 baje", wed))
        assertEquals(At(19, 0, 2, false), HinglishTime.parse("shukravar shaam 7 baje", wed))
        assertEquals(At(9, 0, 5, false), HinglishTime.parse("सोमवार सुबह", wed))
        assertEquals(At(10, 0, 0, true), HinglishTime.parse("wednesday 10 baje", wed))
        assertEquals(At(10, 0, 7, true), HinglishTime.parse("agle budhvar 10 baje", wed))
        assertEquals(At(18, 0, 6, false), HinglishTime.parse("next tuesday evening", wed))
        // Without today's weekday the day is unknown, not guessed.
        assertEquals(At(5, 0, null, true), HinglishTime.parse("friday 5 baje"))
        // "saat" / "sat" is seven, never Saturday.
        assertEquals(At(19, 0, null, false), HinglishTime.parse("shaam sat baje", wed))
        assertEquals(2, HinglishTime.dayOf("friday wali meeting", wed))
        assertNull(HinglishTime.dayOf("friday wali meeting"))
    }

    @Test fun aBareNumberWordIsNotAClockHour() {
        assertNull(p("remind me to do homework tomorrow"))
        assertNull(p("kal yaad dila do"))
        assertNull(p("call ek friend kal"))
        assertNull(p("buy one gift tomorrow"))
        assertNull(p("buy 3 apples tomorrow"))
        assertNull(p("do"))
        assertEquals(false, HinglishTime.hasClock("buy 3 apples tomorrow"))
    }

    @Test fun aNumberNextToAClockCueIsAnHour() {
        assertEquals(At(5, 0, null, true), p("at 5"))
        assertEquals(At(5, 0, null, true), p("at five"))
        assertEquals(At(3, 0, 1, true), p("tomorrow at 3"))
        assertEquals(At(3, 0, 1, true), p("kal 3"))
        assertEquals(At(7, 0, null, true), p("saat baje"))
        assertEquals(At(2, 0, 1, true), p("kal do baje"))
        assertEquals(At(19, 0, null, false), p("shaam saat"))
        assertEquals(At(1, 0, null, true), p("one o'clock"))
    }

    @Test fun aDurationNeedsItsMarkerNextToIt() {
        assertEquals(At(4, 0, null, true), p("remind me about the 30 minute standup at 4"))
        assertEquals(At(16, 0, null, false), p("30 minute standup at 4pm"))
        assertEquals(In(20), p("call me in 20 min"))
        assertEquals(In(20), p("20 min mein"))
        assertEquals(In(20), p("20 min me"))
        assertEquals(In(60), p("in an hour"))
        assertEquals(In(30), p("in half an hour"))
    }

    @Test fun hugeDurationsAreClamped() {
        assertEquals(In(7 * 24 * 60), p("in 99999999 hours"))
        assertNull(p("in 99999999999 minutes"))
    }

    @Test fun keBaadIsARelativeMarker() {
        assertEquals(In(10), p("10 minute ke baad yaad dilana"))
        assertEquals(In(120), p("2 ghante ke baad"))
        assertEquals(In(10), p("दस मिनट के बाद"))
        assertEquals(In(30), p("आधे घंटे के बाद"))
        assertEquals(In(15), p("thodi der ke baad"))
        assertEquals(In(10), p("in about 10 minutes"))
        assertEquals(In(10), p("in the next 10 min"))
    }

    @Test fun numberWordsAmongDayWordsAreHours() {
        assertEquals(At(7, 0, 1, true), p("kal saat"))
        assertEquals(At(5, 0, 1, true), p("kal paanch call karna"))
        assertEquals(At(7, 0, 1, true), p("कल सात"))
        assertEquals(At(5, 0, null, true), p("friday five"))
        assertNull(p("kal do"))
        assertNull(p("kal yaad dila do"))
        assertNull(p("kal ek friend se milna"))
    }

    @Test fun moreCuesBeforeAnHour() {
        assertEquals(At(5, 0, null, true), p("submit report by 5"))
        assertEquals(At(7, 0, null, true), p("call around 7"))
        assertEquals(At(6, 0, null, true), p("before 6"))
        assertEquals(At(8, 0, null, true), p("till 8"))
        assertEquals(At(7, 0, null, true), p("lagbhag 7"))
        assertEquals(At(5, 0, null, true), p("@5"))
        assertEquals(At(17, 0, null, false), p("@5pm"))
    }

    @Test fun aDurationAfterAClockIsNotRelative() {
        assertEquals(At(10, 0, null, true), p("meeting at 10 for 30 minutes"))
        assertEquals(At(18, 0, null, false), p("remind me in the evening about the 30 minute call"))
    }

    @Test fun spelledOutClocks() {
        assertEquals(At(17, 30, null, false), p("five thirty pm"))
        assertEquals(At(5, 0, null, true), p("5 o clock"))
        assertEquals(At(5, 0, null, true), p("5 o'clock"))
        assertEquals(At(5, 45, null, true), p("quarter to six"))
        assertEquals(At(6, 15, null, true), p("quarter past six"))
    }

    @Test fun weakCuesNeedTheNumberToEndTheTime() {
        assertNull(p("read around 5 pages tomorrow"))
        assertNull(p("stand by 3 apples"))
        assertEquals(At(5, 0, null, true), p("submit report by 5"))
        assertEquals(At(5, 0, 1, true), p("by 5 tomorrow"))
        assertEquals(At(17, 0, null, false), p("by 5 pm"))
        assertEquals(At(5, 0, null, true), p("at 5 pages"))
    }

    @Test fun numberWordAdjacencyIgnoresNextWords() {
        assertNull(p("review the next five chapters tomorrow"))
        assertNull(p("agle paanch din"))
        assertEquals(At(4, 0, 1, true), p("kal char"))
        assertEquals(At(10, 0, 1, true), p("kal das"))
    }

    @Test fun theBelongsToNextOnly() {
        assertEquals(At(4, 0, null, true), p("in the 30 minute meeting at 4"))
        assertEquals(In(10), p("in the next 10 min"))
    }
}
