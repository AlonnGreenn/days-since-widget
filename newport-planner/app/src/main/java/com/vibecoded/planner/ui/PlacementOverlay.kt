package com.vibecoded.planner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecoded.planner.domain.AppState
import com.vibecoded.planner.domain.CALENDAR_DAYS
import com.vibecoded.planner.domain.MINUTES_PER_DAY
import com.vibecoded.planner.domain.SLOTS_PER_DAY
import com.vibecoded.planner.domain.SLOT_MIN
import com.vibecoded.planner.domain.Task
import com.vibecoded.planner.domain.blockAt
import com.vibecoded.planner.domain.canPlace
import com.vibecoded.planner.domain.clockLabel
import com.vibecoded.planner.domain.dayBlocks
import com.vibecoded.planner.domain.dayCounts
import com.vibecoded.planner.domain.dayItems
import com.vibecoded.planner.domain.deadlineDays
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

private const val EDGE_ZONE_DP = 72
private const val MAX_SCROLL_PX_PER_FRAME = 42f
private const val PEEK_DELAY_MS = 550L

/** What a drop at the current finger position would do. */
private sealed interface DropTarget {
    data class Slot(val startMin: Int, val ok: Boolean) : DropTarget
    data class Merge(val target: Task) : DropTarget
    data class Day(val offset: Int) : DropTarget
    data object Inbox : DropTarget
    data object Cancel : DropTarget
}

/**
 * Shown over everything while an item is dragged: only the places it can go.
 *  - today's timeline (drop on empty time = block there, drop on a block = group them)
 *  - the next 30 days (hover to peek inside a day)
 *  - back to the to-do list, or cancel
 * Holding the finger in the shaded band at the top/bottom of the timeline scrolls it,
 * faster the closer to the edge.
 */
