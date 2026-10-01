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
    }

    @Test fun partOfDayOnly() {
        assertEquals(At(18, 0, 1, false), p("kal shaam"))
        assertEquals(At(9, 0, 1, false), p("tomorrow morning"))
        assertNull(p("call mom"))
    }
}
