package com.vibecoded.planner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class VaultExportTest {
    private val today = LocalDate.of(2026, 11, 21)
    private val t = today.toEpochDay()
    private val zone = ZoneId.of("Asia/Jerusalem")

    private val state = AppState(
        tasks = listOf(
            Task(1, listOf("Buy stamps"), createdAt = 1),
            Task(2, listOf("Lecture"), date = t, startMin = parseClock("12:45"), durationMin = 90, calendarId = 9),
            Task(3, listOf("Read ch. 3", "Exercises"), date = t, startMin = parseClock("08:00"), done = true),
            Task(4, listOf("Exam"), date = t + 5, deadline = true),
            Task(5, listOf("Old thing"), date = t - 2, postponeCount = 3, reason = "too tired"),
        ),
        calendars = listOf(CalendarSource(9, "Uni", 0)),
        reviewedDays = setOf(t - 1, t),
    )

    @Test fun dayNote() {
        val f = vaultFiles(state, today, zone)
        val day = f["days/$today.md"]!!
        assertTrue(day.startsWith("---\ndate: 2026-11-21\nweekday: Saturday\nitems: 2\ndone: 1\nmorning_review: true\n"))
        assertTrue(day.contains("- [x] 08:00–08:30 Read ch. 3\n  - Exercises\n"))
        assertTrue(day.contains("- [ ] 12:45–14:15 Lecture (Uni)\n"))
        val old = f["days/${today.minusDays(2)}.md"]!!
        assertTrue(old.contains("- [ ] Old thing (pushed 3×)\n  - why not done: too tired"))
    }

    @Test fun overview() {
        val p = vaultFiles(state, today, zone)["Planner.md"]!!
        assertTrue(p.contains("streak: 2\n"))
        assertTrue(p.contains("## To-do list\n- [ ] Buy stamps\n"))
        assertTrue(p.contains("## Unfinished from earlier days"))
        assertTrue(p.contains("- 2026-11-26 Exam"))
        assertTrue(p.contains("### [[planner/days/2026-11-26|Thursday 2026-11-26]]\n- [ ] Exam **deadline**"))
    }

    @Test fun emptiedDayIsRewrittenAndOldHistoryLeftAlone() {
        val f = vaultFiles(AppState(), today, zone, setOf(today.plusDays(3), today.minusDays(400)))
        assertTrue(f["days/${today.plusDays(3)}.md"]!!.contains("Nothing planned."))
        assertFalse(f.containsKey("days/${today.minusDays(400)}.md"))
    }

    @Test fun outputIsStable() {
        assertEquals(vaultFiles(state, today, zone), vaultFiles(state, today, zone))
        assertTrue(vaultFiles(state, today, zone)["calendar.ics"]!!.contains("SUMMARY:Lecture"))
    }
}
