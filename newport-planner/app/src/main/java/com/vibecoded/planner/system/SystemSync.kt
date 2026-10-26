package com.vibecoded.planner.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.vibecoded.planner.data.PlannerRepository
import com.vibecoded.planner.domain.plannerDay
import com.vibecoded.planner.domain.plannerMinute
import java.time.LocalDateTime

/**
 * Keeps everything outside the app window (notifications, badge, alarms) in line with
 * the current state. UI changes are debounced; receivers call [syncNow] directly
 * because their process may die right after onReceive returns.
 */
object SystemSync {
    private const val DEBOUNCE_MS = 600L
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null

    fun requestSync(context: Context) {
        val app = context.applicationContext
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable { syncNow(app) }
        pending = r
        handler.postDelayed(r, DEBOUNCE_MS)
    }

    fun syncNow(context: Context) {
        val state = PlannerRepository.get(context).state.value
        val now = LocalDateTime.now()
        val today = plannerDay(now)
        val nowMin = plannerMinute(now)
        Notifier.updateMorning(context, state, today)
        Notifier.updateNextBlock(context, state, today.toEpochDay(), nowMin)
        AlarmScheduler.schedule(context, state, now)
    }
}
