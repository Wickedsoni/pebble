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
}
