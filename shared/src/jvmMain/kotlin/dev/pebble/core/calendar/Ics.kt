package dev.pebble.core.calendar

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * iCalendar (RFC 5545) files, VEVENT only (WP E2). Hand-written, no dependency.
 *
 * Read: `UID`, `SUMMARY`, `DESCRIPTION`, `DTSTART`/`DTEND`/`DURATION` (date, UTC, `TZID` or floating), `RRULE`,
 * `EXDATE`, and `RECURRENCE-ID` (a changed occurrence becomes its own event and is skipped in the series).
 * Ignored: `VTIMEZONE` (Pebble uses the IANA zone named in `TZID`), `VALARM`, `RDATE` and all other properties.
 * Write: the same properties; `TZID` names the IANA zone without a `VTIMEZONE` block.
 */
object Ics {
    /** What a file gave. [shownOnce]: events that repeat in a way Pebble cannot expand. [unknownZones]: `TZID`s that are not IANA zones (read in the default zone). [skipped]: VEVENTs without a start, or without END:VEVENT. */
    data class Import(val events: List<CalendarEvent>, val shownOnce: Int, val unknownZones: Int, val skipped: Int)

    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
    private val UTC_ZONE: ZoneId = ZoneId.of("UTC")

    private data class Prop(val name: String, val params: Map<String, String>, val value: String)