@Composable
fun PlacementOverlay(state: AppState, clock: Clock, drag: DragState, vm: PlannerViewModel) {
    val dragged = drag.task ?: return
    val tasks = state.tasks
    val today = clock.today
    val todayEpoch = clock.todayEpoch
    val pointer = drag.pointer
    val density = LocalDensity.current
    val slotPx = with(density) { SLOT_H.toPx() }
    val zonePx = with(density) { EDGE_ZONE_DP.dp.toPx() }
    val placeDuration = dragged.durationMin

    val scroll = rememberScrollState()
    val viewport = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val content = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val inboxZone = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val cancelZone = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val dayCoords = remember { mutableStateMapOf<Int, LayoutCoordinates>() }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    var peekDay by remember { mutableStateOf<Int?>(null) }

    fun contains(c: LayoutCoordinates?, p: Offset) = c != null && c.isAttached && c.boundsInRoot().contains(p)

    fun targetAt(p: Offset): DropTarget? {
        if (contains(cancelZone[0], p)) return DropTarget.Cancel
        if (contains(inboxZone[0], p)) return DropTarget.Inbox
        dayCoords.entries.firstOrNull { contains(it.value, p) }?.let { return DropTarget.Day(it.key) }
        val c = content[0]
        if (contains(viewport[0], p) && c != null && c.isAttached) {
            val minute = ((p.y - c.positionInRoot().y) / slotPx * SLOT_MIN).toInt()
            if (minute !in 0 until MINUTES_PER_DAY) return null
            blockAt(tasks, todayEpoch, minute, dragged.id)?.let { return DropTarget.Merge(it) }
            val slot = floor(minute.toFloat() / SLOT_MIN).toInt() * SLOT_MIN
            val dur = placeDuration.coerceAtMost(MINUTES_PER_DAY - slot)
            return DropTarget.Slot(slot, canPlace(tasks, todayEpoch, slot, dur, dragged.id))
        }
        return null
    }

    // Reading scroll.value keeps the highlight in sync while auto-scrolling with a still finger.
    val target = if (scroll.value >= 0) targetAt(pointer) else null
    val hoverDay = (target as? DropTarget.Day)?.offset

    SideEffect {
        drag.dropHandler = { task, p ->
            when (val t = targetAt(p)) {
                is DropTarget.Slot -> if (t.ok) vm.place(task.id, todayEpoch, t.startMin)
                is DropTarget.Merge -> vm.mergeInto(task.id, t.target.id)
                is DropTarget.Day -> vm.place(task.id, todayEpoch + t.offset, null)
                DropTarget.Inbox -> if (task.date != null) vm.toInbox(task.id)
                DropTarget.Cancel, null -> Unit
            }
        }
    }

    LaunchedEffect(Unit) {
        val focusMin = if (dragged.date == todayEpoch && dragged.startMin != null) dragged.startMin else clock.nowMin
        val slot = (focusMin / SLOT_MIN - 2).coerceIn(0, SLOTS_PER_DAY - 1)
        scroll.scrollTo((slot * slotPx).roundToInt())
    }

    LaunchedEffect(hoverDay) {
        peekDay = null
        if (hoverDay != null) {
            delay(PEEK_DELAY_MS)
            peekDay = hoverDay
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(16)
            val vpc = viewport[0]
            if (vpc == null || !vpc.isAttached) continue
            val vp = vpc.boundsInRoot()
            val p = drag.pointer
            if (p.x < vp.left || p.x > vp.right) continue
            val delta = when {
                p.y >= vp.top && p.y < vp.top + zonePx -> -MAX_SCROLL_PX_PER_FRAME * (1f - (p.y - vp.top) / zonePx)
                p.y <= vp.bottom && p.y > vp.bottom - zonePx -> MAX_SCROLL_PX_PER_FRAME * (1f - (vp.bottom - p.y) / zonePx)
                else -> 0f
            }
            if (delta != 0f) scroll.scrollBy(delta)
        }
    }

    val counts = remember(tasks, todayEpoch) { dayCounts(tasks, todayEpoch, CALENDAR_DAYS) }
    val deadlines = remember(tasks, todayEpoch) { deadlineDays(tasks, todayEpoch, CALENDAR_DAYS) }
    val bandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onGloballyPositioned { overlayOrigin = it.positionInRoot() },
    ) {
        Column(Modifier.fillMaxSize()) {
            Text(
                when (target) {
                    is DropTarget.Slot -> if (target.ok) "Today at ${clockLabel(target.startMin)}" else "Doesn't fit there"
                    is DropTarget.Merge -> "Group with “${target.target.title}”"
                    is DropTarget.Day -> today.plusDays(target.offset.toLong()).format(DAY_FMT) + " (no hour)"
                    DropTarget.Inbox -> "Back to the to-do list"
                    DropTarget.Cancel -> "Cancel"
                    null -> "Drop on a time today, a day, or the bottom bar"
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { viewport[0] = it },
            ) {
                Column(Modifier.verticalScroll(scroll)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(SLOT_H * SLOTS_PER_DAY)
                            .onGloballyPositioned { content[0] = it },
                    ) {
                        TimelineLines(onSlotClick = null)
                        NowLine(clock.nowMin)
                        val mergeId = (target as? DropTarget.Merge)?.target?.id
                        for (b in dayBlocks(tasks, todayEpoch)) {
                            if (b.id != dragged.id) StaticBlock(b, highlight = b.id == mergeId)
                        }
                        if (target is DropTarget.Slot) {
                            Box(
                                Modifier
                                    .padding(start = LABEL_W, end = 4.dp)
                                    .offset(y = minutesToDp(target.startMin))
                                    .fillMaxWidth()
                                    .height(minutesToDp(placeDuration.coerceAtMost(MINUTES_PER_DAY - target.startMin)))
                                    .background(
                                        (if (target.ok) OkGreen else DeadlineRed).copy(alpha = 0.35f),
                                        RoundedCornerShape(6.dp),
                                    ),
                            )
                        }
                    }
                }
                Text(
                    "▲  hold here to scroll up",
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(EDGE_ZONE_DP.dp)
                        .background(bandColor)
                        .padding(top = 4.dp),
                )
                Text(
                    "▼  hold here to scroll down",
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(EDGE_ZONE_DP.dp)
                        .background(bandColor)
                        .padding(top = 44.dp),
                )
            }
            DayGrid(
                today = today,
                days = CALENDAR_DAYS,
                counts = counts,
                deadlines = deadlines,
                cellHeight = 36.dp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                highlighted = hoverDay,
                onCoords = { idx, c -> dayCoords[idx] = c },
            )
            DayPeek(state, clock, peekDay)
            Row(Modifier.fillMaxWidth().height(56.dp)) {
                ZoneBox(
                    "↩  To-do list",
                    active = target == DropTarget.Inbox,
                    modifier = Modifier.weight(1f).onGloballyPositioned { inboxZone[0] = it },
                )
                ZoneBox(
                    "✕  Cancel",
                    active = target == DropTarget.Cancel,
                    modifier = Modifier.weight(1f).onGloballyPositioned { cancelZone[0] = it },
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 8.dp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .offset {
                    IntOffset(
                        (pointer.x - overlayOrigin.x).roundToInt() - 140,
                        (pointer.y - overlayOrigin.y).roundToInt() - 150,
                    )
                },
        ) {
            Text(
                dragged.title,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

@Composable
private fun DayPeek(state: AppState, clock: Clock, offset: Int?) {
    Box(Modifier.fillMaxWidth().height(76.dp).padding(horizontal = 12.dp)) {
        if (offset == null) {
            Text(
                "Hold over a day to see what's already there",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterStart),
            )
            return@Box
        }
        val items = dayItems(state.tasks, clock.todayEpoch + offset).filter { !it.done }
        Column {
            Text(clock.today.plusDays(offset.toLong()).format(DAY_FMT), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            if (items.isEmpty()) Text("Nothing planned.", fontSize = 12.sp)
            for (t in items.take(3)) {
                Text(
                    (t.startMin?.let { clockLabel(it) + "  " } ?: "") + (if (t.deadline) "⚑ " else "") + t.title,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            if (items.size > 3) Text("+${items.size - 3} more", fontSize = 12.sp)
        }
    }
}

@Composable
private fun ZoneBox(label: String, active: Boolean, modifier: Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .padding(4.dp)
            .background(
                if (active) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(label)
    }
}
