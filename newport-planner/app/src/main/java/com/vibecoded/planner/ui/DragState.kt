package com.vibecoded.planner.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import com.vibecoded.planner.domain.Task

/**
 * Screen-wide drag state. The finger position is kept in ROOT coordinates, so the
 * placement overlay can hit-test targets regardless of where the drag started.
 */
class DragState {
    var task by mutableStateOf<Task?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set

    val active: Boolean get() = task != null

    /** Installed by the placement overlay; called once when the finger lifts. */
    var dropHandler: (Task, Offset) -> Unit = { _, _ -> }

    fun start(t: Task, rootPos: Offset) {
        task = t
        pointer = rootPos
    }

    fun moveTo(rootPos: Offset) {
        pointer = rootPos
    }

    fun end() {
        val t = task
        val p = pointer
        task = null
        if (t != null) dropHandler(t, p)
    }

    fun cancel() {
        task = null
    }
}

/** Long-press, then drag this item to wherever the placement overlay allows. */
fun Modifier.dragSource(task: Task, drag: DragState): Modifier = composed {
    val haptics = LocalHapticFeedback.current
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val current by rememberUpdatedState(task)
    this
        .onGloballyPositioned { coords[0] = it }
        .pointerInput(Unit) {
            detectDragGesturesAfterLongPress(
                onDragStart = { offset ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val c = coords[0]
                    drag.start(current, if (c != null && c.isAttached) c.localToRoot(offset) else offset)
                },
                onDrag = { change, _ ->
                    change.consume()
                    val c = coords[0]
                    if (c != null && c.isAttached) drag.moveTo(c.localToRoot(change.position))
                },
                onDragEnd = { drag.end() },
                onDragCancel = { drag.cancel() },
            )
        }
}
