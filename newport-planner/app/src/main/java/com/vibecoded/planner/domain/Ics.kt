package com.vibecoded.planner.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One concrete occurrence read from an .ics file, already in the device zone. */
data class IcsOccurrence(
    val uid: String,
    val title: String,
    /** Set for all-day events. */
    val date: LocalDate?,
    /** Set for timed events. */
    val start: LocalDateTime?,
    val durationMin: Int,
)

data class IcsCalendar(val name: String?, val occurrences: List<IcsOccurrence>)

/**
 * Minimal iCalendar (RFC 5545) reader/writer. Covers what Google Calendar, Outlook and
 * university timetable exports use: VEVENT, TZID/UTC/floating times, all-day events,
 * DTEND/DURATION, RRULE (DAILY/WEEKLY/MONTHLY/YEARLY with INTERVAL, COUNT, UNTIL, BYDAY),
 * EXDATE, RECURRENCE-ID overrides and cancelled events.
 */
object Ics {

    private class Prop(val name: String, val params: Map<String, String>, val value: String)

    private class RawEvent(val props: List<Prop>) {
        fun first(name: String): Prop? = props.firstOrNull { it.name == name }
        fun all(name: String): List<Prop> = props.filter { it.name == name }
    }

    private const val MAX_ITERATIONS = 5000
    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
    private val DATETIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DURATION_RE =
        Regex("""^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""")

    fun parse(text: String, zone: ZoneId, windowStart: LocalDate, windowEnd: LocalDate): IcsCalendar {
        val lines = unfold(text)
        var calName: String? = null
        val events = mutableListOf<RawEvent>()
        var current: MutableList<Prop>? = null
        var depth = 0 // nesting inside VEVENT (VALARM etc.)
        for (line in lines) {
            val p = parseProp(line) ?: continue
            when {
                p.name == "BEGIN" && p.value.equals("VEVENT", true) && current == null -> {
                    current = mutableListOf(); depth = 0
                }
                p.name == "BEGIN" && current != null -> depth++
                p.name == "END" && current != null && depth > 0 -> depth--
                p.name == "END" && p.value.equals("VEVENT", true) && current != null -> {
                    events += RawEvent(current); current = null
                }
                current != null && depth == 0 -> current += p
                current == null && p.name == "X-WR-CALNAME" -> calName = unescape(p.value).trim().ifEmpty { null }
            }
        }

        // RECURRENCE-ID events replace one occurrence of their master event.
        val overridden = HashMap<String, MutableSet<Instant>>()
        for (e in events) {
            val rid = e.first("RECURRENCE-ID") ?: continue
            val uid = e.first("UID")?.value ?: continue
            val t = parseTime(rid, zone) ?: continue
            overridden.getOrPut(uid) { mutableSetOf() } += t.instant
        }

        val out = mutableListOf<IcsOccurrence>()
        for ((index, e) in events.withIndex()) {
            if (e.first("STATUS")?.value.equals("CANCELLED", true)) continue
            val startProp = e.first("DTSTART") ?: continue
            val start = parseTime(startProp, zone) ?: continue
            val uid = e.first("UID")?.value ?: "event-$index"
            val title = e.first("SUMMARY")?.let { unescape(it.value).trim() }?.ifEmpty { null } ?: "(untitled)"
            val durationMin = eventDuration(e, start, zone)
            val isOverride = e.first("RECURRENCE-ID") != null
            val rule = if (isOverride) null else e.first("RRULE")?.let { parseRule(it.value) }
            val excluded = HashSet<Instant>()
            for (ex in e.all("EXDATE")) {
                for (v in ex.value.split(',')) {
                    parseTime(Prop("EXDATE", ex.params, v.trim()), zone)?.let { excluded += it.instant }
                }
            }
            if (!isOverride) overridden[uid]?.let { excluded += it }

            for (z in expand(start.zoned, rule, windowEnd)) {
                if (z.toInstant() in excluded) continue
                val local = z.withZoneSameInstant(zone).toLocalDateTime()
                if (start.allDay) {
                    val d = z.toLocalDate()
                    if (d < windowStart || d > windowEnd) continue
                    out += IcsOccurrence(uid, title, d, null, durationMin)
                } else {
                    val d = local.toLocalDate()
                    if (d < windowStart || d > windowEnd) continue
                    out += IcsOccurrence(uid, title, null, local, durationMin)
                }
            }
        }
        return IcsCalendar(calName, out)
    }

