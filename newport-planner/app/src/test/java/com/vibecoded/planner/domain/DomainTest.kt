package com.vibecoded.planner.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class TimeTest {
    @Test fun labels() {
        assertEquals("05:30", clockLabel(0))
        assertEquals("06:00", clockLabel(30))
        assertEquals("05:29", clockLabel(1439))
        assertEquals("12:45–14:15", rangeLabel(parseClock("12:45")!!, 90))
    }

    @Test fun parsing() {
        assertEquals(20 * 60 + 15 - 330, parseClock("20:15"))
        assertEquals(0, parseClock("05:30"))
        assertEquals(1440 - 330 + 60, parseClock("01:00"))
        assertNull(parseClock("25:00"))
        assertNull(parseClock("abc"))
        assertEquals(777, parseClock(clockLabel(777)))
    }

    @Test fun dayBoundaryIs0530() {
        assertEquals(LocalDate.of(2026, 10, 26), plannerDay(LocalDateTime.of(2026, 10, 27, 5, 29)))
        assertEquals(LocalDate.of(2026, 10, 27), plannerDay(LocalDateTime.of(2026, 10, 27, 5, 30)))
        val (d, m) = toPlanner(LocalDateTime.of(2026, 10, 27, 1, 0))
        assertEquals(LocalDate.of(2026, 10, 26).toEpochDay(), d)
        assertEquals(LocalDateTime.of(2026, 10, 27, 1, 0), plannerDateTime(d, m))
    }

    @Test fun weeks() {
        assertEquals(0, sundayIndex(LocalDate.of(2026, 10, 25)))
        assertEquals(6, sundayIndex(LocalDate.of(2026, 10, 31)))
        val sun = LocalDate.of(2026, 10, 25).toEpochDay()
        assertEquals(sundayWeek(sun), sundayWeek(sun + 6))
        assertEquals(sundayWeek(sun) + 1, sundayWeek(sun + 7))
    }
}

class RulesTest {
    private val today = LocalDate.of(2026, 10, 26)
    private val d = today.toEpochDay()
    private fun t(id: Long, text: String, date: Long? = null, start: Int? = null, dur: Int = 30, done: Boolean = false) =
        Task(id, listOf(text), date = date, startMin = start, durationMin = dur, done = done, createdAt = id)

    private val tasks = listOf(
        t(1, "inbox"),
        t(2, "yesterday timed", d - 1, 60),
        t(3, "yesterday done", d - 1, done = true),
        t(4, "today date-only", d),
        t(5, "today block", d, 60, 60),
        t(6, "future", d + 3),
    )

    @Test fun buckets() {
        val b = morningBuckets(tasks, today)
        assertEquals(listOf(2L), b.overdue.map { it.id })
        assertEquals(listOf(4L), b.dueToday.map { it.id })
        assertEquals(listOf(1L), b.inbox.map { it.id })
        assertEquals(3, b.count)
    }

    @Test fun placementRules() {
        assertEquals(listOf(5L), dayBlocks(tasks, d).map { it.id })
        assertFalse(canPlace(tasks, d, 90, 30, null))
        assertTrue(canPlace(tasks, d, 120, 30, null))
        assertTrue(canPlace(tasks, d, 60, 60, 5))
        assertFalse(canPlace(tasks, d, 1430, 30, null))
        assertEquals(5L, blockAt(tasks, d, 100, null)?.id)
        assertNull(blockAt(tasks, d, 120, null))
    }

    @Test fun pushCounting() {
        assertEquals(1, rescheduled(tasks[1], today, d, 120).postponeCount)
        assertEquals(0, rescheduled(tasks[3], today, d, 120).postponeCount)
        assertEquals(0, rescheduled(tasks[0], today, d + 2, null).postponeCount)
        assertEquals(1, rescheduled(tasks[3], today, d + 2, null).postponeCount)
        assertEquals(30, rescheduled(t(9, "x", dur = 60), today, d, 1410).durationMin)
    }

    @Test fun mergingAndMarkdown() {
        val m = merged(tasks[4], tasks[0])
        assertEquals(listOf("today block", "inbox"), m.parts)
        assertEquals(60, m.startMin)
        val md = todoMarkdown(listOf(t(1, "a"), Task(2, listOf("b", "c"), createdAt = 2)))
        assertEquals("- [ ] a\n- [ ] b\n  - [ ] c", md)
    }

    @Test fun currentBlock() {
        assertEquals(5L, currentOrNextBlock(tasks, d, 0)?.id)
        assertEquals(5L, currentOrNextBlock(tasks, d, 119)?.id)
        assertNull(currentOrNextBlock(tasks, d, 120))
    }

    @Test fun reasons() {
        val log = reasonsLog(listOf(Task(9, listOf("x"), date = d, reason = "tired")))
        assertEquals("- 2026-10-26: x — tired", log)
    }
}

class RecurrenceAndStreakTest {
    private val sunday = LocalDate.of(2026, 10, 25).toEpochDay()

    @Test fun weeklySeriesMaterializesOnceAndRespectsDeletion() {
        var st = AppState(tasks = listOf(Task(1, listOf("Gym"), date = sunday, startMin = 60)), nextId = 2)
        st = startSeries(st, 1, RepeatKind.WEEKLY, setOf(0, 2), sunday + 13, 0)
        val dates = st.tasks.filter { it.seriesId != null }.map { it.date!! - sunday }.sorted()
        assertEquals(listOf(0L, 2L, 7L, 9L), dates)
        // delete one occurrence; regenerating further must not bring it back
        st = st.copy(tasks = st.tasks.filterNot { it.date == sunday + 2 })
        st = materializeSeries(st, sunday + 16, 0)
        val after = st.tasks.filter { it.seriesId != null }.map { it.date!! - sunday }.sorted()
        assertEquals(listOf(0L, 7L, 9L, 14L, 16L), after)
        st = stopSeries(st, st.series.first().id, sunday + 7)
        assertEquals(listOf(0L, 7L), st.tasks.map { it.date!! - sunday }.sorted())
        assertTrue(st.series.isEmpty())
    }

    @Test fun streakUsesFreezes() {
        val seven = (0L until 7L).toSet()
        assertEquals(StreakInfo(7, 1, true), streakOf(seven, 6))
        // day 7 missed, today = 8 not yet done: freeze spent, streak kept
        assertEquals(StreakInfo(7, 0, false), streakOf(seven, 8))
        // two misses with one freeze: reset
        assertEquals(0, streakOf(seven, 9).days)
        assertEquals(StreakInfo(0, 0, false), streakOf(emptySet(), 5))
    }
}
