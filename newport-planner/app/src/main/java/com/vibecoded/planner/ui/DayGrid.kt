package com.vibecoded.planner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecoded.planner.domain.sundayIndex
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * [days] days starting at [today], in Sunday-first week rows. Cells are addressed by
 * their offset from today (0 = today). [counts] = open items per offset; the dots give
 * a rough sense of load, red marks a deadline.
 */
@Composable
fun DayGrid(
    today: LocalDate,
    days: Int,
    counts: Map<Int, Int>,
    deadlines: Set<Int>,
    cellHeight: Dp,
    modifier: Modifier = Modifier,
    highlighted: Int? = null,
    selected: Int? = null,
    onCoords: ((Int, LayoutCoordinates) -> Unit)? = null,
    onClick: ((Int) -> Unit)? = null,
) {
    val lead = sundayIndex(today)
    val cells: List<Int?> = List<Int?>(lead) { null } + (0 until days).toList()
    val rows = cells.chunked(7)

    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            for (label in listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")) {
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        for (row in rows) {
            Row(Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val idx: Int? = row.getOrNull(col)
                    if (idx == null) {
                        Box(Modifier.weight(1f).height(cellHeight))
                        continue
                    }
                    val date = today.plusDays(idx.toLong())
                    val count = counts[idx] ?: 0
                    val bg = when {
                        highlighted == idx -> MaterialTheme.colorScheme.tertiaryContainer
                        selected == idx -> MaterialTheme.colorScheme.secondaryContainer
                        idx == 0 -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                    var cell = Modifier
                        .weight(1f)
                        .height(cellHeight)
                        .padding(1.5.dp)
                        .background(bg, RoundedCornerShape(6.dp))
                    if (deadlines.contains(idx)) cell = cell.border(1.5.dp, DeadlineRed, RoundedCornerShape(6.dp))
                    if (onCoords != null) cell = cell.onGloballyPositioned { onCoords(idx, it) }
                    if (onClick != null) cell = cell.clickable { onClick(idx) }
                    Box(cell) {
                        Column(
                            Modifier.fillMaxSize().padding(top = 3.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            val showMonth = idx == 0 || date.dayOfMonth == 1
                            Text(
                                text = if (showMonth) {
                                    date.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + date.dayOfMonth
                                } else {
                                    date.dayOfMonth.toString()
                                },
                                fontSize = 11.sp,
                                lineHeight = 12.sp,
                                fontWeight = if (idx == 0) FontWeight.Bold else FontWeight.Normal,
                            )
                            if (count > 0) {
                                Text(
                                    text = "●".repeat(minOf(count, 4)) + if (count > 4) "+" else "",
                                    fontSize = 7.sp,
                                    lineHeight = 9.sp,
                                    color = if (deadlines.contains(idx)) DeadlineRed else MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
