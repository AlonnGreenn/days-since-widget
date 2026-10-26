package com.vibecoded.planner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecoded.planner.domain.DAY_START_MIN
import com.vibecoded.planner.domain.SLOTS_PER_DAY
import com.vibecoded.planner.domain.SLOT_MIN
import com.vibecoded.planner.domain.SNAP_MIN
import com.vibecoded.planner.domain.Task
import com.vibecoded.planner.domain.clockLabel
import com.vibecoded.planner.domain.rangeLabel
import kotlin.math.roundToInt

/** Height of one half-hour slot. Shared by the Today view and the drag overlay. */
val SLOT_H = 56.dp
val LABEL_W = 48.dp

fun minutesToDp(minutes: Int): Dp = SLOT_H * (minutes.toFloat() / SLOT_MIN)

/** The empty day: a time label + line per half hour (solid on the hour, dashed on the half). */
@Composable
fun TimelineLines(onSlotClick: ((Int) -> Unit)?) {
    val lineColor = MaterialTheme.colorScheme.outline
    Column(Modifier.fillMaxWidth()) {
        for (i in 0 until SLOTS_PER_DAY) {
            val full = (i * SLOT_MIN + DAY_START_MIN) % 60 == 0
            val base = Modifier.fillMaxWidth().height(SLOT_H)
            Row(
                (if (onSlotClick != null) base.clickable { onSlotClick(i) } else base).drawBehind {
                    drawLine(
                        color = lineColor.copy(alpha = if (full) 0.8f else 0.4f),
                        start = Offset(LABEL_W.toPx(), 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = if (full) 2f else 1f,
                        pathEffect = if (full) null else PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
                    )
                },
            ) {
                Text(
                    text = clockLabel(i * SLOT_MIN),
                    fontSize = 11.sp,
                    lineHeight = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (full) 1f else 0.6f),
                    modifier = Modifier.width(LABEL_W).padding(start = 4.dp),
                )
            }
        }
    }
}

/** Thin line marking the current time on today's timeline. */
@Composable
fun NowLine(nowMin: Int) {
    if (nowMin !in 0 until SLOTS_PER_DAY * SLOT_MIN) return
    val color = MaterialTheme.colorScheme.error
    Box(
        Modifier
            .offset(y = minutesToDp(nowMin))
            .padding(start = LABEL_W - 6.dp)
            .fillMaxWidth()
            .height(2.dp)
            .background(color),
    )
}

/** The parts of a (possibly merged) block, each on its own row so they stay distinct. */
@Composable
private fun PartsList(task: Task, maxLinesEach: Int, modifier: Modifier = Modifier) {
    Column(modifier) {
        task.parts.forEachIndexed { i, part ->
            val shaded = task.parts.size > 1 && i % 2 == 1
            Text(
                text = (if (task.parts.size > 1) "▪ " else "") + part,
                fontSize = 13.sp,
                lineHeight = 15.sp,
                maxLines = maxLinesEach,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (task.done) TextDecoration.LineThrough else null,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (shaded) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f)
                        else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0f),
                    )
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }
}

/** A non-interactive block (drawn inside the drag overlay). */
@Composable
fun StaticBlock(task: Task, highlight: Boolean = false) {
    val start = task.startMin ?: return
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (highlight) MergeBlue.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .padding(start = LABEL_W, end = 4.dp)
            .offset(y = minutesToDp(start))
            .fillMaxWidth()
            .height(minutesToDp(task.durationMin))
            .padding(vertical = 1.dp),
    ) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
            if (highlight) Text("Drop to group with:", fontSize = 10.sp, color = MergeBlue)
            PartsList(task, maxLinesEach = 1)
        }
    }
}

/**
 * A block on today's timeline. Long-press the text to move it (or drop it onto another
 * block to group them). Handles: single arrow = 30 min steps, double arrow = 15 min.
 */
