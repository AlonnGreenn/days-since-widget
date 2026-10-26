package com.vibecoded.planner.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecoded.planner.domain.AppState
import com.vibecoded.planner.domain.CALENDAR_DAYS
import com.vibecoded.planner.domain.SLOTS_PER_DAY
import com.vibecoded.planner.domain.SLOT_MIN
import com.vibecoded.planner.domain.canPlace
import com.vibecoded.planner.domain.clockLabel
import com.vibecoded.planner.domain.dayBlocks
import com.vibecoded.planner.domain.dayCounts
import com.vibecoded.planner.domain.dayItems
import com.vibecoded.planner.domain.deadlineDays
import com.vibecoded.planner.domain.rangeLabel
import com.vibecoded.planner.domain.relativeDay
import com.vibecoded.planner.domain.upcomingDeadlines
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val LONG_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMM", Locale.ENGLISH)

/** Today down to the half hour. Tap empty time for a new block; long-press a block to move or group it. */
@Composable
fun TodaySection(
    state: AppState,
    clock: Clock,
    drag: DragState,
    vm: PlannerViewModel,
    handle: Modifier,
    onEdit: (EditRequest) -> Unit,
) {
    val tasks = state.tasks
    val day = clock.todayEpoch
    val scroll = rememberScrollState()
    val slotPx = with(LocalDensity.current) { SLOT_H.toPx() }

    LaunchedEffect(day) {
        val slot = (clock.nowMin / SLOT_MIN - 1).coerceIn(0, SLOTS_PER_DAY - 1)
        scroll.scrollTo((slot * slotPx).roundToInt())
    }

    SectionHeader("Today · " + clock.today.format(LONG_DAY), handle) {
        Text(
            "now " + clockLabel(clock.nowMin),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(end = 4.dp),
        )
    }
    Box(Modifier.fillMaxWidth().height(520.dp).verticalScroll(scroll)) {
        Box(Modifier.fillMaxWidth().height(SLOT_H * SLOTS_PER_DAY)) {
            TimelineLines(onSlotClick = { slot ->
                val start = slot * SLOT_MIN
                if (canPlace(tasks, day, start, SLOT_MIN, null)) onEdit(EditRequest.NewBlock(start))
            })
            NowLine(clock.nowMin)
            for (t in dayBlocks(tasks, day)) {
                key(t.id) {
                    BlockCard(
                        task = t,
                        drag = drag,
                        canPlaceFn = { s, d -> canPlace(tasks, day, s, d, t.id) },
                        onResize = { s, d -> vm.resize(t.id, s, d) },
                        onEdit = { onEdit(EditRequest.Existing(t)) },
                    )
                }
            }
        }
    }
}

/** Next 30 days (extendable), deadlines up top, tap a day for its list. */
@Composable
fun CalendarSection(
    state: AppState,
    clock: Clock,
    drag: DragState,
    vm: PlannerViewModel,
    handle: Modifier,
    onEdit: (EditRequest) -> Unit,
) {
    val tasks = state.tasks
    val todayEpoch = clock.todayEpoch
    var days by remember { mutableIntStateOf(CALENDAR_DAYS) }
    var selected by remember { mutableStateOf<Int?>(null) }
    val counts = remember(tasks, todayEpoch, days) { dayCounts(tasks, todayEpoch, days) }
    val deadlines = remember(tasks, todayEpoch, days) { deadlineDays(tasks, todayEpoch, days) }
    val upcoming = remember(tasks, todayEpoch) { upcomingDeadlines(tasks, todayEpoch) }

    SectionHeader("Next $days days", handle) {
        if (days > CALENDAR_DAYS) TextButton(onClick = { days = CALENDAR_DAYS }) { Text("Less", fontSize = 13.sp) }
    }

    if (upcoming.isNotEmpty()) {
        Column(Modifier.padding(bottom = 6.dp)) {
            Text("Deadlines", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = DeadlineRed)
            for (t in upcoming.take(5)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "⚑ " + t.title,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Text(
                        relativeDay(t.date!!, clock.today) + (t.startMin?.let { " · " + clockLabel(it) } ?: ""),
                        fontSize = 12.sp,
                        color = DeadlineRed,
                    )
                }
            }
            if (upcoming.size > 5) Text("+${upcoming.size - 5} more", fontSize = 12.sp)
        }
    }

    DayGrid(
        today = clock.today,
        days = days,
        counts = counts,
        deadlines = deadlines,
        cellHeight = 46.dp,
        selected = selected,
        onClick = { idx -> selected = if (selected == idx) null else idx },
    )
    TextButton(onClick = { days += CALENDAR_DAYS }) { Text("Show ${CALENDAR_DAYS} more days") }

    val sel = selected
    if (sel != null && sel < days) {
        val dayEpoch = todayEpoch + sel
        val items = dayItems(tasks, dayEpoch).filter { !it.done }
        Text(
            clock.today.plusDays(sel.toLong()).format(LONG_DAY) + " · " + relativeDay(dayEpoch, clock.today),
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
        if (items.isEmpty()) {
            Text("Nothing planned.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        for (t in items) {
            key(t.id) {
                TaskRow(
                    t,
                    drag,
                    prefix = t.startMin?.let { rangeLabel(it, t.durationMin) + "  " } ?: "",
                    onEdit = { onEdit(EditRequest.Existing(t)) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        QuickAdd(onAdd = { vm.addToDay(it, dayEpoch) }, placeholder = "Add to this day…")
    }
}
