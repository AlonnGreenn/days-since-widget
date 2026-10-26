package com.vibecoded.planner.data

import android.content.Context
import android.util.Log
import com.vibecoded.planner.domain.AppState
import com.vibecoded.planner.domain.SERIES_HORIZON_DAYS
import com.vibecoded.planner.domain.materializeSeries
import com.vibecoded.planner.domain.morningBuckets
import com.vibecoded.planner.domain.plannerDay
import com.vibecoded.planner.system.SystemSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Single source of truth, shared by the UI and the background receivers.
 * Reads synchronously once (the file is small), writes asynchronously and atomically.
 */
class PlannerRepository private constructor(private val appContext: Context) {

    private val file = File(appContext.filesDir, "state.json")
    private val writer = Executors.newSingleThreadExecutor()
    private val pendingWrite = AtomicReference<AppState?>(null)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state

    /**
     * Applies [transform], then housekeeping: copies repeating items forward, and, when
     * [markReview] is set and nothing is left to schedule, records today's morning as done.
     */
    @Synchronized
    fun update(markReview: Boolean = false, transform: (AppState) -> AppState): AppState {
        val today = plannerDay(LocalDateTime.now())
        var next = transform(_state.value)
        next = materializeSeries(next, today.toEpochDay() + SERIES_HORIZON_DAYS, System.currentTimeMillis())
        val todayEpoch = today.toEpochDay()
        if (markReview && todayEpoch !in next.reviewedDays && morningBuckets(next.tasks, today).count == 0) {
            next = next.copy(reviewedDays = next.reviewedDays + todayEpoch)
        }
        if (next != _state.value) {
            _state.value = next
            persist(next)
        }
        SystemSync.requestSync(appContext)
        return next
    }

    fun refresh(markReview: Boolean = false): AppState = update(markReview) { it }

    private fun load(): AppState {
        if (!file.exists()) return AppState()
        return try {
            StateCodec.decode(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Could not read state; keeping a copy aside", e)
            file.copyTo(File(file.parentFile, "state.corrupt-${System.currentTimeMillis()}.json"), overwrite = true)
            AppState()
        }
    }

    private fun persist(s: AppState) {
        // Coalesce bursts (e.g. dragging a resize handle) into the latest state only.
        if (pendingWrite.getAndSet(s) != null) return
        writer.execute {
            val latest = pendingWrite.getAndSet(null) ?: return@execute
            try {
                val tmp = File(file.parentFile, "state.json.tmp")
                tmp.writeText(StateCodec.encode(latest))
                if (!tmp.renameTo(file)) {
                    file.writeText(tmp.readText())
                    tmp.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Saving failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "PlannerRepository"

        @Volatile
        private var instance: PlannerRepository? = null

        fun get(context: Context): PlannerRepository =
            instance ?: synchronized(this) {
                instance ?: PlannerRepository(context.applicationContext).also { instance = it }
            }
    }
}