@Composable
fun BlockCard(
    task: Task,
    drag: DragState,
    canPlaceFn: (start: Int, duration: Int) -> Boolean,
    onResize: (start: Int, duration: Int) -> Unit,
    onEdit: () -> Unit,
) {
    val start = task.startMin ?: return
    val pxPerMin = with(LocalDensity.current) { SLOT_H.toPx() } / SLOT_MIN
    var base by remember { mutableStateOf(Pair(0, 0)) }
    val latest by rememberUpdatedState(task)
    val canPlaceNow by rememberUpdatedState(canPlaceFn)
    val resizeNow by rememberUpdatedState(onResize)
    val tall = task.durationMin >= SLOT_MIN

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp,
        modifier = Modifier
            .padding(start = LABEL_W, end = 4.dp)
            .offset(y = minutesToDp(start))
            .fillMaxWidth()
            .height(minutesToDp(task.durationMin))
            .padding(vertical = 1.dp)
            .alpha(if (task.done) 0.55f else 1f),
    ) {
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(if (task.deadline) DeadlineRed else MaterialTheme.colorScheme.primary),
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .dragSource(task, drag)
                    .clickable { onEdit() }
                    .padding(start = 4.dp, top = 2.dp),
            ) {
                if (tall) {
                    Text(
                        rangeLabel(start, task.durationMin) + if (task.done) "  ✓" else "",
                        fontSize = 10.sp,
                        lineHeight = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
                PartsList(task, maxLinesEach = if (task.parts.size > 1) 1 else 3)
            }
            Column(
                Modifier.fillMaxHeight().padding(end = 2.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                Row {
                    for (snap in listOf(SLOT_MIN, SNAP_MIN)) {
                        ResizeHandle(
                            label = if (snap == SLOT_MIN) "▲" else "▲▲",
                            snapMin = snap,
                            pxPerMin = pxPerMin,
                            onStart = { base = Pair(latest.startMin ?: 0, latest.durationMin) },
                            onResize = { delta ->
                                val ns = base.first + delta
                                val nd = base.second - delta
                                if (nd >= SNAP_MIN && canPlaceNow(ns, nd)) resizeNow(ns, nd)
                            },
                        )
                    }
                }
                if (tall) {
                    Text(
                        "✎",
                        fontSize = 15.sp,
                        modifier = Modifier.clickable { onEdit() }.padding(horizontal = 8.dp),
                    )
                } else {
                    Spacer(Modifier.height(0.dp))
                }
                Row {
                    for (snap in listOf(SLOT_MIN, SNAP_MIN)) {
                        ResizeHandle(
                            label = if (snap == SLOT_MIN) "▼" else "▼▼",
                            snapMin = snap,
                            pxPerMin = pxPerMin,
                            onStart = { base = Pair(latest.startMin ?: 0, latest.durationMin) },
                            onResize = { delta ->
                                val nd = base.second + delta
                                if (nd >= SNAP_MIN && canPlaceNow(base.first, nd)) resizeNow(base.first, nd)
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Drag handle. [onResize] receives the total minutes moved since the drag began, snapped
 * to [snapMin], measured in root coordinates so the handle moving under the finger can't skew it.
 */
@Composable
private fun ResizeHandle(
    label: String,
    snapMin: Int,
    pxPerMin: Float,
    onStart: () -> Unit,
    onResize: (Int) -> Unit,
) {
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val startNow by rememberUpdatedState(onStart)
    val resizeNow by rememberUpdatedState(onResize)
    Box(
        modifier = Modifier
            .padding(start = 3.dp)
            .size(width = 30.dp, height = 13.dp)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), RoundedCornerShape(4.dp))
            .onGloballyPositioned { coords[0] = it }
            .pointerInput(snapMin, pxPerMin) {
                var startY = 0f
                var lastSteps = 0
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        val c = coords[0]
                        startY = if (c != null && c.isAttached) c.localToRoot(offset).y else 0f
                        lastSteps = 0
                        startNow()
                    },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        val c = coords[0]
                        val y = if (c != null && c.isAttached) c.localToRoot(change.position).y else startY
                        val steps = ((y - startY) / pxPerMin / snapMin).roundToInt() * snapMin
                        if (steps != lastSteps) {
                            lastSteps = steps
                            resizeNow(steps)
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 8.sp, lineHeight = 8.sp)
    }
}