    /** The events in [text]. Floating times (no zone) are read in [defaultZone]. New uids come from [newUid]. */
    fun parse(text: String, defaultZone: ZoneId, newUid: () -> String = { CalendarRepository.newUid() }): Import {
        val lines = text.replace("\r\n", "\n").replace("\r", "\n").replace(Regex("\n[ \t]"), "").split('\n')
        val blocks = mutableListOf<List<Prop>>()
        var current: MutableList<Prop>? = null
        var skipped = 0
        // Open blocks, outermost first: VEVENT, then VALARM and other blocks inside it.
        val stack = ArrayList<String>()
        for (line in lines) {
            if (line.isBlank()) continue
            val p = prop(line) ?: continue
            val name = p.value.trim().uppercase()
            val block = current
            when {
                block == null -> if (p.name == "BEGIN" && name == "VEVENT") {
                    current = mutableListOf()
                    stack += name
                }

                // A VEVENT that never ended: drop it, and read this line again as the start of the next block.
                p.name == "BEGIN" && name == "VEVENT" -> {
                    skipped++
                    current = mutableListOf()
                    stack.clear()
                    stack += name
                }

                p.name == "BEGIN" -> stack += name

                p.name == "END" && name == "VCALENDAR" -> {
                    skipped++
                    current = null
                    stack.clear()
                }

                p.name == "END" -> {
                    val at = stack.lastIndexOf(name)
                    if (at == 0) {
                        blocks += block
                        current = null
                        stack.clear()
                    } else if (at > 0) {
                        while (stack.size > at) stack.removeAt(stack.lastIndex)
                    }
                }

                stack.size == 1 -> block += p
            }
        }
        if (current != null) skipped++ // the file ended inside a VEVENT

        var shownOnce = 0
        val unknownZones = mutableSetOf<String>()
        fun zoneOf(p: Prop): ZoneId? {
            val tzid = p.params["TZID"] ?: return null
            // Some programs write "/mozilla.org/20070129_1/Europe/Berlin"-style ids: try the IANA part.
            val tries = listOf(tzid, tzid.trim('/').split('/').takeLast(2).joinToString("/"), tzid.substringAfterLast('/'))
            return tries.firstNotNullOfOrNull { runCatching { ZoneId.of(it) }.getOrNull() } ?: run {
                unknownZones += tzid
                defaultZone
            }
        }

        /** A DTSTART-like value: (epoch millis, zone, is a date). */
        fun time(p: Prop): Triple<Long, ZoneId, Boolean>? = runCatching {
            val v = p.value.trim()
            when {
                p.params["VALUE"].equals("DATE", true) || v.length == 8 -> {
                    val zone = zoneOf(p) ?: defaultZone
                    Triple(
                        LocalDate.parse(v.take(8), DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay(zone).toInstant().toEpochMilli(),
                        zone,
                        true,
                    )
                }

                v.endsWith(
                    "Z",
                ) -> Triple(LocalDateTime.parse(v.dropLast(1), LOCAL).toInstant(ZoneOffset.UTC).toEpochMilli(), UTC_ZONE, false)

                else -> {
                    val zone = zoneOf(p) ?: defaultZone
                    Triple(LocalDateTime.parse(v, LOCAL).atZone(zone).toInstant().toEpochMilli(), zone, false)
                }
            }
        }.getOrNull()

        data class Parsed(val event: CalendarEvent, val uid: String?, val recurrenceId: Long?)

        val parsed = blocks.mapNotNull { b ->
            fun first(name: String) = b.firstOrNull { it.name == name }
            val start = first("DTSTART")?.let(::time) ?: run {
                skipped++
                return@mapNotNull null
            }
            val (startAt, zone, allDay) = start
            val endAt = first("DTEND")?.let(::time)?.first
                ?: first("DURATION")?.let { duration(it.value) }?.let { startAt + it }
                ?: if (allDay) {
                    LocalDate.ofInstant(
                        Instant.ofEpochMilli(startAt),
                        zone,
                    ).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                } else {
                    startAt
                }
            val rrule = first("RRULE")?.value?.trim()?.takeIf { it.isNotEmpty() }
            if ((rrule != null && RecurrenceRule.parse(rrule) == null) || (rrule != null && first("RDATE") != null)) shownOnce++
            val startTime = Instant.ofEpochMilli(startAt).atZone(zone).toLocalTime()
            val exdates = b.filter { it.name == "EXDATE" }.flatMap { p ->
                p.value.split(',').mapNotNull { v ->
                    val (millis, exZone, isDate) = time(p.copy(value = v)) ?: return@mapNotNull null
                    // A date on a timed series skips that day: store it as the start of that day's occurrence.
                    if (isDate && !allDay) {
                        ZonedDateTime.of(
                            LocalDate.ofInstant(Instant.ofEpochMilli(millis), exZone),
                            startTime,
                            zone,
                        ).toInstant().toEpochMilli()
                    } else {
                        millis
                    }
                }
            }
            val uid = first("UID")?.value?.trim()?.takeIf { it.isNotEmpty() }
            Parsed(
                CalendarEvent(
                    uid = uid ?: newUid(),
                    title = first("SUMMARY")?.value?.let(::unescape)?.trim()?.takeIf { it.isNotEmpty() } ?: "(No title)",
                    startAt = startAt,
                    endAt = maxOf(endAt, startAt),
                    tz = zone.id,
                    allDay = allDay,
                    notes = first("DESCRIPTION")?.value?.let(::unescape)?.takeIf { it.isNotBlank() },
                    rrule = rrule,
                    exdates = exdates,
                ),
                uid,
                first("RECURRENCE-ID")?.let(::time)?.first,
            )
        }

        // A changed occurrence (RECURRENCE-ID) replaces that occurrence of its series: skip it there, keep it as its own event.
        val overrides = parsed.filter { it.recurrenceId != null && it.uid != null }.groupBy { it.uid }
        val events = parsed.map { p ->
            when {
                p.recurrenceId != null -> p.event.copy(uid = "${p.event.uid}/${p.recurrenceId}", rrule = null, exdates = emptyList())

                p.uid != null && overrides.containsKey(p.uid) ->
                    p.event.copy(exdates = (p.event.exdates + overrides.getValue(p.uid).mapNotNull { it.recurrenceId }).distinct())

                else -> p.event
            }
        }
        return Import(events, shownOnce, unknownZones.size, skipped)
    }

    /** An ICS file with [events]. [now]: the DTSTAMP. */
    fun write(events: List<CalendarEvent>, now: Long): String {
        val out = StringBuilder()
        fun line(s: String) = out.append(fold(s)).append("\r\n")
        line("BEGIN:VCALENDAR")
        line("VERSION:2.0")
        line("PRODID:-//Pebble//Pebble Calendar//EN")
        line("CALSCALE:GREGORIAN")
        val stamp = UTC.format(Instant.ofEpochMilli(now).atOffset(ZoneOffset.UTC))
        for (e in events) {
            val zone = RecurrenceExpander.zoneOf(e.tz, ZoneOffset.UTC)
            fun time(name: String, millis: Long): String = when {
                e.allDay ->
                    "$name;VALUE=DATE:" +
                        LocalDate.ofInstant(Instant.ofEpochMilli(millis), zone).format(DateTimeFormatter.BASIC_ISO_DATE)

                zone == ZoneOffset.UTC || e.tz == "UTC" || e.tz == "Z" ->
                    "$name:" +
                        UTC.format(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC))

                else -> "$name;TZID=${zone.id}:" + LOCAL.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone))
            }
            line("BEGIN:VEVENT")
            line("UID:${noBreaks(e.uid)}")
            line("DTSTAMP:$stamp")
            line(time("DTSTART", e.startAt))
            line(time("DTEND", e.endAt))
            line("SUMMARY:${escape(e.title)}")
            e.notes?.let { line("DESCRIPTION:${escape(it)}") }
            e.rrule?.let { line("RRULE:${noBreaks(it)}") }
            e.exdates.forEach { line(time("EXDATE", it)) }
            line("END:VEVENT")
        }
        line("END:VCALENDAR")
        return out.toString()
    }

    /** `NAME;P=V;Q="a;b":value` → [Prop]; null for a line without a colon. */
    private fun prop(line: String): Prop? {
        var quoted = false
        var colon = -1
        for ((i, ch) in line.withIndex()) {
            if (ch == '"') quoted = !quoted
            if (ch == ':' && !quoted) {
                colon = i
                break
            }
        }
        if (colon < 0) return null
        val head = line.substring(0, colon)
        val parts = mutableListOf<String>()
        var start = 0
        quoted = false
        for ((i, ch) in head.withIndex()) {
            if (ch == '"') quoted = !quoted
            if (ch == ';' && !quoted) {
                parts += head.substring(start, i)
                start = i + 1
            }
        }
        parts += head.substring(start)
        val params = parts.drop(1).mapNotNull { p ->
            val eq = p.indexOf('=')
            if (eq <= 0) null else p.substring(0, eq).uppercase() to p.substring(eq + 1).trim('"')
        }.toMap()
        return Prop(parts[0].trim().uppercase(), params, line.substring(colon + 1))
    }

    /** `PT1H30M`, `P1D`, `P2W`, `-PT15M` → milliseconds; null if not a duration. */
    private fun duration(v: String): Long? = runCatching {
        val s = v.trim()
        val weeks = Regex("""^([+-]?)P(\d+)W$""").find(s)
        if (weeks != null) {
            val n = weeks.groupValues[2].toLong() * 7 * 86_400_000L
            if (weeks.groupValues[1] == "-") -n else n
        } else {
            Duration.parse(s).toMillis()
        }
    }.getOrNull()

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '\\' && i + 1 < s.length) {
                out.append(
                    when (val n = s[i + 1]) {
                        'n', 'N' -> '\n'
                        else -> n
                    },
                )
                i += 2
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    private fun escape(s: String): String = s.replace(
        "\\",
        "\\\\",
    ).replace(";", "\\;").replace(",", "\\,").replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n")

    /** A value that is not text (UID, RRULE) cannot hold a line break: remove it, or the file gets a second property. */
    private fun noBreaks(s: String): String = s.replace("\r", "").replace("\n", "")

    /** RFC 5545 folding: lines of at most 75 bytes (UTF-8), continued with a space. Never splits a character. */
    private fun fold(s: String): String {
        if (s.toByteArray(Charsets.UTF_8).size <= 75) return s
        val out = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val chars = Character.charCount(cp)
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > 75) {
                out.append("\r\n ")
                bytes = 1
            }
            out.appendCodePoint(cp)
            bytes += size
            i += chars
        }
        return out.toString()
    }
}
