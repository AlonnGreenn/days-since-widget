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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Single source of truth, shared by the UI and the background receivers.
 * Reads synchronously once (the file is small), writes asynchronously and atomically.
 */
class PlannerRepository private constructor(private val appContext: Context) {

    private val file = File(appContext.filesDir, "state.json")
    private val writer = Executors.newSingleThreadExecutor()
    private val pendingWrite = AtomicReference<AppState?>(null)
    private val internalDirty = AtomicBoolean(false)

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state

    init {
        // Bring the vault copy up to date on every start (e.g. right after an update or reboot).
        persist(_state.value, internal = !file.exists() && !_state.value.isPristine())
    }

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
        } else {
            // The vault views depend on the date too (e.g. after 05:30), so refresh them anyway.
            persist(next, internal = false)
        }
        SystemSync.requestSync(appContext)
        return next
    }

    fun refresh(markReview: Boolean = false): AppState = update(markReview) { it }

    /**
     * After a reinstall the app starts empty: once "All files access" is granted, take the
     * backup from the vault. Does nothing if the app already holds any data.
     */
    @Synchronized
    fun restoreFromVaultIfEmpty(): Boolean {
        if (!_state.value.isPristine()) return false
        val backup = VaultMirror.readBackup() ?: return false
        _state.value = backup
        persist(backup)
        SystemSync.requestSync(appContext)
        return true
    }

    /** Re-writes the vault copy now (e.g. right after access was granted). */
    fun mirrorNow() = persist(_state.value, internal = false)

    private fun load(): AppState {
        if (!file.exists()) return VaultMirror.readBackup() ?: AppState()
        return try {
            StateCodec.decode(file.readText())
        } catch (e: Exception) {
            Log.e(TAG, "Could not read state; keeping a copy aside", e)
            file.copyTo(File(file.parentFile, "state.corrupt-${System.currentTimeMillis()}.json"), overwrite = true)
            AppState()
        }
    }

    /**
     * Saves [s] to the app's own file (unless [internal] is false), then mirrors it to the vault.
     * Bursts (e.g. dragging a resize handle) are coalesced into the latest state only.
     */
    private fun persist(s: AppState, internal: Boolean = true) {
        if (internal) internalDirty.set(true)
        if (pendingWrite.getAndSet(s) != null) return
        writer.execute {
            val latest = pendingWrite.getAndSet(null) ?: return@execute
            val json = StateCodec.encode(latest)
            if (internalDirty.getAndSet(false)) {
                try {
                    val tmp = File(file.parentFile, "state.json.tmp")
                    tmp.writeText(json)
                    if (!tmp.renameTo(file)) {
                        file.writeText(tmp.readText())
                        tmp.delete()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Saving failed", e)
                }
            }
            VaultMirror.write(latest, json, plannerDay(LocalDateTime.now()))
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
