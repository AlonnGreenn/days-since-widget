package com.vibecoded.planner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class IcsTest {
    private val zone = ZoneId.of("Asia/Jerusalem")

    private val schedule = """
        BEGIN:VCALENDAR
        VERSION:2.0
        X-WR-CALNAME:Semester A
        BEGIN:VEVENT
        UID:linalg@uni
        SUMMARY:Linear algebra\, lecture
        DTSTART;TZID=Asia/Jerusalem:20261025T124500
        DTEND;TZID=Asia/Jerusalem:20261025T141500
        RRULE:FREQ=WEEKLY;BYDAY=SU,TU;UNTIL=20261110T235959Z
        EXDATE;TZID=Asia/Jerusalem:20261027T124500
        BEGIN:VALARM
        ACTION:DISPLAY
        DESCRIPTION:ignored
        END:VALARM
        END:VEVENT
        BEGIN:VEVENT
        UID:linalg@uni
        RECURRENCE-ID;TZID=Asia/Jerusalem:20261101T124500
        SUMMARY:Linear algebra (moved)
        DTSTART;TZID=Asia/Jerusalem:20261101T160000
        DURATION:PT1H30M
        END:VEVENT
        BEGIN:VEVENT
        UID:exam@uni
        SUMMARY:Exam
        DTSTART;VALUE=DATE:20261120
        END:VEVENT
        BEGIN:VEVENT
        UID:gone@uni
        STATUS:CANCELLED
        SUMMARY:Cancelled
        DTSTART:20261026T100000Z
        END:VEVENT
        BEGIN:VEVENT
        UID:utc@uni
        SUMMARY:A very long title that keeps going and going so that the exporter has to fold it nicely
        DTSTART:20261026T100000Z
        DTEND:20261026T110000Z
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    @Test fun parsesClassSchedule() {
        val cal = Ics.parse(schedule, zone, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31))
        assertEquals("Semester A", cal.name)
        val lectures = cal.occurrences.filter { it.uid == "linalg@uni" }.sortedBy { it.start }
        val starts = lectures.map { it.start.toString() }
        assertEquals(
            listOf(
                "2026-10-25T12:45", // Sun
                // Tue 27 excluded by EXDATE
                "2026-11-01T16:00", // Sun 1 moved by RECURRENCE-ID
                "2026-11-03T12:45",
                "2026-11-08T12:45",
                "2026-11-10T12:45", // UNTIL is inclusive
            ),
            starts,
        )
        assertEquals("Linear algebra, lecture", lectures.first().title)
        assertEquals(90, lectures.first().durationMin)
        assertEquals(90, lectures[1].durationMin)

        val exam = cal.occurrences.single { it.uid == "exam@uni" }
        assertEquals(LocalDate.of(2026, 11, 20), exam.date)
        assertTrue(cal.occurrences.none { it.uid == "gone@uni" })

        val utc = cal.occurrences.single { it.uid == "utc@uni" }
        assertEquals(LocalDateTime.of(2026, 10, 26, 12, 0), utc.start) // UTC+2 in late October
    }

    @Test fun windowLimitsOpenEndedRules() {
        val ics = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nUID:d\nSUMMARY:Daily\nDTSTART:20261026T080000\n" +
            "RRULE:FREQ=DAILY;INTERVAL=2\nEND:VEVENT\nEND:VCALENDAR"
        val cal = Ics.parse(ics, zone, LocalDate.of(2026, 10, 26), LocalDate.of(2026, 11, 4))
        assertEquals(5, cal.occurrences.size)
        val counted = ics.replace("INTERVAL=2", "COUNT=3")
        assertEquals(3, Ics.parse(counted, zone, LocalDate.of(2026, 10, 26), LocalDate.of(2027, 1, 1)).occurrences.size)
    }

    @Test fun exportRoundTrips() {
        val day = LocalDate.of(2026, 10, 26).toEpochDay()
        val tasks = listOf(
            Task(1, listOf("Read; chapter, 3"), date = day, startMin = parseClock("09:00"), durationMin = 45),
            Task(2, listOf("Exam"), date = day + 3, deadline = true),
            Task(3, listOf("not dated")),
        )
        val text = Ics.export(tasks, zone, Instant.parse("2026-10-26T00:00:00Z"))
        assertTrue(text.lines().all { it.length <= 76 })
        val back = Ics.parse(text, zone, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 1))
        assertEquals(2, back.occurrences.size)
        val read = back.occurrences.single { it.start != null }
        assertEquals("Read; chapter, 3", read.title)
        assertEquals(LocalDateTime.of(2026, 10, 26, 9, 0), read.start)
        assertEquals(45, read.durationMin)
        assertEquals(LocalDate.of(2026, 10, 29), back.occurrences.single { it.date != null }.date)
    }
}
