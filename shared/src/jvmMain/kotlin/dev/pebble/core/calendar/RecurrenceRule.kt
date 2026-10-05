package dev.pebble.core.calendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The RRULE subset that Pebble expands (WP E2): `FREQ=DAILY|WEEKLY|MONTHLY|YEARLY`, `INTERVAL`, `BYDAY` (WEEKLY only, no
 * numbers such as `1MO`), `BYMONTHDAY` (MONTHLY: 1 to 31; YEARLY: only with `BYMONTH`), `BYMONTH` (YEARLY only,
 * 1 to 12), and one of `UNTIL` or `COUNT`. YEARLY without `BYMONTH` and `BYMONTHDAY` repeats on the day of DTSTART.
 * `WKST=MO` is accepted. [parse] returns null for every other rule; such an event is kept and shown once, with a warning.
 */
data class RecurrenceRule(
    val freq: Freq,
    val interval: Int = 1,
    val byDay: Set<DayOfWeek> = emptySet(),
    val byMonthDay: List<Int> = emptyList(),
    val byMonth: List<Int> = emptyList(),
    val until: Until? = null,
    val count: Int? = null,
) {
    enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    /** The last allowed start: a date (all-day), a UTC instant, or a local time in the event's zone. */
    sealed interface Until {
        data class Date(val date: LocalDate) : Until

        data class Utc(val instant: Instant) : Until

        data class Local(val time: LocalDateTime) : Until
    }

    /** The RRULE value, for the database and for ICS export. */
    fun format(): String = buildList {
        add("FREQ=$freq")
        if (interval != 1) add("INTERVAL=$interval")
        if (byDay.isNotEmpty()) add("BYDAY=" + byDay.sorted().joinToString(",") { DAYS.getValue(it) })
        if (byMonth.isNotEmpty()) add("BYMONTH=" + byMonth.joinToString(","))
        if (byMonthDay.isNotEmpty()) add("BYMONTHDAY=" + byMonthDay.joinToString(","))
        when (val u = until) {
            is Until.Date -> add("UNTIL=" + u.date.format(DateTimeFormatter.BASIC_ISO_DATE))
            is Until.Utc -> add("UNTIL=" + UTC_FORMAT.format(u.instant.atOffset(ZoneOffset.UTC)))
            is Until.Local -> add("UNTIL=" + LOCAL_FORMAT.format(u.time))
            null -> Unit
        }
        count?.let { add("COUNT=$it") }
    }.joinToString(";")

    companion object {
        private val DAYS = mapOf(
            DayOfWeek.MONDAY to "MO",
            DayOfWeek.TUESDAY to "TU",
            DayOfWeek.WEDNESDAY to "WE",
            DayOfWeek.THURSDAY to "TH",
            DayOfWeek.FRIDAY to "FR",
            DayOfWeek.SATURDAY to "SA",
            DayOfWeek.SUNDAY to "SU",
        )
        private val BY_NAME = DAYS.entries.associate { (k, v) -> v to k }
        private val UTC_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        private val LOCAL_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

        /** The rule in [value] (with or without "RRULE:"), or null if Pebble cannot expand it. */
        fun parse(value: String): RecurrenceRule? {
            val parts = value.trim().removePrefix("RRULE:").split(';').filter { it.isNotBlank() }.map { p ->
                val i = p.indexOf('=')
                if (i <= 0) return null
                p.substring(0, i).trim().uppercase() to p.substring(i + 1).trim().uppercase()
            }
            val map = parts.toMap()
            if (map.size != parts.size) return null // a part given twice
            val freq = when (map["FREQ"]) {
                "DAILY" -> Freq.DAILY
                "WEEKLY" -> Freq.WEEKLY
                "MONTHLY" -> Freq.MONTHLY
                "YEARLY" -> Freq.YEARLY
                else -> return null
            }
            var rule = RecurrenceRule(freq)
            for ((k, v) in map) {
                rule = when (k) {
                    "FREQ" -> rule

                    "WKST" -> if (v == "MO") rule else return null

                    "INTERVAL" -> rule.copy(interval = v.toIntOrNull()?.takeIf { it in 1..1000 } ?: return null)

                    "COUNT" -> rule.copy(count = v.toIntOrNull()?.takeIf { it >= 1 } ?: return null)

                    "UNTIL" -> rule.copy(until = until(v) ?: return null)

                    "BYDAY" -> {
                        if (freq != Freq.WEEKLY) return null
                        rule.copy(byDay = v.split(',').map { BY_NAME[it.trim()] ?: return null }.toSet())
                    }

                    "BYMONTHDAY" -> {
                        if (freq != Freq.MONTHLY && freq != Freq.YEARLY) return null
                        rule.copy(
                            byMonthDay = v.split(',').map {
                                it.trim().toIntOrNull()?.takeIf { d -> d in 1..31 } ?: return null
                            }.distinct().sorted(),
                        )
                    }

                    "BYMONTH" -> {
                        if (freq != Freq.YEARLY) return null
                        rule.copy(
                            byMonth = v.split(',').map {
                                it.trim().toIntOrNull()?.takeIf { m -> m in 1..12 } ?: return null
                            }.distinct().sorted(),
                        )
                    }

                    else -> return null
                }
            }
            if (rule.count != null && rule.until != null) return null // RFC 5545: not both
            // BYMONTHDAY alone in a YEARLY rule means every month (RFC 5545); Pebble expands only a named month.
            if (freq == Freq.YEARLY && rule.byMonthDay.isNotEmpty() && rule.byMonth.isEmpty()) return null
            return rule
        }

        private fun until(v: String): Until? = runCatching {
            when {
                v.length == 8 -> Until.Date(LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE))
                v.endsWith("Z") -> Until.Utc(LocalDateTime.parse(v.dropLast(1), LOCAL_FORMAT).toInstant(ZoneOffset.UTC))
                else -> Until.Local(LocalDateTime.parse(v, LOCAL_FORMAT))
            }
        }.getOrNull()
    }
}