    fun export(tasks: List<Task>, zone: ZoneId, now: Instant, calendarName: String = "Planner"): String {
        val lines = mutableListOf(
            "BEGIN:VCALENDAR",
            "VERSION:2.0",
            "PRODID:-//vibecoded//planner//EN",
            "CALSCALE:GREGORIAN",
            "X-WR-CALNAME:" + escape(calendarName),
        )
        val stamp = formatUtc(now)
        for (t in tasks) {
            val day = t.date ?: continue
            lines += "BEGIN:VEVENT"
            lines += "UID:planner-${t.id}@vibecoded"
            lines += "DTSTAMP:$stamp"
            val start = t.startMin
            if (start != null) {
                val begin = plannerDateTime(day, start).atZone(zone).toInstant()
                lines += "DTSTART:" + formatUtc(begin)
                lines += "DTEND:" + formatUtc(begin.plusSeconds(t.durationMin * 60L))
            } else {
                val d = LocalDate.ofEpochDay(day)
                lines += "DTSTART;VALUE=DATE:" + d.format(DATE_FMT)
                lines += "DTEND;VALUE=DATE:" + d.plusDays(1).format(DATE_FMT)
            }
            lines += "SUMMARY:" + escape(t.title)
            if (t.parts.size > 1) lines += "DESCRIPTION:" + escape(t.parts.joinToString("\n") { "- $it" })
            if (t.deadline) lines += "CATEGORIES:Deadline"
            lines += "END:VEVENT"
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n") { fold(it) } + "\r\n"
    }

    // ---- parsing helpers ----

    private class Time(val zoned: ZonedDateTime, val allDay: Boolean) {
        val instant: Instant get() = zoned.toInstant()
    }

    private fun unfold(text: String): List<String> {
        val out = mutableListOf<StringBuilder>()
        for (raw in text.split("\r\n", "\n", "\r")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) {
                out.last().append(raw, 1, raw.length)
            } else if (raw.isNotEmpty()) {
                out += StringBuilder(raw)
            }
        }
        return out.map { it.toString() }
    }

    private fun parseProp(line: String): Prop? {
        var inQuotes = false
        var colon = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') inQuotes = !inQuotes
            if (c == ':' && !inQuotes) { colon = i; break }
        }
        if (colon <= 0) return null
        val head = line.substring(0, colon).split(';')
        val params = head.drop(1).mapNotNull {
            val eq = it.indexOf('=')
            if (eq <= 0) null else it.substring(0, eq).uppercase() to it.substring(eq + 1).trim('"')
        }.toMap()
        return Prop(head[0].uppercase(), params, line.substring(colon + 1))
    }

    private fun parseTime(p: Prop, zone: ZoneId): Time? {
        val v = p.value.trim()
        return try {
            if (p.params["VALUE"].equals("DATE", true) || v.length == 8) {
                val d = LocalDate.parse(v.take(8), DATE_FMT)
                Time(d.atStartOfDay(zone), true)
            } else {
                val utc = v.endsWith("Z")
                val ldt = LocalDateTime.parse(v.removeSuffix("Z").take(15), DATETIME_FMT)
                val src: ZoneId = when {
                    utc -> ZoneOffset.UTC
                    p.params["TZID"] != null -> zoneOrNull(p.params["TZID"]!!) ?: zone
                    else -> zone
                }
                Time(ldt.atZone(src), false)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun zoneOrNull(tzid: String): ZoneId? =
        try { ZoneId.of(tzid.trim().removePrefix("/")) } catch (e: Exception) { null }

    private fun eventDuration(e: RawEvent, start: Time, zone: ZoneId): Int {
        val end = e.first("DTEND")?.let { parseTime(it, zone) }
        val minutes = when {
            end != null -> (end.instant.epochSecond - start.instant.epochSecond) / 60
            e.first("DURATION") != null -> parseDuration(e.first("DURATION")!!.value) ?: -1L
            else -> -1L
        }
        return when {
            minutes > 0 -> minutes.coerceAtMost(MINUTES_PER_DAY.toLong()).toInt()
            start.allDay -> MINUTES_PER_DAY
            else -> DEFAULT_DURATION
        }
    }

    private fun parseDuration(v: String): Long? {
        val m = DURATION_RE.matchEntire(v.trim()) ?: return null
        fun g(i: Int) = m.groupValues[i].toLongOrNull() ?: 0L
        val total = g(2) * 7 * 1440 + g(3) * 1440 + g(4) * 60 + g(5) + g(6) / 60
        return if (m.groupValues[1] == "-") -total else total
    }

    private fun parseRule(v: String): Map<String, String> =
        v.split(';').mapNotNull {
            val eq = it.indexOf('=')
            if (eq <= 0) null else it.substring(0, eq).uppercase().trim() to it.substring(eq + 1).trim()
        }.toMap()

    private val DAY_CODES = mapOf(
        "MO" to DayOfWeek.MONDAY, "TU" to DayOfWeek.TUESDAY, "WE" to DayOfWeek.WEDNESDAY,
        "TH" to DayOfWeek.THURSDAY, "FR" to DayOfWeek.FRIDAY, "SA" to DayOfWeek.SATURDAY,
        "SU" to DayOfWeek.SUNDAY,
    )

    /** Expands a (possibly repeating) start time into occurrences up to [windowEnd]. */
    private fun expand(start: ZonedDateTime, rule: Map<String, String>?, windowEnd: LocalDate): List<ZonedDateTime> {
        val freq = rule?.get("FREQ")?.uppercase() ?: return listOf(start)
        val interval = (rule["INTERVAL"]?.toIntOrNull() ?: 1).coerceAtLeast(1).toLong()
        val count = rule["COUNT"]?.toIntOrNull()
        val until: Instant? = rule["UNTIL"]?.let { u ->
            parseTime(Prop("UNTIL", emptyMap(), u), start.zone)?.let {
                if (it.allDay) it.zoned.plusDays(1).minusSeconds(1).toInstant() else it.instant
            }
        }
        val out = mutableListOf<ZonedDateTime>()
        var emitted = 0
        var iterations = 0

        /** Returns false once the rule is exhausted or past the window. */
        fun offer(z: ZonedDateTime): Boolean {
            if (until != null && z.toInstant().isAfter(until)) return false
            if (count != null && emitted >= count) return false
            if (z.toLocalDate() > windowEnd.plusDays(1)) return false
            emitted++
            out += z
            return true
        }

        when (freq) {
            "WEEKLY" -> {
                val days = rule["BYDAY"]?.split(',')
                    ?.mapNotNull { DAY_CODES[it.trim().takeLast(2).uppercase()] }
                    ?.sortedBy { it.value }
                    ?.ifEmpty { null }
                    ?: listOf(start.dayOfWeek)
                val weekStart = start.toLocalDate().minusDays((start.dayOfWeek.value - 1).toLong())
                var w = 0L
                loop@ while (iterations++ < MAX_ITERATIONS) {
                    for (dow in days) {
                        val date = weekStart.plusWeeks(w).plusDays((dow.value - 1).toLong())
                        val cand = ZonedDateTime.of(date, start.toLocalTime(), start.zone)
                        if (cand.isBefore(start)) continue
                        if (!offer(cand)) break@loop
                    }
                    w += interval
                }
            }
            "DAILY", "MONTHLY", "YEARLY" -> {
                var k = 0L
                while (iterations++ < MAX_ITERATIONS) {
                    val cand = when (freq) {
                        "DAILY" -> start.plusDays(k * interval)
                        "MONTHLY" -> start.plusMonths(k * interval)
                        else -> start.plusYears(k * interval)
                    }
                    if (!offer(cand)) break
                    k++
                }
            }
            else -> out += start
        }
        return out
    }

    // ---- writing helpers ----

    private fun formatUtc(i: Instant): String = DATETIME_FMT.format(i.atOffset(ZoneOffset.UTC)) + "Z"

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    private fun unescape(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                val n = s[i + 1]
                sb.append(if (n == 'n' || n == 'N') '\n' else n)
                i += 2
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    /** Lines longer than 75 characters continue on the next line after a space. */
    private fun fold(line: String): String {
        if (line.length <= 74) return line
        val sb = StringBuilder(line.substring(0, 74))
        var i = 74
        while (i < line.length) {
            val end = minOf(i + 73, line.length)
            sb.append("\r\n ").append(line, i, end)
            i = end
        }
        return sb.toString()
    }
}
